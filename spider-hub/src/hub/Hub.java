package hub;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.github.catvod.crawler.Spider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * spider-hub：把 TVBox 的 catvod spider 变成标准 MacCMS 采集站。
 *
 * 每个 spider 站点对外的地址是：
 *     http://<host>:<port>/v/<站点key>/api.php/provide/vod/
 *
 * 支持 ac=list / ac=videolist&t=&pg= / ac=detail&ids= / wd=关键词，
 * 也就是苹果CMS v10 那套参数 —— 所以任何认 MacCMS 的客户端
 * （包括我们的 Emby 插件）都能直接把它当采集站用。
 *
 * 启动：
 *   java -cp "out:lib/*:spiders/*" hub.Hub --port 8098 --config hub.json
 */
public class Hub {

    static SpiderPool pool = new SpiderPool();
    static ExecutorService workers = Executors.newFixedThreadPool(8);
    static Path dataFile;
    static Map<String, Object> settings = new LinkedHashMap<>();
    static long started = System.currentTimeMillis();

    public static void main(String[] args) throws Exception {
        int port = 8098;
        String jarDir = null;
        String spiderJar = null;
        Path configFile = Path.of("hub.json");

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--config": configFile = Path.of(args[++i]); break;
                case "--spider-jar": spiderJar = args[++i]; break;
                case "--jar-dir": jarDir = args[++i]; break;
                case "--help":
                    System.out.println("用法: java hub.Hub --port 8098 --config hub.json --spider-jar /path/classes.jar --jar-dir /path/lib");
                    return;
                default:
                    System.out.println("未知参数: " + a);
            }
        }

        dataFile = configFile;
        loadSettings();

        // spider classpath：优先用参数，其次读配置
        List<File> jars = new ArrayList<>();
        String sj = spiderJar != null ? spiderJar : str(settings.get("spiderJar"));
        if (sj != null) jars.add(new File(sj));
        String jd = jarDir != null ? jarDir : str(settings.get("jarDir"));
        if (jd != null) {
            File[] fs = new File(jd).listFiles((d, n) -> n.endsWith(".jar"));
            if (fs != null) jars.addAll(Arrays.asList(fs));
        }
        try {
            pool.setSpiderJars(jars);
            System.out.println("spider classpath: " + jars.size() + " 个 jar");
        } catch (Exception e) {
            System.out.println("spider classpath 加载失败: " + e);
        }

        String cfgUrl = str(settings.get("configUrl"));
        if (cfgUrl != null && !cfgUrl.isEmpty()) {
            try {
                reload(cfgUrl);
            } catch (Exception e) {
                System.out.println("启动时加载配置失败: " + e.getMessage());
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", Hub::handle);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("spider-hub 已启动: http://0.0.0.0:" + port + "/");
    }

    // ───────────────────────── 路由 ─────────────────────────

    static void handle(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        try {
            if ("/".equals(path) || "/index.html".equals(path)) {
                send(ex, 200, "text/html; charset=utf-8", ui());
                return;
            }
            if (path.startsWith("/v/")) {
                handleCms(ex, path);
                return;
            }
            if (path.startsWith("/api/")) {
                handleApi(ex, path);
                return;
            }
            send(ex, 404, "text/plain; charset=utf-8", "404");
        } catch (Exception e) {
            try {
                send(ex, 500, "text/plain; charset=utf-8", "ERROR: " + e + "\n" + stack(e));
            } catch (Exception ignore) {
            }
        } finally {
            ex.close();
        }
    }

    static void handleApi(HttpExchange ex, String path) throws Exception {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        switch (path) {
            case "/api/status": {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("uptimeSec", (System.currentTimeMillis() - started) / 1000);
                m.put("configUrl", str(settings.get("configUrl")));
                m.put("spiderJar", str(settings.get("spiderJar")));
                m.put("stats", pool.stats());
                m.put("loadErrors", pool.loadErrors());
                m.put("javaVersion", System.getProperty("java.version"));
                sendJson(ex, 200, m);
                return;
            }
            case "/api/sites": {
                List<Object> list = new ArrayList<>();
                for (SpiderPool.Site s : pool.allSites()) list.add(s.toMap());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("total", pool.size());
                m.put("sites", list);
                sendJson(ex, 200, m);
                return;
            }
            case "/api/home": {
                String key = q.get("key");
                String raw = call(key, () -> {
                    Spider s = pool.get(key);
                    return s.homeContent(false);
                }, 45);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("raw", raw);
                m.put("parsed", tryParse(raw));
                sendJson(ex, 200, m);
                return;
            }
            case "/api/category": {
                String key = q.get("key");
                String tid = q.getOrDefault("tid", "1");
                String pg = q.getOrDefault("pg", "1");
                String raw = call(key, () -> {
                    Spider s = pool.get(key);
                    return s.categoryContent(tid, pg, false, new HashMap<>());
                }, 60);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("raw", raw);
                m.put("parsed", tryParse(raw));
                sendJson(ex, 200, m);
                return;
            }
            case "/api/detail": {
                String key = q.get("key");
                String id = q.get("id");
                String raw = call(key, () -> {
                    Spider s = pool.get(key);
                    List<String> ids = new ArrayList<>();
                    ids.add(id);
                    return s.detailContent(ids);
                }, 60);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("raw", raw);
                m.put("parsed", tryParse(raw));
                sendJson(ex, 200, m);
                return;
            }
            case "/api/search": {
                String key = q.get("key");
                String wd = q.getOrDefault("wd", "");
                String raw = call(key, () -> {
                    Spider s = pool.get(key);
                    return s.searchContent(wd, false);
                }, 60);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("raw", raw);
                m.put("parsed", tryParse(raw));
                sendJson(ex, 200, m);
                return;
            }
            case "/api/save": {
                String body = readBody(ex);
                Object o = SpiderPool.Json.parse(body);
                if (o instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                        settings.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    saveSettings();
                }
                sendJson(ex, 200, ok("已保存"));
                return;
            }
            case "/api/reload": {
                String url = str(settings.get("configUrl"));
                if (url == null || url.isEmpty()) {
                    sendJson(ex, 200, fail("先设置配置地址"));
                    return;
                }
                int n = reload(url);
                sendJson(ex, 200, ok("已加载 " + n + " 个 spider 站点"));
                return;
            }
            default:
                send(ex, 404, "text/plain; charset=utf-8", "no such api");
        }
    }

    /** MacCMS 兼容接口：/v/<key>/api.php/provide/vod/?ac=... */
    static void handleCms(HttpExchange ex, String path) throws Exception {
        String rest = path.substring(3);                 // <key>/api.php/...
        int slash = rest.indexOf('/');
        if (slash <= 0) { sendCms(ex, fail("路径应为 /v/<key>/api.php/provide/vod/")); return; }
        String key = URLDecoder.decode(rest.substring(0, slash), StandardCharsets.UTF_8);
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String ac = q.getOrDefault("ac", "list");

        try {
            switch (ac) {
                case "detail":
                case "videolist": {
                    String ids = q.get("ids");
                    if (ids != null && !ids.isEmpty()) {
                        List<String> idList = new ArrayList<>(Arrays.asList(ids.split(",")));
                        String raw = call(key, () -> {
                            Spider s = pool.get(key);
                            return s.detailContent(idList);
                        }, 60);
                        sendCms(ex, wrapList(raw, null));
                        return;
                    }
                    String tid = q.getOrDefault("t", "1");
                    String pg = q.getOrDefault("pg", "1");
                    String wd = q.get("wd");
                    if (wd != null && !wd.isEmpty()) {
                        String raw = call(key, () -> {
                            Spider s = pool.get(key);
                            return s.searchContent(wd, false);
                        }, 60);
                        sendCms(ex, wrapList(raw, null));
                        return;
                    }
                    String raw = call(key, () -> {
                        Spider s = pool.get(key);
                        return s.categoryContent(tid, pg, false, new HashMap<>());
                    }, 60);
                    sendCms(ex, wrapList(raw, null));
                    return;
                }
                default: {   // ac=list：分类 + 首页列表
                    String raw = call(key, () -> {
                        Spider s = pool.get(key);
                        return s.homeContent(false);
                    }, 45);
                    sendCms(ex, wrapList(raw, "home"));
                }
            }
        } catch (Exception e) {
            sendCms(ex, fail(e.getMessage()));
        }
    }

    // ───────────────────────── CatVod → MacCMS ─────────────────────────

    /**
     * CatVod 的返回本身就是 {class:[], list:[], page:..}，字段名和 MacCMS 基本一致
     * （vod_id/vod_name/vod_pic/vod_remarks/vod_play_from/vod_play_url），
     * 这里只补齐 MacCMS 客户端期望的外壳字段。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> wrapList(String raw, String mode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 1);
        out.put("msg", "数据列表");
        out.put("page", 1);
        out.put("pagecount", 1);
        out.put("limit", "20");
        out.put("total", 0);

        Object parsed = tryParse(raw);
        if (!(parsed instanceof Map)) {
            out.put("list", new ArrayList<>());
            out.put("class", new ArrayList<>());
            return out;
        }
        Map<String, Object> m = (Map<String, Object>) parsed;
        List<Object> list = (List<Object>) m.get("list");
        if (list == null) list = new ArrayList<>();
        out.put("list", list);
        out.put("class", m.get("class") != null ? m.get("class") : new ArrayList<>());
        if (m.get("page") != null) out.put("page", m.get("page"));
        if (m.get("pagecount") != null) out.put("pagecount", m.get("pagecount"));
        if (m.get("limit") != null) out.put("limit", m.get("limit"));
        if (m.get("total") != null) out.put("total", m.get("total"));
        out.put("total", list.size() > 0 && num(m.get("total")) == 0 ? list.size() : out.get("total"));
        if (mode == null) out.remove("class");
        return out;
    }

    static double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    static Object tryParse(String raw) {
        if (raw == null) return null;
        try {
            return SpiderPool.Json.parse(raw);
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("_raw", raw.length() > 2000 ? raw.substring(0, 2000) : raw);
            m.put("_parseError", String.valueOf(e));
            return m;
        }
    }

    // ───────────────────────── 调用工具 ─────────────────────────

    /** 带超时调用 spider；失败记录下来并抛出。 */
    static String call(String key, Callable<String> task, int timeoutSec) throws Exception {
        Future<String> f = workers.submit(task);
        try {
            String r = f.get(timeoutSec, TimeUnit.SECONDS);
            pool.markOk(key);
            return r;
        } catch (java.util.concurrent.TimeoutException te) {
            f.cancel(true);
            pool.record(key, "超时(" + timeoutSec + "s)");
            throw new Exception("spider 超时(" + timeoutSec + "s)");
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable c = ee.getCause() == null ? ee : ee.getCause();
            String msg = SpiderPool.rootMsg(c);
            pool.record(key, msg);
            throw new Exception(msg, c);
        }
    }

    static int reload(String url) throws Exception {
        String body;
        if (url.startsWith("http")) {
            body = fetch(url);
        } else {
            body = Files.readString(Path.of(url), StandardCharsets.UTF_8);
        }
        body = body.trim();
        // 多仓：{urls:[...]} → 取第一个可用子仓（完整多仓展开在 Emby 插件侧做）
        Object root = null;
        try { root = SpiderPool.Json.parse(body); } catch (Exception ignore) { }
        if (root instanceof Map && ((Map<?, ?>) root).get("urls") instanceof List) {
            List<?> urls = (List<?>) ((Map<?, ?>) root).get("urls");
            if (urls.isEmpty()) throw new Exception("多仓里没有子仓");
            String sub = String.valueOf(((Map<?, ?>) urls.get(0)).get("url"));
            body = fetch(sub);
            System.out.println("多仓 → 取第一个子仓: " + sub);
        }
        pool.loadFromConfig(body, url);
        System.out.println("已加载 " + pool.size() + " 个 spider 站点");
        return pool.size();
    }

    static String fetch(String url) throws Exception {
        java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(15)).build();
        java.net.http.HttpRequest r = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(40))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0.0.0 Safari/537.36")
                .GET().build();
        java.net.http.HttpResponse<String> resp = c.send(r, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode());
        return resp.body();
    }

    // ───────────────────────── HTTP 基础 ─────────────────────────

    static void send(HttpExchange ex, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    static void sendJson(HttpExchange ex, int code, Object o) throws Exception {
        send(ex, code, "application/json; charset=utf-8", SpiderPool.Json.stringify(o));
    }

    static void sendCms(HttpExchange ex, Object o) throws Exception {
        sendJson(ex, 200, o);
    }

    static String readBody(HttpExchange ex) throws Exception {
        try (InputStream in = ex.getRequestBody(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) m.put(dec(kv), "");
            else m.put(dec(kv.substring(0, i)), dec(kv.substring(i + 1)));
        }
        return m;
    }

    static String dec(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; }
    }

    static Map<String, Object> ok(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("msg", msg);
        return m;
    }

    static Map<String, Object> fail(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("msg", msg == null ? "未知错误" : msg);
        m.put("code", 0);
        m.put("list", new ArrayList<>());
        return m;
    }

    static String stack(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : t.getStackTrace()) sb.append("  at ").append(e).append('\n');
        return sb.toString();
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ───────────────────────── 配置持久化 ─────────────────────────

    static void loadSettings() {
        try {
            if (dataFile != null && Files.isRegularFile(dataFile)) {
                Object o = SpiderPool.Json.parse(Files.readString(dataFile, StandardCharsets.UTF_8));
                if (o instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                        settings.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("读取配置失败: " + e.getMessage());
        }
        if (dataFile != null && !settings.containsKey("configUrl")) {
            settings.put("configUrl", "");
        }
    }

    static void saveSettings() {
        try {
            if (dataFile != null) {
                Files.writeString(dataFile, SpiderPool.Json.stringify(settings), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.out.println("保存配置失败: " + e.getMessage());
        }
    }

    // ───────────────────────── Web UI ─────────────────────────

    static String ui() {
        try {
            // 优先读 jar 同目录 web/index.html（方便改），否则读 classpath
            Path p = Path.of("web", "index.html");
            if (Files.isRegularFile(p)) return Files.readString(p, StandardCharsets.UTF_8);
            Path p2 = Path.of(System.getProperty("hub.dir", "."), "web", "index.html");
            if (Files.isRegularFile(p2)) return Files.readString(p2, StandardCharsets.UTF_8);
            try (InputStream in = Hub.class.getResourceAsStream("/web/index.html")) {
                if (in != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    return out.toString(StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignore) {
        }
        return "<h1>spider-hub</h1><p>web/index.html 没找到</p>";
    }
}
