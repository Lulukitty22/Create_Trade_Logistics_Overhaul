package com.vrlulu.createtradelogisticsoverhaul.dispatch;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.stockTicker.PackageOrder;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import com.vrlulu.createtradelogisticsoverhaul.logistics.LogisticsTerminalBlockEntity;
import com.vrlulu.createtradelogisticsoverhaul.logistics.TerminalRegistry;
import com.vrlulu.createtradelogisticsoverhaul.logistics.TerminalSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the dispatcher by itself: watches what is waiting, waits for a terminal's batch or deadline,
 * then sends a train. Also keeps supply rules topped up.
 *
 * <p>Off by default, twice over: a global switch (/ctlo auto on) and a per-terminal "auto dispatch"
 * setting. Nothing touches a railway until both are on.
 */
@EventBusSubscriber(modid = CreateTradeLogisticsOverhaul.ID)
public final class DispatchService {
    private static final int DISPATCH_INTERVAL_TICKS = 100;      // 5s
    private static final int ESCROW_INTERVAL_TICKS = 100;        // 5s
    private static final int SUPPLY_INTERVAL_TICKS = 600;        // 30s

    /**
     * On by default, and saved with the world: a switch you have to set again after every restart is
     * a switch that silently stops working. Terminals still each need their own "auto dispatch"
     * setting, so this on its own moves nothing.
     */
    private static boolean enabled = true;
    private static boolean loaded;
    private static int ticks;
    /** When each (station -> address) group was first seen waiting, for the deadline trigger. */
    private static final Map<String, Long> waitingSince = new HashMap<>();

