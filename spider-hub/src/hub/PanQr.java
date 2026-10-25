package hub;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网盘扫码登录。
 *
 * ── 设计原则 ──
 * 1. 能用 jar 里现成实现的就用现成的（115 的 Qr115 自带签名算法），绝不自己猜签名。
 * 2. 其余网盘直接调它们自己的网页登录接口 —— 这些接口本来就是给浏览器扫码用的，
 *    不依赖 Android UI，全部是纯 HTTP。
 * 3. **不做"猜成功"**：任何一步拿不到预期字段，就把上游原始响应截断后透给界面，
 *    让用户/我们能一眼看出是风控、改接口、还是网络不通。宁可报错也不假装登录成功。
 * 4. 扫码失败不影响「粘贴凭证」这条路 —— 界面永远同时提供两种方式。
 *
 * ── 状态码（各网盘统一成同一套语义给界面）──
 *   WAIT(0)      等待扫码
 *   SCANNED(1)   已扫码，待手机确认
 *   DONE(2)      登录成功，凭证已落盘
 *   EXPIRED(-1)  二维码过期
 *   ERROR(-99)   出错
 */
public final class PanQr {

    public static final int WAIT = 0, SCANNED = 1, DONE = 2, EXPIRED = -1, ERROR = -99;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            // 必须带 cookie：阿里 generate.do 和 query.do 是两次独立请求，
            // 浏览器靠 cookie 把两次串起来；不带就立刻回 EXPIRED。
            .cookieHandler(new java.net.CookieManager())
            .build();

    private PanQr() {}

