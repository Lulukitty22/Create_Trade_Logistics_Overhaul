package com.vrlulu.createtradelogisticsoverhaul.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
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

    private static final int PORT_ATTEMPTS = 10;

    private final TerrainApi terrain = new TerrainApi();
    private final int basePort;
    private final List<HttpServer> servers = new ArrayList<>();
    private int port = -1;

    public MapWebServer(int basePort) {
        this.basePort = basePort;
    }

    /** The port the map is actually served on, or -1 if it isn't running. */
    public int port() {
        return port;
    }

    public void start() {
        // Bind IPv4 loopback explicitly: getLoopbackAddress() prefers ::1 on dual-stack Windows,
        // which leaves http://127.0.0.1:<port>/ unreachable.
        Executor pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "clo-web");
            t.setDaemon(true);
            return t;
        });
        for (int attempt = 0; attempt < PORT_ATTEMPTS && port < 0; attempt++) {
            int candidate = basePort + attempt;
            try {
                servers.add(listen("127.0.0.1", candidate, pool));
                port = candidate;
            } catch (IOException e) {
                CreateTradeLogisticsOverhaul.LOG.debug("Port {} unavailable ({})", candidate, e.toString());
            }
        }
        if (port < 0) {
            CreateTradeLogisticsOverhaul.LOG.error("Could not start the logistics map server on ports {}-{}",
                    basePort, basePort + PORT_ATTEMPTS - 1);
            return;
        }
        try {   // also answer on the IPv6 loopback, so "localhost" works however it resolves
            servers.add(listen("::1", port, pool));
        } catch (IOException e) {
            CreateTradeLogisticsOverhaul.LOG.debug("No IPv6 loopback listener: {}", e.toString());
        }
        CreateTradeLogisticsOverhaul.LOG.info("Logistics map available at http://127.0.0.1:{}/", port);
    }

    private HttpServer listen(String host, int port, Executor pool) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0);
        server.createContext("/", this::handleStatic);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/world", wrap(terrain::world));
        server.createContext("/api/palette", wrap(terrain::palette));
        server.createContext("/api/terrain/roots", wrap(terrain::roots));
        server.createContext("/api/terrain/sections", wrap(terrain::sections));
        server.createContext("/api/assets/textures", wrap(terrain::textures));
        server.createContext("/api/assets/models", wrap(terrain::models));
        server.setExecutor(pool);
        server.start();
        return server;
    }

    public void stop() {
        servers.forEach(s -> s.stop(0));
        servers.clear();
        port = -1;
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        send(ex, 200, "application/json",
                ("{\"mod\":\"" + CreateTradeLogisticsOverhaul.ID + "\",\"ok\":true,\"port\":" + port + "}")
                        .getBytes(StandardCharsets.UTF_8));
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

    /** Keeps one failing request from killing the handler thread silently. */
    private HttpHandler wrap(ThrowingHandler handler) {
        return ex -> {
            try {
                handler.handle(ex);
            } catch (Throwable t) {
                CreateTradeLogisticsOverhaul.LOG.error("Map API error on {}", ex.getRequestURI(), t);
                byte[] body = ("{\"error\":\"" + String.valueOf(t).replace("\"", "'") + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                send(ex, 500, "application/json", body);
            } finally {
                ex.close();
            }
        };
    }

    private interface ThrowingHandler {
        void handle(HttpExchange ex) throws Exception;
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
