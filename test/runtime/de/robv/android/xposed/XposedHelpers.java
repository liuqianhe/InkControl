package de.robv.android.xposed;
import java.lang.reflect.*;
public final class XposedHelpers {
    public static Class<?> findClass(String n,ClassLoader c) { try{return Class.forName(n,false,c);}catch(Exception e){throw new RuntimeException(e);} }
    public static XC_MethodHook.Unhook findAndHookConstructor(String n,ClassLoader c,Object... args) {
        Class<?>[] types=new Class<?>[args.length-1];for(int i=0;i<types.length;i++)types[i]=(Class<?>)args[i];
        XC_MethodHook h=(XC_MethodHook)args[args.length-1];
        try { XposedBridge.add(findClass(n,c).getDeclaredConstructor(types),h);return h.new Unhook(); }
        catch(Exception e){throw new RuntimeException(e);}
    }
    public static Object getObjectField(Object o,String n) {
        for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())try {Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}
            catch(NoSuchFieldException e){}catch(Exception e){throw new RuntimeException(e);}
        throw new RuntimeException("missing field "+n);
    }
    public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
    public static boolean getBooleanField(Object o,String n){return (Boolean)getObjectField(o,n);}
    public static Object callMethod(Object o,String n,Object... args){try{return XposedBridge.call(o,n,args);}catch(Throwable e){throw new RuntimeException(e);}}
}