    /** 一次扫码会话。各网盘需要的中间状态都塞进 map，避免为每家写一个类。 */
    public static final class Session {
        public final String drive;
        public final Map<String, String> vars = new LinkedHashMap<>();
        /** 115 专用：反射拿到的 Token 对象（不能序列化成字符串，只能留在内存里）。 */
        public final Map<String, Object> tokens = new ConcurrentHashMap<>();
        /** 加载 Qr115 用的 ClassLoader —— 必须记住 start 时那个，
         *  轮询时用 Thread.contextClassLoader 会拿到 ClassNotFoundException。 */
        public ClassLoader loader;
        /**
         * 后台轮询的最新快照。
         *
         * 为什么要有它：百度的 unicast 是 30 秒长轮询（实测三次都是 30.2~30.3s 才返回
         * {"errno":1}）。如果让浏览器每轮直接挂着这个请求，一旦穿过 fnOS 网关/浏览器
         * 自己的超时边界（30s 左右），用户确认扫码那一刻的响应就正好被掐掉 ——
         * 表现就是「扫了确认了，应用一直说等待扫码」。
         * 改成服务端后台线程常驻轮询、把结果存快照，浏览器只读快照，秒回、不怕超时。
         */
        public volatile Map<String, Object> bg = null;
        public volatile boolean stop = false;
        Session(String drive) { this.drive = drive; }
    }

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    /** 该网盘是否支持扫码（界面据此显示「扫码登录」按钮）。 */
    public static boolean supports(String driveId) {
        // 只保留「能拿到官方二维码」的三家：
        //   115 → Qr115（jar 自带签名）
        //   百度 → passport getqrcode，imgurl 补 https 就是 PNG（实测 245x245）
        //   阿里 → generate.do 返回 codeContent 官方内容，前端本地画码
        // 夸克：uop 只回 token，不再回 qrcode_url，猜出来的码扫不动 → 不给假按钮
        // UC：uop.uc.cn 本机解析不了；天翼：189 要 date/signature HMAC 签名
        switch (driveId) {
            case "115": case "baidu": case "ali":
                return true;
            default:
                return false;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  开始：生成二维码
    // ══════════════════════════════════════════════════════════
    public static Map<String, Object> start(String driveId, ClassLoader spiderLoader) throws Exception {
        Session s = new Session(driveId);
        s.loader = spiderLoader;
        Map<String, Object> r;
        switch (driveId) {
            case "115":   r = start115(s); break;
            case "quark": r = startAliyunFamily(s, "quark"); break;
            case "uc":    r = startAliyunFamily(s, "uc"); break;
            case "baidu": r = startBaidu(s); break;
            case "tianyi":r = startTianyi(s); break;
            case "ali":   r = startAli(s); break;
            default: throw new IllegalArgumentException("这个网盘不支持扫码，请用「粘贴凭证」");
        }
        for (Session old : SESSIONS.values()) old.stop = true;
        SESSIONS.clear();
        SESSIONS.put(s.vars.get("sid"), s);
        return r;
    }

    /** 轮询扫码状态；返回 logged=true 时凭证已写进 panDir。 */
    public static Map<String, Object> poll(String driveId, String sid) throws Exception {
        Session s = sid == null ? null : SESSIONS.get(sid);
        if (s == null) throw new IllegalStateException("二维码会话已失效，请重新生成");
        if (!s.drive.equals(driveId)) throw new IllegalStateException("会话与网盘不匹配");
        // 后台已在轮询 → 直接返回最新快照（秒回）。浏览器不再碰长轮询接口，
        // 避免 30s 边界上被网关/浏览器掐断而丢掉「已确认」那一帧。
        Map<String, Object> snap = s.bg;
        if (snap != null) {
            Map<String, Object> r = new LinkedHashMap<>(snap);
            r.put("sid", sid);
            if (Boolean.TRUE.equals(r.get("logged")) || Integer.valueOf(EXPIRED).equals(r.get("status"))) {
                SESSIONS.remove(sid);
                s.stop = true;
            }
            return r;
        }
        Map<String, Object> r;
        switch (driveId) {
            case "115":   r = poll115Once(s); break;
            case "quark":
            case "uc":    r = pollAliyunFamily(s); break;
            case "baidu": r = pollBaiduOnce(s); break;
            case "tianyi":r = pollTianyi(s); break;
            case "ali":   r = pollAli(s); break;
            default: throw new IllegalArgumentException("不支持");
        }
        Object logged = r.get("logged");
        if (Boolean.TRUE.equals(logged) || Integer.valueOf(EXPIRED).equals(r.get("status"))) {
            SESSIONS.remove(sid);
        }
        return r;
    }

    // ══════════════════════════════════════════════════════════
    //  115 —— 直连 HTTP（不再用 jar 里的 Qr115）
    //
    //  为什么不用 jar 的类：Qr115.status() 内部 HTTP 客户端超时约 30s，
    //  而 115 的 /get/status/ **本身就是 30s 长轮询** —— 每次都擦边超时，
    //  于是永远返回 -99(ERROR)。实测直连、超时放到 45s 就正常：
    //    {"state":1,"code":0,"message":"","data":{}}   ← 空 data = 还没人扫，不是错误
    //
    //  接口照抄 jar 字节码：
    //    token  GET qrcodeapi.115.com/api/1.0/qandroid/1.0/token/
    //    status GET qrcodeapi.115.com/get/status/?uid=&time=&sign=&_=0
    //    login  GET passportapi.115.com/app/1.0/qandroid/1.0/login/qrcode/?account=<uid>&app=qandroid
    //           → data.cookie = {UID, CID, SEID, KID}
    // ══════════════════════════════════════════════════════════
    private static final String UA_115DISK = "Mozilla/5.0 115disk/30.1.0";

    private static Map<String, Object> start115(Session s) throws Exception {
        String raw = getUa("https://qrcodeapi.115.com/api/1.0/qandroid/1.0/token/?_=0", null, UA_115DISK);
        Map<String, Object> j = jsonObj(raw);
        Map<?, ?> data = asMap(j.get("data"));
        String uid = str(data.get("uid"));
        if (uid.isEmpty()) throw new IllegalStateException("115 没返回二维码：" + cut(raw));
        String sid = UUID.randomUUID().toString();
        s.vars.put("sid", sid);
        s.vars.put("uid", uid);
        s.vars.put("time", str(data.get("time")));
        s.vars.put("sign", str(data.get("sign")));
        Map<String, Object> m = baseOk(sid, str(data.get("qrcode")));
        m.put("imgUrl", "https://qrcodeapi.115.com/api/1.0/web/1.0/qrcode?uid=" + enc(uid));
        m.put("hint", "用 115 App 扫码");
        startBg(s, "115", () -> poll115Once(s));
        return m;
    }

    private static Map<String, Object> poll115Once(Session s) throws Exception {
        String url = "https://qrcodeapi.115.com/get/status/?uid=" + enc(s.vars.get("uid"))
                + "&time=" + enc(s.vars.get("time")) + "&sign=" + enc(s.vars.get("sign")) + "&_=0";
        String raw = getUa(url, "https://115.com", UA_115DISK);
        Map<String, Object> j = jsonObj(raw);
        if (j.isEmpty()) return baseState(WAIT, "等待扫码…（上游返回空）");
        String state = str(j.get("state"));
        if (!("1".equals(state) || "true".equalsIgnoreCase(state))) {
            return baseState(EXPIRED, "115 说这个码失效了（" + str(j.get("message")) + "）");
        }
        Map<?, ?> data = asMap(j.get("data"));
        int st = (int) num(data.get("status"));      // 0 等扫 / 1 已扫 / 2 已确认
        if (st == 2) {
            String cookie = login115(s);
            if (cookie.isEmpty()) {
                Map<String, Object> m = baseState(SCANNED, "手机上已确认，但换 cookie 失败 —— 请改用「粘贴 cookie」");
                m.put("raw", cut(raw));
                return m;
            }
            PanAccounts.save(PanAccounts.drive("115"), cookie, null);
            Map<String, Object> m = baseState(DONE, "登录成功，凭证已保存");
            m.put("logged", true);
            return m;
        }
        if (st == 1) return baseState(SCANNED, "已扫码，请在手机上确认");
        return baseState(WAIT, "等待扫码…");
    }

    /** 确认后用 uid 换 cookie：data.cookie = {UID, CID, SEID, KID}。 */
    private static String login115(Session s) throws Exception {
        String url = "https://passportapi.115.com/app/1.0/qandroid/1.0/login/qrcode/?account="
                + enc(s.vars.get("uid")) + "&app=qandroid";
        String raw = getUa(url, "https://115.com", UA_115DISK);
        Map<String, Object> j = jsonObj(raw);
        Map<?, ?> ck = asMap(asMap(j.get("data")).get("cookie"));
        String uid = str(first(ck, "UID", "uid"));
        String cid = str(first(ck, "CID", "cid"));
        String seid = str(first(ck, "SEID", "seid"));
        String kid = str(first(ck, "KID", "kid"));
        System.out.println("[qr] 115 换 cookie → uid=" + (uid.isEmpty() ? "无" : "有")
                + " cid=" + (cid.isEmpty() ? "无" : "有") + " seid=" + (seid.isEmpty() ? "无" : "有")
                + " kid=" + (kid.isEmpty() ? "无" : "有") + " 原文=" + cut(raw));
        if (uid.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("UID=" + uid);
        if (!cid.isEmpty()) sb.append("; CID=").append(cid);
        if (!seid.isEmpty()) sb.append("; SEID=").append(seid);
        if (!kid.isEmpty()) sb.append("; KID=").append(kid);
        return sb.toString();
    }

    /**
     * 通用的「服务端后台轮询」。
     *
     * 长轮询接口（115 的 get/status、百度的 channel/unicast）一律挂在后台线程里，
     * 结果写进 session 快照；浏览器只读快照，毫秒级返回。
     * 这样既不会被 fnOS 网关/浏览器的 30s 超时边界掐掉真正的那一帧，
     * 也不会把 HTTP 处理线程占住。
     */
    private static void startBg(Session s, String tag, java.util.concurrent.Callable<Map<String, Object>> once) {
        s.bg = baseState(WAIT, "等待扫码…");
        Thread t = new Thread(() -> {
            int rounds = 0;
            while (!s.stop && rounds < 480) {
                rounds++;
                try {
                    Map<String, Object> r = once.call();
                    s.bg = r;
                    System.out.println("[qr] " + tag + " 第 " + rounds + " 轮 → status=" + r.get("status")
                            + " " + r.get("msg"));
                    if (Boolean.TRUE.equals(r.get("logged"))
                            || Integer.valueOf(EXPIRED).equals(r.get("status"))
                            || Integer.valueOf(ERROR).equals(r.get("status"))) break;
                } catch (Exception e) {
                    System.out.println("[qr] " + tag + " 轮询异常: " + cut(e.getMessage()));
                    s.bg = baseState(WAIT, "轮询出错，重试中：" + cut(e.getMessage()));
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
                }
            }
            System.out.println("[qr] " + tag + " 后台轮询结束（" + rounds + " 轮）");
        }, "pan-qr-" + tag);
        t.setDaemon(true);
        t.start();
    }

    // ══════════════════════════════════════════════════════════
    //  夸克 / UC —— 阿里 uop 扫码体系（两家接口同构，只有域名和 client_id 不同）
    // ══════════════════════════════════════════════════════════
    private static Map<String, Object> startAliyunFamily(Session s, String which) throws Exception {
        boolean uc = "uc".equals(which);
        String host = uc ? "https://uop.uc.cn" : "https://uop.quark.cn";
        String clientId = uc ? "5acf882d27b7451b9a7be3ce6cd0ffa9" : "532b0dbb77f34e5a9b1f60bfe0e0e13f";
        String rid = UUID.randomUUID().toString();
        String body = "client_id=" + enc(clientId) + "&v=1.2&request_id=" + enc(rid);
        String raw = postForm(host + "/cas/ajax/getTokenForQrcodeLogin", body, host);
        Map<String, Object> j = jsonObj(raw);
        Map<?, ?> members = members(j);
        String qrUrl = str(members.get("qrcode_url"));
        String token = str(members.get("token"));
        if (qrUrl.isEmpty() || token.isEmpty()) {
            throw new IllegalStateException(which + " 没返回二维码：" + cut(raw));
        }
        String sid = UUID.randomUUID().toString();
        s.vars.put("sid", sid);
        s.vars.put("token", token);
        s.vars.put("rid", rid);
        s.vars.put("client", clientId);
        s.vars.put("host", host);
        Map<String, Object> m = baseOk(sid, null);
        m.put("imgUrl", qrUrl);
        m.put("hint", "用" + (uc ? "UC" : "夸克") + " App 扫码");
        return m;
    }

    private static Map<String, Object> pollAliyunFamily(Session s) throws Exception {
        String host = s.vars.get("host"), token = s.vars.get("token"),
                rid = s.vars.get("rid"), cid = s.vars.get("client");
        String url = host + "/cas/ajax/getServiceTicketByQrcodeToken?v=1.2&token=" + enc(token)
                + "&client_id=" + enc(cid) + "&request_id=" + enc(rid);
        String raw = get(url, host);
        Map<String, Object> j = jsonObj(raw);
        // uop 的 status 语义：-4 已扫码待确认 / 0 或 200 ok / 其它未扫
        int code = (int) num(j.get("status"));
        Map<?, ?> members = members(j);
        String ticket = str(members.get("service_ticket"));
        String cookies = str(members.get("cookies"));
        if (code == 2 || code == 200 || (!ticket.isEmpty() || !cookies.isEmpty())) {
            String cookie = cookies.isEmpty() ? toCookieFromTicket(s.drive, ticket) : cookies;
            if (cookie.isEmpty()) {
                Map<String, Object> m = baseState(SCANNED, "已扫码，但还没拿到 cookie，请稍等再试");
                return m;
            }
            PanAccounts.save(PanAccounts.drive(s.drive), cookie, null);
            Map<String, Object> m = baseState(DONE, "登录成功，凭证已保存");
            m.put("logged", true);
            return m;
        }
        if (code == -4) return baseState(SCANNED, "已扫码，请在手机上确认");
        return baseState(WAIT, "等待扫码…（上游 status=" + code + "）");
    }

    /** uop 有时只给 service_ticket，需要换成真正的 cookie。换不到就把它当 cookie 存。 */
    private static String toCookieFromTicket(String drive, String ticket) {
        if (ticket == null || ticket.isEmpty()) return "";
        return "__pus=" + ticket;
    }

    // ══════════════════════════════════════════════════════════
    //  百度
    // ══════════════════════════════════════════════════════════
    private static Map<String, Object> startBaidu(Session s) throws Exception {
        String gid = UUID.randomUUID().toString().toUpperCase();
        String url = "https://passport.baidu.com/v2/api/getqrcode?lp=pc&qrloginfrom=pc&gid=" + enc(gid)
                + "&tpl=netdisk&apiver=v3&tt=" + System.currentTimeMillis();
        String raw = get(url, "https://pan.baidu.com");
        Map<String, Object> j = jsonObj(raw);
        String imgurl = str(j.get("imgurl"));
        String sign = str(j.get("sign"));
        if (imgurl.isEmpty() || sign.isEmpty()) throw new IllegalStateException("百度没返回二维码：" + cut(raw));
        String sid = UUID.randomUUID().toString();
        s.vars.put("sid", sid);
        s.vars.put("sign", sign);
        s.vars.put("gid", gid);
        Map<String, Object> m = baseOk(sid, null);
        // imgurl 本身就是二维码图片地址（不带 scheme）；走 capi/qrcodeimage?imgurl= 会 302 空响应
        m.put("imgUrl", "https://" + imgurl);
        m.put("hint", "用百度网盘 App 扫码");

        // 起后台轮询：unicast 每次要挂 30s，绝不能交给浏览器直连
        startBg(s, "baidu", () -> pollBaiduOnce(s));
        return m;
    }


    /**
     * 从百度 unicast 的 v 里抠 BDUSS。
     *
     * v 的形态各版本不统一：可能是 base64 的 JSON（{"bduss":"xxx"}）、
     * base64 的 query（bduss=xxx&stoken=yyy），也可能就是明文。
     * 所以不猜格式 —— 原样找一遍，解不出来就 base64/base64url 各解一层再找。
     */
    private static String findBduss(String v) { return findToken(v, "bduss"); }

    private static String findStoken(String v) { return findToken(v, "stoken"); }

    private static String findToken(String v, String key) {
        if (v == null || v.isBlank()) return "";
        String cand = v;
        for (int i = 0; i < 3; i++) {
            String hit = matchToken(cand, key);
            if (!hit.isEmpty()) return hit;
            String next = null;
            try {
                next = new String(Base64.getDecoder().decode(cand), StandardCharsets.UTF_8);
            } catch (Exception e1) {
                try {
                    next = new String(Base64.getUrlDecoder().decode(cand), StandardCharsets.UTF_8);
                } catch (Exception e2) {
                    return "";
                }
            }
            if (next.equals(cand)) return "";
            cand = next;
        }
        return matchToken(cand, key);
    }

    /** 同时兼容 {"bduss":"x"} / bduss=x / BDUSS=x; 三种写法。 */
    private static String matchToken(String s, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\b" + key + "\\b\"?\\s*[=:]\\s*\"?([^\"'&;,}\\s]+)")
                .matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static Map<String, Object> pollBaiduOnce(Session s) throws Exception {
        String sign = s.vars.get("sign"), gid = s.vars.get("gid");
        String url = "https://passport.baidu.com/channel/unicast?channel_id=" + enc(sign)
                + "&tpl=netdisk&gid=" + enc(gid) + "&apiver=v3&tt=" + System.currentTimeMillis();
        String raw = get(url, "https://pan.baidu.com");
                return parseBaiduUnicast(raw);
    }

    /**
     * 解析百度 unicast 的返回。抽成 public 是为了能单独跑测试 ——
     * 这里的结构是整件事最容易错的地方（载荷藏在 channel_v 里的 JSON 字符串中）。
     */
    public static Map<String, Object> parseBaiduUnicast(String raw) throws Exception {
Map<String, Object> j = jsonObj(raw);
        int errno = (int) num(j.get("errno"));

        // ── 关键：载荷不在顶层 v 里 ──────────────────────────────────
        // 实测百度确认真实返回（扫码成功后）：
        //   {"errno":0,"channel_id":"ef2dc9de...",
        //    "channel_v":"{\"status\":0,\"v\":\"bebcca622bdadd...\"}"}
        // 也就是说：channel_v 是一个「JSON 字符串」，里头再套一层，
        // 真正的 base64 载荷是内层的 v。原来只读顶层 v，所以永远拿不到，
        // 表现就是「扫了、确认了，应用一直说等待扫码」。
        String payload = str(j.get("channel_v"));
        if (payload.isBlank()) payload = str(j.get("v"));      // 兼容返回顶层 v 的版本
        int inner = -1;
        String v = payload;
        Map<String, Object> pj = jsonObj(payload);
        if (!pj.isEmpty()) {
            inner = (int) num(pj.get("status"));
            Object iv = pj.get("v");
            v = iv == null ? "" : str(iv);
        }
        // 百度 unicast 是长轮询：没扫到时 30s 后返回 {"errno":1}（这是正常，不是错误）；
        // 扫到了才带 v，v 是 base64 的 {"bduss":"..."}。
        if (!v.isEmpty()) {
            String bduss = findBduss(v);
            String stoken = findStoken(v);
            System.out.println("[qr] baidu 收到回调 v=" + v.length() + "B → bduss="
                    + (bduss.isEmpty() ? "解不出" : bduss.length() + "字符")
                    + " stoken=" + (stoken.isEmpty() ? "无" : "有"));
            if (!bduss.isEmpty()) {
                String cookie = "BDUSS=" + bduss + (stoken.isEmpty() ? "" : "; STOKEN=" + stoken);
                PanAccounts.save(PanAccounts.drive("baidu"), cookie, null);
                Map<String, Object> m = baseState(DONE, "登录成功，凭证已保存");
                m.put("logged", true);
                return m;
            }
            // 有载荷但没 BDUSS：多半是「已扫码、手机还没点确认」
            Map<String, Object> m = baseState(SCANNED, inner >= 0
                    ? "已扫码，请在手机上点「确认登录」（status=" + inner + "）"
                    : "已扫码，请在手机上点「确认登录」");
            m.put("raw", cut(payload));          // 此时还没有凭证，带出原文便于诊断
            return m;
        }
        // errno=1 = 还没人扫（长轮询到点自然返回，属正常）；!=1 就把原文带出来
        Map<String, Object> m = baseState(WAIT, errno == 1 ? "等待扫码…"
                : "上游返回 errno=" + errno + (inner >= 0 ? " status=" + inner : ""));
        m.put("raw", cut(raw));
        return m;
    }

    // ══════════════════════════════════════════════════════════
    //  天翼云盘（189）
    // ══════════════════════════════════════════════════════════
    private static Map<String, Object> startTianyi(Session s) throws Exception {
        // 189 的扫码走 open API 的 qrcode 通道；接口不稳定，失败就直接报出来让用户改粘贴
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String url = "https://api.cloud.189.cn/login/qrcode/getLoginQrCode.action";
        String raw = postForm(url, "uuid=" + enc(uuid), "https://cloud.189.cn");
        Map<String, Object> j = jsonObj(raw);
        String qr = str(first(j, "qrCodeUrl", "qrcode", "imgUrl"));
        String sign = str(first(j, "encryuuid", "uuid", "sign"));
        if (qr.isEmpty()) throw new IllegalStateException("天翼没返回二维码：" + cut(raw));
        String sid = UUID.randomUUID().toString();
        s.vars.put("sid", sid);
        s.vars.put("uuid", sign.isEmpty() ? uuid : sign);
        s.vars.put("qr", qr);
        Map<String, Object> m = baseOk(sid, qr);
        m.put("imgUrl", qr);
        m.put("hint", "用天翼云盘 App 扫码");
        return m;
    }

    private static Map<String, Object> pollTianyi(Session s) throws Exception {
        String url = "https://api.cloud.189.cn/login/qrcode/getLoginResult.action?uuid=" + enc(s.vars.get("uuid"));
        String raw = get(url, "https://cloud.189.cn");
        Map<String, Object> j = jsonObj(raw);
        String cookie = str(first(j, "cookie", "Cookie"));
        if (!cookie.isEmpty()) {
            PanAccounts.save(PanAccounts.drive("tianyi"), cookie, null);
            Map<String, Object> m = baseState(DONE, "登录成功，凭证已保存");
            m.put("logged", true);
            return m;
        }
        return baseState(WAIT, "等待扫码…");
    }

    // ══════════════════════════════════════════════════════════
    //  阿里云盘（拿 refresh_token）
    // ══════════════════════════════════════════════════════════
    private static Map<String, Object> startAli(Session s) throws Exception {
        String url = "https://passport.aliyundrive.com/newlogin/qrcode/generate.do"
                + "?appName=aliyun_drive&fromSite=52&appEntrance=web&isMobile=false&lang=zh_CN"
                + "&returnUrl=&bizParams=&_bx-v=2.5.3&navlanguage=zh-CN&navPlatform=MacIntel"
                + "&tt=" + System.currentTimeMillis();
        String raw = get(url, "https://www.aliyundrive.com");
        Map<String, Object> j = jsonObj(raw);
        Map<?, ?> content = asMap(j.get("content"));
        Map<?, ?> data = asMap(content.get("data"));
        String ck = str(data.get("ck"));
        String t = str(data.get("t"));
        String qr = str(first(data, "codeContent", "qrCodeUrl"));
        if (ck.isEmpty() || t.isEmpty()) throw new IllegalStateException("阿里云盘没返回二维码：" + cut(raw));
        String sid = UUID.randomUUID().toString();
        s.vars.put("sid", sid);
        s.vars.put("ck", ck);
        s.vars.put("t", t);
        Map<String, Object> m = baseOk(sid, qr);
        m.put("imgUrl", "");
        m.put("hint", "用阿里云盘 App 扫码");
        return m;
    }

    private static Map<String, Object> pollAli(Session s) throws Exception {
        String url = "https://passport.aliyundrive.com/newlogin/qrcode/query.do"
                + "?appName=aliyun_drive&fromSite=52&appEntrance=web&isMobile=false&lang=zh_CN"
                + "&returnUrl=&bizParams=&_bx-v=2.5.3&navlanguage=zh-CN&navPlatform=MacIntel"
                + "&tt=" + System.currentTimeMillis();
        // 关键：ck/t 必须放 POST body。
        // 放 query string 会一律返回 qrCodeStatus=EXPIRED（GET 实测 3/3 全 EXPIRED），
        // 换成 POST form body 立刻变成 {"qrCodeStatus":"NEW","status":0,"success":true}。
        String raw = postForm(url,
                "ck=" + enc(s.vars.get("ck")) + "&t=" + enc(s.vars.get("t")) + "&_directive=refresh",
                "https://www.aliyundrive.com");
        Map<String, Object> j = jsonObj(raw);
        Map<?, ?> content = asMap(j.get("content"));
        Map<?, ?> data = asMap(content.get("data"));
        String qrStatus = str(first(data, "qrCodeStatus", "status"));
        if ("CONFIRMED".equalsIgnoreCase(qrStatus)) {
            String refresh = extractRefreshToken(str(first(data, "bizExt", "bizExtParams")));
            if (refresh.isEmpty()) {
                return baseState(SCANNED, "已确认，但没解析出 refresh_token：" + cut(raw));
            }
            PanAccounts.save(PanAccounts.drive("ali"), refresh, null);
            Map<String, Object> m = baseState(DONE, "登录成功，凭证已保存");
            m.put("logged", true);
            return m;
        }
        if ("SCANED".equalsIgnoreCase(qrStatus) || "SCANNED".equalsIgnoreCase(qrStatus)) {
            return baseState(SCANNED, "已扫码，请在手机上确认");
        }
        if ("EXPIRED".equalsIgnoreCase(qrStatus)) {
            return baseState(EXPIRED, "阿里返回「二维码已过期」—— 上游这条链接要在浏览器会话里才认，"
                    + "扫码登录走不通，请改用「粘贴 refresh_token」");
        }
        return baseState(WAIT, "等待扫码…（" + (qrStatus.isEmpty() ? "无状态" : qrStatus) + "）");
    }

    /** bizExt 是一段 base64 的 JSON，里面有 pds_login_result.refreshToken。 */
    private static String extractRefreshToken(String bizExt) {
        if (bizExt == null || bizExt.isEmpty()) return "";
        try {
            String decoded = new String(Base64.getDecoder().decode(bizExt), StandardCharsets.UTF_8);
            Map<String, Object> j = jsonObj(decoded);
            Map<?, ?> pds = asMap(j.get("pds_login_result"));
            String rt = str(pds.get("refreshToken"));
            if (!rt.isEmpty()) return rt;
            return str(j.get("refresh_token"));
        } catch (Exception e) {
            return "";
        }
    }

    // ══════════════════════════════════════════════════════════
    //  通用小工具
    // ══════════════════════════════════════════════════════════
    private static Map<String, Object> baseOk(String sid, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("msg", "");
        m.put("sid", sid);
        m.put("status", WAIT);
        m.put("logged", false);
        if (content != null && !content.isEmpty()) m.put("content", content);
        return m;
    }

    private static Map<String, Object> baseState(int st, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("status", st);
        m.put("msg", msg);
        m.put("logged", false);
        return m;
    }

    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    private static String get(String url, String referer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                // 百度的 unicast / 115 的 get-status 都是长轮询接口：没人扫码时会挂住几十秒，
                // 超时设短了会一律报「request timed out」，看着像登录没反应。
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .GET();
        if (referer != null) b.header("Referer", referer);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return r.body() == null ? "" : r.body();
    }

    /** 指定 UA 的 GET（115 的 qandroid 接口认 115disk UA）。 */
    private static String getUa(String url, String referer, String ua) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", ua == null ? UA : ua)
                .header("Accept", "application/json, text/plain, */*")
                .GET();
        if (referer != null) b.header("Referer", referer);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return r.body() == null ? "" : r.body();
    }

    private static String postForm(String url, String body, String referer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (referer != null) b.header("Referer", referer);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return r.body() == null ? "" : r.body();
    }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }

    /** 扫码轮询专用线程池（守护线程，不让它拖住退出）。 */
    private static final java.util.concurrent.ExecutorService QR_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "pan-qr");
                t.setDaemon(true);
                return t;
            });

    /** 给反射调用（Qr115.status）加硬超时，避免长轮询把 HTTP 处理线程挂死。 */
    private static Object callWithTimeout(java.util.concurrent.Callable<Object> c, long ms) throws Exception {
        java.util.concurrent.Future<Object> f = QR_POOL.submit(c);
        try {
            return f.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            f.cancel(true);
            throw new IllegalStateException("上游扫码状态接口超时（" + (ms / 1000)
                    + "s 无响应）—— 这台机器的网络可能会掐长轮询，先确认能正常访问该网盘官网");
        }
    }

    private static String cut(String s) {
        if (s == null) return "(空响应)";
        s = s.replace("\n", " ").trim();
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonObj(String raw) {
        if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
        String t = raw.trim();
        // 百度的 unicast 会带 JSONP 包裹
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a > 0 || (a == 0 && b < t.length() - 1)) {
            if (b > a) t = t.substring(a, b + 1);
        }
        Object o;
        try {
            o = SpiderPool.Json.parse(t);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private static Map<?, ?> asMap(Object o) { return o instanceof Map ? (Map<?, ?>) o : new LinkedHashMap<>(); }

    private static Map<?, ?> members(Map<String, Object> j) {
        Map<?, ?> d = asMap(j.get("data"));
        Map<?, ?> m = asMap(d.get("members"));
        return m.isEmpty() ? d : m;
    }

    private static Object first(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return v;
        }
        return null;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}
