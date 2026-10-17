package com.inkcontrol.features.desktop;

import com.inkcontrol.platform.RuntimeConfig;

import android.content.Context;
import android.database.ContentObserver;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XposedBridge;

/** SystemUI 使用已有媒体控制权限；无需给新应用开启通知读取权限。 */
final class MediaBridge {
    private static Context context;
    private static Handler handler;
    private static final List<MediaController> sessions=new ArrayList<>();
    private static MediaController active;
    private static long seen;
    private static int attempts;
    private static final MediaController.Callback callback=new MediaController.Callback(){
        @Override public void onMetadataChanged(MediaMetadata m){publish();}
        @Override public void onPlaybackStateChanged(PlaybackState s){publish();}
        @Override public void onSessionDestroyed(){publish();}
    };
    static void start(Context c){if(context!=null)return;context=c;handler=new Handler(c.getMainLooper());handler.postDelayed(MediaBridge::connect,1500);}
    private static void connect(){
        try{
            MediaSessionManager manager=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            manager.addOnActiveSessionsChangedListener(MediaBridge::bind,null,handler);bind(manager.getActiveSessions(null));
            // Provider 早期尚不可用也先保留观察器，等待 /ready 后补交数据。
            try{Bundle b=context.getContentResolver().call(ThemeProvider.URI,"read-control",null,null);seen=b.getLong("sequence");}
            catch(IllegalArgumentException notReady){seen=0;}
            context.getContentResolver().registerContentObserver(Uri.withAppendedPath(ThemeProvider.URI,"control"),false,new ContentObserver(handler){@Override public void onChange(boolean self){control();}});
            context.getContentResolver().registerContentObserver(ThemeProvider.URI,true,new ContentObserver(handler){@Override public void onChange(boolean self,Uri uri){
                String path=uri==null?"":uri.getPath();
                if("/ready".equals(path))try{Bundle current=context.getContentResolver().call(ThemeProvider.URI,"read-control",null,null);seen=current.getLong("sequence");}catch(Throwable ignored){}
                if("/ready".equals(path)||"/config".equals(path)||path==null||path.isEmpty())publish();
            }});
            XposedBridge.log("EinkThemes: media bridge ready pid="+android.os.Process.myPid());
        }catch(Throwable t){XposedBridge.log("EinkThemes: media bridge retry "+t.getClass().getSimpleName());if(++attempts<4)handler.postDelayed(MediaBridge::connect,3000);}
    }
    private static void bind(List<MediaController> list){for(MediaController c:sessions)c.unregisterCallback(callback);sessions.clear();if(list!=null)for(MediaController c:list){sessions.add(c);c.registerCallback(callback,handler);}publish();}
    private static void publish(){
        active=com.inkcontrol.shared.MediaSnapshot.select(sessions);
        Bundle b=com.inkcontrol.shared.MediaSnapshot.read(active);
        try{context.getContentResolver().call(ThemeProvider.URI,"publish-media",null,b);}catch(Throwable t){XposedBridge.log("EinkThemes: media publish unavailable "+t.getClass().getSimpleName());}
    }
    private static void control(){if(!RuntimeConfig.enabled("desktop.widget")||!"music".equals(RuntimeConfig.value("desktop.widget")))return;try{
        Bundle b=context.getContentResolver().call(ThemeProvider.URI,"read-control",null,null);long seq=b.getLong("sequence");if(seq==seen)return;seen=seq;
        if(active==null)return;MediaController.TransportControls c=active.getTransportControls();String command=b.getString("command");
        if("toggle".equals(command)){PlaybackState state=active.getPlaybackState();if(state!=null&&state.getState()==PlaybackState.STATE_PLAYING)c.pause();else c.play();}
        else if("previous".equals(command))c.skipToPrevious();else if("next".equals(command))c.skipToNext();
    }catch(Throwable t){XposedBridge.log("EinkThemes: media control unavailable "+t.getClass().getSimpleName());}}
}
