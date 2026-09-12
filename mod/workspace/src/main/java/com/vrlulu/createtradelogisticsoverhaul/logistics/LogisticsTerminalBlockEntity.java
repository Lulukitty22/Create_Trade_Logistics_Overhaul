package com.vrlulu.createtradelogisticsoverhaul.logistics;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.stockTicker.PackageOrder;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.content.logistics.stockTicker.StockCheckingBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.UUID;

/**
 * A Logistics Terminal: one building's window onto the logistics map.
 *
 * <p>It extends Create's own {@link StockCheckingBlockEntity} - the base the Redstone Requester and
 * Stock Ticker use - so it is a real member of a logistics network rather than something holding a
 * network's id from the outside. That is what makes Create's linked item tune it, makes it glow
 * with the rest of the network when a tuned item is held, and keeps the stock readings honest.
 *
 * <p>On top of that it holds the place's name, package address and settings, and it is the only way
 * the map can see or order anything (see DESIGN.md, "Not omnipotent").
 */
public class LogisticsTerminalBlockEntity extends StockCheckingBlockEntity {
    private final TerminalSettings settings = new TerminalSettings();
    private UUID ownerId;
    private String ownerName = "";

    public LogisticsTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.TERMINAL_BE.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);      // the logistically-linked behaviour
    }

    public TerminalSettings settings() {
        return settings;
    }

    public UUID network() {
        return behaviour == null ? null : behaviour.freqId;
    }

    /**
     * Create gives every linked block a fresh network of its own the moment it exists, so "has an
     * id" means nothing. What matters is whether anything else is on that network, which is also
     * what decides whether there is any stock to read.
     */
    public boolean isTuned() {
        return linkCount() > 1;
    }

    /** Blocks currently loaded on this terminal's network, this one included. */
    public int linkCount() {
        if (behaviour == null || behaviour.freqId == null) {
            return 0;
        }
        var present = LogisticallyLinkedBehaviour.getAllPresent(behaviour.freqId, false);
        return present == null ? 0 : present.size();
    }

    public String terminalName() {
        return settings.name.isBlank() ? "Unnamed terminal" : settings.name;
    }

    public String address() {
        return settings.address;
    }

    public String ownerName() {
        return ownerName;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public TerminalSettings.Access accessFor(Player player) {
        return settings.accessFor(player == null ? null : player.getUUID(), ownerId);
    }

    public void setOwner(Player player) {
        ownerId = player.getUUID();
        ownerName = player.getGameProfile().getName();
        notifyUpdate();
    }

    /** Joins a network. Create's behaviour handles membership and saving from here on. */
    public void tuneTo(UUID networkId) {
        if (behaviour == null || networkId == null) {
            return;
        }
        LogisticallyLinkedBehaviour.remove(behaviour);
        behaviour.freqId = networkId;
        LogisticallyLinkedBehaviour.keepAlive(behaviour);
        notifyUpdate();
    }

    /** Everything the network can currently see. Only loaded links contribute (Create's rule). */
    public List<BigItemStack> stock() {
        if (network() == null) {
            return List.of();
        }
        InventorySummary summary = getRecentSummary();
        return summary == null ? List.of() : summary.getStacksByCount();
    }

    public int countOf(ItemStack item) {
        if (network() == null) {
            return 0;
        }
        InventorySummary summary = getRecentSummary();
        return summary == null ? 0 : summary.getCountOf(item);
    }

    /**
     * Places a package order on this terminal's network, addressed wherever the caller asks.
     * Create's packagers do the packing and its transport does the carrying.
     */
    public boolean order(List<BigItemStack> items, String deliverTo) {
        if (network() == null || items.isEmpty() || deliverTo == null || deliverTo.isBlank()) {
            return false;
        }
        PackageOrderWithCrafts order = new PackageOrderWithCrafts(new PackageOrder(items), List.of());
        boolean sent = broadcastPackageRequest(LogisticallyLinkedBehaviour.RequestType.PLAYER,
                order, null, deliverTo);
        CreateTradeLogisticsOverhaul.LOG.info("Terminal {} ordered {} stacks to {} ({})",
                terminalName(), items.size(), deliverTo, sent ? "sent" : "no packager could fill it");
        return sent;
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (ownerId != null) {
            tag.putUUID("Owner", ownerId);
        }
        tag.putString("OwnerName", ownerName);
        tag.put("Settings", settings.save());
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        ownerId = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ownerName = tag.getString("OwnerName");
        settings.load(tag.getCompound("Settings"));
        if (tag.contains("Name")) {            // terminals saved before settings moved into their own tag
            settings.name = tag.getString("Name");
            settings.address = tag.getString("Address");
        }
        // Terminals from before this block joined the network properly kept the id themselves.
        if (behaviour != null && behaviour.freqId == null && tag.hasUUID("Network")) {
            behaviour.freqId = tag.getUUID("Network");
        }
    }

    @Override
    public void remove() {
        super.remove();
        if (level != null && !level.isClientSide) {
            TerminalRegistry.forLevel(level).remove(worldPosition);
        }
    }

    @Override
    public void initialize() {
        super.initialize();
        if (level != null && !level.isClientSide) {
            TerminalRegistry.forLevel(level).add(worldPosition);
        }
    }

    /** Convenience for the "order this much of this item" case. */
    public static BigItemStack bigStack(ItemStack stack, int count) {
        BigItemStack big = new BigItemStack(stack);
        big.count = count;
        return big;
    }
}
