package io.github.dunxuan.douyinnocrop;

import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * 抖音不裁剪 —— 抖音视频按原始比例完整显示，不再左右裁剪。
 *
 * 原理：抖音播放引擎按 cover（放大填满）模式把视频渲进与容器同尺寸的
 * surface，视频比容器"宽"时左右内容被裁掉。本模块把视频渲染 View 的
 * LayoutParams 收缩为"视频宽高比在容器内的最大内接矩形"（gravity 居中保留），
 * surface 宽高比 == 视频宽高比后，cover 渲染在数学上等价于 fit，
 * 裁剪自然消失，容器多余部分显示背景。
 *
 * 依赖（均已用 droidasc 在抖音 40.6.0 上验证为未混淆的稳定名字）：
 *   - com.ss.android.ugc.aweme.feed.model.VideoItemParams#getAweme()   (接口方法，稳定)
 *   - com.ss.android.ugc.aweme.feed.model.VideoItemParams#mAweme / #mBaseFeedPlayerView  (public 字段)
 *   - com.ss.android.ugc.aweme.feed.model.Aweme#getVideo()
 *   - BaseFeedPlayerView#getVideoView()
 *   - com.ss.android.ugc.playerkit.videoview.KeepSurfaceTextureView#onAttachedToWindow()
 */
public final class NoCropModule extends XposedModule {

    static final String TAG = "DouYinNoCrop";

    private static final String TARGET_PACKAGE = "com.ss.android.ugc.aweme";
    private static final String PARAMS_CLASS =
            "com.ss.android.ugc.aweme.feed.model.VideoItemParams";
    private static final String TEXTURE_VIEW_CLASS =
            "com.ss.android.ugc.playerkit.videoview.KeepSurfaceTextureView";

    /** 每个 ClassLoader 只安装一次（进程可能加载多个包） */
    private static final Map<ClassLoader, Boolean> INSTALLED = new WeakHashMap<>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName()
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
                log(Log.DEBUG, TAG, "hooks already installed");
                return;
            }
            INSTALLED.put(param.getClassLoader(), Boolean.TRUE);
        }
        installVideoBindHook(param.getClassLoader());
        installTextureAttachHook(param.getClassLoader());
        log(Log.INFO, TAG, "hooks installed for " + param.getPackageName());
    }

    /**
     * Hook VideoItemParams.getAweme()：
     * 1) 绑定/换视频时取到 Aweme → Video，提取宽高比；
     * 2) 顺手通过 params.mBaseFeedPlayerView.getVideoView() 拿到当前渲染 View 并立即适配。
     * 该 getter 是接口方法且被抖音内部大量调用，每次换视频必然触发。
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
                            float aspect = VideoAspect.read(aweme);
                            Object player = playerField.get(params);
                            View view = resolveVideoView(player);
                            if (view != null && aspect > 0f) {
                                FitApplier.apply(view, aspect);
                            }
                        } catch (Throwable t) {
                            log(Log.DEBUG, TAG, "bind-time apply skipped: " + t);
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "hooked VideoItemParams.getAweme");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to hook VideoItemParams.getAweme", t);
        }
    }

    /**
     * Hook KeepSurfaceTextureView.onAttachedToWindow()：
     * TextureView 路径下 View 被（重新）加入容器时补一次适配，
     * 覆盖"attach 晚于最后一次 getAweme"的时序；幂等，重复执行无害。
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
                            View v = (View) chain.getThisObject();
                            float aspect = VideoAspect.lastGood();
                            if (aspect > 0f) {
                                FitApplier.apply(v, aspect);
                            }
                        } catch (Throwable t) {
                            log(Log.DEBUG, TAG, "attach-time apply skipped: " + t);
                        }
                        return r;
                    });
            log(Log.INFO, TAG, "hooked KeepSurfaceTextureView.onAttachedToWindow");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to hook KeepSurfaceTextureView", t);
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
