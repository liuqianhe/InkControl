package com.inkcontrol.features.lock;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 状态栏歌词：在左侧信号图标和右侧电量/时钟之间（原通知小图标区）显示当前歌词。
 * 有歌词时暂时隐藏通知小图标，歌词停止后恢复。墨水屏上不做滚动，超长部分用省略号截断。
 */
final class StatusBarLyric {
    private static final boolean DEBUG = false;
    private static final String STATUS_BAR_VIEW = "com.android.systemui.statusbar.phone.PhoneStatusBarView";

    /** 状态栏可能因配置变化重建，所以每个实例各存一份 */
    private static final List<Entry> sEntries = new ArrayList<>();

    private static final class Entry {
        final WeakReference<TextView> lyricView;
        final WeakReference<View> iconArea;
        final WeakReference<TextView> clock;

        Entry(TextView lyricView, View iconArea, TextView clock) {
            this.lyricView = new WeakReference<>(lyricView);
            this.iconArea = new WeakReference<>(iconArea);
            this.clock = new WeakReference<>(clock);
        }
    }

    private StatusBarLyric() {
    }

    static void hook(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(STATUS_BAR_VIEW, cl, "onFinishInflate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    attach((ViewGroup) param.thisObject);
                } catch (Throwable t) {
                    XposedBridge.log("EinkLyric: status bar attach failed: " + t);
                }
            }
        });
    }

    private static void attach(ViewGroup statusBar) {
        Context ctx = statusBar.getContext();
        View iconArea = statusBar.findViewById(id(ctx, "notification_icon_area"));
        if (iconArea == null || !(iconArea.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) iconArea.getParent();
        TextView clock = statusBar.findViewById(id(ctx, "clock"));

        TextView tv = new TextView(ctx);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        tv.setIncludeFontPadding(false);
        tv.setVisibility(View.GONE);
        if (clock != null) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, clock.getTextSize());
            tv.setTypeface(clock.getTypeface());
            tv.setTextColor(clock.getCurrentTextColor());
        }
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6,
                ctx.getResources().getDisplayMetrics());
        tv.setPadding(pad, 0, pad, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        parent.addView(tv, parent.indexOfChild(iconArea) + 1, lp);

        // 海信状态栏是两层叠放：左层（信号+通知区）铺满全宽，右侧电量/时钟在另一层上。
        // 所以要按右侧图标实际的最左边，给歌词留出右边距，否则会盖住电量和时钟。
        final ViewGroup rightIcons = statusBar.findViewById(id(ctx, "system_icon_area"));
        if (rightIcons != null) {
            View.OnLayoutChangeListener l = (v, a, b, c, d, e, f, g, h) -> fitRightEdge(tv, rightIcons);
            rightIcons.addOnLayoutChangeListener(l);
            for (int i = 0; i < rightIcons.getChildCount(); i++) {
                rightIcons.getChildAt(i).addOnLayoutChangeListener(l);
            }
            tv.addOnLayoutChangeListener(l);
        }

        sEntries.add(new Entry(tv, iconArea, clock));
        update(Hook.activeLyric());
    }

    /** 主线程调用；lyric 为 null 表示没有歌词 */
    static void update(String lyric) {
        boolean show = lyric != null && Hook.statusBarEnabled();
        for (Iterator<Entry> it = sEntries.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            TextView tv = e.lyricView.get();
            View icons = e.iconArea.get();
            if (tv == null || icons == null) {
                it.remove();
                continue;
            }
            if (show) {
                TextView clock = e.clock.get();
                // 跟随时钟颜色（深色应用里状态栏会反色）
                if (clock != null) tv.setTextColor(clock.getCurrentTextColor());
                tv.setText(lyric);
                tv.setVisibility(View.VISIBLE);
                icons.setVisibility(View.GONE);
            } else if (tv.getVisibility() != View.GONE) {
                tv.setText(null);
                tv.setVisibility(View.GONE);
                icons.setVisibility(View.VISIBLE);
            }
        }
    }

    private static void fitRightEdge(TextView tv, ViewGroup rightIcons) {
        if (tv.getVisibility() != View.VISIBLE) return;
        int leftmost = leftmostContent(rightIcons);
        if (leftmost == Integer.MAX_VALUE) return;
        int[] loc = new int[2];
        View parent = (View) tv.getParent();
        parent.getLocationInWindow(loc);
        int margin = Math.max(0, loc[0] + parent.getWidth() - leftmost);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tv.getLayoutParams();
        if (DEBUG) {
            StringBuilder sb = new StringBuilder("EinkLyric: sb parent=" + parent.getWidth() + " leftmost=" + leftmost + " margin=" + margin + " tv=" + tv.getLeft() + "+" + tv.getWidth() + " siblings:");
            ViewGroup pg = (ViewGroup) parent;
            for (int i = 0; i < pg.getChildCount(); i++) {
                View c = pg.getChildAt(i);
                sb.append(" [").append(c.getClass().getSimpleName()).append(" vis=").append(c.getVisibility()).append(" ").append(c.getLeft()).append("+").append(c.getWidth()).append("]");
            }
            XposedBridge.log(sb.toString());
        }
        if (lp.rightMargin != margin) {
            lp.rightMargin = margin;
            // 在布局回调里直接 requestLayout 会被忽略，放到下一帧
            tv.post(() -> tv.setLayoutParams(lp));
        }
    }

    /** 右侧容器本身很宽、内容靠右，所以要找真正可见的叶子控件（图标、电量、时钟）的最左边 */
    private static int leftmostContent(View v) {
        if (v.getVisibility() != View.VISIBLE || v.getWidth() == 0) return Integer.MAX_VALUE;
        if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) {
            ViewGroup g = (ViewGroup) v;
            int min = Integer.MAX_VALUE;
            for (int i = 0; i < g.getChildCount(); i++) {
                min = Math.min(min, leftmostContent(g.getChildAt(i)));
            }
            return min;
        }
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return loc[0] + v.getPaddingLeft();
    }

    private static int id(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "id", ctx.getPackageName());
    }
}
