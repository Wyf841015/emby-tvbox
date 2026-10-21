package android.content;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Android SharedPreferences 的最小可用桩：内存存储，够 spider 读写配置用。 */
public interface SharedPreferences {
    Map<String, ?> getAll();
    String getString(String key, String defValue);
    Set<String> getStringSet(String key, Set<String> defValues);
    int getInt(String key, int defValue);
    long getLong(String key, long defValue);
    float getFloat(String key, float defValue);
    boolean getBoolean(String key, boolean defValue);
    boolean contains(String key);
    Editor edit();
    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);

    interface Editor {
        Editor putString(String key, String value);
        Editor putStringSet(String key, Set<String> values);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putFloat(String key, float value);
        Editor putBoolean(String key, boolean value);
        Editor remove(String key);
        Editor clear();
        boolean commit();
        void apply();
    }

    interface OnSharedPreferenceChangeListener {
        void onSharedPreferenceChanged(SharedPreferences prefs, String key);
    }

    /** 内存实现。 */
    final class Impl implements SharedPreferences {
        private final Map<String, Object> data = new HashMap<>();

        public Map<String, ?> getAll() { return new HashMap<>(data); }
        public String getString(String k, String d) { Object o = data.get(k); return o instanceof String ? (String) o : d; }
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String k, Set<String> d) { Object o = data.get(k); return o instanceof Set ? (Set<String>) o : d; }
        public int getInt(String k, int d) { Object o = data.get(k); return o instanceof Integer ? (Integer) o : d; }
        public long getLong(String k, long d) { Object o = data.get(k); return o instanceof Long ? (Long) o : d; }
        public float getFloat(String k, float d) { Object o = data.get(k); return o instanceof Float ? (Float) o : d; }
        public boolean getBoolean(String k, boolean d) { Object o = data.get(k); return o instanceof Boolean ? (Boolean) o : d; }
        public boolean contains(String k) { return data.containsKey(k); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        public Editor edit() {
            return new Editor() {
                private final Map<String, Object> pending = new HashMap<>();
                private boolean clearFirst = false;
                public Editor putString(String k, String v) { pending.put(k, v); return this; }
                public Editor putStringSet(String k, Set<String> v) { pending.put(k, v); return this; }
                public Editor putInt(String k, int v) { pending.put(k, v); return this; }
                public Editor putLong(String k, long v) { pending.put(k, v); return this; }
                public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
                public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
                public Editor remove(String k) { pending.put(k, null); return this; }
                public Editor clear() { clearFirst = true; return this; }
                public boolean commit() {
                    if (clearFirst) data.clear();
                    for (Map.Entry<String, Object> e : pending.entrySet()) {
                        if (e.getValue() == null) data.remove(e.getKey());
                        else data.put(e.getKey(), e.getValue());
                    }
                    return true;
                }
                public void apply() { commit(); }
            };
        }
    }
}
