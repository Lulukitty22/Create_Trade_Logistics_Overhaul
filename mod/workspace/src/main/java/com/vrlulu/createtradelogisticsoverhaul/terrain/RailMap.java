package com.vrlulu.createtradelogisticsoverhaul.terrain;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalPackagePort;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Create's railway, as the map draws it: the track, the stations on it, which stations can reach
 * which, and where the trains are right now.
 *
 * <p>Read from {@code Create.RAILWAYS} on the client - Create syncs its track graphs so it can draw
 * the rails itself, so none of this needs a packet of our own.
 *
 * <p>All of it is built <em>on the game thread</em> and handed to the web server as finished text.
 * Walking Create's graphs from an HTTP thread meant touching structures the game was busy mutating,
 * once a second, which showed up as a stutter. Track geometry is rebuilt only when the railway
 * actually changes shape; trains are the only part that moves, so they alone are refreshed often.
 */
public final class RailMap {
    /** Points sampled along a curved edge. Straight edges only need their two ends. */
    private static final int CURVE_SAMPLES = 12;
    private static final int TRAIN_INTERVAL_TICKS = 10;      // twice a second
    private static final int LAYOUT_CHECK_TICKS = 20;        // once a second, and it is only a count
    /** How long the railway must hold still before its shape is read again. */
    private static final long REBUILD_COOLDOWN_MS = 60_000;

    private static volatile String layoutJson = "\"tracks\":[],\"stations\":[],\"links\":[]";
    private static volatile String trainsJson = "\"trains\":[]";
    private static String layoutSignature = "";
    private static long rebuildAllowedAt;
    private static int ticks;

    private RailMap() {
    }

    /** The finished document. Costs nothing but a concatenation. */
    public static String toJson() {
        return "{" + layoutJson + "," + trainsJson + "}";
    }

