package com.inkcontrol.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 墨水屏界面规范：只用纯黑与纯白。A2 快速模式是 1-bit 输出，灰色会被抖动成噪点；
 * 层级靠字号、字重、描边和反色表达，按压态用反色代替水波纹，不使用阴影和过渡动画。
 */
final class Ink {
    static final int INK = Color.BLACK, PAPER = Color.WHITE;
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    static final Typeface MONO = Typeface.MONOSPACE;

    private final Context context;
    private final float density;
    Ink(Context context) { this.context = context; density = context.getResources().getDisplayMetrics().density; }

    String t(CharSequence value) { return UiLanguage.text(context, value); }

    int dp(float value) { return Math.round(value * density); }
    float px(float value) { return value * density; }

    TextView text(CharSequence value, float sp, Typeface face) {
        TextView view = new TextView(context);
        view.setText(face == MONO ? value : t(value));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(face);
        view.setTextColor(INK);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(px(3), 1f);
        return view;
    }

    GradientDrawable box(int fill, int stroke, float strokeDp, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        if (strokeDp > 0) shape.setStroke(dp(strokeDp), stroke);
        shape.setCornerRadius(px(radiusDp));
        return shape;
    }
    GradientDrawable outline(float radiusDp) { return box(PAPER, INK, 1.5f, radiusDp); }
    GradientDrawable solid(float radiusDp) { return box(INK, INK, 1.5f, radiusDp); }

    /** 按压时反色；normal 为白底黑字时 pressed 为黑底白字，反之亦然。 */
    StateListDrawable pressable(Drawable normal, Drawable pressed) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, normal);
        states.setExitFadeDuration(0);
        states.setEnterFadeDuration(0);
        return states;
    }
    static ColorStateList pressedColors(int normal, int pressed) {
        return new ColorStateList(new int[][]{{android.R.attr.state_pressed}, {}}, new int[]{pressed, normal});
    }

    enum Kind { PRIMARY, SECONDARY, DANGER }
    TextView button(String label, Kind kind, View.OnClickListener click) {
        TextView view = text(label, 16, MEDIUM);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(50));
        view.setPadding(dp(16), dp(12), dp(16), dp(12));
        boolean primary = kind == Kind.PRIMARY;
        float stroke = kind == Kind.DANGER ? 2.5f : 1.5f;
        view.setBackground(pressable(primary ? solid(10) : box(PAPER, INK, stroke, 10), primary ? outline(10) : solid(10)));
        view.setTextColor(primary ? pressedColors(PAPER, INK) : pressedColors(INK, PAPER));
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(click);
        return view;
    }

    /** 选项胶囊：选中为黑底白字，未选为黑框白底。 */
    TextView chip(String label, boolean selected, View.OnClickListener click) {
        TextView view = text(label, 14, selected ? MEDIUM : REGULAR);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(34));
        view.setPadding(dp(13), dp(7), dp(13), dp(7));
        view.setBackground(selected ? solid(18) : pressable(outline(18), solid(18)));
        view.setTextColor(selected ? ColorStateList.valueOf(PAPER) : pressedColors(INK, PAPER));
        view.setSelected(selected);
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(click);
        return view;
    }

    /** 小标签，例如“实验”“重启”。 */
    TextView tag(String label, boolean filled) {
        TextView view = text(label, 11, MEDIUM);
        view.setPadding(dp(6), dp(2), dp(6), dp(2));
        view.setBackground(filled ? solid(4) : box(PAPER, INK, 1, 4));
        view.setTextColor(filled ? PAPER : INK);
        return view;
    }

    View rule(float heightDp) {
        View view = new View(context);
        view.setBackgroundColor(INK);
        view.setLayoutParams(new ViewGroup.LayoutParams(-1, Math.max(1, dp(heightDp))));
        return view;
    }

    /** 无动画的黑白开关图形；状态由 checked 选择，不依赖系统 Switch 的过渡动画。 */
    static final class SwitchGlyph extends Drawable {
        private final boolean on;
        private final int width, height;
        private final float stroke;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        SwitchGlyph(Ink ink, boolean on) { this.on = on; width = ink.dp(50); height = ink.dp(30); stroke = ink.px(2); }
        @Override public int getIntrinsicWidth() { return width; }
        @Override public int getIntrinsicHeight() { return height; }
        @Override public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float h = Math.min(bounds.height(), height), w = Math.min(bounds.width(), width);
            RectF track = new RectF(bounds.centerX() - w / 2 + stroke / 2, bounds.centerY() - h / 2 + stroke / 2,
                    bounds.centerX() + w / 2 - stroke / 2, bounds.centerY() + h / 2 - stroke / 2);
            float r = track.height() / 2;
            paint.setStrokeWidth(stroke);
            paint.setStyle(on ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            paint.setColor(INK);
            canvas.drawRoundRect(track, r, r, paint);
            float knob = r - stroke * 1.6f;
            float cx = on ? track.right - r : track.left + r;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? PAPER : INK);
            canvas.drawCircle(cx, track.centerY(), knob, paint);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    StateListDrawable switchDrawable() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_checked}, new SwitchGlyph(this, true));
        states.addState(new int[]{}, new SwitchGlyph(this, false));
        states.setExitFadeDuration(0);
        states.setEnterFadeDuration(0);
        return states;
    }

    /** 底部导航线性图标，24 格坐标绘制，颜色随选中反色。 */
    static final class Glyph extends Drawable {
        static final int FEATURES = 0, INSTALL = 1, PREVIEW = 2, DIAGNOSE = 3;
        private final int kind, size, color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(Ink ink, int kind, int color) {
            this.kind = kind; this.color = color; size = ink.dp(24);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void draw(Canvas canvas) {
            float s = getBounds().width() / 24f;
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(s, s);
            paint.setColor(color);
            paint.setStrokeWidth(2f);
            paint.setStyle(Paint.Style.STROKE);
            Path path = new Path();
            if (kind == FEATURES) {
                // 三条滑杆
                for (int i = 0; i < 3; i++) { float y = 6 + i * 6; canvas.drawLine(3, y, 21, y, paint); }
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                canvas.drawCircle(15, 6, 2.2f, paint); canvas.drawCircle(8, 12, 2.2f, paint); canvas.drawCircle(17, 18, 2.2f, paint);
            } else if (kind == INSTALL) {
                // 向下箭头进托盘
                canvas.drawLine(12, 3, 12, 14, paint);
                path.moveTo(7, 9.5f); path.lineTo(12, 14.5f); path.lineTo(17, 9.5f); canvas.drawPath(path, paint);
                path.reset(); path.moveTo(4, 15); path.lineTo(4, 20); path.lineTo(20, 20); path.lineTo(20, 15); canvas.drawPath(path, paint);
            } else if (kind == PREVIEW) {
                // 手机外框与内容块
                canvas.drawRoundRect(new RectF(6, 2.5f, 18, 21.5f), 2.5f, 2.5f, paint);
                canvas.drawLine(9.5f, 7, 14.5f, 7, paint);
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                canvas.drawRect(9.5f, 11, 14.5f, 15, paint);
            } else {
                // 脉搏线
                path.moveTo(2.5f, 12); path.lineTo(7, 12); path.lineTo(9.5f, 5); path.lineTo(14, 19); path.lineTo(16.5f, 12); path.lineTo(21.5f, 12);
                canvas.drawPath(path, paint);
            }
            canvas.restore();
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
