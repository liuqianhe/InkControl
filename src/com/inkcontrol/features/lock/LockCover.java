package com.inkcontrol.features.lock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;

/**
 * 「透明书封」要用的两张位图：锁屏前最后一帧（内存里，SystemUI 侧保证是最新的）与书封图片（磁盘）。
 *
 * <p>书封图放在 {@code /data/inkcontrol/lockcover.png}——后端为它单独建的一个目录：目录 0771、
 * 文件 0644，标签沿用 {@code fonts_app_data_file}（SystemUI 是 platform_app，只对这个类型有读权限）。
 * 换图只换文件内容，mtime 变了就重新解码，不用重启也不用改配置。
 *
 * <p>一次锁屏期间书封是「钉住」的：熄屏后原厂每分钟会重绘一次海报，若那时文件已经换成下一张，
 * 画面就会在眼前换一张，还会留下上一张的残影。所以进锁屏第一次取图时把当时的位图固定下来，
 * 解锁（ACTION_USER_PRESENT）才放开，下一次熄屏才用得上新图。
 */
final class LockCover {
    private static final String TAG = "EinkLyric: ";
    static final String PATH = "/data/inkcontrol/lockcover.png";
    /** 原图 824×1648，按 360×720 逻辑尺寸画；再大也只是多占内存，先降采样。 */
    private static final int MAX_WIDTH = 1080;

    private static Bitmap sCover;
    private static long sCoverStamp = -1;
    /** 本次锁屏期间固定的那张，不受文件变化影响 */
    private static Bitmap sPinned;
    private static Bitmap sFrame;

    /** 书封图；还没放过图或解码失败时返回 null，绘制端只画背景帧。 */
    static synchronized Bitmap cover() {
        if (sPinned != null && !sPinned.isRecycled()) return sPinned;
        return load();
    }

    /**
     * 进锁屏：把此刻文件里那张钉住，本次锁屏期间不再看磁盘。
     * 重复调用无副作用（已经钉住就什么都不做）。
     */
    static synchronized void beginSession() {
        if (sPinned != null && !sPinned.isRecycled()) return;
        sPinned = load();
    }

    /** 解锁：放开钉住的那张，下次熄屏按最新文件重新取。 */
    static synchronized void endSession() {
        sPinned = null;
    }

    /** 按 mtime 判断要不要重新解码；同一个文件反复取只解码一次。 */
    private static Bitmap load() {
        File file = new File(PATH);
        long stamp = file.lastModified();
        if (stamp == 0) {
            sCover = null;
            sCoverStamp = -1;
            return null;
        }
        if (sCover != null && sCoverStamp == stamp && !sCover.isRecycled()) return sCover;
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(PATH, probe);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (probe.outWidth > 0 && probe.outWidth / (options.inSampleSize * 2) >= MAX_WIDTH)
                options.inSampleSize *= 2;
            sCover = BitmapFactory.decodeFile(PATH, options);
            sCoverStamp = stamp;
            return sCover;
        } catch (Throwable error) {
            Log.w(TAG, "read lock cover failed: " + error);
            sCover = null;
            sCoverStamp = -1;
            return null;
        }
    }

    /** 锁屏前最后一帧，由 SystemUI 侧在画面切换之前截下来；拿不到就是 null，绘制端留白。 */
    static synchronized void setFrame(Bitmap bitmap) {
        if (sFrame != null && sFrame != bitmap) sFrame.recycle();
        sFrame = bitmap;
    }

    static synchronized Bitmap frame() {
        return sFrame != null && !sFrame.isRecycled() ? sFrame : null;
    }

    private LockCover() { }
}
