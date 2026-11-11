package com.inkcontrol.features.external;

import android.content.Context;

import com.inkcontrol.core.FeatureCatalog;
import com.inkcontrol.core.FeatureDefinition;
import com.inkcontrol.platform.ApplicationServices;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「系统增强 → 待机与联网」三项的外部入口，主要给调试试参数用：
 *
 * <pre>am broadcast -a com.inkcontrol.action.IDLE -p com.inkcontrol --es policy never</pre>
 *
 * <p>三个参数都是可选的，给哪个改哪个，可以一次给全：
 * {@code policy}=always/charging/never/default、{@code net}=30/60/120/default 或 1–999 秒、
 * {@code sleep}=3/10/30/default。值取 {@code default} 就是「系统默认」——删掉对应设置项。
 *
 * <p>走 {@link ApplicationServices#changeAll}，一次落盘、一次下发，和界面同一条链路，
 * 所以界面刷新与状态统计都会跟着变，不需要重启。
 */
public final class IdleCommand {
    /** 外部入口用的 action。 */
    public static final String ACTION = "com.inkcontrol.action.IDLE";
    /** WLAN 休眠策略：always / charging / never / default。 */
    public static final String EXTRA_POLICY = "policy";
    /** 保网时长（秒）：30 / 60 / 120 / default，也可以直接给 1–999 的秒数。 */
    public static final String EXTRA_NET = "net";
    /** 浅睡待机（分钟）：3 / 10 / 30 / default。 */
    public static final String EXTRA_SLEEP = "sleep";

    private static final String[] POLICY = {"always", "charging", "never", "default"};
    private static final String[] NET = {"30", "60", "120", "default"};
    private static final String[] SLEEP = {"3", "10", "30", "default"};

    public static String apply(Context context, String policy, String net, String sleep) throws Exception {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (policy != null && !policy.trim().isEmpty())
            values.put("sys.wifi.sleep", pick(policy, POLICY, "policy（always / charging / never / default）"));
        if (net != null && !net.trim().isEmpty())
            values.put("sys.idle.net", netValue(net));
        if (sleep != null && !sleep.trim().isEmpty())
            values.put("sys.idle.sleep", pick(sleep, SLEEP, "sleep（3 / 10 / 30 / default）"));
        if (values.isEmpty()) throw new IllegalArgumentException("至少给一个参数：policy / net / sleep");

        new ApplicationServices(context.getApplicationContext()).changeAll(values);
        ExternalTask.notifyUi(context);
        StringBuilder text = new StringBuilder("待机与联网：");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            FeatureDefinition feature = FeatureCatalog.get(entry.getKey());
            if (text.charAt(text.length() - 1) != '：') text.append("，");
            int index = feature.values.indexOf(entry.getValue());
            String label = index >= 0 && index < feature.labels.size() ? feature.labels.get(index) : entry.getValue();
            if (index < 0 && "sys.idle.net".equals(entry.getKey())) label = label + " 秒";
            text.append(feature.title).append(' ').append(label);
        }
        return text.toString();
    }

    /** 保网时长既可以是预设档，也可以是「自定义」的 1–999 秒。 */
    private static String netValue(String raw) {
        String value = raw.trim();
        if (FeatureDefinition.number(value) != null) return value;
        return pick(value, NET, "net（30 / 60 / 120 / default，或 1–999 秒）");
    }

    /** 参数白名单校验：只接受界面上列出的值，免得把配置写成半开状态。 */
    private static String pick(String raw, String[] allowed, String hint) {
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (String candidate : allowed) if (candidate.equals(value)) return value;
        throw new IllegalArgumentException("不认识的取值：" + raw + "，可用 " + hint);
    }

    public static void runAsync(Context context, String policy, String net, String sleep, Runnable done) {
        final Context app = context.getApplicationContext();
        ExternalTask.run(app, () -> apply(app, policy, net, sleep), done);
    }

    private IdleCommand() { }
}
