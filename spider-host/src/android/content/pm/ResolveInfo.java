package android.content.pm;

public class ResolveInfo {
    public ActivityInfo activityInfo = new ActivityInfo();
    public String resolvePackageName = "tvbox.host";

    public static class ActivityInfo {
        public String packageName = "tvbox.host";
        public String name = "";
        public ApplicationInfo applicationInfo = new ApplicationInfo();
    }
}