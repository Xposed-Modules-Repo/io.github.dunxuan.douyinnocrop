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
 * 范围（"只处理全屏界面"）：
 *   - 主 Feed / 沉浸式播放页全屏视频、动图、图文 → 收缩为内接矩形（或图文改 FIT）；
 *   - 清屏全屏 → 同上；
 *   - 详情图文浏览界面（描述面板可见、可捏合缩放）、搜索卡片 → 一律不触碰。
 *
 * 三条钩子路径：
 *   1. VideoItemParams.getAweme()          —— 视频/动图绑定，取宽高比收缩渲染 View；
 *   2. KeepSurfaceTextureView.onAttachedToWindow() —— attach 时序补偿；
 *   3. FeedImageViewHolder.bind(Aweme)      —— 静态图文封面（DraweeView 改 scaleType）；
 *   4. CleanModeViewModel.sA()              —— 清屏状态进出，驱动全量重算/还原。
 *
 * 依赖（均已用 droidasc 在抖音 40.6.0 上验证为未混淆的稳定名字）：
 *   - com.ss.android.ugc.aweme.feed.model.VideoItemParams#getAweme()
 *   - VideoItemParams#mAweme / #mBaseFeedPlayerView / #isDetailPagePanelShow
 *   - com.ss.android.ugc.aweme.feed.model.Aweme#getVideo() / #imageInfos
 *   - BaseFeedPlayerView#getVideoView()
 *   - com.ss.android.ugc.playerkit.videoview.KeepSurfaceTextureView#onAttachedToWindow()
 *   - FeedImageViewHolder#bind(Aweme)（封面 RemoteImageView 按字段类型定位）
 *   - CleanModeViewModel#sA(CleanModeCommand, String, boolean)
 */
public final class NoCropModule extends XposedModule {

    static final String TAG = "DouYinNoCrop";

