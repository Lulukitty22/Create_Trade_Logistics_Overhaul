package com.vrlulu.createtradelogisticsoverhaul.logistics;

import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Money, when Create Numismatics is installed.
 *
 * <p>Reached by reflection so the mod still loads without Numismatics: nothing here is required for
 * the rest of the logistics to work, and a missing mod simply means orders are free.
 *
 * <p>Payment is held when an order is placed and released when it arrives, per the design. This
 * first pass transfers immediately on order; the escrow half is marked TODO below because holding
 * funds needs delivery tracking, which the dispatcher doesn't report yet.
 */
public final class Payments {
    private static Boolean available;

    private Payments() {
    }

    public static boolean available() {
        if (available == null) {
            try {
                Class.forName("dev.ithundxr.createnumismatics.content.backend.BankAccount");
                available = true;
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    /**
     * Moves `amount` from the buyer to the seller. Returns true if it went through (or if there is
     * nothing to pay). TODO: hold at order time and release on delivery once deliveries are tracked.
     */
    public static boolean transfer(ServerPlayer buyer, UUID seller, int amount) {
        if (amount <= 0) {
            return true;
        }
        if (!available() || seller == null) {
            return false;
        }
        try {
            Class<?> manager = Class.forName("dev.ithundxr.createnumismatics.content.backend.GlobalBankManager");
            Class<?> numismatics = Class.forName("dev.ithundxr.createnumismatics.Numismatics");
            Object bank = numismatics.getField("BANK").get(null);
            Object buyerAccount = manager.getMethod("getAccount", UUID.class).invoke(bank, buyer.getUUID());
            Object sellerAccount = manager.getMethod("getAccount", UUID.class).invoke(bank, seller);
            if (buyerAccount == null || sellerAccount == null) {
                return false;
            }
            Class<?> account = buyerAccount.getClass();
            boolean deducted = (boolean) account.getMethod("deduct", int.class).invoke(buyerAccount, amount);
            if (!deducted) {
                return false;
            }
            account.getMethod("deposit", int.class).invoke(sellerAccount, amount);
            return true;
        } catch (Throwable t) {
            CreateTradeLogisticsOverhaul.LOG.warn("Numismatics payment failed", t);
            return false;
        }
    }
}
