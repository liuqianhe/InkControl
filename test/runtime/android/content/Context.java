package android.content;
import android.content.pm.PackageManager;
public class Context {
    public final PackageManager pm=new PackageManager();
    public PackageManager getPackageManager(){return pm;}
}
