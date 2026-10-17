package com.inkcontrol.features.lock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

/** 构建期 16 级灰阶误差扩散材质：每个目标进程只解码一次，不在歌词更新时运行整屏滤镜。 */
final class GlassTexture {
    private static Bitmap sBitmap;

    static synchronized Bitmap bitmap() {
        if (sBitmap != null) return sBitmap;
        StringBuilder encoded = new StringBuilder();
        for (String part : GlassTextureData.PNG_BASE64) encoded.append(part);
        byte[] png = Base64.decode(encoded.toString(), Base64.DEFAULT);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeByteArray(png, 0, png.length, options);
        if (decoded == null || decoded.getWidth() != 720 || decoded.getHeight() != 1440) {
            if (decoded != null) decoded.recycle();
            throw new IllegalStateException("Invalid glass texture");
        }
        sBitmap = decoded;
        return decoded;
    }
}
