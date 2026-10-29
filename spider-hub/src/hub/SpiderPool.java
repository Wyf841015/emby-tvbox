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
import java.util.concurrent.atomic.AtomicLong;

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
        /**
         * 网盘类源。判定靠继承链：catvod 里所有网盘实现都继承
         * com.github.catvod.spider.Pan —— 它们能把分享页列出来，
         * 但把分享链接换成直链需要夸克/115/迅雷等账号，没有账号就永远播不了。
         */
        public boolean netdisk;
        /** 这个站点自己的 spider jar（TVBox 配置里的 site.jar）。空 = 用全局 jar。 */
        public String jar;
        public volatile String lastError;
        public volatile long lastOk;
        /** 最近一次探测（homeContent）拿到的分类数、列表条目数。 */
        public volatile int lastClasses;
        public volatile int lastList;
        /** 最近一次探测时间（0 = 从没探测过）。 */
        public volatile long lastProbeAt;
        /** 最近一次探测是否真的拿到了内容（分类>0 或 列表>0）。 */
        public volatile boolean hasData;
        /**
         * 拿到的内容到底能不能播。
         *   null  = 没测过（老数据）
         *   true  = 取一条详情，分集地址实际拉得动
         *   false = 有列表但没有一个能播的地址（解析接口失效 / 只给网页播放页 / 图片分片）
         *
         * 为什么要单独一列：实测有源「分类 8 个、列表 0 条」，也有源「37 个条目全部返回
         * application/json（解析 key 失效）」——只判断"有没有数据"会把它们标成可用，
         * 用户照着勾选，同步结果是 0 个文件。
         */
        public volatile Boolean playable;
        public volatile String playMsg;
        public volatile long lastPlayAt;
        /**
         * spider 类到底能不能被 classpath 解析。
         *   null  = 还没判过（探测时再带站点自带 jar 判一次）
         *   true  = 能加载
         *   false = 不能（加壳 APK/dex、或内置 jar 里压根没这个类）
         *
         * 为什么要单独记：TVBox 生态里有一批源把 spider 打成**加密 APK**
         * （assets/xxx.guard 熵 7.99，运行时由 native 库解密交给 Android DexClassLoader），
         * JVM 无论如何都加载不了。以前每个这样的站每轮探测都要白等 45s 超时，
         * 界面上还显示成「失败」，看着像配置坏了。判定一次、永久跳过。
         */
        public volatile Boolean classOk;
        public volatile String classMsg;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("name", name);
            m.put("type", type);
            m.put("api", api);
            m.put("class", cls);
            m.put("netdisk", netdisk);
            m.put("jar", jar);          // 站点自带 spider jar（没设就是 null）
            m.put("ext", ext.length() > 200 ? ext.substring(0, 200) + "…" : ext);
            m.put("ok", lastOk > 0 && lastError == null);
            m.put("lastError", lastError);
            m.put("lastOk", lastOk == 0 ? null : new java.util.Date(lastOk).toString());
            // 测试列三态：失败(lastError) / 可用(有数据) / 无数据(调通了但 0 条) / 未测(从没探测)
            m.put("data", hasData);
            m.put("unsupported", Boolean.FALSE.equals(classOk));
            m.put("unsupportReason", classMsg);
        m.put("playable", playable);          // null = 没测过
        m.put("playMsg", playMsg);
        m.put("lastPlayAt", lastPlayAt);
            m.put("classes", lastClasses);
            m.put("list", lastList);
            m.put("probeAt", lastProbeAt == 0 ? null : new java.util.Date(lastProbeAt).toString());
            m.put("probeTs", lastProbeAt == 0 ? null : lastProbeAt);
            return m;
        }
    }

    private final Map<String, Site> sites = new ConcurrentHashMap<>();
    private final Map<String, Spider> instances = new ConcurrentHashMap<>();

    // ── 实例数量治理 ──
    // 原先 instances 只进不出：站一多（实测 189 个）就常驻 189 个 spider，
    // 每个都可能自带缓存，512m 堆很容易被压死（这台 2 核 Celeron 已经因此崩过两次 GC）。
    // 现在做「空闲回收 + 总量上限」：超过 MAX_INSTANCES 就按最久未用先回收。
    private static final int MAX_INSTANCES = 60;
    private static final long IDLE_MS = 15 * 60 * 1000L;
    private final Map<String, Long> lastUsed = new ConcurrentHashMap<>();
    private final AtomicLong evicted = new AtomicLong();
    private final Map<String, String> loadErrors =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private ClassLoader spiderLoader;
    /** 站点自带 jar 的 ClassLoader（按 jar URL 缓存，父加载器是 spiderLoader）。 */
    private final Map<String, ClassLoader> jarLoaders = new ConcurrentHashMap<>();
    /** jar URL → 本地缓存文件；由 Hub 注入（负责下载 + 缓存 + 镜像回退）。 */
    public java.util.function.Function<String, File> jarResolver;

    private static String shortCls(String cls) {
        if (cls == null) return "?";
        int i = cls.lastIndexOf('.');
        return i > 0 ? cls.substring(i + 1) : cls;
    }

    /**
     * 判这个站点的 spider 类能不能加载。只做 Class.forName(..., false, ...)，
     * 不初始化静态块、不实例化、不调 spider 任何方法，所以不会触发 45s 超时。
     *
     * 站点自带 jar 会被这次判定顺手下载并缓存（jarResolver），之后的探测直接命中缓存。
     */
    public Boolean judgeClass(Site s) {
        if (s == null) return Boolean.FALSE;
        Boolean cached = s.classOk;
        if (cached != null) return cached;
        if (s.cls == null || s.cls.isBlank()) {
            s.classMsg = "站点没有 class 字段，无法实例化";
            s.classOk = Boolean.FALSE;
            return Boolean.FALSE;
        }
        synchronized (s) {
            if (s.classOk != null) return s.classOk;
            Throwable lastErr = null;
            for (ClassLoader ld : loadersFor(s)) {
                if (ld == null) continue;
                try {
                    Class.forName(s.cls, false, ld);
                    s.classOk = Boolean.TRUE;
                    s.classMsg = null;
                    return Boolean.TRUE;
                } catch (Throwable t) {
                    lastErr = t;
                }
            }
            boolean hasOwnJar = s.jar != null && !s.jar.isBlank();
            s.classMsg = hasOwnJar
                    ? "站点自带 jar 提供不了 " + shortCls(s.cls)
                      + "（多为加壳 APK/dex，JVM 无法加载；Android 端的 DexClassLoader 才能跑）"
                    : "内置 spider jar 里没有 " + shortCls(s.cls) + "（发布方未开源该类）";
            if (lastErr != null && !(lastErr instanceof ClassNotFoundException)) {
                s.classMsg += " · " + rootMsg(lastErr);
            }
            s.classOk = Boolean.FALSE;
            return Boolean.FALSE;
        }
    }

    /**
     * 载入后先做一次「只查全局 classpath」的快速预判（纯 zip 查询，不联网）。
     * 命中的站点立刻知道能跑；没命的留 null，等探测时再带站点自带 jar 判一次。
     */
    public int judgeClassesFast() {
        int ok = 0;
        for (Site s : sites.values()) {
            if (s.classOk != null || s.cls == null || s.cls.isBlank()) continue;
            try {
                Class.forName(s.cls, false, spiderLoader);
                s.classOk = Boolean.TRUE;
                ok++;
            } catch (Throwable ignore) {
                // 留给探测时带站点 jar 再判
            }
        }
        return ok;
    }

    /** 已被判定为不可加载的站点数。 */
    public int unsupportedCount() {
        int n = 0;
        for (Site s : sites.values()) if (Boolean.FALSE.equals(s.classOk)) n++;
        return n;
    }

    public Collection<Site> allSites() {
        return sites.values();
    }

    public Site site(String key) {
        return sites.get(key);
    }

    public Map<String, String> loadErrors() {
        synchronized (loadErrors) {
            return new LinkedHashMap<>(loadErrors);   // 快照，避免调用方遍历时被写
        }
    }

    public int size() {
        return sites.size();
    }

    /**
     * 从 TVBox 配置 JSON 文本建立站点表。
     * @param json 配置内容（支持多仓 urls / base64 由调用方先处理）
     * @param baseUrl 用于拼接相对 api
     */
    /** 是否允许访问网盘类源（默认 false，可在 hub 界面打开）。 */
    public volatile boolean allowNetdisk = false;

    public void loadFromConfig(String json, String baseUrl) throws Exception {
        sites.clear();
        instances.clear();
        // 旧 jar 的 URLClassLoader 必须显式关掉并清空，否则每次 reload 都把类留在 metaspace，
        // 多仓配置反复重载必然 OOM（-XX:MaxMetaspaceSize=256m）。
        for (ClassLoader ld : jarLoaders.values()) {
            try { if (ld instanceof java.net.URLClassLoader) ((java.net.URLClassLoader) ld).close(); } catch (Exception ignore) { }
        }
        jarLoaders.clear();
        loadErrors.clear();
        netdiskCount = 0;

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
            // 站点自带 jar：TVBox 配置里很常见（多仓里各子仓各带一份 spider.jar），
            // 不认这个字段 → ClassNotFoundException，整站等于不存在。
            site.jar = str(s.get("jar"));

            if (site.key == null || site.key.isEmpty()) site.key = site.name;
            if (site.key == null) continue;

            // 只收 csp_ 开头的 spider 站点
            if (site.type != 3 || site.api == null || !site.api.startsWith("csp_")) continue;
            site.cls = "com.github.catvod.spider." + site.api.substring(4);
            site.netdisk = isNetdiskClass(site.cls);
            sites.put(site.key, site);
            if (site.netdisk) netdiskCount++;
        }
    }

    /** 加载 spider 的 classpath（dex2jar 产物 + 依赖）。 */
    public void setSpiderJars(List<File> jars) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (File f : jars) {
            if (f != null && f.isFile()) urls.add(f.toURI().toURL());
        }
        // 父加载器用系统 classpath，保证基类和 android stub 能解析到
        try {
            com.tvboxhost.AndroidCrypto.install();   // 安卓填充名 → JCE 填充名
        } catch (Throwable t) {
            System.err.println("[spider] 注册 Android 加密别名失败: " + t);
        }
        spiderLoader = new URLClassLoader(urls.toArray(new URL[0]), SpiderPool.class.getClassLoader());
        initCatvodContext(spiderLoader);
    }

    /**
     * 把我们的 android Context stub 交给 catvod 的 Init。
     *
     * Init.context() 读的是它自己的静态字段，没人调 Init.init(ctx) 就永远是 null。
     * 线上表现：/v/<key>?ac=list 有列表，但 ac=detail 直接
     *   NullPointerException: Cannot invoke "android.content.Context.getPackageName()"
     *   because the return value of "com.github.catvod.spider.Init.context()" is null
     * → 详情拿不到播放地址 → 同步写不出任何一个 strm 文件。
     *
     * 用反射调，避免编译期强依赖（不同 jar 的 Init 签名可能有差异）。
     */
    private static volatile boolean catvodContextReady = false;

    static synchronized void initCatvodContext(ClassLoader loader) {
        if (catvodContextReady) return;
        android.app.Application app = android.app.Application.getInstance();
        try {
            Class<?> initCls = Class.forName("com.github.catvod.spider.Init", true, loader);

            // 先试官方入口
            try {
                initCls.getMethod("init", android.content.Context.class).invoke(null, app);
            } catch (Throwable ignore) {
            }

            // Init.context() 的实现是：get().c —— 而 c 只在 InitCheck.check(ctx) 通过时才会被
            // init() 赋值。我们的 android 桩过不了那个 check，所以这里直接把字段塞进去。
            if (readInitContext(initCls) == null) {
                Object inst = initCls.getMethod("get").invoke(null);
                System.out.println("[spider] Init.get() = " + inst);
                for (java.lang.reflect.Field f : initCls.getDeclaredFields()) {
                    System.out.println("[spider]   字段 " + f.getName() + " : " + f.getType().getName());
                }
                for (String fname : new String[]{"c"}) {
                    try {
                        java.lang.reflect.Field f = initCls.getDeclaredField(fname);
                        System.out.println("[spider] 目标字段类型=" + f.getType().getName()
                                + " 桩类型=" + app.getClass().getName()
                                + " 同类型=" + (f.getType() == app.getClass()));
                        f.setAccessible(true);
                        f.set(inst, app);
                        System.out.println("[spider] 已写入 Init." + fname);
                    } catch (Throwable inner) {
                        System.out.println("[spider] 写 " + fname + " 失败: " + inner);
                    }
                }
            }

            // ★ 必须置 InitCheck.ok = true ★
            // catvod 混淆代码里有个闸门 merge.m.Gate.pass()，它只读 InitCheck.ok；
            // 而 InitCheck.ok 只有在 InitCheck.check(Context) 校验过 APK 签名后才为 true ——
            // JVM 上永远过不了。ok=false 时走 okhttp 的 HTTP 助手全部退化：
            //     merge.k.b.a()      → null（OkHttpClient 建不出来）
            //     merge.k.b.h(url)   → ""（空响应）
            //     merge.k.b.f(...)   → null → 调用处直接 NPE
            // 实测影响：AppGet(csp_Bili ×10)、AppDrama、App99、一大批 AppXxx 全站 0 数据。
            // 置 true 等价于"签名校验通过"。
            try {
                Class<?> initCheck = Class.forName("com.github.catvod.spider.merge.m.InitCheck", true, loader);
                java.lang.reflect.Field okField = initCheck.getDeclaredField("ok");
                okField.setAccessible(true);
                okField.setBoolean(null, true);
                System.out.println("[spider] InitCheck.ok = true（放行 okhttp 通道）");
            } catch (Throwable t) {
                System.out.println("[spider] InitCheck.ok 置位失败: " + t);
            }

            Object got = readInitContext(initCls);
            catvodContextReady = got != null;
            System.out.println(catvodContextReady
                    ? "[spider] Init.context() 就绪，detailContent 可用"
                    : "[spider] Init.context() 仍然为 null —— 详情解析会失败");
        } catch (Throwable t) {
            System.out.println("[spider] 注入 Context 失败（详情可能失败）: " + t);
        }
    }

    /** 反射调 Init.context() 看当前是否为 null。 */
    private static Object readInitContext(Class<?> initCls) {
        try {
            return initCls.getMethod("context").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取（或创建）一个 spider 实例。线程安全：同一 key 只创建一个。 */
    /** 载入时统计的网盘源数量（给界面显示「已排除 N 个」）。 */
    public volatile int netdiskCount = 0;

    /**
     * 判断一个 spider 类是不是网盘类：沿继承链找 com.github.catvod.spider.Pan。
     * 比按类名/站名猜准得多 —— 实测 com.github.catvod.spider.Wn4k、Juying、HJZhenCai
     * 这些名字里没有 Pan 的，实际都 extends Pan。
     */
    public boolean isNetdiskClass(String cls) {
        try {
            Class<?> c = Class.forName(cls, false, spiderLoader);
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                if (k.getName().equals("com.github.catvod.spider.Pan")) return true;
            }
            return false;
        } catch (Throwable t) {
            // 类加载不了（缺类/stub 不全）→ 没法判定，按非网盘处理，留给探测去发现
            return false;
        }
    }

    /** 仅按名字兜底（插件侧拿不到继承链时也用这套规则）。 */
    public static boolean looksLikeNetdisk(String cls, String api, String name) {
        String c = cls == null ? "" : cls;
        String a = api == null ? "" : api;
        String n = name == null ? "" : name;
        if (a.startsWith("csp_Pan")) return true;
        if (c.contains(".Pan")) return true;
        for (String k : new String[]{"网盘", "云盘", "盘搜", "搜盘", "夸克", "115", "迅雷", "百度盘",
                "天翼", "阿里云", "UC网盘", "123云盘", "光鸭", "分享"}) {
            if (n.contains(k)) return true;
        }
        return false;
    }

    public Spider get(String key) throws Exception {
        Site site = sites.get(key);
        if (site == null) throw new IllegalArgumentException("没有这个站点: " + key);
        if (site.netdisk && !allowNetdisk) {
            // 明确告诉调用方原因，别让它以为站点坏了
            throw new IllegalArgumentException("网盘类源已排除（" + site.name + "：需要夸克/115/迅雷等账号才能出直链）");
        }

        Spider cached = instances.get(key);
        if (cached != null) {
            lastUsed.put(key, System.currentTimeMillis());
            maybeEvict();
            return cached;
        }

        synchronized (this) {
            cached = instances.get(key);
            if (cached != null) return cached;

            Class<?> c = null;
            Throwable lastErr = null;
            // 先用站点自带的 jar（如果有），再用全局 jar 兜底
            for (ClassLoader ld : loadersFor(site)) {
                try {
                    c = Class.forName(site.cls, true, ld);
                    break;
                } catch (Throwable t) {
                    lastErr = t;
                }
            }
            if (c == null) {
                record(key, "类加载失败: " + rootMsg(lastErr));
                throw new Exception("类加载失败(" + site.cls + "): " + rootMsg(lastErr), lastErr);
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
            lastUsed.put(key, System.currentTimeMillis());
            maybeEvict();
            return spider;
        }
    }

    /** 空闲/超额实例回收：先收空闲超过 15 分钟的，再看总量是否超上限。 */
    private void maybeEvict() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : lastUsed.entrySet()) {
            if (now - e.getValue() > IDLE_MS && instances.size() > 8 && !pinned.contains(e.getKey())) {
                if (instances.remove(e.getKey()) != null) {
                    lastUsed.remove(e.getKey());
                    evicted.incrementAndGet();
                }
            }
        }
        if (instances.size() <= MAX_INSTANCES) return;
        // 超上限：按最久未用先回收，收到上限的 80% 为止，避免每次都触发
        List<Map.Entry<String, Long>> ordered = new ArrayList<>(lastUsed.entrySet());
        ordered.sort(Map.Entry.comparingByValue());
        int target = (int) (MAX_INSTANCES * 0.8);
        for (Map.Entry<String, Long> e : ordered) {
            if (instances.size() <= target) break;
            if (pinned.contains(e.getKey())) continue;   // 同步名单常驻
            if (instances.remove(e.getKey()) != null) {
                lastUsed.remove(e.getKey());
                evicted.incrementAndGet();
            }
        }
        System.out.println("[pool] spider 实例超过 " + MAX_INSTANCES + " 个，已回收到 " + instances.size()
                + " 个（累计回收 " + evicted.get() + "）");
    }

    /** 实例治理状态，给 /api/status 用。 */
    public Map<String, Object> instanceStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cached", instances.size());
        m.put("pinned", pinned.size());
        m.put("max", MAX_INSTANCES);
        m.put("evicted", evicted.get());
        return m;
    }

    /** 全局 spider ClassLoader（给反射调 jar 内工具类用，比如 Qr115 扫码登录）。 */
    public ClassLoader loader() {
        return spiderLoader;
    }

    /**
     * 该站点可用的 ClassLoader 列表：站点自带 jar 优先，全局 jar 兜底。
     * 父加载器是全局 spiderLoader，所以基类（Spider / android stub）都能解析。
     */
    private List<ClassLoader> loadersFor(Site site) {
        List<ClassLoader> out = new ArrayList<>();
        if (site.jar != null && !site.jar.isBlank() && jarResolver != null) {
            ClassLoader ld = jarLoaders.computeIfAbsent(site.jar, url -> {
                try {
                    File f = jarResolver.apply(url);
                    if (f == null || !f.isFile()) {
                        System.err.println("[spider] 站点 jar 取不到: " + url);
                        return spiderLoader;
                    }
                    return new URLClassLoader(new URL[] { f.toURI().toURL() }, spiderLoader);
                } catch (Throwable t) {
                    System.err.println("[spider] 站点 jar 加载失败: " + url + " → " + rootMsg(t));
                    return spiderLoader;
                }
            });
            out.add(ld);
        }
        if (!out.contains(spiderLoader)) out.add(spiderLoader);
        return out;
    }

    /** 记录一次成功。 */
    public void markOk(String key) {
        Site s = sites.get(key);
        if (s != null) {
            s.lastOk = System.currentTimeMillis();
            s.lastError = null;
        }
    }

    /**
     * 记录一次探测结果（homeContent 的分类数 / 列表条目数）。
     *
     * 和 markOk 的区别：markOk 只说明「调用没抛异常」，返回 0 条也算 success；
     * 这里额外记住有没有真拿到内容，界面测试列才能区分「可用」和「无数据」。
     */
    public void markData(String key, int classes, int list) {
        Site s = sites.get(key);
        if (s == null) return;
        long now = System.currentTimeMillis();
        s.lastProbeAt = now;
        s.lastClasses = classes;
        s.lastList = list;
        s.hasData = classes > 0 || list > 0;
        if (s.hasData) {
            s.lastOk = now;
            s.lastError = null;
        }
    }

    /**
     * 从磁盘恢复一次探测结果（hub 重启后测试列不清空）。
     * err 非空表示上次是失败，此时 data 一律 false。
     */
    /**
     * 同步名单里的站点：常驻内存，不被 LRU 淘汰。
     * 金牌影视这类 spider 冷启动要 30~60 秒（上游接口慢 + jar 初始化），
     * 刚被淘汰又赶上插件 45 秒超时，就会表现成「0 个条目」。
     */
    private final java.util.Set<String> pinned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void pin(java.util.Collection<String> keys) {
        if (keys == null) return;
        for (String k : keys) if (k != null && !k.isBlank()) pinned.add(k);
    }

    public void unpin(java.util.Collection<String> keys) {
        if (keys == null) return;
        for (String k : keys) if (k != null) pinned.remove(k);
    }

    public boolean isPinned(String key) {
        return pinned.contains(key);
    }

    /** 预热：实例化并 init，不取数据。失败不影响调用方。 */
    public void warm(String key) {
        try {
            get(key);
            lastUsed.put(key, System.currentTimeMillis());
        } catch (Exception ignore) {
        }
    }

    public int pinnedCount() {
        return pinned.size();
    }

    /** 记录「能不能播」的检测结果。 */
    public void markPlayable(String key, Boolean ok, String msg) {
        Site s = sites.get(key);
        if (s == null) return;
        s.playable = ok;
        s.playMsg = msg;
        s.lastPlayAt = System.currentTimeMillis();
    }

    public void restoreProbe(String key, int classes, int list, long at, String err) {
        Site s = sites.get(key);
        if (s == null || at <= 0) return;
        s.lastProbeAt = at;
        s.lastClasses = classes;
        s.lastList = list;
        if (err != null && !err.isBlank()) {
            s.hasData = false;
            s.lastError = err;
            s.lastOk = 0;
        } else {
            s.hasData = classes > 0 || list > 0;
            s.lastError = null;
            s.lastOk = s.hasData ? at : 0;
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
            if (s.netdisk) continue;                    // 网盘类不参与可用性统计
            // 探测过（lastProbeAt>0）就只认真的拿到了内容；没探测过才按「调用成功」算
            boolean usable = s.lastError == null
                    && (s.lastProbeAt > 0 ? s.hasData : s.lastOk > 0);
            if (usable) ok++;
            else bad++;
        }
        int playableYes = 0, playableNo = 0, playableUnknown = 0;
        for (Site s : sites.values()) {
            if (s.netdisk) continue;
            if (s.playable == null) playableUnknown++;
            else if (s.playable) playableYes++;
            else playableNo++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", sites.size());
        m.put("ok", ok);
        m.put("unknown", bad);
        m.put("netdisk", netdiskCount);
        m.put("syncable", sites.size() - netdiskCount);
        m.put("playable", playableYes);
        m.put("unplayable", playableNo);
        m.put("playUnknown", playableUnknown);
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
