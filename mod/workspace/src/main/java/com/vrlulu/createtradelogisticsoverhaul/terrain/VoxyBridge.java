package com.vrlulu.createtradelogisticsoverhaul.terrain;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.IWorldGetIdentifier;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.function.LongConsumer;

/**
 * Access to Voxy's live LOD data from inside the game.
 *
 * <p>Voxy runs in the same process, so there's no need to copy or parse its database: ask its
 * {@link WorldEngine} for sections directly. Nothing here writes.
 */
public final class VoxyBridge {
    private VoxyBridge() {
    }

    public static boolean available() {
        try {
            return VoxyCommon.isAvailable();
        } catch (Throwable t) {   // Voxy missing entirely
            return false;
        }
    }

    /** The engine for the level the player is in, or null if Voxy has no data for it yet. */
    public static WorldEngine engine() {
        if (!available()) {
            return null;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        return VoxyCommon.getInstance().getNullable(((IWorldGetIdentifier) level).voxy$getIdentifier());
    }

    /**
     * Visits the key of every section Voxy has stored. Returns false if this storage backend can't
     * enumerate (then callers fall back to probing around the player).
     */
    public static boolean forEachStoredSection(WorldEngine engine, LongConsumer visitor) {
        if (engine.storage instanceof SectionSerializationStorage storage) {
            storage.iterateStoredSectionPositions(visitor);
            return true;
        }
        return false;
    }
}
