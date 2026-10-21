package android.database;

import android.net.Uri;

public abstract class ContentObserver {
    public ContentObserver(android.os.Handler handler) {}
    public void onChange(boolean selfChange) {}
    public void onChange(boolean selfChange, Uri uri) {}
}
