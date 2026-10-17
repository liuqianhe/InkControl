package android.util;
public class Log {
    public static int e(String t,String m,Throwable e){throw new AssertionError(m,e);}
    public static int e(String t,String m){throw new AssertionError(m);}
    public static int i(String t,String m){return 0;}
    public static int d(String t,String m){return 0;}
    public static int v(String t,String m){return 0;}
    public static int w(String t,String m){return 0;}
}
