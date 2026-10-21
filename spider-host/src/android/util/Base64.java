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

    public static byte[] encode(byte[] input, int flags) {
        return encodeToString(input, flags).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}