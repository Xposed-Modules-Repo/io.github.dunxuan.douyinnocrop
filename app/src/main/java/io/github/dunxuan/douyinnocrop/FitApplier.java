package io.github.dunxuan.douyinnocrop;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 视频全屏适配器——把视频/动图渲染 SurfaceView 收缩为"内容比例的最大内接矩形"，
 * 使 surface 比例 == 内容比例：SurfaceView 原生按比例缩放缓冲区，cover 等价于 fit，
 * 消除裁剪。这是本模块唯一生效机制（经日志验证：绑定时写 LP + 容器 layout 监听
 * 抗抖音 FeedAllScreenHelper/FeedLandscapeEntranceV2 的覆写）。
 *
 * 门控（只处理用户点名的全屏界面）：
 *   1) 评论区一律不碰（视图树/Activity/params Fragment/可见评论 Fragment 子树/
 *      全屏级独立窗口 五重判据）；
 *   2) 场所白名单：主 Feed（*MainActivity）、详情播放器（*DetailActivity）、
 *      清屏（cleanActive）——搜索列表/广告/未知上下文还原；
 *   3) 直播上下文不碰；
 *   4) 容器必须全屏级（宽 ≥ 窗口 85% 且高 ≥ 窗口 30%）；
 *   容器尺寸变化时（进/出全屏、面板开合）通过持久 layout 监听自动重算/还原。
 *
 * 状态按 View 记账（FitState 弱表）；原始尺寸记账于弱表，还原时先比对
 * 是否仍是我们写入的值。全部操作保证在主线程执行。
 */
final class FitApplier {

    private static final String TAG = "DouYinNoCrop";

    /** 某条内容绑定到某个渲染 View 的收缩状态。 */
    static final class FitState {
        final float aspect;
        /** 内容 aid（帖子 id）：日志与 View 复用识别。 */
        final String aid;
        final WeakReference<Object> params;
        /** 绑定时的播放器对象（BaseFeedPlayerView）：attach 补偿时验证
         *  "这个 View 确实是该播放器的渲染 View"，防止评论区等无关 TextureView
         *  误吃 pending（评论区 holder 不用 VideoItemParams，判据全会落空）。 */
        final WeakReference<Object> player;

        FitState(float aspect, String aid, Object params, Object player) {
            this.aspect = aspect;
            this.aid = aid;
            this.params = (params != null) ? new WeakReference<>(params) : null;
            this.player = (player != null) ? new WeakReference<>(player) : null;
        }
    }

    /** video → 当前状态；弱引用，仅主线程读写。 */
    private static final Map<View, FitState> STATES = new WeakHashMap<>();

    /** video → {原始宽, 原始高, 本模块写入的宽, 本模块写入的高}；仅主线程。 */
    private static final Map<View, int[]> ORIGINALS = new WeakHashMap<>();

    /** video → 当前挂载的容器尺寸监听；容器变化时换挂。仅主线程。 */
    private static final Map<View, Watch> WATCHES = new WeakHashMap<>();

    /** video → 最近一次 skip 原因；同一原因只打一条，避免 getAweme 高频轮询刷屏。 */
    private static final Map<View, String> SKIP_LOGGED = new WeakHashMap<>();

    /** video → 已打过 apply 日志的内容 key（aid|比例），换内容才再打。 */
    private static final Map<View, String> APPLY_LOGGED = new WeakHashMap<>();

    /**
     * 各 CleanModeViewModel 实例的清屏状态（vm → 是否清屏中）。
     * 由 CleanModeViewModel.sA 钩子在状态机执行后读取其活跃来源列表 a 的真实值，
     * 不镜像命令（命令存在守卫分支，镜像会漂移）。任一 VM 活跃即视为清屏中。
     */
    private static final Map<Object, Boolean> CLEAN_VMS = new WeakHashMap<>();

    /** 绑定成功但渲染 View 尚未创建时的状态（attach 补偿消费）。 */
    private static volatile FitState pending;

    private FitApplier() {
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

    /** 内容绑定（VideoItemParams.getAweme）时调用：记录状态并适配当前 View。 */
    static void apply(final View video, final FitState state) {
        if (state == null || state.aspect <= 0.05f) {
            return;
        }
        if (video != null) {
            String key = state.aid + "|" + Math.round(state.aspect * 1000f);
            String prev = APPLY_LOGGED.get(video);
            if (!key.equals(prev)) {
                APPLY_LOGGED.put(video, key);
                Log.d(TAG, "apply view=" + video.getClass().getName()
                        + " aid=" + state.aid
                        + " aspect=" + state.aspect);
            }
        }
        pending = state;
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
                if (state == null || state.aspect <= 0.05f) {
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
                unwatch(video);
                undo(video);
            }
        });
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

