package io.github.dunxuan.douyinnocrop;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 视频/图文宽高比提取。无全局状态：每次从 Aweme 对象独立读取，
 * 读不到返回 0，由调用方决定回退策略——彻底杜绝跨内容的陈旧比例污染。
 *
 * 提取顺序（抖音 40.6.0 实测）：
 *   1. Video.width / Video.height      —— 语义字段名，部分版本保留
 *   2. Video.t / Video.s               —— 本版本的实际字段名（t=width, s=height，
 *                                         见 Video.toString() 的 "height= s, width= t"）
 *   3. Video.aspectRatio               —— 语义 float 字段（Video.LJIL 中按 t/s 计算，即 w/h）
 *   4. Aweme.imageInfos[0].width/height —— 图文/动图帖子：视频字段为空时
 *                                         从第一张图的 ImageInfo 读尺寸
 */
final class VideoAspect {

    private VideoAspect() {
    }

    /** 从 Aweme 提取宽高比；失败返回 0（调用方不得拿别的内容的比例来填）。 */
    static float read(Object aweme) {
        if (aweme == null) {
            return 0f;
        }
        try {
            Method getVideo = aweme.getClass().getMethod("getVideo");
            Object video = getVideo.invoke(aweme);
            if (video != null) {
                float aspect = fromSizeFields(video);
                if (!isSane(aspect)) {
                    aspect = fromFloatField(video, "aspectRatio");
                }
                if (isSane(aspect)) {
                    return aspect;
                }
            }
            float fromImage = fromImageInfos(aweme);
            if (isSane(fromImage)) {
                return fromImage;
            }
        } catch (Throwable ignored) {
            // 反射失败按读不到处理
        }
        return 0f;
    }

    /** 该 Aweme 是否为图文/动图帖子（imageInfos 或 images 非空）。 */
    static boolean isImagePost(Object aweme) {
        if (aweme == null) {
            return false;
        }
        try {
            Object infos = aweme.getClass().getField("imageInfos").get(aweme);
            if (infos instanceof List && !((List<?>) infos).isEmpty()) {
                return true;
            }
        } catch (Throwable ignored) {
            // 字段不可达，继续试 images
        }
        try {
            Object imgs = aweme.getClass().getField("images").get(aweme);
            return imgs instanceof List && !((List<?>) imgs).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 该 Aweme 的帖子 id（用于识别 View 复用到了新内容，防止真实尺寸残留）。 */
    static String aid(Object aweme) {
        if (aweme == null) {
            return null;
        }
        try {
            Object v = aweme.getClass().getMethod("getAid").invoke(aweme);
            return (v instanceof String) ? (String) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float fromSizeFields(Object video) {
        String[][] candidates = {{"width", "height"}, {"t", "s"}};
        for (String[] pair : candidates) {
            try {
                Field wf = video.getClass().getField(pair[0]);
                Field hf = video.getClass().getField(pair[1]);
                float w = asNumber(wf.get(video));
                float h = asNumber(hf.get(video));
                if (w > 0f && h > 0f) {
                    return w / h;
                }
            } catch (Throwable ignored) {
                // 字段不存在或不可访问，试下一组
            }
        }
        return 0f;
    }

    /** 图文/动图回退：Aweme.imageInfos 第一项的 width/height（ImageInfo 为语义字段名）。 */
    private static float fromImageInfos(Object aweme) {
        // 1) imageInfos（ImageInfo.width/height）
        float r = listDims(aweme, "imageInfos");
        if (isSane(r)) {
            return r;
        }
        // 2) images（有的帖只填 images 不填 imageInfos——aspect=0 的来源）
        return listDims(aweme, "images");
    }

    /** 读 aweme.<listField> 第一项的 width/height；读不到返回 0。 */
    private static float listDims(Object aweme, String listField) {
        try {
            Object infos = aweme.getClass().getField(listField).get(aweme);
            if (!(infos instanceof List)) {
                return 0f;
            }
            List<?> list = (List<?>) infos;
            if (list.isEmpty() || list.get(0) == null) {
                return 0f;
            }
            Object info = list.get(0);
            float w = asNumber(info.getClass().getField("width").get(info));
            float h = asNumber(info.getClass().getField("height").get(info));
            if (w > 0f && h > 0f) {
                return w / h;
            }
        } catch (Throwable ignored) {
            // 非图文帖子或字段不可达
        }
        return 0f;
    }

    private static float fromFloatField(Object video, String name) {
        try {
            return asNumber(video.getClass().getField(name).get(video));
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private static float asNumber(Object value) {
        return (value instanceof Number) ? ((Number) value).floatValue() : 0f;
    }

    /** 合法的宽高比范围（横竖屏内容都覆盖，排除脏数据）。 */
    private static boolean isSane(float aspect) {
        return aspect > 0.1f && aspect < 20f;
    }
}
