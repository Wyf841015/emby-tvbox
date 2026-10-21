package com.github.catvod.crawler;

/**
 * catvod 的调试日志入口。spider 里到处调 SpiderDebug.log(...)，
 * 我们没这个类就 ClassNotFoundException（B 站系列详情全挂）。
 * 直接打到 stdout，用开关控制。
 */
public class SpiderDebug {
    public static boolean enabled = false;

    public static void log(Object obj) {
        if (enabled) System.out.println("[spider-debug] " + obj);
    }

    public static void log(String tag, Object obj) {
        if (enabled) System.out.println("[spider-debug] " + tag + " : " + obj);
    }

    public static void log(String msg) {
        if (enabled) System.out.println("[spider-debug] " + msg);
    }

    public static void log(Object obj, Throwable t) {
        if (enabled) {
            System.out.println("[spider-debug] " + obj + " → " + t);
            if (t != null) t.printStackTrace(System.out);
        }
    }

    public static void log(Throwable t) {
        if (enabled && t != null) t.printStackTrace(System.out);
    }
}
