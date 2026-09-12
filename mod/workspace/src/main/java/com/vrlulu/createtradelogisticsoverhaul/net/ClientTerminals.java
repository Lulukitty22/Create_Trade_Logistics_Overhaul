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
    private static final Object dispatchLock = new Object();
    private static volatile long dispatchVersion;

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

    public static long dispatchVersion() {
        return dispatchVersion;
    }

    public static void requestDispatch(boolean run) {
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new Payloads.RequestDispatch(run));
        }
    }

    /**
     * Waits briefly for a dispatch reply newer than {@code since}. The page asks over HTTP but the
     * server answers with a packet, so the web thread parks here rather than handing back stale
     * numbers. It deliberately does not ring the event stream: that would make the page ask again,
     * and the two would chase each other forever.
     */
    public static void awaitDispatch(long since, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (dispatchLock) {
            while (dispatchVersion == since) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return;
                }
                try {
                    dispatchLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    static void acceptDispatch(Payloads.DispatchStatus status) {
        dispatchJson = status.json();
        autoDispatch = status.autoEnabled();
        synchronized (dispatchLock) {
            dispatchVersion++;
            dispatchLock.notifyAll();
        }
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
