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
 * <p>Payment is held when an order is placed and released when it arrives: the buyer's funds are
 * withdrawn here and parked in {@link Escrow} until the goods show up. Nothing in this class knows
 * about deliveries; it only moves money.
 */
public final class Payments {
    private static final String BANK_ACCOUNT = "dev.ithundxr.createnumismatics.content.backend.BankAccount";
    private static Boolean available;

    private Payments() {
    }

    public static boolean available() {
        if (available == null) {
            try {
                Class.forName(BANK_ACCOUNT);
                available = true;
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    /** Takes money out of the buyer's account. False means they couldn't pay and nothing moved. */
    public static boolean withdraw(ServerPlayer buyer, int amount) {
        if (amount <= 0) {
            return true;
        }
        Object account = accountOf(buyer.getUUID());
        if (account == null) {
            return false;
        }
        try {
            return (boolean) Class.forName(BANK_ACCOUNT).getMethod("deduct", int.class).invoke(account, amount);
        } catch (Throwable t) {
            CreateTradeLogisticsOverhaul.LOG.warn("Numismatics withdrawal failed", t);
            return false;
        }
    }

    /** Puts money into an account. */
    public static boolean deposit(UUID owner, int amount) {
        if (amount <= 0) {
            return true;
        }
        Object account = accountOf(owner);
        if (account == null) {
            return false;
        }
        try {
            Class.forName(BANK_ACCOUNT).getMethod("deposit", int.class).invoke(account, amount);
            return true;
        } catch (Throwable t) {
            CreateTradeLogisticsOverhaul.LOG.warn("Numismatics deposit failed", t);
            return false;
        }
    }

    private static Object accountOf(UUID owner) {
        if (!available() || owner == null) {
            return null;
        }
        try {
            Class<?> manager = Class.forName("dev.ithundxr.createnumismatics.content.backend.GlobalBankManager");
            Class<?> numismatics = Class.forName("dev.ithundxr.createnumismatics.Numismatics");
            Object bank = numismatics.getField("BANK").get(null);
            return manager.getMethod("getAccount", UUID.class).invoke(bank, owner);
        } catch (Throwable t) {
            CreateTradeLogisticsOverhaul.LOG.warn("Numismatics account lookup failed", t);
            return null;
        }
    }
}
