package com.inkcontrol.features.lock;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * system_server：EpdManagerService 送屏前会用 BottomBarController.getBottomBar() 在图底部
 * （y 1365–1419）叠一条电量图标 + “上滑解锁”。本模块的锁屏样式自带这两项，叠上去会重叠，
 * 所以样式 5–8 的锁屏图（priority 0、status 1）跳过这一层；原版样式、关机图、低电量提示照旧。
 */
final class BottomBarHook {
    private static final String TAG = "EinkLyric: ";
    private static final String CONTROLLER = "com.android.server.BottomBarController";
    private static final int STATUS_LOCK = 1;

    /** -1 = 还没读到；在主线程用 ContentObserver 维护，绘制路径里只读这个字段，不在持锁时访问设置库 */
    private static volatile int sStyle = -1;
    private static boolean sObserving;

    static void hook(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CONTROLLER, cl, "getBottomBar",
                Bitmap.class, int.class, boolean.class, int.class, boolean.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            observe(param.thisObject);
                            int status = (Integer) param.args[1];
                            boolean screenOn = (Boolean) param.args[2];
                            int prop = (Integer) param.args[3];
                            if (status == STATUS_LOCK && !screenOn && prop == 0
                                    && LockStyle.custom(sStyle)) {
                                param.setResult(param.args[0]);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "bottom bar hook: " + t);
                        }
                    }
                });
        // 开机创建控制器时就开始监听，第一次锁屏前就读到样式
        XposedHelpers.findAndHookConstructor(CONTROLLER, cl,
                Context.class, "com.android.server.EpdManagerService", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            observe(param.thisObject);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "bottom bar init: " + t);
                        }
                    }
                });
        XposedBridge.log(TAG + "hooked BottomBarController");
    }

    private static synchronized void observe(Object controller) {
        if (sObserving) return;
        final Context context = (Context) Reflect.get(controller, "mContext");
        if (context == null) return;
        sObserving = true;
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final Runnable read = new Runnable() {
                        @Override
                        public void run() {
                            sStyle = Settings.Global.getInt(context.getContentResolver(), LockStyle.KEY, 0);
                        }
                    };
                    context.getContentResolver().registerContentObserver(
                            Settings.Global.getUriFor(LockStyle.KEY), false, new ContentObserver(main) {
                                @Override
                                public void onChange(boolean selfChange) {
                                    read.run();
                                }
                            });
                    read.run();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "bottom bar observe: " + t);
                }
            }
        });
    }
}
