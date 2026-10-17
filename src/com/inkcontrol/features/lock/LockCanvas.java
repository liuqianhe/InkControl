package com.inkcontrol.features.lock;

import com.inkcontrol.shared.WeatherIcon;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 墨水屏锁屏（编辑排版 / 瑞士网格 / 1-bit 像素 / 液态玻璃）的绘制器。
 * 按样张的 360 × 720 逻辑坐标作画，整体放大到实际尺寸（A5 为 720 × 1440，正好 2 倍）。
 * 前三种以黑白图形为主；玻璃用 16 级灰阶材质，配合清晰全刷保留明暗层次。
 */
final class LockCanvas extends View {
    static final int W = 360, H = 720;
    private static final int INK = Color.BLACK, PAPER = Color.WHITE;

    private static Typeface sSerif, sSerifBold, sSans, sSansMedium, sSansBold, sHeavy;

    private int style;
    private LockData data;

    // 每次 draw() 时有效
    private Canvas c;
    private final Paint fill = new Paint();
    private final TextPaint tp = new TextPaint();

    LockCanvas(Context context) {
        super(context);
        initFonts();
    }

    void set(int style, LockData data) {
        this.style = style;
        this.data = data;
        invalidate();
    }

    private static void initFonts() {
        if (sSerif != null) return;
        // 中文由系统回退到 Noto Serif CJK / Noto Sans CJK
        sSerif = Typeface.create("serif", Typeface.NORMAL);
        sSerifBold = Typeface.create("serif", Typeface.BOLD);
        sSans = Typeface.create("sans-serif", Typeface.NORMAL);
        sSansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        sSansBold = Typeface.create("sans-serif", Typeface.BOLD);
        sHeavy = Typeface.create("sans-serif-black", Typeface.NORMAL);
    }

    /** 直接画成一张位图（调试出图、设置预览用） */
    static Bitmap render(Context context, int style, LockData data, int width, int height) {
        LockCanvas v = new LockCanvas(context);
        v.set(style, data);
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        v.paint(canvas, width, height);
        return b;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        paint(canvas, getWidth(), getHeight());
    }

    private void paint(Canvas canvas, int width, int height) {
        if (data == null || width <= 0 || height <= 0) return;
        float s = Math.min(width / (float) W, height / (float) H);
        canvas.drawColor(PAPER);
        canvas.save();
        canvas.translate((width - W * s) / 2f, (height - H * s) / 2f);
        canvas.scale(s, s);
        c = canvas;
        try {
            if (style == LockStyle.GLASS) glass();
            else if (style == LockStyle.SWISS) swiss();
            else if (style == LockStyle.PIXEL) pixel();
            else editorial();
        } finally {
            c = null;
            canvas.restore();
        }
    }

    // ───────────────────────── 基础绘制 ─────────────────────────

    private void rect(float x, float y, float w, float h, int color) {
        fill.reset();
        fill.setColor(color);
        fill.setAntiAlias(false);
        c.drawRect(x, y, x + w, y + h, fill);
    }

    private void frame(float x, float y, float w, float h, float t) {
        rect(x, y, w, t, INK);
        rect(x, y + h - t, w, t, INK);
        rect(x, y, t, h, INK);
        rect(x + w - t, y, t, h, INK);
    }

    private TextPaint font(Typeface face, float size, float spacingEm, int color, boolean aa) {
        tp.reset();
        tp.setTypeface(face);
        tp.setTextSize(size);
        tp.setLetterSpacing(spacingEm);
        tp.setColor(color);
        tp.setAntiAlias(aa);
        tp.setSubpixelText(aa);
        return tp;
    }

    /** 在 (x, top) 处画一行字（top 为字身顶端），超宽时横向压缩到 maxW。返回实际宽度 */
    private float text(String s, float x, float top, float maxW) {
        if (s == null || s.isEmpty()) return 0;
        float w = tp.measureText(s);
        if (maxW > 0 && w > maxW) {
            tp.setTextScaleX(maxW / w);
            w = maxW;
        }
        c.drawText(s, x, top - tp.ascent(), tp);
        tp.setTextScaleX(1f);
        return w;
    }

