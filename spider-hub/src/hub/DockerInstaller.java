package hub;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Emby 跑在 Docker 里时的插件安装器。
 *
 * 为什么需要这个：
 *   Emby 4.9 的 PackageService 只有「从官方插件目录按名字装」的端点
 *   （POST /Packages/Installed/{Name}），没有「给我一个 URL 装 DLL」的接口，
 *   4.9 也不支持自定义插件仓库（system.xml 无该配置键、程序集里搜不到
 *   PluginCatalogUrl/PluginRepository、Web UI 里没有"添加仓库"）。
 *   所以容器部署时只能想办法把文件送进容器，或者在容器里执行命令。
 *
 * 这里走 docker cp + docker restart：
 *   docker cp  <宿主临时文件>  <容器>:/config/plugins/TvBox.dll
 *   docker exec <容器> chown …（把属主对齐到 plugins 目录，否则容器内 Emby 保存配置失败）
 *   docker restart <容器>（插件只有重启后才加载）
 *
 * 前置条件：本进程能访问 docker（/var/run/docker.sock 或 DOCKER_HOST）。
 * 访问不到就明确报错，让用户走「手动拷文件」或填宿主路径，不要静默失败。
 */
public final class DockerInstaller {

    private DockerInstaller() {}

    private static String unavailableWhy = null;

    /** docker 命令是否可用（二进制 + 守护进程可达）。 */
    public static boolean available() {
        unavailableWhy = null;
        if (which("docker") == null) {
            unavailableWhy = "本机没有 docker 命令";
            return false;
        }
        Cmd r = run(20, "docker", "version", "--format", "{{.Server.Version}}");
        if (r.code == 0 && !r.out.trim().isEmpty()) return true;
        String out = r.out == null ? "" : r.out;
        if (out.contains("permission denied") || out.contains("Permission denied")) {
            unavailableWhy = "hub 进程没有读 /var/run/docker.sock 的权限（需要以 root 运行 hub，"
                    + "或把进程用户加入 docker 组）";
        } else if (out.contains("Cannot connect") || out.contains("Is the docker daemon running")) {
            unavailableWhy = "docker 守护进程没在跑";
        } else {
            unavailableWhy = "docker 不可用: " + brief(out);
        }
        return false;
    }

    /** 上一次 available() 判为不可用的原因（可用时返回 null）。 */
    public static String unavailableWhy() {
        return unavailableWhy;
    }

    /** 列出候选容器：名字/镜像里含 emby 的。返回 [{name, image, state}]。 */
    public static List<Map<String, Object>> containers() {
        List<Map<String, Object>> list = new ArrayList<>();
        Cmd r = run(20, "docker", "ps", "-a", "--format", "{{.Names}}\t{{.Image}}\t{{.State}}");
        if (r.code != 0) return list;
        for (String line : r.out.split("\n")) {
            String[] p = line.trim().split("\t");
            if (p.length < 2) continue;
            String name = p[0].trim();
            String image = p[1].trim();
            String state = p.length > 2 ? p[2].trim() : "";
            String hay = (name + " " + image).toLowerCase();
            if (!hay.contains("emby")) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("image", image);
            m.put("state", state);
            list.add(m);
        }
        return list;
    }

