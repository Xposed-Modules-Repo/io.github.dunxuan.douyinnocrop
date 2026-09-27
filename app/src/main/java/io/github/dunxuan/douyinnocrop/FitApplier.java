package io.github.dunxuan.douyinnocrop;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/**
 * 把渲染 View 的 LayoutParams 收缩为"视频宽高比的最大内接矩形"。
 *
 * 约束与行为：
 *   - 只改 lp.width / lp.height，保留原有 gravity / RelativeLayout 规则
 *     （抖音创建 View 时即带居中属性：FrameLayout.LayoutParams(-1,-1,17=CENTER)）；
 *   - 幂等：目标尺寸相同则直接返回，不触发 layout，杜绝监听循环；
 *   - 父容器未完成测量时，挂一次 OnLayoutChangeListener 等首帧布局后再应用；
 *   - 主线程保证：跨线程调用自动 post 到 View 队列。
 */
final class FitApplier {

    private static final String TAG = "DouYinNoCrop";

    private FitApplier() {
    }

    static void apply(final View video, final float aspect) {
        if (video == null || aspect <= 0.05f) {
            return;
        }
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            video.post(new Runnable() {
                @Override
                public void run() {
                    apply(video, aspect);
                }
            });
            return;
        }

        ViewParent parent = video.getParent();
        if (!(parent instanceof View)) {
            return;
        }
        View container = (View) parent;
        int cw = container.getWidth();
        int ch = container.getHeight();
        if (cw <= 0 || ch <= 0) {
            waitForLayout(container, video, aspect);
            return;
        }

        int targetW;
        int targetH;
        if (aspect >= (float) cw / (float) ch) {
            // 视频相对更"宽"：宽度顶满，上下留空（原本左右会被裁）
            targetW = cw;
            targetH = Math.max(1, Math.round(cw / aspect));
        } else {
            // 视频相对更高：高度顶满，左右留空
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
        lp.width = targetW;
        lp.height = targetH;
        video.setLayoutParams(lp);
        Log.d(TAG, "fit " + cw + "x" + ch + " -> "
                + targetW + "x" + targetH + " (aspect=" + aspect + ")");
    }

    /** 容器还没测量：等它完成第一次布局再应用（只挂一次）。 */
    private static void waitForLayout(final View container, final View video, final float aspect) {
        final View.OnLayoutChangeListener[] holder = new View.OnLayoutChangeListener[1];
        holder[0] = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (v.getWidth() <= 0 || v.getHeight() <= 0) {
                    return;
                }
                v.removeOnLayoutChangeListener(this);
                apply(video, aspect);
            }
        };
        container.addOnLayoutChangeListener(holder[0]);
    }
}
