package com.inkcontrol.test;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.widget.TextView;
import java.io.FileOutputStream;
import org.json.JSONObject;

/** 实际系统 MediaSession 的无声夹具；使用正式 lyricInfo 协议，不向产品注入测试歌词。 */
public final class MediaActivity extends Activity {
    private static MediaSession session;
    private int track=1, plays, pauses, nexts, previous;
    private boolean playing=true;
    private long position;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView label=new TextView(this);label.setText("墨控实机会话验收（无声）");setContentView(label);
        if(session!=null)session.release();
        session=new MediaSession(this,"InkControl actual media session fixture");
        session.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){playing=true;plays++;position=0;publish();}
            @Override public void onPause(){playing=false;pauses++;publish();}
            @Override public void onSkipToNext(){track++;nexts++;position=0;publish();}
            @Override public void onSkipToPrevious(){track=Math.max(1,track-1);previous++;position=0;publish();}
        },new Handler(getMainLooper()));
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setActive(true);publish();
    }
    private void publish(){try{
        String title="墨控验收曲目 "+track;
        StringBuilder lrc=new StringBuilder();
        for(int i=0;i<120;i++)lrc.append(String.format(java.util.Locale.ROOT,"[%02d:%02d.00]墨控歌词 %d · 第%d句\n",i/12,(i%12)*5,track,i+1));
        JSONObject info=new JSONObject();info.put("songName",title);info.put("lyric",lrc.toString());
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST,"无声会话验收").putLong(MediaMetadata.METADATA_KEY_DURATION,600000)
                .putString("lyricInfo",info.toString()).build());
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,position,playing?1:0).build());
        JSONObject state=new JSONObject();state.put("playing",playing);state.put("track",track);state.put("plays",plays);state.put("pauses",pauses);state.put("nexts",nexts);state.put("previous",previous);
        try(FileOutputStream output=openFileOutput("media-fixture.json",MODE_PRIVATE)){output.write(state.toString(2).getBytes("UTF-8"));}
    }catch(Exception error){throw new IllegalStateException(error);}}
}
