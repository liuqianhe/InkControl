package com.inkcontrol.features.motion;

import android.view.animation.AlphaAnimation;

/** 与裁剪侧保持相同时长，避免原厂零倍率提前释放底层窗口。 */
final class HoldAnimation extends AlphaAnimation {
    HoldAnimation(long duration) { super(1f, 1f); setDuration(duration); }
    @Override public void scaleCurrentDuration(float scale) { }
}
