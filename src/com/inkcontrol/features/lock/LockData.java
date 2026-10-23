package com.inkcontrol.features.lock;

import com.inkcontrol.platform.RuntimeConfig;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;

/** 锁屏画面要用的数据。取不到的项保持 null，由绘制端显示“—”或整块不画。 */
final class LockData {
    Calendar now = Calendar.getInstance();
    boolean is24h = true;
    int battery = -1;
    boolean charging;
    /** 天气现象（如“多云”）、当前温度（如“18°”）、今日最高/最低温，没有天气数据时为 null */
    String weather, temperature, high, low;
    /** 农历（如“八月三十”）和节日/节气，取不到为 null */
    String lunar, festival;
    /** 锁屏通知数；锁屏关闭通知显示或暂时取不到时为 -1 */
    int notifications = -1;
    /** 倒数日目标日期（"yyyy-MM-dd"）；未设置时为 null，锁屏不显示倒数日。
     *  预留字段：后期在「锁屏自定义」中加入开关与日期设置方法后写入。 */
    String countdownDate;
    /** 通知来源名称：系统通知为“通知”，兼容微信来源时为“微信” */
    String notificationLabel = "通知";
    /** 正在播放：歌词（没有歌词时用曲名），否则是格言 */
    boolean playing;
    String text;
    /** 格言的第二段（海信格言是“中文\n英文”），只在没有曲名时显示 */
    String subText;
    String track;
    long position = -1, duration = -1;

    static LockData live(Context c, Object controller) {
        LockData d = new LockData();
        d.is24h = DateFormat.is24HourFormat(c);
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                if (scale > 0 && level >= 0) d.battery = Math.min(100, level * 100 / scale);
                int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
                d.charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
            }
        } catch (Throwable ignored) {
        }
        if (Settings.Global.getInt(c.getContentResolver(), "eink_keyguard_notification_on", 1) != 0) {
            boolean wechat = RuntimeConfig.enabled("lock.notify")
                    && "wechat".equals(RuntimeConfig.value("lock.notify"));
            if (wechat) {
                d.notifications = Math.max(0, Settings.System.getInt(c.getContentResolver(), "com_hs_wechat_mini", 0));
                d.notificationLabel = "微信";
            } else {
                d.notifications = LockNotifications.count();
            }
        }
        if (RuntimeConfig.enabled("lock.weather")) readWeather(d, controller);
        readLunar(d, controller);

        String lyric = RuntimeConfig.enabled("lock.lyric") ? Hook.activeLyric() : null;
        if (lyric != null) {
            d.playing = true;
            d.text = lyric;
        }
        readMedia(d, c);
        if (d.text == null) {
            String motto = motto(c, controller);
            if (motto != null) {
                int nl = motto.indexOf('\n');
                if (nl > 0) {
                    d.text = motto.substring(0, nl).trim();
                    String rest = motto.substring(nl + 1).replace('\n', ' ').trim();
                    if (!rest.isEmpty()) d.subText = rest;
                } else {
                    d.text = motto;
                }
            }
        }
        return d;
    }

    /**
     * 海信锁屏的天气控件在后台读天气库，这里只取它已有的结果（控件隐藏 = 原版判定无数据/过期，跟着不显示）。
     * 国行用的是 MojiWeatherView：mCityData.dataMap 里有 weatherCode（中文天气）、temperature、
     * temperatureUnit、weather（JSON 预报，第 0 天含 highTemp/lowTemp）。
     */
    private static void readWeather(LockData d, Object controller) {
        try {
            Object view = Reflect.get(Reflect.get(controller, "mFWClockArea"), "mWeather");
            if (!(view instanceof View) || ((View) view).getVisibility() != View.VISIBLE) return;
            Object map = Reflect.get(Reflect.get(view, "mCityData"), "dataMap");
            if (map instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) map;
                android.os.Bundle weather = com.inkcontrol.shared.WeatherData.from(m);
                d.weather = weather.getString("weather"); d.temperature = weather.getString("temperature");
                d.high = weather.getString("high"); d.low = weather.getString("low");
                return;
            }
            Object code = Reflect.get(view, "mWeather");
            Object temp = Reflect.get(view, "mTemperature");
            Object unit = Reflect.get(view, "mTemperatureUnit");
            if (!(code instanceof Integer) || (Integer) code < 0) return;
            Class<?> dict = view.getClass().getClassLoader().loadClass("com.eink.keyguard.weather.WeatherDict");
            Object name = dict.getMethod("getPhenomenon", int.class).invoke(null, code);
            if (name instanceof String && !((String) name).isEmpty()) d.weather = (String) name;
            if (temp instanceof Integer) {
                d.temperature = temp + (unit instanceof Integer && (Integer) unit != 0 ? "°F" : "°");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void readLunar(LockData d, Object controller) {
        try {
            ClassLoader cl = controller.getClass().getClassLoader();
            Object lunar = cl.loadClass("com.eink.keyguard.Lunar")
                    .getConstructor(Calendar.class).newInstance(Calendar.getInstance());
            String s = lunar.toString();
            int i = s.lastIndexOf('年');
            d.lunar = i >= 0 ? s.substring(i + 1) : s;
            Class<?> util = cl.loadClass("com.eink.keyguard.utils.CalendarUtil");
            Object f = util.getMethod("getChinaCalendar")
                    .invoke(util.getConstructor(Calendar.class).newInstance(Calendar.getInstance()));
            if (f instanceof String && !((String) f).trim().isEmpty()) d.festival = ((String) f).trim();
        } catch (Throwable ignored) {
        }
    }

    /** SystemUI 本身有媒体控制权限，直接读正在播放的会话 */
    private static void readMedia(LockData d, Context c) {
        try {
            MediaSessionManager manager = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            android.os.Bundle media = com.inkcontrol.shared.MediaSnapshot.read(com.inkcontrol.shared.MediaSnapshot.select(manager.getActiveSessions(null)));
            if (!media.getBoolean("playing")) return;
            String title = media.getString("title", ""), artist = media.getString("artist", "");
            d.track = title + (artist.isEmpty() ? "" : " · " + artist);
            d.position = media.getLong("position", -1);
            if (d.position >= 0 && media.getLong("positionTime") > 0) d.position += (long) ((SystemClock.elapsedRealtime() - media.getLong("positionTime")) * media.getFloat("speed"));
            d.duration = media.getLong("duration", -1); d.playing = true;
            if (d.text == null) d.text = title;
        } catch (Throwable ignored) {
        }
    }

    /** 原版格言（含用户自定义格言），和原版锁屏每次出图取一次的行为一致 */
    private static String motto(Context c, Object controller) {
        try {
            Object helper = Reflect.get(Reflect.get(controller, "mVMUtil"), "mMottoHelper");
            if (helper != null) {
                TextView tv = new TextView(c);
                Reflect.call(helper, "setMotto", new Class<?>[]{Context.class, TextView.class}, c, tv);
                String s = tv.getText().toString().trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 设置预览和调试出图用的样张数据 */
    static LockData sample() {
        LockData d = new LockData();
        d.now.set(2026, Calendar.OCTOBER, 10, 21, 47, 0);
        d.battery = 76;
        d.weather = "多云";
        d.temperature = "18°";
        d.high = "18°";
        d.low = "12°";
        d.lunar = "九月初一";
        d.countdownDate = "2026-10-31";   // 样张预览：倒数日占位（实机 live() 不设置，留待后期开关）
        d.notifications = 3;
        d.playing = true;
        d.text = "把今天折好，放进明天的口袋。";
        d.track = "曲名 · 歌手";
        d.position = 132000;
        d.duration = 220000;
        return d;
    }
}
