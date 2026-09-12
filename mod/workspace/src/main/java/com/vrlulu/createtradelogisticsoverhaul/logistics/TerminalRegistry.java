package com.vrlulu.createtradelogisticsoverhaul.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where the terminals are, per dimension, saved with the world.
 *
 * <p>Terminals add themselves when they load and drop out when broken, so the server never has to
 * search the world for them.
 */
public class TerminalRegistry extends SavedData {
    private static final String FILE = "createtradelogisticsoverhaul_terminals";

    private final Set<Long> positions = new LinkedHashSet<>();

    public static TerminalRegistry forLevel(Level level) {
        ServerLevel server = (ServerLevel) level;
        return server.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TerminalRegistry::new, TerminalRegistry::load), FILE);
    }

    public void add(BlockPos pos) {
        if (positions.add(pos.asLong())) {
            setDirty();
        }
    }

    public void remove(BlockPos pos) {
        if (positions.remove(pos.asLong())) {
            setDirty();
        }
    }

    /** Terminals in this dimension whose chunk is currently loaded. */
    public List<LogisticsTerminalBlockEntity> loaded(ServerLevel level) {
        List<LogisticsTerminalBlockEntity> out = new ArrayList<>();
        List<Long> stale = new ArrayList<>();
        for (long packed : positions) {
            BlockPos pos = BlockPos.of(packed);
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (level.getBlockEntity(pos) instanceof LogisticsTerminalBlockEntity terminal) {
                out.add(terminal);
            } else {
                stale.add(packed);
            }
        }
        if (!stale.isEmpty()) {
            stale.forEach(positions::remove);
            setDirty();
        }
        return out;
    }

    /** How many of an item the terminal at an address holds, or -1 if no loaded terminal has it. */
    public static int stockAtAddress(net.minecraft.server.MinecraftServer server, String address, String itemId) {
        net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.ResourceLocation.parse(itemId));
        if (server == null || item == null || address == null || address.isBlank()) {
            return -1;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (LogisticsTerminalBlockEntity terminal : forLevel(level).loaded(level)) {
                if (address.equals(terminal.address())) {
                    return terminal.countOf(new net.minecraft.world.item.ItemStack(item));
                }
            }
        }
        return -1;
    }

    public int size() {
        return positions.size();
    }

    private static TerminalRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        TerminalRegistry registry = new TerminalRegistry();
        ListTag list = tag.getList("Positions", net.minecraft.nbt.Tag.TAG_LONG);
        for (int i = 0; i < list.size(); i++) {
            registry.positions.add(((net.minecraft.nbt.LongTag) list.get(i)).getAsLong());
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (long packed : positions) {
            list.add(net.minecraft.nbt.LongTag.valueOf(packed));
        }
        tag.put("Positions", list);
        return tag;
    }
}
