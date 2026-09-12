package com.vrlulu.createtradelogisticsoverhaul.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long the mod's own work actually takes, so a stutter can be attributed rather than guessed at.
 *
 * <p>Every measurement names the thread it happened on, because that is the part that matters: work
 * on the server or client thread delays the game, work on a web thread only delays the browser.
 */
public final class Perf {
    private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();

    private Perf() {
    }

    /** One named measurement. */
    public static final class Stat {
        private long count;
        private long totalNanos;
        private long maxNanos;
        private long lastNanos;

        synchronized void record(long nanos) {
            count++;
            totalNanos += nanos;
            lastNanos = nanos;
            if (nanos > maxNanos) {
                maxNanos = nanos;
            }
        }

        synchronized String toJson(String name) {
            double ms = 1_000_000.0;
            return "{\"name\":\"" + name + "\",\"count\":" + count
                    + ",\"lastMs\":" + String.format(java.util.Locale.ROOT, "%.2f", lastNanos / ms)
                    + ",\"avgMs\":" + String.format(java.util.Locale.ROOT, "%.2f",
                            count == 0 ? 0 : totalNanos / (double) count / ms)
                    + ",\"maxMs\":" + String.format(java.util.Locale.ROOT, "%.2f", maxNanos / ms) + "}";
        }
    }

    /** Times a block of work that returns nothing. */
    public static void time(String name, Runnable work) {
        long start = System.nanoTime();
        try {
            work.run();
        } finally {
            record(name, System.nanoTime() - start);
        }
    }

    public static void record(String name, long nanos) {
        STATS.computeIfAbsent(name, key -> new Stat()).record(nanos);
    }

    public static String toJson() {
        StringBuilder out = new StringBuilder("{\"timings\":[");
        boolean first = true;
        for (Map.Entry<String, Stat> entry : STATS.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(entry.getValue().toJson(entry.getKey()));
        }
        return out.append("]}").toString();
    }

    public static void clear() {
        STATS.clear();
    }
}
