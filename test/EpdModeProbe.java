/** Root app_process 夹具：只操作临时验收包的原厂模式，避免改动个人应用配置。 */
public final class EpdModeProbe {
    public static void main(String[] args) throws Exception {
        String mode=args.length==1?args[0]:"get", pkg="com.inkcontrol.test";
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"epd");
        Object service=Class.forName("com.hmct.epd.IEpdManager$Stub").getMethod("asInterface",Class.forName("android.os.IBinder")).invoke(null,binder);
        Class<?> api=Class.forName("com.hmct.epd.IEpdManager");
        if(mode.equals("515")||mode.equals("521"))api.getMethod("addAppMode",String.class,int.class).invoke(service,pkg,Integer.parseInt(mode)&255);
        else if(mode.equals("remove"))api.getMethod("removeAppMode",String.class).invoke(service,pkg);
        else if(!mode.equals("get"))throw new IllegalArgumentException("只允许 get/515/521/remove");
        java.util.Map<?,?> values=(java.util.Map<?,?>)api.getMethod("getAllAppMode").invoke(service);
        System.out.println("fixture_mode="+values.get(pkg));
    }
}
