package hub;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 HTTP/1.1 服务端，监听 Unix domain socket。
 *
 * 为什么不用 com.sun.net.httpserver：它只接受 InetSocketAddress。
 * fnOS 网关只认 /var/apps/<app>/target/<app>.sock，所以得自己实现。
 *
 * 只做需要的部分：请求行 + 头 + Content-Length 正文，一次性响应后关闭连接。
 */
public class UnixHttp {

    /** 处理函数。 */
    public interface Handler {
        Resp handle(String method, String path, String rawQuery, String body, Map<String, String> headers) throws Exception;
    }

    /** 响应。 */
    public static class Resp {
        public int code = 200;
        public String contentType = "text/plain; charset=utf-8";
        public byte[] body = new byte[0];
        /** 额外响应头（Range/206、缓存、流式开关等都要靠它带出去）。 */
        public Map<String, String> headers = new LinkedHashMap<>();

        public static Resp of(String body) {
            Resp r = new Resp();
            r.body = body.getBytes(StandardCharsets.UTF_8);
            return r;
        }

        public static Resp json(String body) {
            Resp r = of(body);
            r.contentType = "application/json; charset=utf-8";
            return r;
        }

        public static Resp html(String body) {
            Resp r = of(body);
            r.contentType = "text/html; charset=utf-8";
            return r;
        }

        public Resp code(int c) {
            this.code = c;
            return this;
        }
    }

    private final Path sockPath;
    private final Handler handler;
    private volatile boolean running;

    /** 并发连接上限：每连接一个线程，不设上限时几百条半开连接就能把进程栈打满。 */
    private static final java.util.concurrent.Semaphore CONNS = new java.util.concurrent.Semaphore(128);
    /** 单连接最长存活时间（挡住只发半个请求头就挂住的连接）。 */
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "unix-http-watchdog");
                t.setDaemon(true);
                return t;
            });

    public UnixHttp(Path sockPath, Handler handler) {
        this.sockPath = sockPath;
        this.handler = handler;
    }

    public void start() throws Exception {
        Files.createDirectories(sockPath.getParent());
        // 清掉残留 socket，否则 bind 会失败
        try {
            if (Files.exists(sockPath)) Files.delete(sockPath);
        } catch (Exception ignore) {
        }

        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(sockPath));
        // fnOS 网关以 root 连这个 socket；权限收到 600，别的本地用户就别想直接调管理面了。
        try {
            Files.setPosixFilePermissions(sockPath, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception e) {
            System.out.println("[sock] chmod 600 失败（不影响启动）: " + e.getMessage());
        }
        running = true;

        Thread t = new Thread(() -> {
            while (running) {
                try {
                    SocketChannel ch = server.accept();
                    if (!CONNS.tryAcquire()) {
                        try { ch.close(); } catch (Exception ignore) { }
                        continue;
                    }
                    java.util.concurrent.ScheduledFuture<?> kill = WATCHDOG.schedule(() -> {
                        try { ch.close(); } catch (Exception ignore) { }
                    }, 120, java.util.concurrent.TimeUnit.SECONDS);
                    Thread w = new Thread(() -> {
                        try { serve(ch); } catch (Exception ignore) { } finally {
                            kill.cancel(false);
                            CONNS.release();
                        }
                    });
                    w.setDaemon(true);
                    w.start();
                } catch (Exception e) {
                    if (running) {
                        try { Thread.sleep(50); } catch (InterruptedException ignore) { }
                    }
                }
            }
        }, "unix-http-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            Files.deleteIfExists(sockPath);
        } catch (Exception ignore) {
        }
    }

    private void serve(SocketChannel ch) {
        try (SocketChannel c = ch) {
            InputStream in = Channels.newInputStream(c);
            OutputStream out = Channels.newOutputStream(c);

            // ── 请求行 ──
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];

            // ── 头 ──
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            int guard = 0;
            while ((line = readLine(in)) != null && !line.isEmpty() && guard++ < 200) {
                int i = line.indexOf(':');
                if (i > 0) {
                    headers.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
                }
            }

            // ── 正文 ──
            String body = "";
            int len = parseInt(headers.getOrDefault("content-length", "0"));
            if (len > 0 && len < 8 * 1024 * 1024) {
                byte[] buf = new byte[len];
                int read = 0;
                while (read < len) {
                    int n = in.read(buf, read, len - read);
                    if (n < 0) break;
                    read += n;
                }
                body = new String(buf, 0, read, StandardCharsets.UTF_8);
            }

            // ── 路由 ──
            String path = target;
            String rawQuery = null;
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                rawQuery = target.substring(q + 1);
            }
            // fnOS 网关把前缀原样转发过来，需要剥掉
            String prefix = System.getProperty("hub.prefix", "");
            if (!prefix.isEmpty() && path.startsWith(prefix)) {
                path = path.substring(prefix.length());
                if (path.isEmpty()) path = "/";
                // 记下来：handler 重写播放列表时要还原成浏览器实际用的路径
                headers.put("x-hub-prefix-used", prefix);
            }

            Resp resp;
            try {
                resp = handler.handle(method, path, rawQuery, body, headers);
            } catch (Exception e) {
                resp = Resp.of("ERROR: " + e).code(500);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(resp.code).append(' ').append(reason(resp.code)).append("\r\n");
            sb.append("Content-Type: ").append(resp.contentType).append("\r\n");
            sb.append("Content-Length: ").append(resp.body.length).append("\r\n");
            boolean hasCache = false;
            for (Map.Entry<String, String> e : resp.headers.entrySet()) {
                String k = e.getKey();
                if (k == null) continue;
                String lk = k.toLowerCase(Locale.ROOT);
                if (lk.equals("content-length") || lk.equals("content-type") || lk.equals("connection")) continue;
                if (lk.equals("cache-control")) hasCache = true;
                sb.append(k).append(": ").append(e.getValue()).append("\r\n");
            }
            if (!hasCache) sb.append("Cache-Control: no-store\r\n");
            sb.append("Connection: close\r\n");
            sb.append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.write(resp.body);
            out.flush();
        } catch (Exception ignore) {
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 64 * 1024) break;
        }
        if (c < 0 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.UTF_8);
    }
}
