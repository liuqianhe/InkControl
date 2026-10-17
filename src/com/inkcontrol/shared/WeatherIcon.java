package com.inkcontrol.shared;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

/** 根据现有天气文字画黑色矢量图标，不把未知天气默认当晴。 */
public final class WeatherIcon {
    public static boolean available(String weather) {
        return weather != null && !weather.trim().isEmpty() && !"—".equals(weather.trim());
    }

    public static void draw(Canvas canvas, String weather, float x, float y, float size, int color) {
        if (!available(weather)) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        canvas.save();
        try {
            canvas.translate(x, y);
            canvas.scale(size / 64f, size / 64f);
            if (weather.contains("雷")) {
                cloud(canvas, p);
                Path bolt = new Path();
                bolt.moveTo(34, 40); bolt.lineTo(25, 51); bolt.lineTo(32, 51);
                bolt.lineTo(28, 60); bolt.lineTo(42, 46); bolt.lineTo(35, 46); bolt.close();
                p.setStyle(Paint.Style.FILL);
                canvas.drawPath(bolt, p);
            } else if (weather.contains("雪")) {
                cloud(canvas, p);
                snow(canvas, p, 20, 53);
                if (weather.contains("雨")) canvas.drawLine(43, 47, 39, 55, p);
                else snow(canvas, p, 43, 53);
            } else if (weather.contains("雨")) {
                cloud(canvas, p);
                for (int i = 0; i < 3; i++) canvas.drawLine(20+i*13, 47, 16+i*13, 55, p);
            } else if (weather.contains("雾") || weather.contains("霾") || weather.contains("沙") || weather.contains("尘")) {
                canvas.drawLine(12, 22, 50, 22, p);
                canvas.drawLine(6, 32, 57, 32, p);
                canvas.drawLine(13, 42, 48, 42, p);
                canvas.drawLine(20, 52, 56, 52, p);
            } else if (weather.contains("风")) {
                Path wind = new Path();
                wind.moveTo(6, 25); wind.lineTo(45, 25); wind.cubicTo(60, 25, 58, 8, 47, 13);
                wind.moveTo(6, 36); wind.lineTo(52, 36);
                wind.moveTo(11, 46); wind.lineTo(38, 46); wind.cubicTo(53, 46, 51, 62, 40, 56);
                canvas.drawPath(wind, p);
            } else if (weather.contains("云")) {
                sun(canvas, p, 43, 18, 7, 12, 16);
                cloud(canvas, p);
            } else if (weather.contains("阴")) {
                cloud(canvas, p);
            } else if (weather.contains("晴")) {
                sun(canvas, p, 32, 32, 11, 18, 26);
            } else {
                canvas.drawCircle(32, 32, 21, p);
                canvas.drawLine(23, 32, 41, 32, p);
            }
        } finally {
            canvas.restore();
        }
    }

    private static void sun(Canvas c, Paint p, float cx, float cy, float radius, float inner, float outer) {
        c.drawCircle(cx, cy, radius, p);
        for (int i = 0; i < 8; i++) {
            double angle = i*Math.PI/4;
            float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
            c.drawLine(cx+inner*dx, cy+inner*dy, cx+outer*dx, cy+outer*dy, p);
        }
    }

    private static void cloud(Canvas c, Paint p) {
        Path shape = new Path();
        shape.moveTo(15, 43);
        shape.cubicTo(3, 43, 3, 28, 16, 28);
        shape.cubicTo(18, 12, 38, 11, 43, 27);
        shape.cubicTo(60, 24, 63, 43, 49, 43);
        shape.close();
        int color = p.getColor();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawPath(shape, p);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        c.drawPath(shape, p);
    }

    private static void snow(Canvas c, Paint p, float cx, float cy) {
        for (int i = 0; i < 3; i++) {
            double angle = i*Math.PI/3;
            float dx = 4*(float) Math.cos(angle), dy = 4*(float) Math.sin(angle);
            c.drawLine(cx-dx, cy-dy, cx+dx, cy+dy, p);
        }
    }
}
