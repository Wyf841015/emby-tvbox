package android.content;

/**
 * 极简 ContentResolver 桩。
 *
 * AppDrama 这类 spider 会调 Context.getContentResolver() 拿设备 ID / 设置项，
 * 真机上不存在也能跑，但 JVM 上缺这个类就是 NoSuchMethodError，整站直接废。
 * 这里返回空/默认值，让它走"拿不到"的分支。
 */
public class ContentResolver {
    public String getType(android.net.Uri uri) { return null; }
    public android.database.Cursor query(android.net.Uri uri, String[] projection,
                                         String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }
    public android.net.Uri insert(android.net.Uri url, android.content.ContentValues values) { return url; }
    public int delete(android.net.Uri url, String where, String[] args) { return 0; }
    public int update(android.net.Uri uri, android.content.ContentValues values, String where, String[] args) { return 0; }
    public void registerContentObserver(android.net.Uri uri, boolean notifyForDescendants,
                                        android.database.ContentObserver observer) { }
    public void unregisterContentObserver(android.database.ContentObserver observer) { }
    public void notifyChange(android.net.Uri uri, android.database.ContentObserver observer) { }
}
