package com.vrlulu.createtradelogisticsoverhaul.net;

import com.simibubi.create.content.logistics.BigItemStack;
import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import com.vrlulu.createtradelogisticsoverhaul.logistics.LogisticsTerminalBlockEntity;
import com.vrlulu.createtradelogisticsoverhaul.logistics.TerminalRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/** Wires the payloads up and holds the server-side handling. */
@EventBusSubscriber(modid = CreateTradeLogisticsOverhaul.ID)
public final class Network {
    private static final int MAX_STOCK_LINES = 400;

    private Network() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(Payloads.RequestTerminals.TYPE, Payloads.RequestTerminals.CODEC,
                Network::onRequestTerminals);
        registrar.playToServer(Payloads.PlaceOrder.TYPE, Payloads.PlaceOrder.CODEC, Network::onPlaceOrder);
        registrar.playToClient(Payloads.Terminals.TYPE, Payloads.Terminals.CODEC,
                (payload, context) -> ClientTerminals.accept(payload));
        registrar.playToClient(Payloads.OrderResult.TYPE, Payloads.OrderResult.CODEC,
                (payload, context) -> ClientTerminals.acceptOrderResult(payload));
    }

    /** Collects the terminals in the player's dimension and sends them back with their stock. */
    private static void onRequestTerminals(Payloads.RequestTerminals payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            List<Payloads.TerminalInfo> out = new ArrayList<>();
            for (LogisticsTerminalBlockEntity terminal : TerminalRegistry.forLevel(level).loaded(level)) {
                List<Payloads.StockLine> stock = new ArrayList<>();
                for (BigItemStack big : terminal.stock()) {
                    if (stock.size() >= MAX_STOCK_LINES) {
                        break;
                    }
                    ItemStack stack = big.stack;
                    stock.add(new Payloads.StockLine(
                            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                            stack.getHoverName().getString(),
                            big.count));
                }
                out.add(new Payloads.TerminalInfo(terminal.getBlockPos(),
                        level.dimension().location().toString(),
                        terminal.terminalName(), terminal.address(), terminal.ownerName(),
                        terminal.isTuned(), !stock.isEmpty(), stock));
            }
            context.reply(new Payloads.Terminals(out));
        });
    }

    /** Places a package order through the terminal, using Create's own logistics. */
    private static void onPlaceOrder(Payloads.PlaceOrder payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!(level.getBlockEntity(payload.terminal()) instanceof LogisticsTerminalBlockEntity terminal)) {
                context.reply(new Payloads.OrderResult(false, "That terminal is not loaded"));
                return;
            }
            if (!terminal.isTuned()) {
                context.reply(new Payloads.OrderResult(false, "That terminal has no network"));
                return;
            }
            if (payload.address().isBlank()) {
                context.reply(new Payloads.OrderResult(false, "No delivery address given"));
                return;
            }
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(payload.item()));
            if (item == null || payload.count() <= 0) {
                context.reply(new Payloads.OrderResult(false, "Unknown item"));
                return;
            }
            boolean sent = terminal.order(
                    List.of(LogisticsTerminalBlockEntity.bigStack(new ItemStack(item), payload.count())),
                    payload.address());
            String what = payload.count() + " x " + item.getDescription().getString();
            context.reply(sent
                    ? new Payloads.OrderResult(true, "Ordered " + what + " to " + payload.address())
                    : new Payloads.OrderResult(false, "No packager could fill " + what
                            + " (is the network loaded and stocked?)"));
        });
    }
}
