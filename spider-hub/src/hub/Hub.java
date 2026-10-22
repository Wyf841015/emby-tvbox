package hub;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
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

    // ── ac=detail 结果缓存 ──
    // spider 的 detailContent 是最慢的一环（很多站 3~8 秒/条），而它是纯读操作。
    // 同步时的「拆批重试」「跨轮次重跑」「重复点同步」都会重复打同样的 id，
    // 加一层短 TTL 缓存能直接把这些重复请求吃掉，也少给上游压力。
    // 按「站点|单个 id」做键：拆批重试按单个 id 重发，也能命中。
    private static final class DetailEntry {
        final String json;
        final long at;
        DetailEntry(String json, long at) { this.json = json; this.at = at; }
    }
    private static final Map<String, DetailEntry> detailCache = new ConcurrentHashMap<>();
    private static final int DETAIL_CACHE_MAX = 800;
    private static final long DETAIL_TTL_MS = 5 * 60 * 1000L;
    private static final LongAdder detailHits = new LongAdder();
    private static final LongAdder detailMiss = new LongAdder();

    static String detailCacheGet(String k) {
        DetailEntry e = detailCache.get(k);
        if (e == null) { detailMiss.increment(); return null; }
        if (System.currentTimeMillis() - e.at > DETAIL_TTL_MS) { detailCache.remove(k); detailMiss.increment(); return null; }
        detailHits.increment();
        return e.json;
    }

    static synchronized void detailCachePut(String k, String json) {
        long now = System.currentTimeMillis();
        if (detailCache.size() >= DETAIL_CACHE_MAX) {
            for (Map.Entry<String, DetailEntry> e : detailCache.entrySet())
                if (now - e.getValue().at > DETAIL_TTL_MS) detailCache.remove(e.getKey());
            if (detailCache.size() >= DETAIL_CACHE_MAX) {
                // 还是满的：清掉最老的一批，简单省事
                List<Map.Entry<String, DetailEntry>> ordered = new ArrayList<>(detailCache.entrySet());
                ordered.sort((a, b) -> Long.compare(a.getValue().at, b.getValue().at));
                for (int i = 0; i < ordered.size() / 4; i++) detailCache.remove(ordered.get(i).getKey());
            }
        }
        detailCache.put(k, new DetailEntry(json, now));
    }

    static Map<String, Object> detailCacheStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entries", detailCache.size());
        m.put("hits", detailHits.sum());
        m.put("miss", detailMiss.sum());
        m.put("ttlSec", DETAIL_TTL_MS / 1000);
        return m;
    }

    static SpiderPool pool = new SpiderPool();
    static {
        // 站点自带 jar 的解析器（下载 + 缓存）；必须在任何 pool.get(key) 之前设置
        pool.jarResolver = Hub::downloadJar;
    }
    /** /api/save 允许写的键（不白名单化会被塞任意垃圾，比如 {"bad json":null}）。 */
    static final java.util.Set<String> SAVE_KEYS = new java.util.HashSet<>(Arrays.asList(
            "configUrl", "embyPath", "embyBaseUrl", "outputRoot", "publicUrl", "spiderJar", "embyToken"));

    static ExecutorService workers = Executors.newFixedThreadPool(24);
    static UnixHttp unix;

    // ── 操作记录（扫描/添加/安装等），落盘到 PKGVAR/history.json ──
    static final java.util.List<Map<String, Object>> history =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    static final int HISTORY_MAX = 300;
    static Path historyFile;
    /** PKGVAR/data：允许本地配置文件读取的唯一位置。 */
    static Path dataDir;

    /** 记一条操作。 */
    static synchronized void record(String action, String target, boolean ok, String detail, long ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", System.currentTimeMillis());
        m.put("time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
        m.put("action", action);
        m.put("target", target == null ? "" : target);
        m.put("ok", ok);
        m.put("detail", detail == null ? "" : detail);
        m.put("ms", ms);
        history.add(0, m);                     // 最新的在前
        while (history.size() > HISTORY_MAX) history.remove(history.size() - 1);
        saveHistory();
    }

    static synchronized void loadHistory() {
        try {
            if (historyFile != null && Files.isRegularFile(historyFile)) {
                Object o = SpiderPool.Json.parse(Files.readString(historyFile, StandardCharsets.UTF_8));
                if (o instanceof List) {
                    history.clear();
                    for (Object x : (List<?>) o) if (x instanceof Map) history.add((Map<String, Object>) x);
                }
            }
        } catch (Exception e) {
            System.out.println("读取操作记录失败: " + e.getMessage());
        }
    }

    static synchronized void saveHistory() {
        try {
            if (historyFile != null) {
                Files.writeString(historyFile, SpiderPool.Json.stringify(history), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.out.println("保存操作记录失败: " + e.getMessage());
        }
    }
    static Path dataFile;
    static Map<String, Object> settings = new LinkedHashMap<>();
    static long started = System.currentTimeMillis();
    static Path pluginDll;      // TvBox.dll（安装包里带的）
    static Path uiDir;          // Web 界面目录
    static int tcpPort = 8098;  // Unix 前端的反代目标
    static boolean tcpOk = false;

    public static void main(String[] args) throws Exception {
        int port = 8098;
        String jarDir = null;
        String spiderJar = null;
        Path configFile = Path.of("hub.json");
        String socketPath = null;
        String hubPrefix = "";

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--config": configFile = Path.of(args[++i]); break;
                case "--spider-jar": spiderJar = args[++i]; break;
                case "--jar-dir": jarDir = args[++i]; break;
                case "--socket": socketPath = args[++i]; break;
                case "--plugin-dll": pluginDll = Path.of(args[++i]); break;
                case "--ui-dir": uiDir = Path.of(args[++i]); break;
                case "--prefix": hubPrefix = args[++i]; break;
                case "--help":
                    System.out.println("用法: java hub.Hub --port 8098 --config hub.json --spider-jar /path/classes.jar --jar-dir /path/lib");
                    return;
                default:
                    System.out.println("未知参数: " + a);
            }
        }

        tcpPort = port;
        if (uiDir == null && Files.isDirectory(Path.of("web"))) uiDir = Path.of("web");
        System.setProperty("hub.prefix", hubPrefix);

        dataFile = configFile;
    dataDir = (configFile.toAbsolutePath().getParent() == null ? Path.of("data")
            : configFile.toAbsolutePath().getParent().resolve("data"));
        historyFile = configFile.toAbsolutePath().getParent() == null
                ? Path.of("history.json")
                : configFile.toAbsolutePath().getParent().resolve("history.json");
        loadSettings();
        loadHistory();

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

        // TCP 绑定失败绝不能弄死进程：fnOS 的正门是 Unix socket，
        // 端口被占（比如残留实例）时如果直接抛异常退出，socket 也不会建，
        // 网关就会报 502 Bad Gateway。这里顺延端口，实在不行也继续跑。
        for (int i = 0; i < 10 && unix == null; i++) {
            int tryPort = port + i;
            try {
                // 只绑回环：插件和本机都在同一台机器上，走 127.0.0.1 足够。
                // 绑 0.0.0.0 会让局域网任何主机无鉴权调用全部 /api
                //（含 install/save/history clear），而本进程是 root。
                HttpServer server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), tryPort), 0);
                server.createContext("/", Hub::handle);
                startTvBoxFileServer();
                server.setExecutor(Executors.newFixedThreadPool(16));
                server.start();
                tcpPort = tryPort;
                tcpOk = true;
                System.out.println("TCP 监听: http://127.0.0.1:" + tryPort + "/（仅本机）" + (i > 0 ? "（端口顺延）" : ""));
                break;
            } catch (Exception e) {
                System.out.println("TCP 端口 " + tryPort + " 不可用: " + e.getMessage());
            }
        }
        if (!tcpOk) System.out.println("WARNING: TCP 全部端口绑定失败，仅 Unix socket 可用");

        // Unix socket 前端：fnOS 网关只认 /var/apps/<app>/target/<app>.sock
        // 实现方式是反向代理到本地 TCP 端口，避免重构路由
        if (socketPath != null) {
            try {
                final String prefix = hubPrefix;
                unix = new UnixHttp(Path.of(socketPath), (method, path, rawQuery, body, headers) -> {
                    String p = path;
                    String pxUsed = (headers == null) ? "" : headers.getOrDefault("x-hub-prefix-used", "");
                    if (!prefix.isEmpty() && p.startsWith(prefix)) {
                        p = p.substring(prefix.length());
                        if (p.isEmpty()) p = "/";
                        if (!p.startsWith("/")) p = "/" + p;
                    }
                    String url = "http://127.0.0.1:" + tcpPort + p
                            + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery);
                    return proxy(method, url, body, headers, pxUsed);
                });
                unix.start();
                System.out.println("Unix socket 监听: " + socketPath + "  prefix=" + prefix);
            } catch (Exception e) {
                System.out.println("Unix socket 监听失败: " + e.getMessage());
            }
        }

        System.out.println("spider-hub 已启动");
    }

    // ───────────────────────── 路由 ─────────────────────────

    static void handle(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        try {
            if (path.startsWith("/api/") && !apiWriteAllowed(ex, path)) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("ok", false);
                err.put("msg", "缺少令牌或请求不合法（CSRF 防护）");
                sendJson(ex, "OPTIONS".equalsIgnoreCase(ex.getRequestMethod()) ? 403 : 401, err);
                return;
            }
            // 静态资源（hls.min.js 等）：只放行白名单扩展名，且不允许跳出 uiDir
            if (path.length() > 1 && uiDir != null) {
                String lower = path.toLowerCase();
                boolean asset = lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".png")
                        || lower.endsWith(".jpg") || lower.endsWith(".webp") || lower.endsWith(".svg")
                        || lower.endsWith(".woff2") || lower.endsWith(".map");
                if (asset) {
                    try {
                        Path f = uiDir.resolve(path.substring(1)).normalize();
                        if (f.startsWith(uiDir) && Files.isRegularFile(f)) {
                            String ct = lower.endsWith(".css") ? "text/css; charset=utf-8"
                                    : lower.endsWith(".js") ? "application/javascript; charset=utf-8"
                                    : lower.endsWith(".svg") ? "image/svg+xml"
                                    : lower.endsWith(".woff2") ? "font/woff2"
                                    : "image/" + (lower.endsWith(".png") ? "png" : lower.endsWith(".webp") ? "webp" : "jpeg");
                            byte[] bytes = Files.readAllBytes(f);
                            ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
                            sendBytes(ex, 200, ct, bytes);
                            return;
                        }
                    } catch (Exception ignore) { }
                }
            }
            if ("/".equals(path) || "/index.html".equals(path)) {
                // 不能缓存：页面里带着本次启动的令牌，旧页面会让所有写操作被 CSRF 闸门拒掉
                ex.getResponseHeaders().set("Cache-Control", "no-store, must-revalidate");
                ex.getResponseHeaders().set("Pragma", "no-cache");
                send(ex, 200, "text/html; charset=utf-8", ui());
                return;
            }
            if (path.startsWith("/api/warm")) {
                handleWarm(ex);
                return;
            }
            if (path.startsWith("/play/")) {
                handlePlay(ex, path);
                return;
            }
            if (path.startsWith("/live/")) {
                handleLive(ex, path);
                return;
            }
            if (path.startsWith("/v/")) {
                // 记一条请求行：插件跟 curl 拿到不同结果时，靠这个对齐请求差异
                System.out.println("[cms] " + ex.getRequestURI().getRawPath()
                        + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery())
                        + "  ua=" + ex.getRequestHeaders().getFirst("User-Agent"));
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
                // 不再把堆栈回给调用方：里面有内部路径/类名，排障看 info.log 就够
                System.out.println("[500] " + path + " → " + e);
                send(ex, 500, "text/plain; charset=utf-8", "ERROR: " + explain(e));
            } catch (Exception ignore) {
            }
        } finally {
            ex.close();
        }
    }

    /** 需要令牌才能调的破坏性端点（都是界面/插件主动触发、且会改磁盘或换代码的操作）。 */
    static final java.util.Set<String> DESTRUCTIVE_APIS = java.util.Set.of(
            "/api/emby/install", "/api/emby/uninstall", "/api/emby/prepare-output",
            "/api/emby/probe-as-emby", "/api/save", "/api/reload", "/api/cache/clear");

    /** 本地令牌：首次启动生成，注入到页面 <meta>，用于挡住「本机别的进程」和跨站 CSRF。 */
    static String localToken() {
        Object v = settings.get("localToken");
        if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        StringBuilder sb = new StringBuilder();
        for (byte b : java.security.SecureRandom.getSeed(24)) sb.append(String.format("%02x", b));
        String t = sb.toString();
        settings.put("localToken", t);
        saveSettings();
        return t;
    }

    static boolean tokenMatches(String given) {
        if (given == null || given.isBlank()) return false;
        String g = given.trim();
        String local = localToken();
        Object plugin = settings.get("embyToken");
        boolean ok = constantTimeEquals(g, local)
                || (plugin != null && constantTimeEquals(g, String.valueOf(plugin)));
        return ok;
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int d = 0;
        for (int i = 0; i < x.length; i++) d |= x[i] ^ y[i];
        return d == 0;
    }

    /**
     * 写操作的准入闸门。
     *
     * 威胁模型：① 管理员的浏览器打开恶意网页 → 跨站简单请求打 /api/*（无 preflight）；
     * ② 本机其他用户/进程直接打 127.0.0.1:8098；③ 网关之后的正常调用。
     * 对策：OPTIONS 直接 403；写请求必须是 application/json（不属于 CORS 简单请求，
     * 浏览器必须先 preflight，而 ACAO 已删 → 真请求发不出去）；破坏性端点再要令牌。
     * 网关注入的 X-Trim-Userid 说明 fnOS 已经认证过这位用户，放行。
     */
    /**
     * 同源证明：浏览器自己发的同源请求可以通过，跨站伪造的过不来。
     * <p>Sec-Fetch-Site 是「禁止修改的请求头」，脚本无法伪造；跨站表单 POST 会带
     * 外部 Origin，对不上就拒。这样即使页面拿不到令牌（老页面被缓存、或走网关时
     * 网关没透传 X-Trim-Userid），用户在界面上点按钮也不会被自己拦下来。
     */
    static boolean sameOriginProof(HttpExchange ex) {
        try {
            var hd = ex.getRequestHeaders();
            String sfs = hd.getFirst("Sec-Fetch-Site");
            if (sfs != null) {
                String v = sfs.trim().toLowerCase();
                if (v.equals("same-origin") || v.equals("none")) return true;
                if (v.equals("cross-site")) return false;
            }
            // ① Referer 带我们自己的页面路径：只有本站页面会这么发。
            //    跨站页面伪造不出来（它的 Referer 是它自己的地址）。
            //    fnOS 网关会把 Host 改写成 localhost，所以「Origin == Host」在网关后面永远不成立，
            //    这条路径检查才是网关场景真正可用的同源证明。
            String ref = hd.getFirst("Referer");
            if (ref != null) {
                String r = ref.trim();
                if (r.contains("/app/tvboxhub/") || r.endsWith("/app/tvboxhub")) return true;
            }
            // ② 直连 TCP（不经网关）时 Host 就是本站，Origin/Host 比对成立
            String host = hd.getFirst("Host");
            if (host == null || host.isBlank()) return false;
            for (String hn : new String[]{"Origin", "X-Forwarded-Host"}) {
                String raw = hd.getFirst(hn);
                if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("null")) continue;
                try {
                    String h = raw.contains("://") ? java.net.URI.create(raw.trim()).getAuthority() : raw.trim();
                    if (h != null && h.equalsIgnoreCase(host.trim())) return true;
                } catch (Exception ignore) { }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean apiWriteAllowed(HttpExchange ex, String path) {
        String method = ex.getRequestMethod() == null ? "GET" : ex.getRequestMethod().toUpperCase();
        if (method.equals("OPTIONS")) return false;
        String given = ex.getRequestHeaders().getFirst("X-TvBox-Token");
        if (given == null) {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            given = q.get("token");
        }
        boolean viaGateway = ex.getRequestHeaders().getFirst("X-Trim-Userid") != null;
        boolean tokenOk = tokenMatches(given);
        boolean sameOrigin = sameOriginProof(ex);
        boolean destructive = DESTRUCTIVE_APIS.contains(path);

        boolean allowed;
        if (method.equals("GET") || method.equals("HEAD")) {
            allowed = !destructive || viaGateway || tokenOk || sameOrigin;
        } else if (destructive || viaGateway || tokenOk || sameOrigin) {
            allowed = destructive ? (viaGateway || tokenOk || sameOrigin) : true;
        } else {
            // 既没有令牌、也没网关头、又拿不到同源证明：只接受 JSON POST
            //（跨站表单只能发 urlencoded/multipart/纯文本，发不出 application/json）
            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            allowed = ct != null && ct.toLowerCase().contains("application/json");
        }
        if (!allowed) {
            // 打一行原因，下次「按钮点了报 CSRF」能直接看出是哪一项没过
            System.out.println("[auth] 拒绝 " + method + " " + path
                    + " token=" + (given == null ? "无" : (tokenOk ? "对" : "错"))
                    + " gateway=" + viaGateway
                    + " sameOrigin=" + sameOrigin
                    + " ct=" + ex.getRequestHeaders().getFirst("Content-Type")
                    + " sfs=" + ex.getRequestHeaders().getFirst("Sec-Fetch-Site")
                    + " origin=" + ex.getRequestHeaders().getFirst("Origin")
                    + " referer=" + ex.getRequestHeaders().getFirst("Referer")
                    + " host=" + ex.getRequestHeaders().getFirst("Host"));
        }
        return allowed;
    }

    static void handleApi(HttpExchange ex, String path) throws Exception {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        switch (path) {
            case "/api/sites/fail": {        // 插件同步时上报「这个站又失败了」
                String body = readBody(ex);
                Object ob = SpiderPool.Json.parse(body);
                if (!(ob instanceof Map)) { sendJson(ex, 200, fail("请求体必须是 JSON 对象")); return; }
                String key = str(((Map<?, ?>) ob).get("key"));
                if (key == null || key.isBlank()) { sendJson(ex, 200, fail("缺少 key")); return; }
                int n = noteSiteFailure(key);
                clearProbeOnFail(key);
                maybeAutoRemove(key, n);
                Map<String, Object> m = ok("已记录失败 " + n + " 次");
                m.put("key", key);
                m.put("fails", n);
                sendJson(ex, 200, m);
                return;
            }
            case "/api/sites/ok": {          // 插件同步成功后再报一次（清计数）
                String body = readBody(ex);
                Object ob = SpiderPool.Json.parse(body);
                if (ob instanceof Map) {
                    String key = str(((Map<?, ?>) ob).get("key"));
                    if (key != null && !key.isBlank()) clearSiteFailure(key);
                }
                sendJson(ex, 200, ok(""));
                return;
            }
            case "/api/sites/sync": {       // 勾选/取消「同步这个站点」
                String body = readBody(ex);
                Object ob = SpiderPool.Json.parse(body);
                if (!(ob instanceof Map)) { sendJson(ex, 200, fail("请求体必须是 JSON 对象")); return; }
                Map<?, ?> in = (Map<?, ?>) ob;

                java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(syncSiteKeys());
                Object keysObj = in.get("keys");
                java.util.List<String> targets = new ArrayList<>();
                if (keysObj instanceof List) {
                    for (Object k : (List<?>) keysObj) if (k != null) targets.add(String.valueOf(k));
                } else if (in.get("key") != null) {
                    targets.add(String.valueOf(in.get("key")));
                }
                boolean on = Boolean.TRUE.equals(in.get("sync"))
                        || "1".equals(str(in.get("sync"))) || "true".equalsIgnoreCase(str(in.get("sync")));

                for (String k : targets) {
                    if (on) set.add(k); else set.remove(k);
                }
                settings.put("syncSites", new ArrayList<>(set));
                saveSettings();
                record("同步站点选择", (on ? "加入 " : "移出 ") + targets.size() + " 个", true, "", 0);
                Map<String, Object> m = ok((on ? "已加入同步 " : "已移出同步 ") + targets.size() + " 个站点，共 " + set.size() + " 个");
                m.put("sites", new ArrayList<>(set));
                sendJson(ex, 200, m);
                return;
            }
            case "/api/prefs": {   // 界面偏好（主题等）：存服务端，localStorage 不可用时也能记住
                String body = readBody(ex);
                if (body != null && !body.isBlank() && !"null".equals(body.trim())) {
                    Object ob = SpiderPool.Json.parse(body);
                    if (ob instanceof Map) {
                        Object cur = settings.get("prefs");
                        Map<String, Object> prefs = (cur instanceof Map)
                                ? new LinkedHashMap<>((Map<String, Object>) cur) : new LinkedHashMap<>();
                        prefs.putAll((Map<String, Object>) ob);
                        settings.put("prefs", prefs);
                        saveSettings();
                    }
                }
                Object cur2 = settings.get("prefs");
                Map<String, Object> out = ok("");
                out.put("prefs", (cur2 instanceof Map) ? cur2 : new LinkedHashMap<String, Object>());
                sendJson(ex, 200, out);
                return;
            }
            case "/api/sync-sites": {       // 插件用：拿用户勾选的站点
                java.util.Set<String> set = syncSiteKeys();
                Map<String, Object> m = ok("");
                m.put("sites", new ArrayList<>(set));
                m.put("count", set.size());
                sendJson(ex, 200, m);
                return;
            }
            case "/api/netdisk": {          // 是否允许网盘类源（默认关）
                String v = q.get("allow");
                if (v != null) {
                    pool.allowNetdisk = "1".equals(v) || "true".equalsIgnoreCase(v);
                    record("网盘源开关", pool.allowNetdisk ? "开启" : "关闭", true, "", 0);
                }
                Map<String, Object> m = ok("");
                m.put("allowNetdisk", pool.allowNetdisk);
                m.put("netdiskCount", pool.netdiskCount);
                sendJson(ex, 200, m);
                return;
            }
            case "/api/config": {           // 配置镜像：插件可达就读插件，否则用本地缓存
                Map<String, Object> pcfg = pluginConfig();
                Map<String, Object> m = new LinkedHashMap<>();
                if (pcfg != null) {
                    ensureHubUrlInPlugin(pcfg);
                    m.put("ok", true);
                    m.put("source", "plugin");
                    m.put("sourceLabel", "Emby 插件（TvBox.xml）");
                    m.put("config", pcfg);
                    // 顺手把本地缓存对齐，hub 自己加载配置时用同一份
                    String cu = str(pcfg.get("ConfigUrl"));
                    if (cu != null && !cu.isEmpty() && !cu.equals(str(settings.get("configUrl")))) {
                        settings.put("configUrl", cu);
                        saveSettings();
                    }
                    String eb = str(pcfg.get("EmbyBaseUrl"));
                    if (eb != null && !eb.isEmpty()) settings.put("embyBaseUrl", eb);
                } else {
                    m.put("ok", true);
                    m.put("source", "local");
                    m.put("sourceLabel", "本地缓存（插件未连接）");
                    Map<String, Object> local = new LinkedHashMap<>();
                    local.put("ConfigUrl", str(settings.get("configUrl")));
                    local.put("EmbyBaseUrl", str(settings.get("embyBaseUrl")));
                    local.put("OutputRoot", str(settings.get("outputRoot")));
                    m.put("config", local);
                    m.put("hint", "Emby 插件不可达，这里显示的是 hub 自己的缓存。装好插件（Emby 插件标签）后会自动共用插件配置。");
                }
                sendJson(ex, 200, m);
                return;
            }
            case "/api/status": {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("uptimeSec", (System.currentTimeMillis() - started) / 1000);
                m.put("configUrl", str(settings.get("configUrl")));
                m.put("spiderJar", str(settings.get("spiderJar")));
                m.put("stats", pool.stats());
                m.put("pool", pool.instanceStats());
                m.put("detailCache", detailCacheStats());
                m.put("loadErrors", pool.loadErrors());
                m.put("javaVersion", System.getProperty("java.version"));
                m.put("osArch", System.getProperty("os.arch"));
                m.put("jreHome", System.getProperty("java.home"));
                m.put("tcpPort", tcpPort);
                m.put("tcpOk", tcpOk);
                m.put("pluginDll", pluginDll == null ? null
                        : (Files.isRegularFile(pluginDll) ? pluginDll.toString() : "缺失"));
                m.put("historyCount", history.size());
                sendJson(ex, 200, m);
                return;
            }
            case "/api/sites": {
                boolean withNetdisk = "1".equals(q.getOrDefault("netdisk", "0"));
                List<Object> list = new ArrayList<>();
                int hidden = 0;
                java.util.Set<String> picked = syncSiteKeys();
                for (SpiderPool.Site s : pool.allSites()) {
                    if (s.netdisk && !withNetdisk) { hidden++; continue; }
                    Map<String, Object> row = s.toMap();
                    row.put("sync", picked.contains(s.key));
                    list.add(row);
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("total", list.size());
                m.put("sites", list);
                m.put("netdiskExcluded", hidden);     // 被排除的网盘类源数量
                if (withNetdisk) m.put("netdiskIncluded", true);
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
                    return categoryFallback(s, tid, pg);
                }, 60);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("raw", raw);
                m.put("parsed", tryParse(raw));
                sendJson(ex, 200, m);
                return;
            }
            case "/api/stream": {   // 同源播放代理：m3u8 重写分片地址，其它类型直通字节
                String key = q.getOrDefault("site", "").trim();
                String vid = q.getOrDefault("id", "").trim();
                int epIdx = 0;
                try { epIdx = Integer.parseInt(q.getOrDefault("ep", "0")); } catch (Exception ignore) { }
                String wantFlag = q.getOrDefault("flag", "").trim();
                if (key.isEmpty() || vid.isEmpty()) { sendText(ex, 400, "缺少 site / id"); return; }
                try {
                    String[] picked = pickEpisode(key, vid, wantFlag, epIdx);
                    if (picked == null) { sendText(ex, 404, "这条没有播放地址"); return; }
                    String flag = picked[0], epId = picked[1];
                    String[] r = resolveEpisode(key, flag, epId);
                    if (!"1".equals(r[0])) { sendText(ex, 502, "解析失败: " + r[2]); return; }
                    String real = r[1];
                    String hdr = (r.length > 4) ? r[4] : null;
                    String base = basePath(ex);     // 例如 /app/tvboxhub
                    ex.getResponseHeaders().set("X-Accel-Buffering", "no");   // 别让 nginx 攒着不发
                    if (isM3u8(real)) {
                        String body = new String(httpGetBytes(real, hdr, null).body(), StandardCharsets.UTF_8);
                        String rewritten = rewritePlaylist(body, real, hdr, base);
                        sendBytes(ex, 200, "application/vnd.apple.mpegurl; charset=utf-8", rewritten.getBytes(StandardCharsets.UTF_8));
                    } else {
                        // mp4/ts 之类：直通（带 Range），省得浏览器 CORS 或 Referer 挡
                        java.net.http.HttpResponse<byte[]> resp = httpGetBytes(real, hdr,
                                ex.getRequestHeaders().getFirst("Range"));
                        String ct = resp.headers().firstValue("Content-Type").orElse("application/octet-stream");
                        String cr = resp.headers().firstValue("Content-Range").orElse(null);
                        if (cr != null) ex.getResponseHeaders().set("Content-Range", cr);
                        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
                        sendBytes(ex, resp.statusCode() == 206 ? 206 : 200, ct, resp.body());
                    }
                } catch (Exception e) {
                    sendText(ex, 502, "代理失败: " + explain(e));
                }
                return;
            }
            case "/api/stream/seg": {   // 分片代理（m3u8 里重写出来的地址）
                String u = unb64url(q.getOrDefault("u", ""));
                String hdr = q.containsKey("h") ? unb64url(q.get("h")) : null;
                if (u == null || u.isBlank()) { sendText(ex, 400, "缺少 u"); return; }
                try {
                    ex.getResponseHeaders().set("X-Accel-Buffering", "no");
                    java.net.http.HttpResponse<byte[]> resp = httpGetBytes(u, hdr,
                            ex.getRequestHeaders().getFirst("Range"));
                    String ct = resp.headers().firstValue("Content-Type").orElse("application/octet-stream");
                    String cr = resp.headers().firstValue("Content-Range").orElse(null);
                    if (cr != null) ex.getResponseHeaders().set("Content-Range", cr);
                    ex.getResponseHeaders().set("Accept-Ranges", "bytes");
                    ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
                    sendBytes(ex, resp.statusCode() == 206 ? 206 : 200, ct, resp.body());
                } catch (Exception e) {
                    sendText(ex, 502, "分片代理失败: " + explain(e));
                }
                return;
            }
            case "/api/go": {           // 302 到真实地址（给 VLC/IINA 这类外部播放器用）
                String key = q.getOrDefault("site", "").trim();
                String vid = q.getOrDefault("id", "").trim();
                int epIdx = 0;
                try { epIdx = Integer.parseInt(q.getOrDefault("ep", "0")); } catch (Exception ignore) { }
                String wantFlag = q.getOrDefault("flag", "").trim();
                try {
                    String[] picked = pickEpisode(key, vid, wantFlag, epIdx);
                    if (picked == null) { sendText(ex, 404, "这条没有播放地址"); return; }
                    String[] r = resolveEpisode(key, picked[0], picked[1]);
                    if (!"1".equals(r[0])) { sendText(ex, 502, "解析失败: " + r[2]); return; }
                    ex.getResponseHeaders().set("Location", r[1]);
                    ex.getResponseHeaders().set("Cache-Control", "no-store");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                } catch (Exception e) {
                    sendText(ex, 502, "解析失败: " + explain(e));
                }
                return;
            }
            case "/api/play-resolve": {   // 详情页「播放」：挑线路/集 → playerContent 解出真地址 → 给浏览器可播的地址
                String key = q.getOrDefault("site", "").trim();
                String vid = q.getOrDefault("id", "").trim();
                int epIdx = 0;
                try { epIdx = Integer.parseInt(q.getOrDefault("ep", "0")); } catch (Exception ignore) { }
                String wantFlag = q.getOrDefault("flag", "").trim();
                if (key.isEmpty() || vid.isEmpty()) { sendJson(ex, 200, fail("缺少 site / id")); return; }
                final String vidf = vid;
                try {
                    // 1) 拉详情，拿到所有线路 × 分集
                    String draw = call(key, () -> pool.get(key).detailContent(java.util.Collections.singletonList(vidf)), 60);
                    Map<?, ?> d = firstItem(tryParse(draw));
                    if (d == null) { sendJson(ex, 200, fail("这个站点没返回详情")); return; }

                    java.util.List<Object> linesOut = new java.util.ArrayList<>();
                    String chosenFlag = null, chosenId = null, chosenEp = null;
                    String from = str0(d.get("vod_play_from"));
                    String urls = str0(d.get("vod_play_url"));
                    String[] froms = from.split("\\$\\$\\$");
                    String[] groups = urls.split("\\$\\$\\$");
                    for (int i = 0; i < froms.length; i++) {
                        String g = i < groups.length ? groups[i] : "";
                        java.util.List<Object> eps = new java.util.ArrayList<>();
                        for (String seg : g.split("#")) {
                            if (seg.isBlank()) continue;
                            String epName = seg, epId = seg;
                            int cut = seg.lastIndexOf('$');
                            if (cut >= 0) { epName = seg.substring(0, cut); epId = seg.substring(cut + 1); }
                            Map<String, Object> e1 = new LinkedHashMap<>();
                            e1.put("name", epName);
                            e1.put("id", epId);
                            eps.add(e1);
                        }
                        if (eps.isEmpty()) continue;
                        String lineName = froms[i].isBlank() ? ("线路" + (i + 1)) : froms[i];
                        Map<String, Object> line = new LinkedHashMap<>();
                        line.put("name", lineName);
                        line.put("episodes", eps);
                        linesOut.add(line);
                        if (chosenFlag == null || (!wantFlag.isEmpty() && wantFlag.equals(lineName))) {
                            if (chosenFlag == null || wantFlag.equals(lineName)) {
                                chosenFlag = lineName;
                                int pick = Math.min(Math.max(0, epIdx), eps.size() - 1);
                                Map<?, ?> pe = (Map<?, ?>) eps.get(pick);
                                chosenId = str0(pe.get("id"));
                                chosenEp = str0(pe.get("name"));
                            }
                        }
                    }
                    if (chosenFlag == null) { sendJson(ex, 200, fail("这条没有播放地址")); return; }

                    // 2) 交给 playerContent 换真地址（跟 /play/ 同一条逻辑）
                    String[] r = resolveEpisode(key, chosenFlag, chosenId);
                    String emby = browserEmbyBase(ex);
                    Map<String, Object> out = new LinkedHashMap<>();
                    boolean ok = "1".equals(r[0]);
                    out.put("ok", ok);
                    out.put("msg", r[2]);
                    out.put("parse", r[3]);
                    out.put("site", key);
                    out.put("id", vid);
                    out.put("flag", chosenFlag);
                    out.put("episode", chosenEp);
                    out.put("lines", linesOut);
                    if (ok && emby != null) {
                        // 走插件代理：浏览器直接拉 CDN 多半被 CORS/Referer 挡住，
                        // 用 /TvBox/Stream 转发（顺带带上源要求的 header）
                        StringBuilder su = new StringBuilder(emby).append("/TvBox/Stream?u=")
                                .append(b64url(r[1]));
                        if (r.length > 4 && r[4] != null) su.append("&h=").append(b64url(r[4]));
                        out.put("url", r[1]);
                        out.put("streamUrl", su.toString());
                    } else if (ok) {
                        out.put("url", r[1]);
                        out.put("streamUrl", r[1]);
                    }
                    sendJson(ex, 200, out);
                } catch (Exception e) {
                    sendJson(ex, 200, fail("解析播放地址失败: " + explain(e)));
                }
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
                Object parsedDetail = tryParse(raw);
                m.put("ok", true);
                m.put("raw", raw);
                m.put("parsed", parsedDetail);
                // 归一化一份给 UI 用（搜索页点开看详情）
                Map<?, ?> d = null;
                if (parsedDetail instanceof Map) {
                    Object lst = ((Map<?, ?>) parsedDetail).get("list");
                    if (lst instanceof java.util.List && !((java.util.List<?>) lst).isEmpty()) {
                        Object o = ((java.util.List<?>) lst).get(0);
                        if (o instanceof Map) d = (Map<?, ?>) o;
                    }
                }
                if (d != null) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", str0(d.get("vod_name")));
                    item.put("year", str0(d.get("vod_year")));
                    item.put("area", str0(d.get("vod_area")));
                    item.put("type", str0(d.get("type_name")));
                    item.put("remarks", str0(d.get("vod_remarks")));
                    item.put("pic", str0(d.get("vod_pic")));
                    item.put("actor", str0(d.get("vod_actor")));
                    item.put("director", str0(d.get("vod_director")));
                    item.put("content", str0(d.get("vod_content")));
                    item.put("lang", str0(d.get("vod_lang")));
                    item.put("score", str0(d.get("vod_douban_score")));
                    item.put("updated", str0(d.get("vod_time")));
                    java.util.List<Object> lines = new java.util.ArrayList<>();
                    String from = str0(d.get("vod_play_from"));
                    String urls2 = str0(d.get("vod_play_url"));
                    String[] froms = from.split("\\$\\$\\$");
                    String[] groups = urls2.split("\\$\\$\\$");
                    for (int i = 0; i < froms.length; i++) {
                        String g = i < groups.length ? groups[i] : "";
                        int cnt = 0;
                        for (String seg : g.split("#")) if (!seg.isBlank()) cnt++;
                        Map<String, Object> line = new LinkedHashMap<>();
                        line.put("name", froms[i].isBlank() ? ("线路" + (i + 1)) : froms[i]);
                        line.put("count", cnt);
                        lines.add(line);
                    }
                    item.put("lines", lines);
                    m.put("item", item);
                }
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
                Object o;
                try {
                    o = SpiderPool.Json.parse(body);
                } catch (Exception pe) {
                    sendJson(ex, 200, fail("请求体不是合法 JSON: " + pe.getMessage()));
                    return;
                }
                if (!(o instanceof Map)) {
                    sendJson(ex, 200, fail("请求体必须是 JSON 对象"));
                    return;
                }
                Map<?, ?> in = (Map<?, ?>) o;

                // hub 独有的键（插件不认识，只写本地）
                java.util.Set<String> LOCAL_KEYS = new java.util.HashSet<>(Arrays.asList(
                        "embyPath", "embyToken", "publicUrl", "spiderJar"));
                // hub 与插件共用的键：本地存一份缓存，同时推给插件（插件那份才是权威）
                java.util.Map<String, String> SHARED = new LinkedHashMap<>();
                SHARED.put("configUrl", "ConfigUrl");
                SHARED.put("outputRoot", "OutputRoot");
                SHARED.put("embyBaseUrl", "EmbyBaseUrl");

                List<String> ignoredKeys = new ArrayList<>();
                int localApplied = 0;
                Map<String, Object> pluginPayload = new LinkedHashMap<>();

                for (Map.Entry<?, ?> e : in.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    Object v = e.getValue();
                    if (LOCAL_KEYS.contains(k)) {
                        settings.put(k, v);
                        localApplied++;
                        continue;
                    }
                    // 共用键（小写形式）→ 既存本地缓存，也进插件负载
                    if (SHARED.containsKey(k)) {
                        settings.put(k, v);
                        localApplied++;
                        pluginPayload.put(SHARED.get(k), v);
                        continue;
                    }
                    // 插件原生键（首字母大写，如 ConfigUrl/MaxPagesPerCategory）
                    if (java.util.Arrays.asList(PLUGIN_CFG_KEYS).contains(k)) {
                        pluginPayload.put(k, v);
                        if (SHARED.containsValue(k)) settings.put(lowerFirst(k), v);
                        continue;
                    }
                    // 其余插件键用小写传进来也接受
                    String cap = Character.toUpperCase(k.charAt(0)) + k.substring(1);
                    if (java.util.Arrays.asList(PLUGIN_CFG_KEYS).contains(cap)) {
                        pluginPayload.put(cap, v);
                    } else {
                        ignoredKeys.add(k);
                    }
                }

                boolean pluginOk = false;
                String pluginMsg = null;
                if (!pluginPayload.isEmpty()) {
                    try {
                        if (embyBase() == null) throw new Exception("找不到 Emby 地址");
                        String resp = pushPluginConfig(pluginPayload);
                        Object ro = SpiderPool.Json.parse(resp);
                        boolean okResp = ro instanceof Map
                                && Boolean.TRUE.equals(((Map<?, ?>) ro).get("Success"));
                        pluginOk = okResp;
                        pluginMsg = ro instanceof Map ? str(((Map<?, ?>) ro).get("Message")) : resp;
                        if (!okResp) pluginMsg = pluginMsg == null ? resp : pluginMsg;
                    } catch (Exception pe) {
                        pluginMsg = "推给插件失败: " + (pe.getMessage() == null ? pe.getClass().getSimpleName() : pe.getMessage());
                    }
                }

                if (localApplied > 0) saveSettings();

                StringBuilder msg = new StringBuilder();
                if (localApplied > 0) msg.append("本地 ").append(localApplied).append(" 项");
                if (!pluginPayload.isEmpty()) {
                    msg.append(msg.length() > 0 ? "，" : "")
                       .append(pluginOk ? "插件已保存 " + pluginPayload.size() + " 项"
                                        : "插件未保存（" + (pluginMsg == null ? "不可达" : pluginMsg) + "）");
                }
                Map<String, Object> r = ok(msg.length() == 0 ? "没有可保存的字段" : msg.toString());
                r.put("pluginSaved", pluginOk);
                if (!pluginPayload.isEmpty() && !pluginOk) {
                    r.put("ok", false);
                    r.put("msg", "插件保存失败：" + (pluginMsg == null ? "Emby 不可达" : pluginMsg)
                            + "；本地缓存已更新，插件恢复后会以插件为准");
                }
                if (!ignoredKeys.isEmpty()) r.put("ignored", ignoredKeys);
                record("保存配置", str(settings.get("configUrl")), true, String.valueOf(r.get("msg")), 0);
                sendJson(ex, 200, r);
                return;
            }
            case "/api/reload": {
                String url = str(settings.get("configUrl"));
                if (url == null || url.isEmpty()) {
                    sendJson(ex, 200, fail("先设置配置地址"));
                    return;
                }
                long t0 = System.currentTimeMillis();
                try {
                    int n = reload(url);
                    long ms = System.currentTimeMillis() - t0;
                    Map<String, Object> okm = ok("已加载 " + n + " 个 spider 站点");
                    okm.put("via", lastFetchedVia);
                    okm.put("count", n);
                    okm.put("ms", ms);
                    record("扫描配置", url, n > 0, "站点 " + n + " 个 · 来源 " + briefVia(lastFetchedVia), ms);
                    sendJson(ex, 200, okm);
                } catch (Exception e) {
                    long ms = System.currentTimeMillis() - t0;
                    record("扫描配置", url, false, String.valueOf(e.getMessage()), ms);
                    Map<String, Object> fm = fail(String.valueOf(e.getMessage()));
                    fm.put("ms", ms);
                    sendJson(ex, 200, fm);
                }
                return;
            }
            case "/api/history": {
                // record() 是 static synchronized（类锁），这里必须拿同一把锁做快照，
                // 否则并发 add/remove 会让序列化过程中抛 ConcurrentModificationException
                List<Map<String, Object>> snapshot;
                synchronized (Hub.class) {
                    snapshot = new ArrayList<>(history);
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("total", snapshot.size());
                m.put("records", snapshot);
                sendJson(ex, 200, m);
                return;
            }
            case "/api/history/add": {   // 前端补记（如批量探测汇总）
                Map<String, String> req = parseForm(readBody(ex));
                record(req.getOrDefault("action", "操作"),
                        req.getOrDefault("target", ""),
                        !"0".equals(req.getOrDefault("ok", "1")),
                        req.getOrDefault("detail", ""),
                        (long) num(req.get("ms")));
                sendJson(ex, 200, ok("已记录"));
                return;
            }
            case "/api/history/clear": {
                history.clear();
                saveHistory();
                record("清空记录", "", true, "手动清空", 0);
                sendJson(ex, 200, ok("已清空"));
                return;
            }
            case "/api/probe": {         // 单站探测（可记录）
                String key = q.get("key");
                if (key == null || key.isBlank()) { sendJson(ex, 200, fail("缺少 key 参数")); return; }
                boolean rec = !"0".equals(q.getOrDefault("record", "1"));
                long t0 = System.currentTimeMillis();
                try {
                    String raw = call(key, () -> pool.get(key).homeContent(false), 45);
                    long ms = System.currentTimeMillis() - t0;
                    Object parsed = tryParse(raw);
                    int nClass = 0, nList = 0;
                    if (parsed instanceof Map) {
                        Object c = ((Map<?, ?>) parsed).get("class");
                        Object l = ((Map<?, ?>) parsed).get("list");
                        nClass = c instanceof List ? ((List<?>) c).size() : 0;
                        nList = l instanceof List ? ((List<?>) l).size() : 0;
                    }
                    if (rec) record("探测站点", key, nClass > 0 || nList > 0,
                            "分类 " + nClass + " / 列表 " + nList, ms);
                    pool.markData(key, nClass, nList);          // 有数据才算可用
                    rememberProbe(key, nClass, nList, null);    // 落盘，重启后还在
                    clearSiteFailure(key);                      // 探测成功 → 失败计数清零

                    // 有数据 ≠ 能播。实测有源「分类 8 个、列表 0 条」，
                    // 也有源「37 个条目全部返回 application/json（解析 key 失效）」。
                    // 只报"有数据"会让用户照着勾选，同步完是 0 个文件。
                    Boolean playable = null;
                    String playMsg = null;
                    if (nList > 0 || nClass > 0) {
                        try {
                            String[] pr = probePlayable(key, parsed);
                            playable = "1".equals(pr[0]);
                            playMsg = pr[1];
                            pool.markPlayable(key, playable, playMsg);
                            rememberPlayable(key, playable, playMsg);
                        } catch (Exception pe) {
                            playMsg = "可播性检测失败: " + explain(pe);
                            pool.markPlayable(key, null, playMsg);
                        }
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ok", true);
                    m.put("classes", nClass);
                    m.put("list", nList);
                    m.put("playable", playable);
                    m.put("playMsg", playMsg);
                    m.put("ms", ms);
                    m.put("parsed", parsed);
                    sendJson(ex, 200, m);
                } catch (Exception e) {
                    long ms = System.currentTimeMillis() - t0;
                    if (rec) record("探测站点", key, false, String.valueOf(e.getMessage()), ms);
                    pool.markData(key, 0, 0);   // 记下探测时间，界面才能区分「无数据」和「未测」
                    rememberProbe(key, 0, 0, String.valueOf(e.getMessage()));
                    // 连续失败达到阈值的死站自动移出同步名单，免得每轮同步都去撞墙
                    maybeAutoRemove(key, noteSiteFailure(key));
                    Map<String, Object> m = fail(String.valueOf(e.getMessage()));
                    m.put("ms", ms);
                    sendJson(ex, 200, m);
                }
                return;
            }
            // ── 同步控制：直接驱动 Emby 里的插件，Web 上就能看到进度 ──
            case "/api/emby/sync/status": {
                String base = embyBase();
                if (base == null) {
                    sendJson(ex, 200, fail("还没连上 Emby（先在「Emby 插件」页点一次检测）"));
                    return;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                try {
                    String body = embyHttp("GET", base + "/TvBox/Status?token=" + embyToken());
                    m.put("ok", true);
                    m.put("emby", base);
                    try {
                        m.put("status", SpiderPool.Json.parse(body));
                    } catch (Exception ignore) {
                        m.put("raw", body);
                    }
                } catch (Exception e) {
                    m.put("ok", false);
                    m.put("msg", "连不上 Emby: " + explain(e));
                }
                sendJson(ex, 200, m);
                return;
            }
            case "/api/search-all": {   // 关键词搜索（并发打所有同步站点）
                String wd = q.getOrDefault("wd", "").trim();
                if (wd.isEmpty()) { sendJson(ex, 200, fail("缺少搜索关键词")); return; }
                java.util.List<String> keys = new java.util.ArrayList<>(syncSiteKeys());
                String keysParam = q.get("keys");
                if (keysParam != null && !keysParam.isBlank()) {
                    keys.clear();
                    for (String k : keysParam.split(",")) if (!k.isBlank()) keys.add(k.trim());
                }
                if (keys.isEmpty()) { sendJson(ex, 200, fail("同步名单是空的，先在站点页勾选站点")); return; }
                final String kw = wd;
                java.util.concurrent.ExecutorService esp =
                        java.util.concurrent.Executors.newFixedThreadPool(Math.min(6, Math.max(1, keys.size())));
                java.util.List<java.util.concurrent.Future<Map<String, Object>>> futs = new java.util.ArrayList<>();
                for (final String k : keys) {
                    futs.add(esp.submit(() -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("site", k);
                        try {
                            String raw = call(k, () -> pool.get(k).searchContent(kw, false), 30);
                            Object parsed = tryParse(raw);
                            int n = 0;
                            if (parsed instanceof Map) {
                                Object lst = ((Map<?, ?>) parsed).get("list");
                                if (lst instanceof java.util.List) {
                                    for (Object o : (java.util.List<?>) lst) {
                                        if (!(o instanceof Map)) continue;
                                        Map<?, ?> it = (Map<?, ?>) o;
                                        Map<String, Object> one = new LinkedHashMap<>();
                                        one.put("site", k);
                                        one.put("id", str0(it.get("vod_id")));
                                        one.put("name", str0(it.get("vod_name")));
                                        one.put("year", str0(it.get("vod_year")));
                                        one.put("remarks", str0(it.get("vod_remarks")));
                                        one.put("pic", str0(it.get("vod_pic")));
                                        one.put("type", str0(it.get("type_name")));
                                        row.computeIfAbsent("items", x -> new java.util.ArrayList<Object>());
                                        ((java.util.List<Object>) row.get("items")).add(one);
                                        n++;
                                    }
                                }
                            }
                            row.put("count", n);
                            row.put("ok", true);
                        } catch (Exception e) {
                            row.put("ok", false);
                            row.put("count", 0);
                            row.put("msg", explain(e));
                        }
                        return row;
                    }));
                }
                java.util.List<Object> items = new java.util.ArrayList<>();
                java.util.List<Object> sitesOut = new java.util.ArrayList<>();
                for (java.util.concurrent.Future<Map<String, Object>> f : futs) {
                    try {
                        Map<String, Object> row = f.get(75, java.util.concurrent.TimeUnit.SECONDS);
                        Object its = row.remove("items");
                        sitesOut.add(row);
                        if (its instanceof java.util.List) items.addAll((java.util.List<?>) its);
                    } catch (Exception e) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("ok", false); row.put("msg", "超时/异常: " + explain(e));
                        sitesOut.add(row);
                    }
                }
                esp.shutdownNow();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("wd", wd);
                out.put("total", items.size());
                out.put("items", items);
                out.put("sites", sitesOut);
                sendJson(ex, 200, out);
                return;
            }
            case "/api/push": {   // 一键推送：把一条内容写进 Emby 库（由插件负责写 strm）
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { sendJson(ex, 200, fail("需要 POST")); return; }
                String bodyText = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Object parsedBody = tryParse(bodyText);
                String siteKey = null, itemId = null;
                if (parsedBody instanceof Map) {
                    siteKey = str(((Map<?, ?>) parsedBody).get("site"));
                    itemId = str(((Map<?, ?>) parsedBody).get("id"));
                }
                if (siteKey.isBlank() || itemId.isBlank()) { sendJson(ex, 200, fail("缺少 site / id")); return; }
                String base2 = embyBase();
                if (base2 == null) { sendJson(ex, 200, fail("还没连上 Emby")); return; }
                long t0 = System.currentTimeMillis();
                try {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("site", siteKey);
                    payload.put("id", itemId);
                    String body = embyHttpJson("POST", base2 + "/TvBox/PushItem?token=" + embyToken(),
                            SpiderPool.Json.stringify(payload), 240);
                    Object r = SpiderPool.Json.parse(body);
                    boolean ok = r instanceof Map && "true".equalsIgnoreCase(str(((Map<?, ?>) r).get("Ok")));
                    Map<String, Object> m2 = new LinkedHashMap<>();
                    m2.put("ok", ok);
                    m2.put("result", r);
                    record("推送条目", base2, ok, siteKey + " / " + itemId, System.currentTimeMillis() - t0);
                    sendJson(ex, 200, m2);
                } catch (Exception e) {
                    Map<String, Object> m2 = new LinkedHashMap<>();
                    m2.put("ok", false);
                    m2.put("msg", "推送失败: " + explain(e));
                    record("推送条目", base2, false, siteKey + " / " + itemId, System.currentTimeMillis() - t0);
                    sendJson(ex, 200, m2);
                }
                return;
            }
            case "/api/emby/sync": {
                String base = embyBase();
                if (base == null) { sendJson(ex, 200, fail("还没连上 Emby")); return; }
                long t0 = System.currentTimeMillis();
                Map<String, Object> m = new LinkedHashMap<>();
                // 先把同步名单里的 spider 全部实例化好再让插件开始。
                // 之前是后台线程预热，同步可能抢在预热完成前跑，
                // 插件拿到的是"初始化中"的实例，表现为整站「0 个条目」。
                // 注意：不能在这个请求线程里同步预热。
                // 6 个 spider 逐个初始化（每个 30~60 秒）会把 2 核机器打满，
                // Emby 那边连 /System/Info/Public 都答不上（实测黑 40 秒），
                // 用户看到的就是「同步点了没反应、停止也停不下来」。
                // 改成后台单线程低优先级预热，并把名单 pin 住不让 LRU 淘汰。
                final java.util.ArrayList<String> keysToWarm = new java.util.ArrayList<>(syncSiteKeys());
                if (!keysToWarm.isEmpty()) {
                    pool.pin(keysToWarm);
                    Thread t = new Thread(() -> {
                        for (String k : keysToWarm) {
                            try {
                                pool.warm(k);
                            } catch (Exception ignore) { }
                        }
                    }, "spider-warm");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    t.start();
                }
                m.put("warming", keysToWarm.size());
                try {
                    String body = embyHttp("POST", base + "/TvBox/Sync?token=" + embyToken());
                    Object parsed = SpiderPool.Json.parse(body);
                    m.put("ok", true);
                    m.put("emby", base);
                    m.put("result", parsed);
                    String msg = parsed instanceof Map ? str(((Map<?, ?>) parsed).get("Message")) : null;
                    record("开始同步", base, true, msg == null ? "已触发" : msg, System.currentTimeMillis() - t0);
                } catch (Exception e) {
                    m.put("ok", false);
                    m.put("msg", "触发失败: " + explain(e));
                    record("开始同步", base, false, str(m.get("msg")), System.currentTimeMillis() - t0);
                }
                sendJson(ex, 200, m);
                return;
            }
            case "/api/emby/prepare-output": {   // 以 root 建目录并把属主交给 Emby
                String probePath = q.get("path");
                if (probePath == null || probePath.isEmpty()) {
                    // 也接受 JSON body（前端用的是 POST body）
                    try {
                        Object ob = SpiderPool.Json.parse(readBody(ex));
                        if (ob instanceof Map) probePath = str(((Map<?, ?>) ob).get("path"));
                    } catch (Exception ignore) {
                    }
                }
                probePath = firstNonEmpty(probePath, str(settings.get("outputRoot")));
                if (probePath == null || probePath.isEmpty()) { sendJson(ex, 200, fail("缺少 path")); return; }
                Map<String, Object> m = EmbyManager.prepareOutputDir(probePath, currentEmbyPath());
                record("准备输出目录", probePath, Boolean.TRUE.equals(m.get("ok")), str(m.get("msg")), 0);
                sendJson(ex, 200, m);
                return;
            }
            case "/api/emby/probe-as-emby": {    // 以 Emby 身份实测（root su 过去）
                String pth = q.get("path");
                if (pth == null || pth.isEmpty()) {
                    try {
                        Object ob = SpiderPool.Json.parse(readBody(ex));
                        if (ob instanceof Map) pth = str(((Map<?, ?>) ob).get("path"));
                    } catch (Exception ignore) {
                    }
                }
                if (pth == null || pth.isEmpty()) { sendJson(ex, 200, fail("缺少 path")); return; }
                Map<String, Object> m = EmbyManager.probeAsEmby(pth, currentEmbyPath());
                sendJson(ex, 200, m);
                return;
            }
            case "/api/plugin/dll": {
                if (pluginDll == null || !Files.isRegularFile(pluginDll)) {
                    sendJson(ex, 200, fail("安装包里没有 TvBox.dll")); return;
                }
                byte[] bytes = Files.readAllBytes(pluginDll);
                ex.getResponseHeaders().set("X-Plugin-Sha256", sha256Hex(bytes));
                ex.getResponseHeaders().set("X-Plugin-Bytes", String.valueOf(bytes.length));
                ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
                ex.sendResponseHeaders(200, bytes.length);
                try (java.io.OutputStream os = ex.getResponseBody()) { os.write(bytes); }
                return;
            }
            case "/api/plugin/info": {
                Map<String, Object> m = new LinkedHashMap<>();
                if (pluginDll == null || !Files.isRegularFile(pluginDll)) {
                    m.put("ok", false);
                    m.put("msg", "安装包里没有 TvBox.dll");
                } else {
                    byte[] bytes = Files.readAllBytes(pluginDll);
                    m.put("ok", true);
                    m.put("path", pluginDll.toString());
                    m.put("bytes", bytes.length);
                    m.put("sha256", sha256Hex(bytes));
                    m.put("mtime", Files.getLastModifiedTime(pluginDll).toMillis());
                }
                sendJson(ex, 200, m);
                return;
            }
            case "/api/emby/self-update": {
                // 让插件自己下载→替换→重启。容器部署时这是唯一不用碰目录的升级方式。
                String base = embyBase();
                if (base == null) { sendJson(ex, 200, fail("还没连上 Emby")); return; }
                Map<String, String> req = parseForm(readBody(ex));
                String restart = firstNonEmpty(req.get("restart"), "1");
                String force = firstNonEmpty(req.get("force"), "0");
                String url = base + "/TvBox/SelfUpdate?token=" + embyToken()
                        + "&Restart=" + URLEncoder.encode(restart, StandardCharsets.UTF_8)
                        + "&Force=" + URLEncoder.encode(force, StandardCharsets.UTF_8);
                long t0 = System.currentTimeMillis();
                try {
                    String body = embyHttp("GET", url);
                    Object parsed = SpiderPool.Json.parse(body);
                    if (parsed instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) parsed;
                        // 插件返回的是 C# 命名（Ok/Msg），这里统一补一个小写 ok 给前端用，
                        // 否则 putIfAbsent 会因为键名不同而塞进 ok=false（看起来像失败）
                        Object okv = m.get("Ok") != null ? m.get("Ok") : m.get("ok");
                        m.put("ok", Boolean.TRUE.equals(okv) || "true".equalsIgnoreCase(String.valueOf(okv)));
                        // 插件自更新后本该自己重启，但 C# 侧不一定有权限（CanSelfRestart=false）。
                        // 没成功重启就由 hub 兜底重启，免得用户以为更新生效了其实没加载。
                        boolean pluginRestarted = "true".equalsIgnoreCase(String.valueOf(m.get("Restarting")))
                                && "true".equalsIgnoreCase(String.valueOf(m.get("CanSelfRestart")));
                        boolean updatedFlag = "true".equalsIgnoreCase(String.valueOf(m.get("Updated")));
                        // 「已是最新」不算更新：不该无谓地重启 Emby
                        if (Boolean.TRUE.equals(m.get("ok")) && updatedFlag && !pluginRestarted) {
                            final String pdR = str(settings.get("embyPath"));
                            final String baseR = embyBase();
                            Thread th = new Thread(() -> {
                                Map<String, Object> rr = EmbyManager.restartEmby(pdR, baseR);
                                record("重启 Emby", pdR, Boolean.TRUE.equals(rr.get("ok")), String.valueOf(rr.get("msg")), 0);
                                System.out.println("[restart] " + rr);
                                settings.remove("embyBaseUrl");
                                saveSettings();
                            }, "emby-restart-selfupdate");
                            th.setDaemon(true);
                            th.start();
                            m.put("restarting", true);
                        }
                        record("插件自更新", "via plugin", Boolean.TRUE.equals(m.get("ok")),
                                String.valueOf(m.get("msg")), System.currentTimeMillis() - t0);
                        sendJson(ex, 200, m);
                    } else {
                        sendJson(ex, 200, fail("插件返回了非 JSON: " + (body == null ? "" : body.length() > 200 ? body.substring(0, 200) : body)));
                    }
                } catch (Exception e) {
                    // 旧版插件没有这条路由 → 明确让用户先「安装插件」升级一次
                    record("插件自更新", "via plugin", false, e.getMessage(), System.currentTimeMillis() - t0);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ok", false);
                    m.put("needInstall", true);
                    m.put("msg", "当前插件还没有自更新能力（或 Emby 未重启加载新版）：" + explain(e)
                            + "。请先用上面的「安装插件」升级一次，之后就都能自更新了。");
                    sendJson(ex, 200, m);
                }
                return;
            }
            case "/api/emby/docker": {
                Map<String, Object> m = new LinkedHashMap<>();
                boolean av = DockerInstaller.available();
                m.put("ok", true);
                m.put("available", av);
                m.put("containers", av ? DockerInstaller.containers() : new ArrayList<>());
                m.put("hint", av ? "" : (DockerInstaller.unavailableWhy()
                        + "。容器部署时请把 TvBox.dll 手动拷进容器的 /config/plugins/，"
                        + "或让 hub 以 root 运行后重试"));
                sendJson(ex, 200, m);
                return;
            }
            case "/api/cache/clear": {
                int n = detailCache.size();
                detailCache.clear();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("cleared", n);
                sendJson(ex, 200, m);
                return;
            }
            case "/api/emby/write-check": {
                String base = embyBase();
                if (base == null) { sendJson(ex, 200, fail("还没连上 Emby")); return; }
                String probePath = q.getOrDefault("path", str(settings.get("outputRoot")));
                if (probePath == null || probePath.isEmpty()) { sendJson(ex, 200, fail("没给路径")); return; }
                Map<String, Object> m = new LinkedHashMap<>();
                try {
                    String body = embyHttp("GET", base + "/TvBox/ProbeWrite?token=" + embyToken()
                            + "&Path=" + URLEncoder.encode(probePath, StandardCharsets.UTF_8));
                    m.put("ok", true);
                    m.put("emby", base);
                    m.put("result", SpiderPool.Json.parse(body));
                } catch (Exception e) {
                    m.put("ok", false);
                    m.put("msg", "探测失败: " + explain(e));
                }
                sendJson(ex, 200, m);
                return;
            }
            case "/api/emby/config": {
                String base = embyBase();
                if (base == null) { sendJson(ex, 200, fail("还没连上 Emby")); return; }
                String body = readBody(ex);
                if (body == null || body.isBlank()) { sendJson(ex, 200, fail("缺少配置内容")); return; }
                long t0 = System.currentTimeMillis();
                Map<String, Object> m = new LinkedHashMap<>();
                try {
                    // body 里可能是 {"SiteWhitelist":...} 也可能已经是带 Token 的完整对象；
                    // 统一补一个 token 字段塞进去（Jackson 风格的简单拼装，不引库）。
                    String payload = body.trim();
                    if (payload.startsWith("{")) {
                        payload = payload.substring(0, 1)
                                + "\"Token\":\"" + embyToken() + "\"," + payload.substring(1);
                    }
                    String resp = embyPostJson(base + "/TvBox/Config", payload);
                    Object parsed = SpiderPool.Json.parse(resp);
                    m.put("ok", true);
                    m.put("result", parsed);
                    String msg = parsed instanceof Map ? str(((Map<?, ?>) parsed).get("Message")) : null;
                    boolean okFlag = !(parsed instanceof Map) || !Boolean.FALSE.equals(((Map<?, ?>) parsed).get("Success"));
                    record("保存同步范围", base, okFlag, msg == null ? "" : msg, System.currentTimeMillis() - t0);
                } catch (Exception e) {
                    m.put("ok", false);
                    m.put("msg", "保存失败: " + explain(e));
                }
                sendJson(ex, 200, m);
                return;
            }
            case "/api/emby/sync/stop": {
                String base = embyBase();
                if (base == null) { sendJson(ex, 200, fail("还没连上 Emby")); return; }
                long t0 = System.currentTimeMillis();
                Map<String, Object> m = new LinkedHashMap<>();
                try {
                    String body = embyHttp("POST", base + "/TvBox/Stop?token=" + embyToken());
                    m.put("ok", true);
                    m.put("result", SpiderPool.Json.parse(body));
                    record("停止同步", base, true, "已请求停止", System.currentTimeMillis() - t0);
                } catch (Exception e) {
                    m.put("ok", false);
                    m.put("msg", "停止失败: " + explain(e));
                }
                sendJson(ex, 200, m);
                return;
            }
            case "/api/emby/libraries": {
                String pd = q.getOrDefault("programdata", str(settings.get("embyPath")));
                if (pd == null || pd.isEmpty()) { sendJson(ex, 200, fail("还没选 Emby 目录")); return; }
                List<Object> dirs = new ArrayList<>();
                for (Map<String, Object> m : EmbyManager.detectLibraries(pd)) dirs.add(m);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("programdata", pd);
                m.put("total", dirs.size());
                m.put("dirs", dirs);
                sendJson(ex, 200, m);
                return;
            }
            case "/api/emby/detect": {
                List<Object> list = new ArrayList<>();
                for (EmbyManager.Instance i : EmbyManager.detect(str(settings.get("embyPath")))) {
                    list.add(i.toMap());
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("found", list.size());
                m.put("instances", list);
                m.put("dll", pluginDll == null ? null
                        : (Files.isRegularFile(pluginDll) ? pluginDll.toString() + " (" + Files.size(pluginDll) + " bytes)" : "缺失"));
                sendJson(ex, 200, m);
                return;
            }
            case "/api/emby/status": {
                String pd = q.getOrDefault("programdata", str(settings.get("embyPath")));
                if (pd == null || pd.isEmpty()) { sendJson(ex, 200, fail("还没选 Emby 目录")); return; }
                Map<String, Object> m = EmbyManager.status(pd);
                m.put("ok", true);
                m.put("programdata", pd);
                // 转码临时目录：Emby 缺这个目录时，凡是需要转码的播放都会报
                // 「Unable to find a suitable output format for .../transcoding-temp/xxx」
                m.put("transcodingTemp", embyTranscodingTemp(pd));
                // 实时探测（不走 embyBase() 的缓存），前端靠它判断「重启好了没」
                m.put("serverUp", EmbyManager.serverUpNow(null));
                sendJson(ex, 200, m);
                return;
            }
            case "/api/emby/install": {
                Map<String, String> req = parseForm(readBody(ex));
                String pd = firstNonEmpty(req.get("programdata"), str(settings.get("embyPath")));
                // 本地优先、失败自动转 Docker。pd 允许为空：容器部署时压根没有宿主 programdata。
                String container = firstNonEmpty(req.get("container"), str(settings.get("dockerContainer")));
                if (container != null && !container.isBlank()) {
                    boolean okName = container.matches("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$");
                    boolean known = false;
                    if (okName) {
                        for (java.util.Map<String, Object> c : DockerInstaller.containers()) {
                            Object nm = c.get("name");
                            if (nm != null && container.equals(String.valueOf(nm))) { known = true; break; }
                        }
                    }
                    // 以前 container 直接来自表单：可以 docker restart mysql 打断任何服务，
                    // 或把 DLL 拷进任意容器里以 root 执行命令。
                    if (!okName || !known) {
                        record("安装插件", container, false, "不是 Emby 容器", 0);
                        sendJson(ex, 200, fail("只能安装到检测到的 Emby 容器，拒绝: " + container));
                        return;
                    }
                }
                String useDocker = req.get("docker");
                boolean allowDocker = useDocker == null || !("0".equals(useDocker) || "false".equalsIgnoreCase(useDocker));
                if (pd != null && !pd.isEmpty()) {
                    // 必须是真的 Emby programdata：本进程以 root 跑，不校验就等于
                    // 给任何调用者一个「往任意目录写文件」的原语。
                    String why = EmbyManager.validateProgramdata(pd);
                // 只允许装到「本机真实存在的 Emby 实例」上。
                // 以前任意可写目录（/tmp、/home）都能过校验，等于给调用方一个
                // root 往任意目录投放 DLL + 含 HubToken 的 XML 的原语。
                    if (why == null) {
                        java.util.List<EmbyManager.Instance> found = EmbyManager.detect(null);
                        boolean known = false;
                        for (EmbyManager.Instance it : found) {
                            if (it != null && it.programdata != null && EmbyManager.samePath(it.programdata, pd)) {
                                known = true;
                                break;
                            }
                        }
                        if (!known) why = "不是本机检测到的 Emby 数据目录（拒绝安装到 " + pd + "）";
                    }
                    if (why != null && !allowDocker) {
                        record("安装插件", pd, false, why, 0);
                        sendJson(ex, 200, fail("拒绝安装: " + why));
                        return;
                    }
                } else if (!allowDocker) {
                    sendJson(ex, 200, fail("还没选 Emby 目录"));
                    return;
                }
                if (pluginDll == null || !Files.isRegularFile(pluginDll)) {
                    sendJson(ex, 200, fail("安装包里没有 TvBox.dll")); return;
                }
                long t0 = System.currentTimeMillis();
                Map<String, Object> r = EmbyManager.installSmart(
                        pd, Files.readAllBytes(pluginDll), buildPluginXml(req), container, allowDocker);
                long ms = System.currentTimeMillis() - t0;
                if (Boolean.TRUE.equals(r.get("ok"))) {
                    if (!"docker".equals(String.valueOf(r.get("method"))) && pd != null && !pd.isEmpty())
                        settings.put("embyPath", pd);
                    if ("docker".equals(String.valueOf(r.get("method"))))
                        settings.put("dockerContainer", r.get("container"));
                    settings.remove("embyBaseUrl");   // 让下次 embyBase() 重新探测
                    saveSettings();
                    embyBase();                        // 装完立刻探一次，后面的同步按钮就能用
                    // 本地安装：DLL 只有 Emby 重启后才加载，这里直接帮用户重启掉。
                    // 容器安装由 DockerInstaller 自己 docker restart，不用重复。
                    if (!"docker".equals(String.valueOf(r.get("method")))) {
                        final String pdR = pd;
                        final String baseR = embyBase();
                        Thread th = new Thread(() -> {
                            Map<String, Object> rr = EmbyManager.restartEmby(pdR, baseR);
                            record("重启 Emby", pdR, Boolean.TRUE.equals(rr.get("ok")), String.valueOf(rr.get("msg")), 0);
                            System.out.println("[restart] " + rr);
                            settings.remove("embyBaseUrl");
                            saveSettings();
                        }, "emby-restart");
                        th.setDaemon(true);
                        th.start();
                        r.put("restarting", true);
                        r.put("msg", String.valueOf(r.get("msg")) + "，正在自动重启 Emby（约 20~40 秒）");
                    }
                }
                record("安装插件", pd, Boolean.TRUE.equals(r.get("ok")), String.valueOf(r.get("msg")), ms);
                sendJson(ex, 200, r);
                return;
            }
            case "/api/emby/uninstall": {
                Map<String, String> req = parseForm(readBody(ex));
                String pd = firstNonEmpty(req.get("programdata"), str(settings.get("embyPath")));
                if (pd == null || pd.isEmpty()) { sendJson(ex, 200, fail("还没选 Emby 目录")); return; }
                // 必须是真的 Emby programdata：本进程以 root 跑，不校验就等于
                // 给任何调用者一个「往任意目录写文件」的原语。
                String why = EmbyManager.validateProgramdata(pd);
                if (why != null) {
                    record("安装插件", pd, false, why, 0);
                    sendJson(ex, 200, fail("拒绝安装: " + why));
                    return;
                }
                Map<String, Object> ru = EmbyManager.uninstall(pd);
                record("卸载插件", pd, Boolean.TRUE.equals(ru.get("ok")), String.valueOf(ru.get("msg")), 0);
                sendJson(ex, 200, ru);
                return;
            }
            default:
                send(ex, 404, "text/plain; charset=utf-8", "no such api");
        }
    }

    /** MacCMS 兼容接口：/v/<key>/api.php/provide/vod/?ac=... */
    /**
     * 有些 spider 必须带 filter=true 才肯吐数据（它们把 filter 当成"给我带筛选参数的结果"），
     * 而 TVBox 客户端在没选筛选条件时传的是 false。这里做两级兜底：
     *   (filter=false, extend={}) → 空就再来 (filter=true, extend={})
     * 两边都空才认为是真的没数据。
     */
    static String categoryFallback(Object spiderObj, String tid, String pg) {
        try {
            com.github.catvod.crawler.Spider sp = (com.github.catvod.crawler.Spider) spiderObj;
            String raw = sp.categoryContent(tid, pg, false, new HashMap<>());
            if (hasItems(raw)) return raw;
            String raw2 = sp.categoryContent(tid, pg, true, new HashMap<>());
            if (hasItems(raw2)) {
                System.out.println("[cms] filter=true 才拿到数据 tid=" + tid + " pg=" + pg);
                return raw2;
            }
            return raw;
        } catch (Exception e) {
            return null;
        }
    }

    /** 粗略判断 categoryContent 的 JSON 里有没有 list 内容。 */
    static boolean hasItems(String raw) {
        if (raw == null) return false;
        String compact = raw.replaceAll("\\s", "");
        int i = compact.indexOf("\"list\":[");
        if (i < 0) return false;
        int j = i + 8;
        return j < compact.length() && compact.charAt(j) != ']';
    }

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
                        // 逐个 id 调 detailContent 再合并。
                        // 为什么不能一次传多个：不少 spider 的 detailContent 里按自己约定的
                        // 分隔符拆 id，传多个会让它拿到长度对不上的数组直接崩。
                        // 实测 Sao火：单个 id → 22 集正常；传两个 id → ArrayIndexOutOfBoundsException。
                        String[] parts = ids.split(",");
                        StringBuilder merged = new StringBuilder();
                        int okCount = 0;
                        String lastErr = null;
                        for (String one : parts) {
                            if (one == null || one.isEmpty()) continue;
                            final String oneId = one;
                            final String ck = key + "|" + oneId;
                            try {
                                String cachedJson = detailCacheGet(ck);
                                if (cachedJson != null) {
                                    if (okCount > 0) merged.append(",");
                                    merged.append(cachedJson.substring(1, cachedJson.length() - 1));
                                    okCount++;
                                    continue;
                                }
                                String raw = call(key, () -> {
                                    Spider s = pool.get(key);
                                    return s.detailContent(new ArrayList<>(List.of(oneId)));
                                }, 60);
                                if (raw == null || raw.isBlank()) continue;
                                Object parsed = tryParse(raw);
                                Object lst = (parsed instanceof Map) ? ((Map<?, ?>) parsed).get("list") : null;
                                if (!(lst instanceof List) || ((List<?>) lst).isEmpty()) continue;
                                String oneJson = SpiderPool.Json.stringify(lst);
                                detailCachePut(ck, oneJson);
                                if (okCount > 0) merged.append(",");
                                merged.append(oneJson.substring(1, oneJson.length() - 1));  // 去掉外层 []
                                okCount++;
                            } catch (Exception e) {
                                lastErr = String.valueOf(e.getMessage());
                            }
                        }
                        if (okCount == 0) {
                            sendCms(ex, fail(lastErr == null ? "详情为空" : lastErr));
                            return;
                        }
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("code", 1);
                        m.put("msg", "数据列表");
                        m.put("page", 1);
                        m.put("pagecount", 1);
                        m.put("limit", "20");
                        m.put("total", okCount);
                        m.put("list", SpiderPool.Json.parse("[" + merged + "]"));
                        sendCms(ex, m);
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
                        return categoryFallback(s, tid, pg);
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
    /** 每个站点一把锁：catvod spider 实例内部有可变状态，多线程同时调必然互相踩。 */
    static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> KEY_LOCKS
            = new java.util.concurrent.ConcurrentHashMap<>();

    static String call(String key, Callable<String> task, int timeoutSec) throws Exception {
        // key 为空时 ConcurrentHashMap.get(null) 直接 NPE，报出来的是一句
        // "Cannot invoke Object.hashCode() because parameter1 is null"，完全看不出是缺参数。
        if (key == null || key.isBlank()) throw new Exception("缺少 site/key 参数");
        java.util.concurrent.locks.ReentrantLock lock =
                KEY_LOCKS.computeIfAbsent(String.valueOf(key), k -> new java.util.concurrent.locks.ReentrantLock());
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        // 锁只等 1/4 预算：锁 + 执行原本各等满 timeout，一个请求最坏要 2×timeout，
        // 16 个慢源并发就把 worker 池和 HTTP 线程一起拖死。
        long lockWait = Math.max(1, timeoutSec / 4);
        if (!lock.tryLock(lockWait, TimeUnit.SECONDS)) {
            throw new Exception("站点 " + key + " 正在处理中，请稍后重试");
        }
        try {
            long left = Math.max(1, (deadline - System.currentTimeMillis()) / 1000);
            return callUnlocked(key, task, (int) left);
        } finally {
            lock.unlock();
        }
    }

    static String callUnlocked(String key, Callable<String> task, int timeoutSec) throws Exception {
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
        if (url.startsWith("http://") || url.startsWith("https://")) {
            body = fetch(url);
        } else {
            // 本地文件只允许读 PKGVAR/data 下的内容。本进程是 root，
            // 原来这里裸 Path.of(url) 等于一个任意文件读取原语（/etc/shadow 都能读）。
            Path p = Path.of(url).toAbsolutePath().normalize();
            Path allowed = dataDir;                       // TRIM_PKGVAR/data
            if (allowed == null || !p.startsWith(allowed)) {
                throw new Exception("本地配置只允许放在 " + (allowed == null ? "<PKGVAR>/data" : allowed.toString()) + " 下");
            }
            if (!Files.isRegularFile(p)) throw new Exception("文件不存在: " + p);
            body = Files.readString(p, StandardCharsets.UTF_8);
        }
        body = body.trim();
        // 多仓：{urls:[...]} → 全部子仓展开合并（和 Emby 插件侧行为一致）
        body = expandMultiRepo(body);
        pool.loadFromConfig(body, url);
        System.out.println("已加载 " + pool.size() + " 个 spider 站点");
        restoreProbeState();
        pool.pin(syncSiteKeys());   // 同步名单常驻，避免冷启动撞插件超时
        return pool.size();
    }

    /** 上一次成功使用的地址（镜像回退后能看出走的是哪条）。 */
    static volatile String lastFetchedVia = null;

    /**
     * 取配置。国内直连 raw.githubusercontent.com 经常不通
     * （域名只返回 AAAA 记录，本机 IPv6 不通时一直挂到超时），所以直连失败自动走镜像。
     */
    // ═══════════ Emby 插件控制（同步触发 / 状态查询 / 停止）═══════════

    static final HttpClient EMBY_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * GET/POST /api/warm[?key=x]  预热 spider 实例。
     * 不带 key 就把同步名单里的站点全部预热并常驻，避免冷启动撞插件超时。
     */
    static void handleWarm(com.sun.net.httpserver.HttpExchange ex) {
        try {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            List<String> keys = new ArrayList<>();
            String one = q.get("key");
            if (one != null && !one.isBlank()) {
                keys.add(one);
            } else {
                keys.addAll(syncSiteKeys());
            }
            pool.pin(keys);
            final List<String> target = new ArrayList<>(keys);
            new Thread(() -> {
                long t0 = System.currentTimeMillis();
                for (String k : target) {
                    try { pool.warm(k); } catch (Exception ignore) {}
                }
                System.out.println("[warm] 预热 " + target.size() + " 个站点，用时 "
                        + (System.currentTimeMillis() - t0) + "ms");
            }, "warm").start();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("warmed", keys.size());
            m.put("pinned", pool.pinnedCount());
            sendJson(ex, 200, m);
        } catch (Exception e) {
            try { sendJson(ex, 500, Map.of("ok", false, "msg", explain(e))); } catch (Exception ignore) {}
        }
    }

    /** 探测可播性用的 HttpClient（短超时，带 UA）。 */
    static final HttpClient PROBE_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 记录可播性检测结果（落盘到 probe 同一行里）。 */
    static synchronized void rememberPlayable(String key, Boolean playable, String msg) {
        if (key == null || key.isBlank()) return;
        try {
            Object cur = settings.get("probe");
            Map<String, Object> probe = (cur instanceof Map)
                    ? (Map<String, Object>) cur : new LinkedHashMap<String, Object>();
            Object old = probe.get(key);
            Map<String, Object> row = (old instanceof Map)
                    ? (Map<String, Object>) old : new LinkedHashMap<String, Object>();
            row.put("p", playable == null ? null : (playable ? "1" : "0"));
            row.put("pm", msg == null ? null
                    : (msg.length() > 200 ? msg.substring(0, 200) : msg));
            row.put("pat", System.currentTimeMillis());
            probe.put(key, row);
            settings.put("probe", probe);
            saveSettings();
        } catch (Exception ignore) {
        }
    }

    /** 取一条详情，把第一条分集地址实际拉一次，判断这个源能不能播。 */
    static String[] probePlayable(String key, Object homeParsed) throws Exception {
        String vid = firstVodId(homeParsed);
        if (vid == null) {
            try {
                String raw = call(key, () -> pool.get(key).categoryContent("", "1", false, null), 45);
                vid = firstVodId(tryParse(raw));
            } catch (Exception ignore) {
            }
        }
        if (vid == null) return new String[]{"0", "列表里一条都没有，取不到详情"};

        final String oneId = vid;
        String raw = call(key, () -> pool.get(key).detailContent(new ArrayList<>(List.of(oneId))), 60);
        Object detailParsed = tryParse(raw);
        String playUrl = firstPlayUrl(detailParsed);
        if (playUrl == null || playUrl.isBlank()) return new String[]{"0", "详情里没有播放地址"};
        // 私有格式（146660@1314815 / CO4K_… / /play/xxx.html）不是 URL，
        // 要经 playerContent 才能换出真地址，TVBox 客户端就是这么做的。
        if (!(playUrl.startsWith("http://") || playUrl.startsWith("https://"))) {
            String flag = firstFlag(detailParsed);
            String[] r = resolveEpisode(key, flag, playUrl);
            if ("1".equals(r[0])) return fetchOnce(r[1]);
            return new String[]{"0", "私有格式需 playerContent 解析：" + r[2]};
        }
        return fetchOnce(playUrl);
    }

    private static String firstVodId(Object parsed) {
        if (!(parsed instanceof Map)) return null;
        Object l = ((Map<?, ?>) parsed).get("list");
        if (!(l instanceof List)) return null;
        for (Object o : (List<?>) l) {
            if (o instanceof Map) {
                Object id = ((Map<?, ?>) o).get("vod_id");
                if (id != null) return String.valueOf(id);
            }
        }
        return null;
    }

    private static String firstFlag(Object parsed) {
        if (!(parsed instanceof Map)) return "";
        Object l = ((Map<?, ?>) parsed).get("list");
        if (!(l instanceof List) || ((List<?>) l).isEmpty()) return "";
        Object first = ((List<?>) l).get(0);
        if (!(first instanceof Map)) return "";
        Object pf = ((Map<?, ?>) first).get("vod_play_from");
        if (pf == null) return "";
        return String.valueOf(pf).split("\\$\\$\\$")[0].trim();
    }

    private static String firstPlayUrl(Object parsed) {
        if (!(parsed instanceof Map)) return null;
        Object l = ((Map<?, ?>) parsed).get("list");
        if (!(l instanceof List) || ((List<?>) l).isEmpty()) return null;
        Object first = ((List<?>) l).get(0);
        if (!(first instanceof Map)) return null;
        Object pu = ((Map<?, ?>) first).get("vod_play_url");
        if (pu == null) return null;
        String first1 = String.valueOf(pu).split("#")[0];
        int d = first1.indexOf('$');
        return d >= 0 ? first1.substring(d + 1) : first1;
    }

    /**
     * 把源给的地址修成 java.net.URI 能接受的形式。
     * 这些源经常把 UA 直接拼进 query（含空格），还有用 | 当分隔符的（Java 的 URI 认为非法）。
     * 空格→%20，|→%7C，其余原样（已编码的 %XX 不要二次编码）。
     */
    static String sanitizeForUri(String url) {
        if (url == null) return "";
        StringBuilder sb = new StringBuilder(url.length() + 24);
        for (char ch : url.trim().toCharArray()) {
            switch (ch) {
                case ' ': sb.append("%20"); break;
                case '|': sb.append("%7C"); break;
                case '"': sb.append("%22"); break;
                case '<': sb.append("%3C"); break;
                case '>': sb.append("%3E"); break;
                case '{': sb.append("%7B"); break;
                case '}': sb.append("%7D"); break;
                case '\\': sb.append("%5C"); break;
                case '^': sb.append("%5E"); break;
                case '`': sb.append("%60"); break;
                default:
                    if (ch < 0x20 || ch == 0x7f) sb.append(String.format("%%%02X", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 拉一次地址，返回 {ok(1/0), 说明}。 */
    static String[] fetchOnce(String url) {
        String u = sanitizeForUri(url);
        if (!(u.startsWith("http://") || u.startsWith("https://")))
            return new String[]{"0", "不是 http(s) 地址"};
        try {
            java.net.URI uri = java.net.URI.create(u);
            String host = uri.getHost() == null ? "" : uri.getHost();
            if (host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost"))
                return new String[]{"0", "本机地址，跳过"};
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Range", "bytes=0-65535")
                    .GET().build();
            HttpResponse<byte[]> resp = PROBE_HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            String ct = resp.headers().firstValue("content-type").orElse("").toLowerCase();
            String body = new String(resp.body(), StandardCharsets.UTF_8);
            if (code >= 400) return new String[]{"0", "源返回 " + code};
            if (body.startsWith("#EXTM3U") || ct.contains("mpegurl")) return new String[]{"1", "m3u8 可播"};
            if (ct.contains("json")) return new String[]{"0", "返回 JSON（解析接口，需二次解析）"};
            if (ct.contains("html") || ct.contains("xml"))
                return new String[]{"0", "返回 " + ct.split(";")[0] + "（网页播放页，不是媒体流）"};
            if (ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("octet-stream") || ct.isEmpty())
                return new String[]{"1", "直链可播" + (ct.isEmpty() ? "" : "（" + ct + "）")};
            return new String[]{"0", "content-type=" + ct};
        } catch (Exception e) {
            return new String[]{"0", "请求失败: " + (e.getMessage() == null ? e.toString() : e.getMessage())};
        }
    }

    /** 给 hub 用的共享令牌：装插件时写进 TvBox.xml，之后调免登录路由都要带上。 */
    static synchronized String embyToken() {
        String mine = str(settings.get("embyToken"));

        // 以插件 XML 里的 HubToken 为准。重装插件、在插件页改配置、安装器重新
        // 生成 token 都会让两边分叉 —— 症状就是「同步显示 token 无效」，
        // 而 hub 和插件各自看自己的配置都"没错"。
        String fromXml = EmbyManager.readHubToken(str(settings.get("embyPath")));
        if (fromXml == null || fromXml.isEmpty()) fromXml = EmbyManager.readHubToken(currentEmbyPath());

        if (fromXml != null && !fromXml.isEmpty()) {
            if (!fromXml.equals(mine)) {
                settings.put("embyToken", fromXml);
                saveSettings();
                System.out.println("[emby] HubToken 已与插件对齐: " + fromXml.substring(0, Math.min(8, fromXml.length())) + "…");
            }
            return fromXml;
        }
        if (mine == null || mine.isEmpty()) {
            mine = UUID.randomUUID().toString().replace("-", "");
            settings.put("embyToken", mine);
            saveSettings();
        }
        return mine;
    }

    /** 当前要操作的 programdata：配置里没有就现查一次 ready 的实例。 */
    static String currentEmbyPath() {
        String p = str(settings.get("embyPath"));
        if (p != null && !p.isEmpty()) return p;
        try {
            for (EmbyManager.Instance i : EmbyManager.detect(null)) {
                if ("ready".equals(i.state)) {
                    settings.put("embyPath", i.programdata);
                    saveSettings();
                    return i.programdata;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /** Emby 基地址：优先用配置，其次直探常见端口，最后从 system.xml 读端口。 */
    /** 用户勾选要同步的站点 key 集合。空集合 = 没勾过（当作"全部直连站"）。 */
    @SuppressWarnings("unchecked")
    static java.util.LinkedHashSet<String> syncSiteKeys() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        Object v = settings.get("syncSites");
        if (v instanceof List) {
            for (Object o : (List<Object>) v) if (o != null) set.add(String.valueOf(o));
        }
        return set;
    }

    /**
     * 给**浏览器**用的 Emby 基地址。
     *
     * embyBase() 是给服务端内部调用用的（回环就行），但播放地址是浏览器去拉的：
     * 走 127.0.0.1 的话，用户手机/电视上的浏览器会连自己。
     * 优先用设置里的 embyBaseUrl，其次用请求的 Host（网关转发时就是局域网地址），
     * 直连 hub（:8098）的 Host 不可用，退回回环。
     */
    static String browserEmbyBase(com.sun.net.httpserver.HttpExchange ex) {
        String saved = str(settings.get("embyBaseUrl"));
        if (saved != null && !saved.isEmpty()) {
            String s2 = saved.replaceAll("/+$", "");
            String lo = s2.toLowerCase();
            // 回环地址对浏览器没用（手机/电视会去连自己），当作没配
            boolean loopback = lo.contains("127.0.0.1") || lo.contains("localhost") || lo.contains("[::1]");
            if (!loopback) return s2;
        }
        try {
            String host = ex.getRequestHeaders().getFirst("Host");
            String proto = ex.getRequestHeaders().getFirst("X-Forwarded-Proto");
            if (proto == null || proto.isEmpty()) proto = "http";
            if (host != null && !host.isEmpty() && !host.toLowerCase().contains(":8098"))
                return proto + "://" + host;
        } catch (Exception ignore) { }
        return embyBase();
    }

    static String embyBase() {
        String saved = str(settings.get("embyBaseUrl"));
        if (saved != null && !saved.isEmpty()) return saved.replaceAll("/+$", "");

        for (int port : new int[]{8096, 8097, 8920}) {
            String base = "http://127.0.0.1:" + port;
            try {
                String s = embyHttp("GET", base + "/System/Info/Public");
                if (s != null && s.contains("ServerName")) {
                    settings.put("embyBaseUrl", base);
                    saveSettings();
                    return base;
                }
            } catch (Exception ignore) {
            }
        }

        // 从 Emby 的 system.xml 里读 HttpServerPortNumber
        String pd = str(settings.get("embyPath"));
        if (pd != null && !pd.isEmpty()) {
            try {
                Path xml = Path.of(pd, "config", "system.xml");
                if (Files.isRegularFile(xml)) {
                    String txt = Files.readString(xml, StandardCharsets.UTF_8);
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("<HttpServerPortNumber>(\\d+)</HttpServerPortNumber>").matcher(txt);
                    if (m.find()) {
                        String base = "http://127.0.0.1:" + m.group(1);
                        String s = embyHttp("GET", base + "/System/Info/Public");
                        if (s != null && s.contains("ServerName")) {
                            settings.put("embyBaseUrl", base);
                            saveSettings();
                            return base;
                        }
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    /** 带 JSON body 的 POST（转发插件的配置更新）。 */
    static String embyPostJson(String url, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return EMBY_HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    /**
     * 插件配置的字段清单 —— hub 的「配置」页和插件的配置页展示同一套字段、同一份取值。
     * 真相源是 plugins/configurations/TvBox.xml，hub 这边只是镜像。
     */
    static final String[] PLUGIN_CFG_KEYS = {
            "ConfigUrl", "SiteNameFilter", "SiteWhitelist", "SiteBlacklist",
            "MaxPagesPerCategory", "MaxItemsPerRun", "Concurrency", "TimeoutSeconds",
            "RequestDelayMs", "ExcludeCloudDrive", "OutputRoot", "EmbyBaseUrl",
            "Referer", "UserAgent", "UseProxy", "AllowPrivateHosts", "CleanupOrphans",
            "EnableScheduledSync", "SyncIntervalHours", "SpiderHubUrl", "ConfigFilePath",
            // 下面 12 项以前漏在白名单外：Emby 插件页能改，hub 界面改了会被静默忽略
            "VerifyPlayUrl", "VerifyMaxEpisodes", "DetailBatchSize", "ResolveIndirectUrl",
            "WriteNfo", "DownloadPoster", "PosterMaxMb", "IncrementalSync", "IncrementalHours",
            "StreamMode", "RedirectHosts", "MaxDeletePerRun"
    };

    /** 读插件配置。插件不可达/未安装 → 返回 null。 */
    static Map<String, Object> pluginConfig() {
        try {
            String base = embyBase();
            if (base == null) return null;
            String body = embyHttp("GET", base + "/TvBox/Config?token=" + embyToken());
            Object o = SpiderPool.Json.parse(body);
            if (!(o instanceof Map)) return null;
            Map<?, ?> m = (Map<?, ?>) o;
            if (!Boolean.TRUE.equals(m.get("Success")) && !Boolean.TRUE.equals(m.get("success"))) {
                // 兼容大小写不同的成功标志；明确 false 才算失败
                Object okv = m.containsKey("Success") ? m.get("Success") : m.get("success");
                if (okv != null) return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            // 插件不返回 HubToken（怕泄露），但「有没有配」界面要能判断 —— 直接看配置文件
            out.put("HubTokenSet", pluginTokenConfigured(out));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 读插件配置文件，判断 HubToken 是否已设置（只回布尔，不回值）。 */
    /** Emby 转码临时目录是否存在/可写（缺了会导致所有转码播放失败）。 */
    static Map<String, Object> embyTranscodingTemp(String programdata) {
        Map<String, Object> out = new LinkedHashMap<>();
        java.util.List<String> cands = new java.util.ArrayList<>();
        cands.add(programdata + "/var/transcoding-temp");
        cands.add(programdata + "/transcoding-temp");
        cands.add(programdata + "/cache/transcoding-temp");
        for (String c : cands) {
            try {
                java.nio.file.Path p = java.nio.file.Path.of(c);
                if (!java.nio.file.Files.isDirectory(p)) continue;
                out.put("path", c);
                out.put("exists", true);
                out.put("writable", java.nio.file.Files.isWritable(p));
                try { out.put("owner", java.nio.file.Files.getOwner(p).getName()); } catch (Exception ignore) { }
                return out;
            } catch (Exception ignore) { }
        }
        out.put("path", cands.get(0));
        out.put("exists", false);
        out.put("writable", false);
        return out;
    }

    static boolean pluginTokenConfigured(Map<String, Object> cfg) {
        Object p = cfg.get("ConfigFilePath");
        if (p == null) return false;
        try {
            java.nio.file.Path f = java.nio.file.Path.of(String.valueOf(p));
            if (!java.nio.file.Files.isRegularFile(f)) return false;
            String xml = java.nio.file.Files.readString(f);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<HubToken>\\s*([^<]*)\\s*</HubToken>").matcher(xml);
            return m.find() && m.group(1) != null && !m.group(1).isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /** 写插件配置（只挑插件认识的字段发过去），返回插件的应答文本。 */
    static String pushPluginConfig(Map<String, Object> payload) throws Exception {
        String base = embyBase();
        if (base == null) throw new Exception("找不到 Emby 地址");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Token", embyToken());
        // 插件必须知道 spider 中转在哪，否则 type:3 的站点它没法同步。
        // 这一项由 hub 自动维护（它知道自己的端口），界面只做展示。
        body.put("SpiderHubUrl", hubSelfUrl());
        for (String k : PLUGIN_CFG_KEYS) {
            if (payload.containsKey(k)) body.put(k, payload.get(k));
            else if (payload.containsKey(lowerFirst(k))) body.put(k, payload.get(lowerFirst(k)));
        }
        return embyPostJson(base + "/TvBox/Config", SpiderPool.Json.stringify(body));
    }

    /** hub 自己的 TCP 地址，写进插件的 SpiderHubUrl。 */
    static String hubSelfUrl() {
        return "http://127.0.0.1:" + tcpPort;
    }

    /**
     * 插件里的 SpiderHubUrl 是否指向一个活着的 hub。
     * 只在「为空」或「那个端口已经没人监听」时才改写成自己的地址 ——
     * 否则任何第二个 hub 实例（测试用、临时启动的）都会把生产插件的这一项抢走，
     * 表现为同步时 spider 站点全部失败。
     */
    static void ensureHubUrlInPlugin(Map<String, Object> pc) {
        try {
            if (pc == null) return;
            String want = hubSelfUrl();
            String have = str(pc.get("SpiderHubUrl"));
            if (want.equals(have)) return;

            if (have != null && !have.isEmpty() && !have.equals(want)) {
                if (hubPortAlive(have)) return;      // 原来那个地址还活着，别动它
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("SpiderHubUrl", want);
            String resp = pushPluginConfig(one);
            System.out.println("[emby] 插件的 SpiderHubUrl 由 " + have + " 改为 " + want + " —— " + resp);
        } catch (Exception e) {
            System.out.println("[emby] 修正 SpiderHubUrl 失败: " + e.getMessage());
        }
    }

    /** 尝试连一下 http://host:port/api/status，能连上就算活着。 */
    static boolean hubPortAlive(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            int port = u.getPort() > 0 ? u.getPort() : 8098;
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(
                        u.getHost() == null ? "127.0.0.1" : u.getHost(), port), 1200);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    static String lowerFirst(String s) {
        return s == null || s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /// <summary>带 JSON body + 可配超时的 Emby 调用（推送单条内容可能几十秒）。</summary>
    static String embyHttpJson(String method, String url, String jsonBody, int timeoutSeconds) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)));
        if ("POST".equalsIgnoreCase(method))
            b.POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody, StandardCharsets.UTF_8))
             .header("Content-Type", "application/json; charset=utf-8");
        else b.GET();
        HttpResponse<String> r = EMBY_HTTP.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return r.body();
    }

    static String embyHttp(String method, String url) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
        if ("POST".equalsIgnoreCase(method)) b.POST(HttpRequest.BodyPublishers.noBody());
        else b.GET();
        HttpResponse<String> r = EMBY_HTTP.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return r.body();
    }

    /**
     * 多仓展开：{urls:[...]} → 逐个拉子仓并合并 sites（按 key 去重，先到先得）。
     *
     * 以前这里只取第一个子仓，注释写「完整多仓展开在插件侧做」。但插件展开后，
     * 会把所有子仓里的 csp 站都改写成 http://127.0.0.1:8098/v/&lt;key&gt;/api.php/...
     * 交给 hub；hub 不认识那些 key，就回「没有这个站点」，同步日志里只显示
     * 「0 个条目」，完全看不出是配置没加载。
     *
     * 实测（tushen6/Tomorrow/lmw.json，16 个子仓）：
     *   第一子仓 100 站（hub 原本只加载这 94 个 csp）
     *   第 3 子仓  40 站（39 个 csp，33 个 hub 不认识）
     *   第 4 子仓  99 站（99 个 csp，94 个 hub 不认识）
     * 也就是一大半站点白丢。这里改成全量展开。
     *
     * 子仓是并发拉的：很多子仓域名已失效，串行会把启动拖成分钟级。
     */
    static String expandMultiRepo(String body) throws Exception {
        Object root;
        try { root = SpiderPool.Json.parse(body); }
        catch (Exception e) { return body; }
        if (!(root instanceof Map)) return body;
        Object urlsObj = ((Map<?, ?>) root).get("urls");
        if (!(urlsObj instanceof List)) return body;                 // 不是多仓，原样返回

        final List<String> subs = new ArrayList<>();
        for (Object o : (List<?>) urlsObj) {
            String u = null;
            if (o instanceof Map) {
                Object v = ((Map<?, ?>) o).get("url");
                u = (v == null) ? null : String.valueOf(v);
            } else if (o != null) {
                u = String.valueOf(o);
            }
            if (u != null && !u.isBlank() && !"null".equals(u)) subs.add(u);
        }
        if (subs.isEmpty()) throw new Exception("多仓里没有子仓");

        System.out.println("多仓：开始展开 " + subs.size() + " 个子仓");
        java.util.concurrent.ExecutorService workers2 =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(6, subs.size()));
        List<java.util.concurrent.Future<Object[]>> futs = new ArrayList<>();
        for (final String sub : subs) {
            futs.add(workers2.submit(() -> {
                try {
                    Object parsed = SpiderPool.Json.parse(fetch(sub));
                    if (!(parsed instanceof Map)) return new Object[] { sub, null, "不是 JSON 对象" };
                    Object sites = ((Map<?, ?>) parsed).get("sites");
                    if (!(sites instanceof List)) return new Object[] { sub, null, "没有 sites" };
                    return new Object[] { sub, sites, null, parsed };
                } catch (Exception e) {
                    return new Object[] { sub, null, brief(e) };
                }
            }));
        }

        List<Object> merged = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int okRepos = 0, badRepos = 0, dupSkipped = 0;
        for (java.util.concurrent.Future<Object[]> f : futs) {
            Object[] r;
            try {
                r = f.get(90, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                badRepos++;
                continue;
            }
            String sub = String.valueOf(r[0]);
            Object sites = r[1];
            if (sites == null) {
                badRepos++;
                System.out.println("   子仓失败: " + hostOf(sub) + " → " + r[2]);
                continue;
            }
            // 子仓级 jar：TVBox 多仓的子仓常带 "spider"/"jar" 字段，是这个子仓所有站点的
            // 默认 spider jar。不注入下去，站点类就找不到（实测 97/189 站缺类）。
            String repoJar = null;
            for (String fieldName : new String[] { "spider", "jar", "spiderJar" }) {
                Object v = ((Map<?, ?>) r[3]).get(fieldName);
                if (v instanceof String && !((String) v).isBlank()) { repoJar = (String) v; break; }
            }
            if (repoJar != null) repoJar = resolveUrl(sub, repoJar);

            int n = 0;
            for (Object o : (List<?>) sites) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> sm = (Map<?, ?>) o;
                Object k = sm.get("key");
                String key = (k == null) ? "" : String.valueOf(k);
                if (key.isBlank() || "null".equals(key)) continue;
                if (!seen.add(key)) { dupSkipped++; continue; }
                Object ownJar = sm.get("jar");
                if ((ownJar == null || String.valueOf(ownJar).isBlank()) && repoJar != null) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : sm.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
                    copy.put("jar", repoJar);
                    merged.add(copy);
                } else {
                    merged.add(o);
                }
                n++;
            }
            okRepos++;
            System.out.println("   子仓 OK: " + hostOf(sub) + " → " + n + " 站");
        }
        workers2.shutdownNow();

        if (merged.isEmpty())
            throw new Exception("多仓展开后没有任何站点（" + okRepos + " 个子仓成功 / " + badRepos + " 个失败）");

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("sites", merged);
        System.out.println("多仓展开完成: " + okRepos + " 个子仓成功 / " + badRepos + " 个失败，"
                + "合并 " + merged.size() + " 个站点（去重跳过 " + dupSkipped + " 个同名 key）");
        return SpiderPool.Json.stringify(cfg);
    }

    // ───────────── 站点自带 spider jar 的下载与缓存 ─────────────

    /** 站点 jar 的本地缓存目录：&lt;PKGVAR&gt;/data/jars（和 info.log/app.pid 同一个父目录）。 */
    static Path jarCacheDir() throws Exception {
        Path base = (dataFile != null && dataFile.getParent() != null)
                ? dataFile.getParent() : Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
        Path dir = base.resolve("data").resolve("jars");
        Files.createDirectories(dir);
        return dir;
    }

    static String sha1Hex(String text) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        byte[] d = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * 去掉 TVBox 配置里的校验后缀：「url;md5;abc123」→「url」。
     * 不剥掉会拿着带 ;md5;... 的地址去下载，必然 404。
     */
    static String stripTvBoxSuffix(String url) {
        if (url == null) return null;
        int i = url.indexOf(";md5;");
        if (i > 0) url = url.substring(0, i);
        i = url.indexOf(";");
        if (i > 0 && !url.substring(0, i).endsWith(";")) {
            String rest = url.substring(i);
            // 只剥形如 ;xxx 的协议后缀，别把带分号的正常 URL 弄坏
            if (rest.matches("^;[A-Za-z]+;?.*$")) url = url.substring(0, i);
        }
        return url.trim();
    }

    /** 相对路径按所属配置地址解析（"spider": "./spider.jar" 这类写法很常见）。 */
    static String resolveUrl(String base, String ref) {
        if (ref == null) return null;
        ref = stripTvBoxSuffix(ref);
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        try {
            return java.net.URI.create(base).resolve(ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    /** 判断一个下载下来的「jar」能不能被 JVM 直接当 jar 用（dex/APK 不行）。 */
    static boolean isJvmLoadableJar(byte[] data) {
        if (data == null || data.length < 4) return false;
        // dex 文件魔数 "dex\n"
        if (data[0] == 'd' && data[1] == 'e' && data[2] == 'x') return false;
        boolean zip = data[0] == 'P' && data[1] == 'K';
        if (!zip) return false;
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            java.util.zip.ZipEntry e;
            boolean hasClass = false;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if ("classes.dex".equals(n) || n.startsWith("classes") && n.endsWith(".dex")) return false;  // APK
                if (n.endsWith(".class")) hasClass = true;
            }
            return hasClass;
        } catch (Exception e) {
            return false;
        }
    }

    /** 下载（带镜像回退）站点 jar 并缓存；失败返回 null，调用方退回全局 jar。 */
    /** 取 TVBox 配置里声明的 md5（形如 url;md5;abcdef...），没有就返回 null。 */
    static String declaredMd5(String url) {
        if (url == null) return null;
        int i = url.indexOf(";md5;");
        if (i < 0) return null;
        String rest = url.substring(i + 5).trim();
        int semi = rest.indexOf(';');
        if (semi >= 0) rest = rest.substring(0, semi);
        rest = rest.trim().toLowerCase();
        return rest.matches("[0-9a-f]{32}") ? rest : null;
    }

    static String md5Hex(byte[] b) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static File downloadJar(String urlWithSuffix) {
        String wantMd5 = declaredMd5(urlWithSuffix);
        String url = stripTvBoxSuffix(urlWithSuffix);
        try {
            String name = sha1Hex(url) + ".jar";
            Path target = jarCacheDir().resolve(name);
            if (Files.isRegularFile(target) && Files.size(target) > 4096) {
                // 配置里声明了 md5 就必须对上，否则缓存也作废（配置指向的 jar 被换掉了）
                if (wantMd5 == null || wantMd5.equals(md5Hex(Files.readAllBytes(target)))) return target.toFile();
                System.out.println("站点 jar 缓存 md5 不符，重新下载: " + url);
                Files.deleteIfExists(target);
            }

            java.util.List<String> candidates = new ArrayList<>();
            candidates.add(url);
            candidates.addAll(mirrorsOf(url));
            candidates.add("https://gh-proxy.org/" + url);
            candidates.add("https://gh-proxy.com/" + url);

            HttpClient cli = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            for (String u : candidates) {
                try {
                    HttpResponse<byte[]> r = cli.send(
                            HttpRequest.newBuilder(java.net.URI.create(u))
                                    .timeout(Duration.ofSeconds(60)).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    if (r.statusCode() != 200 || r.body() == null || r.body().length < 4096) continue;
                    if (r.body().length > 80 * 1024 * 1024) {
                        System.out.println("站点 jar 超过 80MB，跳过: " + u);
                        continue;
                    }
                    // 配置里写了 ;md5;xxx 就必须一致 —— 否则等于无条件信任任意远端 jar，
                    // 而这个 jar 会被 URLClassLoader 以 root 身份加载并执行静态块。
                    if (wantMd5 != null) {
                        String got = md5Hex(r.body());
                        if (!wantMd5.equals(got)) {
                            System.out.println("站点 jar md5 不符，拒绝加载: " + u
                                    + "（期望 " + wantMd5 + "，实际 " + got + "）");
                            continue;
                        }
                    }
                    // TVBox 生态里的 "spider jar" 有两种：真 jar，和 APK/dex 包。
                    // dex 包（含 classes.dex）JVM 的 URLClassLoader 加载不了，
                    // 必须先 dex2jar 转换。这里明确识别出来，别让它表现为看不懂的
                    // ClassNotFoundException。
                    if (!isJvmLoadableJar(r.body())) {
                        System.out.println("站点 jar 是 APK/dex 格式，JVM 无法直接加载（需 dex2jar 转换）→ 退回全局 jar: " + u);
                        continue;
                    }
                    Files.write(target, r.body());
                    System.out.println("已缓存站点 jar: " + u + " (" + (r.body().length / 1024) + " KB)");
                    return target.toFile();
                } catch (Exception e) { /* 试下一个镜像 */ }
            }
            System.out.println("站点 jar 下载失败（将退回全局 jar）: " + url);
            return null;
        } catch (Exception e) {
            System.out.println("站点 jar 处理异常: " + url + " → " + e.getMessage());
            return null;
        }
    }

    static String fetch(String url) throws Exception {
        StringBuilder log = new StringBuilder();
        try {
            String body = fetchDirect(url, 5);
            lastFetchedVia = url;
            return body;
        } catch (Exception e) {
            log.append("直连失败(").append(brief(e)).append(")");
        }
        for (String m : mirrorsOf(url)) {
            try {
                String body = fetchDirect(m, 25);
                lastFetchedVia = m;
                System.out.println("镜像可用: " + m);
                return body;
            } catch (Exception e) {
                log.append("；").append(hostOf(m)).append("失败(").append(brief(e)).append(")");
            }
        }
        throw new Exception("配置地址取不到 → " + log);
    }

    /** 给一个 URL 生成候选镜像。 */
    static java.util.List<String> mirrorsOf(String url) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")
                .matcher(url);
        if (m.find()) {
            String user = m.group(1), repo = m.group(2), branch = m.group(3), path = m.group(4);
            String raw = url.startsWith("http://") ? url.replaceFirst("^http://", "https://") : url;
            out.add("https://ghproxy.net/" + raw);
            out.add("https://gh-proxy.com/" + raw);
            out.add("https://cdn.jsdelivr.net/gh/" + user + "/" + repo + "@" + branch + "/" + path);
            out.add("https://fastly.jsdelivr.net/gh/" + user + "/" + repo + "@" + branch + "/" + path);
            out.add("https://gcore.jsdelivr.net/gh/" + user + "/" + repo + "@" + branch + "/" + path);
            return out;
        }
        if (url.contains("github.com") || url.contains("githubusercontent.com")) {
            out.add("https://ghproxy.net/" + url);
            out.add("https://gh-proxy.com/" + url);
        }
        return out;
    }

    static String fetchDirect(String url, int connectSec) throws Exception {
        java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(connectSec)).build();
        java.net.http.HttpRequest r = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(connectSec * 2L))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .GET().build();
        java.net.http.HttpResponse<String> resp = c.send(r, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode());
        if (resp.body() == null || resp.body().isBlank()) throw new Exception("空响应");
        return resp.body();
    }

    static String hostOf(String url) {
        try { return java.net.URI.create(url).getHost(); } catch (Exception e) { return url; }
    }

    static String briefVia(String via) {
        if (via == null) return "未知";
        try { return java.net.URI.create(via).getHost(); } catch (Exception e) { return via; }
    }

    static String brief(Throwable e) {
        Throwable x = e;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        String s = x.getClass().getSimpleName();
        if (x.getMessage() != null) s += ": " + x.getMessage();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    // ───────────────────────── HTTP 基础 ─────────────────────────

    static void send(HttpExchange ex, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
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
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 2 * 1024 * 1024) throw new IllegalStateException("请求体超过 2MB");
                out.write(buf, 0, n);
            }
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

    /** 异常转人话（连不上/超时/404 分开说，别只丢一句 message）。 */
    static String explain(Exception e) {
        if (e == null) return "未知错误";
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String n = c.getClass().getSimpleName();
        if (c instanceof java.net.http.HttpConnectTimeoutException) return "连接超时（Emby 没起或端口不对）";
        if (c instanceof java.net.ConnectException) return "连接被拒（Emby 没起或端口不对）";
        if (c instanceof java.net.http.HttpTimeoutException) return "请求超时（Emby 卡住或正在扫库）";
        if (c instanceof java.io.IOException) {
            String m = c.getMessage();
            if (m != null && m.length() > 200) m = m.substring(0, 200) + "…";
            return n + ": " + m;
        }
        String m2 = e.getMessage();
        if (m2 == null) return n;
        // 只回前 200 字：完整堆栈/内部路径走日志，不进 HTTP 响应
        return m2.length() > 200 ? m2.substring(0, 200) + "…" : m2;
    }

    /** 空值统一成空字符串（避免页面上出现 None / null）。 */
    /** 反向代理专用：复用同一个实例。以前每请求 new 一个，HLS 每小时上千个实例/线程。 */
    static final java.net.http.HttpClient PROXY_HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    static final java.net.http.HttpClient HUB_HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    /** 带源 header 的 GET（Range 可选），拿原始字节。 */
    /** 是否允许访问非公网地址（默认 false；内网自建视频源的用户可在配置里打开）。 */
    static boolean allowPrivateProxy() {
        Object v = settings.get("allowPrivateProxy");
        return v != null && ("true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v)));
    }

    /**
     * 只允许 http/https，且域名解析出的地址必须全是公网。
     *
     * 为什么必须有：/api/stream/seg、/proxy 直接吃调用方给的 URL，
     * 而 hub 是 root 跑的 —— 不校验就等于给任何人一个「读内网任意 HTTP 服务」的原语
     * （127.0.0.1.nip.io 这类域名指向回环，纯字符串判断拦不住，必须解析）。
     */
    static boolean isPublicHttpUrl(String url) {
        try {
            java.net.URI u = java.net.URI.create(url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) return false;
            String host = u.getHost();
            if (host == null || host.isBlank()) return false;
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) return false;
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
            if (addrs.length == 0) return false;
            for (java.net.InetAddress a : addrs) {
                if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                        || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
                byte[] b = a.getAddress();
                if (b.length == 4) {
                    int f = b[0] & 0xFF;
                    if (f == 0 || f == 10 || f == 127) return false;
                    if (f == 169 && (b[1] & 0xFF) == 254) return false;       // 云元数据
                    if (f == 172 && (b[1] & 0xFF) >= 16 && (b[1] & 0xFF) <= 31) return false;
                    if (f == 192 && (b[1] & 0xFF) == 168) return false;
                    if (f >= 224) return false;                               // 组播/保留
                } else if (b.length == 16) {
                    if ((b[0] & 0xFE) == 0xFC) return false;                  // fc00::/7 ULA
                    if ((b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) == 0x80) return false;  // fe80::/10
                    boolean mapped = true;
                    for (int i = 0; i < 10; i++) if (b[i] != 0) { mapped = false; break; }
                    if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                        int f = b[12] & 0xFF;
                        if (f == 10 || f == 127 || (f == 192 && (b[13] & 0xFF) == 168)) return false;
                        if (f == 172 && (b[13] & 0xFF) >= 16 && (b[13] & 0xFF) <= 31) return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static java.net.http.HttpResponse<byte[]> httpGetBytes(String url, String headerJson, String range) throws Exception {
        if (!allowPrivateProxy() && !isPublicHttpUrl(url))
            throw new Exception("拒绝访问非公网地址: " + url);
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(45))
                .GET()
                .header("User-Agent", "Mozilla/5.0");
        if (range != null && !range.isBlank()) b.header("Range", range);
        if (headerJson != null && !headerJson.isBlank()) {
            Object h = tryParse(headerJson);
            if (h instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) h).entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    String k1 = String.valueOf(e.getKey());
                    String lk = k1.toLowerCase();
                    if (lk.equals("host") || lk.equals("content-length") || lk.equals("connection")
                            || lk.equals("accept-encoding") || lk.equals("range")) continue;
                    try { b.header(k1, String.valueOf(e.getValue())); } catch (Exception ignore) { }
                }
            }
        }
        return HUB_HTTP.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    }

    static void sendText(com.sun.net.httpserver.HttpExchange ex, int code, String text) throws java.io.IOException {
        sendBytes(ex, code, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    /** 当前请求的网关前缀（/app/tvboxhub 之类），用于拼同源地址。 */
    static String basePath(com.sun.net.httpserver.HttpExchange ex) {
        // 走 unix socket 时前缀被剥掉了，靠 proxy() 注入的 X-Hub-Prefix 还原，
        // 否则重写出来的分片地址会少掉 /app/tvboxhub 这一段 → 浏览器 404。
        String px = ex.getRequestHeaders().getFirst("X-Hub-Prefix");
        if (px != null && !px.isEmpty()) return px.replaceAll("/+$", "");
        String p = ex.getRequestURI().getPath();
        int i = p.indexOf("/api/");
        return i > 0 ? p.substring(0, i) : "";
    }

    static boolean isM3u8(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        int cut = u.indexOf('?');
        if (cut >= 0) u = u.substring(0, cut);
        return u.endsWith(".m3u8") || u.contains("m3u8");
    }

    /** 把播放列表里的相对/分片地址统统改写成 /api/stream/seg?u=&h=，播放器只跟本机打交道。 */
    static String rewritePlaylist(String body, String playlistUrl, String hdr, String base) {
        String hb64 = (hdr == null || hdr.isBlank()) ? null : b64url(hdr);
        StringBuilder out = new StringBuilder(body.length() * 2);
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) { out.append(line).append('\n'); continue; }
            if (t.startsWith("#")) {
                StringBuilder sb = new StringBuilder(line);
                int from = 0;
                while (true) {
                    int a = sb.indexOf("URI=\"", from);
                    if (a < 0) break;
                    int b = sb.indexOf("\"", a + 5);
                    if (b < 0) break;
                    String ref = sb.substring(a + 5, b);
                    String abs = absolutize(playlistUrl, ref);
                    String proxy = base + "/api/stream/seg?u=" + b64url(abs) + (hb64 != null ? ("&h=" + hb64) : "");
                    sb.replace(a + 5, b, proxy);
                    from = a + 5 + proxy.length();
                }
                out.append(sb).append('\n');
            } else {
                String abs = absolutize(playlistUrl, t);
                out.append(base).append("/api/stream/seg?u=").append(b64url(abs))
                   .append(hb64 != null ? ("&h=" + hb64) : "").append('\n');
            }
        }
        return out.toString();
    }

    static String absolutize(String baseUrl, String ref) {
        try {
            return java.net.URI.create(baseUrl).resolve(ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    /** 按 flag/序号挑一集：返回 {线路名, 分集id}。 */
    static String[] pickEpisode(String key, String vid, String wantFlag, int epIdx) {
        try {
            String raw = call(key, () -> pool.get(key).detailContent(java.util.Collections.singletonList(vid)), 60);
            Map<?, ?> d = firstItem(tryParse(raw));
            if (d == null) return null;
            String from = str0(d.get("vod_play_from"));
            String urls = str0(d.get("vod_play_url"));
            String[] froms = from.split("\\$\\$\\$");
            String[] groups = urls.split("\\$\\$\\$");
            String firstFlag = null, firstId = null;
            for (int i = 0; i < froms.length; i++) {
                String g = i < groups.length ? groups[i] : "";
                java.util.List<String> ids = new java.util.ArrayList<>();
                for (String seg : g.split("#")) {
                    if (seg.isBlank()) continue;
                    int cut = seg.lastIndexOf('$');
                    ids.add(cut >= 0 ? seg.substring(cut + 1) : seg);
                }
                if (ids.isEmpty()) continue;
                String lineName = froms[i].isBlank() ? ("线路" + (i + 1)) : froms[i];
                String pick = ids.get(Math.min(Math.max(0, epIdx), ids.size() - 1));
                if (wantFlag != null && !wantFlag.isEmpty() && wantFlag.equals(lineName)) return new String[]{lineName, pick};
                if (firstFlag == null) { firstFlag = lineName; firstId = pick; }
            }
            return firstFlag == null ? null : new String[]{firstFlag, firstId};
        } catch (Exception e) {
            return null;
        }
    }

    static String unb64url(String s) {
        try {
            return new String(java.util.Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 detailContent 解析结果里取第一条。 */
    static Map<?, ?> firstItem(Object parsed) {
        if (!(parsed instanceof Map)) return null;
        Object lst = ((Map<?, ?>) parsed).get("list");
        if (!(lst instanceof java.util.List) || ((java.util.List<?>) lst).isEmpty()) return null;
        Object o = ((java.util.List<?>) lst).get(0);
        return (o instanceof Map) ? (Map<?, ?>) o : null;
    }

    /** base64url（跟插件的 SyncService.Base64UrlEncode 对齐）。 */
    static String b64url(String s) {
        if (s == null) return "";
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String str0(Object o) {
        if (o == null) return "";
        String v;
        try { v = str(o); } catch (Exception e) { return ""; }
        if (v == null || v.isEmpty()) return "";
        if ("null".equalsIgnoreCase(v) || "None".equalsIgnoreCase(v)) return "";
        return v;
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Unix 前端把请求反代到本地 TCP。 */
    static UnixHttp.Resp proxy(String method, String url, String body, Map<String, String> inHeaders, String pxUsed) {
        try {
            java.net.http.HttpClient c = PROXY_HTTP;   // 复用静态实例（不再每请求 new）
            java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(180));
            // 转发给本机 handler 的请求头：Range 一定要带，否则视频没法边下边播
            if (inHeaders != null) {
                for (String k : new String[]{"range", "accept", "user-agent", "referer", "origin", "cookie", "if-range"}) {
                    String v = inHeaders.get(k);
                    if (v != null && !v.isEmpty()) {
                        try { b.header(k, v); } catch (Exception ignore) { }
                    }
                }
            }
            if (pxUsed != null && !pxUsed.isEmpty()) b.header("X-Hub-Prefix", pxUsed);
            if ("POST".equalsIgnoreCase(method)) {
                b.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body == null ? "" : body,
                        StandardCharsets.UTF_8));
            } else {
                b.GET();
            }
            java.net.http.HttpResponse<byte[]> resp = c.send(b.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            UnixHttp.Resp r = new UnixHttp.Resp();
            r.code = resp.statusCode();
            r.contentType = resp.headers().firstValue("content-type").orElse("text/plain; charset=utf-8");
            r.body = resp.body();
            // 响应头也要透传：Content-Range/Accept-Ranges/X-Accel-Buffering 全在里头
            for (String k : new String[]{"content-range", "accept-ranges", "x-accel-buffering", "cache-control", "location"}) {
                java.util.Optional<String> v = resp.headers().firstValue(k);
                if (v.isPresent()) r.headers.put(k, v.get());
            }
            return r;
        } catch (Exception e) {
            return UnixHttp.Resp.of("proxy 失败: " + e).code(502);
        }
    }

    /** body 既支持 JSON 也支持 a=b&c=d。 */
    static Map<String, String> parseForm(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return m;
        String t = body.trim();
        if (t.startsWith("{")) {
            try {
                Object o = SpiderPool.Json.parse(t);
                if (o instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                        Object v = e.getValue();
                        m.put(String.valueOf(e.getKey()), v == null ? "" : String.valueOf(v));
                    }
                }
            } catch (Exception ignore) {
            }
            return m;
        }
        for (String kv : t.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(dec(kv.substring(0, i)), dec(kv.substring(i + 1)));
        }
        return m;
    }

    /** 生成插件配置 XML——装插件时顺手写好，用户不用再进 Emby 手填。 */
    static String buildPluginXml(Map<String, String> req) {
        String outputRoot = firstNonEmpty(req.get("outputRoot"), "");
        if (outputRoot.isEmpty()) return null;
        String configUrl = firstNonEmpty(req.get("configUrl"), str(settings.get("configUrl")), "");
        // 兜底用 hub 自己的 TCP 地址：安装请求没带 hubUrl 时不能把插件里的
        // SpiderHubUrl 写成空串，否则插件从此连不上 hub（自更新/推送全失效）
        String hubUrl = firstNonEmpty(req.get("hubUrl"), str(settings.get("publicUrl")),
                "http://127.0.0.1:" + tcpPort);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<PluginConfiguration xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
          .append("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n");
        sb.append("  <ConfigUrl>").append(esc(configUrl)).append("</ConfigUrl>\n");
        sb.append("  <SpiderHubUrl>").append(esc(hubUrl)).append("</SpiderHubUrl>\n");
        sb.append("  <HubToken>").append(esc(embyToken())).append("</HubToken>\n");
        sb.append("  <OutputRoot>").append(esc(outputRoot)).append("</OutputRoot>\n");
        sb.append("  <EmbyBaseUrl></EmbyBaseUrl>\n");
        sb.append("  <UseProxy>true</UseProxy>\n");
        sb.append("  <AllowPrivateHosts>false</AllowPrivateHosts>\n");   // SSRF：默认不放行内网
        sb.append("  <MaxPagesPerCategory>1</MaxPagesPerCategory>\n");
        sb.append("  <RequestDelayMs>200</RequestDelayMs>\n");
        sb.append("  <TimeoutSeconds>45</TimeoutSeconds>\n");
        sb.append("  <EnableScheduledSync>false</EnableScheduledSync>\n");
        sb.append("  <SyncIntervalHours>12</SyncIntervalHours>\n");
        sb.append("  <CleanupOrphans>false</CleanupOrphans>\n");
        sb.append("</PluginConfiguration>\n");
        return sb.toString();
    }

    static String esc(String v) {
        return v == null ? "" : v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
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


    // ───────────── 探测结果持久化（界面「测试」列在重启后不清空）─────────────

    // ───────────── 站点连续失败计数（死站自动退避）─────────────

    /** settings["siteFail"] = {"站点key": 连续失败次数}。 */
    @SuppressWarnings("unchecked")
    static synchronized int noteSiteFailure(String key) {
        if (key == null || key.isBlank()) return 0;
        Object cur = settings.get("siteFail");
        Map<String, Object> m = (cur instanceof Map)
                ? (Map<String, Object>) cur : new LinkedHashMap<String, Object>();
        int n = (int) num(m.get(key)) + 1;
        m.put(key, n);
        settings.put("siteFail", m);
        saveSettings();
        return n;
    }

    @SuppressWarnings("unchecked")
    static synchronized void clearSiteFailure(String key) {
        if (key == null || key.isBlank()) return;
        Object cur = settings.get("siteFail");
        if (!(cur instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) cur;
        if (m.remove(key) != null) {
            settings.put("siteFail", m);
            saveSettings();
        }
    }

    /** 失败时顺手把探测结果也标成失败，界面立刻能看出来。 */
    static void clearProbeOnFail(String key) {
        try { rememberProbe(key, 0, 0, "同步失败（插件上报）"); } catch (Exception ignore) { }
        try { pool.markData(key, 0, 0); } catch (Exception ignore) { }
    }

    /**
     * 连续失败达到阈值（默认 3，settings.autoDisableAfter 可调，0 关掉）就把站点
     * 从同步名单里摘掉，并在操作记录里留痕。探测成功会清零计数，但不会自动加回名单
     * （用户可能在界面上手动移出过，自动加回会抢用户的决定）。
     */
    static void maybeAutoRemove(String key, int fails) {
        // 默认**不**自动移出。
        // 教训：一次「停止同步 + 重启 Emby + 重启 hub」的折腾会被算成连续失败，
        // 结果把用户勾好的同步名单一个个摘光（实测 6 个站被清空成 0）。
        // 要这个行为就显式配 settings.autoDisableAfter = N（N>=2）。
        if (!settings.containsKey("autoDisableAfter")) return;
        int limit = (int) num(settings.get("autoDisableAfter"));
        if (limit < 2) return;
        if (fails < limit) return;
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(syncSiteKeys());
        if (!set.remove(key)) return;
        settings.put("syncSites", new ArrayList<>(set));
        saveSettings();
        record("自动移出同步", key, false,
                "连续失败 " + fails + " 次，已从同步名单移除（修好后在站点页手动加回）", 0);
        System.out.println("站点 " + key + " 连续失败 " + fails + " 次 → 已自动移出同步名单");
    }

    /**
     * 把一条探测结果写进 settings["probe"] 并落盘。
     * 结构：{"站点key": {"c":分类数,"l":列表数,"at":毫秒,"e":错误或null}}
     */
    @SuppressWarnings("unchecked")
    static synchronized void rememberProbe(String key, int classes, int list, String err) {
        if (key == null || key.isBlank()) return;
        Object cur = settings.get("probe");
        Map<String, Object> probe = (cur instanceof Map)
                ? (Map<String, Object>) cur : new LinkedHashMap<String, Object>();
        // 复用已有那一行：可播性检测是紧接着 markData 之后做的，
        // 重新造一个 row 会把刚写进去的 p/pm 覆盖掉。
        Map<String, Object> row;
        Object old = probe.get(key);
        if (old instanceof Map) {
            row = (Map<String, Object>) old;
        } else {
            row = new LinkedHashMap<>();
        }
        row.put("c", classes);
        row.put("l", list);
        row.put("at", System.currentTimeMillis());
        row.put("e", (err == null || err.isBlank()) ? null
                : (err.length() > 200 ? err.substring(0, 200) : err));
        probe.put(key, row);
        settings.put("probe", probe);
        saveSettings();
    }

    /** hub 启动 / 重新加载配置后，把磁盘上的探测结果恢复到 pool（测试列不清空）。 */
    static void restoreProbeState() {
        Object cur = settings.get("probe");
        if (!(cur instanceof Map)) return;
        int n = 0;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) cur).entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            Map<?, ?> row = (Map<?, ?>) e.getValue();
            int c = (int) num(row.get("c"));
            int l = (int) num(row.get("l"));
            long at = (long) num(row.get("at"));
            Object errObj = row.get("e");
            String err = errObj == null ? null : String.valueOf(errObj);
            if (at <= 0) continue;
            pool.restoreProbe(String.valueOf(e.getKey()), c, l, at, err);
            Object p = row.get("p");
            if (p != null) {
                Boolean pl = "1".equals(String.valueOf(p));
                Object pm = row.get("pm");
                pool.markPlayable(String.valueOf(e.getKey()), pl, pm == null ? null : String.valueOf(pm));
            }
            n++;
        }
        if (n > 0) System.out.println("恢复上次探测结果: " + n + " 个站点");
    }

    static final Object SETTINGS_LOCK = new Object();

    static void saveSettings() {
        try {
            if (dataFile == null) return;
            String json;
            synchronized (SETTINGS_LOCK) {
                json = SpiderPool.Json.stringify(settings);   // 锁内快照，避免并发遍历 CME
            }
            Path tmp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, dataFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFail) {
                Files.move(tmp, dataFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.out.println("保存配置失败: " + e.getMessage());
        }
    }

    // ───────────────────────── Web UI ─────────────────────────

    /** 把本地令牌注入页面 head，同源页面自动带上，跨站页面读不到。 */
    static String injectToken(String html) {
        if (html == null) return "";
        String meta = "<meta name=\"hub-token\" content=\"" + localToken() + "\">";
        // 注意：不能用 contains("name=\"hub-token\"") 判断是否已注入 —— 页面里的 JS
        // 选择器 'meta[name="hub-token"]' 本身就含这段字面量，会导致永远判定「已注入」。
        if (html.contains("<meta name=\"hub-token\"")) return html;
        int i = html.indexOf("<head>");
        if (i >= 0) return html.substring(0, i + 6) + "\n" + meta + html.substring(i + 6);
        int j = html.indexOf("<html");
        if (j >= 0) {
            int k = html.indexOf('>', j);
            if (k > 0) return html.substring(0, k + 1) + meta + html.substring(k + 1);
        }
        return meta + html;
    }

    static String ui() {
        try {
            if (uiDir != null) {
                Path u = uiDir.resolve("index.html");
                if (Files.isRegularFile(u)) return injectToken(Files.readString(u, StandardCharsets.UTF_8));
            }
            // 优先读 jar 同目录 web/index.html（方便改），否则读 classpath
            Path p = Path.of("web", "index.html");
            if (Files.isRegularFile(p)) return injectToken(Files.readString(p, StandardCharsets.UTF_8));
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

    /**
     * TVBox 客户端的本地文件服务（127.0.0.1:9978）。
     *
     * 为什么要它：catvod 的 spider 里大量写死
     *     http://127.0.0.1:9978/file/TVBox/bili_cookie.txt
     * 也就是 TVBox App 自带的那个小文件服务。我们跑在服务端，这个地址不存在，
     * cookie 读不到 → B 站系列（csp_Bili）11 个站全部 NPE / 返回非 JSON。
     *
     * 这里补一个：/file/<path> 读 PKGVAR/data/file/<path>，/proxy?url= 透传。
     * 用户把 bili_cookie.txt 丢到 data/file/TVBox/ 下就能用。
     */
    static int filePort = 0;

    static void startTvBoxFileServer() {
        int want = 9978;
        Object v = settings.get("fileServerPort");
        if (v != null) {
            try { want = Integer.parseInt(String.valueOf(v).trim()); } catch (Exception ignore) { }
        }
        for (int p = want; p < want + 5; p++) {
            try {
                HttpServer fs = HttpServer.create(new java.net.InetSocketAddress(
                        java.net.InetAddress.getLoopbackAddress(), p), 0);
                fs.createContext("/", Hub::handleFileRequest);
                fs.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
                fs.start();
                filePort = p;
                System.out.println("[fileserver] TVBox 本地文件服务: http://127.0.0.1:" + p
                        + "/file/  根目录 " + dataDir.resolve("file"));
                return;
            } catch (Exception e) {
                // 端口被占，试下一个
            }
        }
        System.out.println("[fileserver] 起不来（端口被占），依赖 127.0.0.1:9978 的站点会读不到 cookie");
    }

    /**
     * GET /play/&lt;key&gt;?flag=&lt;线路&gt;&amp;id=&lt;分集 id&gt;[&amp;json=1]
     *
     * 很多 spider 的 vod_play_url 里根本不是 URL，而是它自己的私有格式：
     *   金牌影视  146660@1314815
     *   半日99    CO4K_...@CO4K@重生@1
     *   Sao火     /play/50375-1-1.html
     * TVBox 客户端每次播放都会先调 Spider.playerContent(flag, id, vipFlags) 换成真地址，
     * 我们之前直接把私有 id 当 URL 用，这些源就整站同步不到。
     * 这个端点补上这一层：解析出真地址后 302 过去（json=1 则返回 JSON 说明）。
     */
    /**
     * spider.proxy() 可能返回 okhttp3.Response / ResponseBody / byte[] / String，
     * 统一抠成 byte[]（顺便带回 content-type）。
     */
    static byte[] proxyBody(Object out, String[] ctOut) {
        try {
            if (out instanceof byte[]) return (byte[]) out;
            if (out instanceof String) return ((String) out).getBytes(StandardCharsets.UTF_8);
            try {
                Object ctv = out.getClass().getMethod("contentType").invoke(out);
                if (ctv != null) ctOut[0] = String.valueOf(ctv);
            } catch (Exception ignore) { }
            Object body = out;
            try {
                Object b = out.getClass().getMethod("body").invoke(out);
                if (b != null) body = b;
            } catch (Exception ignore) { }
            try {
                Object bytes = body.getClass().getMethod("bytes").invoke(body);
                if (bytes instanceof byte[]) return (byte[]) bytes;
            } catch (Exception ignore) { }
            try {
                Object strv = body.getClass().getMethod("string").invoke(body);
                if (strv != null) return String.valueOf(strv).getBytes(StandardCharsets.UTF_8);
            } catch (Exception ignore) { }
        } catch (Exception ignore) { }
        return null;
    }

    /**
     * GET /live/&lt;key&gt; —— 直播源。
     * spider.liveContent() 给 {"lives":[{"name":"组","channels":[{"name":"台","urls":["http://…"]}]}]}，
     * 这里转成标准 M3U，Emby 的 Live TV → M3U tuner 能直接用。
     */
    static void handleLive(com.sun.net.httpserver.HttpExchange ex, String path) {
        try {
            String key = java.net.URLDecoder.decode(path.substring("/live/".length()), StandardCharsets.UTF_8);
            String raw = call(key, () -> pool.get(key).liveContent(null), 45);
            if (raw == null || raw.isBlank()) {
                send(ex, 404, "text/plain; charset=utf-8", "这个站点没有直播源（liveContent 返回空）");
                return;
            }
            Object parsed = tryParse(raw);
            StringBuilder m3u = new StringBuilder("#EXTM3U\n");
            int n = 0;
            if (parsed instanceof Map && ((Map<?, ?>) parsed).get("lives") instanceof List) {
                for (Object g : (List<?>) ((Map<?, ?>) parsed).get("lives")) {
                    if (!(g instanceof Map)) continue;
                    String gname = m3uEsc(str(((Map<?, ?>) g).get("name")));
                    Object chs = ((Map<?, ?>) g).get("channels");
                    if (!(chs instanceof List)) continue;
                    for (Object c : (List<?>) chs) {
                        if (!(c instanceof Map)) continue;
                        String cname = m3uEsc(str(((Map<?, ?>) c).get("name")));
                        Object urls = ((Map<?, ?>) c).get("urls");
                        if (!(urls instanceof List)) continue;
                        for (Object u : (List<?>) urls) {
                            String uu = u instanceof Map ? str(((Map<?, ?>) u).get("url")) : str(u);
                            if (uu.isBlank()) continue;
                            m3u.append("#EXTINF:-1 group-title=\"").append(gname)
                               .append("\",").append(cname).append("\n").append(uu).append("\n");
                            n++;
                        }
                    }
                }
            }
            if (n == 0) {
                send(ex, 404, "text/plain; charset=utf-8", "直播数据解析不出频道: " + abbrev(raw, 120));
                return;
            }
            send(ex, 200, "audio/x-mpegurl; charset=utf-8", m3u.toString());
        } catch (Exception e) {
            try { send(ex, 502, "text/plain; charset=utf-8", "liveContent 失败: " + explain(e)); } catch (Exception ignore) { }
        }
    }

    static String m3uEsc(String s) {
        if (s == null) return "";
        return s.replace("\"", "'").replace("\n", " ").replace("\r", "");
    }

    static void handlePlay(com.sun.net.httpserver.HttpExchange ex, String path) {
        try {
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String key = java.net.URLDecoder.decode(path.substring("/play/".length()), StandardCharsets.UTF_8);
            String flag = q.getOrDefault("flag", "");
            String id = q.getOrDefault("id", "");
            boolean wantJson = "1".equals(q.get("json"));
            if (id.isBlank()) { sendJson(ex, 400, Map.of("ok", false, "msg", "缺少 id")); return; }

            String[] r = resolveEpisode(key, flag, id);
            if (wantJson || !"1".equals(r[0])) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", "1".equals(r[0]));
                m.put("url", r[1]);
                m.put("msg", r[2]);
                m.put("parse", r[3]);
                if (r.length > 4 && r[4] != null) m.put("header", SpiderPool.Json.parse(r[4]));
                sendJson(ex, "1".equals(r[0]) ? 200 : 502, m);
                return;
            }
            // 302 到真地址：播放器自己跟着走，hub 不占带宽
            ex.getResponseHeaders().add("Location", r[1]);
            ex.getResponseHeaders().add("X-TvBox-Parse", r[3]);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        } catch (Exception e) {
            try { sendJson(ex, 500, Map.of("ok", false, "msg", explain(e))); } catch (Exception ignore) {}
        }
    }

    /** 调 playerContent 换真地址，返回 {ok(1/0), url, 说明, parse 标记}。 */
    static String[] resolveEpisode(String key, String flag, String id) {
        try {
            String raw = call(key, () -> pool.get(key).playerContent(flag, id, new ArrayList<String>()), 45);
            if (raw == null || raw.isBlank()) return new String[]{"0", "", "playerContent 返回空", "", null};
            Object parsed = tryParse(raw);
            if (!(parsed instanceof Map)) return new String[]{"0", "", "playerContent 返回的不是 JSON: " + abbrev(raw, 80), "", null};
            Map<?, ?> m = (Map<?, ?>) parsed;
            String url = str(m.get("url"));
            String parse = str(m.get("parse"));
            // playerContent 常带 header（Referer / User-Agent / Cookie），
            // 不转给播放端，那些 CDN 直接 401/403。用第 5 个返回值带出去。
            String headerJson = null;
            Object hdr = m.get("header");
            if (hdr instanceof Map && !((Map<?, ?>) hdr).isEmpty())
                headerJson = SpiderPool.Json.stringify(hdr);
            if (url.isBlank()) return new String[]{"0", "", "playerContent 没给 url: " + abbrev(raw, 80), parse, headerJson};
            url = url.replace(" ", "%20");
            if (!(url.startsWith("http://") || url.startsWith("https://")))
                return new String[]{"0", url, "playerContent 给的也不是 http 地址", parse, headerJson};
            // parse=1 表示还要送给外部解析器，这里尽量再往前推一步
            if ("1".equals(parse)) {
                String deeper = tryParseService(url);
                if (deeper != null) return new String[]{"1", deeper, "解析接口已解出", parse, headerJson};
                return new String[]{"0", url, "parse=1，解析接口没解出直链", parse, headerJson};
            }
            return new String[]{"1", url, "playerContent 已解出", parse, headerJson};
        } catch (Exception e) {
            return new String[]{"0", "", "playerContent 失败: " + explain(e), "", null};
        }
    }

    /** 解析接口形如 ?url=@|… 或直接返回 {"url":…}，尽量抠出直链。 */
    /**
     * 用户可配的解析接口：data/parse-apis.txt，一行一个模板，{url} 会被替换成待解析地址。
     * 例：https://api.example.com/parse?url={url}
     * 这是 parse=1 场景（Sao火/布布 这类只给网页地址的源）唯一的解，内置的 JSON 试探覆盖不到。
     */
    static java.util.List<String> parseApiTemplates() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            Path f = dataDir.resolve("parse-apis.txt");
            if (!java.nio.file.Files.exists(f)) return out;
            for (String line : java.nio.file.Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                out.add(t);
            }
        } catch (Exception ignore) { }
        return out;
    }

    static String tryParseService(String url) {
        // 1) 先按用户配置的解析接口试
        for (String tpl : parseApiTemplates()) {
            try {
                String target = tpl.contains("{url}")
                        ? tpl.replace("{url}", java.net.URLEncoder.encode(url, StandardCharsets.UTF_8))
                        : tpl + java.net.URLEncoder.encode(url, StandardCharsets.UTF_8);
                HttpRequest req = HttpRequest.newBuilder(java.net.URI.create(sanitizeForUri(target)))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .GET().build();
                HttpResponse<String> resp = PROBE_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                String body = resp.body();
                if (body == null) continue;
                Object p = tryParse(body);
                if (p instanceof Map) {
                    String u = str(((Map<?, ?>) p).get("url"));
                    if (u.startsWith("http")) {
                        System.out.println("[play] 用户解析接口解出: " + abbrev(u, 100));
                        return u;
                    }
                }
                java.util.regex.Matcher mm = java.util.regex.Pattern
                        .compile("https?:[^\\s\"'<>()\\]*?\\.(?:m3u8|mp4|flv|ts|mkv)(?:\\?[^\\s\"'<>()\\]*)?").matcher(body);
                if (mm.find()) return mm.group();
            } catch (Exception ignore) { }
        }
        // 2) 再把地址本身当解析接口试（原有行为）
        return tryParseInline(url);
    }

    static String tryParseInline(String url) {
        try {
            String[] r = fetchOnce(url);
            // fetchOnce 只返回 {ok,msg}，这里要正文，单独再拉一次
            HttpRequest req = HttpRequest.newBuilder(java.net.URI.create(sanitizeForUri(url)))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .GET().build();
            HttpResponse<String> resp = PROBE_HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            if (body == null) return null;
            Object p = tryParse(body);
            if (p instanceof Map) {
                String u = str(((Map<?, ?>) p).get("url"));
                if (u.startsWith("http")) return u;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("https?:[^\s\"'<>()\\]*?\\.(?:m3u8|mp4|flv|ts|mkv)(?:\\?[^\\s\"'<>()\\]*)?").matcher(body);
            if (m.find()) return m.group();
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String abbrev(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    static void handleFileRequest(com.sun.net.httpserver.HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();

            // /proxy?url=xxx —— TVBox 同款透传，spider 用来绕 CORS
            if (path.startsWith("/proxy") && query != null && query.contains("key=")) {
                Map<String, String> p2 = query(query);
                String key2 = p2.get("key");
                if (key2 != null) {
                    try {
                        Object out = pool.get(key2).proxy(p2);
                        if (out == null) { sendBytes(ex, 404, "text/plain", "spider.proxy returned null".getBytes(StandardCharsets.UTF_8)); return; }
                        String[] ct = new String[]{"application/octet-stream"};
                        byte[] body = proxyBody(out, ct);
                        if (body == null) { sendBytes(ex, 502, "text/plain", "spider.proxy bad type".getBytes(StandardCharsets.UTF_8)); return; }
                        sendBytes(ex, 200, ct[0], body);
                        return;
                    } catch (Exception e) {
                        sendBytes(ex, 502, "text/plain", ("spider.proxy failed: " + explain(e)).getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                }
            }
            if (path.startsWith("/proxy")) {
                String target = null;
                if (query != null) {
                    for (String kv : query.split("&")) {
                        int i = kv.indexOf('=');
                        if (i > 0 && "url".equals(kv.substring(0, i)))
                            target = java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
                    }
                }
                if (target == null) { sendBytes(ex, 400, "text/plain", "missing url".getBytes(StandardCharsets.UTF_8)); return; }
                if (!allowPrivateProxy() && !isPublicHttpUrl(target)) {
                    sendBytes(ex, 403, "text/plain", "拒绝访问非公网地址".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create(target).toURL().openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("User-Agent", "okhttp/3.12.0");
                    c.setInstanceFollowRedirects(true);
                    int code = c.getResponseCode();
                    java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    byte[] body = in == null ? new byte[0] : in.readAllBytes();
                    String ct = c.getContentType();
                    sendBytes(ex, code, ct == null ? "application/octet-stream" : ct, body);
                    return;
                } catch (Exception e) {
                    sendBytes(ex, 502, "text/plain", ("proxy error: " + e).getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }

            System.out.println("[fileserver] 请求 " + path + (query == null ? "" : "?" + query));

            if (!path.startsWith("/file/")) {
                byte[] hint = ("TVBox 本地文件服务\n"
                        + "  GET /file/<相对路径>   读 " + dataDir.resolve("file") + "/<相对路径>\n"
                        + "  GET /proxy?url=<url>   透传\n").getBytes(StandardCharsets.UTF_8);
                sendBytes(ex, 200, "text/plain; charset=utf-8", hint);
                return;
            }

            String rel = path.substring("/file/".length());
            java.nio.file.Path root = dataDir.resolve("file").normalize();
            java.nio.file.Path target = root.resolve(rel).normalize();
            if (!target.startsWith(root)) { sendBytes(ex, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8)); return; }
            if (!java.nio.file.Files.isRegularFile(target)) {
                sendBytes(ex, 404, "text/plain; charset=utf-8",
                        ("文件不存在: " + target + "\n把 cookie 等文件放到 " + root + " 下即可").getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body = java.nio.file.Files.readAllBytes(target);
            String ct = target.toString().endsWith(".json") ? "application/json"
                      : target.toString().endsWith(".txt") ? "text/plain; charset=utf-8"
                      : "application/octet-stream";
            sendBytes(ex, 200, ct, body);
        } catch (Exception e) {
            try { sendBytes(ex, 500, "text/plain", String.valueOf(e).getBytes(StandardCharsets.UTF_8)); } catch (Exception ignore) { }
        } finally {
            try { ex.close(); } catch (Exception ignore) { }
        }
    }

    /** 文件/字节的 sha256（自更新用它比对版本，比版本号可靠）。 */
    static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static void sendBytes(com.sun.net.httpserver.HttpExchange ex, int code, String contentType, byte[] body) throws java.io.IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (java.io.OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
    }

}
