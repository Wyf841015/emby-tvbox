package android.provider;

import android.content.ContentResolver;

/**
 * Settings 桩。spider 常拿 Settings.Secure.getString(resolver, "android_id") 当设备号，
 * JVM 上没有这个类就是 ClassNotFoundException，整站直接废。
 */
public class Settings {
    public static class Secure {
        public static String getString(ContentResolver resolver, String name) { return null; }
        public static int getInt(ContentResolver resolver, String name, int def) { return def; }
        public static long getLong(ContentResolver resolver, String name, long def) { return def; }
        public static float getFloat(ContentResolver resolver, String name, float def) { return def; }
        public static boolean putString(ContentResolver resolver, String name, String value) { return true; }
    }
    public static class Global {
        public static String getString(ContentResolver resolver, String name) { return null; }
        public static int getInt(ContentResolver resolver, String name, int def) { return def; }
    }
    public static class System {
        public static String getString(ContentResolver resolver, String name) { return null; }
        public static int getInt(ContentResolver resolver, String name, int def) { return def; }
    }
}
