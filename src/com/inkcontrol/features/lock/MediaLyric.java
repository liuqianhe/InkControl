package com.inkcontrol.features.lock;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.lyric.getter.api.data.LyricData;
import de.robv.android.xposed.XposedBridge;

/**
 * 不注入音乐 App，从系统媒体会话里取歌词。
 * <p>
 * 网易云给 ColorOS 流体云 / 荣耀实况窗准备了一个出口：把整首 LRC 以 JSON
 * {"lyric": "...", "songName": "...", "artist": "..."} 放进媒体元数据的 "lyricInfo" 键，
 * 由系统按播放进度显示。开启条件只看系统属性 ro.build.version.oplus.api >= 37
 * （由 Magisk 模块 oplus_lyric 的 system.prop 设置）。这里读出 LRC，自己按进度逐句推进。
 */
final class MediaLyric {
    private static final String TAG = "EinkLyric: ";
    private static final String KEY_LYRIC_INFO = "lyricInfo";
    /** 空白行（间奏）显示的内容 */
    private static final String INTERLUDE = "♪";
    private static final Pattern TIME_TAG = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]");

    private static Handler sHandler;
    private static final Map<MediaController, Session> sSessions = new HashMap<>();
    private static Session sCurrent;
    private static String sShown;

    private static final class Line {
        final long time;
        final String text;

        Line(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    private static final class Session extends MediaController.Callback {
        final MediaController controller;
        String rawInfo;
        List<Line> lines;

        Session(MediaController controller) {
            this.controller = controller;
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            parse(metadata);
            schedule();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            schedule();
        }

        @Override
        public void onSessionDestroyed() {
            sSessions.remove(controller);
            controller.unregisterCallback(this);
            if (sCurrent == this) sCurrent = null;
            schedule();
        }

        void parse(MediaMetadata md) {
            String info = md == null ? null : md.getString(KEY_LYRIC_INFO);
            if (info == null) {
                rawInfo = null;
                lines = null;
                return;
            }
            String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (!info.equals(rawInfo)) {
                rawInfo = info;
                lines = null;
                try {
                    JSONObject o = new JSONObject(info);
                    // 换歌后新歌词还没加载完时，元数据里还是上一首的歌词，按歌名对一下
                    String song = o.optString("songName", null);
                    if (song == null || title == null || song.equals(title)) {
                        lines = parseLrc(o.optString("lyric", ""));
                        XposedBridge.log(TAG + "lyricInfo from " + controller.getPackageName()
                                + ": " + song + ", " + lines.size() + " lines");
                    }
                } catch (Exception e) {
                    XposedBridge.log(TAG + "bad lyricInfo: " + e);
                }
            }
        }
    }

    private MediaLyric() {
    }

    static void start(Context ctx, Handler handler) {
        sHandler = handler;
        try {
            final MediaSessionManager msm =
                    (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            msm.addOnActiveSessionsChangedListener(MediaLyric::bind, null, handler);
            bind(msm.getActiveSessions(null));
            XposedBridge.log(TAG + "media session lyric started");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "media session lyric failed: " + t);
        }
    }

    private static void bind(List<MediaController> controllers) {
        if (controllers == null) return;
        for (MediaController c : controllers) {
            boolean known = false;
            for (MediaController k : sSessions.keySet()) {
                if (k.getSessionToken().equals(c.getSessionToken())) {
                    known = true;
                    break;
                }
            }
            if (known) continue;
            Session s = new Session(c);
            sSessions.put(c, s);
            c.registerCallback(s, sHandler);
            s.parse(c.getMetadata());
        }
        schedule();
    }

    private static final Runnable sTick = MediaLyric::tick;

    private static void schedule() {
        sHandler.removeCallbacks(sTick);
        sHandler.post(sTick);
    }

    /** 选出正在播放且带歌词的会话，算出当前这一句，并预约下一句的时间 */
    private static void tick() {
        Session playing = null;
        for (Session s : sSessions.values()) {
            PlaybackState st = s.controller.getPlaybackState();
            if (s.lines != null && !s.lines.isEmpty() && st != null
                    && st.getState() == PlaybackState.STATE_PLAYING) {
                playing = s;
                break;
            }
        }
        sCurrent = playing;
        if (playing == null) {
            show(null);
            return;
        }
        PlaybackState st = playing.controller.getPlaybackState();
        float speed = st.getPlaybackSpeed() > 0 ? st.getPlaybackSpeed() : 1f;
        long pos = st.getPosition()
                + (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * speed);
        List<Line> lines = playing.lines;
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).time <= pos) idx = i;
            else break;
        }
        if (idx >= 0) {
            String text = lines.get(idx).text;
            show(text.isEmpty() ? INTERLUDE : text);
        }
        if (idx + 1 < lines.size()) {
            long wait = (long) ((lines.get(idx + 1).time - pos) / speed) + 20;
            sHandler.postDelayed(sTick, Math.max(50, wait));
        }
    }

    private static void show(String text) {
        if (text == null ? sShown == null : text.equals(sShown)) return;
        sShown = text;
        Hook.onLyric(text == null ? LyricData.of(LyricData.TYPE_STOP, "")
                : LyricData.of(LyricData.TYPE_UPDATE, text));
    }

    static List<Line> parseLrc(String lrc) {
        List<Line> out = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            String line = raw.trim();
            // 网易云开头的作词/作曲信息是 JSON 行：{"t":0,"c":[...]}
            if (line.isEmpty() || line.startsWith("{")) continue;
            Matcher m = TIME_TAG.matcher(line);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long ms = Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000;
                String frac = m.group(3);
                if (frac != null) {
                    if (frac.length() > 3) frac = frac.substring(0, 3);
                    ms += Long.parseLong(frac) * (frac.length() == 1 ? 100 : frac.length() == 2 ? 10 : 1);
                }
                times.add(ms);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = line.substring(end).trim();
            for (long t : times) out.add(new Line(t, text));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.time, b.time));
        return out;
    }
}
