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

    /**
     * 安装前的 programdata 校验。返回 null = 通过，否则是拒绝原因。
     *
     * 为什么要这道关：hub 以 root 运行，install() 会往传入目录写 DLL 和 XML。
     * 不校验就等于给任何能调到这个接口的人一个「root 往任意目录写文件」的原语
     * （实测传 /tmp/evil_emby 就能落地 root:root 的文件）。
     */
    /**
     * 智能安装：先按「本地写文件」走，本地不行再转 Docker。
     *
     * 为什么要有这一步：
     *   1) Emby 4.9 没有「从 URL 装 DLL」的 API，只有从官方插件目录按名字装
     *      （POST /Packages/Installed/{Name}），而 4.9 又不支持自定义插件仓库，
     *      所以容器部署时必须走 docker cp；
     *   2) 但原生部署（fnOS 应用、deb 安装）直接写文件最简单、也不依赖 docker；
     *   3) 还有一种坑：宿主上某目录长得像 programdata，写入也"成功"了，
     *      但真正在跑的 Emby 在容器里用的是另一份数据 —— 装完插件根本不出现。
     *      所以本地成功之后还要对比一次「运行中的进程实际用的 programdata」，
     *      不一致就继续尝试 Docker，并把两条结果都报给用户。
     */
    public static Map<String, Object> installSmart(String programdata, byte[] dllBytes, String configXml,
                                                   String container, boolean allowDocker) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> tried = new ArrayList<>();
        Map<String, Object> localOk = null;
        String localWhy;

        if (programdata == null || programdata.isBlank()) {
            localWhy = "还没填 Emby 数据目录";
            tried.add("本地: " + localWhy);
        } else {
            String why = validateProgramdata(programdata);
            if (why != null) {
                localWhy = why;
                tried.add("本地: " + why);
            } else {
                Map<String, Object> r = install(programdata, dllBytes, configXml);
                if (Boolean.TRUE.equals(r.get("ok"))) {
                    String running = runningProgramdata();
                    if (running != null && !samePath(running, programdata)) {
                        tried.add("本地写入 " + programdata + " 成功，但运行中的 Emby 用的是 " + running
                                + "（很可能是容器部署）→ 继续尝试 Docker");
                        localOk = r;      // 留作兜底：Docker 也失败时仍有本地结果
                        localWhy = "本地装到的目录不是运行中的 Emby 用的那个";
                    } else {
                        r.put("method", "local");
                        r.put("tried", tried);
                        r.put("msg", String.valueOf(r.getOrDefault("msg", "已安装")));
                        return r;
                    }
                } else {
                    localWhy = String.valueOf(r.get("msg"));
                    tried.add("本地失败: " + localWhy);
                }
            }
        }

        if (!allowDocker) {
            out.put("ok", false);
            out.put("method", "local");
            out.put("msg", localWhy + "（未启用 Docker 兜底）");
            out.put("tried", tried);
            return out;
        }

        Map<String, Object> d = DockerInstaller.install(container, dllBytes, configXml, true);
        d.put("tried", tried);
        if (Boolean.TRUE.equals(d.get("ok"))) {
            d.put("method", "docker");
            if (localOk != null) d.put("localAlsoWrote", localOk.get("msg"));
            return d;
        }
        // Docker 也失败：如果本地其实写成功过（装错地方那种），至少把本地结果交回去
        if (localOk != null) {
            localOk.put("method", "local");
            localOk.put("tried", tried);
            localOk.put("warning", "本地写入成功，但可能与运行中的 Emby 不是同一份数据；"
                    + "Docker 安装也失败：" + d.get("msg"));
            return localOk;
        }
        d.put("method", "docker");
        d.put("localWhy", localWhy);
        return d;
    }

    /** 运行中的 Emby 进程实际用的 programdata（拿不到返回 null）。 */
    public static String runningProgramdata() {
        try {
            List<String> list = programdataFromProcesses();
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean samePath(String a, String b) {
        try {
            return Path.of(a).toAbsolutePath().normalize()
                    .equals(Path.of(b).toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    /** 只拒绝这些系统目录本身及其直接子系统，不再整段封 /var/lib（/var/lib/emby 是合法目标）。 */
    static boolean isSystemDir(String path) {
        for (String bad : new String[]{"/etc", "/root", "/usr", "/bin", "/sbin", "/lib", "/lib64",
                                       "/boot", "/dev", "/proc", "/sys", "/var/lib/docker", "/var/run"}) {
            if (path.equals(bad) || path.startsWith(bad + "/")) return true;
        }
        return false;
    }

    public static String validateProgramdata(String programdata) {
        if (programdata == null || programdata.isBlank()) return "路径为空";
        Path p;
        try {
            p = Path.of(programdata.trim()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return "路径非法";
        }
        if (!Files.isDirectory(p)) return "目录不存在: " + p;

        // 硬拒绝系统目录，防止有人传 /etc /root /usr 这些地方
        for (String bad : new String[]{"/etc", "/root", "/usr", "/bin", "/sbin", "/lib", "/lib64",
                "/boot", "/dev", "/proc", "/sys", "/var/lib", "/var/run"}) {
            if (p.toString().equals(bad) || p.toString().startsWith(bad + "/")) {
                return "不允许写入系统目录: " + bad;
            }
        }

        // 必须是个真 Emby 数据目录：已初始化（config/system.xml）
        if (!Files.isRegularFile(p.resolve("config").resolve("system.xml"))) {
            return "这不是已初始化的 Emby 数据目录（缺 config/system.xml）—— 先在 Emby 应用里启动一次";
        }

        // 必须有 Emby 的痕迹：plugins 目录，或者是本机检测出来的实例
        boolean hasPlugins = Files.isDirectory(p.resolve("plugins"));
        boolean detected = false;
        try {
            for (Instance i : detect(null)) {
                if (Path.of(i.programdata).toAbsolutePath().normalize().equals(p)) { detected = true; break; }
            }
        } catch (Exception ignore) {
        }
        if (!hasPlugins && !detected) {
            return "目录里没有 plugins/，也不像是本机检测到的 Emby 实例";
        }
        return null;
    }

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
                // 每次安装/升级都留一份备份，长期下来会把小分区占满 —— 只保留最近 3 份
                try (java.util.stream.Stream<Path> old = java.nio.file.Files.list(plugins)) {
                    java.util.List<Path> baks = new java.util.ArrayList<>();
                    old.filter(x -> x.getFileName().toString().startsWith("TvBox.dll.bak-"))
                       .forEach(baks::add);
                    baks.sort(java.util.Comparator.comparing(p2 -> p2.getFileName().toString()));
                    for (int i = 0; i + 3 < baks.size(); i++) {
                        try { java.nio.file.Files.deleteIfExists(baks.get(i)); } catch (Exception ignore) { }
                    }
                } catch (Exception ignore) { }
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

            // 关键权限修复：
            //   1) 本进程以 root 跑，写出来的 DLL/XML 是 root 所有；
            //      而 Emby 以自己用户跑，之后【保存插件配置】会因无权限失败
            //      （用户侧表现就是「插件界面无法保存」）。
            //   2) OutputRoot 若是 root 建的，Emby 写 strm 同样会被拒。
            // 这里统一把 owner/group 对齐到 plugins 目录的 owner（即 Emby 运行用户）。
            String chownTo = null;
            try {
                chownTo = Files.getOwner(plugins).getName();
                chownGroupLike(target, plugins);
            } catch (Exception ignore) {
            }

            String outRoot = null;
            if (configXml != null && !configXml.isBlank()) {
                Path cfgDir = plugins.resolve("configurations");
                Path cfg = cfgDir.resolve("TvBox.xml");
                // 目录本身也要 chown：Emby 保存配置常常先写临时文件再 rename，
                // 只改文件 owner 而目录还是 root 的话照样失败（实测 Permission denied）
                adoptOwner(cfgDir, plugins);
                adoptOwner(cfg, plugins);
            }
            adoptOwner(target, plugins);

            // 准备输出目录并交给 Emby 用户
            String or = extractTag(configXml, "OutputRoot");
            if (or != null && or.startsWith("/") && !underMediaRoots(or)) {
                // 本进程是 root，会创建目录并递归 chown；不能让它往 /etc /usr 这类地方建
                r.put("ok", false);
                r.put("msg", "输出目录必须在 /vol*、/mnt、/media、/home 下，当前: " + or);
                return r;
            }
            if (or != null && or.startsWith("/")) {
                try {
                    Path outDir = Path.of(or);
                    Files.createDirectories(outDir.resolve("movies"));
                    Files.createDirectories(outDir.resolve("tv"));
                    adoptOwnerRecursive(outDir, plugins, 2);
                    outRoot = outDir.toString();
                } catch (Exception ignore) {
                }
            }

            r.put("ok", true);
            r.put("dll", target.toString());
            if (chownTo != null) r.put("owner", chownTo);
            if (outRoot != null) r.put("outputPrepared", outRoot);
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

    /**
     * 读插件 XML 里的 HubToken。用来和 hub 自己存的那份对齐 ——
     * 只要重装过插件 / 在插件页改过配置 / 安装器重新生成过 token，
     * 两边就会分叉，表现就是「同步显示 token 无效」，而两边各自看都"没错"。
     */
    public static String readHubToken(String programdata) {
        if (programdata == null || programdata.isBlank()) return null;
        try {
            Path xml = Path.of(programdata).resolve("plugins").resolve("configurations").resolve("TvBox.xml");
            if (!Files.isRegularFile(xml)) return null;
            String txt = Files.readString(xml, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<HubToken>([^<]*)</HubToken>").matcher(txt);
            return m.find() ? m.group(1).trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 准备输出目录：建 movies/ 和 tv/，并把属主交给 Emby 的运行用户。
     *
     * 为什么由 hub（root）来建：插件是 Emby 身份跑的，如果父目录还没给权限，
     * 它连"创建目录"这一步都做不了。root 先建好并把 owner 改成 plugins 目录的
     * owner（也就是 Emby 用户），插件随后直接往里写就行。
     */
    public static Map<String, Object> prepareOutputDir(String path, String programdata) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            if (!underMediaRoots(path)) {
                r.put("ok", false);
                r.put("msg", "只允许在 /volN、/mnt、/media、/home、/srv 下建输出目录");
                return r;
            }
            String why = unsafeOutputPath(path);
            if (why != null) {
                r.put("ok", false);
                r.put("msg", "拒绝这个输出目录: " + why);
                return r;
            }
            Path root = Path.of(path).toAbsolutePath().normalize();
            Files.createDirectories(root);
            Path movies = root.resolve("movies");
            Path tv = root.resolve("tv");
            Files.createDirectories(movies);
            Files.createDirectories(tv);

            // 属主对齐到 Emby 用户（plugins 目录的 owner）
            String plugins = null;
            if (programdata != null && !programdata.isEmpty()) plugins = programdata + "/plugins";
            java.nio.file.attribute.UserPrincipal owner = null;
            java.nio.file.attribute.GroupPrincipal group = null;
            if (plugins != null && Files.isDirectory(Path.of(plugins))) {
                owner = Files.getOwner(Path.of(plugins));
                try {
                    group = Files.readAttributes(Path.of(plugins),
                            java.nio.file.attribute.PosixFileAttributes.class).group();
                } catch (Exception ignore) {
                }
            }
            if (owner != null) {
                // 只改我们自己建出来的 movies/ 和 tv/ 两层。
                // 以前是对 root 整棵树递归 chown —— 传 /vol3 就把整个卷（含
                // @appcenter 里以 root 运行的 hub.jar）交给 Emby 用户，且不可逆。
                adopt(movies, owner, group, true);
                adopt(tv, owner, group, true);
            }
            r.put("ok", true);
            r.put("msg", "已准备 " + root + "（movies/tv 已建，属主 " + (owner == null ? "未改" : owner.getName()) + "）");
            r.put("path", root.toString());
            r.put("owner", owner == null ? null : owner.getName());
        } catch (Exception e) {
            r.put("ok", false);
            r.put("msg", "准备失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return r;
    }

    /** 递归把属主/组设成指定用户（目录 775、文件 664）。 */
    /** 单个目录的递归上限：超过就只改目录本身，避免一次 prepare 把整卷拉进内存。 */
    private static final int ADOPT_MAX_ENTRIES = 50000;

    private static void adopt(Path start, java.nio.file.attribute.UserPrincipal owner,
                              java.nio.file.attribute.GroupPrincipal group, boolean recursive) throws Exception {
        java.util.List<Path> paths = new ArrayList<>();
        paths.add(start);
        if (recursive && Files.isDirectory(start)) {
            try (java.util.stream.Stream<Path> st = Files.walk(start)) {
                java.util.Iterator<Path> it = st.iterator();
                int n = 0;
                while (it.hasNext() && n++ < ADOPT_MAX_ENTRIES) paths.add(it.next());
            } catch (Exception e) {
                System.out.println("[prepare] 遍历中断，只改目录本身: " + e.getMessage());
            }
        }
        for (Path p : paths) {
            try {
                java.nio.file.Files.setOwner(p, owner);
                if (group != null) {
                    Files.getFileAttributeView(p, java.nio.file.attribute.PosixFileAttributeView.class)
                         .setGroup(group);
                }
                String perm = Files.isDirectory(p) ? "rwxrwxr-x" : "rw-rw-r--";
                Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString(perm));
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * 以 Emby 运行用户的身份实测目录可写性（hub 是 root，可以 su 过去）。
     *
     * 为什么必须这样测：fnOS 卷是 trimacl，`ls` 的权限位不代表真实可写性；
     * 只有真正切到那个 uid 写一次，才知道插件能不能把 strm 落盘。
     */
    public static Map<String, Object> probeAsEmby(String path, String programdata) {
        Map<String, Object> r = new LinkedHashMap<>();
        java.util.List<Map<String, Object>> items = new ArrayList<>();
        boolean allOk = true;
        String user = pluginsOwner(programdata);
        if (user == null) {
            r.put("ok", false);
            r.put("msg", "找不到 plugins 目录的属主，没法确定 Emby 运行用户");
            return r;
        }
        String bad = unsafeOutputPath(path);
        if (bad != null) {
            r.put("ok", false);
            r.put("msg", "拒绝探测这个目录: " + bad);
            return r;
        }
        for (String dir : new String[]{path, path + "/movies", path + "/tv"}) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("path", dir);
            // 参数化执行，不经过 shell —— 以前是 "mkdir -p '" + dir + "'"，
            // 路径里带一个单引号就能注入任意命令，而执行身份是 root→Emby。
            String out = "";
            String probe = dir + "/.tvbox-probe-" + ProcessHandle.current().pid();
            out += runAsArgv(user, "/bin/mkdir", "-p", "--", dir);
            String touch = runAsArgv(user, "/usr/bin/touch", probe);
            out += touch;
            String rm = runAsArgv(user, "/bin/rm", "-f", "--", probe);
            out += rm;
            boolean ok = !touch.contains("ERROR") && !touch.contains("TIMEOUT") && !touch.startsWith("FAILED");
            if (!ok) out += " FAILED";
            it.put("writable", ok);
            if (!ok) {
                allOk = false;
                String err = out.replace("WRITABLE", "").replace("FAILED", "").trim();
                if (err.contains("may not be used by non-root")) {
                    err = "hub 没有以 root 运行，没法切到 " + user + " 身份实测";
                }
                it.put("error", err);
            }
            items.add(it);
        }
        r.put("ok", allOk);
        r.put("user", user);
        r.put("items", items);
        r.put("msg", allOk ? ("以 " + user + " 身份三个目录都能写") : ("以 " + user + " 身份有目录写不进去"));
        return r;
    }

    /** plugins 目录的属主 = Emby 运行用户。 */
    static String pluginsOwner(String programdata) {
        try {
            if (programdata == null) return null;
            Path plugins = Path.of(programdata).resolve("plugins");
            if (!Files.isDirectory(plugins)) return null;
            return Files.getOwner(plugins).getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** 以指定用户执行一段 sh，返回 stdout+stderr。 */
    /** 参数化版 runAs：argv 直传，不经过 shell（路径里的引号/分号不再有特殊含义）。 */
    static String runAsArgv(String user, String... argv) {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            boolean runuser = Files.isExecutable(Path.of("/usr/sbin/runuser"));
            if (runuser) {
                cmd.add("/usr/sbin/runuser");
                cmd.add("-u");
                cmd.add(user);
                cmd.add("--");
                cmd.addAll(java.util.Arrays.asList(argv));
            } else {
                // su 没有 -- 语法，只能把 argv 交给 sh 重新展开成位置参数，仍然不拼字符串
                cmd.add("/bin/su");
                cmd.add("-s");
                cmd.add("/bin/sh");
                cmd.add(user);
                cmd.add("-c");
                cmd.add("\"$0\" \"$@\"");
                cmd.addAll(java.util.Arrays.asList(argv));
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!pr.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                pr.destroyForcibly();
                return "TIMEOUT";
            }
            return out;
        } catch (Exception e) {
            return "ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    static String runAs(String user, String script) {
        try {
            ProcessBuilder pb;
            if (Files.isExecutable(Path.of("/usr/sbin/runuser"))) {
                pb = new ProcessBuilder("/usr/sbin/runuser", "-u", user, "--", "/bin/sh", "-c", script);
            } else {
                pb = new ProcessBuilder("/bin/su", "-s", "/bin/sh", user, "-c", script);
            }
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!pr.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                pr.destroyForcibly();
                return "TIMEOUT";
            }
            return out;
        } catch (Exception e) {
            return "ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * 输出目录的「深度/形态」校验（在 underMediaRoots 之上再收一道）。
     * 拒绝：数据卷根、单层目录、封装了平台目录（@app*、.@#local 等）的路径。
     */
    static String unsafeOutputPath(String path) {
        try {
            Path p = Path.of(path).toAbsolutePath().normalize();
            String[] seg = p.toString().split("/");
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (String x : seg) if (!x.isBlank()) parts.add(x);
            if (parts.isEmpty()) return "路径为空";
            for (String x : parts) {
                if (x.startsWith("@app") || x.startsWith("@sys") || x.startsWith(".@")
                        || x.equals("@" + "appcenter") || x.startsWith("@appdata")) {
                    return "不能指向平台目录 " + x;
                }
            }
            if (parts.get(0).matches("vol[0-9]+")) {
                if (parts.size() < 3) return "不能是卷根或卷下一层目录（至少要像 /vol2/1000/tvbox）";
            } else if (parts.size() < 3) {
                return "目录层级太浅，至少要到 /home/<用户>/<目录> 这种深度";
            }
            return null;
        } catch (Exception e) {
            return "路径不合法: " + e.getMessage();
        }
    }

    /** 输出目录白名单：只允许落在数据卷 / 常见媒体挂载点下。 */
    public static boolean underMediaRoots(String path) {
        try {
            Path p = Path.of(path).toAbsolutePath().normalize();
            String s = p.toString();
            if (s.matches("^/vol[0-9]+(/.*)?$")) return true;
            for (String ok : new String[]{"/mnt", "/media", "/home", "/srv"}) {
                if (s.equals(ok) || s.startsWith(ok + "/")) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 从 XML 里抠一个标签值。 */
    private static String extractTag(String xml, String tag) {
        if (xml == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<" + tag + ">(.*?)</" + tag + ">", java.util.regex.Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    /** 把 target 的 owner/group 对齐到 reference（一般是 plugins 目录）。 */
    private static void adoptOwner(Path target, Path reference) {
        try {
            if (!Files.exists(target)) return;
            Files.setOwner(target, Files.getOwner(reference));
            try {
                java.nio.file.attribute.GroupPrincipal g =
                        Files.readAttributes(reference, java.nio.file.attribute.PosixFileAttributes.class)
                             .group();
                Files.getFileAttributeView(target, java.nio.file.attribute.PosixFileAttributeView.class)
                     .setGroup(g);
            } catch (Exception ignore) {
            }
            try {
                Files.setPosixFilePermissions(target,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-r--"));
            } catch (Exception ignore) {
            }
        } catch (Exception ignore) {
        }
    }

    /** 递归对齐目录 owner（限制深度，别把整个媒体库扫一遍）。 */
    private static void adoptOwnerRecursive(Path dir, Path reference, int depth) {
        adoptOwner(dir, reference);
        try {
            Files.setPosixFilePermissions(dir,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxr-x"));
        } catch (Exception ignore) {
        }
        if (depth <= 0) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path c : ds) {
                if (Files.isDirectory(c)) adoptOwnerRecursive(c, reference, depth - 1);
            }
        } catch (Exception ignore) {
        }
    }

    private static void chownGroupLike(Path target, Path reference) {
        adoptOwner(target, reference);
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
