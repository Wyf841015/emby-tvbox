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
    static UnixHttp unix;

    // ── 操作记录（扫描/添加/安装等），落盘到 PKGVAR/history.json ──
    static final java.util.List<Map<String, Object>> history = new java.util.ArrayList<>();
    static final int HISTORY_MAX = 300;
    static Path historyFile;

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
                HttpServer server = HttpServer.create(new InetSocketAddress(tryPort), 0);
                server.createContext("/", Hub::handle);
                server.setExecutor(Executors.newFixedThreadPool(16));
                server.start();
                tcpPort = tryPort;
                tcpOk = true;
                System.out.println("TCP 监听: http://0.0.0.0:" + tryPort + "/" + (i > 0 ? "（端口顺延）" : ""));
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
                unix = new UnixHttp(Path.of(socketPath), (method, path, rawQuery, body) -> {
                    String p = path;
                    if (!prefix.isEmpty() && p.startsWith(prefix)) {
                        p = p.substring(prefix.length());
                        if (p.isEmpty()) p = "/";
                        if (!p.startsWith("/")) p = "/" + p;
                    }
                    String url = "http://127.0.0.1:" + tcpPort + p
                            + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery);
                    return proxy(method, url, body);
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
                m.put("tcpPort", tcpPort);
                m.put("tcpOk", tcpOk);
                m.put("pluginDll", pluginDll == null ? null
                        : (Files.isRegularFile(pluginDll) ? pluginDll.toString() : "缺失"));
                m.put("historyCount", history.size());
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
                record("保存配置", str(settings.get("configUrl")), true, "配置已写入", 0);
                sendJson(ex, 200, ok("已保存"));
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
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("total", history.size());
                m.put("records", history);
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
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ok", true);
                    m.put("classes", nClass);
                    m.put("list", nList);
                    m.put("ms", ms);
                    m.put("parsed", parsed);
                    sendJson(ex, 200, m);
                } catch (Exception e) {
                    long ms = System.currentTimeMillis() - t0;
                    if (rec) record("探测站点", key, false, String.valueOf(e.getMessage()), ms);
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
            case "/api/emby/sync": {
                String base = embyBase();
                if (base == null) { sendJson(ex, 200, fail("还没连上 Emby")); return; }
                long t0 = System.currentTimeMillis();
                Map<String, Object> m = new LinkedHashMap<>();
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
                sendJson(ex, 200, m);
                return;
            }
            case "/api/emby/install": {
                Map<String, String> req = parseForm(readBody(ex));
                String pd = firstNonEmpty(req.get("programdata"), str(settings.get("embyPath")));
                if (pd == null || pd.isEmpty()) { sendJson(ex, 200, fail("还没选 Emby 目录")); return; }
                if (pluginDll == null || !Files.isRegularFile(pluginDll)) {
                    sendJson(ex, 200, fail("安装包里没有 TvBox.dll")); return;
                }
                long t0 = System.currentTimeMillis();
                Map<String, Object> r = EmbyManager.install(pd, Files.readAllBytes(pluginDll), buildPluginXml(req));
                long ms = System.currentTimeMillis() - t0;
                if (Boolean.TRUE.equals(r.get("ok"))) {
                    settings.put("embyPath", pd);
                    settings.remove("embyBaseUrl");   // 让下次 embyBase() 重新探测
                    saveSettings();
                    embyBase();                        // 装完立刻探一次，后面的同步按钮就能用
                }
                record("安装插件", pd, Boolean.TRUE.equals(r.get("ok")), String.valueOf(r.get("msg")), ms);
                sendJson(ex, 200, r);
                return;
            }
            case "/api/emby/uninstall": {
                Map<String, String> req = parseForm(readBody(ex));
                String pd = firstNonEmpty(req.get("programdata"), str(settings.get("embyPath")));
                if (pd == null || pd.isEmpty()) { sendJson(ex, 200, fail("还没选 Emby 目录")); return; }
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

    /** 给 hub 用的共享令牌：装插件时写进 TvBox.xml，之后调免登录路由都要带上。 */
    static synchronized String embyToken() {
        String t = str(settings.get("embyToken"));
        if (t == null || t.isEmpty()) {
            t = UUID.randomUUID().toString().replace("-", "");
            settings.put("embyToken", t);
            saveSettings();
        }
        return t;
    }

    /** Emby 基地址：优先用配置，其次直探常见端口，最后从 system.xml 读端口。 */
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

    static String embyHttp(String method, String url) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
        if ("POST".equalsIgnoreCase(method)) b.POST(HttpRequest.BodyPublishers.noBody());
        else b.GET();
        HttpResponse<String> r = EMBY_HTTP.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return r.body();
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

    /** 异常转人话（连不上/超时/404 分开说，别只丢一句 message）。 */
    static String explain(Exception e) {
        if (e == null) return "未知错误";
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String n = c.getClass().getSimpleName();
        if (c instanceof java.net.http.HttpConnectTimeoutException) return "连接超时（Emby 没起或端口不对）";
        if (c instanceof java.net.ConnectException) return "连接被拒（Emby 没起或端口不对）";
        if (c instanceof java.net.http.HttpTimeoutException) return "请求超时（Emby 卡住或正在扫库）";
        if (c instanceof java.io.IOException) return n + ": " + c.getMessage();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Unix 前端把请求反代到本地 TCP。 */
    static UnixHttp.Resp proxy(String method, String url, String body) {
        try {
            java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build();
            java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(180));
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
            String ct = resp.headers().firstValue("content-type").orElse("text/plain; charset=utf-8");
            r.contentType = ct;
            r.body = resp.body();
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
        String hubUrl = firstNonEmpty(req.get("hubUrl"), str(settings.get("publicUrl")), "");

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
        sb.append("  <AllowPrivateHosts>true</AllowPrivateHosts>\n");
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
            if (uiDir != null) {
                Path u = uiDir.resolve("index.html");
                if (Files.isRegularFile(u)) return Files.readString(u, StandardCharsets.UTF_8);
            }
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
