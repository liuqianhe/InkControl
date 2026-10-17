package android.os;
import java.util.HashMap;
public final class Bundle {
    private final HashMap<String, Object> values = new HashMap<>();
    public void putString(String key, String value) { values.put(key, value); }
    public String getString(String key) { return (String) values.get(key); }
    public String getString(String key, String fallback) { String value = getString(key); return value == null ? fallback : value; }
    public void putInt(String key, int value) { values.put(key, value); }
    public int getInt(String key) { return values.containsKey(key) ? (Integer) values.get(key) : 0; }
    public void putBundle(String key, Bundle value) { values.put(key, value); }
    public Bundle getBundle(String key) { return (Bundle) values.get(key); }
}
