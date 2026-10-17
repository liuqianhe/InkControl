package de.robv.android.xposed;
public final class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static Object invokeOriginalMethod(java.lang.reflect.Member method, Object object, Object[] args) throws java.lang.reflect.InvocationTargetException, IllegalAccessException { return null; }
    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member method, XC_MethodHook callback) { return null; }
    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
}
