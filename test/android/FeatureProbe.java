package com.inkcontrol.test;

import android.app.Instrumentation;
import android.os.Bundle;
import com.inkcontrol.core.Configuration;
import com.inkcontrol.core.FeatureCatalog;
import com.inkcontrol.core.FeatureDefinition;
import com.inkcontrol.platform.ApplicationServices;
import org.json.JSONObject;

/** 同证书的临时验收 APK；在目标 UID 内调用真实应用服务，不向产品增加控制接口。 */
public final class FeatureProbe extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle value) { arguments=value; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            ApplicationServices app=new ApplicationServices(getTargetContext());
            String operation=arguments.getString("operation", "state");
            String id=arguments.getString("id", "");
            if (operation.equals("change")) {
                FeatureDefinition feature=FeatureCatalog.get(id);
                app.change(id, arguments.getString("enabled", "false").equals("true"),
                        arguments.getString("value", app.configuration().value(id)));
            } else if (operation.equals("disableAll")) app.disableAll();
            else if (operation.equals("appMode")) {
                Object epd=getTargetContext().getSystemService("epd");
                if(epd==null)throw new IllegalStateException("原厂 epd 服务不可用");
                String mode=arguments.getString("mode","get");
                if(mode.equals("methods")) {
                    StringBuilder methods=new StringBuilder();
                    for(java.lang.reflect.Method method:epd.getClass().getMethods())methods.append(method.toString()).append('\n');
                    result.putString("operationResult",methods.toString());finish(-1,result);return;
                }
                if(mode.equals("515")||mode.equals("521"))epd.getClass().getMethod("addAppMode",String.class,int.class).invoke(epd,"com.inkcontrol.test",Integer.parseInt(mode));
                else if(mode.equals("remove"))epd.getClass().getMethod("removeAppMode",String.class).invoke(epd,"com.inkcontrol.test");
                else if(!mode.equals("get"))throw new IllegalArgumentException("只允许验收包的固定模式");
                Object modes=epd.getClass().getMethod("getAllAppMode").invoke(epd);
                result.putString("operationResult",String.valueOf(((java.util.Map<?,?>)modes).get("com.inkcontrol.test")));
            }
            else if (!operation.equals("state")) throw new IllegalArgumentException("未知验收操作");
            Thread.sleep(1500);
            String environment=app.inspect();
            Configuration config=app.configuration();
            JSONObject statuses=new JSONObject();
            for (FeatureDefinition feature:FeatureCatalog.all()) statuses.put(feature.id,app.featureStatus(feature.id));
            JSONObject data=new JSONObject();
            data.put("operation",operation); data.put("id",id);data.put("configuration",config.text());
            data.put("statuses",statuses);data.put("environment",environment);
            Bundle reports=getTargetContext().getContentResolver().call(com.inkcontrol.platform.ConfigProvider.URI,"reports",null,null);
            JSONObject confirmations=new JSONObject();
            if(reports!=null)for(String role:reports.keySet()) {
                Bundle report=reports.getBundle(role);
                if(report==null)continue;
                JSONObject item=new JSONObject();item.put("pid",report.getInt("pid"));item.put("start",report.getString("start"));
                item.put("version",report.getString("version"));item.put("error",report.getString("error"));
                item.put("revision",Configuration.parse(report.getString("text","")).revision);confirmations.put(role,item);
            }
            data.put("confirmations",confirmations);
            result.putString("result",data.toString());
            result.putString("stream","PASS "+operation+" "+id+" revision="+config.revision+"\n");
            finish(-1,result);
        } catch (Throwable error) {
            result.putString("stream","FAIL "+error.getClass().getName()+": "+error.getMessage()+"\n");
            finish(0,result);
        }
    }
}
