import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 最小可行性验证：在纯 JVM 里把一个 CatVod spider 跑起来。
 *
 * 用法: java SpiderRunner <spider类名> [方法]
 *   方法: home | category <tid> <pg> | detail <id> | search <key>
 *
 * 目的不是做产品，而是回答一个问题：
 * 这些 spider 能不能脱离 Android 运行时、在纯 JVM 里被加载并产出数据。
 */
public class SpiderRunner {

    public static void main(String[] args) throws Exception {
        String cls = args.length > 0 ? args[0] : "com.github.catvod.spider.Bili";
        String method = args.length > 1 ? args[1] : "home";

        line("=== 加载类: " + cls + " ===");
        Class<?> c;
        try {
            c = Class.forName(cls);
            line("✅ 类加载成功，父类: " + (c.getSuperclass() != null ? c.getSuperclass().getName() : "(none)"));
        } catch (Throwable t) {
            line("❌ 类加载失败: " + t);
            return;
        }

        Object spider;
        try {
            spider = c.getDeclaredConstructor().newInstance();
            line("✅ 实例化成功");
        } catch (Throwable t) {
            line("❌ 实例化失败: " + t);
            return;
        }

        // init(Context, String) —— 大部分 spider 在这里做配置解析
        try {
            Method init = findMethod(c, "init", 2);
            if (init != null) {
                init.setAccessible(true);
                String ext = System.getenv("SPIDER_EXT");
                if (ext == null) ext = "";
                init.invoke(spider, null, ext);
                line("✅ init(null, ext=" + (ext.length() > 60 ? ext.substring(0, 60) + "…(" + ext.length() + "字符)" : "\"" + ext + "\"") + ") 调用成功");
            } else {
                line("… 没有 init(Context, String) 方法，跳过");
            }
        } catch (Throwable t) {
            line("⚠ init 抛异常（继续尝试取数据）: " + unwrap(t));
        }

        line("");
        line("=== 调用 " + method + " ===");
        try {
            Object out;
            switch (method) {
                case "category": {
                    String tid = args.length > 2 ? args[2] : "1";
                    String pg = args.length > 3 ? args[3] : "1";
                    Method m = findMethod(c, "categoryContent", 4);
                    if (m == null) { line("❌ 没有 categoryContent 方法"); return; }
                    m.setAccessible(true);
                    out = m.invoke(spider, tid, pg, false, new HashMap<String, String>());
                    break;
                }
                case "detail": {
                    String id = args.length > 2 ? args[2] : "";
                    Method m = findMethod(c, "detailContent", 1);
                    if (m == null) { line("❌ 没有 detailContent 方法"); return; }
                    List<String> ids = new ArrayList<>();
                    ids.add(id);
                    m.setAccessible(true);
                    out = m.invoke(spider, ids);
                    break;
                }
                case "search": {
                    String key = args.length > 2 ? args[2] : "测试";
                    Method m = findMethod(c, "searchContent", 2);
                    if (m == null) { line("❌ 没有 searchContent 方法"); return; }
                    m.setAccessible(true);
                    out = m.invoke(spider, key, true);
                    break;
                }
                default: {
                    Method m = findMethod(c, "homeContent", 1);
                    if (m == null) { line("❌ 没有 homeContent 方法"); return; }
                    m.setAccessible(true);
                    out = m.invoke(spider, false);
                }
            }
            if (out == null) {
                line("⚠ 返回 null");
            } else {
                String s = String.valueOf(out);
                // 完整内容写文件，避免 stdout 截断导致统计误判
                try {
                    java.nio.file.Files.write(
                        java.nio.file.Paths.get("/tmp/spider_out.json"),
                        s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ignore) {
                }
                line("✅ 返回 " + s.length() + " 字符");
                line(pretty(s));
            }
        } catch (Throwable t) {
            line("❌ 调用失败: " + unwrap(t));
        }
    }

    /** 按参数个数找方法（子类可能用不同签名，比如 categoryContent 有 3 参和 4 参版本） */
    private static Method findMethod(Class<?> c, String name, int paramCount) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
        }
        return null;
    }

    private static Throwable unwrap(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t;
    }

    private static String pretty(String s) {
        s = s.trim();
        if (s.length() > 1200) return s.substring(0, 1200) + "\n…(截断，共 " + s.length() + " 字符)";
        return s;
    }

    private static void line(String s) {
        System.out.println(s);
    }
}
