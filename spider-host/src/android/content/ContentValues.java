package android.content;

import java.util.HashMap;
import java.util.Map;

public class ContentValues {
    private final Map<String, Object> map = new HashMap<>();
    public ContentValues() {}
    public void put(String key, String value) { map.put(key, value); }
    public void put(String key, Integer value) { map.put(key, value); }
    public void put(String key, Long value) { map.put(key, value); }
    public void put(String key, Boolean value) { map.put(key, value); }
    public void put(String key, byte[] value) { map.put(key, value); }
    public void remove(String key) { map.remove(key); }
    public Object get(String key) { return map.get(key); }
    public boolean containsKey(String key) { return map.containsKey(key); }
    public int size() { return map.size(); }
}
