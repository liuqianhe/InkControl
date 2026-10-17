package de.robv.android.xposed;
import java.lang.reflect.*;
import java.util.*;
/** Test dispatcher, not an implementation of ART or LSPosed. */
public final class XposedBridge {
    static final Map<Member,List<XC_MethodHook>> hooks = new HashMap<>();
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static void deoptimizeMethod(Member m) {}
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> c, String name, XC_MethodHook h) {
        Set<XC_MethodHook.Unhook> result = new HashSet<>();
        for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name)) {
            add(m,h); result.add(h.new Unhook());
        }
        return result;
    }
    static void add(Member m, XC_MethodHook h) {
        List<XC_MethodHook> list = hooks.get(m);
        if (list == null) { list = new ArrayList<>(); hooks.put(m,list); }
        list.add(h); list.sort((a,b)->Integer.compare(b.priority,a.priority));
    }
    public static Object invokeOriginalMethod(Member member, Object object, Object[] args) throws InvocationTargetException,IllegalAccessException {
        Method method=(Method) member; method.setAccessible(true); return method.invoke(object,args);
    }
    public static Object call(Object object, String name, Object... args) throws Throwable {
        Method m=null;
        for (Method candidate : object.getClass().getDeclaredMethods())
            if (candidate.getName().equals(name) && candidate.getParameterTypes().length==args.length) { m=candidate; break; }
        if(m==null) throw new NoSuchMethodException(name);
        XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();
        p.method=m; p.thisObject=object; p.args=args;
        List<XC_MethodHook> list=hooks.get(m); int invoked=0;
        if(list!=null) for(XC_MethodHook h:list) { h.beforeHookedMethod(p); invoked++; if(p.early) break; }
        if(!p.early) try { p.setResult(invokeOriginalMethod(m,object,args)); }
            catch(InvocationTargetException e) { p.setThrowable(e.getCause()); }
        if(list!=null) for(int i=invoked-1;i>=0;i--) list.get(i).afterHookedMethod(p);
        if(p.hasThrowable()) throw p.getThrowable(); return p.getResult();
    }
    public static void constructed(Object object) throws Throwable {
        Constructor<?> c=object.getClass().getDeclaredConstructors()[0];
        List<XC_MethodHook> list=hooks.get(c);
        if(list==null) return;
        XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam(); p.thisObject=object;p.method=c;
        for(XC_MethodHook h:list) h.afterHookedMethod(p);
    }
}
