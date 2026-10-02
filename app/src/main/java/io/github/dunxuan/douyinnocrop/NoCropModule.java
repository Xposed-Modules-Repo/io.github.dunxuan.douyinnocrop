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
        FitApplier.init(param.getClassLoader());
        installVideoBindHook(param.getClassLoader());
        installTextureAttachHook(param.getClassLoader());
        installImageHolderHook(param.getClassLoader());
        installCleanModeHook(param.getClassLoader());
        installPlayerSizeHook(param.getClassLoader());
        installFeedPlayerSizeHook(param.getClassLoader());
        installSurfaceTrackHook(param.getClassLoader());
        installResumeRefitHook(param.getClassLoader());
        alog(Log.INFO, "hooks installed for " + param.getPackageName());
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
                            logBindType(aweme, aspect, imagePost, view);
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
                            alog(Log.DEBUG, "bind-time apply skipped: " + t);
                        }
                        return result;
                    });
            alog(Log.INFO, "hooked VideoItemParams.getAweme");
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook VideoItemParams.getAweme", t);
        }
    }

    /** 按 aid 去重的绑定类型日志（同一内容只打一条）。 */
    private static final java.util.Map<String, Boolean> BIND_LOGGED =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, Boolean>());

    /**
     * 内容类型日志：区分视频/图文/图文内动图的关键证据。
     * awemeType：抖音内容大类；hasVideo：该条目挂没挂 Video（图文里的动图项通常有）；
     * imagePost：图文帖（imageInfos/images 非空）；aspect：猜测比例来源。
     */
    private static void logBindType(Object aweme, float aspect, boolean imagePost, View view) {
        try {
            String aid = VideoAspect.aid(aweme);
            String key = aid + "|" + aspect;
            synchronized (BIND_LOGGED) {
                if (BIND_LOGGED.containsKey(key)) {
                    return;
                }
                if (BIND_LOGGED.size() > 128) {
                    BIND_LOGGED.clear();
                }
                BIND_LOGGED.put(key, Boolean.TRUE);
            }
            int awemeType = -1;
            boolean hasVideo = false;
            String mediaCls = "none";
            try {
                awemeType = (Integer) aweme.getClass().getMethod("getAwemeType").invoke(aweme);
            } catch (Throwable ignored) {
                // 无该方法
            }
            try {
                Object video = aweme.getClass().getMethod("getVideo").invoke(aweme);
                hasVideo = video != null;
                if (video != null) {
                    mediaCls = video.getClass().getName();
                }
            } catch (Throwable ignored) {
                // 无该方法
            }
            alog(Log.DEBUG, "bind-type aid=" + aid
                    + " awemeType=" + awemeType
                    + " imagePost=" + imagePost
                    + " hasVideo=" + hasVideo
                    + " videoCls=" + mediaCls
                    + " aspect=" + aspect
                    + " view=" + (view != null ? view.getClass().getName() : "null"));
            // aspect=0 时 dump 两个图片列表，确认尺寸藏在哪个字段里
            if (aspect <= 0f) {
                for (String lf : new String[]{"imageInfos", "images"}) {
                    try {
                        Object lst = aweme.getClass().getField(lf).get(aweme);
                        int size = (lst instanceof java.util.List)
                                ? ((java.util.List<?>) lst).size() : -1;
                        String elem = "null";
                        Object first = (size > 0) ? ((java.util.List<?>) lst).get(0) : null;
                        if (first != null) {
                            elem = first.getClass().getName();
                            String dims = "?";
                            try {
                                dims = first.getClass().getField("width").get(first)
                                        + "x" + first.getClass().getField("height").get(first);
                            } catch (Throwable ignored) {
                                // 无尺寸字段
                            }
                            elem += "(" + dims + ")";
                        }
                        alog(Log.DEBUG, "aspect0 " + lf + " size=" + size + " elem=" + elem);
                    } catch (Throwable t) {
                        alog(Log.DEBUG, "aspect0 " + lf + " dump fail: " + t);
                    }
                }
            }
        } catch (Throwable t) {
            alog(Log.DEBUG, "bind-type log failed: " + t);
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
            alog(Log.ERROR, "failed to resolve image holder deps", t);
            return;
        }
        int hooked = 0;
        for (String holderName : holderNames) {
            try {
                Class<?> holderCls = Class.forName(holderName, false, loader);
                hooked += hookImageBind(holderCls, awemeCls, paramsCls, rivCls);
            } catch (Throwable t) {
                alog(Log.DEBUG, "image holder not hookable: " + holderName + " (" + t + ")");
            }
        }
        if (hooked > 0) {
            alog(Log.INFO, "hooked image holder binds: " + hooked);
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
                            if (aweme == null) {
                                alog(Log.DEBUG, "image bind: aweme=null");
                            } else if (!VideoAspect.isImagePost(aweme)) {
                                alog(Log.DEBUG, "image bind: not imagePost ("
                                        + aweme.getClass().getName() + ")");
                            } else {
                                Object coverObj = findFieldOfType(holder, rivCls, null);
                                Object params = findFieldOfType(holder, paramsCls, null);
                                alog(Log.DEBUG, "image bind aid=" + VideoAspect.aid(aweme)
                                        + " cover=" + (coverObj instanceof View
                                                ? coverObj.getClass().getName()
                                                : String.valueOf(coverObj))
                                        + " params=" + (params != null
                                                ? params.getClass().getSimpleName() : "null"));
                                if (coverObj instanceof View) {
                                    FitApplier.apply((View) coverObj, new FitApplier.FitState(
                                            VideoAspect.read(aweme), true, params, true,
                                            VideoAspect.aid(aweme)));
                                } else {
                                    alog(Log.DEBUG, "image holder: cover view not found");
                                }
                            }
                        } catch (Throwable t) {
                            alog(Log.DEBUG, "image bind apply skipped: " + t);
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
                            alog(Log.DEBUG, "cb onVideoSizeChanged " + w + "x" + h
                                    + " this=" + chain.getThisObject().getClass().getName());
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
                            alog(Log.DEBUG, "player-size apply skipped: " + t);
                        }
                        return r;
                    });
            alog(Log.INFO, "hooked VideoPatchLayout.onVideoSizeChanged");
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook VideoPatchLayout.onVideoSizeChanged", t);
        }
    }

    /**
     * Hook BaseFeedPlayerView.onVideoSizeChanged(String playId, int width, int height)：
     * Feed 播放器的真实宽高回调（OnUIPlayListener 接口、final 实现，播放器事件必经）。
     * 实测 videoshop 的 VideoPatchLayout.onVideoSizeChanged 在 Feed 中从不触发，
     * 动图等 imageInfos[0] 不准的内容只有这里能拿到权威比例。
     */
    private void installFeedPlayerSizeHook(ClassLoader loader) {
        try {
            Class<?> cls = Class.forName(
                    "com.ss.android.ugc.aweme.feed.ui.BaseFeedPlayerView", false, loader);
            Method m = cls.getDeclaredMethod("onVideoSizeChanged",
                    String.class, int.class, int.class);
            hook(m)
                    .setId("nocrop-feed-player-size")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            java.util.List<?> args = chain.getArgs();
                            int w = (Integer) args.get(1);
                            int h = (Integer) args.get(2);
                            alog(Log.DEBUG, "cb2 feedPlayer onVideoSizeChanged " + w + "x" + h
                                    + " playId=" + args.get(0)
                                    + " this=" + chain.getThisObject().getClass().getName());
                            if (w > 0 && h > 0) {
                                float aspect = (float) w / (float) h;
                                if (aspect < 0.1f || aspect > 20f) {
                                    int t = w;
                                    w = h;
                                    h = t;
                                    aspect = (float) w / (float) h;
                                }
                                if (aspect >= 0.1f && aspect <= 20f) {
                                    View view = resolveVideoView(chain.getThisObject());
                                    if (view != null) {
                                        FitApplier.onPlayerLayoutSize(view, w, h);
                                    } else {
                                        alog(Log.DEBUG, "cb2: no video view from player");
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            alog(Log.DEBUG, "feed-player-size apply skipped: " + t);
                        }
                        return r;
                    });
            alog(Log.INFO, "hooked BaseFeedPlayerView.onVideoSizeChanged");
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook BaseFeedPlayerView.onVideoSizeChanged", t);
        }
    }

    /**
     * Hook PlayerManager.setSurface(Surface)：记录全局播放器当前渲染的 Surface。
     * fit 时用它做身份守卫——只有"该 View 的 Surface == 播放器当前 Surface"
     * 才允许拉取 getVideoWidth/Height 覆盖比例，防止邻居内容吃到别人的真实尺寸。
     */
    private void installSurfaceTrackHook(ClassLoader loader) {
        // 实测 PlayerManager 的 surface setter 从不被调用；真正的链路是
        // wrapper → TTVideoEngine.setSurface/setSurfaceHolder 系列。全部挂上取 Surface。
        Object[][] targets = {
                {"com.ss.ttvideoengine.TTVideoEngine", "setSurface", android.view.Surface.class},
                {"com.ss.ttvideoengine.TTVideoEngine", "setSurfaceSync", android.view.Surface.class},
                {"com.ss.ttvideoengine.TTVideoEngine", "setSurfaceHolder", android.view.SurfaceHolder.class},
                {"com.ss.ttvideoengine.TTVideoEngine", "setSurfaceHolderSync", android.view.SurfaceHolder.class},
                {"com.ss.android.ugc.aweme.video.PlayerManager", "setSurface", android.view.Surface.class},
                {"com.ss.android.ugc.aweme.video.PlayerManager", "LJIIIIZZ", android.view.Surface.class},
                // 实测部分条目的 View Surface 从不经过 TTVideoEngine；这两条是 IPlayerManager /
                // ooplayer 侧的 setter（X.0DmO 为混淆名，仅对抖音 40.6.0 有效）。
                {"X.0DmO", "setSurface", android.view.Surface.class},
                {"X.0DmO", "setSurfaceHolder", android.view.SurfaceHolder.class},
                {"com.ss.android.ugc.aweme.o.oplayer.client.player.ability.DPlayAbility",
                        "setSurface", android.view.Surface.class},
                {"com.ss.android.ugc.aweme.o.oplayer.client.player.ability.DPlayAbility",
                        "setSurfaceHolder", android.view.SurfaceHolder.class},
        };
        int hooked = 0;
        for (Object[] t : targets) {
            try {
                Class<?> cls = Class.forName((String) t[0], false, loader);
                Method m = cls.getDeclaredMethod((String) t[1], (Class<?>) t[2]);
                final String label = ((String) t[0]).substring(
                        ((String) t[0]).lastIndexOf('.') + 1) + "#" + t[1];
                hook(m)
                        .setId("nocrop-surface-" + t[1] + "-" + hooked)
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                Object arg = chain.getArgs().get(0);
                                if (arg instanceof android.view.Surface
                                        || arg instanceof android.view.SurfaceHolder) {
                                    // holder 与 view.getHolder() 同实例（最稳的键）；
                                    // notePlayerSurface 内部会把 holder 的 Surface 也记一份
                                    FitApplier.notePlayerSurface(chain.getThisObject(), arg);
                                    alog(Log.DEBUG, "surface " + label + " -> " + arg);
                                }
                            } catch (Throwable ignored) {
                                // 记录失败不影响播放
                            }
                            return chain.proceed();
                        });
                hooked++;
            } catch (Throwable ignored) {
                // 该类/方法不存在，试下一个
            }
        }
        if (hooked > 0) {
            alog(Log.INFO, "hooked surface setters: " + hooked);
        } else {
            alog(Log.ERROR, "no surface setter hookable");
        }
        // texturerender VideoSurface.setSurfaceDimensions(w,h)：渲染宽高落点，建立 engine 关联
        try {
            Class<?> vsCls = Class.forName("com.ss.texturerender.VideoSurface", false, loader);
            Method m = vsCls.getDeclaredMethod("setSurfaceDimensions", int.class, int.class);
            hook(m)
                    .setId("nocrop-vsurface-dims")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            int w = (Integer) chain.getArgs().get(0);
                            int h = (Integer) chain.getArgs().get(1);
                            FitApplier.noteVideoSurface(chain.getThisObject());
                            alog(Log.DEBUG, "VideoSurface dims " + w + "x" + h
                                    + " -> " + chain.getThisObject());
                        } catch (Throwable ignored) {
                            // 诊断钩子失败不影响播放
                        }
                        return r;
                    });
            alog(Log.INFO, "hooked VideoSurface.setSurfaceDimensions");
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook VideoSurface.setSurfaceDimensions", t);
        }
        // VideoSurface.setExtraRenderSurface(Surface[,int])：把渲染接到目标 Surface 的交汇点——
        // arg 很可能就是 View 的 Surface，可直接把"目标 Surface → 带宽高的 VideoSurface"对上
        try {
            Class<?> vsCls = Class.forName("com.ss.texturerender.VideoSurface", false, loader);
            Class<?> sCls = Class.forName("android.view.Surface", false, loader);
            String[][] sigs = {
                    {"setExtraRenderSurface", "SI"},
                    {"setExtraRenderSurface", "S"},
                    {"onTextureUpdate", "SIL"},
                    {"resetSurface", "S"}, // 非空 Surface 交接的强候选
            };
            int hooked2 = 0;
            for (String[] sig : sigs) {
                try {
                    Method m;
                    if (sig[1].equals("SI")) {
                        m = vsCls.getDeclaredMethod(sig[0], sCls, int.class);
                    } else if (sig[1].equals("S")) {
                        m = vsCls.getDeclaredMethod(sig[0], sCls);
                    } else {
                        m = vsCls.getDeclaredMethod(sig[0], int.class, sCls, long.class);
                    }
                    final String label = sig[0] + sig[1];
                    hook(m)
                            .setId("nocrop-extra-surface-" + label)
                            .setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object r = chain.proceed();
                                try {
                                    java.util.List<?> args = chain.getArgs();
                                    Object argS = null;
                                    for (Object a : args) {
                                        if (a instanceof android.view.Surface) {
                                            argS = a;
                                            break;
                                        }
                                    }
                                    Object vs = chain.getThisObject();
                                    FitApplier.noteExtraSurface(argS, vs);
                                    Field wF = vs.getClass().getField("mSurfaceWidth");
                                    Field hF = vs.getClass().getField("mSurfaceHeight");
                                    alog(Log.DEBUG, "extraSurface " + label
                                            + " target=" + argS
                                            + " dims=" + wF.getInt(vs) + "x" + hF.getInt(vs));
                                } catch (Throwable ignored) {
                                    // 诊断失败不影响渲染
                                }
                                return r;
                            });
                    hooked2++;
                } catch (Throwable ignored) {
                    // 该签名不存在
                }
            }
            alog(Log.INFO, "hooked VideoSurface extra-surface: " + hooked2);
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook VideoSurface extra-surface", t);
        }
        installScalingProbeHook(loader);
        installDraweeSetHook(loader);
    }

    /**
     * Hook SimpleDraweeView.setImageURI 各重载：静态图文封面的运行时入口。
     * FeedImageViewHolder.bind 在沉浸式图文上从不触发（实测 0 次），
     * 改从"图片加载"这个必经点反查，回调里走 FitApplier.onDraweeSet 标准门控。
     */
    private void installDraweeSetHook(ClassLoader loader) {
        Class<?> cls;
        try {
            cls = Class.forName("com.facebook.drawee.view.SimpleDraweeView", false, loader);
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to resolve SimpleDraweeView", t);
            return;
        }
        // 拦截重设点 1：ImageView.setScaleType —— 抖音绑定/翻页把已跟踪图文改回 cover 时就地拦成 FIT
        try {
            Class<?> ivCls = Class.forName("android.widget.ImageView");
            Method m = ivCls.getDeclaredMethod(
                    "setScaleType", android.widget.ImageView.ScaleType.class);
            hook(m)
                    .setId("nocrop-enforce-iv-scale")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            if (chain.getThisObject() instanceof View) {
                                Object replacement = FitApplier.enforceIvScale(
                                        (View) chain.getThisObject(), chain.getArgs().get(0));
                                if (replacement != null) {
                                    java.util.List<Object> args =
                                            new java.util.ArrayList<>(chain.getArgs());
                                    args.set(0, replacement);
                                    return chain.proceed(args.toArray());
                                }
                            }
                        } catch (Throwable t) {
                            alog(Log.DEBUG, "enforce iv-scale skipped: " + t);
                        }
                        return chain.proceed();
                    });
            alog(Log.INFO, "hooked ImageView.setScaleType (enforce-fit)");
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook ImageView.setScaleType", t);
        }
        // 拦截重设点 2：GenericDraweeHierarchy.setActualImageScaleType
        try {
            Class<?> gdh = Class.forName(
                    "com.facebook.drawee.generic.GenericDraweeHierarchy", false, loader);
            Class<?> stCls = Class.forName(
                    "com.facebook.drawee.drawable.ScalingUtils$ScaleType", false, loader);
            Method m = gdh.getDeclaredMethod("setActualImageScaleType", stCls);
            hook(m)
                    .setId("nocrop-enforce-hierarchy-scale")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            Object replacement = FitApplier.enforceHierarchyScale(
                                    chain.getThisObject(), chain.getArgs().get(0));
                            if (replacement != null) {
                                java.util.List<Object> args =
                                        new java.util.ArrayList<>(chain.getArgs());
                                args.set(0, replacement);
                                return chain.proceed(args.toArray());
                            }
                        } catch (Throwable t) {
                            alog(Log.DEBUG, "enforce hierarchy-scale skipped: " + t);
                        }
                        return chain.proceed();
                    });
            alog(Log.INFO, "hooked GenericDraweeHierarchy.setActualImageScaleType (enforce-fit)");
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook hierarchy setActualImageScaleType", t);
        }
        // 拦截重设点 3：AssembleScaleView 重写的 setScaleType——把 scaleType 存进
        // PhotoViewAttacher 自己的字段并由它算矩阵，不走 super，必须拦重写入口。
        try {
            Class<?> asmCls = Class.forName(
                    "com.bytedance.ies.ugc.aweme.photos.ui.scale.assemble.AssembleScaleView",
                    false, loader);
            Method m = asmCls.getDeclaredMethod(
                    "setScaleType", android.widget.ImageView.ScaleType.class);
            hook(m)
                    .setId("nocrop-enforce-assemble-scale")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            if (chain.getThisObject() instanceof View) {
                                Object replacement = FitApplier.enforceIvScale(
                                        (View) chain.getThisObject(), chain.getArgs().get(0));
                                if (replacement != null) {
                                    java.util.List<Object> args =
                                            new java.util.ArrayList<>(chain.getArgs());
                                    args.set(0, replacement);
                                    return chain.proceed(args.toArray());
                                }
                            }
                        } catch (Throwable t) {
                            alog(Log.DEBUG, "enforce assemble-scale skipped: " + t);
                        }
                        return chain.proceed();
                    });
            alog(Log.INFO, "hooked AssembleScaleView.setScaleType (enforce-fit)");
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook AssembleScaleView.setScaleType", t);
        }
        Class<?>[][] sigs = {
                {android.net.Uri.class},
                {String.class},
                {Object.class},
        };
        int hooked = 0;
        for (Class<?>[] sig : sigs) {
            try {
                Method m = cls.getDeclaredMethod("setImageURI", sig);
                hook(m)
                        .setId("nocrop-drawee-set-" + sig[0].getSimpleName())
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            try {
                                if (chain.getThisObject() instanceof View) {
                                    FitApplier.onDraweeSet((View) chain.getThisObject());
                                }
                            } catch (Throwable t) {
                                alog(Log.DEBUG, "drawee set skipped: " + t);
                            }
                            return r;
                        });
                hooked++;
            } catch (Throwable ignored) {
                // 该重载不存在
            }
        }
        if (hooked > 0) {
            alog(Log.INFO, "hooked drawee setImageURI overloads: " + hooked);
        } else {
            alog(Log.ERROR, "no drawee setImageURI hookable");
        }
        // setImageURI 实测 0 触发（抖音手动构建 Controller），补挂 Fresco 总闸 setController
        try {
            Class<?> dv = Class.forName("com.facebook.drawee.view.DraweeView", false, loader);
            Class<?> dc = Class.forName("com.facebook.drawee.interfaces.DraweeController", false, loader);
            Method m = dv.getDeclaredMethod("setController", dc);
            hook(m)
                    .setId("nocrop-drawee-setController")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            if (chain.getThisObject() instanceof View) {
                                FitApplier.onDraweeSet((View) chain.getThisObject());
                            }
                        } catch (Throwable t) {
                            alog(Log.DEBUG, "drawee setController skipped: " + t);
                        }
                        return r;
                    });
            alog(Log.INFO, "hooked DraweeView.setController");
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook DraweeView.setController", t);
        }
    }

    /**
     * 诊断钩子：X.0DvF（Feed 渲染 SurfaceView）的 scaleX/scaleY 与带原因的 setLayoutParams。
     * 抖音的 "VideoScaling" 可能用缩放变换做 cover——那样只改 LayoutParams 会被绕过。
     * 只打日志，不改行为。
     */
    private void installScalingProbeHook(ClassLoader loader) {
        try {
            Class<?> cls = Class.forName("X.0DvF", false, loader);
            int n = 0;
            try {
                Method m = cls.getDeclaredMethod("setScaleX", float.class);
                hook(m).setId("nocrop-probe-scaleX")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            alog(Log.DEBUG, "probe setScaleX=" + chain.getArgs().get(0));
                            return chain.proceed();
                        });
                n++;
            } catch (Throwable ignored) {
                // 没有该方法
            }
            try {
                Method m = cls.getDeclaredMethod("setScaleY", float.class);
                hook(m).setId("nocrop-probe-scaleY")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            alog(Log.DEBUG, "probe setScaleY=" + chain.getArgs().get(0));
                            return chain.proceed();
                        });
                n++;
            } catch (Throwable ignored) {
                // 没有该方法
            }
            try {
                Method m = cls.getDeclaredMethod("LJI",
                        android.view.ViewGroup.LayoutParams.class, String.class);
                hook(m).setId("nocrop-probe-setLp")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            android.view.ViewGroup.LayoutParams lp =
                                    (android.view.ViewGroup.LayoutParams) chain.getArgs().get(0);
                            alog(Log.DEBUG, "probe setLp " + lp.width + "x" + lp.height
                                    + " reason=" + chain.getArgs().get(1)
                                    + " view=" + chain.getThisObject());
                            return chain.proceed();
                        });
                n++;
            } catch (Throwable ignored) {
                // 没有该方法
            }
            alog(Log.INFO, "hooked scaling probes: " + n);
            // 裁剪机制直接证据：玩家对 SurfaceView 的 setWindowCrop 矩形
            try {
                Method m = cls.getDeclaredMethod("setWindowCropNew", android.graphics.Rect.class);
                hook(m).setId("nocrop-probe-windowCrop")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            android.graphics.Rect r =
                                    (android.graphics.Rect) chain.getArgs().get(0);
                            alog(Log.DEBUG, "probe windowCrop " + r.toShortString()
                                    + " view=" + chain.getThisObject());
                            return chain.proceed();
                        });
                alog(Log.INFO, "hooked X.0DvF.setWindowCropNew");
            } catch (Throwable t) {
                alog(Log.ERROR, "failed to hook setWindowCropNew", t);
            }
        } catch (Throwable t) {
            alog(Log.ERROR, "failed to hook scaling probes", t);
        }
    }

    /**
     * Hook Activity.onResume：从搜索/分享/其他页面返回 Feed 时全量重算。
     * 实测：返回后 getAweme 不再触发、layout 无变化 → fit 永不重跑，
     * 动图沿用旧的猜测比例且 pull 无法重评（表现为"返回后还裁剪"）。
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
