package com.vrlulu.createtradelogisticsoverhaul.client;

import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import com.vrlulu.createtradelogisticsoverhaul.terrain.RailMap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Keeps the map's view of the railway up to date, on the thread that owns it. */
@EventBusSubscriber(modid = CreateTradeLogisticsOverhaul.ID, value = Dist.CLIENT)
public final class RailMapTicker {
    private RailMapTicker() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        RailMap.tick();
    }
}
