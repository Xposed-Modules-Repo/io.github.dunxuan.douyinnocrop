package io.github.dunxuan.douyinnocrop;

import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * 抖音不裁剪 —— 抖音视频按原始比例完整显示，不再裁剪（video-fit 分支：纯视频）。
 *
 * 生效机制（经日志验证，见 FitApplier）：
 *   绑定时读取内容宽高比 → 收缩渲染 SurfaceView 的 LayoutParams 为内容比例的
 *   最大内接矩形 → SurfaceView 按比例缩放缓冲区，cover 等价于 fit。
 *   容器 layout 监听对抗抖音 FeedAllScreenHelper 等的覆写，幂等重写。
 *
 * 四条钩子：
 *   1. VideoItemParams.getAweme()                 —— 视频/动图绑定，取比例收缩渲染 View；
 *   2. KeepSurfaceTextureView.onAttachedToWindow() —— attach 时序补偿（TextureView 路径）；
 *   3. CleanModeViewModel.sA()                     —— 清屏状态进出，驱动全量重算/还原；
 *   4. Activity.onResume()                         —— 返回 Feed 后的全量重算（死区兜底）。
 *
 * 依赖（均已用 droidasc 在抖音 40.6.0 上验证为未混淆的稳定名字）：
 *   - com.ss.android.ugc.aweme.feed.model.VideoItemParams#getAweme()
 *   - VideoItemParams#mAweme / #mBaseFeedPlayerView
 *   - com.ss.android.ugc.aweme.feed.model.Aweme#getVideo() / #imageInfos
 *   - BaseFeedPlayerView#getVideoView()
 *   - com.ss.android.ugc.playerkit.videoview.KeepSurfaceTextureView#onAttachedToWindow()
 *   - CleanModeViewModel#sA(CleanModeCommand, String, boolean) + #a（清屏活跃列表）
 */
public final class NoCropModule extends XposedModule {

    static final String TAG = "DouYinNoCrop";

    /** 走 logcat 的日志（XposedModule.log 只进 LSPosed 日志，排障时 logcat 抓不到）。 */
    private static void alog(int priority, String msg) {
        android.util.Log.println(priority, TAG, msg);
    }

    private static void alog(int priority, String msg, Throwable t) {
        android.util.Log.println(priority, TAG, msg + "\n"
                + android.util.Log.getStackTraceString(t));
    }

    private static final String TARGET_PACKAGE = "com.ss.android.ugc.aweme";
    private static final String PARAMS_CLASS =
            "com.ss.android.ugc.aweme.feed.model.VideoItemParams";
    private static final String TEXTURE_VIEW_CLASS =
            "com.ss.android.ugc.playerkit.videoview.KeepSurfaceTextureView";
    private static final String CLEAN_VM_CLASS =
            "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.cleanmode.CleanModeViewModel";
    private static final String CLEAN_CMD_CLASS =
            "com.ss.android.ugc.aweme.feed.cleanmode.CleanModeCommand";

    /** 每个 ClassLoader 只安装一次（进程可能加载多个包） */
    private static final Map<ClassLoader, Boolean> INSTALLED = new WeakHashMap<>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        alog(Log.INFO, "loaded in " + param.getProcessName()
                + ", framework=" + getFrameworkName()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        synchronized (INSTALLED) {
            if (INSTALLED.containsKey(param.getClassLoader())) {
                alog(Log.DEBUG, "hooks already installed");
                return;
            }
            INSTALLED.put(param.getClassLoader(), Boolean.TRUE);
        }
        installVideoBindHook(param.getClassLoader());
        installTextureAttachHook(param.getClassLoader());
        installCleanModeHook(param.getClassLoader());
        installResumeRefitHook(param.getClassLoader());
        alog(Log.INFO, "hooks installed for " + param.getPackageName());
    }