    private static final String TARGET_PACKAGE = "com.ss.android.ugc.aweme";
    private static final String PARAMS_CLASS =
            "com.ss.android.ugc.aweme.feed.model.VideoItemParams";
    private static final String TEXTURE_VIEW_CLASS =
            "com.ss.android.ugc.playerkit.videoview.KeepSurfaceTextureView";
    private static final String IMAGE_HOLDER_CLASS =
            "com.ss.android.ugc.aweme.feed.adapter.FeedImageViewHolder";
    private static final String CLEAN_VM_CLASS =
            "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.cleanmode.CleanModeViewModel";
    private static final String CLEAN_CMD_CLASS =
            "com.ss.android.ugc.aweme.feed.cleanmode.CleanModeCommand";
    private static final String REMOTE_IMAGE_VIEW_CLASS =
            "com.ss.android.ugc.aweme.base.ui.RemoteImageView";

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
        FitApplier.init(param.getClassLoader());
        installVideoBindHook(param.getClassLoader());
        installTextureAttachHook(param.getClassLoader());
        installImageHolderHook(param.getClassLoader());
        installCleanModeHook(param.getClassLoader());
        installPlayerSizeHook(param.getClassLoader());
        log(Log.INFO, TAG, "hooks installed for " + param.getPackageName());
    }

    /**
     * Hook VideoItemParams.getAweme()：
     * 1) 绑定/换视频时取到 Aweme → 提取宽高比（视频字段优先，图文回退 imageInfos）；
     * 2) 顺手通过 params.mBaseFeedPlayerView.getVideoView() 拿到渲染 View 并适配；
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
                            boolean imagePost = VideoAspect.isImagePost(aweme);
                            Object player = playerField.get(params);
                            View view = resolveVideoView(player);
                            if (aspect > 0f) {
                                // player 一并记录：attach 补偿时验证"该播放器的渲染 View"
                                // 才是本 View，杜绝评论区 TextureView 误吃 pending
                                FitApplier.apply(view, new FitApplier.FitState(
                                        aspect, imagePost, params, false,
                                        VideoAspect.aid(aweme), player));
                            } else {
                                FitApplier.release(view);
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
     * 覆盖"attach 晚于最后一次 getAweme"的时序；只用该 View 自己记录的
     * 状态或最近一次视频 pending，不碰全局比例。幂等。
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
                            log(Log.DEBUG, TAG, "attach-time apply skipped: " + t);
                        }
                        return r;
                    });
            log(Log.INFO, TAG, "hooked KeepSurfaceTextureView.onAttachedToWindow");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to hook KeepSurfaceTextureView", t);
        }
    }

    /**
     * Hook FeedImageViewHolder.bind(Aweme) 及子类重载（FullFeed/Detail 继承或重写）：
     * 静态图文封面是 Fresco DraweeView，改 scaleType 而非 LayoutParams——
     * 不破坏捏合缩放，多图切换由 Fresco 自动处理。封面 View 按字段类型
     * （RemoteImageView）定位，不依赖混淆字段名。面板/详情/清屏门控在 fit() 内统一执行。
     */
    private void installImageHolderHook(ClassLoader loader) {
        String[] holderNames = {
                IMAGE_HOLDER_CLASS,
                "com.ss.android.ugc.aweme.feed.adapter.FullFeedImageViewHolder",
                "com.ss.android.ugc.aweme.detail.ui.DetailFeedImageViewHolder",
        };
        Class<?> awemeCls;
        Class<?> paramsCls;
        Class<?> rivCls;
        try {
            awemeCls = Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme", false, loader);
            paramsCls = Class.forName(PARAMS_CLASS, false, loader);
            rivCls = Class.forName(REMOTE_IMAGE_VIEW_CLASS, false, loader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to resolve image holder deps", t);
            return;
        }
        int hooked = 0;
        for (String holderName : holderNames) {
            try {
                Class<?> holderCls = Class.forName(holderName, false, loader);
                hooked += hookImageBind(holderCls, awemeCls, paramsCls, rivCls);
            } catch (Throwable t) {
                log(Log.DEBUG, TAG, "image holder not hookable: " + holderName + " (" + t + ")");
            }
        }
        if (hooked > 0) {
            log(Log.INFO, TAG, "hooked image holder binds: " + hooked);
        }
    }

    /** 对单个 holder 类尝试 hook 其声明的 bind 重载；返回实际 hook 数。 */
    private int hookImageBind(Class<?> holderCls, Class<?> awemeCls,
                              Class<?> paramsCls, Class<?> rivCls) {
        int count = 0;
        Class<?>[][] sigs = {
                {awemeCls},
                {awemeCls, int.class},
        };
        for (Class<?>[] sig : sigs) {
            Method bind;
            try {
                bind = holderCls.getDeclaredMethod("bind", sig);
            } catch (Throwable ignored) {
                continue; // 该类未声明此重载（继承的由父类 hook 覆盖）
            }
            hook(bind)
                    .setId("nocrop-image-bind-" + holderCls.getSimpleName() + "-" + sig.length)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object holder = chain.getThisObject();
                            Object aweme = chain.getArgs().get(0);
                            if (aweme != null && VideoAspect.isImagePost(aweme)) {
                                Object coverObj = findFieldOfType(holder, rivCls, null);
                                Object params = findFieldOfType(holder, paramsCls, null);
                                if (coverObj instanceof View) {
                                    FitApplier.apply((View) coverObj, new FitApplier.FitState(
                                            VideoAspect.read(aweme), true, params, true,
                                            VideoAspect.aid(aweme)));
                                } else {
                                    log(Log.DEBUG, TAG, "image holder: cover view not found");
                                }
                            }
                        } catch (Throwable t) {
                            log(Log.DEBUG, TAG, "image bind apply skipped: " + t);
                        }
                        return result;
                    });
            count++;
        }
        return count;
    }

    /**
     * Hook CleanModeViewModel.sA(CleanModeCommand, String, boolean)：
     * 清屏进入/退出的状态机入口（详情与 Feed 共用该 ViewModel 类）。
     * sA 执行后直接读该 VM 的活跃来源列表 a（LinkedList，语义名）的空否——
     * 这是状态机真值，避免镜像命令在守卫分支下漂移。
     * 任一 VM 活跃 = 清屏中；随后全量重算：进清屏 → 图文收缩，退清屏 → 面板门控还原。
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
                            log(Log.DEBUG, TAG, "clean-mode refit skipped: " + t);
                        }
                        return r;
                    });
            log(Log.INFO, TAG, "hooked CleanModeViewModel.sA");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to hook CleanModeViewModel.sA", t);
        }
    }

    /**
     * Hook VideoPatchLayout.onVideoSizeChanged(VideoStateInquirer, PlayEntity, int, int)：
     * 播放器上报**真实解码宽高**的权威回调（Feed 与详情页共用 videoshop 链路，
     * LayerHostMediaLayout 继承并 super 转发到此类）。
     * Aweme 字段可能给的是封面/海报尺寸、多图帖的 imageInfos[0] 也可能不是当前显示的图
     * —— 此前图文界面动图"退出放大后变窄、比例错误"的根因就是错误比例被写进绝对像素。
     * 回调携带的 (width, height) 覆盖猜测值并立即重算；aid 记账保证内容切换后旧值作废。
     * 参数顺序 sanity：若 w/h 比例越界则交换（防个别实现顺序相反）。
     */
    private void installPlayerSizeHook(ClassLoader loader) {
        try {
            Class<?> layoutCls = Class.forName(
                    "com.ss.android.videoshop.mediaview.VideoPatchLayout", false, loader);
            Class<?> inquirerCls = Class.forName(
                    "com.ss.android.videoshop.api.VideoStateInquirer", false, loader);
            Class<?> entityCls = Class.forName(
                    "com.ss.android.videoshop.entity.PlayEntity", false, loader);
            Method m = layoutCls.getDeclaredMethod(
                    "onVideoSizeChanged", inquirerCls, entityCls, int.class, int.class);

            hook(m)
                    .setId("nocrop-player-size")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            java.util.List<?> args = chain.getArgs();
                            int w = (Integer) args.get(2);
                            int h = (Integer) args.get(3);
                            if (w > 0 && h > 0) {
                                float aspect = (float) w / (float) h;
                                if (aspect < 0.1f || aspect > 20f) {
                                    int t = w;
                                    w = h;
                                    h = t; // 个别实现顺序相反，交换后重验
                                    aspect = (float) w / (float) h;
                                }
                                if (aspect >= 0.1f && aspect <= 20f
                                        && chain.getThisObject() instanceof View) {
                                    FitApplier.onPlayerLayoutSize(
                                            (View) chain.getThisObject(), w, h);
                                }
                            }
                        } catch (Throwable t) {
                            log(Log.DEBUG, TAG, "player-size apply skipped: " + t);
                        }
                        return r;
                    });
            log(Log.INFO, TAG, "hooked VideoPatchLayout.onVideoSizeChanged");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "failed to hook VideoPatchLayout.onVideoSizeChanged", t);
        }
    }

    /** 按字段类型在对象（含父类）上找第一个匹配字段并取值。找不到返回 fallback。 */
    private static Object findFieldOfType(Object obj, Class<?> type, Object fallback) {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return f.get(obj);
                    } catch (Throwable ignored) {
                        // 继续找下一个
                    }
                }
            }
        }
        return fallback;
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
