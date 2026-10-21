package android.app;

public class Application extends android.content.Context {
    private static final Application INSTANCE = new Application();
    public static Application getInstance() { return INSTANCE; }
    public void onCreate() {}
    public void onTerminate() {}
    public void registerActivityLifecycleCallbacks(Object cb) {}
}