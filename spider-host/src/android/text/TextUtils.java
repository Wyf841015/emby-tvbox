package android.text;

import java.util.List;

public class TextUtils {
    public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }
    public static boolean equals(CharSequence a, CharSequence b) {
        return a == null ? b == null : a.toString().equals(b == null ? null : b.toString());
    }
    public static String join(CharSequence delim, Iterable<?> tokens) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object o : tokens) {
            if (!first) sb.append(delim);
            sb.append(o);
            first = false;
        }
        return sb.toString();
    }
    public static String join(CharSequence delim, Object[] tokens) {
        return join(delim, java.util.Arrays.asList(tokens));
    }
    public static boolean isDigitsOnly(CharSequence s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return s.length() > 0;
    }
}