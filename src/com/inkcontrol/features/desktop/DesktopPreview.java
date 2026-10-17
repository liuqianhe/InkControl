package com.inkcontrol.features.desktop;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;

public final class DesktopPreview {
    public static View create(Context context, String theme, String kind) {
        try { Assets.init(context); } catch (Exception error) { throw new IllegalStateException("预览素材不可用", error); }
        return new View(context) {
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Bundle sample = new Bundle();
                sample.putString("weather", "多云");
                sample.putString("temperature", "22°");
                sample.putString("title", "音乐组件预览");
                WidgetRenderer.draw(canvas, getWidth(), getHeight(), context, theme, kind, sample);
            }
        };
    }
    private DesktopPreview() {}
}
