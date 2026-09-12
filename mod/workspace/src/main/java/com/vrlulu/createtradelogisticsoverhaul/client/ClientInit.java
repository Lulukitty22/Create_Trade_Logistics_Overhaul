package com.vrlulu.createtradelogisticsoverhaul.client;

import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import com.vrlulu.createtradelogisticsoverhaul.web.MapWebServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only half: runs the local map web server for as long as the game is open. */
@Mod(value = CreateTradeLogisticsOverhaul.ID, dist = Dist.CLIENT)
public class ClientInit {
    private static MapWebServer server;

    public ClientInit(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            server = new MapWebServer(MapWebServer.DEFAULT_PORT);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "clo-webserver-shutdown"));
        });
    }

    public static MapWebServer server() {
        return server;
    }
}
