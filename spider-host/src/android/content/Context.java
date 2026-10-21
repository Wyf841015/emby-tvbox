package android.content;

/** 极简 Context stub：spider 只把它当占位符传进来，基本不用。 */
public class Context {
    public Context() {}
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String name) { return null; }
    public String getPackageName() { return "tvbox.host"; }
    public android.content.res.Resources getResources() { return new android.content.res.Resources(); }
    public java.io.File getCacheDir() { return new java.io.File(System.getProperty("java.io.tmpdir")); }
    public java.io.File getFilesDir() { return getCacheDir(); }
    public ClassLoader getClassLoader() { return Context.class.getClassLoader(); }
    public Object getSharedPreferences(String n, int m) { return null; }
}