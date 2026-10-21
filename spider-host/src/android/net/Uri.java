package android.net;

public class Uri {
    private final java.net.URI uri;
    private Uri(java.net.URI u) { this.uri = u; }

    public static Uri parse(String s) {
        try { return new Uri(new java.net.URI(s)); }
        catch (Exception e) { return new Uri(null); }
    }

    public String getQueryParameter(String key) {
        if (uri == null || uri.getQuery() == null) return null;
        for (String kv : uri.getQuery().split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key))
                return java.net.URLDecoder.decode(kv.substring(i + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    public String toString() { return uri == null ? "" : uri.toString(); }
}