package com.vrlulu.createtradelogisticsoverhaul.logistics;

import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Money held between ordering and delivery.
 *
 * <p>The design asks for the buyer's funds to be held when they order and handed over when the
 * goods arrive, so a seller can't take payment for a delivery that never happens. The money leaves
 * the buyer's account at once and sits here until one of three things happens:
 *
 * <ul>
 *   <li>the destination's stock goes up by what was ordered — the goods arrived, so the seller is paid;</li>
 *   <li>the order could not be filled at all — the buyer is refunded straight away;</li>
 *   <li>nothing is observed for {@link #GRACE_MS} — the seller is paid anyway. Payment is only ever
 *       taken once Create has accepted the request, meaning the stock was really packaged, so after
 *       the grace period the balance of probability is that it was delivered and simply used up
 *       before anyone looked.</li>
 * </ul>
 *
 * <p>Saved with the world, so a restart mid-delivery doesn't swallow anyone's money.
 */
public class Escrow extends SavedData {
    private static final String FILE = "createtradelogisticsoverhaul_escrow";
    /** How long to wait for a delivery to show up before paying the seller anyway. */
    public static final long GRACE_MS = 10 * 60 * 1000L;

    /** One payment waiting on a delivery. */
    public record Held(long id, UUID buyer, UUID seller, int amount, String address,
                       String item, int count, int baseline, long placedAt) {
    }

    private final List<Held> held = new ArrayList<>();
    private long nextId = 1;

    /** The escrow lives in the overworld, so it is one ledger for the whole server. */
    public static Escrow of(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(Escrow::new, Escrow::load), FILE);
    }

    /**
     * Takes the money from the buyer and holds it. Returns the hold's id, or -1 if the buyer
     * couldn't pay — in which case nothing was taken.
     */
    public long hold(net.minecraft.server.level.ServerPlayer buyer, UUID seller, int amount,
                     String address, String item, int count, int baseline) {
        if (amount <= 0) {
            return -1;
        }
        if (!Payments.withdraw(buyer, amount)) {
            return -1;
        }
        long id = nextId++;
        held.add(new Held(id, buyer.getUUID(), seller, amount, address, item, count, baseline,
                System.currentTimeMillis()));
        setDirty();
        CreateTradeLogisticsOverhaul.LOG.info("Holding {} from {} for {} x {} to {}",
                amount, buyer.getGameProfile().getName(), count, item, address);
        return id;
    }

    /** Gives a held payment to the seller. */
    public void release(long id) {
        finish(id, true);
    }

    /** Gives a held payment back to the buyer. */
    public void refund(long id) {
        finish(id, false);
    }

    private void finish(long id, boolean toSeller) {
        for (int i = 0; i < held.size(); i++) {
            Held entry = held.get(i);
            if (entry.id() != id) {
                continue;
            }
            Payments.deposit(toSeller ? entry.seller() : entry.buyer(), entry.amount());
            held.remove(i);
            setDirty();
            CreateTradeLogisticsOverhaul.LOG.info("{} {} for {} ({})",
                    toSeller ? "Released" : "Refunded", entry.amount(), entry.address(),
                    toSeller ? "delivered" : "order failed");
            return;
        }
    }

    /**
     * Settles anything that has arrived or run out of time. {@code stockAt} reports how many of an
     * item a destination currently holds, or -1 when that terminal isn't loaded and can't be read.
     */
    public void settle(java.util.function.ToIntBiFunction<String, String> stockAt) {
        long now = System.currentTimeMillis();
        for (Held entry : List.copyOf(held)) {
            int stock = stockAt.applyAsInt(entry.address(), entry.item());
            if (stock >= 0 && stock >= entry.baseline() + entry.count()) {
                release(entry.id());
            } else if (now - entry.placedAt() > GRACE_MS) {
                release(entry.id());
            }
        }
    }

    public List<Held> open() {
        return List.copyOf(held);
    }

    private static Escrow load(CompoundTag tag, HolderLookup.Provider registries) {
        Escrow escrow = new Escrow();
        escrow.nextId = Math.max(1, tag.getLong("NextId"));
        ListTag list = tag.getList("Held", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            escrow.held.add(new Held(entry.getLong("Id"), entry.getUUID("Buyer"), entry.getUUID("Seller"),
                    entry.getInt("Amount"), entry.getString("Address"), entry.getString("Item"),
                    entry.getInt("Count"), entry.getInt("Baseline"), entry.getLong("PlacedAt")));
        }
        return escrow;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("NextId", nextId);
        ListTag list = new ListTag();
        for (Held entry : held) {
            CompoundTag out = new CompoundTag();
            out.putLong("Id", entry.id());
            out.putUUID("Buyer", entry.buyer());
            out.putUUID("Seller", entry.seller());
            out.putInt("Amount", entry.amount());
            out.putString("Address", entry.address());
            out.putString("Item", entry.item());
            out.putInt("Count", entry.count());
            out.putInt("Baseline", entry.baseline());
            out.putLong("PlacedAt", entry.placedAt());
            list.add(out);
        }
        tag.put("Held", list);
        return tag;
    }
}