    /** 主线程上的适配主流程：记状态 + 挂监听 + 执行适配。 */
    private static void applyMain(View video, FitState state) {
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

        // 门控一：评论区一律不碰（评论动图/评论图片查看器/评论 feed，无论容器多像全屏）。
        String commentJudge = commentJudge(video, state);
        if (commentJudge != null) {
            undo(video);
            logSkip(video, commentJudge);
            return;
        }
        // 门控二：场所白名单——只处理用户点名的三处：
        //   主 Feed（MainActivity 家族）、详情全屏播放器（*DetailActivity 家族）、清屏全屏（cleanActive）。
        //   搜索列表、广告页、商城等其余界面一律还原、保持抖音原生行为。
        if (!inAllowedScope(video)) {
            undo(video);
            logSkip(video, "out-of-scope context");
            return;
        }
        // 门控三：直播上下文不在范围内（直播封面/直播间组件一律还原）。
        if (isLiveContext(video)) {
            undo(video);
            logSkip(video, "live context");
            return;
        }
        // 门控四：非全屏容器（搜索卡片等）保持抖音原生 cover 填充
        if (!isFullscreenLevel(container, cw, ch)) {
            undo(video);
            logSkip(video, "non-fullscreen container " + cw + "x" + ch);
            return;
        }
        SKIP_LOGGED.remove(video); // 本次放行：清掉历史 skip 记录，下次跳过会重新打印

        fitLayoutParams(video, cw, ch, state);
    }

    private static void logSkip(View video, String reason) {
        String prev = SKIP_LOGGED.get(video);
        if (reason.equals(prev)) {
            return;
        }
        SKIP_LOGGED.put(video, reason);
        Log.d(TAG, "skip " + reason + " (view=" + video.getClass().getName() + ")");
    }

    // ---------------------------------------------------- LayoutParams 收缩

    private static void fitLayoutParams(View video, int cw, int ch, FitState state) {
        float aspect = state.aspect;
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
                + ",aid=" + state.aid
                + ",view=" + video.getClass().getName() + ")");
    }

    // ---------------------------------------------------------- 门控与还原

    /**
     * 容器是否为"全屏级"（主 Feed / 沉浸式播放页那样的整屏容器）。
     * 阈值：宽 ≥ 窗口 85%（排除双列/单列卡片）且高 ≥ 窗口 30%（排除通栏 16:9 预览）。
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
     * 命中的评论判据（null = 不在评论上下文）。返回值直接作为 skip 日志原因，
     * 便于运行时精确定位是哪条判据命中：
     *   1) 视图树：从渲染 View 向上任一祖先类名含 comment/cmt；
     *   2) 宿主 Activity 类名含 comment；
     *   3) params 的 fragment/commentFragment/feedItemFragment 指向评论类
     *      （Fragment 必须 isAdded——评论面板关闭后残留引用会永久误伤）
     *      或 commentFeedPageId 非空；
     *   4) 该 View 位于某个已添加、可见的评论 Fragment 的视图子树内
     *      （覆盖判据 3 的时序竞态：绑定瞬间 commentFragment 可能尚未回填）；
     *   5) 全屏级独立窗口（评论面板是 Dialog，decor ≠ Activity decor；
     *      必须已 attach——未 attach 时 getRootView 是脱离窗口的子树顶会误判）。
     * 评论区一律不触碰（用户明确要求）。
     */
    private static String commentJudge(View video, FitState state) {
        // 判据 1：视图树
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
     * video 是否位于一个全屏级、但不属于宿主 Activity 的独立窗口。
     * 关键前提：View 必须已 attach 到窗口——未 attach 时 getRootView() 返回的是
     * 脱离窗口的子树顶（播放器预挂载/重挂载中的 SurfaceView），必然 ≠ decor，
     * 会被误判成独立窗口，导致主 Feed 视频被错误还原。
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
                + " root=" + root.getClass().getName();
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
     * 覆盖 CommentFeedDialogFragment（Dialog 窗口）内嵌 CommentFeedFragment 的场景。
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
                try {
                    if ((Boolean) frag.getClass().getMethod("isHidden").invoke(frag)) {
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
     *   1) 主 Feed：MainActivity 家族
     *   2) 详情全屏播放器：*DetailActivity 家族
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
     * 判据：祖先链类名含 livesdk / livepreview / android.live / live.core
     * （避开 "deliver" 之类误伤）。
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
     * 全量重算：清屏状态变化 / Activity resume 时遍历已跟踪的 View 重新走 fit()
     * （返回 Feed 后 getAweme 不再触发、layout 无变化时的死区靠它兜底）。
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

    /** 撤销该 View 上本模块的适配（幂等还原原始 LayoutParams）。 */
    private static void undo(View video) {
        restoreLayoutParams(video);
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
                if (state == null || state.aspect <= 0.05f) {
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

    /** 还原 LayoutParams（仅当当前值仍是我们写入的值才覆盖）。 */
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
}
