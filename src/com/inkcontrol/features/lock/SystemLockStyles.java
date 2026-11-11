package com.inkcontrol.features.lock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.inkcontrol.platform.RuntimeConfig;

import java.io.File;
import java.io.FileOutputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 海信息屏画面 = EinkKeyguardController.showPoster() 把 getCurrentStyleView() 画成 720×1440 位图送墨水屏。
 * 样式 5–8 时让 getCurrentStyleView() 返回本模块的 LockCanvas（不挂到任何父布局，手动 measure/layout），
 * 其余流程（息屏延时、每分钟走时、换歌词重绘、刷新模式）全部沿用原版。
 */
final class SystemLockStyles {
    private static final String TAG = "EinkLyric: ";
    private static final String CONTROLLER = "com.eink.keyguard.EinkKeyguardController";
    /** 调试：am broadcast -a com.inkcontrol.features.lock.RENDER [--ei style 5] [--ez sample true] */
    private static final String ACTION_RENDER = "com.inkcontrol.features.lock.RENDER";

    private static LockCanvas sView;
    /** 挂在 mHeadQuarters 里、亮屏解锁那一瞬间真正显示在屏幕上的那份 */
    private static LockCanvas sScreenView;
    private static final String[] STOCK_VIEWS = {"mFWKeyguard", "mVPKeyguard", "mWWKeyguard", "mBTKeyguard"};

