package com.vrlulu.createtradelogisticsoverhaul.dispatch;

import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.postbox.PostboxBlockEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.destination.DeliverPackagesInstruction;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.schedule.condition.IdleCargoCondition;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.condition.ScheduledDelay;
import com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition;
import com.simibubi.create.content.trains.schedule.destination.FetchPackagesInstruction;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;
import com.simibubi.create.content.trains.station.GlobalPackagePort;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plans deliveries for waiting packages: what to pick up, where to take it, and which train to ask.
 *
 * <p>This is the heart of the mod (see DESIGN.md). Create already packs and addresses packages; it
 * just has no way to task a vehicle, so vans end up on fixed round-robin loops. Here we read what's
 * actually waiting, work out the stops, and build a schedule for a free train.
 *
 * <p>Nothing is assigned unless asked: {@link #plan} only looks, so it is safe on a live world.
 */
public final class Dispatcher {
    /** A station whose name is an address plus this suffix is the reverse point for it. */
    public static final String REVERSE_SUFFIX = " REV";

    private Dispatcher() {
    }

    /** One package (or group) waiting at a station, and where it wants to go. */
    public record Waiting(String atStation, String toAddress, int count) {
    }

    /** A planned run for one courier. */
    public record Plan(String trainName, UUID trainId, String pickupStation, String dropStation,
                       String address, int packages, List<String> stops, String problem,
                       boolean alreadyAboard) {
        public Plan(String trainName, UUID trainId, String pickupStation, String dropStation,
                    String address, int packages, List<String> stops, String problem) {
            this(trainName, trainId, pickupStation, dropStation, address, packages, stops, problem, false);
        }

        public boolean isPossible() {
            return problem == null;
        }
    }

    /**
     * Every package currently sitting in a station's postbox.
     *
     * <p>Read the way Create's own fetch instruction reads it: a postbox in a loaded chunk keeps its
     * packages in the block entity, and {@code offlineBuffer} only holds them while the chunk is
     * unloaded. Reading the buffer alone meant the dispatcher went blind whenever anyone was
     * standing near the station - which is most of the time someone is testing it.
     */
    public static List<Waiting> waitingPackages(net.minecraft.server.MinecraftServer server) {
        List<Waiting> out = new ArrayList<>();
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                net.minecraft.server.level.ServerLevel level =
                        server == null ? null : server.getLevel(station.blockEntityDimension);
                for (Map.Entry<BlockPos, GlobalPackagePort> entry : station.connectedPorts.entrySet()) {
                    GlobalPackagePort port = entry.getValue();
                    BlockPos pos = entry.getKey();
                    net.neoforged.neoforge.items.IItemHandlerModifiable inventory = port.offlineBuffer;
                    if (level != null && level.isLoaded(pos)
                            && level.getBlockEntity(pos) instanceof PostboxBlockEntity postbox) {
                        inventory = postbox.inventory;
                    }
                    Map<String, Integer> byAddress = new LinkedHashMap<>();
                    countPackages(inventory, byAddress);
                    byAddress.forEach((address, count) -> out.add(new Waiting(station.name, address, count)));
                }
            }
        }
        return out;
    }

    private static void countPackages(net.neoforged.neoforge.items.IItemHandlerModifiable inventory,
                                      Map<String, Integer> byAddress) {
        if (inventory == null) {
            return;
        }
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || !PackageItem.isPackage(stack)) {
                continue;
            }
            String address = PackageItem.getAddress(stack);
            if (address != null && !address.isBlank()) {
                byAddress.merge(address, 1, Integer::sum);
            }
        }
    }

    /**
     * Works out a run for each waiting package: which train, which stops, and whether it's possible.
     * Looks only; call {@link #assign} to actually send a train.
     */
    public static List<Plan> plan() {
        return plan(null);
    }

    /**
     * Plans the runs. With a server, legs that leave a railway are routed to that railway's
     * interchange terminal (a post office declaring its other side), so cross-city freight moves
     * one leg at a time instead of being refused.
     */
    public static List<Plan> plan(net.minecraft.server.MinecraftServer server) {
        List<Plan> plans = new ArrayList<>();
        for (Waiting waiting : waitingPackages(server)) {
            GlobalStation pickup = stationNamed(waiting.atStation());
            GlobalStation drop = stationServing(waiting.toAddress());
            if (pickup == null) {
                continue;
            }
            if (drop == null) {
                plans.add(new Plan("-", null, waiting.atStation(), "?", waiting.toAddress(), waiting.count(),
                        List.of(), "no station serves " + waiting.toAddress()));
                continue;
            }
            TrackGraph graph = graphOf(pickup);
            if (graph == null) {
                continue;
            }
            if (graphOf(drop) != graph) {
                // Different railways: hand the package to the interchange on this one (the post
                // office), and let its own leg carry on from there.
                GlobalStation handoff = interchangeOn(graph, server);
                if (handoff == null) {
                    plans.add(new Plan("-", null, pickup.name, drop.name, waiting.toAddress(), waiting.count(),
                            List.of(), "different track networks and no interchange terminal on this one"));
                    continue;
                }
                if (handoff.name.equals(pickup.name)) {
                    plans.add(new Plan("-", null, pickup.name, drop.name, waiting.toAddress(), waiting.count(),
                            List.of(), "waiting at the interchange: the hand-off is yours to build"));
                    continue;
                }
                drop = handoff;         // this leg only goes as far as the interchange
            }
            Train courier = freeTrainOn(graph);
            if (courier == null) {
                plans.add(new Plan("-", null, pickup.name, drop.name, waiting.toAddress(), waiting.count(),
                        List.of(), "no idle train on that track network"));
                continue;
            }
            // Create's package instructions quietly refuse to run without one, so the train would
            // just sit there. Better to say why than to send it and watch nothing happen.
            if (!courier.hasForwardConductor() && !courier.hasBackwardConductor()) {
                plans.add(new Plan(courier.name.getString(), null, pickup.name, drop.name,
                        waiting.toAddress(), waiting.count(), List.of(),
                        "train '" + courier.name.getString() + "' has no conductor"));
                continue;
            }
            List<String> stops = stopsFor(pickup, drop, waiting.toAddress());
            plans.add(new Plan(courier.name.getString(), courier.id, pickup.name, drop.name,
                    waiting.toAddress(), waiting.count(), stops, null));
        }
        plans.addAll(planLoadedTrains());
        return plans;
    }

    /**
     * Runs for packages that are already on board an idle train.
     *
     * <p>A postbox hands its packages to whatever train is standing at the station, so a van parked
     * at its own depot is loaded the moment one is made. Nothing is then waiting in any postbox, and
     * without this the train would sit there for good, holding goods nobody had told it to deliver.
     */
    private static List<Plan> planLoadedTrains() {
        List<Plan> plans = new ArrayList<>();
        for (Train train : Create.RAILWAYS.trains.values()) {
            if (train.derailed || train.invalid || isBusy(train)) {
                continue;
            }
            for (Map.Entry<String, Integer> entry : packagesAboard(train).entrySet()) {
                String address = entry.getKey();
                GlobalStation drop = stationServing(address);
                if (drop == null) {
                    plans.add(new Plan(train.name.getString(), null, "aboard", "?", address,
                            entry.getValue(), List.of(), "no station serves " + address, true));
                    continue;
                }
                if (!train.hasForwardConductor() && !train.hasBackwardConductor()) {
                    plans.add(new Plan(train.name.getString(), null, "aboard", drop.name, address,
                            entry.getValue(), List.of(),
                            "train '" + train.name.getString() + "' has no conductor", true));
                    continue;
                }
                List<String> stops = new ArrayList<>();
                GlobalStation dropReverse = reversePointFor(drop, address);
                if (dropReverse != null) {
                    stops.add(dropReverse.name);
                }
                stops.add(drop.name);
                stops.add("deliver packages");
                plans.add(new Plan(train.name.getString(), train.id, "aboard", drop.name, address,
                        entry.getValue(), stops, null, true));
            }
        }
        return plans;
    }

    /** Packages riding in a train's carriages, grouped by the address they are bound for. */
    private static Map<String, Integer> packagesAboard(Train train) {
        Map<String, Integer> byAddress = new LinkedHashMap<>();
        for (com.simibubi.create.content.trains.entity.Carriage carriage : train.carriages) {
            net.neoforged.neoforge.items.IItemHandlerModifiable inventory = carriage.storage.getAllItems();
            if (inventory == null) {
                continue;
            }
            countPackages(inventory, byAddress);
        }
        return byAddress;
    }

    private static String showPos(BlockPos pos) {
        return pos == null ? "" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** One station as the diagnostics see it. */
    public record StationInfo(String name, String graph, boolean hasReversePoint,
                              List<String> portAddresses, int packagesWaiting,
                              String pos, List<String> portPositions) {
    }

    /** Every station Create knows about, for working out why a run is planned the way it is. */
    public static List<StationInfo> stations(net.minecraft.server.MinecraftServer server) {
        Map<String, Integer> waitingByStation = new LinkedHashMap<>();
        for (Waiting waiting : waitingPackages(server)) {
            waitingByStation.merge(waiting.atStation(), waiting.count(), Integer::sum);
        }
        List<StationInfo> out = new ArrayList<>();
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                List<String> addresses = new ArrayList<>();
                List<String> portPositions = new ArrayList<>();
                for (Map.Entry<BlockPos, GlobalPackagePort> port : station.connectedPorts.entrySet()) {
                    addresses.add(port.getValue().address == null ? "" : port.getValue().address);
                    portPositions.add(showPos(port.getKey()));
                }
                out.add(new StationInfo(station.name, graph.id.toString().substring(0, 8),
                        reversePointFor(station, addressOf(station)) != null, addresses,
                        waitingByStation.getOrDefault(station.name, 0),
                        showPos(station.blockEntityPos), portPositions));
            }
        }
        return out;
    }

    /**
     * The reverse point a station has to be entered through, or null if it can be entered directly.
     *
     * <p>Named after the <em>address</em> first - that is the convention in use: a building's
     * station may be called anything, while its reverse point is named after the address it serves.
     * Falls back to the station's own name for layouts that pair them that way instead.
     */
    private static GlobalStation reversePointFor(GlobalStation station, String address) {
        if (address != null && !address.isBlank()) {
            GlobalStation byAddress = stationNamed(address + REVERSE_SUFFIX);
            if (byAddress != null) {
                return byAddress;
            }
        }
        return stationNamed(station.name + REVERSE_SUFFIX);
    }

    /** The address a station's postbox answers to, if it has one. */
    private static String addressOf(GlobalStation station) {
        for (GlobalPackagePort port : station.connectedPorts.values()) {
            if (port.address != null && !port.address.isBlank()) {
                return port.address;
            }
        }
        return null;
    }

    /** The stop list for a run, with reverse points inserted where they exist. */
    private static List<String> stopsFor(GlobalStation pickup, GlobalStation drop, String address) {
        List<String> stops = new ArrayList<>();
        GlobalStation pickupReverse = reversePointFor(pickup, addressOf(pickup));
        if (pickupReverse != null) {
            stops.add(pickupReverse.name);
        }
        stops.add(pickup.name);
        stops.add("fetch packages " + address);
        GlobalStation dropReverse = reversePointFor(drop, address);
        if (dropReverse != null) {
            stops.add(dropReverse.name);
        }
        stops.add(drop.name);
        stops.add("deliver packages");
        return stops;
    }

    /** Builds the schedule a plan describes and hands it to the train. */
    public static boolean assign(Plan plan, net.minecraft.core.HolderLookup.Provider registries) {
        if (!plan.isPossible() || plan.trainId() == null) {
            return false;
        }
        Train train = Create.RAILWAYS.trains.get(plan.trainId());
        if (train == null) {
            return false;
        }
        Schedule schedule = new Schedule();
        schedule.cyclic = false;
        GlobalStation drop = stationNamed(plan.dropStation());
        if (drop == null) {
            return false;
        }
        GlobalStation pickup = plan.alreadyAboard() ? null : stationNamed(plan.pickupStation());
        if (!plan.alreadyAboard() && pickup == null) {
            return false;
        }
        // Name both stations explicitly. Create's fetch and deliver instructions find a station by
        // themselves - any station holding a matching package - so on their own they undo the point
        // of dispatching: the train would go wherever Create fancied rather than where it was sent.
        // This also makes the schedule match the plan the map showed, stop for stop.
        if (pickup != null) {
            GlobalStation pickupReverse = reversePointFor(pickup, addressOf(pickup));
            if (pickupReverse != null) {
                schedule.entries.add(entry(destination(pickupReverse.name, registries), delay(registries, 1)));
            }
            schedule.entries.add(entry(destination(pickup.name, registries), cargoIdle(registries, 3)));
            schedule.entries.add(entry(fetchPackages(plan.address(), registries), cargoIdle(registries, 3)));
        }
        GlobalStation dropReverse = reversePointFor(drop, plan.address());
        if (dropReverse != null) {
            schedule.entries.add(entry(destination(dropReverse.name, registries), delay(registries, 1)));
        }
        schedule.entries.add(entry(destination(drop.name, registries), cargoIdle(registries, 3)));
        schedule.entries.add(entry(deliverPackages(), cargoIdle(registries, 3)));
        train.runtime.setSchedule(schedule, true);
        CreateTradeLogisticsOverhaul.LOG.info("Dispatched {} : {} -> {} for {}",
                plan.trainName(), plan.pickupStation(), plan.dropStation(), plan.address());
        return true;
    }

    /**
     * Wraps an instruction as a stop the train will actually leave again.
     *
     * <p>An entry with no wait conditions is a trap: ScheduleRuntime advances the schedule from
     * inside the loop over those conditions, so with an empty list it never advances and the train
     * sits at that stop for good. Every instruction here reports supportsConditions() == true, so
     * every one of them needs a condition.
     */
    private static ScheduleEntry entry(ScheduleInstruction instruction, ScheduleWaitCondition condition) {
        List<ScheduleWaitCondition> column = new ArrayList<>();
        column.add(condition);
        List<List<ScheduleWaitCondition>> conditions = new ArrayList<>();
        conditions.add(column);
        return new ScheduleEntry(instruction, conditions);
    }

    /** Leave once nothing has been loaded or unloaded for a few seconds. */
    private static ScheduleWaitCondition cargoIdle(net.minecraft.core.HolderLookup.Provider registries, int seconds) {
        return timed(new IdleCargoCondition(), registries, seconds);
    }

    /** Leave after a fixed pause - for reverse points, where nothing is being loaded. */
    private static ScheduleWaitCondition delay(net.minecraft.core.HolderLookup.Provider registries, int seconds) {
        return timed(new ScheduledDelay(), registries, seconds);
    }

    private static ScheduleWaitCondition timed(TimedWaitCondition condition,
                                               net.minecraft.core.HolderLookup.Provider registries, int seconds) {
        CompoundTag data = new CompoundTag();
        data.putInt("Value", seconds);
        data.putInt("TimeUnit", TimedWaitCondition.TimeUnit.SECONDS.ordinal());
        condition.setData(registries, data);
        return condition;
    }

    private static ScheduleInstruction destination(String stationName, net.minecraft.core.HolderLookup.Provider registries) {
        DestinationInstruction instruction = new DestinationInstruction();
        CompoundTag data = new CompoundTag();
        data.putString("Text", stationName);
        instruction.setData(registries, data);
        return instruction;
    }

    private static ScheduleInstruction fetchPackages(String addressFilter, net.minecraft.core.HolderLookup.Provider registries) {
        FetchPackagesInstruction instruction = new FetchPackagesInstruction();
        CompoundTag data = new CompoundTag();
        data.putString("Text", addressFilter);
        instruction.setData(registries, data);
        return instruction;
    }

    private static ScheduleInstruction deliverPackages() {
        return new DeliverPackagesInstruction();
    }

    /** The station of an interchange terminal (role POST_OFFICE with an other side) on this railway. */
    private static GlobalStation interchangeOn(TrackGraph graph, net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return null;
        }
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (com.vrlulu.createtradelogisticsoverhaul.logistics.LogisticsTerminalBlockEntity terminal
                    : com.vrlulu.createtradelogisticsoverhaul.logistics.TerminalRegistry.forLevel(level).loaded(level)) {
                var settings = terminal.settings();
                if (settings.role != com.vrlulu.createtradelogisticsoverhaul.logistics.TerminalSettings.Role.POST_OFFICE
                        || settings.address.isBlank()) {
                    continue;
                }
                GlobalStation station = stationNamed(settings.address);
                if (station != null && graphOf(station) == graph) {
                    return station;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ lookups
    public static GlobalStation stationNamed(String name) {
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                if (station.name.equals(name)) {
                    return station;
                }
            }
        }
        return null;
    }

    /** The station whose postbox accepts this address (exact first, then wildcards like "PD-C01-B*"). */
    public static GlobalStation stationServing(String address) {
        GlobalStation wildcard = null;
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                for (GlobalPackagePort port : station.connectedPorts.values()) {
                    String filter = port.address;
                    if (filter == null || filter.isBlank()) {
                        continue;
                    }
                    if (filter.equals(address)) {
                        return station;
                    }
                    if (filter.endsWith("*") && address.startsWith(filter.substring(0, filter.length() - 1))) {
                        wildcard = station;
                    }
                }
            }
        }
        return wildcard;
    }

    private static TrackGraph graphOf(GlobalStation station) {
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            if (graph.getPoint(EdgePointType.STATION, station.id) != null) {
                return graph;
            }
        }
        return null;
    }

    /** A train is busy while it still has a schedule it has not finished. */
    private static boolean isBusy(Train train) {
        return train.runtime.getSchedule() != null && !train.runtime.completed;
    }

    /** A train on that network with nothing to do. */
    private static Train freeTrainOn(TrackGraph graph) {
        for (Train train : Create.RAILWAYS.trains.values()) {
            if (train.graph != graph || train.derailed || train.invalid) {
                continue;
            }
            if (!isBusy(train)) {
                return train;
            }
        }
        return null;
    }
}
