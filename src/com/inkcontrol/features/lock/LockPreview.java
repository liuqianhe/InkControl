package com.inkcontrol.features.lock;

import android.content.Context;
import android.view.View;

public final class LockPreview {
    public static View create(Context context, int style) {
        LockCanvas canvas = new LockCanvas(context);
        canvas.set(style, LockData.sample());
        return canvas;
    }
    private LockPreview() {}
}
