package com.github.catvod.crawler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CatVod spider 基类的本地实现。
 *
 * 背景：lmw.jar 里的 classes.dex 只包含 2660 个 spider 子类，
 * 并不包含 com.github.catvod.crawler.Spider 本身——基类由 TVBox 宿主 App 提供。
 * 所以在纯 JVM 里要自己补一个签名兼容的版本，让子类能正常链接。
 *
 * 方法签名是按 javap 反查实际子类的 override 反推出来的。
 */
public class Spider {

    public Spider() {
    }

    // ── 生命周期 ──
    public void init(android.content.Context context) {
    }

    public void init(android.content.Context context, String extend) {
    }

    // ── 数据接口：返回 CMS 风格的 JSON 字符串 ──
    public String homeContent(boolean filter) {
        return emptyResult();
    }

    public String homeVideoContent() {
        return emptyResult();
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return emptyResult();
    }

    public String detailContent(List<String> ids) {
        return emptyResult();
    }

    public String searchContent(String key, boolean quick) {
        return emptyResult();
    }

    public String searchContent(String key, boolean quick, String pg) {
        return emptyResult();
    }

    public String playerContent(String flag, String id, List<String> vipFlags) {
        return emptyResult();
    }

    public String liveContent(String url) {
        return emptyResult();
    }

    public Object proxy(Map<String, String> params) {
        return null;
    }

    public String getName() {
        return this.getClass().getSimpleName();
    }

    // ── 宿主/子类可能调用的辅助方法 ──
    /**
     * catvod 的 Spider.safeDns()：返回一个"更可靠"的 DNS 给 OkHttp 用。
     * 混淆代码里 merge.k.b 建 OkHttpClient 时会调 Spider.safeDns() 链式设置 dns，
     * 缺这个静态方法就是 NoSuchMethodError，整条 okhttp 通道直接断。
     * 这里返回系统 DNS；如需防污染可以换成自定义实现。
     */
    public static okhttp3.Dns safeDns() {
        return okhttp3.Dns.SYSTEM;
    }

    public static String emptyResult() {
        return "{\"class\":[],\"list\":[]}";
    }

    public static String emptyList() {
        return "{\"list\":[]}";
    }

    /** catvod 原版的视频扩展名表（spider 用它判断"这个地址是不是视频直链"）。 */
    private static final String[] VIDEO_FORMAT = {
            ".m3u8", ".mp4", ".rmvb", ".avi", ".mpeg", ".mkv", ".flv", ".mov",
            ".wmv", ".webm", ".mpd", ".m4v", ".ts", ".m4s", ".f4v", ".3gp"
    };

    /**
     * 和 catvod 原版对齐：带视频扩展名才算视频，且排除 URL 里含 "js" 的情况
     * （源站经常把 .json/.js 也塞进播放地址列表，误判成视频会让上层把网页当流播）。
     * 之前只认 4 种扩展名（m3u8/mp4/flv/mkv），.mpd/.ts/.mov 这类会被判成"不是视频"，
     * spider 于是去做网页解析，最后拿不到地址 —— 属于隐性丢源。
     */
    public static boolean isVideoFormat(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        if (u.contains("js")) return false;
        for (String s : VIDEO_FORMAT) {
            if (u.contains(s)) return true;
        }
        return false;
    }

    /** 需要二次解析的主流站关键词（catvod 原版的 VIP 判定，缺了会让 spider 少走解析分支）。 */
    private static final String[] VIP_KEYWORDS = {
            "v.qq.com", "qq.com/x/cover", "iqiyi.com", "youku.com", "mgtv.com",
            "bilibili.com", "pptv.com", "le.com", "sohu.com", "tudou.com",
            "acfun.cn", "wasu.cn", "1905.com", "fun.tv", "qiyi.com"
    };

    public static boolean isVip(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        for (String s : VIP_KEYWORDS) {
            if (u.contains(s)) return true;
        }
        return false;
    }
}
