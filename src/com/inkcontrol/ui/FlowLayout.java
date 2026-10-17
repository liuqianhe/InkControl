package com.inkcontrol.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** 自动换行的横向排列，用于选项胶囊和标签。 */
final class FlowLayout extends ViewGroup {
    private final int gapX, gapY;
    FlowLayout(Context context, int gapX, int gapY) { super(context); this.gapX = gapX; this.gapY = gapY; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int limit = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        boolean bounded = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED;
        int x = 0, y = 0, line = 0, widest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(bounded ? limit : 0, bounded ? MeasureSpec.AT_MOST : MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
            if (bounded && x > 0 && x + w > limit) { x = 0; y += line + gapY; line = 0; }
            x += w + gapX; line = Math.max(line, h); widest = Math.max(widest, x - gapX);
        }
        int width = bounded && MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(widthSpec) : widest + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(width, resolveSize(y + line + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int limit = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, line = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
            if (x > 0 && x + w > limit) { x = 0; y += line + gapY; line = 0; }
            child.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
            x += w + gapX; line = Math.max(line, h);
        }
    }
}
