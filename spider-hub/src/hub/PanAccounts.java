package hub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * 网盘账号（凭证）管理。
 *
 * ── 为什么是「写文件」而不是「调 spider 的登录接口」 ──
 * 这套 TVBox spider 的网盘凭证**本来就是文件式的**：所有网盘客户端统一走
 * <pre>
 *   merge.m.k.b(path)  →  k2.root()  =  new File(Init.context().getFilesDir(), "lzxw")
 * </pre>
 * 而 {@code Init.context()} 是我们自己的 android Context 桩（spider-host/src/android/
 * content/Context.java），所以「凭证放哪个目录」完全由我们决定。既不用改一行 spider
 * 代码，也不依赖任何 Android UI。
 *
 * ── 原本坏在哪 ──
 * 桩里的 getFilesDir() 原来是 getCacheDir() → java.io.tmpdir，也就是 /tmp/lzxw。
 * /tmp 重启即清 → 凭证静默丢失，用户会看到「昨天还能用今天列表空了」。
 * 现在由 -Dtvbox.panDir 指到 ${PKGVAR}/data/pan，持久化。
 *
 * ── 各网盘的文件与格式（从 lmw-classes.jar 字节码逐类反查得到） ──
 * <pre>
 *   115      115.txt         纯文本 cookie（Pan115.cookie() 读出来直接 trim 用）
 *   夸克     quark_cookie.txt JSON {"cookie": "...", "nickname": "..."}   (w.r("cookie"))
 *   UC       uc_cookie.txt    JSON {"cookie": "..."}                      (B.l())
 *            uc_token.txt     JSON {"access_token","refresh_token","device_id"}
 *            uc_machine_code.txt 纯文本
 *   百度     baidu.txt        JSON {"cookie": "BDUSS=...; STOKEN=..."}     (j)
 *   天翼189  cloud189.txt     JSON {"Cookie": "...", "userName","passWord"} (键名首字母大写)
 *   123      cloud123.txt     JSON {"token": "...", "userName": "..."}     (m.j())
 *   迅雷     xunlei.txt       JSON {"access_token","refresh_token","deviceid",...} (X.t())
 *   光鸭     guangya.txt      JSON {"access_token","refresh_token","deviceid",...} (G.t())
 *   阿里     aliyun           JSON {"refresh_token": "...", ...}           (i.f() = k.b("aliyun"))
 *   哔哩     bili_cookie.txt  纯文本
 * </pre>
 * 「阿里」那个文件没有扩展名，是早期用 *.txt/*.json 通配扫不到它的原因。
 *
 * ── 关于 AES ──
 * spider 运行时**必须**读到明文，所以运行期明文是躲不掉的。这里的加密做的是
 * 「备份副本」：pan-vault.enc 用一个密钥加密全部凭证，重启/换机可用它恢复；
 * 明文工作文件权限收紧到 0600。等效于「磁盘上多一份加密备份，明文只在运行时存在」。
 */
public final class PanAccounts {

    private PanAccounts() {}

    /** 一个网盘的定义：站点 key + spider 类 + 需要写的文件。 */
    public static final class Drive {
        public final String id;          // 内部 id（也用作 API 参数）
        public final String label;       // 界面名
        public final String siteKey;     // TVBox 配置里的站点 key（「我的丨XX网盘」）
        public final String spider;      // 简名，用于界面展示
        public final String hint;        // 教用户怎么拿凭证
        public Drive(String id, String label, String siteKey, String spider, String hint) {
            this.id = id; this.label = label; this.siteKey = siteKey;
            this.spider = spider; this.hint = hint;
        }
    }

