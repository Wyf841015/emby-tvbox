package android.os;

import java.util.HashMap;
import java.util.Map;

public class Bundle {
    private final Map<String, Object> map = new HashMap<>();
    public void putString(String k, String v) { map.put(k, v); }
    public String getString(String k) { Object o = map.get(k); return o == null ? null : String.valueOf(o); }
    public void putInt(String k, int v) { map.put(k, v); }
    public int getInt(String k) { Object o = map.get(k); return o instanceof Integer ? (Integer) o : 0; }
    public boolean containsKey(String k) { return map.containsKey(k); }
    public void putAll(Bundle b) { map.putAll(b.map); }
}