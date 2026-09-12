package com.vrlulu.createtradelogisticsoverhaul.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vrlulu.createtradelogisticsoverhaul.dispatch.Dispatcher;

import java.util.List;

/** The dispatcher's view as JSON, so the map can show what is waiting and what it would do. */
public final class DispatchJson {
    private DispatchJson() {
    }

    public static String toJson(List<Dispatcher.Plan> plans, boolean mayDispatch) {
        JsonObject root = new JsonObject();
        root.addProperty("mayDispatch", mayDispatch);
        JsonArray runs = new JsonArray();
        int possible = 0;
        int packages = 0;
        for (Dispatcher.Plan plan : plans) {
            JsonObject entry = new JsonObject();
            entry.addProperty("train", plan.trainName());
            entry.addProperty("pickup", plan.pickupStation());
            entry.addProperty("drop", plan.dropStation());
            entry.addProperty("address", plan.address());
            entry.addProperty("packages", plan.packages());
            entry.addProperty("possible", plan.isPossible());
            entry.addProperty("problem", plan.problem() == null ? "" : plan.problem());
            JsonArray stops = new JsonArray();
            plan.stops().forEach(stops::add);
            entry.add("stops", stops);
            runs.add(entry);
            packages += plan.packages();
            if (plan.isPossible()) {
                possible++;
            }
        }
        root.add("runs", runs);
        root.addProperty("waitingPackages", packages);
        root.addProperty("readyRuns", possible);
        root.addProperty("blockedRuns", plans.size() - possible);
        return root.toString();
    }
}
