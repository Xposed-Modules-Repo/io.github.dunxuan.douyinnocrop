package io.github.dunxuan.douyinnocrop;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 全屏内容适配器——"只处理全屏界面"的落地点。
 *
 * 两类内容、两条路径：
 *   1) 视频/动图（TextureView）：收缩 LayoutParams 为"宽高比最大内接矩形"，
 *      使 surface 比例 == 内容比例，cover 渲染等价于 fit，消除裁剪；
 *   2) 静态图文（Fresco DraweeView）：不改尺寸，改层级 scaleType 为 FIT_CENTER，
 *      由 Fresco 自己完成 letterbox——不破坏捏合缩放，多图切换自动正确。
 *
 * 门控（三层，"只处理点名的全屏界面"）：
 *   0) 评论区一律不碰：
 *      a) 视图树/Activity/params.Fragment 类名含 comment；
 *      b) 当前位于某个可见评论 Fragment 的视图子树内
 *         （CommentFeedDialogFragment → CommentFeedFragment，覆盖 params
 *          判据的时序竞态——绑定瞬间 commentFragment 可能尚未回填）；
 *      c) 位于全屏级的独立窗口（评论面板是 Dialog，decor 与 Activity 不同）；
 *   1) 场所白名单：主 Feed（*MainActivity）、详情播放器（*DetailActivity）、
 *      清屏全屏（cleanActive）——搜索列表/广告/未知上下文全部还原；
 *   2) 图片类内容在详情图文浏览态（面板可见或详情非清屏）不触碰；
 *   3) 容器必须全屏级（宽 ≥ 窗口 85% 且高 ≥ 窗口 40%）；
 *   容器尺寸变化时（进/出全屏、面板开合）通过持久 layout 监听自动重算/还原。
 *
 * 状态按 View 记账（FitState 弱表），attach 补偿只用该 View 自己的状态；
 * 原始尺寸/原始 scaleType 均记录于弱表，还原时先比对是否仍是我们写入的值。
 * 全部操作保证在主线程执行。
 */
final class FitApplier {

    private static final String TAG = "DouYinNoCrop";

    /** 某条内容/某个 View 的收缩状态。 */
    static final class FitState {
        final float aspect;
        final boolean imagePost;
        /** true = 静态图文 DraweeView，走 scaleType 适配而非 LayoutParams。 */
        final boolean draweeFit;
        /** 内容 aid（帖子 id）：用于识别 View 复用到了新内容，防止真实尺寸残留。 */
        final String aid;
        /** 绑定时的播放器对象（BaseFeedPlayerView，非 View）：attach 补偿时验证
         *  "这个 View 确实是该播放器的渲染 View"，防止评论区等无关 TextureView
         *  误吃 pending（评论区 holder 不用 VideoItemParams，判据全会落空）。 */
        final WeakReference<Object> player;
        final WeakReference<Object> params;

        FitState(float aspect, boolean imagePost, Object params) {
            this(aspect, imagePost, params, false, null, null);
        }

        FitState(float aspect, boolean imagePost, Object params, boolean draweeFit, String aid) {
            this(aspect, imagePost, params, draweeFit, aid, null);
        }

        FitState(float aspect, boolean imagePost, Object params, boolean draweeFit,
                 String aid, Object player) {
            this.aspect = aspect;
            this.imagePost = imagePost;
            this.draweeFit = draweeFit;
            this.aid = aid;
            this.player = (player != null) ? new WeakReference<>(player) : null;
            this.params = (params != null) ? new WeakReference<>(params) : null;
        }
    }

    /** video → 当前状态；弱引用，仅主线程读写。 */
    private static final Map<View, FitState> STATES = new WeakHashMap<>();

    /** video → {原始宽, 原始高, 本模块写入的宽, 本模块写入的高}；仅主线程。 */
    private static final Map<View, int[]> ORIGINALS = new WeakHashMap<>();

    /** DraweeView → {原始 ImageView scaleType, 原始 hierarchy scaleType}；仅主线程。 */
    private static final Map<View, Object[]> SCALE_ORIGS = new WeakHashMap<>();

    /** hierarchy 对象 → 所属的已跟踪图文 View（拦截 hierarchy 重设用）。 */
    private static final Map<Object, View> HIERARCHY_VIEWS = new WeakHashMap<>();

    /** 该 View 是否是我们跟踪的图文（drawee）内容——setScaleType 拦截判据。 */
    static boolean isTrackedDrawee(View v) {
        if (v == null) {
            return false;
        }
        FitState s = STATES.get(v);
        return s != null && s.draweeFit;
    }

    /**
     * ImageView.setScaleType 拦截（钩子回调，主线程）：
     * 已跟踪的图文 View 被设成非 FIT_CENTER 时（抖音绑定/翻页会重设回 cover），
     * 就地替换为 FIT_CENTER，杜绝"设完又被改回去"的竞态。
     * 返回 null = 不替换，按原值执行。
     */
    static Object enforceIvScale(View v, Object requested) {
        if (v == null || requested == null || !isTrackedDrawee(v)) {
            return null;
        }
        Object fit = ImageView.ScaleType.FIT_CENTER;
        if (fit.equals(requested)) {
            return null;
        }
        Log.d(TAG, "enforce-fit iv: " + requested + " -> FIT_CENTER view="
                + v.getClass().getName());
        return fit;
    }

    /**
     * GenericDraweeHierarchy.setActualImageScaleType 拦截：
     * 该 hierarchy 属于已跟踪图文且请求非 FIT → 替换为 FIT_CENTER 常量。
     * 返回 null = 不替换。
     */
    static Object enforceHierarchyScale(Object hierarchy, Object requested) {
        if (hierarchy == null || requested == null) {
            return null;
        }
        View v;
        synchronized (HIERARCHY_VIEWS) {
            v = HIERARCHY_VIEWS.get(hierarchy);
        }
        if (v == null || !isTrackedDrawee(v)) {
            return null;
        }
        Object fit = fitCenterConst;
        if (fit == null) {
            return null;
        }
        if (fit.equals(requested)) {
            return null;
        }
        Log.d(TAG, "enforce-fit hierarchy: " + requested + " -> FIT_CENTER view="
                + v.getClass().getName());
        return fit;
    }

    /** video → 当前挂载的容器尺寸监听；容器变化时换挂。仅主线程。 */
    private static final Map<View, Watch> WATCHES = new WeakHashMap<>();

    /**
     * video → 播放器回调的真实宽高比（VideoPatchLayout.onVideoSizeChanged）。
     * 优先于 FitState.aspect——Aweme 字段可能是封面/海报尺寸，
     * 动图多图时 imageInfos[0] 也可能与当前显示的图不符，真实回调是唯一权威值。
     * 配套 REAL_AID：仅当真实尺寸属于当前绑定的同一内容（aid 相同）时才采用，
     * View 复用到新内容后旧真实尺寸自动失效（新内容播放必然重新回调）。
     */
    private static final Map<View, Float> REAL = new WeakHashMap<>();

    /** video → REAL 尺寸所属内容的 aid（与 FitState.aid 比对）。 */
    private static final Map<View, String> REAL_AID = new WeakHashMap<>();

    // ------------------------------------------- 引擎真实尺寸（pull）

    /**
     * 绑定键 → 引擎。两种键都记：
     *   - SurfaceHolder（setSurfaceHolder 路径：与 view.getHolder() 是同一实例，身份最稳）；
     *   - Surface（setSurface 路径的兜底）。
     * 必须用 IdentityHashMap：Surface.equals/hashCode 基于 native 对象，会随创建/销毁漂移。
     * 上限 64 条，满了整体清空重记（绑定关系几秒内就会重建）。
     */
    private static final Map<Object, Object> SURFACE_ENGINES = new java.util.IdentityHashMap<>();
    /** Surface 的 native 对象 id → 引擎（Surface 实例会被反复包装重建，native id 更稳）。 */
    private static final Map<Long, Object> NATIVE_ENGINES = new java.util.HashMap<>();
    /**
     * 引擎 → 它绑过的 VideoSurface（texturerender 的 Surface 子类，
     * mSurfaceWidth/mSurfaceHeight 公有字段存渲染宽高——动图等无 Aweme 尺寸内容的权威来源）。
     */
    private static final Map<Object, Object> ENGINE_VSURFACE = new java.util.IdentityHashMap<>();

    /**
     * 目标 Surface（View 侧）→ 带宽高的 VideoSurface。
     * 由 setExtraRenderSurface(target) 钩子建立；pull 时直接读 VideoSurface 的活字段。
     */
    private static final Map<Object, Object> SURFACE_VS = new java.util.IdentityHashMap<>();
    private static final Map<Long, Object> NATIVE_VS = new java.util.HashMap<>();

