package dev.bursa.stockexchange.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.bursa.stockexchange.StockExchangePlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/** Server HTTP kecil (JDK bawaan) untuk dashboard saham realtime. Hanya GET, read-only. */
public final class WebServer {
    private static final int RATE_LIMIT = 40;        // permintaan
    private static final long RATE_WINDOW_MS = 10_000; // per 10 detik per IP

    private final StockExchangePlugin plugin;
    private final Snapshot snapshot;
    private final Map<String, long[]> hits = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService pool;
    private byte[] indexHtml = new byte[0];
    private long lastCleanup;

    public WebServer(StockExchangePlugin plugin, Snapshot snapshot) {
        this.plugin = plugin;
        this.snapshot = snapshot;
    }

    public boolean start() {
        try (InputStream in = plugin.getResource("web/index.html")) {
            if (in == null) {
                plugin.getLogger().warning("web/index.html tidak ada di jar, website dimatikan.");
                return false;
            }
            indexHtml = in.readAllBytes();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Gagal membaca web/index.html", e);
            return false;
        }

        String bind = plugin.getConfig().getString("web.bind", "0.0.0.0");
        int port = plugin.getConfig().getInt("web.port", 8123);
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 50);
            pool = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "StockExchange-Web");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(pool);
            server.createContext("/", this::handle);
            server.start();
            plugin.getLogger().info("Website bursa aktif di http://" + bind + ":" + port + "/");
            return true;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Gagal menyalakan website bursa di " + bind + ":" + port
                    + " (port dipakai/diblokir hosting?). Ubah web.port di config.yml atau matikan web.enabled.", t);
            stop();
            return false;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                send(ex, 405, "text/plain; charset=utf-8", "Method Not Allowed", false);
                return;
            }
            if (limited(ex)) {
                send(ex, 429, "text/plain; charset=utf-8", "Terlalu banyak permintaan", false);
                return;
            }
            boolean head = method.equals("HEAD");
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                ex.getResponseHeaders().set("Content-Security-Policy",
                        "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:");
                send(ex, 200, "text/html; charset=utf-8", indexHtml, head);
            } else if (path.equals("/api/snapshot")) {
                send(ex, 200, "application/json; charset=utf-8", snapshot.data().summary(), head);
            } else if (path.startsWith("/api/stock/")) {
                String t = path.substring("/api/stock/".length()).toUpperCase(Locale.ROOT);
                String json = t.matches("[A-Z0-9]{2,6}") ? snapshot.data().details().get(t) : null;
                if (json == null) send(ex, 404, "application/json; charset=utf-8", "{\"error\":\"not found\"}", head);
                else send(ex, 200, "application/json; charset=utf-8", json, head);
            } else {
                send(ex, 404, "text/plain; charset=utf-8", "Not Found", head);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Error web handler", t);
            try {
                send(ex, 500, "text/plain; charset=utf-8", "Server Error", false);
            } catch (Throwable ignored) {
                // koneksi sudah putus
            }
        } finally {
            ex.close();
        }
    }

    private boolean limited(HttpExchange ex) {
        long now = System.currentTimeMillis();
        if (now - lastCleanup > 60_000) {
            lastCleanup = now;
            hits.entrySet().removeIf(e -> now - e.getValue()[0] > RATE_WINDOW_MS);
        }
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        long[] slot = hits.computeIfAbsent(ip, k -> new long[]{now, 0});
        synchronized (slot) {
            if (now - slot[0] > RATE_WINDOW_MS) {
                slot[0] = now;
                slot[1] = 0;
            }
            slot[1]++;
            return slot[1] > RATE_LIMIT;
        }
    }

    private void send(HttpExchange ex, int code, String type, String body, boolean head) throws IOException {
        send(ex, code, type, body.getBytes(StandardCharsets.UTF_8), head);
    }

    private void send(HttpExchange ex, int code, String type, byte[] body, boolean head) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        if (head) {
            h.set("Content-Length", String.valueOf(body.length));
            ex.sendResponseHeaders(code, -1);
            return;
        }
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