    /** 9 个「我的网盘」站点 —— key 与 TVBox 配置里的 sites[].key 一致。 */
    public static final List<Drive> DRIVES = List.of(
            new Drive("115", "115 网盘", "115网盘", "Pan115",
                    "浏览器打开 115 登录后，F12 → Application → Cookies，复制 UID/CID/SEID/KID 四个值拼成 "
                            + "UID=xxx; CID=xxx; SEID=xxx; KID=xxx"),
            new Drive("quark", "夸克网盘", "夸克网盘", "PanQuark",
                    "浏览器登录 pan.quark.cn 后 F12 → Application → Cookies，找出 __pus 与 __puus，"
                            + "拼成 __pus=xxx; __puus=xxx"),
            new Drive("uc", "UC 网盘", "UC网盘", "PanUC",
                    "浏览器登录 drive.uc.cn 后取 Cookie（同夸克，__pus / __puus），"
                            + "另外可填 device_id（不填会自动生成）"),
            new Drive("baidu", "百度网盘", "百度网盘", "PanBaidu",
                    "浏览器登录 pan.baidu.com 后 F12 → Cookies，取 BDUSS 与 STOKEN，"
                            + "拼成 BDUSS=xxx; STOKEN=xxx"),
            new Drive("tianyi", "天翼云盘", "天翼云盘", "PanTianyi",
                    "浏览器登录 cloud.189.cn 后取整条 Cookie 字符串"),
            new Drive("123", "123 云盘", "123云盘", "Pan123",
                    "登录 123pan.com 后取 token（或整条 Cookie）"),
            new Drive("xunlei", "迅雷云盘", "迅雷云盘", "PanXunlei",
                    "登录 pan.xunlei.com 后取 access_token 与 refresh_token"),
            new Drive("guangya", "光鸭网盘", "光鸭网盘", "PanGuangya",
                    "登录 guangyapan.com 后取 access_token 与 refresh_token"),
            new Drive("ali", "阿里云盘", "阿里云盘", "PanAli",
                    "登录 alipan.com 后取 refresh_token（aliyun 文件里存的就是它）")
    );

    public static Drive drive(String id) {
        if (id == null) return null;
        for (Drive d : DRIVES) if (d.id.equalsIgnoreCase(id.trim())) return d;
        return null;
    }

    /** 凭证根目录：<panDir>/lzxw */
    public static Path root() {
        String base = System.getProperty("tvbox.panDir");
        if (base == null || base.isBlank()) base = System.getProperty("java.io.tmpdir", "/tmp");
        return Path.of(base).resolve("lzxw");
    }

    public static Path vaultFile() {
        return root().getParent().resolve("pan-vault.enc");
    }

    /** 该网盘需要写的文件名（用于界面展示与存在性判断）。 */
    public static List<String> fileNames(Drive d) {
        switch (d.id) {
            case "115":     return List.of("115.txt");
            case "quark":   return List.of("quark_cookie.txt");
            case "uc":      return List.of("uc_cookie.txt", "uc_token.txt", "uc_machine_code.txt");
            case "baidu":   return List.of("baidu.txt");
            case "tianyi":  return List.of("cloud189.txt");
            case "123":     return List.of("cloud123.txt");
            case "xunlei":  return List.of("xunlei.txt");
            case "guangya": return List.of("guangya.txt");
            case "ali":     return List.of("aliyun");
            default:        return List.of();
        }
    }

    /** 是否已经有凭证文件（不代表有效，只代表用户填过）。 */
    public static boolean configured(Drive d) {
        for (String f : fileNames(d)) {
            if (Files.isRegularFile(root().resolve(f))) return true;
        }
        return false;
    }

    /**
     * 保存前的基本校验：缺了关键字段就给出提示（只警告，不拦着保存）。
     *
     * 为什么需要：用户很容易粘错域名的 cookie。实测有人把 www.baidu.com 的
     * BIDUPSID/BAIDUID/PSTM 当成「百度网盘 cookie」贴进来 —— 那些跟网盘登录
     * 完全无关，spider 只认 BDUSS。不提示的话表现就是「保存成功但一条都列不出来」，
     * 极难自己看出问题在哪。
     */
    public static String warnFor(Drive d, String main) {
        String s = main == null ? "" : main;
        switch (d.id) {
            case "115":
                if (!s.contains("UID=") || !(s.contains("CID=") || s.contains("SEID=")))
                    return "没找到 UID / CID —— 115 需要 UID、CID、SEID、KID 四个值";
                break;
            case "baidu":
                if (!s.contains("BDUSS"))
                    return "没找到 BDUSS —— 这多半是百度网页 cookie，不是网盘登录 cookie。"
                            + "请登录 pan.baidu.com，从该域名下复制 BDUSS（顺手带上 STOKEN）";
                break;
            case "ali":
                if (!s.contains("refresh_token"))
                    return "没找到 refresh_token —— 阿里云盘要的是浏览器 localStorage 里的 refresh_token";
                break;
            case "xunlei":
            case "guangya":
                if (!s.contains("access_token") && !s.contains("refresh_token"))
                    return "没找到 access_token —— 请粘贴 token 或完整的 token JSON";
                break;
            default:
                break;
        }
        return "";
    }

