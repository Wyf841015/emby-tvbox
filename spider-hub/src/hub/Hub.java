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
    /** /api/save 允许写的键（不白名单化会被塞任意垃圾，比如 {"bad json":null}）。 */
    static final java.util.Set<String> SAVE_KEYS = new java.util.HashSet<>(Arrays.asList(
            "configUrl", "embyPath", "embyBaseUrl", "outputRoot", "publicUrl", "spiderJar", "embyToken"));

    static ExecutorService workers = Executors.newFixedThreadPool(24);
    static UnixHttp unix;

    // ── 操作记录（扫描/添加/安装等），落盘到 PKGVAR/history.json ──
    static final java.util.List<Map<String, Object>> history = new java.util.ArrayList<>();
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
                sendJson(ex, 200, m);
                return;
            }
            case "/api/emby/install": {
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
    /** 每个站点一把锁：catvod spider 实例内部有可变状态，多线程同时调必然互相踩。 */
    static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> KEY_LOCKS
            = new java.util.concurrent.ConcurrentHashMap<>();

    static String call(String key, Callable<String> task, int timeoutSec) throws Exception {
        java.util.concurrent.locks.ReentrantLock lock =
                KEY_LOCKS.computeIfAbsent(String.valueOf(key), k -> new java.util.concurrent.locks.ReentrantLock());
        // 拿不到锁说明这个站点正在被另一路请求用着，直接排队等（同 key 串行）
        if (!lock.tryLock(timeoutSec, TimeUnit.SECONDS)) {
            throw new Exception("站点 " + key + " 正在处理中，请稍后重试");
        }
        try {
            return callUnlocked(key, task, timeoutSec);
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
            "EnableScheduledSync", "SyncIntervalHours", "SpiderHubUrl", "ConfigFilePath"
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
            return out;
        } catch (Exception e) {
            return null;
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