    /** Called from the client tick, so Create's data is only ever read on its own thread. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || Create.RAILWAYS == null) {
            return;
        }
        ticks++;
        try {
            if (ticks % LAYOUT_CHECK_TICKS == 0) {
                long signatureStart = System.nanoTime();
                String signature = layoutSignature();
                com.vrlulu.createtradelogisticsoverhaul.web.Perf.record(
                        "client:RailMap.signature", System.nanoTime() - signatureStart);
                if (!signature.equals(layoutSignature) && System.currentTimeMillis() >= rebuildAllowedAt) {
                    layoutSignature = signature;
                    // Building or tearing up track changes this many times in a row; rebuilding on
                    // each one would stall the game repeatedly, so settle down before looking again.
                    rebuildAllowedAt = System.currentTimeMillis() + REBUILD_COOLDOWN_MS;
                    long layoutStart = System.nanoTime();
                    layoutJson = buildLayout(mc.level.dimension());
                    com.vrlulu.createtradelogisticsoverhaul.web.Perf.record(
                            "client:RailMap.buildLayout", System.nanoTime() - layoutStart);
                }
            }
            if (ticks % TRAIN_INTERVAL_TICKS == 0) {
                long trainStart = System.nanoTime();
                trainsJson = buildTrains(mc.level.dimension());
                com.vrlulu.createtradelogisticsoverhaul.web.Perf.record(
                        "client:RailMap.buildTrains", System.nanoTime() - trainStart);
            }
        } catch (Throwable t) {
            CreateTradeLogisticsOverhaul.LOG.debug("Could not read the railway", t);
        }
    }

    /**
     * Cheap stand-in for "has the railway changed shape". Counting nodes and stations costs nothing,
     * and after a railway is built it stops changing, so the expensive rebuild stops happening.
     */
    private static String layoutSignature() {
        int nodes = 0;
        int stations = 0;
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            nodes += graph.getNodes().size();
            stations += graph.getPoints(EdgePointType.STATION).size();
        }
        return Create.RAILWAYS.trackNetworks.size() + ":" + nodes + ":" + stations;
    }

    // ------------------------------------------------------------------ layout
    private static String buildLayout(ResourceKey<Level> here) {
        StringBuilder out = new StringBuilder(1 << 14);
        out.append("\"tracks\":[");
        boolean first = true;
        Set<Long> seen = new HashSet<>();
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (TrackNodeLocation location : graph.getNodes()) {
                TrackNode node = graph.locateNode(location);
                Map<TrackNode, TrackEdge> connections = node == null ? null : graph.getConnectionsFrom(node);
                if (connections == null) {
                    continue;
                }
                for (TrackEdge edge : connections.values()) {
                    if (edge == null || edge.node1 == null || edge.node2 == null) {
                        continue;
                    }
                    if (!seen.add(edgeKey(edge))) {
                        continue;           // each edge is reachable from both ends; draw it once
                    }
                    if (!here.equals(edge.node1.getLocation().dimension)) {
                        continue;
                    }
                    first = appendEdge(out, graph, edge, first);
                }
            }
        }

        out.append("],\"stations\":[");
        first = true;
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                if (station.blockEntityPos == null || !here.equals(station.blockEntityDimension)) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append("{\"name\":\"").append(escape(station.name)).append('"')
                        .append(",\"graph\":\"").append(graph.id.toString(), 0, 8).append('"')
                        .append(",\"x\":").append(station.blockEntityPos.getX())
                        .append(",\"y\":").append(station.blockEntityPos.getY())
                        .append(",\"z\":").append(station.blockEntityPos.getZ())
                        .append(",\"addresses\":[");
                boolean firstAddress = true;
                for (GlobalPackagePort port : station.connectedPorts.values()) {
                    if (port.address == null || port.address.isBlank()) {
                        continue;
                    }
                    if (!firstAddress) {
                        out.append(',');
                    }
                    firstAddress = false;
                    out.append('"').append(escape(port.address)).append('"');
                }
                out.append("]}");
            }
        }

        out.append("],\"links\":[");
        first = true;
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            java.util.Map<GlobalStation, TrackEdge> stationEdges = stationEdges(graph);
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                if (!here.equals(station.blockEntityDimension)) {
                    continue;
                }
                for (String neighbour : neighboursOf(graph, station, stationEdges.get(station))) {
                    // One line per ordered pair; the page draws each undirected pair once.
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append("{\"from\":\"").append(escape(station.name))
                            .append("\",\"to\":\"").append(escape(neighbour)).append("\"}");
                }
            }
        }
        return out.append(']').toString();
    }

    /**
     * The stations a train can roll to from this one without passing a third.
     *
     * <p>This is what says a siding can only be entered from its reverse point: walk outward along
     * the track and stop at the first station found in each direction, so the result is the real
     * shape of the railway rather than "everything on the same network".
     */
    private static Set<String> neighboursOf(TrackGraph graph, GlobalStation from, TrackEdge origin) {
        Set<String> found = new LinkedHashSet<>();
        if (origin == null) {
            return found;
        }
        Set<TrackNodeLocation> visited = new HashSet<>();
        Deque<TrackNode> queue = new ArrayDeque<>();
        for (TrackNode start : List.of(origin.node1, origin.node2)) {
            if (start != null && visited.add(start.getLocation())) {
                queue.add(start);
            }
        }
        while (!queue.isEmpty()) {
            TrackNode node = queue.poll();
            Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(node);
            if (connections == null) {
                continue;
            }
            for (Map.Entry<TrackNode, TrackEdge> entry : connections.entrySet()) {
                TrackEdge edge = entry.getValue();
                if (edge == null) {
                    continue;
                }
                boolean blocked = false;
                for (TrackEdgePoint point : edge.getEdgeData().getPoints()) {
                    if (point instanceof GlobalStation station && !station.name.equals(from.name)) {
                        found.add(station.name);
                        blocked = true;     // the line carries on, but not without stopping there
                    }
                }
                if (!blocked && visited.add(entry.getKey().getLocation())) {
                    queue.add(entry.getKey());
                }
            }
        }
        return found;
    }

    /** The edge each station sits on, found in a single pass over the graph. */
    private static java.util.Map<GlobalStation, TrackEdge> stationEdges(TrackGraph graph) {
        java.util.Map<GlobalStation, TrackEdge> out = new java.util.HashMap<>();
        for (TrackNodeLocation location : graph.getNodes()) {
            TrackNode node = graph.locateNode(location);
            Map<TrackNode, TrackEdge> connections = node == null ? null : graph.getConnectionsFrom(node);
            if (connections == null) {
                continue;
            }
            for (TrackEdge edge : connections.values()) {
                if (edge == null) {
                    continue;
                }
                for (TrackEdgePoint point : edge.getEdgeData().getPoints()) {
                    if (point instanceof GlobalStation station) {
                        out.putIfAbsent(station, edge);
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ trains
    private static String buildTrains(ResourceKey<Level> here) {
        StringBuilder out = new StringBuilder(512).append("\"trains\":[");
        boolean first = true;
        for (Train train : Create.RAILWAYS.trains.values()) {
            if (train.carriages.isEmpty() || train.graph == null) {
                continue;
            }
            Vec3 at = leadPosition(train);
            if (at == null) {
                continue;
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            boolean busy = train.runtime.getSchedule() != null && !train.runtime.completed;
            out.append("{\"name\":\"").append(escape(train.name.getString())).append('"')
                    .append(",\"x\":").append(round(at.x))
                    .append(",\"y\":").append(round(at.y))
                    .append(",\"z\":").append(round(at.z))
                    .append(",\"busy\":").append(busy)
                    .append(",\"cars\":").append(train.carriages.size()).append('}');
        }
        return out.append(']').toString();
    }

    private static boolean appendEdge(StringBuilder out, TrackGraph graph, TrackEdge edge, boolean first) {
        List<Vec3> points = new ArrayList<>();
        if (edge.isTurn()) {
            for (int i = 0; i <= CURVE_SAMPLES; i++) {
                points.add(edge.getPosition(graph, (double) i / CURVE_SAMPLES));
            }
        } else {
            points.add(edge.node1.getLocation().getLocation());
            points.add(edge.node2.getLocation().getLocation());
        }
        if (points.size() < 2) {
            return first;
        }
        if (!first) {
            out.append(',');
        }
        out.append('[');
        for (int i = 0; i < points.size(); i++) {
            Vec3 point = points.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append(round(point.x)).append(',').append(round(point.y)).append(',').append(round(point.z));
        }
        out.append(']');
        return false;
    }

    private static Vec3 leadPosition(Train train) {
        try {
            Carriage carriage = train.carriages.get(0);
            return carriage.getLeadingPoint().getPosition(train.graph);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Both ends identify an edge; order them so either direction gives the same key. */
    private static long edgeKey(TrackEdge edge) {
        long a = edge.node1.getLocation().hashCode() & 0xFFFFFFFFL;
        long b = edge.node2.getLocation().hashCode() & 0xFFFFFFFFL;
        return Math.min(a, b) << 32 | Math.max(a, b);
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
