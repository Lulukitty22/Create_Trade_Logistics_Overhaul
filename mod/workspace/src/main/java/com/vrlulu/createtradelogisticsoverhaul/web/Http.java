package com.vrlulu.createtradelogisticsoverhaul.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Small response helpers shared by the handlers. */
final class Http {
    private Http() {
    }

    static void json(HttpExchange ex, int code, String body) throws IOException {
        bytes(ex, code, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    static void bytes(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