    /** setExtraRenderSurface 钩子回写：(目标 Surface, VideoSurface)。 */
    static void noteExtraSurface(Object targetSurface, Object videoSurface) {
        if (targetSurface == null || videoSurface == null) {
            return;
        }
        synchronized (SURFACE_ENGINES) {
            if (SURFACE_VS.size() > 64) {
                SURFACE_VS.clear();
                NATIVE_VS.clear();
            }
            SURFACE_VS.put(targetSurface, videoSurface);
            long id = surfaceNativeId(targetSurface);
            if (id != 0) {
                NATIVE_VS.put(id, videoSurface);
            }
        }
    }

    /** 从 (View Surface → VideoSurface) 链路直读渲染宽高。 */
    private static int[] pullViaSurfaceLink(View video) {
        if (!(video instanceof android.view.SurfaceView)) {
            return null;
        }
        android.view.Surface s;
        try {
            s = ((android.view.SurfaceView) video).getHolder().getSurface();
        } catch (Throwable t) {
            return null;
        }
        if (s == null) {
            return null;
        }
        Object vs = null;
        synchronized (SURFACE_ENGINES) {
            vs = SURFACE_VS.get(s);
            if (vs == null) {
                long id = surfaceNativeId(s);
                if (id != 0) {
                    vs = NATIVE_VS.get(id);
                }
            }
        }
        if (vs == null) {
            return null;
        }
        try {
            Field wf = vs.getClass().getField("mSurfaceWidth");
            Field hf = vs.getClass().getField("mSurfaceHeight");
            int w = wf.getInt(vs);
            int h = hf.getInt(vs);
            if (w <= 0 || h <= 0) {
                lastPullCause = "link-vs-size0(" + w + "x" + h + ")";
                return null;
            }
            float ratio = (float) w / (float) h;
            if (ratio < 0.1f || ratio > 20f) {
                lastPullCause = "link-vs-insane(" + w + "x" + h + ")";
                return null;
            }
            lastPullCause = "ok-link";
            return new int[]{w, h};
        } catch (Throwable t) {
            lastPullCause = "link-vs-fail:" + t;
            return null;
        }
    }

    /** VideoSurface.setSurfaceDimensions 钩子回写：建立 engine↔VideoSurface 关联。 */
    static void noteVideoSurface(Object videoSurface) {
        if (videoSurface == null) {
            return;
        }
        synchronized (SURFACE_ENGINES) {
            Object engine = SURFACE_ENGINES.get(videoSurface);
            if (engine != null) {
                if (ENGINE_VSURFACE.size() > 32) {
                    ENGINE_VSURFACE.clear();
                }
                ENGINE_VSURFACE.put(engine, videoSurface);
            }
        }
    }

