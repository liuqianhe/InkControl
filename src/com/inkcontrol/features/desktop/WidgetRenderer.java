package com.inkcontrol.features.desktop;

import com.inkcontrol.shared.WeatherIcon;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.format.DateFormat;
import java.util.Calendar;
import java.util.Locale;

final class WidgetRenderer {
    private static final int DARK=Color.rgb(17,17,17);
    static void text(Canvas c,String value,float x,float y,float size,String theme,boolean numeric,int color,Paint.Align align,float maxWidth){
        if(value==null)value="";Paint p=new Paint(3);p.setTypeface(Assets.face(theme,numeric));p.setColor(color);p.setTextSize(size);p.setTextAlign(align);
        while(p.measureText(value)>maxWidth&&p.getTextSize()>20)p.setTextSize(p.getTextSize()-1);
        if(p.measureText(value)>maxWidth){while(value.length()>1&&p.measureText(value+"…")>maxWidth)value=value.substring(0,value.length()-1);value+="…";}
        c.drawText(value,x,y-p.ascent(),p);
    }
    private static void t(Canvas c,String value,float x,float y,float size,String theme,boolean numeric,int fg){text(c,value,x,y,size,theme,numeric,fg,Paint.Align.LEFT,595-x);}
    private static void center(Canvas c,String value,float x,float y,float size,String theme,boolean numeric,int fg){text(c,value,x,y,size,theme,numeric,fg,Paint.Align.CENTER,590);}
    private static void right(Canvas c,String value,float x,float y,float size,String theme,boolean numeric,int fg){text(c,value,x,y,size,theme,numeric,fg,Paint.Align.RIGHT,x-32);}
    private static void rule(Canvas c,float x,float y,float end,int color,float width){Paint p=new Paint(3);p.setColor(color);p.setStrokeWidth(width);c.drawLine(x,y,end,y,p);}
    private static void play(Canvas c,float x,float y,boolean playing,int color){Paint p=new Paint(3);p.setColor(color);if(playing){c.drawRect(x,y,x+8,y+35,p);c.drawRect(x+17,y,x+25,y+35,p);}else{Path a=new Path();a.moveTo(x,y);a.lineTo(x,y+35);a.lineTo(x+29,y+17);a.close();c.drawPath(a,p);}}
    private static void skip(Canvas c,float x,float y,boolean next,int color){Paint p=new Paint(3);p.setColor(color);float s=next?1:-1;Path a=new Path();a.moveTo(x-s*12,y);a.lineTo(x-s*12,y+29);a.lineTo(x+s*10,y+14);a.close();c.drawPath(a,p);c.drawRect(x+s*11-2,y,x+s*11+2,y+29,p);}
    private static String seconds(long ms){if(ms<0)return "—";long sec=ms/1000;return String.format(Locale.ROOT,"%02d:%02d",sec/60,sec%60);}
    static void draw(Canvas canvas,int width,int height,Context context,String theme,String kind,Bundle data){
        int saved=canvas.save();canvas.scale(width/632f,height/432f);
        if(!"paper".equals(theme)){Path clip=new Path();clip.addRoundRect(new android.graphics.RectF(0,0,632,432),"ink".equals(theme)?24:38,"ink".equals(theme)?24:38,Path.Direction.CW);canvas.clipPath(clip);}
        Bitmap bg=Assets.bitmap("widget-"+theme+".png");if(bg!=null)canvas.drawBitmap(bg,null,new Rect(0,0,632,432),new Paint(3));else canvas.drawColor(Color.WHITE);
        int fg="ink".equals(theme)?Color.WHITE:DARK;Calendar now=Calendar.getInstance();String day=DateFormat.format("EEEE",now).toString();String date=DateFormat.format("M月d日",now).toString();String lunar=data.getString("lunar","");
        if("clock".equals(kind)){
            String time=DateFormat.format(DateFormat.is24HourFormat(context)?"HH:mm":"h:mm",now).toString();
            if("paper".equals(theme)){
                center(canvas,DateFormat.format("MMM / dd",now).toString().toUpperCase(Locale.ROOT),316,39,23,theme,true,fg);
                center(canvas,time,316,104,139,theme,true,fg);rule(canvas,280,300,353,fg,2);center(canvas,day+(lunar.isEmpty()?"":" · "+lunar),316,345,25,theme,false,fg);
            }else{
                t(canvas,"ink".equals(theme)?DateFormat.format("EEE / MM.dd",now).toString().toUpperCase(Locale.ROOT):date+"  "+day,37,30,25,theme,"ink".equals(theme),fg);
                t(canvas,time,27,92,161,theme,true,fg);t(canvas,lunar.isEmpty()?date:lunar,39,345,25,theme,false,fg);
                String weather=data.getString("weather","");if(WeatherIcon.available(weather)&&!data.getBoolean("weatherStale"))WeatherIcon.draw(canvas,weather,525,325,61,fg);
            }
        }else if("weather".equals(kind)){
            String weather=data.getString("weather",""),temperature=data.getString("temperature","");boolean available=!temperature.isEmpty();
            String label=available?(data.getBoolean("weatherStale")?"缓存天气":"今日天气"):"暂无天气数据";
            String high=data.getString("high",""),low=data.getString("low","");String range=(low.isEmpty()?"—":low)+" / "+(high.isEmpty()?"—":high);if(weather.isEmpty())weather=available?"天气":"轻触更新天气";
            if("paper".equals(theme)){
                t(canvas,label,32,35,25,theme,false,fg);right(canvas,DateFormat.format("EEE.dd",now).toString().toUpperCase(Locale.ROOT),601,39,22,theme,true,fg);
                if(available)WeatherIcon.draw(canvas,weather,76,137,128,fg);rule(canvas,292,326,292,fg,1);
                Paint p=new Paint(3);p.setColor(Color.LTGRAY);p.setStrokeWidth(1);canvas.drawLine(293,104,293,321,p);
                t(canvas,available?temperature:"—",355,101,127,theme,true,fg);text(canvas,weather,381,280,27,theme,false,fg,Paint.Align.LEFT,215);
                t(canvas,"最低 "+(low.isEmpty()?"—":low),32,361,24,theme,false,fg);right(canvas,"最高 "+(high.isEmpty()?"—":high),601,361,24,theme,false,fg);
            }else{
                t(canvas,label,37,30,25,theme,false,fg);t(canvas,available?temperature:"—",29,100,157,theme,true,fg);
                if(available)WeatherIcon.draw(canvas,weather,431,130,141,fg);
                text(canvas,weather,39,319,available?36:25,theme,false,fg,Paint.Align.LEFT,300);right(canvas,range,594,345,27,theme,true,fg);
            }
        }else{
            boolean available=data.getBoolean("mediaAvailable"),playing=data.getBoolean("playing");String title=available?data.getString("title",""):"暂无播放",artist=available?data.getString("artist",""):"轻触打开音乐";
            long position=data.getLong("position",-1),duration=data.getLong("duration",-1),stamp=data.getLong("positionTime",-1);
            if(playing&&stamp>0&&position>=0)position+=(long)((SystemClock.elapsedRealtime()-stamp)*data.getFloat("speed",1));if(duration>0)position=Math.min(duration,position);
            if("paper".equals(theme)){
                t(canvas,playing?"正在播放":available?"已暂停":"听觉日记",31,35,25,theme,false,fg);text(canvas,title,34,109,60,theme,false,fg,Paint.Align.LEFT,385);text(canvas,artist,38,208,24,theme,false,fg,Paint.Align.LEFT,375);
                Paint p=new Paint(3);p.setColor(fg);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);canvas.drawCircle(501,180,69,p);canvas.drawCircle(501,180,22,p);
                rule(canvas,37,300,594,Color.LTGRAY,2);if(duration>0&&position>=0)rule(canvas,37,300,37+557*Math.min(1,position/(float)duration),fg,3);
                t(canvas,available?seconds(position)+" / "+seconds(duration):"— / —",36,350,23,theme,true,fg);skip(canvas,434,346,false,fg);play(canvas,498,342,playing,fg);skip(canvas,579,346,true,fg);
            }else{
                t(canvas,playing?"正在播放":available?"已暂停":"音乐",37,29,25,theme,false,fg);
                Paint p=new Paint(3);p.setColor("ink".equals(theme)?Color.WHITE:DARK);canvas.drawRoundRect(36,103,212,279,18,18,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);p.setColor("ink".equals(theme)?DARK:Color.WHITE);canvas.drawCircle(124,191,63,p);canvas.drawCircle(124,191,16,p);
                text(canvas,title,247,102,51,theme,false,fg,Paint.Align.LEFT,345);text(canvas,artist,250,190,24,theme,false,fg,Paint.Align.LEFT,345);skip(canvas,289,247,false,fg);play(canvas,402,241,playing,fg);skip(canvas,536,247,true,fg);
                rule(canvas,39,323,593,"ink".equals(theme)?Color.GRAY:Color.LTGRAY,2);if(duration>0&&position>=0)rule(canvas,39,323,39+554*Math.min(1,position/(float)duration),fg,4);
                t(canvas,available?seconds(position):"—",38,357,23,theme,true,fg);right(canvas,available?seconds(duration):"—",594,357,23,theme,true,fg);
            }
        }
        canvas.restoreToCount(saved);
    }
}
