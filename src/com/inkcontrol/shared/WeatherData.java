package com.inkcontrol.shared;

import android.os.Bundle;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** 原厂 Moji 天气数据的共同解析；数据定位与页面绘制分别留在功能中。 */
public final class WeatherData {
    public static Bundle from(Map<?, ?> data) {
        Bundle value = new Bundle();
        String unit = "1".equals(String.valueOf(data.get("temperatureUnit"))) ? "°F" : "°";
        Object temperature = data.get("temperature"), condition = data.get("weatherCode");
        if (temperature != null && !temperature.toString().isEmpty()) value.putString("temperature", temperature + unit);
        if (condition != null && !condition.toString().matches("[0-9]+")) value.putString("weather", condition.toString());
        try {
            Object forecast = data.get("weather");
            if (forecast != null) {
                JSONObject today = new JSONArray(forecast.toString()).optJSONObject(0);
                if (today != null) for (String key : new String[]{"high", "low"}) {
                    String text = today.optString(key + "Temp"); if (!text.isEmpty()) value.putString(key, text + unit);
                }
            }
            Object stamp = data.get("updateTime");
            if (stamp != null) { long time = Long.parseLong(stamp.toString()); value.putBoolean("weatherStale", time < 0 || Math.abs(System.currentTimeMillis() - time) >= 7200000); }
        } catch (Exception ignored) { }
        return value;
    }
    private WeatherData() {}
}
