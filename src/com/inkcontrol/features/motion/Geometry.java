package com.inkcontrol.features.motion;

/**
 * 系统层动画的几何：把“起跑帧图” S(x,y) 换算成每一步的裁剪矩形。
 * 定义与预览页 preview_template.html 的 buildMap() 一致（steps=3 时逐像素相同，见 test/GeometryTest.java）。
 *
 * 约定：像素在第 S 步换成新画面，S 取 0..steps-1。系统层只能裁剪一个窗口，所以：
 *   - 展开（grow）：裁剪新窗口，第 i 步可见区域 = { S <= i }
 *   - 收缩（shrink）：裁剪旧窗口，第 i 步可见区域 = { S > i }
 * 两种区域都是矩形。方形展开在收缩时用反向的图（外圈先换、中心最后换）。
 * 不依赖 Android 类，桌面 JVM 上也能跑测试。
 */
public final class Geometry {
    private Geometry() {}

    /** 方形展开每一步的阈值。steps=3 时就是预览页的 0.18 / 0.55 */
    static double[] irisThresholds(int steps) {
        switch (steps) {
            case 2: return new double[]{0.35};
            case 4: return new double[]{0.12, 0.35, 0.65};
            default: return new double[]{0.18, 0.55};
        }
    }

    /** 擦除图：坐标 c 在第几步换新（预览页 kwipe 的公式，推广到任意步数和方向） */
    static int wipeStep(int c, int len, int steps, boolean reversed) {
        int pos = reversed ? len - 1 - c : c;
        return Math.min(steps - 1, (int) Math.floor((double) pos / len * steps));
    }

    /** 方形展开图：k 是相对切比雪夫距离，按阈值分级（预览页 kiris 的公式） */
    static int irisLevel(double k, double[] thr) {
        int s = 0;
        while (s < thr.length && !(k < thr[s])) s++;
        return s;
    }

    /**
     * 返回 steps 个矩形 {left, top, right, bottom}（right/bottom 不含）。
     * 第 0..steps-2 个是动画进行中的各步；最后一个是结束状态：展开为全屏，收缩为空。
     *
     * @param style  Config.KWIPE 或 Config.KIRIS
     * @param grow   true = 裁剪新窗口（打开、前进）；false = 裁剪旧窗口（关闭、后退）
     * @param dir    擦除方向 ltr / rtl / ttb / btt（只对 kwipe 有意义）：最先换成新画面的一侧
     * @param ox,oy  方形展开的中心（像素）
     */
    public static int[][] stepRects(String style, boolean grow, int steps, int w, int h,
                                    double ox, double oy, String dir) {
        int[][] r = new int[steps][];
        for (int i = 0; i < steps - 1; i++) {
            r[i] = Config.KIRIS.equals(style)
                    ? irisRect(grow, steps, i, w, h, ox, oy)
                    : wipeRect(grow, steps, i, w, h, dir);
        }
        r[steps - 1] = grow ? new int[]{0, 0, w, h} : new int[]{0, 0, 0, 0};
        return r;
    }

    static int[] wipeRect(boolean grow, int steps, int i, int w, int h, String dir) {
        boolean vertical = "ttb".equals(dir) || "btt".equals(dir);
        boolean reversed = "rtl".equals(dir) || "btt".equals(dir);
        int len = vertical ? h : w;
        int a = len, b = 0; // 可见区间 [a, b)
        for (int c = 0; c < len; c++) {
            int s = wipeStep(c, len, steps, reversed);
            if (grow ? s <= i : s > i) {
                if (c < a) a = c;
                b = c + 1;
            }
        }
        if (a >= b) a = b = 0;
        return vertical ? new int[]{0, a, w, b} : new int[]{a, 0, b, h};
    }

    static int[] irisRect(boolean grow, int steps, int i, int w, int h, double ox, double oy) {
        double[] thr = irisThresholds(steps);
        // 展开：可见 = 级别 <= i，即 k < thr[i]
        // 收缩用反向图 S' = steps-1-级别：可见 = S' > i，即 k < thr[steps-2-i]
        double t = grow ? thr[i] : thr[steps - 2 - i];
        double mx = Math.max(ox, w - ox), my = Math.max(oy, h - oy);
        int[] xr = axisRange(w, ox, mx, t), yr = axisRange(h, oy, my, t);
        if (xr[0] >= xr[1] || yr[0] >= yr[1]) return new int[]{0, 0, 0, 0};
        return new int[]{xr[0], yr[0], xr[1], yr[1]};
    }

    // 满足 |c-o|/m < t 的整数 c 的范围 [a, b)
    private static int[] axisRange(int len, double o, double m, double t) {
        int a = len, b = 0;
        for (int c = 0; c < len; c++) {
            if (Math.abs(c - o) / m < t) {
                if (c < a) a = c;
                b = c + 1;
            }
        }
        return new int[]{a, b};
    }

    /** 每步停留时间：A2 帧数 + 3 帧余量（与预览页的 gap = D + 3 相同），扫描 85.8Hz */
    public static int autoHoldMs(double tempC) {
        return (int) Math.round((a2Frames(tempC) + 3) * 1000.0 / 85.8);
    }

    /** WBF 里 A2 在各温区的帧数（墨水屏刷新分析/波形帧数表.txt） */
    public static int a2Frames(double tempC) {
        return A2_FRAMES[com.inkcontrol.core.FeatureDefinition.band(tempC)];
    }
    /** 各温区 A2 帧数，顺序与 FeatureDefinition.TEMPERATURE_BOUNDS 一致，最后一段为 24°C 及以上。 */
    public static final int[] A2_FRAMES = {35, 31, 28, 24, 21, 17, 15, 12, 10};
    public static int autoHoldForBand(int band) { return (int) Math.round((A2_FRAMES[band] + 3) * 1000.0 / 85.8); }
}