    static void hook(ClassLoader cl) {
        // 原版 updateVisiblity() 只处理 0–4；样式 5–8 时四套原版视图全保持可见、叠在 mHeadQuarters 里，
        // 解锁亮屏那一下会露出艺术封面。这里在它之后把原版视图藏起来，换上本模块的画面。
        XC_MethodHook sync = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    syncScreenView(param.thisObject, null);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "screen view sync failed: " + t);
                }
            }
        };
        XposedHelpers.findAndHookMethod(CONTROLLER, cl, "updateVisiblity", sync);
        XposedHelpers.findAndHookMethod(CONTROLLER, cl, "initHeadQuarters", sync);

        XposedHelpers.findAndHookMethod(CONTROLLER, cl, "getCurrentStyleView", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object controller = param.thisObject;
                    Object style = Reflect.get(controller, "mCurrentStyle");
                    if (!(style instanceof Integer) || !LockStyle.custom((Integer) style)) return;
                    Context context = (Context) Reflect.get(controller, "mContext");
                    LockData data = LockData.live(context, controller);
                    param.setResult(prepare(context, (Integer) style, data));
                    // 送屏可能来自唤醒引擎的线程；挂在窗口上的视图只能在主线程改
                    final Object c = controller;
                    final LockData d = data;
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        syncScreenView(c, d);
                    } else {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    syncScreenView(c, d);
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + "screen view sync failed: " + t);
                                }
                            }
                        });
                    }
                } catch (Throwable t) {
                    // 出错就让原版画面顶上，不影响锁屏
                    XposedBridge.log(TAG + "custom lock style failed: " + t);
                }
            }
        });
    }

    /** 样式 5–8：藏起原版四套视图，显示 sScreenView（内容与最近一次送屏的图一致）；其它样式：藏起 sScreenView */
    private static void syncScreenView(Object controller, LockData data) {
        // updateVisiblity 也可能由设置观察器的后台线程调用，所有入口统一转到主线程。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final Object target = controller;
            final LockData snapshot = data;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        syncScreenView(target, snapshot);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "screen view sync failed: " + t);
                    }
                }
            });
            return;
        }
        Object parentObj = Reflect.get(controller, "mHeadQuarters");
        if (!(parentObj instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) parentObj;
        Object styleObj = Reflect.get(controller, "mCurrentStyle");
        int style = styleObj instanceof Integer ? (Integer) styleObj : 0;
        boolean custom = LockStyle.custom(style);
        if (!custom) {
            if (sScreenView != null) sScreenView.setVisibility(View.GONE);
            return;
        }
        Context context = (Context) Reflect.get(controller, "mContext");
        if (sScreenView == null) sScreenView = new LockCanvas(context);
        if (sScreenView.getParent() != parent) {
            if (sScreenView.getParent() instanceof ViewGroup) {
                ((ViewGroup) sScreenView.getParent()).removeView(sScreenView);
            }
            parent.addView(sScreenView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        for (String name : STOCK_VIEWS) {
            Object v = Reflect.get(controller, name);
            if (v instanceof View) ((View) v).setVisibility(View.GONE);
        }
        sScreenView.setVisibility(View.VISIBLE);
        sScreenView.bringToFront();
        sScreenView.set(style, data != null ? data : LockData.live(context, controller));
    }

    private static boolean sCacheStarted;
    private static int sCacheTicks;
    /** 锁屏是否显示中。海信的锁屏不走 KeyguardManager（解锁后它仍报 locked），改用广播维护：
     *  解锁完成 → 可截；息屏 → 停止截，免得把锁屏画面自己缓存进去。 */
    private static volatile boolean sLocked = true;

    /**
     * 解锁标记：每次解锁往 SystemUI 自己的缓存目录写一个毫秒时间戳，后端读到变化就换下一张书封。
     * 走文件而不是让 SystemUI 去跑 su：解锁时 fork 一个 root shell 太重，而且会被 Magisk 记一笔。
     */
    static final String UNLOCK_STAMP = "inkcontrol-unlock";

    /**
     * 亮屏且已解锁时每 2 秒缓存一帧，锁屏画布用的就是「熄屏前最后看到的画面」。
     */
    static void startFrameCache(final Context context, final Handler handler) {
        if (sCacheStarted) return;
        sCacheStarted = true;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                        sLocked = false;
                        // 解锁之后才轮到换书封：锁屏显示期间换图会在眼前换一张，还留残影
                        LockCover.endSession();
                        stampUnlock(c);
                    } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) sLocked = true;
                }
            }, filter, null, handler);
        } catch (Throwable error) {
            XposedBridge.log(TAG + "frame cache receiver: " + error);
        }
        final android.os.PowerManager power =
                (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
        XposedBridge.log(TAG + "frame cache started");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (power != null && power.isInteractive() && !sLocked) {
                        Bitmap frame = capture();
                        if (frame != null && !blank(frame)) {
                            LockCover.setFrame(frame);
                        }
                        if (sCacheTicks++ % 5 == 0)
                            XposedBridge.log(TAG + "frame cache " + (frame == null ? "miss" : "ok"));
                    }
                } catch (Throwable error) {
                    XposedBridge.log(TAG + "frame cache: " + error);
                }
                handler.postDelayed(this, 2000);
            }
        }, 2000);
    }

    /** 把「刚解锁」写成一个时间戳文件（十几字节，覆盖写），后端 2 秒看一次。 */
    private static void stampUnlock(Context context) {
        if (context == null) return;
        File file = new File(context.getCacheDir(), UNLOCK_STAMP);
        try (FileOutputStream out = new FileOutputStream(file)) {
            // 末尾带换行：后端用内建 read 读这一行，没有换行会被当成读失败
            out.write((System.currentTimeMillis() + "\n").getBytes("UTF-8"));
        } catch (Throwable error) {
            XposedBridge.log(TAG + "unlock stamp: " + error);
        }
    }

    /**
     * 空心帧判定：墨水屏在锁屏/无刷新时合成层可能是整屏纯白，截下来会把上一张好画面顶掉。
     * 稀疏采样 98% 以上是白就算空白，直接丢弃。
     */
    private static boolean blank(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return true;
        int white = 0, total = 0;
        for (int y = 0; y < height; y += 40) {
            for (int x = 0; x < width; x += 40) {
                int color = bitmap.getPixel(x, y);
                if (((color >> 16) & 255) > 240 && ((color >> 8) & 255) > 240 && (color & 255) > 240) white++;
                total++;
            }
        }
        return total > 0 && white * 100 >= total * 98;
    }


    /** 当前配置是不是「透明书封」：逻辑 11 与横版共用物理样式 9。 */
    private static boolean isCover() {
        try {
            return String.valueOf(LockStyle.COVER).equals(RuntimeConfig.value("lock.style"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static long sCaptureAt;

    /**
     * 截取「锁屏前最后一帧」。调用点在 getCurrentStyleView 里，此时屏幕还没换成锁屏画面，
     * 拿到就是熄屏前的内容。SystemUI 持有 READ_FRAME_BUFFER（实测 granted=true），
     * SurfaceControl.screenshot 是隐藏 API，按 Android 10 的两种签名依次试。
     * 1.5 秒节流：同一次息屏里 getCurrentStyleView 可能被调多次，不必反复截。
     */
    private static Bitmap capture() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (sCaptureAt != 0 && now - sCaptureAt < 1500) return null;
        sCaptureAt = now;
        // 各版本签名不同（Android 10 是 (Rect,int,int,boolean,int) 或 (Rect,int,int,int)），
        // 按参数类型枚举一遍，比写死方法名稳。
        java.lang.reflect.Method fallback = null;
        for (java.lang.reflect.Method method : android.view.SurfaceControl.class.getDeclaredMethods()) {
            if (!method.getName().equals("screenshot")
                    || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] types = method.getParameterTypes();
            try {
                if (types.length == 5 && types[0] == android.graphics.Rect.class
                        && types[1] == int.class && types[2] == int.class
                        && types[3] == boolean.class && types[4] == int.class) {
                    method.setAccessible(true);
                    Object result = method.invoke(null, new android.graphics.Rect(0, 0, 720, 1440), 720, 1440, false, 0);
                    if (result instanceof Bitmap) return soften((Bitmap) result);
                } else if (types.length == 4 && types[0] == android.graphics.Rect.class
                        && types[1] == int.class && types[2] == int.class && types[3] == int.class) {
                    method.setAccessible(true);
                    Object result = method.invoke(null, new android.graphics.Rect(0, 0, 720, 1440), 720, 1440, 0);
                    if (result instanceof Bitmap) return soften((Bitmap) result);
                } else if (types.length == 2 && types[0] == int.class && types[1] == int.class) {
                    fallback = method;
                }
            } catch (Throwable error) {
                XposedBridge.log(TAG + "screenshot " + types.length + " args failed: " + error);
            }
        }
        if (fallback != null) {
            try {
                fallback.setAccessible(true);
                Object result = fallback.invoke(null, 720, 1440);
                if (result instanceof Bitmap) return soften((Bitmap) result);
            } catch (Throwable error) {
                XposedBridge.log(TAG + "capture frame failed: " + error);
            }
        }
        return null;
    }

    /**
     * SurfaceControl.screenshot 返回的是 Config#HARDWARE 位图：不能 getPixel（空白判定会抛），
     * 也不能画到软件 Canvas 上（画不出背景）。先转成 ARGB_8888 的软件位图。
     */
    private static Bitmap soften(Bitmap bitmap) {
        if (bitmap == null) return null;
        if (bitmap.getConfig() != Bitmap.Config.HARDWARE) return bitmap;
        try {
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            bitmap.recycle();
            return copy;
        } catch (Throwable error) {
            XposedBridge.log(TAG + "soften failed: " + error);
            return null;
        }
    }

    private static View prepare(Context context, int style, LockData data) {
        // 亮屏缓帧的定时器也在这里兜底启动：锁屏画面首次渲染时一定有 context。
        startFrameCache(context, new Handler(Looper.getMainLooper()));
        // 锁屏瞬间 SurfaceFlinger 已经把这一屏清掉了，此刻截到的多半是空白；
        // 所以平时亮屏就缓存最近一帧（见 startFrameCache），这里只兜底。
        if (isCover()) {
            // 本次锁屏要画的那张在这里钉住：之后每分钟走时重绘都画同一张，
            // 不会因为文件被换成下一张就在锁屏上「换图」。
            LockCover.beginSession();
            Bitmap frame = LockCover.frame();
            if (frame == null) {
                frame = capture();
                if (frame != null && !blank(frame)) LockCover.setFrame(frame);
            }
            if (frame == null)
                XposedBridge.log(TAG + "cover frame unavailable, lock screen falls back to paper");
        }
        if (sView == null) sView = new LockCanvas(context);
        sView.set(style, data);
        int w = 720, h = 1440;
        sView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        sView.layout(0, 0, w, h);
        return sView;
    }

    static void capture(Context context, Bitmap bitmap, int mode) {
        if (context == null || bitmap == null) return;
        File f = new File(context.getCacheDir(), "einklock-posted.png");
        try (FileOutputStream out = new FileOutputStream(f)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            XposedBridge.log(TAG + "posted " + bitmap.getWidth() + "x" + bitmap.getHeight() + " mode " + mode);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "capture failed: " + t);
        }
    }

    /** 在 SystemUI 进程里注册调试出图广播，图存到 SystemUI 的缓存目录 */
    static void registerDebug(final Context context, final Object controller, Handler handler) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent.getBooleanExtra("dumpViews", false)) {
                    Object hq = Reflect.get(controller, "mHeadQuarters");
                    StringBuilder sb = new StringBuilder("headquarters " + (hq == null ? null
                            : ((View) hq).getVisibility() + " " + ((View) hq).getWidth() + "x" + ((View) hq).getHeight()));
                    if (hq instanceof ViewGroup) {
                        ViewGroup g = (ViewGroup) hq;
                        for (int i = 0; i < g.getChildCount(); i++) {
                            View v = g.getChildAt(i);
                            sb.append(" | ").append(v.getClass().getSimpleName()).append(" vis=")
                                    .append(v.getVisibility()).append(' ').append(v.getWidth()).append('x').append(v.getHeight());
                        }
                    }
                    XposedBridge.log(TAG + sb);
                    return;
                }
                if (intent.getBooleanExtra("dumpWeather", false)) {
                    Object view = Reflect.get(Reflect.get(controller, "mFWClockArea"), "mWeather");
                    XposedBridge.log(TAG + "weather view " + (view == null ? null : view.getClass())
                            + " data " + Reflect.get(Reflect.get(view, "mCityData"), "dataMap"));
                    return;
                }
                int style = intent.getIntExtra("style", -1);
                boolean sample = intent.getBooleanExtra("sample", false);
                int[] styles = style >= 0 ? new int[]{style}
                        : new int[]{LockStyle.EDITORIAL, LockStyle.SWISS, LockStyle.PIXEL, LockStyle.GLASS, LockStyle.EDITORIAL_LAND, LockStyle.EDITORIAL_LAND_R};
                for (int s : styles) {
                    try {
                        LockData data = sample ? LockData.sample(RuntimeConfig.snapshot()) : LockData.live(context, controller);
                        Bitmap b = LockCanvas.render(context, s, data, 720, 1440);
                        File f = new File(context.getCacheDir(), "einklock-" + s + ".png");
                        try (FileOutputStream out = new FileOutputStream(f)) {
                            b.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                        f.setReadable(true, false);
                        XposedBridge.log(TAG + "rendered " + f);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "render " + s + " failed: " + t);
                    }
                }
            }
        }, new IntentFilter(ACTION_RENDER), null, handler);
    }
}
