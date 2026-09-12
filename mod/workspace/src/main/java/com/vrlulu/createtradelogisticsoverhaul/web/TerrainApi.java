package com.vrlulu.createtradelogisticsoverhaul.web;

import com.sun.net.httpserver.HttpExchange;
import com.vrlulu.createtradelogisticsoverhaul.net.ClientTerminals;
import com.vrlulu.createtradelogisticsoverhaul.net.Payloads;
import com.vrlulu.createtradelogisticsoverhaul.terrain.BlockAssets;
import com.vrlulu.createtradelogisticsoverhaul.terrain.ChangeHub;
import com.vrlulu.createtradelogisticsoverhaul.terrain.TerrainStore;
import com.vrlulu.createtradelogisticsoverhaul.terrain.VoxyBridge;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The terrain half of the local API, backed by Voxy's live data.
 *
 * <p>Same contract as the Python prototype (see DESIGN.md), so the page is identical: terrain
 * sections, the palette, a texture array, baked block models, and a live event stream.
 */
public class TerrainApi {
    private final TerrainStore store = new TerrainStore();

    private WorldEngine engineOrNull() {
        return VoxyBridge.engine();
    }

    public void world(HttpExchange ex) throws IOException {
        WorldEngine engine = engineOrNull();
        if (engine == null) {
            Http.json(ex, 503, "{\"error\":\"" + (VoxyBridge.available()
                    ? "no Voxy data for this world yet - join a world and look around"
                    : "Voxy is not installed") + "\"}");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 at = mc.player != null ? mc.player.position() : Vec3.ZERO;
        StringBuilder out = new StringBuilder(1 << 16);
        out.append("{\"name\":\"").append(worldName(mc)).append('"')
                .append(",\"live\":true,\"textures\":true")
                .append(",\"maxLod\":").append(store.maxLod())
                .append(",\"snapshotTime\":").append(System.currentTimeMillis() / 1000)
                .append(",\"lastChange\":null")
                .append(",\"start\":{\"x\":").append(at.x).append(",\"y\":").append(at.y)
                .append(",\"z\":").append(at.z).append('}')
                .append(",\"textureCount\":").append(store.palette().assets().layerCount())
                .append(",\"modelCount\":").append(store.palette().assets().modelCount()).append(",\"waterLayer\":").append(store.palette().waterLayer()).append(',');
        store.palette().writeJson(out);
        out.append('}');
        Http.json(ex, 200, out.toString());
    }

    public void palette(HttpExchange ex) throws IOException {
        StringBuilder out = new StringBuilder(1 << 16).append('{');
        store.palette().writeJson(out);
        out.append(",\"textureCount\":").append(store.palette().assets().layerCount())
                .append(",\"modelCount\":").append(store.palette().assets().modelCount()).append(",\"waterLayer\":").append(store.palette().waterLayer()).append('}');
        Http.json(ex, 200, out.toString());
    }

    /** u32 maxLod, u32 count, then i32 x,y,z per section. */
    public void roots(HttpExchange ex) throws IOException {
        WorldEngine engine = engineOrNull();
        if (engine == null) {
            Http.json(ex, 503, "{\"error\":\"no world\"}");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 at = mc.player != null ? mc.player.position() : Vec3.ZERO;
        List<int[]> roots = store.roots(engine, at.x, at.z);
        ByteBuffer buf = ByteBuffer.allocate(8 + roots.size() * 12).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(store.maxLod()).putInt(roots.size());
        for (int[] r : roots) {
            buf.putInt(r[0]).putInt(r[1]).putInt(r[2]);
        }
        Http.bytes(ex, 200, "application/octet-stream", buf.array());
    }

    /** Body: i32 lod,x,y,z per requested section. Response: "VXS1", u32 count, u32 paletteLen, records. */
    public void sections(HttpExchange ex) throws IOException {
        WorldEngine engine = engineOrNull();
        byte[] body = ex.getRequestBody().readAllBytes();
        if (engine == null) {
            Http.json(ex, 503, "{\"error\":\"no world\"}");
            return;
        }
        ByteBuffer in = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        int count = body.length / 16;
        byte[][] records = new byte[count][];
        int total = 0;
        for (int i = 0; i < count; i++) {
            records[i] = store.record(engine, in.getInt(), in.getInt(), in.getInt(), in.getInt());
            total += records[i].length;
        }
        ByteBuffer out = ByteBuffer.allocate(12 + total).order(ByteOrder.LITTLE_ENDIAN);
        out.put("VXS1".getBytes(StandardCharsets.US_ASCII)).putInt(count).putInt(store.palette().size());
        for (byte[] r : records) {
            out.put(r);
        }
        Http.bytes(ex, 200, "application/octet-stream", out.array());
    }

    /**
     * Server-Sent Events: the keys of sections Voxy just changed, so the page re-fetches only those.
     * Fed by the mixin on Voxy's markDirty (see ChangeHub).
     */
    public void events(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.getResponseHeaders().add("Connection", "close");
        ex.sendResponseHeaders(200, 0);
        ChangeHub.Subscription sub = ChangeHub.get().subscribe();
        long version = 0;
        try (OutputStream out = ex.getResponseBody()) {
            out.write("retry: 2000\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            long lastPing = System.currentTimeMillis();
            while (true) {
                long[] keys = sub.drain(1000);
                for (String result : sub.takeOrderResults()) {
                    boolean ok = result.startsWith("ok|");
                    out.write(("event: order\ndata: {\"ok\":" + ok + ",\"message\":\""
                            + result.substring(result.indexOf('|') + 1).replace("\"", "'")
                            + "\"}\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    lastPing = System.currentTimeMillis();
                }
                if (sub.takeTerminalsChanged()) {
                    out.write("event: terminals\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    lastPing = System.currentTimeMillis();
                }
                if (sub.takeResync()) {
                    out.write("event: resync\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    lastPing = System.currentTimeMillis();
                }
                if (keys.length == 0) {
                    if (System.currentTimeMillis() - lastPing > 15_000) {
                        out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        lastPing = System.currentTimeMillis();
                    }
                    continue;
                }
                StringBuilder data = new StringBuilder(keys.length * 24 + 64);
                data.append("event: changed\ndata: {\"v\":").append(++version).append(",\"paletteLen\":")
                        .append(store.palette().size()).append(",\"keys\":[");
                for (int i = 0; i < keys.length; i++) {
                    long k = keys[i];
                    data.append(i == 0 ? "" : ",").append('[').append(WorldEngine.getLevel(k)).append(',')
                            .append(WorldEngine.getX(k)).append(',').append(WorldEngine.getY(k)).append(',')
                            .append(WorldEngine.getZ(k)).append(']');
                }
                data.append("]}\n\n");
                out.write(data.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                lastPing = System.currentTimeMillis();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException closed) {
            // page navigated away
        } finally {
            ChangeHub.get().unsubscribe(sub);
        }
    }

    /** The terminals the player can see, with their stock. */
    public void terminals(HttpExchange ex) throws IOException {
        ClientTerminals.requestRefresh(false);
        StringBuilder out = new StringBuilder(1 << 14);
        out.append("{\"updatedAt\":").append(ClientTerminals.updatedAt()).append(",\"terminals\":[");
        List<Payloads.TerminalInfo> list = ClientTerminals.get();
        for (int i = 0; i < list.size(); i++) {
            Payloads.TerminalInfo t = list.get(i);
            out.append(i == 0 ? "" : ",")
                    .append("{\"x\":").append(t.pos().getX())
                    .append(",\"y\":").append(t.pos().getY())
                    .append(",\"z\":").append(t.pos().getZ())
                    .append(",\"dimension\":\"").append(esc(t.dimension())).append('"')
                    .append(",\"name\":\"").append(esc(t.name())).append('"')
                    .append(",\"address\":\"").append(esc(t.address())).append('"')
                    .append(",\"owner\":\"").append(esc(t.owner())).append('"')
                    .append(",\"tuned\":").append(t.tuned())
                    .append(",\"stock\":[");
            List<Payloads.StockLine> stock = t.stock();
            for (int j = 0; j < stock.size(); j++) {
                Payloads.StockLine line = stock.get(j);
                out.append(j == 0 ? "" : ",")
                        .append("{\"item\":\"").append(esc(line.item())).append('"')
                        .append(",\"name\":\"").append(esc(line.display())).append('"')
                        .append(",\"count\":").append(line.count()).append('}');
            }
            out.append("]}");
        }
        out.append("]}");
        Http.json(ex, 200, out.toString());
    }

    /** Places an order: {"x":..,"y":..,"z":..,"item":"minecraft:iron_ingot","count":64,"address":"PD-C01-B02"} */
    public void order(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            int x = (int) jsonNumber(body, "x"), y = (int) jsonNumber(body, "y"), z = (int) jsonNumber(body, "z");
            int count = (int) jsonNumber(body, "count");
            String item = jsonString(body, "item");
            String address = jsonString(body, "address");
            if (item.isEmpty() || address.isEmpty() || count <= 0) {
                Http.json(ex, 400, "{\"error\":\"item, count and address are required\"}");
                return;
            }
            ClientTerminals.order(new net.minecraft.core.BlockPos(x, y, z), item, count, address);
            Http.json(ex, 202, "{\"accepted\":true}");
        } catch (RuntimeException e) {
            Http.json(ex, 400, "{\"error\":\"bad request\"}");
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Tiny JSON readers: the page only ever sends these flat objects. */
    private static double jsonNumber(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?[0-9.]+)").matcher(json);
        if (!m.find()) {
            throw new IllegalArgumentException(key);
        }
        return Double.parseDouble(m.group(1));
    }

    private static String jsonString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    /** The page's "Full re-sync" button: re-read everything from Voxy. */
    public void resync(HttpExchange ex) throws IOException {
        store.invalidate();
        ChangeHub.get().requestResync();
        Http.json(ex, 200, "{\"ok\":true}");
    }

    /** "VXA1", u32 layer count, u32 size, then RGBA pixels per layer. */
    public void textures(HttpExchange ex) throws IOException {
        byte[] pixels = store.palette().assets().atlasBytes();
        int count = store.palette().assets().layerCount();
        ByteBuffer buf = ByteBuffer.allocate(12 + pixels.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("VXA1".getBytes(StandardCharsets.US_ASCII)).putInt(count).putInt(BlockAssets.TEX).put(pixels);
        Http.bytes(ex, 200, "application/octet-stream", buf.array());
    }

    public void models(HttpExchange ex) throws IOException {
        Http.bytes(ex, 200, "application/octet-stream", store.palette().assets().modelsBlob());
    }

    private static String worldName(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) {
            return escape(mc.getSingleplayerServer().getWorldData().getLevelName());
        }
        return mc.getCurrentServer() != null ? escape(mc.getCurrentServer().name) : "Minecraft";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
