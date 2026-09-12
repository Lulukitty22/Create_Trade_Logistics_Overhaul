package com.vrlulu.createtradelogisticsoverhaul.terrain;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects the sections Voxy changes and hands them to open map pages.
 *
 * <p>Fed by the mixin on Voxy's markDirty, so updates are exact and immediate rather than polled.
 * Keys are coalesced per subscriber: during ingest Voxy touches the same section many times, and
 * the page only needs to know it should re-fetch it once.
 */
public final class ChangeHub {
    private static final ChangeHub INSTANCE = new ChangeHub();
    /** Beyond this a page is hopelessly behind; it will catch up on its next full load. */
    private static final int MAX_PENDING = 20_000;

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    public static ChangeHub get() {
        return INSTANCE;
    }

    /** Called from Voxy's threads: must stay cheap and never throw. */
    public void record(long sectionKey) {
        com.vrlulu.createtradelogisticsoverhaul.web.Perf.record("voxy:markDirty", 0);
        for (Subscription sub : subscriptions) {
            sub.add(sectionKey);
        }
    }

    public boolean hasListeners() {
        return !subscriptions.isEmpty();
    }

    /** Terminal list changed: the page should re-read /api/terminals. */
    public void notifyTerminals() {
        for (Subscription sub : subscriptions) {
            sub.markTerminals();
        }
    }

    /** The result of an order the page placed. */
    public void notifyOrderResult(boolean ok, String message) {
        for (Subscription sub : subscriptions) {
            sub.pushOrderResult(ok, message);
        }
    }

    /** Tells every open page to reload from scratch (after a re-sync). */
    public void requestResync() {
        for (Subscription sub : subscriptions) {
            sub.markResync();
        }
    }

    public Subscription subscribe() {
        Subscription sub = new Subscription();
        subscriptions.add(sub);
        return sub;
    }

    public void unsubscribe(Subscription sub) {
        subscriptions.remove(sub);
    }

    public static final class Subscription {
        private final LongOpenHashSet pending = new LongOpenHashSet();
        private boolean overflowed;
        private boolean resync;
        private boolean terminalsChanged;
        private final java.util.List<String> orderResults = new java.util.ArrayList<>();

        private synchronized void markTerminals() {
            terminalsChanged = true;
            notifyAll();
        }

        private synchronized void pushOrderResult(boolean ok, String message) {
            orderResults.add((ok ? "ok|" : "fail|") + message);
            notifyAll();
        }

        public synchronized boolean takeTerminalsChanged() {
            boolean was = terminalsChanged;
            terminalsChanged = false;
            return was;
        }

        public synchronized java.util.List<String> takeOrderResults() {
            if (orderResults.isEmpty()) {
                return java.util.List.of();
            }
            java.util.List<String> copy = java.util.List.copyOf(orderResults);
            orderResults.clear();
            return copy;
        }

        private synchronized void markResync() {
            resync = true;
            notifyAll();
        }

        /** True once, after a re-sync was requested. */
        public synchronized boolean takeResync() {
            boolean was = resync;
            resync = false;
            return was;
        }

        private synchronized void add(long key) {
            if (pending.size() >= MAX_PENDING) {
                overflowed = true;
                return;
            }
            pending.add(key);
            notifyAll();
        }

        /** Waits up to waitMs for changes, then returns and clears them. */
        public synchronized long[] drain(long waitMs) throws InterruptedException {
            if (pending.isEmpty() && !resync && !terminalsChanged && orderResults.isEmpty()) {
                wait(waitMs);
            }
            long[] keys = pending.toLongArray();
            pending.clear();
            overflowed = false;
            return keys;
        }

        public synchronized boolean didOverflow() {
            return overflowed;
        }
    }
}
