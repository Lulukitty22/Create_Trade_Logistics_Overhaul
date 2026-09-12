package com.vrlulu.createtradelogisticsoverhaul.mixin;

import com.vrlulu.createtradelogisticsoverhaul.terrain.ChangeHub;
import me.cortex.voxy.common.world.WorldSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notices every section Voxy changes, so the map can update live.
 *
 * <p>Voxy's own dirty callback can't be borrowed: it belongs to Voxy's renderer and is a single
 * slot, so setting it would break Voxy's rendering. Hooking markDirty instead means we're told
 * alongside the renderer, changing nothing about Voxy's behaviour.
 */
@Mixin(targets = "me.cortex.voxy.common.world.WorldEngine", remap = false)
public class VoxyWorldEngineMixin {
    @Inject(method = "markDirty(Lme/cortex/voxy/common/world/WorldSection;II)V", at = @At("HEAD"))
    private void ctlo$onSectionChanged(WorldSection section, int updateType, int childBits, CallbackInfo ci) {
        ChangeHub.get().record(section.key);
    }
}
