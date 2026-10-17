package android.os;
import java.util.*;
public class Handler {
    private final Queue<Runnable> queue=new ArrayDeque<>();
    public boolean post(Runnable r){queue.add(r);return true;}
    public void drain(){while(!queue.isEmpty())queue.remove().run();}
}
