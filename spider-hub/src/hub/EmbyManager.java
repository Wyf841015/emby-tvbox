package hub;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Emby 插件安装器 —— 检测 + 安装 + 卸载。
 *
 * 检测为什么要这么多路子：Emby 的 programdata 位置五花八门，
 *   fnOS 应用   /volN/@appdata/EmbyServer4-9        （应用名带版本号，programdata 就是它）
 *   官方 deb    /var/lib/emby                       （也可能用 -programdata 自定义）
 *   Docker      /volN/docker/emby/config
 *   手工运行    -programdata /path                  （只有进程 cmdline 里才看得到）
 *   自编译      /opt/emby-server/programdata
 * 所以按"可靠性从高到低"依次尝试：
 *   1. 运行中的进程 cmdline（最准，能拿到真实 -programdata）
 *   2. systemd unit 的 ExecStart
 *   3. fnOS 应用数据目录（名字里带 emby 的）
 *   4. Docker 挂载目录
 *   5. 常见默认路径
 *
 * 每个候选还要分类：
 *   ready          已初始化（有 config/system.xml）→ 可以直接装插件
 *   uninitialized  目录在但没初始化（fnOS 应用刚装/被清空）→ 必须先启动一次 Emby
 */
public class EmbyManager {

    public static final String STATE_READY = "ready";
    public static final String STATE_INIT = "uninitialized";

