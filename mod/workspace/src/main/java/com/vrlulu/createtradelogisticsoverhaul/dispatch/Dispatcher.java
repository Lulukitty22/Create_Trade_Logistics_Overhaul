package com.vrlulu.createtradelogisticsoverhaul.dispatch;

import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.destination.DeliverPackagesInstruction;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.schedule.destination.FetchPackagesInstruction;
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
                       String address, int packages, List<String> stops, String problem) {
        public boolean isPossible() {
            return problem == null;
        }
    }

    /** Every package currently sitting in a station's postbox, loaded chunks or not. */
    public static List<Waiting> waitingPackages() {
        List<Waiting> out = new ArrayList<>();
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                for (Map.Entry<BlockPos, GlobalPackagePort> entry : station.connectedPorts.entrySet()) {
                    GlobalPackagePort port = entry.getValue();
                    Map<String, Integer> byAddress = new LinkedHashMap<>();
                    countPackages(port.offlineBuffer, byAddress);
                    byAddress.forEach((address, count) -> out.add(new Waiting(station.name, address, count)));
                }
            }
        }
        return out;
    }

    private static void countPackages(ItemStackHandler inventory, Map<String, Integer> byAddress) {
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
        List<Plan> plans = new ArrayList<>();
        for (Waiting waiting : waitingPackages()) {
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
            if (graph == null || graphOf(drop) != graph) {
                plans.add(new Plan("-", null, pickup.name, drop.name, waiting.toAddress(), waiting.count(),
                        List.of(), "different track networks: needs an interchange (not built yet)"));
                continue;
            }
            Train courier = freeTrainOn(graph);
            if (courier == null) {
                plans.add(new Plan("-", null, pickup.name, drop.name, waiting.toAddress(), waiting.count(),
                        List.of(), "no idle train on that track network"));
                continue;
            }
            List<String> stops = stopsFor(pickup, drop, waiting.toAddress());
            plans.add(new Plan(courier.name.getString(), courier.id, pickup.name, drop.name,
                    waiting.toAddress(), waiting.count(), stops, null));
        }
        return plans;
    }

    /** The stop list for a run, with reverse points inserted where they exist. */
    private static List<String> stopsFor(GlobalStation pickup, GlobalStation drop, String address) {
        List<String> stops = new ArrayList<>();
        if (stationNamed(pickup.name + REVERSE_SUFFIX) != null) {
            stops.add(pickup.name + REVERSE_SUFFIX);
        }
        stops.add(pickup.name);
        stops.add("fetch packages " + address);
        if (stationNamed(drop.name + REVERSE_SUFFIX) != null) {
            stops.add(drop.name + REVERSE_SUFFIX);
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
        GlobalStation pickup = stationNamed(plan.pickupStation());
        GlobalStation drop = stationNamed(plan.dropStation());
        if (pickup == null || drop == null) {
            return false;
        }
        if (stationNamed(pickup.name + REVERSE_SUFFIX) != null) {
            schedule.entries.add(destination(pickup.name + REVERSE_SUFFIX, registries));
        }
        schedule.entries.add(fetchPackages(plan.address(), registries));
        if (stationNamed(drop.name + REVERSE_SUFFIX) != null) {
            schedule.entries.add(destination(drop.name + REVERSE_SUFFIX, registries));
        }
        schedule.entries.add(deliverPackages());
        train.runtime.setSchedule(schedule, true);
        CreateTradeLogisticsOverhaul.LOG.info("Dispatched {} : {} -> {} for {}",
                plan.trainName(), plan.pickupStation(), plan.dropStation(), plan.address());
        return true;
    }

    private static ScheduleEntry destination(String stationName, net.minecraft.core.HolderLookup.Provider registries) {
        DestinationInstruction instruction = new DestinationInstruction();
        CompoundTag data = new CompoundTag();
        data.putString("Text", stationName);
        instruction.setData(registries, data);
        return new ScheduleEntry(instruction, new ArrayList<>());
    }

    private static ScheduleEntry fetchPackages(String addressFilter, net.minecraft.core.HolderLookup.Provider registries) {
        FetchPackagesInstruction instruction = new FetchPackagesInstruction();
        CompoundTag data = new CompoundTag();
        data.putString("Text", addressFilter);
        instruction.setData(registries, data);
        return new ScheduleEntry(instruction, new ArrayList<>());
    }

    private static ScheduleEntry deliverPackages() {
        return new ScheduleEntry(new DeliverPackagesInstruction(), new ArrayList<>());
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

    /** A train on that network with nothing to do. */
    private static Train freeTrainOn(TrackGraph graph) {
        for (Train train : Create.RAILWAYS.trains.values()) {
            if (train.graph != graph || train.derailed || train.invalid) {
                continue;
            }
            boolean busy = train.runtime.getSchedule() != null && !train.runtime.completed;
            if (!busy) {
                return train;
            }
        }
        return null;
    }
}
