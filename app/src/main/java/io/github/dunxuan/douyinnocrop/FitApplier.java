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

    /**
     * 最近一次成功读到比例、但渲染 View 尚未创建时的状态（仅 TextureView 路径）。
     * DraweeView 路径不写 pending，防止图片状态污染视频 attach 补偿。
     */
    private static volatile FitState pending;

    /** VideoItemParams.isDetailPagePanelShow 的缓存 Field（语义字段名，稳定）。 */
    private static volatile Field panelField;

    /** Fresco ScalingUtils.ScaleType 类（启动时按包 ClassLoader 解析）。 */
    private static volatile Class<?> scaleTypeCls;
    /** ScalingUtils.ScaleType.FIT_CENTER 枚举常量缓存。 */
    private static volatile Object fitCenterConst;
    private static volatile boolean scaleReflectLogged;

    /**
     * 各 CleanModeViewModel 实例的清屏状态（vm → 是否清屏中）。
     * 由 CleanModeViewModel.sA 钩子在状态机执行后读取其活跃来源列表 a 的真实值，
     * 不镜像命令（命令存在守卫分支，镜像会漂移）。任一 VM 活跃即视为清屏中。
     */
    private static final Map<Object, Boolean> CLEAN_VMS = new WeakHashMap<>();

    private FitApplier() {
    }

    /** 包加载时调用：解析 Fresco 反射所需类。失败仅影响 DraweeView 路径。 */
    static void init(ClassLoader loader) {
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

    /** 内容绑定/图片 holder 绑定时调用：记录状态并适配当前 View。 */
    static void apply(final View video, final FitState state) {
        if (state == null) {
            return;
        }
        if (!state.draweeFit && state.aspect <= 0.05f) {
            return;
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
        if (isCommentContext(video, state)) {
            undo(video);
            Log.d(TAG, "skip comment context");
            return;
        }
        // 门控一：场所白名单——只处理用户点名的三处：
        //   主 Feed（MainActivity 家族）、详情全屏播放器（*DetailActivity 家族）、清屏全屏（cleanActive）。
        //   搜索列表、广告页、商城等其余界面一律还原、保持抖音原生行为。
        if (!inAllowedScope(video)) {
            undo(video);
            Log.d(TAG, "skip out-of-scope context");
            return;
        }
        // 门控二（图片类内容专用，双保险）：
        //   a) 详情图文浏览面板可见（isDetailPagePanelShow）→ 绝不触碰；
        //   b) 位于 Detail 类 Activity 且当前非清屏 → 同样视为浏览态，跳过。
        // 主 Feed（MainActivity）与清屏全屏（cleanActive）放行 —— "只处理全屏界面"。
        if (state.imagePost) {
            if (panelShown(state)) {
                undo(video);
                Log.d(TAG, "skip detail image browse (panel shown)");
                return;
            }
            if (isDetailActivity(video) && !cleanActive()) {
                undo(video);
                Log.d(TAG, "skip detail activity (not clean mode)");
                return;
            }
        }
        // 门控三：非全屏容器（搜索卡片等）保持抖音原生 cover 填充
        if (!isFullscreenLevel(container, cw, ch)) {
            undo(video);
            Log.d(TAG, "skip non-fullscreen container " + cw + "x" + ch);
            return;
        }

        if (state.draweeFit) {
            applyScaleType(video);
            return;
        }
        fitLayoutParams(video, cw, ch, state);
    }

    // ------------------------------------------- 路径一：TextureView 收缩

    private static void fitLayoutParams(View video, int cw, int ch, FitState state) {
        float aspect = effectiveAspect(video, state);
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
                + targetW + "x" + targetH + " (aspect=" + aspect + ")");
    }

    // ----------------------------------- 路径二：DraweeView scaleType 适配

    /**
     * 把静态图文的显示方式改为 FIT（层级 scaleType → FIT_CENTER）。
     * 不改 LayoutParams：捏合缩放、多图切换、容器变形全部不受影响，
     * 还原时恢复原始 scaleType 即可。反射失败仅记日志，不影响视频路径。
     */
    private static void applyScaleType(View video) {
        if (!(video instanceof ImageView)) {
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
            Method getScale = hierarchy.getClass().getMethod("getActualImageScaleType");
            Method setScale = hierarchy.getClass().getMethod("setActualImageScaleType", stCls);
            Object cur = getScale.invoke(hierarchy);

            ImageView iv = (ImageView) video;
            if (!SCALE_ORIGS.containsKey(video)) {
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
            if (changed) {
                Log.d(TAG, "scale-fit DraweeView -> FIT_CENTER");
            }
        } catch (Throwable t) {
            logOnce("scale-fit reflection failed: " + t);
        }
    }

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
            logOnce("scale restore reflection failed: " + t);
        }
    }

    // ---------------------------------------------------------- 门控与还原

    /**
     * 容器是否为"全屏级"（主 Feed / 沉浸式播放页那样的整屏容器）。
     * 阈值：宽 ≥ 窗口 85%（排除双列/单列卡片）且高 ≥ 窗口 40%
     * （排除通栏 16:9 预览，保留评论面板打开时的半屏 Feed 容器）。
     * 独立小窗（后台播放）窗口本身就是小窗尺寸，容器≈窗口，判断自然通过。
     */
    private static boolean isFullscreenLevel(View container, int cw, int ch) {
        View root = container.getRootView();
        int rw = root != null ? root.getWidth() : 0;
        int rh = root != null ? root.getHeight() : 0;
        if (rw <= 0 || rh <= 0) {
            return true; // 窗口还没测量出来：按全屏处理，避免主 Feed 因时序漏适配
        }
        return cw >= rw * 0.85f && ch >= rh * 0.40f;
    }

    /** 该状态对应的详情页面板是否可见（仅图片类内容关心）。读不到视为不可见。 */
    private static boolean panelShown(FitState state) {
        if (state.params == null) {
            return false;
        }
        Object params = state.params.get();
        if (params == null) {
            // 弱引用已清：持有者已销毁，无法证明不在浏览态 → 保守按浏览态处理
            return true;
        }
        try {
            Field f = panelField;
            if (f == null) {
                f = params.getClass().getField("isDetailPagePanelShow");
                panelField = f;
            }
            return f.getBoolean(params);
        } catch (Throwable t) {
            return false;
        }
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
    private static boolean isCommentContext(View video, FitState state) {
        // 判据 1：视图树（从渲染 View 向上走，评论容器类名通常带 comment/cmt）
        for (View v = video; v != null; ) {
            if (looksComment(v.getClass().getName())) {
                return true;
            }
            ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        // 判据 2：宿主 Activity
        Activity act = activityOf(video);
        if (act != null && looksComment(act.getClass().getName())) {
            return true;
        }
        // 判据 3：params 上的 Fragment / pageId 字段
        Object params = (state.params != null) ? state.params.get() : null;
        if (params != null) {
            String[] fragFields = {"fragment", "commentFragment", "feedItemFragment"};
            for (String fn : fragFields) {
                try {
                    Object frag = params.getClass().getField(fn).get(params);
                    if (frag != null && looksComment(frag.getClass().getName())) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // 字段不存在或不可访问，试下一个
                }
            }
            try {
                Object pageId = params.getClass().getField("commentFeedPageId").get(params);
                if (pageId instanceof String && !((String) pageId).isEmpty()) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 字段不可达
            }
        }
        // 判据 4：可见评论 Fragment 的视图包含该 View（时序竞态兜底）
        if (insideVisibleCommentFragment(video, act)) {
            return true;
        }
        // 判据 5：全屏级独立窗口（评论 Dialog 等覆盖式弹窗）
        return inForeignFullscreenWindow(video, act);
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
     * FragmentManager 反射拿不到时保守返回 false（其余判据继续）。
     */
    private static boolean insideVisibleCommentFragment(View video, Activity act) {
        if (act == null) {
            return false;
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
                        return true;
                    }
                    ViewParent p = v.getParent();
                    v = (p instanceof View) ? (View) p : null;
                }
            } catch (Throwable ignored) {
                // 该 Fragment 反射失败，试下一个
            }
        }
        return false;
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
     * video 是否位于一个全屏级、但不属于宿主 Activity 的独立窗口
     * （评论面板 CommentFeedDialogFragment 是 Dialog，decorView 与 Activity 不同）。
     * 后台小窗等非全屏独立窗口不算（尺寸门控与白名单已覆盖它们）。
     * 窗口尚未测量（宽高为 0）时无法判定，返回 false 交由后续 layout 重算。
     */
    private static boolean inForeignFullscreenWindow(View video, Activity act) {
        if (act == null || act.getWindow() == null) {
            return false;
        }
        View decor = act.getWindow().getDecorView();
        View root = video.getRootView();
        if (decor == null || root == null || root == decor) {
            return false; // 同一窗口（主 Feed / 详情 / 清屏都在 Activity 自己的窗口）
        }
        int dw = decor.getWidth();
        int dh = decor.getHeight();
        int rw = root.getWidth();
        int rh = root.getHeight();
        if (dw <= 0 || dh <= 0 || rw <= 0 || rh <= 0) {
            return false;
        }
        return rw >= dw * 0.85f && rh >= dh * 0.50f;
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
     * 是否位于详情类 Activity（DetailActivity / UltraDetailActivity 等）。
     * 详情页 = 图文浏览与清屏全屏的发生地；主 Feed 在 MainActivity，不匹配。
     * 类名含 "DetailActivity" 子串判定（UltraDetailActivity 亦命中）。
     */
    private static boolean isDetailActivity(View video) {
        Activity act = activityOf(video);
        if (act == null) {
            return false;
        }
        return act.getClass().getName().contains("DetailActivity");
    }

    /**
     * 清屏状态变化后全量重算：遍历已跟踪的 View 重新走 fit()
     * （进入清屏 → 图片开始收缩；退出清屏回详情 → 面板门控自动还原）。
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

    private static void logOnce(String msg) {
        if (!scaleReflectLogged) {
            scaleReflectLogged = true;
            Log.e(TAG, msg);
        }
    }
}
