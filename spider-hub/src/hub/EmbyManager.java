package hub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Emby 插件安装器。
 *
 * 目标：在 fnOS 上点一下按钮，就把 TvBox.dll 装进本机 Emby，并把插件配置写好，
 * 用户只需要重启一次 Emby。
 *
 * 找 Emby 的方式（按可靠性排序）：
 *   1. 用户手工指定（记住上次结果）
 *   2. 扫描 /vol*\/@appdata/*\/  —— fnOS 应用数据目录，Emby 的 programdata 通常在这
 *   3. 扫描 /vol*\/@appcenter/emby*  —— 应用安装目录（有些版本把 var 放这）
 *   4. 扫描 /vol*\/docker/*\/...     —— Docker 部署的 Emby（config 挂载）
 *   5. 扫描 /opt/emby-server、/var/lib/emby —— 官方 deb 安装
 *
 * Emby programdata 的认定标志：存在 config/system.xml（Emby 初始化后必生成）。
 */
public class EmbyManager {

    /** Emby programdata 目录。 */
    public static class Instance {
        public String programdata;
        public String pluginsDir;
        public String source;        // 怎么找到的
        public long mtime;
        public boolean writable;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("programdata", programdata);
            m.put("plugins", pluginsDir);
            m.put("source", source);
            m.put("writable", writable);
            m.put("mtime", mtime == 0 ? null : new java.util.Date(mtime).toString());
            return m;
        }
    }

    /**
     * 扫描本机所有可能的 Emby programdata。
     * @param extra 额外指定的路径（用户填的），优先验证
     */
    public static List<Instance> detect(String extra) {
        List<Instance> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        if (extra != null && !extra.isBlank()) {
            Instance i = check(Path.of(extra.trim()), "手动指定");
            if (i != null) { out.add(i); seen.add(i.programdata); }
        }

        List<Path> roots = new ArrayList<>();
        // fnOS 应用数据 / 安装目录
        for (String vol : volumes()) {
            roots.add(Path.of(vol, "@appdata"));
            roots.add(Path.of(vol, "@appcenter"));
        }
        // 常见手工安装
        roots.add(Path.of("/var/lib"));
        roots.add(Path.of("/opt"));

        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.list(root)) {
                List<Path> children = new ArrayList<>();
                s.limit(400).forEach(children::add);
                for (Path child : children) {
                    String n = child.getFileName().toString().toLowerCase();
                    // 先把名字里带 emby 的当候选
                    if (n.contains("emby")) {
                        for (Path cand : candidates(child)) {
                            Instance i = check(cand, root.toString());
                            if (i != null && !seen.contains(i.programdata)) {
                                out.add(i);
                                seen.add(i.programdata);
                            }
                        }
                    }
                }
            } catch (Exception ignore) {
            }
        }

        // Docker：/vol*/docker/*/ 下面找 config/system.xml
        for (String vol : volumes()) {
            Path docker = Path.of(vol, "docker");
            if (!Files.isDirectory(docker)) continue;
            try (Stream<Path> s = Files.list(docker)) {
                s.limit(200).forEach(container -> {
                    for (Path cand : candidates(container)) {
                        Instance i = check(cand, "docker");
                        if (i != null && !seen.contains(i.programdata)) {
                            out.add(i);
                            seen.add(i.programdata);
                        }
                    }
                });
            } catch (Exception ignore) {
            }
        }

        out.sort(Comparator.comparingLong((Instance i) -> i.mtime).reversed());
        return out;
    }

    /** 一个目录下可能是 programdata 的候选。 */
    private static List<Path> candidates(Path base) {
        List<Path> list = new ArrayList<>();
        list.add(base);                                  // 目录本身就是 programdata
        list.add(base.resolve("config"));                // 有些把 config 挂出来
        list.add(base.resolve("var"));                   // fnOS 应用把数据放 var
        list.add(base.resolve("data"));
        list.add(base.resolve("emby"));
        list.add(base.resolve("config/emby"));
        return list;
    }

    /** 判断一个目录是不是 Emby programdata。 */
    private static Instance check(Path dir, String source) {
        try {
            if (dir == null || !Files.isDirectory(dir)) return null;
            Path sysXml = dir.resolve("config").resolve("system.xml");
            Path plugins = dir.resolve("plugins");
            boolean isEmby = Files.isRegularFile(sysXml)
                    || (Files.isDirectory(plugins) && Files.isDirectory(dir.resolve("config")));
            if (!isEmby) return null;

            Instance i = new Instance();
            i.programdata = dir.toAbsolutePath().toString();
            i.pluginsDir = plugins.toAbsolutePath().toString();
            i.source = source;
            i.writable = Files.isWritable(dir);
            try {
                i.mtime = Files.getLastModifiedTime(Files.isRegularFile(sysXml) ? sysXml : dir).toMillis();
            } catch (Exception ignore) {
            }
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> volumes() {
        List<String> v = new ArrayList<>();
        try (Stream<Path> s = Files.list(Path.of("/"))) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.matches("vol\\d+")) v.add(p.toString());
            });
        } catch (Exception ignore) {
        }
        if (v.isEmpty()) v.add("/volume1");
        v.sort(String::compareTo);
        return v;
    }

    /**
     * 安装插件。
     * @param programdata Emby programdata 目录
     * @param dllBytes    TvBox.dll 内容
     * @param configXml   插件配置 XML（可空）
     * @return 结果
     */
    public static Map<String, Object> install(String programdata, byte[] dllBytes, String configXml) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            Path root = Path.of(programdata);
            Path plugins = root.resolve("plugins");
            if (!Files.isDirectory(plugins)) Files.createDirectories(plugins);

            // 备份旧的
            Path target = plugins.resolve("TvBox.dll");
            String backup = null;
            if (Files.isRegularFile(target)) {
                Path bak = plugins.resolve("TvBox.dll.bak-" + System.currentTimeMillis());
                Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
                backup = bak.toString();
            }

            Files.write(target, dllBytes);

            // 写插件配置，省得用户再进 Emby 手填
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

    /** 卸载（把 dll 移走，保留备份）。 */
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

    /** 查插件是否已装、版本多大。 */
    public static Map<String, Object> status(String programdata) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Path target = Path.of(programdata).resolve("plugins").resolve("TvBox.dll");
            boolean installed = Files.isRegularFile(target);
            m.put("installed", installed);
            m.put("path", target.toString());
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
        if (e instanceof IOException) return e.getMessage();
        return e.toString();
    }
}
