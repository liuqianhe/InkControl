package com.inkcontrol.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import com.inkcontrol.core.FeatureDefinition;
import com.inkcontrol.features.motion.Geometry;
import java.util.List;

/** 功能页里的示意图：温度曲线编辑器、开启前后对比条、刷新方式对比。全部纯黑白。 */
final class Charts {
    private Charts() {}

    static final String[] BAND_LABELS = {"<3", "3–6", "6–9", "9–12", "12–15", "15–18", "18–21", "21–24", "≥24"};
    static int[] autoCurve() {
        int[] v = new int[FeatureDefinition.TEMPERATURE_BANDS];
        for (int i = 0; i < v.length; i++) v[i] = Geometry.autoHoldForBand(i);
        return v;
    }

    /** 9 个温区的停留时长柱状图；可编辑时上下拖动柱子调整，10 毫秒一档。虚线是该温区刷完所需的自动值。 */
    static final class HoldCurve extends View {
        interface Listener { void changed(int[] values); }
        private final Ink ink;
        private final int[] values, auto = autoCurve();
        private final int band;
        private final boolean editable;
        private final Listener listener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int scale;
        HoldCurve(Context context, Ink ink, int[] values, int band, boolean editable, Listener listener) {
            super(context);
            this.ink = ink; this.values = values.clone(); this.band = band; this.editable = editable; this.listener = listener;
            rescale();
            this.setContentDescription(ink.t(editable ? "温度停留曲线，上下拖动柱子调整" : "温度停留曲线"));
        }
        int[] values() { return values.clone(); }
        private void rescale() {
            int max = 0;
            for (int i = 0; i < values.length; i++) max = Math.max(max, Math.max(values[i], auto[i]));
            scale = Math.max(500, (int) Math.ceil(max * 1.2 / 100.0) * 100);
        }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), ink.dp(250)); }
        private float top() { return ink.px(26); }
        private float bottom() { return getHeight() - ink.px(46); }
        private float column() { return getWidth() / (float) values.length; }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!editable) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            int i = Math.max(0, Math.min(values.length - 1, (int) (e.getX() / column())));
            float ratio = (bottom() - e.getY()) / (bottom() - top());
            int v = Math.round(ratio * scale / 10f) * 10;
            values[i] = Math.max(30, Math.min(FeatureDefinition.CURVE_MAX_MS, v));
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
                rescale();
                // 页面会整体重建，不能在触摸分发过程中同步替换视图树。
                int[] result = values.clone();
                if (listener != null) post(() -> listener.changed(result));
            }
            invalidate();
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            float col = column(), top = top(), bottom = bottom(), span = bottom - top;
            paint.setColor(Color.BLACK);
            paint.setStrokeWidth(ink.px(1.5f));
            c.drawLine(0, bottom, getWidth(), bottom, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < values.length; i++) {
                float cx = col * i + col / 2, half = Math.min(col * 0.32f, ink.px(13));
                float y = bottom - span * Math.min(1f, values[i] / (float) scale);
                boolean low = values[i] > 0 && values[i] < auto[i];
                RectF bar = new RectF(cx - half, y, cx + half, bottom);
                paint.setStyle(low ? Paint.Style.STROKE : Paint.Style.FILL);
                paint.setStrokeWidth(ink.px(2));
                c.drawRect(bar, paint);
                if (low) for (float hy = bottom - ink.px(6); hy > y; hy -= ink.px(6)) c.drawLine(bar.left, hy, bar.right, Math.max(y, hy - ink.px(6)), paint);
                // 自动值标记
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ink.px(2));
                paint.setPathEffect(new DashPathEffect(new float[]{ink.px(3), ink.px(2)}, 0));
                float ay = bottom - span * Math.min(1f, auto[i] / (float) scale);
                c.drawLine(cx - half - ink.px(4), ay, cx + half + ink.px(4), ay, paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setTypeface(Ink.MEDIUM); paint.setTextSize(ink.px(11));
                c.drawText(ink.t((low ? "!" : "") + values[i]), cx, y - ink.px(6), paint);
                // 温区标签，当前温区反色
                paint.setTextSize(ink.px(11)); paint.setTypeface(i == band ? Ink.BOLD : Ink.REGULAR);
                float ly = bottom + ink.px(17);
                if (i == band) {
                    RectF pill = new RectF(cx - col / 2 + ink.px(1), ly - ink.px(13), cx + col / 2 - ink.px(1), ly + ink.px(5));
                    c.drawRoundRect(pill, ink.px(6), ink.px(6), paint);
                    paint.setColor(Color.WHITE); c.drawText(ink.t(BAND_LABELS[i]), cx, ly, paint); paint.setColor(Color.BLACK);
                    paint.setTextSize(ink.px(10)); c.drawText(ink.t("当前"), cx, ly + ink.px(17), paint);
                } else c.drawText(ink.t(BAND_LABELS[i]), cx, ly, paint);
            }
            paint.setTextAlign(Paint.Align.LEFT); paint.setTypeface(Ink.REGULAR); paint.setTextSize(ink.px(11));
            c.drawText(ink.t("毫秒"), 0, ink.px(11), paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(ink.t("°C"), getWidth(), getHeight() - ink.px(2), paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** 开启前后对比条。 */
    static final class Bars extends View {
        private final Ink ink;
        private final List<Guide.Bar> bars;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bars(Context context, Ink ink, List<Guide.Bar> bars) {
            super(context); this.ink = ink; this.bars = bars;
            StringBuilder d = new StringBuilder();
            for (Guide.Bar b : bars) d.append(b.label).append(' ').append(b.text).append('，');
            this.setContentDescription(ink.t(d.toString()));
        }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), ink.dp(30) * bars.size() + ink.dp(6)); }
        @Override protected void onDraw(Canvas c) {
            float max = 0;
            for (Guide.Bar b : bars) max = Math.max(max, b.value);
            float labelW = ink.px(64), valueW = ink.px(64), track = getWidth() - labelW - valueW;
            for (int i = 0; i < bars.size(); i++) {
                Guide.Bar b = bars.get(i);
                float y = i * ink.px(30) + ink.px(4), h = ink.px(20);
                paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(ink.px(14)); paint.setTypeface(i == bars.size() - 1 ? Ink.BOLD : Ink.REGULAR);
                c.drawText(ink.t(b.label), 0, y + h * 0.78f, paint);
                float w = max <= 0 ? 0 : track * b.value / max;
                if (w > 0) c.drawRoundRect(new RectF(labelW, y, labelW + w, y + h), ink.px(4), ink.px(4), paint);
                else { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ink.px(2)); c.drawLine(labelW, y, labelW, y + h, paint); paint.setStyle(Paint.Style.FILL); }
                c.drawText(ink.t(b.text), labelW + w + ink.px(8), y + h * 0.78f, paint);
            }
        }
    }

    /** 三种刷新方式的时长与是否闪黑；highlight 中的方式加粗并标注。 */
    static final class RefreshModes extends View {
        static final String[][] MODES = {{"a2", "极速模式", "0.12 秒", "黑白"}, {"gld", "阅读模式", "0.45 秒", "16 级灰"}, {"gc", "清晰全刷", "0.9 秒", "先闪黑"}};
        private static final float[] SECONDS = {0.12f, 0.45f, 0.9f};
        private final Ink ink;
        private final java.util.Map<String, String> marks;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RefreshModes(Context context, Ink ink, java.util.Map<String, String> marks) {
            super(context); this.ink = ink; this.marks = marks;
            this.setContentDescription(ink.t("刷新方式对比：极速约 0.12 秒仅黑白，阅读约 0.45 秒 16 级灰，清晰全刷约 0.9 秒先闪黑"));
        }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), ink.dp(46) * MODES.length); }
        @Override protected void onDraw(Canvas c) {
            float labelW = ink.px(UiLanguage.english(getContext()) ? 122 : 78), track = getWidth() - labelW - ink.px(96);
            for (int i = 0; i < MODES.length; i++) {
                String mark = marks.get(MODES[i][0]);
                boolean on = mark != null;
                float y = i * ink.px(46) + ink.px(6), h = ink.px(18);
                paint.setColor(Color.BLACK); paint.setStyle(Paint.Style.FILL);
                paint.setTypeface(on ? Ink.BOLD : Ink.REGULAR); paint.setTextSize(ink.px(UiLanguage.english(getContext()) ? 11 : 14));
                c.drawText(ink.t(MODES[i][1]), 0, y + h * 0.8f, paint);
                float w = track * SECONDS[i] / SECONDS[2];
                RectF bar = new RectF(labelW, y, labelW + w, y + h);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ink.px(on ? 2.5f : 1.5f));
                c.drawRoundRect(bar, ink.px(4), ink.px(4), paint);
                paint.setStyle(Paint.Style.FILL);
                if (i == 2) c.drawRoundRect(new RectF(bar.left, bar.top, bar.left + w * 0.3f, bar.bottom), ink.px(4), ink.px(4), paint);
                else if (on) c.drawRoundRect(bar, ink.px(4), ink.px(4), paint);
                paint.setTypeface(on ? Ink.BOLD : Ink.REGULAR); paint.setTextSize(ink.px(13));
                c.drawText(ink.t(MODES[i][2]), bar.right + ink.px(8), y + h * 0.8f, paint);
                paint.setTextSize(ink.px(12)); paint.setTypeface(Ink.REGULAR);
                c.drawText(ink.t(MODES[i][3] + (on && !mark.isEmpty() ? " · " + mark : "")), labelW, y + h + ink.px(16), paint);
            }
        }
    }

}