    /** 以基线定位画一行字 */
    private float textBase(String s, float x, float baseline, float maxW) {
        if (s == null || s.isEmpty()) return 0;
        float w = tp.measureText(s);
        if (maxW > 0 && w > maxW) {
            tp.setTextScaleX(maxW / w);
            w = maxW;
        }
        c.drawText(s, x, baseline, tp);
        tp.setTextScaleX(1f);
        return w;
    }

    private void textRight(String s, float right, float top, float maxW) {
        if (s == null) return;
        float w = Math.min(tp.measureText(s), maxW);
        text(s, right - w, top, maxW);
    }

    private void textCenter(String s, float cx, float top) {
        if (s == null) return;
        // 字间距会在末尾多留一个间距，居中时扣掉
        float w = tp.measureText(s) - tp.getLetterSpacing() * tp.getTextSize();
        text(s, cx - w / 2f, top, 0);
    }

    /** 多行段落，lineHeight 为 CSS 式的行高（倍数）。返回高度 */
    private float paragraph(String s, float x, float top, float width, float lineHeight, int maxLines) {
        if (s == null || s.isEmpty()) return 0;
        Paint.FontMetrics fm = tp.getFontMetrics();
        float natural = fm.descent - fm.ascent;
        float add = tp.getTextSize() * lineHeight - natural;
        StaticLayout layout = StaticLayout.Builder.obtain(s, 0, s.length(), tp, (int) width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(add, 1f)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        c.save();
        c.translate(x, top + add / 2f);
        layout.draw(c);
        c.restore();
        return layout.getLineCount() * tp.getTextSize() * lineHeight;
    }

    // ───────────────────────── 数据格式化 ─────────────────────────

    // ───────────────────────── D · 液态玻璃 ─────────────────────────

    private void glass() {
        // 16 级灰阶误差扩散在构建时以 720×1440 原生分辨率完成，运行时仅解码一次。
        // 源图按 360×720 逻辑坐标缩放，2x 送屏时每个点仍是一个实际像素。
        fill.reset();
        fill.setFilterBitmap(false);
        c.drawBitmap(GlassTexture.bitmap(), null, new RectF(0, 0, W, H), fill);

        font(sSansBold, 76, -0.045f, INK, true);
        textBase(time(), 32, 138, 296);
        font(sSansMedium, 16, 0, INK, true);
        text(fmt("M月d日") + "  " + weekCn(), 36, 157, 288);
        if (almanac() != null) {
            font(sSans, 11, 0, INK, true);
            text(almanac(), 37, 187, 286);
        }

        font(sSansMedium, 12, 0.04f, INK, true);
        text("今日天气", 54, 289, 150);
        font(sSansMedium, 17, 0, INK, true);
        textRight(dash(data.weather), 304, 291, 115);
        boolean weatherIcon = WeatherIcon.available(data.weather);
        if (weatherIcon) WeatherIcon.draw(c, data.weather, 239, 326, 64, INK);
        font(sSansBold, 58, -0.03f, INK, true);
        textBase(dash(data.temperature), 52, 386, weatherIcon ? 166 : 248);
        font(sSans, 13, 0, INK, true);
        String range = data.low != null && data.high != null ? data.low + " — " + data.high : "气温数据暂无";
        text(range, 55, 404, 248);

        font(sSansMedium, 12, 0.04f, INK, true);
        text(data.playing ? "正在播放" : "今日格言", 54, 502, 250);
        font(sSansBold, 21, 0, INK, true);
        String main = data.text != null ? data.text : data.playing ? dash(data.track) : "留一点时间，慢慢读。";
        paragraph(main, 54, 532, 252, 1.3f, 2);
        String sub = data.track != null && !data.track.equals(main) ? data.track : data.subText;
        if (sub != null) {
            font(sSans, 11, 0, INK, true);
            paragraph(sub, 55, 599, 250, 1.3f, 1);
        }
        if (data.playing && data.duration > 0 && data.position >= 0) {
            // 只在原有走时/歌词更新事件重绘，不新增定时动画或连续刷屏。
            float progress = Math.max(0f, Math.min(1f, data.position / (float) data.duration));
            frame(55, 622, 250, 2, .5f);
            rect(55, 622, 250 * progress, 2, INK);
        }

        font(sSansMedium, 11, 0, INK, true);
        text("电量 " + (data.battery < 0 ? "—" : data.battery + "%") + (data.charging ? " · 充电中" : ""), 35, 661, 170);
        if (data.wechat >= 0) {
            textRight(data.wechat > 0 ? "微信 " + data.wechat + " 条新消息" : "暂无新消息", 325, 661, 140);
        }
        font(sSansMedium, 12, .12f, INK, true);
        textCenter("上滑解锁", 180, 691);
    }

    private static final String[] CN = {"〇", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
    private static final String[] WEEK_CN = {"日", "一", "二", "三", "四", "五", "六"};

    private String fmt(String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(data.now.getTime());
    }

    private String hour() {
        return fmt(data.is24h ? "HH" : "hh");
    }

    private String time() {
        return hour() + ":" + fmt("mm");
    }

    private static String chineseNumber(int n) {
        if (n < 10) return CN[n];
        if (n == 10) return "十";
        if (n < 20) return "十" + CN[n % 10];
        return CN[n / 10] + "十" + (n % 10 == 0 ? "" : CN[n % 10]);
    }

    private String chineseDate() {
        return chineseNumber(data.now.get(Calendar.MONTH) + 1) + "月"
                + chineseNumber(data.now.get(Calendar.DAY_OF_MONTH)) + "日";
    }

    private String weekCn() {
        return "星期" + WEEK_CN[data.now.get(Calendar.DAY_OF_WEEK) - 1];
    }

    /** 节日/节气优先，其次农历 */
    private String almanac() {
        if (data.festival != null) return data.festival;
        return data.lunar;
    }

    private static String dash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }

    /** 去掉温度单位（瑞士网格里只要数字） */
    private static String strip(String temp) {
        return temp == null ? "—" : temp.replace("°F", "").replace("°", "");
    }

    private String batteryText() {
        return data.battery < 0 ? "—" : String.valueOf(data.battery);
    }

    // ───────────────────────── A · 编辑排版 ─────────────────────────

    private void editorial() {
        final float L = 24, R = 336, CW = R - L;

        font(sSerif, 11, 0.18f, INK, true);
        text(fmt("EEE · dd MMM yyyy").toUpperCase(Locale.US), L, 28, 220);
        font(sSerif, 11, 0.18f, INK, true);
        textRight("第 " + data.now.get(Calendar.DAY_OF_YEAR) + " 日", R + 2, 28, 90);
        rect(L, 52, CW, 3, INK);
        rect(L, 58, CW, 1, INK);

        // 大号时间：衬线体横向收窄铺满版心，接近样张里 Instrument Serif 的瘦长比例
        font(sSerif, 136, -0.03f, INK, true);
        float tw = tp.measureText(time());
        tp.setTextScaleX(Math.max(0.7f, Math.min(1f, (CW + 4) / tw)));
        c.drawText(time(), L - 4, 200, tp);
        tp.setTextScaleX(1f);

        font(sSerifBold, 22, 0, INK, true);
        textBase(chineseDate(), L, 252, 150);
        String sub = weekCn() + (almanac() == null ? "" : " · " + almanac());
        font(sSerif, 14, 0, INK, true);
        float sw = Math.min(tp.measureText(sub), CW - 150);
        textBase(sub, R - sw, 252, CW - 150);

        // 三栏：天气 / 气温 / 电量
        float gy = 284;
        rect(L, gy, CW, 1, INK);
        rect(L, gy + 66, CW, 1, INK);
        rect(L + 104, gy, 1, 66, INK);
        rect(L + 208, gy, 1, 66, INK);
        String[] labels = {"天气", "气温", "电量"};
        for (int i = 0; i < 3; i++) {
            float x = L + i * 104 + (i == 0 ? 0 : 12);
            font(sSerif, 10, 0.24f, INK, true);
            text(labels[i], x, gy + 11, 90);
        }
        font(sSerif, 18, 0, INK, true);
        textBase(dash(data.weather), L, gy + 51, 92);
        font(sSerif, 22, 0, INK, true);
        String temp = data.high != null ? data.high + " / " + data.low : dash(data.temperature);
        textBase(temp, L + 116, gy + 52, 88);
        textBase(data.battery < 0 ? "—" : data.battery + "%", L + 220, gy + 52, 88);

        // 正在播放 / 今日格言
        float py = 384;
        font(sSerif, 10, 0.3f, INK, true);
        text(data.playing ? "正在播放" : "今日格言", L, py, 0);
        font(sSerif, 64, 0, INK, true);
        textBase("“", L - 2, py + 62, 0);
        float ph = 0;
        if (data.text != null) {
            font(sSerifBold, 21, 0, INK, true);
            ph = paragraph(data.text, L, py + 50, CW, 1.55f, 3);
        }
        if (data.track != null) {
            font(sSerif, 12, 0.08f, INK, true);
            text("— " + data.track, L, py + 50 + ph + 10, CW);
        } else if (data.subText != null) {
            font(sSerif, 12, 0.02f, INK, true);
            paragraph(data.subText, L, py + 50 + ph + 8, CW, 1.5f, 3);
        }

        // 底部：微信提醒条 + 上滑解锁
        float hintTop = 671;
        if (data.wechat > 0) {
            rect(L, 613, CW, 42, INK);
            font(sSerifBold, 14, 0, PAPER, true);
            float w = textBase("微信", L + 14, 639, 0);
            font(sSerif, 14, 0, PAPER, true);
            textBase("　" + data.wechat + " 条新消息", L + 14 + w, 639, CW - 40);
        } else if (data.wechat == 0) {
            frame(L, 613, CW, 42, 1);
            font(sSerif, 14, 0, INK, true);
            textBase("暂无新消息", L + 14, 639, 0);
        }
        rect(L, hintTop, CW, 1, INK);
        font(sSerif, 11, 0.6f, INK, true);
        textCenter("上滑解锁", W / 2f, hintTop + 10);
    }

    // ───────────────────────── B · 瑞士网格 ─────────────────────────

    private void swiss() {
        final float L = 20, CW = 320, COL = 74, GAP = 8;

        // 顶部四栏
        String day = fmt("EEE");
        String[] top = {day.substring(0, Math.min(2, day.length())), fmt("MM.dd"), fmt("yyyy"),
                data.temperature == null ? "—" : data.temperature + "C"};
        if (data.temperature != null && data.temperature.endsWith("F")) top[3] = data.temperature;
        for (int i = 0; i < 4; i++) {
            float x = L + i * (COL + GAP);
            rect(x, 24, COL, 4, INK);
            font(sSansBold, 12, 0, INK, true);
            text(top[i], x, 34, COL);
        }

        // 两行叠放的特大时间
        font(sHeavy, 196, -0.07f, INK, true);
        textBase(hour(), L - 6, 219, 290);
        textBase(fmt("mm"), L - 6, 376, 290);

        // 竖排日期
        String vertical = chineseDate() + " " + weekCn();
        font(sSansBold, 13, 0, INK, true);
        float vy = 76;
        for (int i = 0; i < vertical.length(); i++) {
            String ch = vertical.substring(i, i + 1);
            if (!ch.equals(" ")) {
                float w = tp.measureText(ch);
                text(ch, 334 - w / 2f, vy, 0);
            }
            vy += 18.2f;
        }

        // 黑底天气块
        float by = 411;
        rect(L, by, CW, 84, INK);
        boolean range = data.high != null;
        font(sSansBold, 10, 0.12f, PAPER, true);
        text("WEATHER", L + 16, by + 14, 0);
        text(range ? "HI / LO" : "TEMP", L + 164, by + 14, 0);
        font(sSansMedium, 28, 0, PAPER, true);
        text(dash(data.weather), L + 16, by + 32, 140);
        font(sHeavy, 28, -0.03f, PAPER, true);
        text(range ? strip(data.high) + " / " + strip(data.low) : dash(data.temperature), L + 164, by + 32, 140);

        // 正在播放 / 格言
        float ny = 517;
        rect(L, ny, CW, 1, INK);
        font(sSansBold, 10, 0.12f, INK, true);
        text(data.playing ? "NOW" : "DAILY", L, ny + 9, COL);
        text(data.playing ? "PLAYING" : "QUOTE", L, ny + 22, COL);
        float tx = L + COL + GAP, tw = CW - COL - GAP;
        float th = 0;
        if (data.text != null) {
            font(sSansBold, 17, 0, INK, true);
            th = paragraph(data.text, tx, ny + 8, tw, 1.45f, 3);
        }
        if (data.track != null) {
            font(sSans, 12, 0, INK, true);
            text(data.track, tx, ny + 8 + th + 4, tw);
        } else if (data.subText != null) {
            font(sSans, 12, 0, INK, true);
            paragraph(data.subText, tx, ny + 8 + th + 4, tw, 1.4f, 3);
        }

        // 底部数据行
        float fy = 640;
        rect(L, fy, CW, 4, INK);
        String[] nums = {batteryText(), data.wechat < 0 ? "—" : String.valueOf(data.wechat),
                String.valueOf(data.now.get(Calendar.WEEK_OF_YEAR))};
        String[] labels = {"电量 %", "微信", "第几周"};
        for (int i = 0; i < 3; i++) {
            float x = L + i * (COL + GAP);
            font(sHeavy, 30, -0.04f, INK, true);
            text(nums[i], x, fy + 12, COL);
            font(sSans, 10, 0, INK, true);
            text(labels[i], x, fy + 46, COL);
        }
        font(sSansBold, 11, 0, INK, true);
        textRight("↑ 解锁", L + CW, fy + 47, COL);
    }

    // ───────────────────────── C · 1-bit 像素 ─────────────────────────

    private static Bitmap sDots;

    private void pixel() {
        // 桌面点阵：每 3×3 逻辑像素一个点
        if (sDots == null) {
            sDots = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888);
            sDots.eraseColor(PAPER);
            sDots.setPixel(0, 0, INK);
        }
        Paint dots = new Paint();
        dots.setAntiAlias(false);
        dots.setFilterBitmap(false);
        dots.setShader(new BitmapShader(sDots, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        c.drawRect(0, 0, W, H, dots);

        final float X = 18, WW = 324, IN = X + 16, IW = WW - 32;

        // 窗口 1：时钟
        float y1 = 22;
        window(X, y1, WW, 150, "CLOCK");
        pixText(time(), IN, y1 + 38, 9);
        pixText(fmt("EEE dd.MM.yyyy").toUpperCase(Locale.US), IN, y1 + 122, 1.5f);
        float tempW = data.temperature == null ? 0 : pixWidth(data.temperature, 1.5f);
        if (data.temperature != null) pixText(data.temperature, IN + IW - tempW, y1 + 122, 1.5f);
        if (data.weather != null) icon(ICON_CLOUD, IN + IW - tempW - 30, y1 + 115, 1.5f, INK);

        // 窗口 2：正在播放 / 格言
        float y2 = y1 + 150 + 22;
        boolean progress = data.playing && data.duration > 0 && data.position >= 0;
        font(sSans, 21, 0, INK, false);
        float textH = 0;
        StaticLayout probe = null;
        if (data.text != null) {
            probe = StaticLayout.Builder.obtain(data.text, 0, data.text.length(), tp, (int) (IW - 44))
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            textH = Math.max(32, probe.getLineCount() * 21 * 1.35f);
        }
        float subH = 0;
        if (data.track == null && data.subText != null) {
            font(sSans, 12, 0, INK, false);
            StaticLayout sub = StaticLayout.Builder.obtain(data.subText, 0, data.subText.length(), tp, (int) IW)
                    .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
            subH = sub.getLineCount() * 12 * 1.4f;
        }
        float h2 = 24 + 14 + textH + (data.track != null ? 10 + 11 : 0) + (subH > 0 ? 10 + subH : 0)
                + (progress ? 10 + 10 : 0) + 14 + 4;
        window(X, y2, WW, h2, data.playing ? "NOW PLAYING" : "DAILY QUOTE");
        float cy = y2 + 24 + 14;
        icon(data.playing ? ICON_NOTE : ICON_QUOTE, IN, cy, 2, INK);
        if (data.text != null) {
            font(sSans, 21, 0, INK, false);
            paragraph(data.text, IN + 44, cy, IW - 44, 1.35f, 3);
        }
        cy += textH;
        if (data.track != null) {
            cy += 10;
            mixedText(data.track, IN, cy, IW, 1.5f, 12);
            cy += 11;
        } else if (subH > 0) {
            cy += 10;
            font(sSans, 12, 0, INK, false);
            paragraph(data.subText, IN, cy, IW, 1.4f, 3);
            cy += subH;
        }
        if (progress) {
            cy += 10;
            int filled = Math.round(20f * Math.min(1, Math.max(0, data.position / (float) data.duration)));
            for (int i = 0; i < 20; i++) {
                float sx = IN + i * 13;
                frame(sx, cy, 10, 10, 2);
                if (i < filled) rect(sx, cy, 10, 10, INK);
            }
        }

        // 窗口 3：电量 / 微信
        float y3 = y2 + h2 + 22;
        window(X, y3, WW, 48, null);
        icon(ICON_BATTERY, IN - 2, y3 + 12, 1.5f, INK);
        if (data.battery >= 0) {
            // 电池图标内部按电量填充（16 格网格里内腔为 x 3..9）
            float inner = 6 * 1.5f * data.battery / 100f;
            rect(IN - 2 + 3 * 1.5f, y3 + 12 + 6 * 1.5f, 6 * 1.5f, 4 * 1.5f, PAPER);
            rect(IN - 2 + 3 * 1.5f, y3 + 12 + 6 * 1.5f, Math.round(inner / 1.5f) * 1.5f, 4 * 1.5f, INK);
        }
        pixText(batteryText() + "%", IN + 26, y3 + 19, 1.5f);
        if (data.wechat >= 0) {
            String n = String.valueOf(data.wechat);
            float nw = pixWidth(n, 1.5f);
            pixText(n, IN + IW - nw, y3 + 19, 1.5f);
            font(sSans, 16, 0, INK, false);
            float lw = tp.measureText("微信");
            text("微信", IN + IW - nw - 6 - lw, y3 + 15, 0);
            icon(ICON_WECHAT, IN + IW - nw - 6 - lw - 30, y3 + 12, 1.5f, INK);
        }

        // 底部：滑动解锁
        float yb = H - 18 - 40;
        rect(X, yb, WW, 40, PAPER);
        frame(X, yb, WW, 40, 2);
        String hint = ">> SLIDE TO UNLOCK";
        float hw = pixWidth(hint, 1.5f);
        pixText(hint, (W - hw) / 2f, yb + 15, 1.5f);
    }

    /** 老式 Mac 窗口：2px 边框、4px 实心投影、条纹标题栏 */
    private void window(float x, float y, float w, float h, String title) {
        rect(x + 4, y + 4, w, h, INK);
        rect(x, y, w, h, INK);
        rect(x + 2, y + 2, w - 4, h - 4, PAPER);
        if (title == null) return;
        float sx = x + 2 + 6, sy = y + 2 + 5, swd = w - 4 - 12;
        for (int i = 0; i < 5; i++) rect(sx, sy + i * 2, swd, 1, INK);
        rect(x + 2, y + 22, w - 4, 2, INK);
        float tw = pixWidth(title, 1);
        rect(x + (w - tw) / 2f - 8, sy - 1, tw + 16, 12, PAPER);
        pixText(title, x + (w - tw) / 2f, sy + 1.5f, 1);
    }

    // ───────────── 5×7 点阵字（只含锁屏用到的 ASCII，按位图逐格画方块） ─────────────

    private static final Map<Character, String[]> GLYPHS = new HashMap<>();

    private static void g(char ch, String... rows) {
        GLYPHS.put(ch, rows);
    }

    static {
        g('0', "01110", "10001", "10001", "10001", "10001", "10001", "01110");
        g('1', "00100", "01100", "00100", "00100", "00100", "00100", "01110");
        g('2', "01110", "10001", "00001", "00010", "00100", "01000", "11111");
        g('3', "11111", "00010", "00100", "00010", "00001", "10001", "01110");
        g('4', "00010", "00110", "01010", "10010", "11111", "00010", "00010");
        g('5', "11111", "10000", "11110", "00001", "00001", "10001", "01110");
        g('6', "00110", "01000", "10000", "11110", "10001", "10001", "01110");
        g('7', "11111", "00001", "00010", "00100", "01000", "01000", "01000");
        g('8', "01110", "10001", "10001", "01110", "10001", "10001", "01110");
        g('9', "01110", "10001", "10001", "01111", "00001", "00010", "01100");
        g('A', "01110", "10001", "10001", "10001", "11111", "10001", "10001");
        g('B', "11110", "10001", "10001", "11110", "10001", "10001", "11110");
        g('C', "01110", "10001", "10000", "10000", "10000", "10001", "01110");
        g('D', "11100", "10010", "10001", "10001", "10001", "10010", "11100");
        g('E', "11111", "10000", "10000", "11110", "10000", "10000", "11111");
        g('F', "11111", "10000", "10000", "11110", "10000", "10000", "10000");
        g('G', "01110", "10001", "10000", "10111", "10001", "10001", "01111");
        g('H', "10001", "10001", "10001", "11111", "10001", "10001", "10001");
        g('I', "01110", "00100", "00100", "00100", "00100", "00100", "01110");
        g('J', "00111", "00010", "00010", "00010", "00010", "10010", "01100");
        g('K', "10001", "10010", "10100", "11000", "10100", "10010", "10001");
        g('L', "10000", "10000", "10000", "10000", "10000", "10000", "11111");
        g('M', "10001", "11011", "10101", "10101", "10001", "10001", "10001");
        g('N', "10001", "10001", "11001", "10101", "10011", "10001", "10001");
        g('O', "01110", "10001", "10001", "10001", "10001", "10001", "01110");
        g('P', "11110", "10001", "10001", "11110", "10000", "10000", "10000");
        g('Q', "01110", "10001", "10001", "10001", "10101", "10010", "01101");
        g('R', "11110", "10001", "10001", "11110", "10100", "10010", "10001");
        g('S', "01111", "10000", "10000", "01110", "00001", "00001", "11110");
        g('T', "11111", "00100", "00100", "00100", "00100", "00100", "00100");
        g('U', "10001", "10001", "10001", "10001", "10001", "10001", "01110");
        g('V', "10001", "10001", "10001", "10001", "10001", "01010", "00100");
        g('W', "10001", "10001", "10001", "10101", "10101", "10101", "01010");
        g('X', "10001", "10001", "01010", "00100", "01010", "10001", "10001");
        g('Y', "10001", "10001", "10001", "01010", "00100", "00100", "00100");
        g('Z', "11111", "00001", "00010", "00100", "01000", "10000", "11111");
        g(':', "00", "11", "11", "00", "11", "11", "00");
        g('.', "00", "00", "00", "00", "00", "11", "11");
        g(',', "00", "00", "00", "00", "11", "01", "10");
        g('\'', "1", "1", "0", "0", "0", "0", "0");
        g('!', "1", "1", "1", "1", "1", "0", "1");
        g('?', "01110", "10001", "00001", "00010", "00100", "00000", "00100");
        g('%', "11000", "11001", "00010", "00100", "01000", "10011", "00011");
        g('-', "0000", "0000", "0000", "1111", "0000", "0000", "0000");
        g('>', "100", "010", "001", "010", "100", "000", "000");
        g('<', "001", "010", "100", "010", "001", "000", "000");
        g('/', "00001", "00010", "00010", "00100", "01000", "01000", "10000");
        g('°', "010", "101", "010", "000", "000", "000", "000");
        g('(', "01", "10", "10", "10", "10", "10", "01");
        g(')', "10", "01", "01", "01", "01", "01", "10");
        g('&', "01100", "10010", "10100", "01000", "10101", "10010", "01101");
        g('·', "0", "0", "0", "1", "0", "0", "0");
        g(' ', "000", "000", "000", "000", "000", "000", "000");
    }

    private static String[] glyph(char ch) {
        return GLYPHS.get(Character.toUpperCase(ch));
    }

    private static boolean pixelable(String s) {
        for (int i = 0; i < s.length(); i++) if (glyph(s.charAt(i)) == null) return false;
        return true;
    }

    private static float pixWidth(String s, float u) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            String[] gl = glyph(s.charAt(i));
            if (gl == null) continue;
            w += (gl[0].length() + 1) * u;
        }
        return Math.max(0, w - u);
    }

