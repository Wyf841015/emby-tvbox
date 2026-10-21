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

    public static boolean isVideoFormat(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".flv") || u.contains(".mkv");
    }

    public static boolean isVip(String url) {
        return false;
    }
}