    public static class Instance {
        public String programdata;
        public String pluginsDir;
        public String source;        // 怎么找到的
        public String state = STATE_READY;
        public boolean running;      // 有进程正在用它
        public boolean writable;
        public boolean exists;
        public long mtime;
        public String hint;          // 给界面看的说明

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("programdata", programdata);
            m.put("plugins", pluginsDir);
            m.put("source", source);
            m.put("state", state);
            m.put("running", running);
            m.put("writable", writable);
            m.put("exists", exists);
            m.put("mtime", mtime == 0 ? null : new java.util.Date(mtime).toString());
            m.put("hint", hint);
            return m;
        }
    }

    // ───────────────────────── 检测 ─────────────────────────

    /**
     * 扫描本机所有可能的 Emby programdata。
     * @param extra 用户手工指定的路径，优先验证
     */
    public static List<Instance> detect(String extra) {
        List<Instance> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // 1) 手工指定
        if (extra != null && !extra.isBlank()) {
            Instance i = probe(Path.of(extra.trim()), "手动指定");
            if (i != null) add(out, seen, i);
        }

        // 2) 运行中的进程（最准）：cmdline 里有 EmbyServer.dll 或 emby-server，抓 -programdata
        for (String pd : programdataFromProcesses()) {
            Instance i = probe(Path.of(pd), "运行中的进程");
            if (i != null) {
                i.running = true;
                add(out, seen, i);
            }
        }

        // 3) systemd unit 的 ExecStart
        for (String pd : programdataFromSystemd()) {
            Instance i = probe(Path.of(pd), "systemd 服务");
            if (i != null) add(out, seen, i);
        }

        // 4) fnOS 应用数据目录（EmbyServer4-9 这种名字）
        for (String vol : volumes()) {
            for (String sub : new String[]{"@appdata", "@appcenter"}) {
                Path root = Path.of(vol, sub);
                if (!Files.isDirectory(root)) continue;
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                    for (Path child : ds) {
                        String n = child.getFileName().toString().toLowerCase();
                        if (!n.contains("emby")) continue;
                        for (Path cand : fnosCandidates(child)) {
                            Instance i = probe(cand, sub.equals("@appdata") ? "fnOS 应用数据" : "fnOS 应用目录");
                            if (i != null) add(out, seen, i);
                        }
                    }
                } catch (Exception ignore) {
                }
            }
        }

        // 5) Docker 挂载目录
        for (String vol : volumes()) {
            Path docker = Path.of(vol, "docker");
            if (!Files.isDirectory(docker)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(docker)) {
                for (Path container : ds) {
                    for (Path cand : dockerCandidates(container)) {
                        Instance i = probe(cand, "docker");
                        if (i != null) add(out, seen, i);
                    }
                }
            } catch (Exception ignore) {
            }
        }

        // 6) 常见默认位置
        for (String p : new String[]{
                "/var/lib/emby", "/opt/emby-server/programdata", "/opt/emby-server/var",
                "/usr/lib/emby-server/var", "/home/emby/.config/emby-server",
                "/root/.config/emby-server", "/var/lib/emby-server"}) {
            Instance i = probe(Path.of(p), "默认路径");
            if (i != null) add(out, seen, i);
        }

        // 排序：就绪 > 运行中 > 未初始化；就绪里再按修改时间
        out.sort(Comparator
                .comparingInt((Instance i) -> STATE_READY.equals(i.state) ? 0 : 1)
                .thenComparingInt(i -> i.running ? 0 : 1)
                .thenComparing(Comparator.comparingLong((Instance i) -> i.mtime).reversed()));
        return out;
    }

    private static void add(List<Instance> out, Set<String> seen, Instance i) {
        String key = i.programdata;
        try {
            key = Path.of(i.programdata).toRealPath().toString();
        } catch (Exception ignore) {
        }
        if (seen.contains(key)) {
            // 同一个目录被多条路子找到：合并信息（运行中优先级高）
            for (Instance e : out) {
                if (e.programdata.equals(i.programdata)) {
                    if (i.running) { e.running = true; e.source = i.source; }
                    if (STATE_READY.equals(i.state)) e.state = STATE_READY;
                    return;
                }
            }
            return;
        }
        seen.add(key);
        out.add(i);
    }

    /** fnOS 应用目录里可能的 programdata 位置。 */
    private static List<Path> fnosCandidates(Path base) {
        List<Path> list = new ArrayList<>();
        list.add(base);                                   // EmbyServer4-9 本身就是 programdata
        list.add(base.resolve("var"));
        list.add(base.resolve("data"));
        list.add(base.resolve("config"));
        list.add(base.resolve("programdata"));
        list.add(base.resolve("emby"));
        return list;
    }

    private static List<Path> dockerCandidates(Path container) {
        List<Path> list = new ArrayList<>();
        list.add(container.resolve("config"));
        list.add(container.resolve("programdata"));
        list.add(container.resolve("emby"));
        list.add(container.resolve("data"));
        return list;
    }

    /**
     * 判断一个目录是不是 Emby programdata，并分类。
     * 三种接受情形：
     *   - config/system.xml 在        → ready
     *   - 有 plugins/cache/metadata   → uninitialized（有痕迹但没初始化完）
     *   - 目录名带 emby 且存在        → uninitialized（fnOS 应用刚装好还没启动）
     */
    private static Instance probe(Path dir, String source) {
        try {
            if (dir == null || !Files.isDirectory(dir)) return null;

            boolean hasSystemXml = Files.isRegularFile(dir.resolve("config").resolve("system.xml"));
            Path plugins = dir.resolve("plugins");
            boolean hasPlugins = Files.isDirectory(plugins);
            boolean hasTrace = hasPlugins
                    || Files.isDirectory(dir.resolve("cache"))
                    || Files.isDirectory(dir.resolve("metadata"))
                    || Files.isDirectory(dir.resolve("config"));

            String name = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase();
            boolean nameLooksEmby = name.contains("emby");

            if (!hasSystemXml && !hasTrace && !nameLooksEmby) return null;

            // 排除「应用安装目录」：fnOS 的 @appcenter/EmbyServer4-9 里有 target/system/EmbyServer，
            // 那是程序本体不是 programdata，认成 programdata 会让用户装错地方。
            if (Files.exists(dir.resolve("system").resolve("EmbyServer.dll"))
                    || Files.isRegularFile(dir.resolve("system").resolve("EmbyServer"))
                    || Files.isDirectory(dir.resolve("target").resolve("system"))) {
                return null;
            }

            Instance i = new Instance();
            i.programdata = dir.toAbsolutePath().toString();
            i.pluginsDir = plugins.toAbsolutePath().toString();
            i.source = source;
            i.exists = true;
            i.writable = Files.isWritable(dir);

            if (hasSystemXml) {
                i.state = STATE_READY;
                i.hint = "已初始化，可直接安装插件";
            } else {
                i.state = STATE_INIT;
                i.hint = hasTrace
                        ? "目录有残留但缺 config/system.xml，启动一次 Emby 即可补齐"
                        : "目录是空的，先在 fnOS 应用中心启动一次 Emby（首次启动会生成 config/system.xml）";
            }

            try {
                Path probeFile = hasSystemXml ? dir.resolve("config").resolve("system.xml") : dir;
                i.mtime = Files.getLastModifiedTime(probeFile).toMillis();
            } catch (Exception ignore) {
            }
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    // ───────────────────────── 从进程 / systemd 里扒 programdata ─────────────────────────

    private static List<String> programdataFromProcesses() {
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of("/proc"))) {
            for (Path p : ds) {
                String pid = p.getFileName().toString();
                if (!pid.matches("\\d+")) continue;
                String cmd;
                try {
                    cmd = Files.readString(p.resolve("cmdline"), StandardCharsets.UTF_8).replace('\0', ' ');
                } catch (Exception ignore) {
                    continue;
                }
                String low = cmd.toLowerCase();
                if (!low.contains("emby")) continue;
                String pd = extractProgramdata(cmd);
                if (pd != null && !out.contains(pd)) out.add(pd);
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    private static List<String> programdataFromSystemd() {
        List<String> out = new ArrayList<>();
        for (String dir : new String[]{"/etc/systemd/system", "/lib/systemd/system", "/usr/lib/systemd/system"}) {
            File[] fs = new File(dir).listFiles((d, n) -> n.toLowerCase().contains("emby") && n.endsWith(".service"));
            if (fs == null) continue;
            for (File f : fs) {
                try {
                    String txt = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    String pd = extractProgramdata(txt);
                    if (pd != null && !out.contains(pd)) out.add(pd);
                } catch (Exception ignore) {
                }
            }
        }
        return out;
    }

    /** 从命令行/unit 文本里抓 -programdata 后面的路径。 */
    private static String extractProgramdata(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:--programdata|-programdata|/programdata)[ =]+\"?([^\"\\s]+)\"?")
                .matcher(text);
        if (m.find()) {
            String p = m.group(1).trim();
            if (p.startsWith("/")) return p;
        }
        return null;
    }

    private static List<String> volumes() {
        List<String> v = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of("/"))) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.matches("vol\\d+")) v.add(p.toString());
            }
        } catch (Exception ignore) {
        }
        if (v.isEmpty()) v.add("/volume1");
        v.sort(String::compareTo);
        return v;
    }

    // ───────────────────────── 安装 / 卸载 / 状态 ─────────────────────────

    public static Map<String, Object> install(String programdata, byte[] dllBytes, String configXml) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            Path root = Path.of(programdata);
            if (!Files.isDirectory(root)) {
                r.put("ok", false);
                r.put("msg", "Emby 数据目录不存在：" + programdata);
                return r;
            }
            boolean initialized = Files.isRegularFile(root.resolve("config").resolve("system.xml"));
            if (!initialized) {
                r.put("ok", false);
                r.put("msg", "这个目录还没被 Emby 初始化（缺 config/system.xml），"
                        + "先在 fnOS 应用中心启动一次 Emby，等它生成配置后再装插件");
                return r;
            }

            Path plugins = root.resolve("plugins");
            if (!Files.isDirectory(plugins)) Files.createDirectories(plugins);

            Path target = plugins.resolve("TvBox.dll");
            String backup = null;
            if (Files.isRegularFile(target)) {
                Path bak = plugins.resolve("TvBox.dll.bak-" + System.currentTimeMillis());
                Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
                backup = bak.toString();
            }
            // 不能直接 Files.write 覆盖：Emby 正在跑，这个 DLL 已被 mmap，
            // 截断它会触发 SIGBUS 把 Emby 打挂。写成临时文件再原子 rename，
            // 老 inode 留给运行中的进程，新文件下次启动生效。
            atomicWrite(target, dllBytes);

            String configPath = null;
            if (configXml != null && !configXml.isBlank()) {
                Path cfgDir = plugins.resolve("configurations");
                if (!Files.isDirectory(cfgDir)) Files.createDirectories(cfgDir);
                Path cfg = cfgDir.resolve("TvBox.xml");
                atomicWrite(cfg, configXml.getBytes(StandardCharsets.UTF_8));
                configPath = cfg.toString();
            }

            r.put("ok", true);
            r.put("dll", target.toString());
            r.put("size", Files.size(target));
            r.put("config", configPath);
            r.put("backup", backup);
            r.put("needRestart", true);
            r.put("msg", "已安装到 " + target + "，重启 Emby 后生效");
        } catch (Exception e) {
            r.put("ok", false);
            r.put("msg", "安装失败: " + explain(e));
        }
        return r;
    }

    public static Map<String, Object> uninstall(String programdata) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            Path target = Path.of(programdata).resolve("plugins").resolve("TvBox.dll");
            if (!Files.isRegularFile(target)) {
                r.put("ok", false);
                r.put("msg", "没找到已安装的 TvBox.dll");
                return r;
            }
            Path bak = target.resolveSibling("TvBox.dll.removed-" + System.currentTimeMillis());
            Files.move(target, bak, StandardCopyOption.REPLACE_EXISTING);
            r.put("ok", true);
            r.put("msg", "已移出到 " + bak.getFileName() + "，重启 Emby 后卸载");
        } catch (Exception e) {
            r.put("ok", false);
            r.put("msg", "卸载失败: " + explain(e));
        }
        return r;
    }

    public static Map<String, Object> status(String programdata) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Path root = Path.of(programdata);
            Path target = root.resolve("plugins").resolve("TvBox.dll");
            boolean installed = Files.isRegularFile(target);
            m.put("installed", installed);
            m.put("path", target.toString());
            m.put("initialized", Files.isRegularFile(root.resolve("config").resolve("system.xml")));
            m.put("exists", Files.isDirectory(root));
            if (installed) {
                m.put("size", Files.size(target));
                m.put("mtime", new java.util.Date(Files.getLastModifiedTime(target).toMillis()).toString());
            }
        } catch (Exception e) {
            m.put("installed", false);
            m.put("error", explain(e));
        }
        return m;
    }


    // ───────────────────────── 媒体库目录检测 ─────────────────────────

    /**
     * 从 Emby programdata 里挖出已配置的媒体库目录。
     *
     * Emby 把这信息分散在三处，单一来源都不可靠，所以三路都扫再合并：
     *   1. root/default/<库>/*.mblink  —— 每个媒体文件夹一个链接文件，正文就是路径
     *   2. config/library/*            —— 库的选项文件，里面会有 <Path> 之类
     *   3. data/library.db             —— SQLite，直接按字节找绝对路径串（无需驱动）
     */
    public static List<Map<String, Object>> detectLibraries(String programdata) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Path root;
        try {
            root = Path.of(programdata);
        } catch (Exception e) {
            return out;
        }
        if (!Files.isDirectory(root)) return out;

        // 1) .mblink
        try (Stream<Path> s = Files.walk(root.resolve("root"), 6)) {
            s.filter(Files::isRegularFile)
             .filter(f -> {
                 String n = f.getFileName().toString().toLowerCase();
                 return n.endsWith(".mblink") || n.endsWith(".mblink.link");
             })
             .limit(500)
             .forEach(f -> {
                 try {
                     String txt = Files.readString(f, StandardCharsets.UTF_8).trim();
                     addLib(out, seen, txt, "媒体库链接文件", root);
                     // 有些版本把路径 base64 存
                     try {
                         String dec = new String(java.util.Base64.getDecoder().decode(txt), StandardCharsets.UTF_8);
                         if (dec.startsWith("/")) addLib(out, seen, dec.trim(), "媒体库链接文件(base64)", root);
                     } catch (Exception ignore) {
                     }
                 } catch (Exception ignore) {
                 }
             });
        } catch (Exception ignore) {
        }

        // 2) config/library/*
        Path libCfg = root.resolve("config").resolve("library");
        if (Files.isDirectory(libCfg)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(libCfg)) {
                for (Path f : ds) {
                    if (!Files.isRegularFile(f)) continue;
                    try {
                        long size = Files.size(f);
                        if (size > 4L * 1024 * 1024) continue;
                        String txt = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                        for (String abs : extractAbsPaths(txt)) {
                            addLib(out, seen, abs, "config/library/" + f.getFileName(), root);
                        }
                    } catch (Exception ignore) {
                    }
                }
            } catch (Exception ignore) {
            }
        }

        // 3) data/library.db —— 按字节找绝对路径
        for (String dbName : new String[]{"library.db", "library.db-wal"}) {
            Path db = root.resolve("data").resolve(dbName);
            if (!Files.isRegularFile(db)) continue;
            try (java.io.InputStream in = Files.newInputStream(db)) {
                byte[] head = in.readNBytes(16 * 1024 * 1024);   // 最多读 16MB
                String txt = new String(head, StandardCharsets.UTF_8);
                for (String abs : extractAbsPaths(txt)) {
                    addLib(out, seen, abs, "data/" + dbName, root);
                }
            } catch (Exception ignore) {
            }
        }

        // 排序：存在的在前，可写的优先，层级浅的优先
        out.sort(java.util.Comparator
                .comparingInt((Map<String, Object> m) -> Boolean.TRUE.equals(m.get("exists")) ? 0 : 1)
                .thenComparingInt(m -> Boolean.TRUE.equals(m.get("writable")) ? 0 : 1)
                .thenComparingInt(m -> String.valueOf(m.get("path")).length()));
        return out;
    }

    private static void addLib(List<Map<String, Object>> out, Set<String> seen,
                               String raw, String source, Path programdata) {
        String path = cleanPath(raw);
        if (path == null) return;
        // 排除 Emby 自己的数据目录、系统目录
        if (path.startsWith(programdata.toString())) return;
        if (path.startsWith("/proc") || path.startsWith("/sys") || path.startsWith("/dev")) return;
        if (path.split("/").length < 3) return;          // 至少 /vol1/xxx
        if (seen.contains(path)) return;
        seen.add(path);

        Path p = Path.of(path);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("source", source);
        m.put("exists", Files.isDirectory(p));
        m.put("writable", Files.isDirectory(p) && Files.isWritable(p));
        // 猜一下库类型，方便界面提示
        String low = path.toLowerCase();
        if (low.contains("movie") || low.contains("电影")) m.put("kind", "电影");
        else if (low.contains("动漫") || low.contains("anime")) m.put("kind", "动漫");
        else if (low.contains("tv") || low.contains("show") || low.contains("剧") || low.contains("series")) m.put("kind", "剧集");
        else if (low.contains("music") || low.contains("音乐")) m.put("kind", "音乐");
        else if (low.contains("photo") || low.contains("相册")) m.put("kind", "照片");
        else m.put("kind", "未知");
        out.add(m);
    }

    /**
     * 从任意文本里抽绝对路径。
     * 注意：字符类里用 \x2d 表示 '-'，避免被当成范围；
     * 汉字区间直接写成 \u4e00-\u9fa5（Java 预处理会转成真字符）。
     */
    private static final java.util.regex.Pattern ABS_PATH = java.util.regex.Pattern.compile(
            "/(?:vol[0-9]+|volume[0-9]+|mnt|media|share)/[A-Za-z0-9_./@# \u4e00-\u9fa5-]{2,200}");

    private static List<String> extractAbsPaths(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        java.util.regex.Matcher m = ABS_PATH.matcher(text);
        while (m.find()) {
            String p = cleanPath(m.group());
            if (p != null && !out.contains(p)) out.add(p);
            if (out.size() > 200) break;
        }
        return out;
    }

    /** 归一化：去掉尾部的控制字符、引号、常见后缀噪声。 */
    private static String cleanPath(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        // 去掉 XML/JSON 尾巴
        s = s.replaceAll("[\\x00-\\x1f\"'<>|,*;]+$", "");
        s = s.replaceAll("[\\x00-\\x1f\"'<>|*?]$", "");
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || "\"'<>|*?".indexOf(c) >= 0) { cut = i; break; }
        }
        s = s.substring(0, cut).trim();
        while (s.endsWith("/") && s.length() > 1) s = s.substring(0, s.length() - 1);
        if (s.length() < 4 || !s.startsWith("/")) return null;
        if (s.contains("://")) return null;
        if (s.contains("..")) return null;
        return s;
    }

    /** 先写同目录临时文件，再原子替换，避免打断正在使用这个文件的进程。 */
    private static void atomicWrite(Path target, byte[] bytes) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + System.nanoTime());
        Files.write(tmp, bytes);
        try {
            Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        } catch (Exception ignore) {
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String explain(Exception e) {
        if (e instanceof java.nio.file.AccessDeniedException) {
            return "没有写权限（Emby 目录不属于本应用用户），请检查目录权限";
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