    /** 从 Surface 提取 native 对象 id（0 = 无效）。走 toString 解析，避开隐藏 API 限制。 */
    private static long surfaceNativeId(Object surface) {
        if (surface == null) {
            return 0;
        }
        try {
            String s = surface.toString();
            int i = s.indexOf("mNativeObject=");
            if (i < 0) {
                return 0;
            }
            i += "mNativeObject=".length();
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j))) {
                j++;
            }
            return (j > i) ? Long.parseLong(s.substring(i, j)) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** surface setter 钩子回写：key 可以是 SurfaceHolder 或 Surface。 */
    static void notePlayerSurface(Object engine, Object key) {
        if (engine == null || key == null) {
            return;
        }
        synchronized (SURFACE_ENGINES) {
            if (SURFACE_ENGINES.size() > 64) {
                SURFACE_ENGINES.clear();
                NATIVE_ENGINES.clear();
            }
            // 已有条目且旧引擎带尺寸 API、新引擎没有 → 保留旧的（尺寸优先）
            Object existing = SURFACE_ENGINES.get(key);
            if (existing != null && hasSizeApi(existing) && !hasSizeApi(engine)) {
                return;
            }
            SURFACE_ENGINES.put(key, engine);
            // key 本身是 VideoSurface（带 mSurfaceWidth 字段）→ 记引擎关联，pull 直读宽高
            if (hasField(key, "mSurfaceWidth")) {
                if (ENGINE_VSURFACE.size() > 32) {
                    ENGINE_VSURFACE.clear();
                }
                ENGINE_VSURFACE.put(engine, key);
            }
            if (key instanceof android.view.SurfaceHolder) {
                try {
                    android.view.Surface s = ((android.view.SurfaceHolder) key).getSurface();
                    if (s != null) {
                        SURFACE_ENGINES.put(s, engine);
                        putNative(s, engine);
                    }
                } catch (Throwable ignored) {
                    // holder 已失效
                }
            } else if (key instanceof android.view.Surface) {
                putNative((android.view.Surface) key, engine);
            }
        }
        maybeRefitAll();
    }

    /** 节流全量重算（≥500ms 一次）：surface 绑定事件驱动，尺寸就绪后自动纠正 LP。 */
    private static volatile long lastRefitAt;

    static void maybeRefitAll() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastRefitAt < 500) {
            return;
        }
        lastRefitAt = now;
        refitAll();
    }

    private static void putNative(android.view.Surface s, Object engine) {
        long id = surfaceNativeId(s);
        if (id != 0) {
            NATIVE_ENGINES.put(id, engine);
        }
    }

    /** 引擎类 → 尺寸提供者的字段路径（首次 BFS 成功后缓存，后续直走）。 */
    private static final Map<Class<?>, String[]> SIZE_PATH_CACHE =
            java.util.Collections.synchronizedMap(new java.util.HashMap<Class<?>, String[]>());
    /**
     * BFS 暂时找不到尺寸的类 → 重试时间点（毫秒 uptime）。
     * 不能永久缓存：首帧时引擎字段可能还没就绪，缓存毒化会导致整类永远拉不到尺寸。
     */
    private static final Map<Class<?>, Long> NO_SIZE_UNTIL =
            java.util.Collections.synchronizedMap(new java.util.HashMap<Class<?>, Long>());

    private static boolean hasSizeApi(Object obj) {
        try {
            obj.getClass().getMethod("getVideoWidth");
            obj.getClass().getMethod("getVideoHeight");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 找到持有宽高 API 的对象：DPlayAbility 自身没有，引擎埋在 provider 链里，
     * 以实例做深度 ≤4 的 BFS。允许下钻 com.ss/X/com.bytedance/java/kotlin 对象
     * （引擎可能包在 java 容器里），android 框架对象不下钻。
     * 失败仅 3 秒内跳过该类（字段就绪后自动重试），成功后缓存字段路径。
     */
    private static Object findSizeProvider(Object engine) {
        if (engine == null) {
            return null;
        }
        if (hasSizeApi(engine)) {
            return engine;
        }
        Class<?> cls = engine.getClass();
        Long until = NO_SIZE_UNTIL.get(cls);
        if (until != null && android.os.SystemClock.uptimeMillis() < until) {
            return null;
        }
        String[] path = SIZE_PATH_CACHE.get(cls);
        if (path == null) {
            java.util.List<String> trail = new java.util.ArrayList<>(4);
            Object found = bfsSize(engine, 0, trail,
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>()));
            if (found == null) {
                // 诊断：字段树 dump（每类一次）+ 试一把 PlayerCommand 通道
                if (FIELD_DUMPED.add(cls)) {
                    dumpFieldsOnce(engine);
                    tryServerVideoSizeCommand(engine);
                }
                NO_SIZE_UNTIL.put(cls, android.os.SystemClock.uptimeMillis() + 3000);
                return null;
            }
            NO_SIZE_UNTIL.remove(cls);
            path = trail.toArray(new String[0]);
            SIZE_PATH_CACHE.put(cls, path);
            return found;
        }
        Object cur = engine;
        for (String fn : path) {
            cur = readFieldByName(cur, fn);
            if (cur == null) {
                SIZE_PATH_CACHE.remove(cls);
                return null;
            }
        }
        return hasSizeApi(cur) ? cur : null;
    }

    private static Object bfsSize(Object obj, int depth, java.util.List<String> trail,
                                  java.util.Set<Object> seen) {
        if (obj == null || depth > 4 || !seen.add(obj)) {
            return null;
        }
        int scanned = 0;
        for (Class<?> c = obj.getClass();
             c != null && c != Object.class;
             c = c.getSuperclass()) {
            String cn = c.getName();
            if (cn.startsWith("android") || cn.startsWith("javax.") || cn.startsWith("dalvik")) {
                break; // 框架类不下钻
            }
            for (Field f : c.getDeclaredFields()) {
                if (++scanned > 64) {
                    break; // 单节点字段上限，防超大类拖慢主线程
                }
                if (f.isSynthetic() || f.getType().isPrimitive() || f.getType().isArray()
                        || f.getType() == String.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v == null) {
                        continue;
                    }
                    if (hasSizeApi(v)) {
                        trail.add(f.getName());
                        return v;
                    }
                    String vn = v.getClass().getName();
                    if (vn.startsWith("com.ss.") || vn.startsWith("X.")
                            || vn.startsWith("com.bytedance.")
                            || vn.startsWith("java.") || vn.startsWith("kotlin")) {
                        trail.add(f.getName());
                        Object r = bfsSize(v, depth + 1, trail, seen);
                        if (r != null) {
                            return r;
                        }
                        trail.remove(trail.size() - 1);
                    }
                } catch (Throwable ignored) {
                    // 字段不可读，跳过
                }
            }
        }
        return null;
    }

    /** 已经 dump 过字段树的引擎类（每类一次）。 */
    private static final java.util.Set<Class<?>> FIELD_DUMPED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 诊断：打印引擎首层字段（含值的运行时类名），并探两个候选尺寸接口。 */
    private static void dumpFieldsOnce(Object engine) {
        try {
            StringBuilder sb = new StringBuilder("engine-fields ")
                    .append(engine.getClass().getName()).append(" {");
            int n = 0;
            for (Class<?> c = engine.getClass();
                 c != null && c != Object.class && n < 40;
                 c = c.getSuperclass()) {
                if (c.getName().startsWith("android")) {
                    break;
                }
                for (Field f : c.getDeclaredFields()) {
                    if (++n > 40) {
                        break;
                    }
                    Object v = null;
                    try {
                        f.setAccessible(true);
                        v = f.get(engine);
                    } catch (Throwable ignored) {
                        // 不可读
                    }
                    sb.append(f.getName()).append("=")
                            .append(v != null ? v.getClass().getName() : "null").append(" ");
                }
            }
            sb.append("}");
            Log.d(TAG, sb.toString());
        } catch (Throwable t) {
            Log.d(TAG, "engine-fields dump failed: " + t);
        }
        // 候选尺寸接口 1：DPlayInfoProvider.LJLJJL() 疑似 "宽x高"
        try {
            Field gf = engine.getClass().getField("g");
            Object info = gf.get(engine);
            if (info != null) {
                Object s = info.getClass().getMethod("LJLJJL").invoke(info);
                Log.d(TAG, "DPlayInfo.LJLJJL -> " + s);
            }
        } catch (Throwable t) {
            Log.d(TAG, "DPlayInfo probe failed: " + t);
        }
        // 候选尺寸接口 2：VideoResolution 命令值
        try {
            ClassLoader loader = appLoader;
            Class<?> cmdCls = Class.forName(
                    "com.ss.android.ugc.aweme.player.sdk.api.PlayerCommand$Getter$VideoResolution",
                    false, loader);
            Object cmd = cmdCls.getField("f").get(null);
            Class<?> baseCls = Class.forName(
                    "com.ss.android.ugc.aweme.player.sdk.api.PlayerCommand", false, loader);
            Object res = engine.getClass().getMethod("LJI", baseCls).invoke(engine, cmd);
            Log.d(TAG, "VideoResolution -> " + res);
        } catch (Throwable t) {
            Log.d(TAG, "VideoResolution probe failed: " + t);
        }
    }

    /** 诊断：DPlayAbility.LJI(PlayerCommand$Getter$ServerVideoSize) 返回什么（解码用）。 */
    private static void tryServerVideoSizeCommand(Object engine) {
        try {
            ClassLoader loader = appLoader;
            if (loader == null) {
                Log.d(TAG, "ServerVideoSize skipped: appLoader null");
                return;
            }
            Class<?> cmdCls = Class.forName(
                    "com.ss.android.ugc.aweme.player.sdk.api.PlayerCommand$Getter$ServerVideoSize",
                    false, loader);
            Object cmd = cmdCls.getField("f").get(null);
            Class<?> baseCls = Class.forName(
                    "com.ss.android.ugc.aweme.player.sdk.api.PlayerCommand", false, loader);
            Object res = engine.getClass().getMethod("LJI", baseCls).invoke(engine, cmd);
            Log.d(TAG, "ServerVideoSize via " + engine.getClass().getSimpleName()
                    + " -> " + res + " (" + (res != null ? res.getClass().getName() : "null") + ")");
        } catch (Throwable t) {
            Log.d(TAG, "ServerVideoSize command failed: " + t);
        }
    }

    private static Object readFieldByName(Object obj, String name) {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
                // 父类里继续找
            }
        }
        return null;
    }

    /** pullForView 最近一次失败原因（仅诊断用，主线程读写）。 */
    private static volatile String lastPullCause = "never";

    private static boolean hasField(Object obj, String name) {
        if (obj == null) {
            return false;
        }
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                c.getDeclaredField(name);
                return true;
            } catch (Throwable ignored) {
                // 父类继续找
            }
        }
        return false;
    }

    /** 从 VideoSurface 的 mSurfaceWidth/mSurfaceHeight 直读渲染宽高（null = 不可用）。 */
    private static int[] pullFromVideoSurface(Object engine) {
        Object vs;
        synchronized (SURFACE_ENGINES) {
            vs = ENGINE_VSURFACE.get(engine);
        }
        if (vs == null) {
            return null;
        }
        try {
            Field wf = vs.getClass().getField("mSurfaceWidth");
            Field hf = vs.getClass().getField("mSurfaceHeight");
            int w = wf.getInt(vs);
            int h = hf.getInt(vs);
            if (w <= 0 || h <= 0) {
                lastPullCause = "vs-size0(" + w + "x" + h + ")";
                return null;
            }
            float ratio = (float) w / (float) h;
            if (ratio < 0.1f || ratio > 20f) {
                lastPullCause = "vs-insane(" + w + "x" + h + ")";
                return null;
            }
            lastPullCause = "ok-vs";
            return new int[]{w, h};
        } catch (Throwable t) {
            lastPullCause = "vs-fail:" + t;
            return null;
        }
    }

    /**
     * 拉取该 View 对应引擎的真实解码宽高（null = 不可用）。
     * 顺序：VideoSurface 渲染宽高（最权威）→ 字段 BFS → PlayerCommand 命令通道。
     * 身份守卫三级：Holder 实例 → Surface 实例 → Surface native id。
     */
    private static int[] pullForView(View video) {
        if (!(video instanceof android.view.SurfaceView)) {
            lastPullCause = "not-surfaceview";
            return null;
        }
        // 最高优先级：(View Surface → VideoSurface) 直链，字段是活的渲染宽高
        int[] linked = pullViaSurfaceLink(video);
        if (linked != null) {
            return linked;
        }
        android.view.SurfaceView sv = (android.view.SurfaceView) video;
        android.view.SurfaceHolder holder;
        android.view.Surface viewSurface;
        try {
            holder = sv.getHolder();
            viewSurface = holder.getSurface();
        } catch (Throwable t) {
            lastPullCause = "holder-fail:" + t;
            return null;
        }
        Object engine;
        int mapSize;
        synchronized (SURFACE_ENGINES) {
            engine = (holder != null) ? SURFACE_ENGINES.get(holder) : null;
            if (engine == null && viewSurface != null) {
                engine = SURFACE_ENGINES.get(viewSurface);
            }
            mapSize = SURFACE_ENGINES.size();
        }
        if (engine == null && viewSurface != null) {
            long id = surfaceNativeId(viewSurface);
            if (id != 0) {
                synchronized (SURFACE_ENGINES) {
                    engine = NATIVE_ENGINES.get(id);
                }
                if (engine != null) {
                    lastPullCause = "ok-native"; // 临时标记，后面统一覆盖
                }
            }
        }
        if (engine == null) {
            lastPullCause = "map-miss(holder=" + holder + ",surface=" + viewSurface
                    + ",mapSize=" + mapSize + ")";
            return null;
        }
        // 最权威：texturerender VideoSurface 的渲染宽高（mSurfaceWidth/Height）
        int[] vs = pullFromVideoSurface(engine);
        if (vs != null) {
            return vs;
        }
        Object provider = findSizeProvider(engine);
        if (provider == null) {
            // 字段链够不到 → PlayerCommand 命令通道
            return commandVideoSize(engine);
        }
        try {
            int w = (Integer) provider.getClass().getMethod("getVideoWidth").invoke(provider);
            int h = (Integer) provider.getClass().getMethod("getVideoHeight").invoke(provider);
            if (w <= 0 || h <= 0) {
                lastPullCause = "size0(engine=" + provider.getClass().getName()
                        + " " + w + "x" + h + ")";
                return null;
            }
            float ratio = (float) w / (float) h;
            if (ratio < 0.1f || ratio > 20f) {
                lastPullCause = "insane(" + w + "x" + h + ")";
                return null;
            }
            lastPullCause = "ok";
            return new int[]{w, h};
        } catch (Throwable t) {
            lastPullCause = "engine-call-fail:" + t;
            return null;
        }
    }

    /** 最近一次打印过的 ServerVideoSize 原始值（去重日志）。 */
    private static volatile long lastCmdRawLogged = Long.MIN_VALUE;

    /**
     * PlayerCommand 通道拉取（无尺寸 API 的引擎，如 DPlayAbility）：
     *   1) DebugInfoMap / FirstFrameInfoMap —— 疑似带 video_width/video_height 的信息表；
     *   2) ServerVideoSize —— 实测为文件字节数，仅当打包解码 sanity 通过才用。
     * 返回 0/null = 尚未就绪（触发重试）。
     */
    private static int[] commandVideoSize(Object engine) {
        // 1) 信息表命令：找 width/height 键
        int[] fromMap = infoMapVideoSize(engine);
        if (fromMap != null) {
            return fromMap;
        }
        try {
            ClassLoader loader = appLoader;
            if (loader == null) {
                lastPullCause = "cmd-no-loader";
                return null;
            }
            Class<?> cmdCls = Class.forName(
                    "com.ss.android.ugc.aweme.player.sdk.api.PlayerCommand$Getter$ServerVideoSize",
                    false, loader);
            Object cmd = cmdCls.getField("f").get(null);
            Class<?> baseCls = Class.forName(
                    "com.ss.android.ugc.aweme.player.sdk.api.PlayerCommand", false, loader);
            Object res = engine.getClass().getMethod("LJI", baseCls).invoke(engine, cmd);
            if (!(res instanceof Long)) {
                lastPullCause = "cmd-type(" + res + ")";
                return null;
            }
            long raw = (Long) res;
            if (raw == 0) {
                lastPullCause = "cmd-size0";
                return null;
            }
            if (raw != lastCmdRawLogged) {
                lastCmdRawLogged = raw;
                Log.d(TAG, "ServerVideoSize raw=" + raw + " engine="
                        + engine.getClass().getSimpleName());
            }
            int w1 = (int) (raw >>> 32);
            int h1 = (int) raw;
            float r1 = (h1 != 0) ? (float) w1 / h1 : 0f;
            int w2 = (int) raw;
            int h2 = (int) (raw >>> 32);
            float r2 = (w2 != 0) ? (float) w2 / h2 : 0f;
            boolean ok1 = w1 > 0 && h1 > 0 && r1 >= 0.1f && r1 <= 20f;
            boolean ok2 = w2 > 0 && h2 > 0 && r2 >= 0.1f && r2 <= 20f;
            if (ok1 && ok2) {
                lastPullCause = "cmd-ambiguous(raw=" + raw + " " + w1 + "x" + h1
                        + "/" + w2 + "x" + h2 + ")";
                return null;
            }
            if (ok1) {
                lastPullCause = "cmd-ok";
                return new int[]{w1, h1};
            }
            if (ok2) {
                lastPullCause = "cmd-ok";
                return new int[]{w2, h2};
            }
            lastPullCause = "cmd-insane(raw=" + raw + ")";
            return null;
        } catch (Throwable t) {
            lastPullCause = "cmd-fail:" + t;
            return null;
        }
    }

    /** 已经 dump 过内容的信息表命令名（诊断去重）。 */
    private static final java.util.Set<String> INFOMAP_DUMPED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** DebugInfoMap/FirstFrameInfoMap → 找 width/height 键组成宽高。 */
    private static int[] infoMapVideoSize(Object engine) {
        String[] getters = {"DebugInfoMap", "FirstFrameInfoMap"};
        try {
            ClassLoader loader = appLoader;
            if (loader == null) {
                return null;
            }
            Class<?> baseCls = Class.forName(
                    "com.ss.android.ugc.aweme.player.sdk.api.PlayerCommand", false, loader);
            for (String g : getters) {
                try {
                    Class<?> cmdCls = Class.forName(
                            "com.ss.android.ugc.aweme.player.sdk.api.PlayerCommand$Getter$"
                                    + g, false, loader);
                    Object cmd = cmdCls.getField("f").get(null);
                    Object res = engine.getClass().getMethod("LJI", baseCls)
                            .invoke(engine, cmd);
                    if (res == null) {
                        continue;
                    }
                    // 提取 width/height 类键
                    Integer w = null;
                    Integer h = null;
                    if (res instanceof org.json.JSONObject) {
                        org.json.JSONObject o = (org.json.JSONObject) res;
                        if (INFOMAP_DUMPED.add(g)) {
                            Log.d(TAG, g + " keys=" + o.toString());
                        }
                        java.util.Iterator<String> it = o.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            String lk = k.toLowerCase(Locale.ROOT);
                            if (lk.contains("width")) {
                                w = o.optInt(k, 0);
                            } else if (lk.contains("height")) {
                                h = o.optInt(k, 0);
                            }
                        }
                    } else if (res instanceof java.util.Map) {
                        java.util.Map<?, ?> m = (java.util.Map<?, ?>) res;
                        if (INFOMAP_DUMPED.add(g)) {
                            Log.d(TAG, g + " entries=" + m);
                        }
                        for (Object k : m.keySet()) {
                            String lk = String.valueOf(k).toLowerCase(Locale.ROOT);
                            Object v = m.get(k);
                            if (lk.contains("width") && v instanceof Number) {
                                w = ((Number) v).intValue();
                            } else if (lk.contains("height") && v instanceof Number) {
                                h = ((Number) v).intValue();
                            }
                        }
                    } else {
                        if (INFOMAP_DUMPED.add(g)) {
                            Log.d(TAG, g + " type=" + res.getClass().getName()
                                    + " val=" + res);
                        }
                        continue;
                    }
                    if (w != null && h != null && w > 0 && h > 0) {
                        float ratio = (float) w / h;
                        if (ratio >= 0.1f && ratio <= 20f) {
                            lastPullCause = "ok-infomap";
                            return new int[]{w, h};
                        }
                    }
                } catch (Throwable ignored) {
                    // 该命令不可用，试下一个
                }
            }
        } catch (Throwable ignored) {
            // loader 问题
        }
        return null;
    }

    // size0 重试：解码尺寸稍后才就绪，安排一次延迟重算（每 View 最多 5 次）。
    private static final Map<View, Integer> RETRY_COUNT = new WeakHashMap<>();
    private static final Map<View, Boolean> RETRY_SCHEDULED = new WeakHashMap<>();

    private static void schedulePullRetry(final View video) {
        if (RETRY_SCHEDULED.containsKey(video)) {
            return;
        }
        int n = RETRY_COUNT.containsKey(video) ? RETRY_COUNT.get(video) : 0;
        if (n >= 5) {
            return;
        }
        RETRY_COUNT.put(video, n + 1);
        RETRY_SCHEDULED.put(video, Boolean.TRUE);
        video.postDelayed(new Runnable() {
            @Override
            public void run() {
                RETRY_SCHEDULED.remove(video);
                FitState st = STATES.get(video);
                View c = parentOf(video);
                if (st != null && c != null) {
                    fit(video, c, st);
                }
            }
        }, 700);
    }

    /**
     * 最近一次成功读到比例、但渲染 View 尚未创建时的状态（仅 TextureView 路径）。
     * DraweeView 路径不写 pending，防止图片状态污染视频 attach 补偿。
     */
    private static volatile FitState pending;

    /** Fresco ScalingUtils.ScaleType 类（启动时按包 ClassLoader 解析）。 */
    private static volatile Class<?> scaleTypeCls;
    /** ScalingUtils.ScaleType.FIT_CENTER 枚举常量缓存。 */
    private static volatile Object fitCenterConst;

    /**
     * 各 CleanModeViewModel 实例的清屏状态（vm → 是否清屏中）。
     * 由 CleanModeViewModel.sA 钩子在状态机执行后读取其活跃来源列表 a 的真实值，
     * 不镜像命令（命令存在守卫分支，镜像会漂移）。任一 VM 活跃即视为清屏中。
     */
    private static final Map<Object, Boolean> CLEAN_VMS = new WeakHashMap<>();

    private FitApplier() {
    }

    /** 包加载时调用：解析 Fresco 反射所需类。失败仅影响 DraweeView 路径。 */
    private static volatile ClassLoader appLoader;

    static void init(ClassLoader loader) {
        appLoader = loader;
        try {
            scaleTypeCls = Class.forName(
                    "com.facebook.drawee.drawable.ScalingUtils$ScaleType", false, loader);
        } catch (Throwable t) {
            log("Fresco ScaleType class not found: " + t);
        }
    }

    /** sA 状态机执行完毕后回写该 ViewModel 的清屏状态（来源列表 a 是否为空）。 */
    static void noteCleanVm(Object vm, boolean active) {
        if (vm == null) {
            return;
        }
        synchronized (CLEAN_VMS) {
            if (active) {
                CLEAN_VMS.put(vm, Boolean.TRUE);
            } else {
                CLEAN_VMS.remove(vm);
            }
        }
    }

    /** 当前是否处于清屏全屏（任一被跟踪的 ViewModel 活跃）。 */
    private static boolean cleanActive() {
        synchronized (CLEAN_VMS) {
            return !CLEAN_VMS.isEmpty();
        }
    }

    // ---------------------------------------------------------------- 入口

    /** drawee set 探针：已尝试跟踪过的 View（去重日志与重复 apply）。 */
    private static final Map<View, Boolean> DRAWEE_SET_LOGGED = new WeakHashMap<>();

    /**
     * SimpleDraweeView.setImageURI 触发：静态图文封面的运行时入口。
     * 实测 FeedImageViewHolder.bind 在沉浸式图文上从不调用，改为从图片加载点反查：
     * 首次见到该 View 时按"图文 drawee 状态"走标准 applyMain（门控/监听全部复用）。
     * params 传 null：门控里 params 类判据自动跳过，其余判据照常生效。
     */
    static void onDraweeSet(final View v) {
        if (v == null) {
            return;
        }
        runOnMain(v, new Runnable() {
            @Override
            public void run() {
                if (STATES.get(v) != null) {
                    return; // 已跟踪（视频或图文状态）
                }
                if (DRAWEE_SET_LOGGED.containsKey(v)) {
                    return;
                }
                DRAWEE_SET_LOGGED.put(v, Boolean.TRUE);
                Log.d(TAG, "drawee set -> " + v.getClass().getName()
                        + " parents=[" + parentChain(v) + "]");
                if (parentOf(v) == null) {
                    // setController 可能发生在挂载前：挂一次性 attach 监听补跑，
                    // 否则没父容器 → 没有 layout 监听 → 永远不会重算。
                    v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View av) {
                            av.removeOnAttachStateChangeListener(this);
                            Log.d(TAG, "drawee attach-retry -> " + av.getClass().getName());
                            applyMain(av, new FitState(0f, true, null, true, null));
                            logDraweeContainer(av);
                        }

                        @Override
                        public void onViewDetachedFromWindow(View av) {
                            // 未 attach 就 detach：保持监听等下一次 attach
                        }
                    });
                    return;
                }
                applyMain(v, new FitState(0f, true, null, true, null));
                logDraweeContainer(v);
            }
        });
    }

    /** 诊断：applyMain 刚跑完时容器的实测尺寸（fit 首轮看到的就是它）。 */
    private static void logDraweeContainer(View v) {
        View c = parentOf(v);
        Log.d(TAG, "drawee applyMain -> container="
                + (c == null ? "null" : (c.getWidth() + "x" + c.getHeight()))
                + " view=" + v.getClass().getName());
    }

    /** 内容绑定/图片 holder 绑定时调用：记录状态并适配当前 View。 */
    private static final Map<View, String> APPLY_LOGGED = new WeakHashMap<>();

    static void apply(final View video, final FitState state) {
        if (state == null) {
            return;
        }
        if (!state.draweeFit && state.aspect <= 0.05f) {
            return;
        }
        if (video != null) {
            // 每个 View×内容 只打一条，标识"这次是谁绑给了谁"
            String key = state.aid + "|" + state.draweeFit
                    + "|" + Math.round(state.aspect * 1000f);
            String prev = APPLY_LOGGED.get(video);
            if (!key.equals(prev)) {
                APPLY_LOGGED.put(video, key);
                Log.d(TAG, "apply view=" + video.getClass().getName()
                        + " aid=" + state.aid
                        + " aspect=" + state.aspect
                        + " drawee=" + state.draweeFit
                        + " parents=[" + parentChain(video) + "]");
            }
        }
        if (!state.draweeFit) {
            pending = state; // 仅视频/动图状态参与 attach 补偿
        }
        if (video == null) {
            return; // View 还没创建，attach 补偿接手
        }
        runOnMain(video, new Runnable() {
            @Override
            public void run() {
                applyMain(video, state);
            }
        });
    }

    /** TextureView 被（重新）attach 时调用：优先用它自己记录的状态，其次用 pending。 */
    static void applyOnAttach(final View video) {
        if (video == null) {
            return;
        }
        runOnMain(video, new Runnable() {
            @Override
            public void run() {
                FitState state = STATES.get(video);
                if (state == null) {
                    state = pending;
                    // pending 属于"上一条绑定的播放器"——只有当该播放器此刻解析出的
                    // 渲染 View 就是本 View 时才允许消费。评论区 holder 不走
                    // VideoItemParams，评论动图的 TextureView 永远匹配不上主视频的
                    // player → 拒绝，从源头杜绝 pending 跨内容污染。
                    if (!isPendingOwner(state, video)) {
                        Log.d(TAG, "attach ignored: pending not owned by this view");
                        return;
                    }
                }
                if (state == null || state.draweeFit || state.aspect <= 0.05f) {
                    return; // 没有可信的视频状态：宁可不收缩，也不套错误比例
                }
                applyMain(video, state);
            }
        });
    }

    /**
     * pending 状态的播放器此刻解析出的渲染 View 是否就是 candidate。
     * getVideoView() 返回 null（播放器还没创建 View，那 attach 的就不是它的）
     * 或返回别的 View/子树（评论动图）→ false。解析失败也保守拒绝——
     * getAweme 会被抖音高频重复调用，漏掉的 attach 会被后续 bind 兜底。
     */
    private static boolean isPendingOwner(FitState state, View candidate) {
        if (state == null) {
            return false;
        }
        Object player = (state.player != null) ? state.player.get() : null;
        if (player == null) {
            return false;
        }
        try {
            Method getter = player.getClass().getMethod("getVideoView");
            Object owned = getter.invoke(player);
            if (owned == candidate) {
                return true;
            }
            // 部分实现返回包裹容器：candidate 在其子树内也算归属
            if (owned instanceof ViewGroup) {
                return containsView((ViewGroup) owned, candidate);
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean containsView(ViewGroup root, View target) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == target || (child instanceof ViewGroup
                    && containsView((ViewGroup) child, target))) {
                return true;
            }
        }
        return false;
    }

    /** 新内容读不到宽高比时调用：清 pending，并还原/忘掉该 View 的状态。 */
    static void release(final View video) {
        pending = null;
        if (video == null) {
            return;
        }
        runOnMain(video, new Runnable() {
            @Override
            public void run() {
                STATES.remove(video);
                REAL.remove(video);
                REAL_AID.remove(video);
                unwatch(video);
                undo(video);
            }
        });
    }

    /**
     * 播放器真实尺寸回调（VideoPatchLayout.onVideoSizeChanged）。
     * 这是宽高比的唯一权威来源：Aweme 字段可能是封面尺寸、
     * 多图帖的 imageInfos[0] 也可能与当前显示的图不符——
     * 此前图文界面动图"变窄比例错误"正是错误比例被写进绝对像素所致。
     * 从播放器 View 子树中找到被跟踪的渲染 View，按其 aid 覆盖比例并立即重算。
     */
    static void onPlayerLayoutSize(final View root, final int width, final int height) {
        if (root == null || width <= 0 || height <= 0) {
            return;
        }
        final float aspect = (float) width / (float) height;
        if (aspect < 0.1f || aspect > 20f) {
            return;
        }
        runOnMain(root, new Runnable() {
            @Override
            public void run() {
                View target = findTracked(root);
                if (target == null) {
                    Log.d(TAG, "player size " + width + "x" + height
                            + " but no tracked view in subtree");
                    return;
                }
                FitState state = STATES.get(target);
                if (state == null) {
                    return;
                }
                REAL.put(target, aspect);
                REAL_AID.put(target, state.aid);
                FitState fresh = new FitState(aspect, state.imagePost,
                        state.params != null ? state.params.get() : null,
                        state.draweeFit, state.aid);
                STATES.put(target, fresh);
                View container = parentOf(target);
                if (container != null) {
                    watch(target, container);
                    fit(target, container, fresh);
                }
                Log.d(TAG, "real size " + width + "x" + height
                        + " overrides aspect=" + aspect);
            }
        });
    }

    /** BFS 在 root 子树里找第一个被 STATES 跟踪的 View（限深防意外）。 */
    private static View findTracked(View root) {
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int budget = 500; // 节点预算，防极端布局拖慢主线程
        while (!queue.isEmpty() && budget-- > 0) {
            View v = queue.poll();
            if (STATES.containsKey(v)) {
                return v;
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    queue.add(vg.getChildAt(i));
                }
            }
        }
        return null;
    }

    /** 该 View 当前可用的权威比例：真实回调值（aid 匹配时）优先，否则用绑定时的猜测值。 */
    private static float effectiveAspect(View video, FitState state) {
        Float real = REAL.get(video);
        if (real != null && real > 0.05f) {
            String realAid = REAL_AID.get(video);
            if (state.aid == null || state.aid.equals(realAid)) {
                return real;
            }
            // REAL 属于上一条内容 → 作废，防止 View 复用后比例残留
            REAL.remove(video);
            REAL_AID.remove(video);
        }
        return state.aspect;
    }

    // ------------------------------------------------------------ 内部实现

    /** 主线程直接执行，否则 post 到 View 队列。 */
    private static void runOnMain(final View video, final Runnable r) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            r.run();
        } else {
            video.post(r);
        }
    }

    /** 主线程上的适配主流程（视频与图文共用：记状态 + 挂监听 + 执行适配）。 */
    private static void applyMain(View video, FitState state) {
        // View 复用到新内容：旧的真实尺寸作废（新内容播放会重新回调）
        String realAid = REAL_AID.get(video);
        if (realAid != null && state.aid != null && !realAid.equals(state.aid)) {
            REAL.remove(video);
            REAL_AID.remove(video);
            RETRY_COUNT.remove(video); // 新内容重新给 pull 重试预算
        }
        STATES.put(video, state);
        View container = parentOf(video);
        if (container == null) {
            return; // View 还没挂上，attach 补偿接手
        }
        watch(video, container);
        fit(video, container, state);
    }

    private static View parentOf(View video) {
        ViewParent parent = video.getParent();
        return (parent instanceof View) ? (View) parent : null;
    }

    /** 门控 + 适配。容器必须非空。 */
    private static void fit(View video, View container, FitState state) {
        int cw = container.getWidth();
        int ch = container.getHeight();
        if (cw <= 0 || ch <= 0) {
            return; // 监听已挂上，容器首次布局后会回调
        }

        // 门控零：评论区一律不碰（评论动图/评论图片查看器/评论 feed，无论容器多像全屏）。
        String commentJudge = commentJudge(video, state);
        if (commentJudge != null) {
            undo(video);
            logSkip(video, commentJudge);
            return;
        }
        // 门控一：场所白名单——只处理用户点名的三处：
        //   主 Feed（MainActivity 家族）、详情全屏播放器（*DetailActivity 家族）、清屏全屏（cleanActive）。
        //   搜索列表、广告页、商城等其余界面一律还原、保持抖音原生行为。
        if (!inAllowedScope(video)) {
            undo(video);
            logSkip(video, "out-of-scope context");
            return;
        }
        // 门控一点五：直播上下文不在范围内（直播封面/直播间组件一律还原）。
        if (isLiveContext(video)) {
            undo(video);
            logSkip(video, "live context");
            return;
        }
        // 门控二：已按用户新要求整体移除——动图与图文在详情页同样必须不裁剪。
        // 静态图走 scaleType 路径（不改 LayoutParams），不破坏捏合缩放；
        // 动图走播放器表面收缩。评论/直播/清屏等其余门控继续生效。

        // 门控三：非全屏容器（搜索卡片等）保持抖音原生 cover 填充
        if (!isFullscreenLevel(container, cw, ch)) {
            undo(video);
            logSkip(video, "non-fullscreen container " + cw + "x" + ch);
            return;
        }
        SKIP_LOGGED.remove(video); // 本次放行：清掉历史 skip 记录，下次跳过会重新打印

        if (state.draweeFit) {
            applyScaleType(video);
            return;
        }
        fitLayoutParams(video, cw, ch, state);
    }

    /** video → 最近一次 skip 原因；同一原因只打一条，避免 getAweme 高频轮询刷屏。 */
    private static final Map<View, String> SKIP_LOGGED = new WeakHashMap<>();

    /** Surface 已可查但 pull 失败的 View → 最近原因（原因变了才再打一条）。 */
    private static final Map<View, String> PULL_NA = new WeakHashMap<>();

    /** 向上收集父 View 类名（最多 6 层），用于日志定位布局栈。 */
    private static String parentChain(View v) {
        StringBuilder sb = new StringBuilder();
        ViewParent p = v.getParent();
        int depth = 0;
        while (p instanceof View && depth++ < 6) {
            if (sb.length() > 0) {
                sb.append(" < ");
            }
            sb.append(((View) p).getClass().getName());
            p = ((View) p).getParent();
        }
        return sb.toString();
    }

    private static void logSkip(View video, String reason) {
        String prev = SKIP_LOGGED.get(video);
        if (reason.equals(prev)) {
            return;
        }
        SKIP_LOGGED.put(video, reason);
        Log.d(TAG, "skip " + reason + " (view=" + video.getClass().getName() + ")");
    }

    // ------------------------------------------- 路径一：TextureView 收缩

    private static void fitLayoutParams(View video, int cw, int ch, FitState state) {
        float aspect = effectiveAspect(video, state);
        String src = aspectSource(video, state);
        // 真实解码尺寸（pull）：从"绑定了本 View Surface 的引擎"直接拉——
        // 引擎对自己内容的解码尺寸是唯一权威，身份守卫保证不吃到别人的比例。
        int[] real = pullForView(video);
        if (real == null) {
            if (video instanceof android.view.SurfaceView) {
                if (lastPullCause.startsWith("size0") || lastPullCause.startsWith("no-size-api")
                        || lastPullCause.startsWith("map-miss")
                        || lastPullCause.startsWith("cmd-size0")
                        || lastPullCause.startsWith("cmd-insane")
                        || lastPullCause.startsWith("vs-size0")) {
                    schedulePullRetry(video); // 解码尺寸/引擎字段/绑定稍后才就绪
                }
                if (!lastPullCause.equals(PULL_NA.get(video))) {
                    PULL_NA.put(video, lastPullCause);
                    Log.d(TAG, "pull unavailable [" + lastPullCause + "] view="
                            + video.getClass().getName());
                }
            }
        } else {
            RETRY_COUNT.remove(video);
            PULL_NA.remove(video);
            float pa = (float) real[0] / (float) real[1];
            if (Math.abs(pa - aspect) > Math.max(0.01f, aspect * 0.02f)) {
                Log.d(TAG, "pull real " + real[0] + "x" + real[1]
                        + " overrides " + src + " aspect=" + aspect
                        + " view=" + video.getClass().getName());
            }
            aspect = pa;
            src = "pull " + real[0] + "x" + real[1];
        }
        int targetW;
        int targetH;
        if (aspect >= (float) cw / (float) ch) {
            targetW = cw;
            targetH = Math.max(1, Math.round(cw / aspect));
        } else {
            targetH = ch;
            targetW = Math.max(1, Math.round(ch * aspect));
        }

        ViewGroup.LayoutParams lp = video.getLayoutParams();
        if (lp == null) {
            return;
        }
        if (lp.width == targetW && lp.height == targetH) {
            return; // 幂等：已适配
        }
        rememberOriginal(video, lp);
        lp.width = targetW;
        lp.height = targetH;
        video.setLayoutParams(lp);
        markWritten(video, targetW, targetH);
        Log.d(TAG, "fit " + cw + "x" + ch + " -> "
                + targetW + "x" + targetH + " (aspect=" + aspect
                + ",src=" + src
                + ",aid=" + state.aid
                + ",view=" + video.getClass().getName() + ")");
    }

    /** 当前采用的比例来自权威回调（real）还是绑定时的猜测值（guess）。 */
    private static String aspectSource(View video, FitState state) {
        Float real = REAL.get(video);
        if (real != null && real > 0.05f
                && (state.aid == null || state.aid.equals(REAL_AID.get(video)))) {
            return "real";
        }
        return "guess";
    }

    // ----------------------------------- 路径二：DraweeView scaleType 适配

    /**
     * 把静态图文的显示方式改为 FIT（层级 scaleType → FIT_CENTER）。
     * 不改 LayoutParams：捏合缩放、多图切换、容器变形全部不受影响，
     * 还原时恢复原始 scaleType 即可。反射失败仅记日志，不影响视频路径。
     */
    private static void applyScaleType(View video) {
        if (!(video instanceof ImageView)) {
            if (!Boolean.TRUE.equals(SCALE_NA.get(video))) {
                SCALE_NA.put(video, Boolean.TRUE);
                Log.d(TAG, "scale-fit not ImageView: " + video.getClass().getName());
            }
            return;
        }
        try {
            Class<?> stCls = scaleTypeCls;
            if (stCls == null) {
                return;
            }
            Object fitCenter = fitCenterConst;
            if (fitCenter == null) {
                fitCenter = stCls.getField("FIT_CENTER").get(null);
                fitCenterConst = fitCenter;
            }
            Object holder = video.getClass().getField("mDraweeHolder").get(video);
            Object hierarchy = holder.getClass().getMethod("getHierarchy").invoke(holder);
            synchronized (HIERARCHY_VIEWS) {
                if (HIERARCHY_VIEWS.size() > 64) {
                    HIERARCHY_VIEWS.clear();
                }
                HIERARCHY_VIEWS.put(hierarchy, video);
            }
            Method getScale = hierarchy.getClass().getMethod("getActualImageScaleType");
            Method setScale = hierarchy.getClass().getMethod("setActualImageScaleType", stCls);
            Object cur = getScale.invoke(hierarchy);

            ImageView iv = (ImageView) video;
            boolean firstApply = !SCALE_ORIGS.containsKey(video);
            if (firstApply) {
                SCALE_ORIGS.put(video, new Object[]{iv.getScaleType(), cur});
            }
            boolean changed = false;
            if (iv.getScaleType() == ImageView.ScaleType.CENTER_CROP) {
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                changed = true;
            }
            if (cur == null || !fitCenter.equals(cur)) {
                setScale.invoke(hierarchy, fitCenter);
                changed = true;
            }
            if (firstApply) {
                Log.d(TAG, "scale-fit first apply changed=" + changed
                        + " cur=" + cur + " iv=" + iv.getScaleType()
                        + " view=" + video.getClass().getName());
            } else if (changed) {
                // 之前已适配过、现在又发生变化 = 抖音异步把 scaleType 重设了，重新压回
                Log.d(TAG, "scale-fit re-applied after reset, cur=" + cur
                        + " iv=" + iv.getScaleType()
                        + " view=" + video.getClass().getName());
            }
        } catch (Throwable t) {
            reflectFail(video, "scale-fit", t);
        }
    }

    /** 非 ImageView 的 scale-fit 尝试（去重日志）。 */
    private static final Map<View, Boolean> SCALE_NA = new WeakHashMap<>();

    /** 反射失败按 View 去重打印（logOnce 是进程级一次，可能在清日志前就消耗掉）。 */
    private static void reflectFail(View video, String where, Throwable t) {
        String key = where + ":" + video.getClass().getName();
        if (!REFLECT_FAILED.add(key)) {
            return;
        }
        if (REFLECT_FAILED.size() > 64) {
            REFLECT_FAILED.clear(); // 防长期运行无限增长
        }
        Log.e(TAG, where + " reflection failed: " + t);
    }

    private static final java.util.Set<String> REFLECT_FAILED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 还原 DraweeView 的原始 scaleType（仅当当前值仍是我们写入的值才覆盖）。 */
    private static void restoreScale(View video) {
        Object[] orig = SCALE_ORIGS.remove(video);
        if (orig == null || !(video instanceof ImageView)) {
            return;
        }
        try {
            ImageView iv = (ImageView) video;
            Class<?> stCls = scaleTypeCls;
            if (stCls == null) {
                return;
            }
            Object holder = video.getClass().getField("mDraweeHolder").get(video);
            Object hierarchy = holder.getClass().getMethod("getHierarchy").invoke(holder);
            Method getScale = hierarchy.getClass().getMethod("getActualImageScaleType");
            Method setScale = hierarchy.getClass().getMethod("setActualImageScaleType", stCls);
            Object cur = getScale.invoke(hierarchy);
            Object fitCenter = fitCenterConst;
            // 层级：当前仍是 FIT_CENTER（我们写的）且原值不同 → 恢复
            if (orig[1] != null && fitCenter != null && fitCenter.equals(cur)
                    && !fitCenter.equals(orig[1])) {
                setScale.invoke(hierarchy, orig[1]);
            }
            // ImageView：当前仍是 FIT_CENTER 且原值是 CENTER_CROP → 恢复
            if (orig[0] instanceof ImageView.ScaleType
                    && iv.getScaleType() == ImageView.ScaleType.FIT_CENTER
                    && orig[0] == ImageView.ScaleType.CENTER_CROP) {
                iv.setScaleType((ImageView.ScaleType) orig[0]);
            }
            Log.d(TAG, "restore DraweeView scaleType");
        } catch (Throwable t) {
            reflectFail(video, "scale restore", t);
        }
    }

    // ---------------------------------------------------------- 门控与还原

    /**
     * 容器是否为"全屏级"（主 Feed / 沉浸式播放页那样的整屏容器）。
     * 阈值：宽 ≥ 窗口 85%（排除双列/单列卡片）且高 ≥ 窗口 30%
     * （排除通栏 16:9 预览 26.8%；沉浸式图文页容器 ~36% 必须放行）。
     * 独立小窗（后台播放）窗口本身就是小窗尺寸，容器≈窗口，判断自然通过。
     */
    private static boolean isFullscreenLevel(View container, int cw, int ch) {
        View root = container.getRootView();
        int rw = root != null ? root.getWidth() : 0;
        int rh = root != null ? root.getHeight() : 0;
        if (rw <= 0 || rh <= 0) {
            return true; // 窗口还没测量出来：按全屏处理，避免主 Feed 因时序漏适配
        }
        return cw >= rw * 0.85f && ch >= rh * 0.30f;
    }

    /**
     * 是否处于评论上下文：评论区动图/评论图片查看器/评论 feed。
     * 判据（任一命中即算评论）：
     *   1) 视图树：从渲染 View 向上任一祖先类名含 comment/cmt
     *      （评论面板容器、评论 feed 根布局）；
     *   2) 宿主 Activity 类名含 comment（CommentFeedActivity 等）；
     *   3) params 的 fragment/commentFragment/feedItemFragment 指向评论类
     *      或 commentFeedPageId 非空；
     *   4) 该 View 位于某个**可见评论 Fragment 的视图子树**内
     *      （遍历 Activity 的 FragmentManager 含子级；覆盖判据 3 的时序竞态：
     *      getAweme 绑定瞬间 commentFragment 可能还没被 syncBind 回填）；
     *   5) 该 View 处于全屏级的**独立窗口**（评论面板是 DialogFragment，
     *      decorView ≠ Activity decor；长按预览等覆盖式弹窗一并排除，
     *      后台小窗等小窗口不满足全屏级、不受此判据影响）。
     * 评论区一律不触碰（用户明确要求）。
     */
    /**
     * 命中的评论判据（null = 不在评论上下文）。返回值直接作为 skip 日志原因，
     * 便于运行时精确定位是哪条判据误伤。
     */
    private static String commentJudge(View video, FitState state) {
        // 判据 1：视图树（从渲染 View 向上走，评论容器类名通常带 comment/cmt）
        for (View v = video; v != null; ) {
            String cls = v.getClass().getName();
            if (looksComment(cls)) {
                return "comment:viewtree:" + cls;
            }
            ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        // 判据 2：宿主 Activity
        Activity act = activityOf(video);
        if (act != null && looksComment(act.getClass().getName())) {
            return "comment:activity:" + act.getClass().getName();
        }
        // 判据 3：params 上的 Fragment / pageId 字段
        // Fragment 必须 isAdded：commentFragment 可能在评论面板关闭后残留非空，
        // 只看非空会永久误伤该条内容的所有后续 fit。
        Object params = (state.params != null) ? state.params.get() : null;
        if (params != null) {
            String[] fragFields = {"fragment", "commentFragment", "feedItemFragment"};
            for (String fn : fragFields) {
                try {
                    Object frag = params.getClass().getField(fn).get(params);
                    if (frag != null && looksComment(frag.getClass().getName())
                            && isFragmentAdded(frag)) {
                        return "comment:params." + fn + ":" + frag.getClass().getName();
                    }
                } catch (Throwable ignored) {
                    // 字段不存在或不可访问，试下一个
                }
            }
            try {
                Object pageId = params.getClass().getField("commentFeedPageId").get(params);
                if (pageId instanceof String && !((String) pageId).isEmpty()) {
                    return "comment:params.commentFeedPageId";
                }
            } catch (Throwable ignored) {
                // 字段不可达
            }
        }
        // 判据 4：可见评论 Fragment 的视图包含该 View（时序竞态兜底）
        String fragHit = insideVisibleCommentFragment(video, act);
        if (fragHit != null) {
            return "comment:fragment-subtree:" + fragHit;
        }
        // 判据 5：全屏级独立窗口（评论 Dialog 等覆盖式弹窗）
        return inForeignFullscreenWindow(video, act);
    }

    /**
     * video 是否位于一个全屏级、但不属于宿主 Activity 的独立窗口
     * （评论面板 CommentFeedDialogFragment 是 Dialog，decorView 与 Activity 不同）。
     * 关键前提：**View 必须已 attach 到窗口**——未 attach 时 getRootView() 返回的是
     * 脱离窗口的子树顶（播放器预挂载/重挂载中的 SurfaceView 就是这种），
     * 必然 ≠ decor，会被误判成独立窗口，导致主 Feed 视频被错误还原。
     * 已 attach 的 View，getRootView() 必然是其所在窗口的顶——此时不等 decor
     * 就确实身处另一个窗口（评论 Dialog），判据成立。
     * 后台小窗等非全屏独立窗口不在此列（尺寸门控与白名单已覆盖）。
     */
    private static String inForeignFullscreenWindow(View video, Activity act) {
        if (act == null || act.getWindow() == null) {
            return null;
        }
        if (!video.isAttachedToWindow()) {
            return null; // 未挂窗口：预布局子树，不算独立窗口
        }
        View decor = act.getWindow().getDecorView();
        View root = video.getRootView();
        if (decor == null || root == null || root == decor) {
            return null; // 同一窗口（主 Feed / 详情 / 清屏都在 Activity 自己的窗口）
        }
        int dw = decor.getWidth();
        int dh = decor.getHeight();
        int rw = root.getWidth();
        int rh = root.getHeight();
        if (dw <= 0 || dh <= 0 || rw <= 0 || rh <= 0) {
            return null; // 窗口尚未测量：交由后续 layout 重算
        }
        if (rw < dw * 0.85f || rh < dh * 0.50f) {
            return null; // 非全屏级（后台小窗等）
        }
        return "comment:foreign-fullscreen-window"
                + " act=" + act.getClass().getName()
                + " root=" + root.getClass().getName()
                + " rootSize=" + rw + "x" + rh
                + " decorSize=" + dw + "x" + dh;
    }

    /** Fragment.isAdded()（反射；拿不到按未添加处理，避免残留引用误伤）。 */
    private static boolean isFragmentAdded(Object frag) {
        try {
            return (Boolean) frag.getClass().getMethod("isAdded").invoke(frag);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 类名是否像评论相关（comment 包/类，或 Cmt* 缩写）。 */
    private static boolean looksComment(String className) {
        String n = className.toLowerCase(Locale.ROOT);
        return n.contains("comment") || n.contains("cmt");
    }

    /**
     * video 是否位于某个已添加、可见、类名含 comment/cmt 的 Fragment 的视图子树内。
     * 覆盖 CommentFeedDialogFragment（Dialog 窗口）内嵌 CommentFeedFragment 的场景：
     * 绑定瞬间 params.commentFragment 尚未回填时，判据 3 落空，靠这里兜住。
     * 返回命中的 Fragment 类名（null = 未命中）；反射拿不到时保守视为未命中。
     */
    private static String insideVisibleCommentFragment(View video, Activity act) {
        if (act == null) {
            return null;
        }
        java.util.List<Object> frags = new java.util.ArrayList<>(8);
        collectFragments(act, frags);
        for (Object frag : frags) {
            if (!looksComment(frag.getClass().getName())) {
                continue;
            }
            try {
                if (!((Boolean) frag.getClass().getMethod("isAdded").invoke(frag))) {
                    continue;
                }
                Method hidden = null;
                try {
                    hidden = frag.getClass().getMethod("isHidden");
                    if ((Boolean) hidden.invoke(frag)) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    // 没有 isHidden 的实现跳过该检查
                }
                Object fv = frag.getClass().getMethod("getView").invoke(frag);
                if (!(fv instanceof View)) {
                    continue;
                }
                View fragView = (View) fv;
                if (fragView.getVisibility() != View.VISIBLE) {
                    continue;
                }
                for (View v = video; v != null; ) {
                    if (v == fragView) {
                        return frag.getClass().getName();
                    }
                    ViewParent p = v.getParent();
                    v = (p instanceof View) ? (View) p : null;
                }
            } catch (Throwable ignored) {
                // 该 Fragment 反射失败，试下一个
            }
        }
        return null;
    }

    /** 收集 Activity 顶层 Fragment 及其各一层子 Fragment（评论面板两层结构够用）。 */
    private static void collectFragments(Activity act, java.util.List<Object> out) {
        String[] getters = {"getSupportFragmentManager", "getFragmentManager"};
        for (String g : getters) {
            try {
                Object fm = act.getClass().getMethod(g).invoke(act);
                if (fm == null) {
                    continue;
                }
                Object list = fm.getClass().getMethod("getFragments").invoke(fm);
                if (!(list instanceof java.util.List)) {
                    continue;
                }
                for (Object f : (java.util.List<?>) list) {
                    if (f == null) {
                        continue;
                    }
                    out.add(f);
                    try {
                        Object childFm = f.getClass().getMethod("getChildFragmentManager")
                                .invoke(f);
                        Object cl = childFm.getClass().getMethod("getFragments").invoke(childFm);
                        if (cl instanceof java.util.List) {
                            for (Object cf : (java.util.List<?>) cl) {
                                if (cf != null) {
                                    out.add(cf);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // 无子 FragmentManager，继续
                    }
                }
            } catch (Throwable ignored) {
                // 该 getter 不可用（非 FragmentActivity 等），试下一个
            }
        }
    }

    /**
     * 场所白名单——只处理用户点名的三类全屏界面：
     *   1) 主 Feed：MainActivity 家族（MainActivity/TeenMainActivity/BasicFuncMainActivity）
     *   2) 详情全屏播放器：*DetailActivity 家族（UltraDetail/Detail/SingleTaskDetail/LongVideoDetail…）
     *   3) 清屏全屏：cleanActive（可发生在上述任一界面内）
     * 其余（搜索列表、广告、商城、未知 Activity、非 Activity 上下文）一律不碰。
     */
    private static boolean inAllowedScope(View video) {
        if (cleanActive()) {
            return true;
        }
        Activity act = activityOf(video);
        if (act == null) {
            return false; // 非 Activity 上下文（悬浮窗/服务窗口）：保守不碰
        }
        String n = act.getClass().getName().toLowerCase(Locale.ROOT);
        return n.contains("mainactivity") || n.contains("detailactivity");
    }

    /**
     * 该 View 是否处于直播上下文（直播预览卡、直播间组件）。
     * 直播不在用户点名的三处范围内，封面/组件一律保持原生。
     * 判据：祖先链类名含 livesdk / livepreview / android.live 包名（避开 "deliver" 之类误伤）。
     */
    private static boolean isLiveContext(View video) {
        for (View v = video; v != null; ) {
            String n = v.getClass().getName().toLowerCase(Locale.ROOT);
            if (n.contains("livesdk") || n.contains("livepreview")
                    || n.contains("android.live") || n.contains("live.core")) {
                return true;
            }
            ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /** View 当前挂在哪个 Activity 上（解包 ContextWrapper）。解不到返回 null。 */
    private static Activity activityOf(View video) {
        Context ctx = video.getContext();
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return (ctx instanceof Activity) ? (Activity) ctx : null;
    }

    /**
     * 清屏状态变化后全量重算：遍历已跟踪的 View 重新走 fit()
     * （进入清屏 → 图片开始收缩；退出清屏回详情 → 按门控自动还原）。
     * 非主线程自动切主线程。
     */
    static void refitAll() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(new Runnable() {
                        @Override
                        public void run() {
                            refitAll();
                        }
                    });
            return;
        }
        for (View video : new java.util.ArrayList<View>(STATES.keySet())) {
            FitState state = STATES.get(video);
            if (state == null) {
                continue;
            }
            View container = parentOf(video);
            if (container != null) {
                fit(video, container, state);
            }
        }
    }

    /** 撤销该 View 上本模块的全部适配（LayoutParams 与 scaleType 各自幂等还原）。 */
    private static void undo(View video) {
        restoreLayoutParams(video);
        restoreScale(video);
    }

    // ------------------------------------------------------ 容器尺寸监听

    private static final class Watch {
        final View container;
        final View.OnLayoutChangeListener listener;

        Watch(View container, View.OnLayoutChangeListener listener) {
            this.container = container;
            this.listener = listener;
        }
    }

    /** 确保 video 在其当前容器上有尺寸监听；容器换了就换挂。 */
    private static void watch(final View video, final View container) {
        Watch old = WATCHES.get(video);
        if (old != null) {
            if (old.container == container) {
                return;
            }
            old.container.removeOnLayoutChangeListener(old.listener);
        }
        final WeakReference<View> ref = new WeakReference<>(video);
        View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                View target = ref.get();
                if (target == null || target.getParent() != v) {
                    v.removeOnLayoutChangeListener(this);
                    Watch cur = (target != null) ? WATCHES.get(target) : null;
                    if (cur != null && cur.listener == this) {
                        WATCHES.remove(target);
                    }
                    return;
                }
                FitState state = STATES.get(target);
                if (state == null) {
                    return;
                }
                if (!state.draweeFit && state.aspect <= 0.05f) {
                    return;
                }
                fit(target, v, state);
            }
        };
        container.addOnLayoutChangeListener(listener);
        WATCHES.put(video, new Watch(container, listener));
    }

    private static void unwatch(View video) {
        Watch w = WATCHES.remove(video);
        if (w != null) {
            w.container.removeOnLayoutChangeListener(w.listener);
        }
    }

    // ---------------------------------------------------- 原始尺寸记账

    /** 记录收缩前的原始尺寸（仅首次；须在改写 lp 之前调用）。 */
    private static void rememberOriginal(View video, ViewGroup.LayoutParams lp) {
        if (!ORIGINALS.containsKey(video)) {
            ORIGINALS.put(video, new int[]{lp.width, lp.height, -1, -1});
        }
    }

    /** 记录本模块本次写入的尺寸（须在改写 lp 之后调用），供还原时比对。 */
    private static void markWritten(View video, int w, int h) {
        int[] rec = ORIGINALS.get(video);
        if (rec != null) {
            rec[2] = w;
            rec[3] = h;
        }
    }

    /** 还原 LayoutParams（仅当当前值仍是我们写入的原始记录语义下才覆盖）。 */
    private static void restoreLayoutParams(View video) {
        int[] rec = ORIGINALS.remove(video);
        if (rec == null) {
            return;
        }
        ViewGroup.LayoutParams lp = video.getLayoutParams();
        if (lp == null) {
            return;
        }
        if (rec[2] < 0 || (lp.width == rec[2] && lp.height == rec[3])) {
            if (lp.width == rec[0] && lp.height == rec[1]) {
                return;
            }
            lp.width = rec[0];
            lp.height = rec[1];
            video.setLayoutParams(lp);
            Log.d(TAG, "restore " + rec[0] + "x" + rec[1]);
        }
        // 当前尺寸不等于本模块上次写入值 → 抖音已自行改写，只清记录不覆盖
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
