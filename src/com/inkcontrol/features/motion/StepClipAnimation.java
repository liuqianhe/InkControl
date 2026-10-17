package com.inkcontrol.features.motion;

import android.graphics.Rect;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.Transformation;

import java.lang.reflect.Method;

/**
 * 阶梯裁剪：整段动画分成 steps-1 个等长的关键帧，每个关键帧内裁剪矩形不变，
 * 所以合成器只在换帧时产生新画面，墨水屏每个关键帧刷一次。
 * 只在 system_server 里使用（WindowAnimationSpec 会把 Transformation 的裁剪矩形设成窗口裁剪）。
 */
final class StepClipAnimation extends Animation {
    private static Method sSetClipRect;

    private final int[][] mRects;
    private final Rect mTmp = new Rect();

    StepClipAnimation(int[][] rects, long durationMs) {
        mRects = rects;
        setDuration(durationMs);
        setInterpolator(new LinearInterpolator());
        setZAdjustment(ZORDER_TOP);
        setFillAfter(true);
    }

    @Override
    protected void applyTransformation(float t, Transformation tr) {
        int n = mRects.length;
        int i = Math.min(n - 1, Math.max(0, (int) Math.floor(t * (n - 1) + 1e-4f)));
        int[] r = mRects[i];
        mTmp.set(r[0], r[1], r[2], r[3]);
        try {
            // Transformation.setClipRect 是隐藏接口，system_server 里可以直接反射调用
            if (sSetClipRect == null) sSetClipRect = Transformation.class.getMethod("setClipRect", Rect.class);
            sSetClipRect.invoke(tr, mTmp);
        } catch (Throwable ignored) {
            // 设不了裁剪就等于瞬切
        }
    }

    /** 窗口动画会乘以海信强制为 0 的倍率，这里忽略，时长由我们自己定 */
    @Override
    public void scaleCurrentDuration(float scale) {
    }

    @Override
    public boolean willChangeTransformationMatrix() {
        return false;
    }

    @Override
    public boolean willChangeBounds() {
        return true;
    }
}
