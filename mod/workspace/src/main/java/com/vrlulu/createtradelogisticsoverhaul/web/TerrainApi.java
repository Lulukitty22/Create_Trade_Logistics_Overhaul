package com.vrlulu.createtradelogisticsoverhaul.web;

import com.sun.net.httpserver.HttpExchange;
import com.vrlulu.createtradelogisticsoverhaul.terrain.TerrainStore;
import com.vrlulu.createtradelogisticsoverhaul.terrain.VoxyBridge;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The terrain half of the local API, backed by Voxy's live data.
 *
 * <p>Same contract as the Python prototype (see DESIGN.md), so the page is identical. Textures and
 * block models aren't served yet: the page falls back to flat colours when "textures" is false.
 */
public class TerrainApi {
    private static final long INDEX_INTERVAL_MS = 60_000;   // a full key scan; cheap enough once a minute

    private final TerrainStore store = new TerrainStore();

    private WorldEngine engineOrNull() {
        WorldEngine engine = VoxyBridge.engine();
        if (engine != null) {
            store.refreshIndex(engine, INDEX_INTERVAL_MS);
        }
        return engine;
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
                .append(",\"live\":false,\"textures\":false")
                .append(",\"maxLod\":").append(store.maxLod())
                .append(",\"snapshotTime\":").append(System.currentTimeMillis() / 1000)
                .append(",\"lastChange\":null")
                .append(",\"start\":{\"x\":").append(at.x).append(",\"y\":").append(at.y)
                .append(",\"z\":").append(at.z).append('}')
                .append(",\"textureCount\":1,\"modelCount\":0,\"waterLayer\":0,");
        store.palette().writeJson(out);
        out.append('}');
        Http.json(ex, 200, out.toString());
    }

    public void palette(HttpExchange ex) throws IOException {
        StringBuilder out = new StringBuilder(1 << 16).append('{');
        store.palette().writeJson(out);
        out.append(",\"textureCount\":1,\"modelCount\":0,\"waterLayer\":0}");
        Http.json(ex, 200, out.toString());
    }

    /** u32 maxLod, u32 count, then i32 x,y,z per section. */
    public void roots(HttpExchange ex) throws IOException {
        WorldEngine engine = engineOrNull();
        if (engine == null) {
            Http.json(ex, 503, "{\"error\":\"no world\"}");
            return;
        }
        List<int[]> roots = store.sectionsAt(store.maxLod());
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

    /** A single grey layer, so the page's texture array is valid while textures are unimplemented. */
    public void textures(HttpExchange ex) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(12 + 16 * 16 * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("VXA1".getBytes(StandardCharsets.US_ASCII)).putInt(1).putInt(16);
        for (int i = 0; i < 16 * 16; i++) {
            buf.put((byte) 128).put((byte) 128).put((byte) 128).put((byte) 255);
        }
        Http.bytes(ex, 200, "application/octet-stream", buf.array());
    }

    public void models(HttpExchange ex) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(12 + 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("VXM1".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(0).putInt(0);
        Http.bytes(ex, 200, "application/octet-stream", buf.array());
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
