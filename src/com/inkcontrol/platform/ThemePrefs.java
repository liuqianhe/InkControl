package com.inkcontrol.platform;

import android.content.Context;
import android.content.SharedPreferences;
import com.inkcontrol.core.FeatureCatalog;
import com.inkcontrol.core.FeatureDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * 锁屏主题的界面偏好：下拉里的顺序、隐藏哪些主题、快捷面板轮换在哪几个主题之间切。
 *
 * <p>纯界面偏好，不进功能配置、不参与后端校验，所以和功能列表排序一样放在设备保护存储的
 * SharedPreferences 里。存的是主题取值（如 5、11）以逗号连接的序列，而不是名称，
 * 改名字不会让旧偏好失效；认不出的取值（以后删掉的主题）读的时候自动丢掉。
 */
public final class ThemePrefs {
    private static final String FILE = "lock-theme-prefs";
    private static final String CYCLE = "cycle";
    private static final String ORDER = "order";
    private static final String HIDDEN = "hidden";

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 功能目录里的锁屏主题取值（不含占位符），顺序即出厂默认顺序。 */
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

    /** 用户勾选的轮换范围（未过滤隐藏项），供设置页显示勾选状态；没存过＝全部。 */
    public static List<String> cycleSaved(Context context) { return read(context, CYCLE, styles()); }

    /**
     * 实际参与轮换的主题：按「主题排序」里的顺序，去掉隐藏项。
     * 隐藏＝不想要，即便还留在勾选范围里也不该被轮换到。
     */
    public static List<String> cycle(Context context) {
        List<String> picked = cycleSaved(context), hidden = hidden(context), result = new ArrayList<>();
        for (String value : order(context)) if (picked.contains(value) && !hidden.contains(value)) result.add(value);
        return result;
    }

    /** 保存参与轮换的主题；调用方保证至少留一个。 */
    public static void saveCycle(Context context, List<String> values) { write(context, CYCLE, values, styles()); }

    /** 主题下拉里的顺序；没存过＝出厂默认顺序。 */
    public static List<String> order(Context context) { return read(context, ORDER, styles()); }
    public static void saveOrder(Context context, List<String> values) { write(context, ORDER, values, styles()); }
    public static void resetOrder(Context context) { prefs(context).edit().remove(ORDER).commit(); }

    /** 从下拉里隐藏的主题取值。 */
    public static List<String> hidden(Context context) { return read(context, HIDDEN, new ArrayList<String>()); }
    public static void saveHidden(Context context, List<String> values) { write(context, HIDDEN, values, styles()); }

    /** 下拉里真正显示的主题：按排序顺序，去掉隐藏项。 */
    public static List<String> visible(Context context) {
        List<String> hidden = hidden(context), result = new ArrayList<>();
        for (String value : order(context)) if (!hidden.contains(value)) result.add(value);
        return result;
    }

    /** 读一段逗号序列：按存下来的顺序保留，只丢掉目录里已经没有的取值。 */
    private static List<String> read(Context context, String key, List<String> fallback) {
        String saved = prefs(context).getString(key, null);
        if (saved == null) return new ArrayList<>(fallback);
        List<String> allowed = styles(), result = new ArrayList<>();
        for (String value : saved.split(",", -1)) if (allowed.contains(value) && !result.contains(value)) result.add(value);
        return result;
    }

    /** 按调用方给的顺序去重保存，只保留目录里有的取值（排序的顺序不能被重排回出厂顺序）。 */
    private static void write(Context context, String key, List<String> values, List<String> allowed) {
        List<String> clean = new ArrayList<>();
        for (String value : values) if (allowed.contains(value) && !clean.contains(value)) clean.add(value);
        prefs(context).edit().putString(key, String.join(",", clean)).commit();
    }

    /** 循环取下一个：当前值不在集合里就从第一个开始；集合为空时保持原样。 */
    public static String next(String current, List<String> values) {
        if (values.isEmpty()) return current;
        int at = values.indexOf(current);
        return values.get((at + 1) % values.size());
    }

    private ThemePrefs() { }
}