    /**
     * 把插件装进指定容器（不指定就自动挑唯一一个 emby 容器）。
     *
     * @param container   容器名，可为空
     * @param dllBytes    TvBox.dll 内容
     * @param configXml   插件配置 XML，可为空
     * @param restart     装完是否重启容器（默认 true，插件只有重启才加载）
     */
    public static Map<String, Object> install(String container, byte[] dllBytes, String configXml, boolean restart) {
        Map<String, Object> r = new LinkedHashMap<>();
        List<String> steps = new ArrayList<>();
        try {
            if (!available()) {
                r.put("ok", false);
                r.put("msg", "本机访问不到 docker（没有 docker 命令，或没权限读 /var/run/docker.sock）。"
                        + "这种情况请用「下载 TvBox.dll 后手动拷进容器」或在 hub 里填宿主上的 programdata 路径。");
                return r;
            }

            String target = container == null ? "" : container.trim();
            if (target.isEmpty()) {
                List<Map<String, Object>> cs = containers();
                if (cs.isEmpty()) {
                    r.put("ok", false);
                    r.put("msg", "没找到名字或镜像里带 emby 的容器。请在 hub 里手动填容器名（docker ps 可以看到）。");
                    return r;
                }
                if (cs.size() > 1) {
                    r.put("ok", false);
                    r.put("msg", "找到多个 emby 容器，请指定一个：" + cs);
                    r.put("candidates", cs);
                    return r;
                }
                target = String.valueOf(cs.get(0).get("name"));
            }

            // 容器里的配置目录：优先 /config（官方与 linuxserver 镜像都是它），
            // 否则从挂载点里挑一个像配置目录的。
            String cfgDir = detectConfigDir(target);
            steps.add("容器 " + target + " 配置目录: " + cfgDir);

            Path tmp = Files.createTempFile("TvBox-", ".dll");
            Files.write(tmp, dllBytes);
            try {
                Cmd cp = run(120, "docker", "cp", tmp.toString(), target + ":" + cfgDir + "/plugins/TvBox.dll");
                if (cp.code != 0) {
                    r.put("ok", false);
                    r.put("msg", "docker cp 失败: " + brief(cp.out));
                    r.put("steps", steps);
                    return r;
                }
                steps.add("已拷入 " + cfgDir + "/plugins/TvBox.dll (" + dllBytes.length + " bytes)");

                if (configXml != null && !configXml.isBlank()) {
                    Path tmpXml = Files.createTempFile("TvBox-", ".xml");
                    Files.write(tmpXml, configXml.getBytes(StandardCharsets.UTF_8));
                    try {
                        run(120, "docker", "exec", target, "mkdir", "-p", cfgDir + "/plugins/configurations");
                        Cmd c2 = run(120, "docker", "cp", tmpXml.toString(),
                                target + ":" + cfgDir + "/plugins/configurations/TvBox.xml");
                        steps.add(c2.code == 0 ? "已写入插件配置 TvBox.xml"
                                : "写配置失败: " + brief(c2.out));
                    } finally {
                        try { Files.deleteIfExists(tmpXml); } catch (Exception ignore) {}
                    }
                }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
            }

            // 属主对齐：docker cp 过去的文件是 root:root，容器内 Emby 用户写不了，
            // 用户侧表现就是「插件设置页保存不了」。照 plugins 目录的属主改。
            Cmd chown = run(60, "docker", "exec", "-u", "0", target, "sh", "-c",
                    "d=" + cfgDir + "/plugins; u=$(stat -c %u:%g \"$d\" 2>/dev/null || echo ''); "
                            + "[ -n \"$u\" ] && chown -R \"$u\" \"$d/TvBox.dll\" \"$d/configurations/TvBox.xml\" 2>/dev/null; "
                            + "stat -c %u:%g \"$d/TvBox.dll\" 2>/dev/null || true");
            if (chown.code == 0 && !chown.out.trim().isEmpty()) {
                steps.add("属主已对齐: " + chown.out.trim());
            } else {
                steps.add("属主对齐跳过（容器内 exec 不可用或 stat 失败）");
            }

            if (restart) {
                Cmd rs = run(180, "docker", "restart", target);
                if (rs.code != 0) {
                    r.put("ok", true);
                    r.put("msg", "插件已拷入容器，但重启失败：" + brief(rs.out)
                            + "。请手动 docker restart " + target + " 让插件生效。");
                    r.put("steps", steps);
                    r.put("container", target);
                    r.put("configDir", cfgDir);
                    return r;
                }
                steps.add("容器已重启，插件随启动加载");
            }

            r.put("ok", true);
            r.put("method", "docker");
            r.put("container", target);
            r.put("configDir", cfgDir);
            r.put("needsRestart", false);
            r.put("msg", "已装入容器 " + target + "（" + cfgDir + "/plugins）"
                    + (restart ? "，已重启" : "，需重启容器后生效"));
            r.put("steps", steps);
            return r;
        } catch (Exception e) {
            r.put("ok", false);
            r.put("msg", "容器安装异常: " + e.getMessage());
            r.put("steps", steps);
            return r;
        }
    }

    /** 容器里的配置目录：/config 优先，其次从挂载点里挑。 */
    private static String detectConfigDir(String container) {
        Cmd r = run(30, "docker", "inspect", "--format",
                "{{range .Mounts}}{{.Destination}}{{\"\\n\"}}{{end}}", container);
        List<String> mounts = new ArrayList<>();
        if (r.code == 0) {
            for (String l : r.out.split("\n")) {
                String d = l.trim();
                if (!d.isEmpty()) mounts.add(d);
            }
        }
        for (String m : mounts) {
            String s = m.toLowerCase();
            if (s.equals("/config") || s.endsWith("/config")) return m;
        }
        for (String m : mounts) {
            String s = m.toLowerCase();
            if (s.contains("emby") && !s.equals("/media")) return m;
        }
        return "/config";
    }

    // ───────────────────── 小工具 ─────────────────────

    private static String which(String bin) {
        for (String dir : new String[]{"/usr/bin", "/bin", "/usr/local/bin", "/snap/bin"}) {
            if (Files.isExecutable(Path.of(dir, bin))) return dir + "/" + bin;
        }
        return null;
    }

    private static final class Cmd {
        int code;
        String out;
    }

    private static Cmd run(int timeoutSec, String... argv) {
        Cmd c = new Cmd();
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            }
            if (!p.waitFor(Math.max(5, timeoutSec), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                c.code = -1;
                c.out = "超时";
                return c;
            }
            c.code = p.exitValue();
            c.out = buf.toString(StandardCharsets.UTF_8);
            return c;
        } catch (Exception e) {
            c.code = -1;
            c.out = e.getMessage() == null ? e.toString() : e.getMessage();
            return c;
        }
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.trim().replace('\n', ' ');
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
