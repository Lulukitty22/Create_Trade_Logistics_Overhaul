package com.vrlulu.createtradelogisticsoverhaul.terrain;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.station.GlobalPackagePort;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Create's railway, as the map draws it: the track itself, the stations on it, and where the trains
 * are right now.
 *
 * <p>Read straight from {@code Create.RAILWAYS} on the client. Create syncs its track graphs to
 * clients so it can draw the rails and the trains, which means none of this needs a packet of our
 * own - unlike stock and dispatching, which only the server knows about.
 *
 * <p>Curves are sampled rather than rebuilt: {@link TrackEdge#getPosition} walks an edge whether it
 * is straight or a bezier turn, so a handful of samples describes any piece of track.
 */
public final class RailMap {
    /** Points sampled along a curved edge. Straight edges only need their two ends. */
    private static final int CURVE_SAMPLES = 12;

    private RailMap() {
    }

    public static boolean available() {
        try {
            return Create.RAILWAYS != null && Minecraft.getInstance().level != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The railway in the dimension the player is standing in. */
    public static String toJson() {
        StringBuilder out = new StringBuilder(1 << 14).append('{');
        if (!available()) {
            return out.append("\"tracks\":[],\"stations\":[],\"trains\":[]}").toString();
        }
        ResourceKey<Level> here = Minecraft.getInstance().level.dimension();

        out.append("\"tracks\":[");
        boolean first = true;
        Set<Long> seen = new HashSet<>();
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (TrackNodeLocation location : graph.getNodes()) {
                TrackNode node = graph.locateNode(location);
                if (node == null) {
                    continue;
                }
                Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(node);
                if (connections == null) {
                    continue;
                }
                for (TrackEdge edge : connections.values()) {
                    if (edge == null || edge.node1 == null || edge.node2 == null) {
                        continue;
                    }
                    // Each edge is reachable from both of its ends; draw it once.
                    if (!seen.add(edgeKey(edge))) {
                        continue;
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

        out.append("],\"trains\":[");
        first = true;
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
        return out.append("]}").toString();
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
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
