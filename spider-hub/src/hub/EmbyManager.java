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
            // "默认路径"这类靠名字匹配的，如果目录空且和 emby 无关就别认
            if (!hasSystemXml && !hasTrace && !nameLooksEmby) return null;

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
            Files.write(target, dllBytes);

            String configPath = null;
            if (configXml != null && !configXml.isBlank()) {
                Path cfgDir = plugins.resolve("configurations");
                if (!Files.isDirectory(cfgDir)) Files.createDirectories(cfgDir);
                Path cfg = cfgDir.resolve("TvBox.xml");
                Files.write(cfg, configXml.getBytes(StandardCharsets.UTF_8));
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

    private static String explain(Exception e) {
        if (e instanceof java.nio.file.AccessDeniedException) {
            return "没有写权限（Emby 目录不属于本应用用户），请检查目录权限";
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