    /**
     * 保存凭证。
     *
     * @param d    网盘
     * @param main 主凭证：cookie / token / refresh_token（按网盘语义）
     * @param sub  次要凭证（可为空）：如 UC 的 device_id、天翼的密码
     * @return 写入了哪些文件
     */
    public static List<String> save(Drive d, String main, String sub) throws IOException {
        String v = main == null ? "" : main.trim();
        if (v.isEmpty()) throw new IllegalArgumentException("凭证不能为空");
        Files.createDirectories(root());
        List<String> written = new ArrayList<>();
        switch (d.id) {
            case "115": {
                // 纯文本，spider 直接 trim() 当 cookie 用
                written.add(write("115.txt", v));
                break;
            }
            case "quark": {
                written.add(write("quark_cookie.txt",
                        json(Map.of("cookie", v, "nickname", nicknameOf(v)))));
                break;
            }
            case "uc": {
                String device = sub == null ? "" : sub.trim();
                if (device.isEmpty()) device = randomHex(16);
                written.add(write("uc_cookie.txt", json(Map.of("cookie", v, "nickname", nicknameOf(v)))));
                // access_token / refresh_token：用户如果只给了 cookie，就把 cookie 当 access_token 放进去，
                // spider 首次请求会自己刷新出真 token 并回写这个文件。
                written.add(write("uc_token.txt",
                        json(Map.of("access_token", v, "refresh_token", v, "device_id", device))));
                written.add(write("uc_machine_code.txt", device));
                break;
            }
            case "baidu": {
                written.add(write("baidu.txt", json(Map.of("cookie", v, "nickname", nicknameOf(v)))));
                break;
            }
            case "tianyi": {
                // 键名是首字母大写的 Cookie —— 反查 l.h() 得到，写小写 spider 读不到
                Map<String, String> m = new LinkedHashMap<>();
                m.put("Cookie", v);
                m.put("userName", nicknameOf(v));
                if (sub != null && !sub.isBlank()) m.put("passWord", sub.trim());
                written.add(write("cloud189.txt", json(m)));
                break;
            }
            case "123": {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("token", v);
                m.put("userName", nicknameOf(v));
                written.add(write("cloud123.txt", json(m)));
                break;
            }
            case "xunlei": {
                written.add(write("xunlei.txt", tokenJson(v, sub)));
                break;
            }
            case "guangya": {
                written.add(write("guangya.txt", tokenJson(v, sub)));
                break;
            }
            case "ali": {
                written.add(write("aliyun", json(Map.of("refresh_token", v))));
                break;
            }
            default:
                throw new IllegalArgumentException("不支持的网盘: " + d.id);
        }
        updateVault(d);
        return written;
    }

    private static String tokenJson(String access, String refresh) {
        String a = access == null ? "" : access.trim();
        String r = (refresh == null || refresh.isBlank()) ? a : refresh.trim();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("access_token", a);
        m.put("refresh_token", r);
        m.put("deviceid", randomHex(16));
        m.put("token_type", "Bearer");
        return json(m);
    }

    /** 从 cookie 里猜一个昵称做展示（猜不到就留空，不影响功能）。 */
    private static String nicknameOf(String cookie) {
        return "";
    }

