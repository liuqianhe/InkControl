package com.inkcontrol.shared;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import java.util.List;

/** 锁屏和桌面的共同媒体数据模型，绘制端不持有 MediaController。 */
public final class MediaSnapshot {
    public static MediaController select(List<MediaController> sessions) {
        MediaController paused = null;
        if (sessions != null) for (MediaController controller : sessions) {
            MediaMetadata metadata = controller.getMetadata(); PlaybackState state = controller.getPlaybackState();
            if (metadata == null || state == null || metadata.getString(MediaMetadata.METADATA_KEY_TITLE) == null) continue;
            if (state.getState() == PlaybackState.STATE_PLAYING) return controller;
            if (state.getState() == PlaybackState.STATE_PAUSED && paused == null) paused = controller;
        }
        return paused;
    }
    public static Bundle read(MediaController controller) {
        Bundle value = new Bundle(); value.putBoolean("mediaAvailable", controller != null);
        if (controller == null) return value;
        MediaMetadata metadata = controller.getMetadata(); PlaybackState state = controller.getPlaybackState();
        if (metadata == null || state == null) { value.putBoolean("mediaAvailable", false); return value; }
        value.putString("title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
        value.putString("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
        value.putString("mediaPackage", controller.getPackageName());
        value.putBoolean("playing", state.getState() == PlaybackState.STATE_PLAYING);
        value.putLong("position", state.getPosition()); value.putLong("positionTime", state.getLastPositionUpdateTime());
        value.putLong("duration", metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)); value.putFloat("speed", state.getPlaybackSpeed());
        return value;
    }
    private MediaSnapshot() {}
}
