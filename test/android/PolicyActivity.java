package com.inkcontrol.test;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import org.json.JSONObject;

/** 独立普通应用 UID 的真实 Binder 验收；只修改自己的临时模式行。 */
public final class PolicyActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        JSONObject result=new JSONObject();
        try {
            Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"epd");
            Object epd=Class.forName("com.hmct.epd.IEpdManager$Stub").getMethod("asInterface",android.os.IBinder.class).invoke(null,binder);
            Class<?> api=Class.forName("com.hmct.epd.IEpdManager");
            api.getMethod("addAppMode",String.class,int.class).invoke(epd,getPackageName(),9);
            Object modes=api.getMethod("getAllAppMode").invoke(epd);
            result.put("mode",((java.util.Map<?,?>)modes).get(getPackageName()));
            result.put("success",true);
        } catch(Throwable error) {
            if(error instanceof InvocationTargetException)error=error.getCause();
            try {result.put("success",false);result.put("error",error.toString());}catch(Exception ignored){}
        }
        try(FileOutputStream out=openFileOutput("policy-fixture.json",MODE_PRIVATE)) {
            out.write(result.toString(2).getBytes("UTF-8"));
        } catch(Exception error) {throw new IllegalStateException(error);}
        TextView text=new TextView(this);text.setText(result.toString());setContentView(text);
    }
}
