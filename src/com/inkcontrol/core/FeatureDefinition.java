package com.inkcontrol.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 功能元数据是界面、业务校验和平台执行的共同契约。 */
public final class FeatureDefinition {
    public enum ApplyMode { HOT, REBOOT }
    public final String id, group, title, description, role, dependency;
    public final ApplyMode mode;
    public final boolean experimental;
    public final List<String> values, labels;

    public FeatureDefinition(String id, String group, String title, String description,
            String role, String dependency, ApplyMode mode, boolean experimental,
            String[] values, String[] labels) {
        this.id = id;
        this.group = group;
        this.title = title;
        this.description = description;
        this.role = role;
        this.dependency = dependency;
        this.mode = mode;
        this.experimental = experimental;
        this.values = Collections.unmodifiableList(Arrays.asList(values));
        this.labels = Collections.unmodifiableList(Arrays.asList(labels));
        if (values.length == 0 || values.length != labels.length) throw new IllegalArgumentException(id);
    }

    public String defaultValue() { return values.get(0); }
    /** 是否接受按温区自定义的停留曲线（值形如 curve:150,175,...，共 TEMPERATURE_BANDS 段）。 */
    public boolean acceptsCurve() { return values.contains(CURVE_TOKEN); }
    public void validate(String value) {
        if (CURVE_TOKEN.equals(value) || !values.contains(value) && !(acceptsCurve() && curve(value) != null))
            throw new IllegalArgumentException("不支持的选项：" + title);
    }

    /** 目录里的占位值；本身不是合法配置，只表示允许 curve: 取值。 */
    public static final String CURVE_TOKEN = "@curve";
    /** 温区上界（不含），最后一段为 24°C 及以上；与屏幕波形表的 A2 温区一致。 */
    public static final int[] TEMPERATURE_BOUNDS = {3, 6, 9, 12, 15, 18, 21, 24};
    public static final int TEMPERATURE_BANDS = TEMPERATURE_BOUNDS.length + 1, CURVE_MAX_MS = 2000;

    /** 解析 curve: 取值；格式不对返回 null。只接受数字与逗号，不能夹带其他文本。 */
    public static int[] curve(String value) {
        if (value == null || !value.startsWith("curve:")) return null;
        String[] parts = value.substring(6).split(",", -1);
        if (parts.length != TEMPERATURE_BANDS) return null;
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("[0-9]{1,4}")) return null;
            result[i] = Integer.parseInt(parts[i]);
            if (result[i] > CURVE_MAX_MS) return null;
        }
        return result;
    }
    public static String curveValue(int[] ms) {
        if (ms.length != TEMPERATURE_BANDS) throw new IllegalArgumentException("温区数量不符");
        StringBuilder out = new StringBuilder("curve:");
        for (int i = 0; i < ms.length; i++) {
            if (ms[i] < 0 || ms[i] > CURVE_MAX_MS) throw new IllegalArgumentException("停留时长超出范围");
            out.append(i == 0 ? "" : ",").append(ms[i]);
        }
        return out.toString();
    }
    public static int band(double tempC) {
        for (int i = 0; i < TEMPERATURE_BOUNDS.length; i++) if (tempC < TEMPERATURE_BOUNDS[i]) return i;
        return TEMPERATURE_BOUNDS.length;
    }
}
