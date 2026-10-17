package com.inkcontrol.features.motion;

import android.os.SystemClock;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** 在真实显示切换/首次bitmap提交边界发请求，不干预认证和原厂送图。 */
final class LockTransitions {
    private static final Object GUARD = new Object();
    private static Object sArmedService;
    private static long sArmedUntil;
    static volatile boolean installed;

    static void install(ClassLoader cl) {
        try {
            Class<?> service = XposedHelpers.findClass("com.android.server.EpdManagerService", cl);
            int displayHooks = XposedBridge.hookAllMethods(service, "setDisplayTypeLocked", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length != 1 || !(p.args[0] instanceof Integer)) return;
                        int type = XposedHelpers.getIntField(p.thisObject, "mDisplayType");
                        p.setObjectExtra("einkPreviousDisplayType", type);
                    } catch (Throwable t) { Log.e(Hook.TAG, "lock state read failed", t); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object previous = p.getObjectExtra("einkPreviousDisplayType");
                        if (!(previous instanceof Integer) || p.hasThrowable()) return;
                        int type = XposedHelpers.getIntField(p.thisObject, "mDisplayType");
                        synchronized (GUARD) {
                            if ((Integer) previous >= 0 && type == -1) {
                                HwcBridge.cancelPending();
                                sArmedService = p.thisObject;
                                sArmedUntil = SystemClock.uptimeMillis() + 5000;
                            } else if ((Integer) previous == -1 && type >= 0) {
                                sArmedService = null;
                                HwcBridge.cancelPending();
                                request(false);
                            }
                        }
                    } catch (Throwable t) { Log.e(Hook.TAG, "lock display transition failed", t); }
                }
            }).size();
            int bitmapHooks = XposedBridge.hookAllMethods(service, "commitFbBufferLocked", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length != 6 || p.args[0] == null || !Integer.valueOf(0).equals(p.args[1])
                                || !Integer.valueOf(1).equals(p.args[2])) return;
                        synchronized (GUARD) {
                            if (sArmedService != p.thisObject) return;
                            sArmedService = null;
                            if (SystemClock.uptimeMillis() > sArmedUntil
                                    || XposedHelpers.getBooleanField(p.thisObject, "mIsScreenOn")) return;
                            request(true);
                        }
                    } catch (Throwable t) { Log.e(Hook.TAG, "lock bitmap transition failed", t); }
                }
            }).size();
            installed = displayHooks > 0 && bitmapHooks > 0;
            XposedBridge.log("EinkMotion: lock transitions installed=" + installed + " pid=" + android.os.Process.myPid());
            Log.i(Hook.TAG, "lock transitions installed pid=" + android.os.Process.myPid());
        } catch (Throwable t) { installed=false; XposedBridge.log("EinkMotion: lock transitions unavailable " + t); Log.e(Hook.TAG, "lock transitions unavailable", t); }
    }

    private static void request(boolean entering) {
        Config c = Hook.sCfg;
        String style = c.styleFor(entering ? Config.LOCK : Config.UNLOCK);
        if (Config.INSTANT.equals(style)) return;
        String dir = "auto".equals(c.wipeDir) ? (entering ? "ttb" : "btt") : c.wipeDir;
        boolean sent = HwcBridge.requestLock(style, c.spread, dir, entering);
        Log.i(Hook.TAG, "lock transition entering=" + entering + " style=" + style + " sent=" + sent);
    }
}
