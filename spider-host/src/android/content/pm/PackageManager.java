package android.content.pm;

import java.util.ArrayList;
import java.util.List;

public class PackageManager {
    public static final int GET_META_DATA = 128;
    public static final int GET_SIGNATURES = 64;
    public static final int GET_SIGNING_CERTIFICATES = 134217728;

    public PackageInfo getPackageInfo(String pkg, int flags) throws NameNotFoundException {
        return new PackageInfo();
    }

    public List<PackageInfo> getInstalledPackages(int flags) { return new ArrayList<>(); }
    public List<PackageInfo> getPackagesHoldingPermissions(String[] perms, int flags) { return new ArrayList<>(); }
    public ResolveInfo resolveActivity(Object intent, int flags) { return null; }
    public List<ResolveInfo> queryIntentActivities(Object intent, int flags) { return new ArrayList<>(); }

    public static class NameNotFoundException extends Exception {
        public NameNotFoundException() { super("package not found"); }
    }
}