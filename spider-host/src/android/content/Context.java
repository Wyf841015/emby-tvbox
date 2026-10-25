package android.content;

/** 极简 Context stub：spider 只把它当占位符传进来，基本不用。 */
public class Context {
    public Context() {}
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String name) { return null; }
    public String getPackageName() { return "tvbox.host"; }

    private static final ContentResolver RESOLVER = new ContentResolver();
    public ContentResolver getContentResolver() { return RESOLVER; }
    public android.content.res.Resources getResources() { return new android.content.res.Resources(); }
    public java.io.File getCacheDir() { return new java.io.File(System.getProperty("java.io.tmpdir")); }

    /**
     * spider 的「私有目录」。
     *
     * 这套 TVBox spider 的网盘凭证全部存在 {@code new File(getFilesDir(), "lzxw")} 下面
     * （见 merge.m.k2.root()），所以这里的返回值直接决定了「登录凭证存哪」。
     *
     * 原来是 getCacheDir() → java.io.tmpdir → /tmp/lzxw：重启或清 /tmp 凭证就静默丢失，
     * 表现是「昨天还能播，今天网盘列表空了」。现在由 -Dtvbox.panDir 指到
     * ${PKGVAR}/data/pan（见 run-hub.sh），持久化。
     */
    public java.io.File getFilesDir() {
        String custom = System.getProperty("tvbox.panDir");
        if (custom != null && !custom.isBlank()) {
            java.io.File f = new java.io.File(custom);
            if (!f.exists()) f.mkdirs();
            return f;
        }
        return getCacheDir();
    }
    public ClassLoader getClassLoader() { return Context.class.getClassLoader(); }
    private static final java.util.Map<String, SharedPreferences> PREFS = new java.util.concurrent.ConcurrentHashMap<>();

    public SharedPreferences getSharedPreferences(String n, int m) {
        return PREFS.computeIfAbsent(String.valueOf(n), k -> new SharedPreferences.Impl());
    }
}