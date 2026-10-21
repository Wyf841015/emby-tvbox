package android.app;

import java.util.HashMap;

/**
 * ActivityThread 桩。
 *
 * Init.interceptActivityStart() 用反射：Class.forName("android.app.ActivityThread")
 *   → currentActivityThread() 拿实例
 *   → 读字段 mActivities 找当前 Activity
 * Config / PanQuark / PanShare 这些 spider 的 detailContent 会调它，
 * 缺类就是 ClassNotFoundException，整站详情全废。
 *
 * 真机上 mActivities 是 SparseArray；这里给个 Map，反射取值后若强转会失败，
 * 所以也提供 SparseArray 风格的兼容访问（见 android.util.SparseArray 桩）。
 */
public class ActivityThread {
    private static final ActivityThread INSTANCE = new ActivityThread();

    /** interceptActivityStart 会 getDeclaredField("mActivities") 再 field.get(thread)。 */
    public HashMap<String, Object> mActivities = new HashMap<>();

    public static ActivityThread currentActivityThread() {
        return INSTANCE;
    }

    public static ActivityThread systemMain() {
        return INSTANCE;
    }

    public Application getApplication() {
        return Application.getInstance();
    }

    public static String currentPackageName() {
        return "tvbox.host";
    }

    public static String currentProcessName() {
        return "tvbox.host";
    }
}
