package com.android.server;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import java.util.*;
import de.robv.android.xposed.XposedHelpers;
public class EpdManagerService {
    public Object mLock=new Object(); public Handler mHandler=new Handler();
    public int mEpdMode=6, calls=0, stockNotifyCalls=0, writes=0;
    public String mTopPackage="",mTopActivity="";
    public List<Object> mGray2NoFlickerReason=new ArrayList<>();
    public Set<String> mGray2NoFlickerActivity=new HashSet<>();
    final Context context;
    public interface Stub {}
    public final Stub mService=new Stub(){
        public void begigResumeActivity(String pkg,String name,boolean floating){
            mTopPackage=pkg;mTopActivity=name;mGray2NoFlickerReason.clear();
            if(mGray2NoFlickerActivity.contains(name))mGray2NoFlickerReason.add("VIDEO_CAMERA_ON");
            XposedHelpers.callMethod(EpdManagerService.this,"dealWithGray2NoFlicker");
        }
        public void notifyResumedPackage(String pkg){mTopPackage=pkg;}
        public int getEpdMode(){return mEpdMode & 255;}
        public void setEpdDisplayMode(int mode){long t=Binder.clearCallingIdentity();calls++;mEpdMode=mode;Binder.restoreCallingIdentity(t);}
        public void updateEpdMode(){calls++;}
        public void addAppMode(String pkg,int mode){long t=Binder.clearCallingIdentity();calls++;Binder.restoreCallingIdentity(t);}
        public void updateShutdownWallPaper(Object bitmap){long t=Binder.clearCallingIdentity();calls++;Binder.restoreCallingIdentity(t);}
        public void addBitmapToPresentationDisplayWithString(Object bitmap,int priority,int mode,int time,boolean bar,String extra){calls++;}
        public void notifyVideoOn(boolean on,int uid){mHandler.post(()->XposedHelpers.callMethod(EpdManagerService.this,"notifyVideoOrCameraOn",on?1:0,"VIDEO_ON",uid));}
        public void notifyCameraOn(boolean on,int uid){mHandler.post(()->XposedHelpers.callMethod(EpdManagerService.this,"notifyVideoOrCameraOn",on?1:0,"CAMERA_ON",uid));}
        public void onTransact(){}
    };
    public EpdManagerService(Context context){this.context=context;}
    public Context getContext(){return context;}
    public void dealWithGray2NoFlicker(){mEpdMode=mGray2NoFlickerReason.isEmpty()?(mEpdMode&~1024):(mEpdMode|1024);writes++;}
    public void notifyVideoOrCameraOn(int value,String reason,int uid){stockNotifyCalls++;mGray2NoFlickerReason.add(reason);mGray2NoFlickerActivity.add(mTopActivity);}
    public void handleWriteConfigFile(){}
    public void access$100(){}
    public class EpdHandler { public void handleMessage(Object message){} }
}