    /**
     * Hook VideoItemParams.getAweme()：
     * 1) 绑定/换视频时取到 Aweme → 提取宽高比（Video 字段优先，回退 imageInfos）；
     * 2) 通过 params.mBaseFeedPlayerView.getVideoView() 拿到渲染 View 并收缩适配；
     * 3) 读不到宽高比时释放该 View（还原 + 忘记旧比例），绝不套用旧内容的比例。
     */
    private void installVideoBindHook(ClassLoader loader) {
        try {
            Class<?> paramsCls = Class.forName(PARAMS_CLASS, false, loader);
            Method getAweme = paramsCls.getDeclaredMethod("getAweme");
            Field awemeField = paramsCls.getField("mAweme");
            Field playerField = paramsCls.getField("mBaseFeedPlayerView");

            hook(getAweme)
                    .setId("nocrop-video-bind")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object params = chain.getThisObject();
                            Object aweme = result != null ? result : awemeField.get(params);
                            if (aweme == null) {
                                return result; // 瞬时解绑：保持现状
                            }
                            float aspect = VideoAspect.read(aweme);
                            Object player = playerField.get(params);
                            View view = resolveVideoView(player);
                            if (aspect > 0f) {
                                // player 一并记录：attach 补偿时验证"该播放器的渲染 View"
                                // 才是本 View，杜绝评论区 TextureView 误吃 pending
                                FitApplier.apply(view, new FitApplier.FitState(
                                        aspect, VideoAspect.aid(aweme), params, player));
                            } else {
                                FitApplier.release(view);
                            }
                        } catch (Throwable t) {
                            alog(Log.DEBUG, "bind-time apply skipped: " + t);
                        }
                        return result;
                    });
            alog(Log.INFO, "hooked VideoItemParams.getAweme");
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook VideoItemParams.getAweme", t);
        }
    }

    /**
     * Hook KeepSurfaceTextureView.onAttachedToWindow()：
     * 覆盖"attach 晚于最后一次 getAweme"的时序；只用该 View 自己记录的
     * 状态或最近一次 pending（带播放器归属验证），不碰全局比例。幂等。
     */
    private void installTextureAttachHook(ClassLoader loader) {
        try {
            Class<?> viewCls = Class.forName(TEXTURE_VIEW_CLASS, false, loader);
            Method onAttach = viewCls.getDeclaredMethod("onAttachedToWindow");
            hook(onAttach)
                    .setId("nocrop-texture-attach")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            FitApplier.applyOnAttach((View) chain.getThisObject());
                        } catch (Throwable t) {
                            alog(Log.DEBUG, "attach-time apply skipped: " + t);
                        }
                        return r;
                    });
            alog(Log.INFO, "hooked KeepSurfaceTextureView.onAttachedToWindow");
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook KeepSurfaceTextureView", t);
        }
    }

    /**
     * Hook CleanModeViewModel.sA(CleanModeCommand, String, boolean)：
     * 清屏进入/退出的状态机入口（详情与 Feed 共用该 ViewModel 类）。
     * sA 执行后直接读该 VM 的活跃来源列表 a（LinkedList，语义名）的空否——
     * 这是状态机真值，避免镜像命令在守卫分支下漂移。
     * 任一 VM 活跃 = 清屏中；随后全量重算：进清屏 → 收缩，退清屏 → 还原。
     */
    private void installCleanModeHook(ClassLoader loader) {
        try {
            Class<?> vmCls = Class.forName(CLEAN_VM_CLASS, false, loader);
            Method sA = vmCls.getDeclaredMethod("sA",
                    Class.forName(CLEAN_CMD_CLASS, false, loader),
                    String.class, boolean.class);
            // CleanModeViewModel.a：活跃清屏来源列表（LinkedList，空 = 非清屏）
            final Field activeList = vmCls.getField("a");

            hook(sA)
                    .setId("nocrop-clean-mode")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            Object vm = chain.getThisObject();
                            Object list = activeList.get(vm);
                            boolean active = (list instanceof java.util.Collection)
                                    && !((java.util.Collection<?>) list).isEmpty();
                            FitApplier.noteCleanVm(vm, active);
                            FitApplier.refitAll();
                        } catch (Throwable t) {
                            alog(Log.DEBUG, "clean-mode refit skipped: " + t);
                        }
                        return r;
                    });
            alog(Log.INFO, "hooked CleanModeViewModel.sA");
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook CleanModeViewModel.sA", t);
        }
    }

    /**
     * Hook Activity.onResume()：从搜索/分享/其他页面返回 Feed 时全量重算。
     * 实测：返回后 getAweme 不再触发、layout 无变化 → fit 永不重跑，
     * 该 View 沿用旧尺寸（表现为"返回后又裁剪"），resume 兜底。
     */
    private void installResumeRefitHook(ClassLoader loader) {
        try {
            Class<?> activityCls = Class.forName("android.app.Activity");
            // onResume 是 protected：必须用 getDeclaredMethod
            Method m = activityCls.getDeclaredMethod("onResume");
            hook(m)
                    .setId("nocrop-activity-resume")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            FitApplier.refitAll();
                        } catch (Throwable t) {
                            alog(Log.DEBUG, "resume refit skipped: " + t);
                        }
                        return r;
                    });
            alog(Log.INFO, "hooked Activity.onResume");
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook Activity.onResume", t);
        }
    }

    /** playerView（BaseFeedPlayerView 子类）→ 真正的渲染 View（TextureView 或 SurfaceView 容器）。 */
    private static View resolveVideoView(Object playerView) {
        if (playerView == null) {
            return null;
        }
        try {
            Method getter = playerView.getClass().getMethod("getVideoView");
            Object v = getter.invoke(playerView);
            return (v instanceof View) ? (View) v : null;
        } catch (Throwable ignored) {
            // 不同 holder 子类可能没有该方法（如空播放器），跳过即可
            return null;
        }
    }
}
