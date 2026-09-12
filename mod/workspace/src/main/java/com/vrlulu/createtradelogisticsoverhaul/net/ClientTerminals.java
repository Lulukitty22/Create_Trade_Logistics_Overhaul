package com.vrlulu.createtradelogisticsoverhaul.net;

import com.vrlulu.createtradelogisticsoverhaul.terrain.ChangeHub;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * The client's picture of the terminals, kept for the map page.
 *
 * <p>The page reads this over HTTP; refreshes are asked for from the server and arrive
 * asynchronously, so a request never blocks the browser.
 */
public final class ClientTerminals {
    private static final long REFRESH_INTERVAL_MS = 3_000;

    private static volatile List<Payloads.TerminalInfo> terminals = List.of();
    private static volatile long updatedAt;
    private static volatile long requestedAt;
    private static volatile String dispatchJson = "{}";
    private static volatile boolean autoDispatch;

    private ClientTerminals() {
    }

    public static List<Payloads.TerminalInfo> get() {
        return terminals;
    }

    public static long updatedAt() {
        return updatedAt;
    }

    /** Asks the server for a fresh list, at most every few seconds. */
    public static void requestRefresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - requestedAt < REFRESH_INTERVAL_MS) {
            return;
        }
        requestedAt = now;
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new Payloads.RequestTerminals());
        }
    }

    public static void order(net.minecraft.core.BlockPos terminal, String item, int count, String address) {
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new Payloads.PlaceOrder(terminal, item, count, address));
        }
    }

    public static void updateSettings(net.minecraft.core.BlockPos terminal, String json) {
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new Payloads.UpdateTerminal(terminal, json));
        }
    }

    public static String dispatchJson() {
        return dispatchJson;
    }

    public static boolean autoDispatch() {
        return autoDispatch;
    }

    public static void requestDispatch(boolean run) {
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new Payloads.RequestDispatch(run));
        }
    }

    static void acceptDispatch(Payloads.DispatchStatus status) {
        dispatchJson = status.json();
        autoDispatch = status.autoEnabled();
        ChangeHub.get().notifyTerminals();
    }

    static void accept(Payloads.Terminals payload) {
        terminals = payload.terminals();
        updatedAt = System.currentTimeMillis();
        ChangeHub.get().notifyTerminals();
    }

    static void acceptOrderResult(Payloads.OrderResult result) {
        ChangeHub.get().notifyOrderResult(result.ok(), result.message());
        requestRefresh(true);      // stock just changed
    }
}
