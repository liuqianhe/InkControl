package com.android.server.wm;
import android.content.pm.ActivityInfo;
public class ActivityRecord {
    public enum State { RESUMED, PAUSED, STOPPED, DESTROYED }
    public final Object appToken=new Object();
    public final ActivityInfo info=new ActivityInfo(); public boolean visible;
    public ActivityRecord(int uid,String pkg,String name){info.applicationInfo.uid=uid;info.packageName=pkg;info.name=name;}
    public void setState(State state,String reason){if(state==State.RESUMED)visible=true; if(state==State.DESTROYED)visible=false;}
    public void setVisible(boolean value){visible=value;}
    public void takeFromHistory(){}
}
