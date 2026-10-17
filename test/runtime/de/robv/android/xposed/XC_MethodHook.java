package de.robv.android.xposed;
public abstract class XC_MethodHook {
    final int priority;
    public XC_MethodHook() { this(50); }
    public XC_MethodHook(int priority) { this.priority = priority; }
    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam p) throws Throwable {}
    public static class MethodHookParam {
        public java.lang.reflect.Member method;
        public Object thisObject;
        public Object[] args;
        private Object result; private Throwable throwable; boolean early;
        public Object getResult() { return result; }
        public void setResult(Object value) { result=value; throwable=null; early=true; }
        public Throwable getThrowable() { return throwable; }
        public boolean hasThrowable() { return throwable!=null; }
        public void setThrowable(Throwable value) { throwable=value; early=true; }
    }
    public class Unhook {}
}
