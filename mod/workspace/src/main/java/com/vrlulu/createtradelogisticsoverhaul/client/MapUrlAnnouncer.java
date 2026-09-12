package com.vrlulu.createtradelogisticsoverhaul.client;

import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import com.vrlulu.createtradelogisticsoverhaul.web.MapWebServer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Says where the map is, in chat, once per world.
 *
 * <p>The server takes the next free port when its usual one is busy, so the address isn't always
 * the one you'd guess. Printing the real link removes the guesswork.
 */
@EventBusSubscriber(modid = CreateTradeLogisticsOverhaul.ID, value = Dist.CLIENT)
public final class MapUrlAnnouncer {
    private MapUrlAnnouncer() {
    }

    @SubscribeEvent
    public static void onJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        MapWebServer server = ClientInit.server();
        if (server == null || !server.isRunning()) {
            return;
        }
        String url = "http://127.0.0.1:" + server.port() + "/";
        Component link = Component.literal(url).withStyle(Style.EMPTY
                .withColor(ChatFormatting.GOLD)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Open the logistics map"))));
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal("Logistics map: ")
                .withStyle(ChatFormatting.GRAY).append(link));
    }
}