    /**
     * 清除凭证。
     *
     * 注意：**不删文件**，移到 <panDir>/.trash/<时间戳>/ 下 —— 用户要求本机现有文件一律不删，
     * 凭证文件也一样，万一误点了还能捞回来。
     */
    public static List<String> forget(Drive d) throws IOException {
        List<String> moved = new ArrayList<>();
        Path trash = root().getParent().resolve(".trash")
                .resolve(String.valueOf(System.currentTimeMillis()));
        for (String f : fileNames(d)) {
            Path p = root().resolve(f);
            if (!Files.isRegularFile(p)) continue;
            Files.createDirectories(trash);
            Files.move(p, trash.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            moved.add(f);
        }
        updateVault(d);
        return moved;
    }

    /** 读原始文件内容（高级模式：让用户直接看/改 spider 真正读到的东西）。 */
    public static Map<String, String> readRaw(Drive d) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String f : fileNames(d)) {
            Path p = root().resolve(f);
            m.put(f, Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : null);
        }
        return m;
    }

    /** 直接写原始文件（高级模式兜底：万一某个网盘的格式我们猜错了，用户能自己贴正确的）。 */
    public static void writeRaw(Drive d, String fileName, String content) throws IOException {
        if (!fileNames(d).contains(fileName)) {
            throw new IllegalArgumentException("文件名不在该网盘的凭证清单里: " + fileName);
        }
        Files.createDirectories(root());
        write(fileName, content == null ? "" : content);
        updateVault(d);
    }

    // ══════════ 文件写入 + 权限 ══════════
    private static String write(String name, String content) throws IOException {
        Path p = root().resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(p, java.util.EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignore) {
            // 非 POSIX 文件系统（比如某些挂载）拿不到权限位，不影响功能
        }
        return name;
    }

    // ══════════ 极简 JSON 生成（只用来写{"k":"v"}这种扁平结构） ══════════
    static String json(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String randomHex(int bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : new SecureRandom().getSeed(bytes)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ══════════ 加密备份（AES-256-GCM） ══════════
    // 密钥每次启动由 Hub 注入（存在 hub.json 的 panKey 里）。做的是「加密备份」：
    // spider 运行期必须读到明文，所以磁盘上的工作文件是明文（0600）；
    // vault 是给「重启恢复 / 换机迁移 / 备份带走」用的加密副本。
    private static volatile byte[] vaultKey;

    public static void setVaultKey(String hex) {
        if (hex == null || hex.length() < 32) { vaultKey = null; return; }
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) k[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        vaultKey = k;
    }

    public static boolean vaultEnabled() {
        return vaultKey != null;
    }

    /** 把当前全部凭证打包进 vault（先解密旧的再合并，避免覆盖别的网盘）。 */
    public static synchronized void updateVault(Drive d) {
        if (vaultKey == null || d == null) return;
        try {
            Map<String, String> all = readVault();
            for (String f : fileNames(d)) {
                Path p = root().resolve(f);
                if (Files.isRegularFile(p)) all.put(f, Files.readString(p, StandardCharsets.UTF_8));
                else all.remove(f);
            }
            if (all.isEmpty()) {
                Path v = vaultFile();
                if (Files.isRegularFile(v)) Files.delete(v);
                return;
            }
            Files.createDirectories(vaultFile().getParent());
            Files.write(vaultFile(), encrypt(json(all)));
        } catch (Exception e) {
            System.out.println("[pan] 更新 vault 失败: " + e.getMessage());
        }
    }

    private static Map<String, String> readVault() {
        Map<String, String> out = new LinkedHashMap<>();
        if (vaultKey == null) return out;
        try {
            Path v = vaultFile();
            if (!Files.isRegularFile(v)) return out;
            String plain = decrypt(Files.readAllBytes(v));
            Object o = SpiderPool.Json.parse(plain);
            if (o instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
            }
        } catch (Exception e) {
            System.out.println("[pan] 读取 vault 失败（可能是密钥变了）: " + e.getMessage());
        }
        return out;
    }

    /** 启动时调用：明文工作文件缺失、但 vault 里有 → 还原出来（重启/换机后凭证不丢）。 */
    public static synchronized void materializeFromVault() {
        if (vaultKey == null) return;
        Map<String, String> all = readVault();
        if (all.isEmpty()) return;
        int n = 0;
        try {
            Files.createDirectories(root());
            for (Map.Entry<String, String> e : all.entrySet()) {
                Path p = root().resolve(e.getKey());
                if (Files.isRegularFile(p)) continue;      // 已有的不覆盖
                if (e.getKey().contains("/") || e.getKey().contains("..")) continue;
                write(e.getKey(), e.getValue());
                n++;
            }
        } catch (Exception ex) {
            System.out.println("[pan] 从 vault 还原凭证失败: " + ex.getMessage());
        }
        if (n > 0) System.out.println("[pan] 已从加密备份还原 " + n + " 个凭证文件");
    }

    private static byte[] encrypt(String plain) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(vaultKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    private static String decrypt(byte[] data) throws Exception {
        if (data.length < 13) throw new IllegalArgumentException("vault 数据太短");
        byte[] iv = java.util.Arrays.copyOfRange(data, 0, 12);
        byte[] ct = java.util.Arrays.copyOfRange(data, 12, data.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(vaultKey, "AES"), new GCMParameterSpec(128, iv));
        return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    }

    /** 给界面用的一条网盘状态。 */
    public static Map<String, Object> status(Drive d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id);
        m.put("label", d.label);
        m.put("siteKey", d.siteKey);
        m.put("spider", d.spider);
        m.put("hint", d.hint);
        m.put("files", fileNames(d));
        m.put("configured", configured(d));
        List<String> present = new ArrayList<>();
        for (String f : fileNames(d)) if (Files.isRegularFile(root().resolve(f))) present.add(f);
        m.put("present", present);
        return m;
    }
}
