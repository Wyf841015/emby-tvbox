package android.util;

public class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;
    public static final int NO_PADDING = 1;

    public static String encodeToString(byte[] input, int flags) {
        if ((flags & URL_SAFE) != 0) {
            String s = java.util.Base64.getUrlEncoder().encodeToString(input);
            if ((flags & NO_PADDING) != 0) s = s.replace("=", "");
            if ((flags & NO_WRAP) == 0) return s;
            return s;
        }
        return java.util.Base64.getEncoder().encodeToString(input);
    }

    public static byte[] decode(String str, int flags) {
        if ((flags & URL_SAFE) != 0) return java.util.Base64.getUrlDecoder().decode(str);
        return java.util.Base64.getDecoder().decode(str);
    }

    /**
     * Android 的 Base64 有一组「把 flags 写成第二个位置」的重载，其中
     * decode(byte[], int) 被不少 spider 直接用来解开配置/接口返回的 base64。
     * 纯 JVM 上缺这个方法会 NoSuchMethodError（整站直接判死，如「魔方」）。
     */
    public static byte[] decode(byte[] input, int flags) {
        return decode(new String(input, java.nio.charset.StandardCharsets.UTF_8), flags);
    }

    public static byte[] decode(byte[] input, int offset, int len, int flags) {
        byte[] slice = new byte[len];
        System.arraycopy(input, offset, slice, 0, len);
        return decode(slice, flags);
    }

    public static byte[] decode(String str, int flags, int dummy) {
        return decode(str, flags);
    }

    public static byte[] encode(byte[] input, int flags) {
        return encodeToString(input, flags).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}