package com.vrlulu.createtradelogisticsoverhaul.logistics;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a Logistics Terminal remembers, beyond which network it is tuned to.
 *
 * <p>Edited from the map page rather than an in-game screen: the page is the interface, and the
 * block stays a simple thing you place and tune (see DESIGN.md).
 */
public class TerminalSettings {
    /** What this place does, which the dispatcher and the map use to describe it. */
    public enum Role {
        PRODUCER, CONSUMER, WAREHOUSE, POST_OFFICE;

        public static Role of(String name) {
            for (Role role : values()) {
                if (role.name().equalsIgnoreCase(name)) {
                    return role;
                }
            }
            return WAREHOUSE;
        }
    }

    /** What another player may do here. */
    public enum Access {
        NONE, VIEW, ORDER, ADMIN;

        public boolean atLeast(Access other) {
            return ordinal() >= other.ordinal();
        }

        public static Access of(String name) {
            for (Access access : values()) {
                if (access.name().equalsIgnoreCase(name)) {
                    return access;
                }
            }
            return NONE;
        }
    }

    /** An item this terminal offers to others, optionally for money. */
    public record Listing(String item, int price, int maxPerOrder) {
    }

    /** "Keep this much of this item here, fetched from that network." */
    public record SupplyRule(String item, int keepStocked, UUID sourceNetwork, String sourceName) {
    }

    public String name = "";
    public String address = "";
    public Role role = Role.WAREHOUSE;
    /** For a post office: the station on the other side of the hand-off (e.g. "Woodbury Station"). */
    public String interchangeStation = "";

    // dispatch triggers
    public boolean autoDispatch = false;
    public int batchSize = 1;
    public int maxWaitSeconds = 120;
    public int priority = 0;

    // permissions
    public Access publicAccess = Access.VIEW;
    public final Map<UUID, Access> players = new LinkedHashMap<>();
    public final Map<UUID, String> playerNames = new LinkedHashMap<>();

    // trade + supply
    public final List<Listing> listings = new ArrayList<>();
    public final List<SupplyRule> supplyRules = new ArrayList<>();

    public Access accessFor(UUID player, UUID owner) {
        if (player == null) {
            return publicAccess;
        }
        if (player.equals(owner)) {
            return Access.ADMIN;
        }
        return players.getOrDefault(player, publicAccess);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        tag.putString("Address", address);
        tag.putString("Role", role.name());
        tag.putString("Interchange", interchangeStation);
        tag.putBoolean("AutoDispatch", autoDispatch);
        tag.putInt("BatchSize", batchSize);
        tag.putInt("MaxWait", maxWaitSeconds);
        tag.putInt("Priority", priority);
        tag.putString("PublicAccess", publicAccess.name());

        ListTag people = new ListTag();
        players.forEach((id, access) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", id);
            entry.putString("Access", access.name());
            entry.putString("Name", playerNames.getOrDefault(id, ""));
            people.add(entry);
        });
        tag.put("Players", people);

        ListTag sale = new ListTag();
        for (Listing listing : listings) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Item", listing.item());
            entry.putInt("Price", listing.price());
            entry.putInt("Max", listing.maxPerOrder());
            sale.add(entry);
        }
        tag.put("Listings", sale);

        ListTag rules = new ListTag();
        for (SupplyRule rule : supplyRules) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Item", rule.item());
            entry.putInt("Keep", rule.keepStocked());
            if (rule.sourceNetwork() != null) {
                entry.putUUID("Source", rule.sourceNetwork());
            }
            entry.putString("SourceName", rule.sourceName() == null ? "" : rule.sourceName());
            rules.add(entry);
        }
        tag.put("SupplyRules", rules);
        return tag;
    }

    public void load(CompoundTag tag) {
        name = tag.getString("Name");
        address = tag.getString("Address");
        role = Role.of(tag.getString("Role"));
        interchangeStation = tag.getString("Interchange");
        autoDispatch = tag.getBoolean("AutoDispatch");
        batchSize = Math.max(1, tag.getInt("BatchSize"));
        maxWaitSeconds = Math.max(0, tag.getInt("MaxWait"));
        priority = tag.getInt("Priority");
        publicAccess = Access.of(tag.getString("PublicAccess"));

        players.clear();
        playerNames.clear();
        ListTag people = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < people.size(); i++) {
            CompoundTag entry = people.getCompound(i);
            if (entry.hasUUID("Id")) {
                UUID id = entry.getUUID("Id");
                players.put(id, Access.of(entry.getString("Access")));
                playerNames.put(id, entry.getString("Name"));
            }
        }

        listings.clear();
        ListTag sale = tag.getList("Listings", Tag.TAG_COMPOUND);
        for (int i = 0; i < sale.size(); i++) {
            CompoundTag entry = sale.getCompound(i);
            listings.add(new Listing(entry.getString("Item"), entry.getInt("Price"), entry.getInt("Max")));
        }

        supplyRules.clear();
        ListTag rules = tag.getList("SupplyRules", Tag.TAG_COMPOUND);
        for (int i = 0; i < rules.size(); i++) {
            CompoundTag entry = rules.getCompound(i);
            supplyRules.add(new SupplyRule(entry.getString("Item"), entry.getInt("Keep"),
                    entry.hasUUID("Source") ? entry.getUUID("Source") : null, entry.getString("SourceName")));
        }
    }
}