    private DispatchService() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        waitingSince.clear();
        Settings.save(value);
    }

    /** The saved switch, kept with the world so it survives a restart. */
    public static class Settings extends net.minecraft.world.level.saveddata.SavedData {
        private static final String FILE = "createtradelogisticsoverhaul_dispatch";
        private static net.minecraft.server.MinecraftServer server;
        private boolean autoDispatch = true;

        public static void load(net.minecraft.server.MinecraftServer minecraftServer) {
            server = minecraftServer;
            enabled = of(minecraftServer).autoDispatch;
            loaded = true;
        }

        static void save(boolean value) {
            if (server == null) {
                return;
            }
            Settings settings = of(server);
            settings.autoDispatch = value;
            settings.setDirty();
        }

        private static Settings of(net.minecraft.server.MinecraftServer minecraftServer) {
            return minecraftServer.overworld().getDataStorage().computeIfAbsent(
                    new net.minecraft.world.level.saveddata.SavedData.Factory<>(Settings::new, Settings::read), FILE);
        }

        private static Settings read(net.minecraft.nbt.CompoundTag tag,
                                     net.minecraft.core.HolderLookup.Provider registries) {
            Settings settings = new Settings();
            settings.autoDispatch = !tag.contains("AutoDispatch") || tag.getBoolean("AutoDispatch");
            return settings;
        }

        @Override
        public net.minecraft.nbt.CompoundTag save(net.minecraft.nbt.CompoundTag tag,
                                                  net.minecraft.core.HolderLookup.Provider registries) {
            tag.putBoolean("AutoDispatch", autoDispatch);
            return tag;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ticks++;
        MinecraftServer server = event.getServer();
        if (!loaded) {
            Settings.load(server);
        }
        // Held payments are settled whether or not automatic dispatch is on: someone's money is
        // waiting on a delivery either way.
        if (ticks % ESCROW_INTERVAL_TICKS == 0) {
            try {
                com.vrlulu.createtradelogisticsoverhaul.logistics.Escrow.of(server).settle(
                        (address, item) -> TerminalRegistry.stockAtAddress(server, address, item));
            } catch (Throwable t) {
                CreateTradeLogisticsOverhaul.LOG.error("Settling held payments failed", t);
            }
        }
        if (!enabled) {
            return;
        }
        if (ticks % DISPATCH_INTERVAL_TICKS == 0) {
            try {
                dispatchReady(server);
            } catch (Throwable t) {
                CreateTradeLogisticsOverhaul.LOG.error("Auto dispatch failed", t);
            }
        }
        if (ticks % SUPPLY_INTERVAL_TICKS == 0) {
            try {
                runSupplyRules(server);
            } catch (Throwable t) {
                CreateTradeLogisticsOverhaul.LOG.error("Supply rules failed", t);
            }
        }
    }

    /** Sends trains for any waiting group whose origin terminal says it's time. */
    private static void dispatchReady(MinecraftServer server) {
        // Goods already loaded onto a parked train go at once: the batch and deadline settings are
        // about how long to let packages pile up at a postbox, and these are long past that.
        for (Dispatcher.Plan plan : Dispatcher.plan(server)) {
            if (plan.alreadyAboard() && plan.isPossible()) {
                Dispatcher.assign(plan, server.registryAccess());
            }
        }
        List<Dispatcher.Waiting> waiting = Dispatcher.waitingPackages(server);
        long now = System.currentTimeMillis();
        Map<String, Dispatcher.Waiting> current = new HashMap<>();
        for (Dispatcher.Waiting w : waiting) {
            current.put(w.atStation() + "->" + w.toAddress(), w);
        }
        waitingSince.keySet().removeIf(key -> !current.containsKey(key));

        for (Map.Entry<String, Dispatcher.Waiting> entry : current.entrySet()) {
            Dispatcher.Waiting w = entry.getValue();
            waitingSince.putIfAbsent(entry.getKey(), now);
            LogisticsTerminalBlockEntity origin = terminalForAddress(server, w.atStation());
            if (origin == null || !origin.settings().autoDispatch) {
                continue;               // this building hasn't opted in
            }
            TerminalSettings settings = origin.settings();
            long waitedSeconds = (now - waitingSince.get(entry.getKey())) / 1000;
            boolean batchReady = w.count() >= settings.batchSize;
            boolean deadlineHit = settings.maxWaitSeconds > 0 && waitedSeconds >= settings.maxWaitSeconds;
            if (!batchReady && !deadlineHit) {
                continue;
            }
            for (Dispatcher.Plan plan : Dispatcher.plan(server)) {
                if (!plan.isPossible() || !plan.pickupStation().equals(w.atStation())
                        || !plan.address().equals(w.toAddress())) {
                    continue;
                }
                if (Dispatcher.assign(plan, server.registryAccess())) {
                    waitingSince.remove(entry.getKey());
                }
                break;
            }
        }
    }

    /** "Keep N of X here": orders the shortfall from the source network. */
    private static void runSupplyRules(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (LogisticsTerminalBlockEntity terminal : TerminalRegistry.forLevel(level).loaded(level)) {
                TerminalSettings settings = terminal.settings();
                if (settings.supplyRules.isEmpty() || settings.address.isBlank()) {
                    continue;
                }
                for (TerminalSettings.SupplyRule rule : settings.supplyRules) {
                    Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(rule.item()));
                    if (item == null || rule.sourceNetwork() == null || rule.keepStocked() <= 0) {
                        continue;
                    }
                    ItemStack stack = new ItemStack(item);
                    int have = terminal.countOf(stack);
                    int missing = rule.keepStocked() - have;
                    if (missing <= 0) {
                        continue;
                    }
                    InventorySummary source = LogisticsManager.getSummaryOfNetwork(rule.sourceNetwork(), false);
                    int available = source == null ? 0 : source.getCountOf(stack);
                    int amount = Math.min(missing, available);
                    if (amount <= 0) {
                        continue;
                    }
                    BigItemStack big = LogisticsTerminalBlockEntity.bigStack(stack, amount);
                    PackageOrderWithCrafts order =
                            new PackageOrderWithCrafts(new PackageOrder(List.of(big)), List.of());
                    boolean sent = LogisticsManager.broadcastPackageRequest(rule.sourceNetwork(),
                            LogisticallyLinkedBehaviour.RequestType.RESTOCK, order, null, settings.address);
                    if (sent) {
                        CreateTradeLogisticsOverhaul.LOG.info("Supply rule: {} x {} -> {} (had {} of {})",
                                amount, rule.item(), settings.address, have, rule.keepStocked());
                    }
                }
            }
        }
    }

    /** The terminal whose address matches a station name (the user names stations after addresses). */
    private static LogisticsTerminalBlockEntity terminalForAddress(MinecraftServer server, String address) {
        for (ServerLevel level : server.getAllLevels()) {
            for (LogisticsTerminalBlockEntity terminal : TerminalRegistry.forLevel(level).loaded(level)) {
                if (address.equals(terminal.address())) {
                    return terminal;
                }
            }
        }
        return null;
    }
}
