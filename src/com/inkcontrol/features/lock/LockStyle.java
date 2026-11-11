package com.inkcontrol.features.lock;

import com.inkcontrol.platform.RuntimeConfig;
import android.content.Context;
import android.provider.Settings;

/**
 * 沿用海信系统的锁屏样式键 Settings.Global eink_keyguard_style：
 * 0–4 为原版（壁纸 / 艺术 / 单词 / 诗词 / 大字时钟），5–9 为本模块新增。
 *
 * <p>原厂只认 0–9，所以逻辑样式 10（编辑排版-横-逆）与 11（透明书封）在系统里都写 9，
 * 具体用哪一种由配置里的 {@code lock.style} 值决定，绘制端按逻辑值分派。
 */
final class LockStyle {
    static final String KEY = "eink_keyguard_style";
    static final int EDITORIAL = 5, SWISS = 6, PIXEL = 7, GLASS = 8, EDITORIAL_LAND = 9, EDITORIAL_LAND_R = 10;
    /** 透明书封：锁屏前最后一帧做底，叠一张带透明通道的书封图。物理样式同样复用 9。 */
    static final int COVER = 11;
    static final String[] NAMES = {"壁纸", "艺术", "单词", "诗词", "大字时钟",
            "编辑排版", "瑞士网格", "1-bit 像素", "液态玻璃", "编辑排版-横", "编辑排版-横-逆", "透明书封"};

    static int read(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), KEY, 0);
    }

    static boolean custom(int style) {
        return RuntimeConfig.enabled("lock.style") && style >= EDITORIAL && style <= EDITORIAL_LAND;
    }

    /**
     * 当前配置选的是不是「透明书封」。
     * 系统里写的物理样式同样是 9（和横版共用），只能靠配置里的逻辑值区分。
     */
    static boolean cover() {
        if (!RuntimeConfig.enabled("lock.style")) return false;
        try { return COVER == Integer.parseInt(RuntimeConfig.value("lock.style").trim()); }
        catch (RuntimeException ignored) { return false; }
    }
}
