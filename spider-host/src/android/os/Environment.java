package android.os;

import java.io.File;

/**
 * android.os.Environment 桩。
 *
 * 不少 spider 在 init/解析阶段会调 Environment.getExternalStorageDirectory()
 * 拼缓存/配置文件路径；纯 JVM 上没有这个方法会直接
 * ClassNotFoundException: android.os.Environment，整站被判死。
 *
 * 这里统一映射到 java.io.tmpdir 下的 tvbox-env/，仍然保证：
 *   · 返回非 null 的 File（spider 里大量 new File(env, "xx") 会直接 NPE）
 *   · 目录可写（首次调用时 mkdirs）
 *   · 静态常量齐全（DIRECTORY_MOVIES / DIRECTORY_DOWNLOADS …）
 */
public class Environment {

    public static final String DIRECTORY_MUSIC = "Music";
    public static final String DIRECTORY_PODCASTS = "Podcasts";
    public static final String DIRECTORY_RINGTONES = "Ringtones";
    public static final String DIRECTORY_ALARMS = "Alarms";
    public static final String DIRECTORY_NOTIFICATIONS = "Notifications";
    public static final String DIRECTORY_PICTURES = "Pictures";
    public static final String DIRECTORY_MOVIES = "Movies";
    public static final String DIRECTORY_DOWNLOADS = "Downloads";
    public static final String DIRECTORY_DCIM = "DCIM";
    public static final String DIRECTORY_DOCUMENTS = "Documents";
    public static final String MEDIA_MOUNTED = "mounted";

    private static File root() {
        File d = new File(System.getProperty("java.io.tmpdir", "/tmp"), "tvbox-env");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static File getExternalStorageDirectory() {
        return root();
    }

    public static File getExternalStoragePublicDirectory(String type) {
        File d = new File(root(), type == null || type.isEmpty() ? "Public" : type);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static File getDataDirectory() {
        return root();
    }

    public static File getDownloadCacheDirectory() {
        return new File(root(), "cache");
    }

    public static String getExternalStorageState() {
        return MEDIA_MOUNTED;
    }

    public static String getDataDirectoryPath() {
        return getDataDirectory().getAbsolutePath();
    }

    public static boolean isExternalStorageRemovable() {
        return false;
    }

    public static boolean isExternalStorageEmulated() {
        return true;
    }
}
