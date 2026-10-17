package com.inkcontrol.features.desktop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.LruCache;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.Calendar;

final class Assets {
    private static Context module;
    private static JSONObject index;
    private static final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(12*1024*1024){@Override protected int sizeOf(String k,Bitmap v){return v.getByteCount();}};
    static void init(Context c)throws Exception{
        if(module!=null)return;module=c.createPackageContext("com.inkcontrol",0);
        try(InputStream in=module.getAssets().open("icons.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);index=new JSONObject(out.toString("UTF-8"));}
    }
    static Bitmap bitmap(String path){Bitmap b=cache.get(path);if(b!=null)return b;try(InputStream in=module.getAssets().open(path)){b=BitmapFactory.decodeStream(in);if(b!=null){b.setDensity(Bitmap.DENSITY_NONE);cache.put(path,b);}return b;}catch(Exception e){return null;}}
    static Bitmap icon(String title,String theme){
        if(index==null||!index.has(title))return null;
        try{
            Bitmap b=bitmap(index.getJSONObject(title).getString(theme));
            if(!"日历".equals(title)||b==null)return b;
            int day=Calendar.getInstance().get(Calendar.DAY_OF_MONTH);String key="calendar-"+theme+"-"+day;Bitmap result=cache.get(key);if(result!=null)return result;
            result=b.copy(Bitmap.Config.ARGB_8888,true);Canvas c=new Canvas(result);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor("ink".equals(theme)?Color.WHITE:Color.rgb(17,17,17));p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setTextSize(26);p.setTextAlign(Paint.Align.CENTER);
            c.drawText(String.valueOf(day),56,76,p);cache.put(key,result);return result;
        }catch(Exception e){return null;}
    }
    static Typeface face(String theme,boolean numeric){return Typeface.create("paper".equals(theme)?"serif":"ink".equals(theme)?"sans-serif-condensed":numeric?"sans-serif-light":"sans-serif",Typeface.NORMAL);}
}
