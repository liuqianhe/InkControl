package com.inkcontrol.platform;

import android.content.Context;
import android.content.SharedPreferences;
import com.inkcontrol.core.FeatureCatalog;
import com.inkcontrol.core.FeatureDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * 锁屏主题的界面偏好：快捷面板「轮换主题」在哪几个主题之间切换。
 *
 * <p>纯界面偏好，不进功能配置、不参与后端校验，所以和功能列表排序一样放在设备保护存储的
 * SharedPreferences 里。没配置过时按功能目录里的全部主题处理；存的是主题取值（如 5、11）
 * 以逗号连接的序列，而不是名称，改名字不会让旧偏好失效。
 */
public final class ThemePrefs {
    private static final String FILE = "lock-theme-prefs";
    private static final String CYCLE = "cycle";

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 功能目录里的锁屏主题取值（不含占位符），顺序即目录顺序。 */
    public static List<String> styles() {
        List<String> result = new ArrayList<>();
        for (String value : FeatureCatalog.get("lock.style").values) if (!value.startsWith("@")) result.add(value);
        return result;
    }

    /** 主题取值对应的名称；认不出的值原样返回。 */
    public static String name(String value) {
        FeatureDefinition feature = FeatureCatalog.get("lock.style");
        int at = feature.values.indexOf(value);
        return at < 0 ? value : feature.labels.get(at);
    }

    /** 参与轮换的主题；没配置过＝全部主题，按目录顺序。 */
    public static List<String> cycle(Context context) {
        String saved = prefs(context).getString(CYCLE, null);
        if (saved == null) return styles();
        List<String> all = styles(), result = new ArrayList<>();
        for (String value : saved.split(",", -1)) if (all.contains(value) && !result.contains(value)) result.add(value);
        return result;
    }

    /** 保存参与轮换的主题，按目录顺序去重；调用方保证至少留一个。 */
    public static void saveCycle(Context context, List<String> values) {
        List<String> clean = new ArrayList<>();
        for (String value : styles()) if (values.contains(value) && !clean.contains(value)) clean.add(value);
        prefs(context).edit().putString(CYCLE, String.join(",", clean)).commit();
    }

    /** 循环取下一个：当前值不在集合里就从第一个开始；集合为空时保持原样。 */
    public static String next(String current, List<String> values) {
        if (values.isEmpty()) return current;
        int at = values.indexOf(current);
        return values.get((at + 1) % values.size());
    }

    private ThemePrefs() { }
}
