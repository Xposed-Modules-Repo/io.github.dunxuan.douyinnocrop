package io.github.dunxuan.douyinnocrop;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 视频宽高比（width / height）缓存与提取。
 *
 * 提取顺序（抖音 40.6.0 实测）：
 *   1. Video.width / Video.height      —— 语义字段名，部分版本保留
 *   2. Video.t / Video.s               —— 本版本的实际字段名（t=width, s=height，
 *                                         见 Video.toString() 的 "height= s, width= t"）
 *   3. Video.aspectRatio               —— 语义 float 字段（Video.LJIL 中按 t/s 计算，即 w/h）
 *
 * 全部失败时沿用上一次成功读取的值（lastGood），保证滚动切换时不闪烁回全屏裁剪态。
 */
final class VideoAspect {

    private static volatile float last = 0f;

    private VideoAspect() {
    }

    /** 最近一次成功读取的宽高比；无则 0。 */
    static float lastGood() {
        return last;
    }

    /** 从 Aweme 提取当前视频宽高比；失败返回 lastGood()。 */
    static float read(Object aweme) {
        if (aweme == null) {
            return last;
        }
        try {
            Method getVideo = aweme.getClass().getMethod("getVideo");
            Object video = getVideo.invoke(aweme);
            if (video == null) {
                return last;
            }
            float aspect = fromSizeFields(video);
            if (!isSane(aspect)) {
                aspect = fromFloatField(video, "aspectRatio");
            }
            if (isSane(aspect)) {
                last = aspect;
            }
        } catch (Throwable ignored) {
            // 反射失败保持上次值
        }
        return last;
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

    /** 合法的宽高比范围（横竖屏视频都覆盖，排除脏数据）。 */
    private static boolean isSane(float aspect) {
        return aspect > 0.1f && aspect < 20f;
    }
}
