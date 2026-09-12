package com.vrlulu.createtradelogisticsoverhaul.logistics;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.stockTicker.PackageOrder;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.UUID;

/**
 * A Logistics Terminal: one building's window onto the logistics map.
 *
 * <p>Tuned to a Create logistics network with a stock link, exactly like a Stock Ticker. It holds
 * the place's name, package address and settings, and it is the only way the map can see or order
 * anything (see DESIGN.md, "Not omnipotent").
 */
public class LogisticsTerminalBlockEntity extends BlockEntity {
    private final TerminalSettings settings = new TerminalSettings();
    private UUID network;
    private UUID ownerId;
    private String ownerName = "";

    public LogisticsTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.TERMINAL_BE.get(), pos, state);
    }

    public TerminalSettings settings() {
        return settings;
    }

    public UUID network() {
        return network;
    }

    public boolean isTuned() {
        return network != null;
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
        setChanged();
    }

    public void tuneTo(UUID networkId) {
        this.network = networkId;
        setChanged();
    }

    /** Everything the network can currently see. Only loaded stock links contribute (Create's rule). */
    public List<BigItemStack> stock() {
        if (network == null) {
            return List.of();
        }
        InventorySummary summary = LogisticsManager.getSummaryOfNetwork(network, false);
        return summary == null ? List.of() : summary.getStacksByCount();
    }

    public int countOf(ItemStack item) {
        if (network == null) {
            return 0;
        }
        InventorySummary summary = LogisticsManager.getSummaryOfNetwork(network, false);
        return summary == null ? 0 : summary.getCountOf(item);
    }

    /**
     * Places a package order on this terminal's network, addressed wherever the caller asks.
     * Create's packagers do the packing and its transport does the carrying.
     */
    public boolean order(List<BigItemStack> items, String deliverTo) {
        if (network == null || items.isEmpty() || deliverTo == null || deliverTo.isBlank()) {
            return false;
        }
        PackageOrderWithCrafts order = new PackageOrderWithCrafts(new PackageOrder(items), List.of());
        boolean sent = LogisticsManager.broadcastPackageRequest(network,
                LogisticallyLinkedBehaviour.RequestType.PLAYER, order, null, deliverTo);
        CreateTradeLogisticsOverhaul.LOG.info("Terminal {} ordered {} stacks to {} ({})",
                terminalName(), items.size(), deliverTo, sent ? "sent" : "no packager could fill it");
        return sent;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (network != null) {
            tag.putUUID("Network", network);
        }
        if (ownerId != null) {
            tag.putUUID("Owner", ownerId);
        }
        tag.putString("OwnerName", ownerName);
        tag.put("Settings", settings.save());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        network = tag.hasUUID("Network") ? tag.getUUID("Network") : null;
        ownerId = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ownerName = tag.getString("OwnerName");
        settings.load(tag.getCompound("Settings"));
        if (tag.contains("Name")) {            // terminals saved before settings moved into their own tag
            settings.name = tag.getString("Name");
            settings.address = tag.getString("Address");
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide) {
            TerminalRegistry.forLevel(level).remove(worldPosition);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
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
