package android.os;
public class Binder {
    public static int uid=1000, pid=123;
    public static int getCallingUid(){return uid;}
    public static int getCallingPid(){return pid;}
    public static long clearCallingIdentity(){int old=uid;uid=1000;return old;}
    public static void restoreCallingIdentity(long old){uid=(int)old;}
}
