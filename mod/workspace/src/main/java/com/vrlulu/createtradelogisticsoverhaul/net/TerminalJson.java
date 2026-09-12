package com.vrlulu.createtradelogisticsoverhaul.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vrlulu.createtradelogisticsoverhaul.logistics.LogisticsTerminalBlockEntity;
import com.vrlulu.createtradelogisticsoverhaul.logistics.TerminalSettings;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;

/**
 * Terminal settings as JSON, so the map page can read and edit them without a bespoke packet per
 * field. Unknown or missing fields are simply left alone.
 */
public final class TerminalJson {
    private TerminalJson() {
    }

    public static String settingsToJson(LogisticsTerminalBlockEntity terminal) {
        TerminalSettings s = terminal.settings();
        JsonObject root = new JsonObject();
        root.addProperty("network", terminal.network() == null ? "" : terminal.network().toString());
        root.addProperty("role", s.role.name());
        root.addProperty("interchangeStation", s.interchangeStation);
        root.addProperty("autoDispatch", s.autoDispatch);
        root.addProperty("batchSize", s.batchSize);
        root.addProperty("maxWaitSeconds", s.maxWaitSeconds);
        root.addProperty("priority", s.priority);
        root.addProperty("publicAccess", s.publicAccess.name());

        JsonArray players = new JsonArray();
        for (Map.Entry<UUID, TerminalSettings.Access> entry : s.players.entrySet()) {
            JsonObject person = new JsonObject();
            person.addProperty("id", entry.getKey().toString());
            person.addProperty("name", s.playerNames.getOrDefault(entry.getKey(), ""));
            person.addProperty("access", entry.getValue().name());
            players.add(person);
        }
        root.add("players", players);

        JsonArray listings = new JsonArray();
        for (TerminalSettings.Listing listing : s.listings) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", listing.item());
            entry.addProperty("price", listing.price());
            entry.addProperty("maxPerOrder", listing.maxPerOrder());
            listings.add(entry);
        }
        root.add("listings", listings);

        JsonArray rules = new JsonArray();
        for (TerminalSettings.SupplyRule rule : s.supplyRules) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", rule.item());
            entry.addProperty("keepStocked", rule.keepStocked());
            entry.addProperty("sourceNetwork", rule.sourceNetwork() == null ? "" : rule.sourceNetwork().toString());
            entry.addProperty("sourceName", rule.sourceName());
            rules.add(entry);
        }
        root.add("supplyRules", rules);
        return root.toString();
    }

    /** Applies the fields present in json. Returns a problem description, or null if it went fine. */
    public static String applyJson(LogisticsTerminalBlockEntity terminal, String json, MinecraftServer server) {
        TerminalSettings s = terminal.settings();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("name")) {
                s.name = trim(root.get("name").getAsString(), 48);
            }
            if (root.has("address")) {
                s.address = trim(root.get("address").getAsString(), 64);
            }
            if (root.has("role")) {
                s.role = TerminalSettings.Role.of(root.get("role").getAsString());
            }
            if (root.has("interchangeStation")) {
                s.interchangeStation = trim(root.get("interchangeStation").getAsString(), 64);
            }
            if (root.has("autoDispatch")) {
                s.autoDispatch = root.get("autoDispatch").getAsBoolean();
            }
            if (root.has("batchSize")) {
                s.batchSize = Math.max(1, root.get("batchSize").getAsInt());
            }
            if (root.has("maxWaitSeconds")) {
                s.maxWaitSeconds = Math.max(0, root.get("maxWaitSeconds").getAsInt());
            }
            if (root.has("priority")) {
                s.priority = root.get("priority").getAsInt();
            }
            if (root.has("publicAccess")) {
                s.publicAccess = TerminalSettings.Access.of(root.get("publicAccess").getAsString());
            }
            if (root.has("players")) {
                s.players.clear();
                s.playerNames.clear();
                for (var element : root.getAsJsonArray("players")) {
                    JsonObject person = element.getAsJsonObject();
                    UUID id = resolvePlayer(person, server);
                    if (id == null) {
                        continue;
                    }
                    s.players.put(id, TerminalSettings.Access.of(person.get("access").getAsString()));
                    s.playerNames.put(id, person.has("name") ? person.get("name").getAsString() : "");
                }
            }
            if (root.has("listings")) {
                s.listings.clear();
                for (var element : root.getAsJsonArray("listings")) {
                    JsonObject entry = element.getAsJsonObject();
                    s.listings.add(new TerminalSettings.Listing(entry.get("item").getAsString(),
                            optInt(entry, "price"), optInt(entry, "maxPerOrder")));
                }
            }
            if (root.has("supplyRules")) {
                s.supplyRules.clear();
                for (var element : root.getAsJsonArray("supplyRules")) {
                    JsonObject entry = element.getAsJsonObject();
                    String source = entry.has("sourceNetwork") ? entry.get("sourceNetwork").getAsString() : "";
                    s.supplyRules.add(new TerminalSettings.SupplyRule(entry.get("item").getAsString(),
                            optInt(entry, "keepStocked"),
                            source.isBlank() ? null : UUID.fromString(source),
                            entry.has("sourceName") ? entry.get("sourceName").getAsString() : ""));
                }
            }
            return null;
        } catch (Exception e) {
            return "Could not read those settings: " + e.getMessage();
        }
    }

    /** Accepts either a uuid or a player name, so the page can offer a simple text field. */
    private static UUID resolvePlayer(JsonObject person, MinecraftServer server) {
        try {
            if (person.has("id") && !person.get("id").getAsString().isBlank()) {
                return UUID.fromString(person.get("id").getAsString());
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to the name
        }
        if (person.has("name") && server != null) {
            var profile = server.getProfileCache() == null ? null
                    : server.getProfileCache().get(person.get("name").getAsString()).orElse(null);
            if (profile != null) {
                return profile.getId();
            }
        }
        return null;
    }

    private static int optInt(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsInt() : 0;
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
