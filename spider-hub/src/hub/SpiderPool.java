package hub;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.catvod.crawler.Spider;

/**
 * spider 池：负责加载配置、按站点 key 创建/缓存 spider 实例。
 *
 * 关键点：
 *  - spider 类来自外部的 dex2jar 产物（classes.jar），用独立的 URLClassLoader 加载
 *  - 基类 com.github.catvod.crawler.Spider 由本工程提供（在系统 classpath 上）
 *  - 同一个 key 只保留一个实例（spider 内部常有状态/缓存）
 */
public class SpiderPool {

    /** 一个站点。 */
    public static class Site {
        public String key;
        public String name;
        public int type;
        public String api;
        public String ext = "";
        public String cls;          // com.github.catvod.spider.Xxx
        public volatile String lastError;
        public volatile long lastOk;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("name", name);
            m.put("type", type);
            m.put("api", api);
            m.put("class", cls);
            m.put("ext", ext.length() > 200 ? ext.substring(0, 200) + "…" : ext);
            m.put("ok", lastOk > 0 && lastError == null);
            m.put("lastError", lastError);
            m.put("lastOk", lastOk == 0 ? null : new java.util.Date(lastOk).toString());
            return m;
        }
    }

    private final Map<String, Site> sites = new ConcurrentHashMap<>();
    private final Map<String, Spider> instances = new ConcurrentHashMap<>();
    private final Map<String, String> loadErrors = new LinkedHashMap<>();
    private ClassLoader spiderLoader;

    public Collection<Site> allSites() {
        return sites.values();
    }

    public Site site(String key) {
        return sites.get(key);
    }

    public Map<String, String> loadErrors() {
        return loadErrors;
    }

    public int size() {
        return sites.size();
    }

    /**
     * 从 TVBox 配置 JSON 文本建立站点表。
     * @param json 配置内容（支持多仓 urls / base64 由调用方先处理）
     * @param baseUrl 用于拼接相对 api
     */
    public void loadFromConfig(String json, String baseUrl) throws Exception {
        sites.clear();
        instances.clear();
        loadErrors.clear();

        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("配置不是 JSON 对象");
        Map<?, ?> cfg = (Map<?, ?>) root;

        List<?> list = (List<?>) cfg.get("sites");
        if (list == null) throw new IllegalArgumentException("配置里没有 sites");

        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> s = (Map<?, ?>) o;
            Site site = new Site();
            site.key = str(s.get("key"));
            site.name = str(s.get("name"));
            site.type = (int) num(s.get("type"));
            site.api = str(s.get("api"));
            Object ext = s.get("ext");
            site.ext = ext == null ? "" : (ext instanceof String ? (String) ext : Json.stringify(ext));

            if (site.key == null || site.key.isEmpty()) site.key = site.name;
            if (site.key == null) continue;

            // 只收 csp_ 开头的 spider 站点
            if (site.type != 3 || site.api == null || !site.api.startsWith("csp_")) continue;
            site.cls = "com.github.catvod.spider." + site.api.substring(4);
            sites.put(site.key, site);
        }
    }

    /** 加载 spider 的 classpath（dex2jar 产物 + 依赖）。 */
    public void setSpiderJars(List<File> jars) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (File f : jars) {
            if (f != null && f.isFile()) urls.add(f.toURI().toURL());
        }
        // 父加载器用系统 classpath，保证基类和 android stub 能解析到
        spiderLoader = new URLClassLoader(urls.toArray(new URL[0]), SpiderPool.class.getClassLoader());
    }

    /** 取（或创建）一个 spider 实例。线程安全：同一 key 只创建一个。 */
    public Spider get(String key) throws Exception {
        Site site = sites.get(key);
        if (site == null) throw new IllegalArgumentException("没有这个站点: " + key);

        Spider cached = instances.get(key);
        if (cached != null) return cached;

        synchronized (this) {
            cached = instances.get(key);
            if (cached != null) return cached;

            Class<?> c;
            try {
                c = Class.forName(site.cls, true, spiderLoader);
            } catch (Throwable t) {
                record(key, "类加载失败: " + rootMsg(t));
                throw new Exception("类加载失败(" + site.cls + "): " + rootMsg(t), t);
            }

            Object obj;
            try {
                obj = c.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                record(key, "实例化失败: " + rootMsg(t));
                throw new Exception("实例化失败: " + rootMsg(t), t);
            }

            Spider spider = (Spider) obj;
            try {
                spider.init(null, site.ext == null ? "" : site.ext);
            } catch (Throwable t) {
                // init 失败不算致命：有些 spider 在 homeContent 里懒初始化
                // 但 StackOverflowError 之类说明 stub 不完整，记录下来
                record(key, "init 异常(继续): " + rootMsg(t));
            }

            instances.put(key, spider);
            return spider;
        }
    }

    /** 记录一次成功。 */
    public void markOk(String key) {
        Site s = sites.get(key);
        if (s != null) {
            s.lastOk = System.currentTimeMillis();
            s.lastError = null;
        }
    }

    /** 记录一次失败。 */
    public void record(String key, String err) {
        Site s = sites.get(key);
        if (s != null) {
            s.lastError = err;
            loadErrors.put(key, err);
        }
    }

    public static String rootMsg(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        String m = x.getClass().getSimpleName();
        if (x.getMessage() != null) m += ": " + x.getMessage();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    /** 供 UI 展示的统计。 */
    public Map<String, Object> stats() {
        int ok = 0, bad = 0;
        for (Site s : sites.values()) {
            if (s.lastOk > 0 && s.lastError == null) ok++;
            else bad++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", sites.size());
        m.put("ok", ok);
        m.put("unknown", bad);
        return m;
    }

    /** 简单 JSON 解析/序列化（不引第三方，避免依赖冲突）。 */
    public static class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        public static Object parse(String text) {
            Json j = new Json(text.trim());
            Object v = j.value();
            j.ws();
            return v;
        }

        public static String stringify(Object o) {
            StringBuilder sb = new StringBuilder();
            write(o, sb);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void write(Object o, StringBuilder sb) {
            if (o == null) { sb.append("null"); return; }
            if (o instanceof String) { quote((String) o, sb); return; }
            if (o instanceof Number || o instanceof Boolean) { sb.append(o); return; }
            if (o instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
                return;
            }
            if (o instanceof Collection) {
                sb.append('[');
                boolean first = true;
                for (Object x : (Collection<?>) o) {
                    if (!first) sb.append(',');
                    first = false;
                    write(x, sb);
                }
                sb.append(']');
                return;
            }
            quote(String.valueOf(o), sb);
        }

        private static void quote(String v, StringBuilder sb) {
            sb.append('"');
            for (int k = 0; k < v.length(); k++) {
                char c = v.charAt(k);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't' && s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (c == 'f' && s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (c == 'n' && s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (i < s.length()) {
                ws();
                String k = string();
                ws();
                if (i < s.length() && s.charAt(i) == ':') i++;
                m.put(k, value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                break;
            }
            return m;
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (i < s.length()) {
                l.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                break;
            }
            return l;
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            if (i < s.length() && s.charAt(i) == '"') i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (i + 4 <= s.length()) {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                            break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (n.isEmpty()) { i++; return null; }
            try {
                if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
                long v = Long.parseLong(n);
                return v;
            } catch (Exception e) {
                return n;
            }
        }
    }
}
