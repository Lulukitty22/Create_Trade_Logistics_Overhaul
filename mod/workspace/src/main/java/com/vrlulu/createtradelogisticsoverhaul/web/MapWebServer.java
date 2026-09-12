package com.vrlulu.createtradelogisticsoverhaul.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The local web server that serves the map page, bound to loopback only.
 *
 * <p>Page files live in the jar under assets/createtradelogisticsoverhaul/web and are shared with the
 * Python prototype, which implements the same API (see DESIGN.md).
 */
public class MapWebServer {
    public static final int DEFAULT_PORT = 8765;
    private static final String WEB_ROOT = "/assets/" + CreateTradeLogisticsOverhaul.ID + "/web/";
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "json", "application/json", "png", "image/png",
            "svg", "image/svg+xml", "ico", "image/x-icon");

    private final int port;
    private HttpServer http;

    public MapWebServer(int port) {
        this.port = port;
    }

    public void start() {
        try {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            http.createContext("/", this::handleStatic);
            http.createContext("/api/status", this::handleStatus);
            http.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "clo-web");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            CreateTradeLogisticsOverhaul.LOG.info("Logistics map available at http://127.0.0.1:{}/", port);
        } catch (IOException e) {
            CreateTradeLogisticsOverhaul.LOG.error("Could not start the logistics map server on port {}", port, e);
        }
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        send(ex, 200, "application/json",
                ("{\"mod\":\"" + CreateTradeLogisticsOverhaul.ID + "\",\"ok\":true}").getBytes(StandardCharsets.UTF_8));
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            send(ex, 400, "text/plain", "bad path".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = MapWebServer.class.getResourceAsStream(WEB_ROOT + path.substring(1))) {
            if (in == null) {
                send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
            send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), in.readAllBytes());
        }
    }

    private void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