    /** 点阵字，u 为一格的逻辑像素数（×2 后须为整数物理像素） */
    private float pixText(String s, float x, float top, float u) {
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            String[] gl = glyph(s.charAt(i));
            if (gl == null) continue;
            for (int r = 0; r < gl.length; r++) {
                String row = gl[r];
                for (int col = 0; col < row.length(); col++) {
                    if (row.charAt(col) == '1') rect(cx + col * u, top + r * u, u, u, INK);
                }
            }
            cx += (gl[0].length() + 1) * u;
        }
        return cx - x;
    }

    /** 能用点阵字就用点阵字，否则（中文等）用无抗锯齿的黑体 */
    private void mixedText(String s, float x, float top, float maxW, float u, float size) {
        if (pixelable(s) && pixWidth(s, u) <= maxW) {
            pixText(s, x, top, u);
        } else {
            font(sSans, size, 0, INK, false);
            text(s, x, top - 2, maxW);
        }
    }

    // ───────────── 16×16 像素图标（只用水平/垂直线段的路径，奇偶填充） ─────────────

    private static final String ICON_CLOUD = "M5 5h4v1h2v1h2v1h1v4H2V9h1V7h2z";
    private static final String ICON_NOTE = "M6 2h8v2H6zM6 4h2v6H6zM12 4h2v4h-2zM3 10h5v4H3zM9 8h5v4H9z";
    private static final String ICON_QUOTE = "M2 4h5v5H5v3H2zM9 4h5v5h-2v3H9z";
    private static final String ICON_BATTERY = "M1 4h12v8H1zM2 5h10v6H2zM13 6h2v4h-2z";
    private static final String ICON_WECHAT = "M1 1h9v6H4v1H3v1H2V7H1zM3 3h1v1H3zM7 3h1v1H7z"
            + "M8 8h7v5h-1v2h-1v-1h-1v-1h-1v-1H8zM10 10h1v1h-1zM12 10h1v1h-1z";

    private void icon(String d, float x, float y, float scale, int color) {
        Path path = parse(d);
        path.setFillType(Path.FillType.EVEN_ODD);
        fill.reset();
        fill.setColor(color);
        fill.setAntiAlias(false);
        c.save();
        c.translate(x, y);
        c.scale(scale, scale);
        c.drawPath(path, fill);
        c.restore();
    }

    /** 只支持 M/m H/h V/v Z/z（像素图标够用） */
    private static Path parse(String d) {
        Path p = new Path();
        float cx = 0, cy = 0, sx = 0, sy = 0;
        int i = 0, n = d.length();
        char cmd = 'M';
        while (i < n) {
            char ch = d.charAt(i);
            if (Character.isLetter(ch)) {
                cmd = ch;
                i++;
                if (cmd == 'z' || cmd == 'Z') {
                    p.close();
                    cx = sx;
                    cy = sy;
                }
                continue;
            }
            if (ch == ' ' || ch == ',') {
                i++;
                continue;
            }
            int[] pos = {i};
            float a = number(d, pos);
            i = pos[0];
            switch (cmd) {
                case 'M':
                case 'm': {
                    while (i < n && (d.charAt(i) == ' ' || d.charAt(i) == ',')) i++;
                    pos[0] = i;
                    float b = number(d, pos);
                    i = pos[0];
                    if (cmd == 'm') {
                        a += cx;
                        b += cy;
                    }
                    cx = sx = a;
                    cy = sy = b;
                    p.moveTo(cx, cy);
                    cmd = cmd == 'm' ? 'l' : 'L';
                    break;
                }
                case 'H':
                    cx = a;
                    p.lineTo(cx, cy);
                    break;
                case 'h':
                    cx += a;
                    p.lineTo(cx, cy);
                    break;
                case 'V':
                    cy = a;
                    p.lineTo(cx, cy);
                    break;
                case 'v':
                    cy += a;
                    p.lineTo(cx, cy);
                    break;
                default:
                    break;
            }
        }
        return p;
    }

    private static float number(String s, int[] pos) {
        int i = pos[0], start = i;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        pos[0] = i;
        return Float.parseFloat(s.substring(start, i));
    }
}
