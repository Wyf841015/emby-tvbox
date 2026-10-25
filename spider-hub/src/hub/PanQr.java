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
            case "115":   r = start115(s, spiderLoader); break;
            case "quark": r = startAliyunFamily(s, "quark"); break;
            case "uc":    r = startAliyunFamily(s, "uc"); break;
            case "baidu": r = startBaidu(s); break;
            case "tianyi":r = startTianyi(s); break;
            case "ali":   r = startAli(s); break;
            default: throw new IllegalArgumentException("这个网盘不支持扫码，请用「粘贴凭证」");
        }
        SESSIONS.clear();
        SESSIONS.put(s.vars.get("sid"), s);
        return r;
    }

    /** 轮询扫码状态；返回 logged=true 时凭证已写进 panDir。 */
    public static Map<String, Object> poll(String driveId, String sid) throws Exception {
        Session s = sid == null ? null : SESSIONS.get(sid);
        if (s == null) throw new IllegalStateException("二维码会话已失效，请重新生成");
        if (!s.drive.equals(driveId)) throw new IllegalStateException("会话与网盘不匹配");
        Map<String, Object> r;
        switch (driveId) {
            case "115":   r = poll115(s); break;
            case "quark":
            case "uc":    r = pollAliyunFamily(s); break;
            case "baidu": r = pollBaidu(s); break;
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
    //  115 —— 用 jar 里的 Qr115（自带签名，别自己拼）
    // ══════════════════════════════════════════════════════════
    private static Class<?> qr115(ClassLoader ld) throws Exception {
        if (ld == null) throw new IllegalStateException("spider 还没加载（先去「配置」填一个点播源）");
        return Class.forName("com.github.catvod.spider.Qr115", true, ld);
    }

    private static Map<String, Object> start115(Session s, ClassLoader ld) throws Exception {
        Class<?> qr = qr115(ld);
        Object token = qr.getMethod("token").invoke(null);
        if (token == null) throw new IllegalStateException("115 没有返回二维码（接口风控或网络不通）");
        Class<?> tc = token.getClass();
        String uid = (String) tc.getField("uid").get(token);
        String content = (String) tc.getField("qrcode").get(token);
        s.vars.put("sid", uid);
        s.vars.put("token", "1");            // 占位，真正的 token 对象另存
        SESSIONS.put(uid, s);
        s.tokens.put(uid, token);
        Map<String, Object> m = baseOk(uid, content);
        m.put("imgUrl", "https://qrcodeapi.115.com/api/1.0/web/1.0/qrcode?uid=" + uid);
        m.put("hint", "用 115 App 扫码");
        return m;
    }

    private static Map<String, Object> poll115(Session s) throws Exception {
        Class<?> qr = qr115(s.loader);
        Object token = s.tokens.get(s.vars.get("sid"));
        if (token == null) throw new IllegalStateException("二维码会话已失效");
        Class<?> tc = token.getClass();
        // status() 走的是 115 的长轮询接口，被网络拦掉时会一直挂着 —— 加个硬超时，
        // 否则界面看起来就是「扫了没反应」。
        Object stObj = callWithTimeout(() -> qr.getMethod("status", tc).invoke(null, token), 40000);
        int st = stObj instanceof Integer ? (Integer) stObj : ERROR;
        // 常量：WAIT=0 SCANNED=1 DONE=2 EXPIRED=-1 ERROR=-99（与我们的语义一致）
        if (st == DONE) {
            qr.getMethod("login", tc).invoke(null, token);   // 自己把 cookie 写进 <panDir>/lzxw/115.txt
            PanAccounts.updateVault(PanAccounts.drive("115"));
            Map<String, Object> m = baseState(st, "登录成功，凭证已保存");
            m.put("logged", true);
            return m;
        }
        return baseState(st, st == SCANNED ? "已扫码，请在手机上确认" : st == WAIT ? "等待扫码…"
                : st == EXPIRED ? "二维码已过期"
                : "115 的状态接口无响应（本机网络把它掐了）—— 扫码登录在这台机器走不通，请改用「粘贴 cookie」");
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
        return m;
    }

    private static Map<String, Object> pollBaidu(Session s) throws Exception {
        String sign = s.vars.get("sign"), gid = s.vars.get("gid");
        String url = "https://passport.baidu.com/channel/unicast?channel_id=" + enc(sign)
                + "&tpl=netdisk&gid=" + enc(gid) + "&apiver=v3&tt=" + System.currentTimeMillis();
        String raw = get(url, "https://pan.baidu.com");
        Map<String, Object> j = jsonObj(raw);
        int errno = (int) num(j.get("errno"));
        String v = str(j.get("v"));
        // 百度 unicast 是长轮询：没扫到时 30s 后返回 {"errno":1}（这是正常，不是错误）；
        // 扫到了才带 v，v 是 base64 的 {"bduss":"..."}。
        if (!v.isEmpty()) {
            String bduss = "";
            try {
                String decoded = new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8);
                bduss = str(jsonObj(decoded).get("bduss"));
            } catch (Exception ignore) {
                // 解不出来就往下走，交给下面报原文
            }
            if (!bduss.isEmpty()) {
                PanAccounts.save(PanAccounts.drive("baidu"), "BDUSS=" + bduss, null);
                Map<String, Object> m = baseState(DONE, "登录成功，凭证已保存");
                m.put("logged", true);
                return m;
            }
            return baseState(WAIT, "拿到回调但解不出 BDUSS：" + cut(v));
        }
        return baseState(WAIT, "等待扫码…");
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
                + "&ck=" + enc(s.vars.get("ck")) + "&t=" + enc(s.vars.get("t"))
                + "&_directive=refresh&tt=" + System.currentTimeMillis();
        String raw = get(url, "https://www.aliyundrive.com");
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
