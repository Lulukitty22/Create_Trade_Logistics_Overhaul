package com.vrlulu.createtradelogisticsoverhaul.net;

import com.simibubi.create.content.logistics.BigItemStack;
import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import com.vrlulu.createtradelogisticsoverhaul.logistics.Escrow;
import com.vrlulu.createtradelogisticsoverhaul.logistics.LogisticsTerminalBlockEntity;
import com.vrlulu.createtradelogisticsoverhaul.logistics.Payments;
import com.vrlulu.createtradelogisticsoverhaul.logistics.TerminalRegistry;
import com.vrlulu.createtradelogisticsoverhaul.logistics.TerminalSettings;
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
        registrar.playToServer(Payloads.UpdateTerminal.TYPE, Payloads.UpdateTerminal.CODEC, Network::onUpdateTerminal);
        registrar.playToClient(Payloads.Terminals.TYPE, Payloads.Terminals.CODEC,
                (payload, context) -> ClientTerminals.accept(payload));
        registrar.playToServer(Payloads.RequestDispatch.TYPE, Payloads.RequestDispatch.CODEC,
                Network::onRequestDispatch);
        registrar.playToClient(Payloads.OrderResult.TYPE, Payloads.OrderResult.CODEC,
                (payload, context) -> ClientTerminals.acceptOrderResult(payload));
        registrar.playToClient(Payloads.DispatchStatus.TYPE, Payloads.DispatchStatus.CODEC,
                (payload, context) -> ClientTerminals.acceptDispatch(payload));
    }

    /** Terminals the player may at least see, with their stock. */
    private static void onRequestTerminals(Payloads.RequestTerminals payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            List<Payloads.TerminalInfo> out = new ArrayList<>();
            for (LogisticsTerminalBlockEntity terminal : TerminalRegistry.forLevel(level).loaded(level)) {
                TerminalSettings.Access access = terminal.accessFor(player);
                if (!access.atLeast(TerminalSettings.Access.VIEW)) {
                    continue;
                }
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
                        terminal.isTuned(), !stock.isEmpty(), stock,
                        TerminalJson.settingsToJson(terminal), access.name()));
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
            if (!terminal.accessFor(player).atLeast(TerminalSettings.Access.ORDER)) {
                context.reply(new Payloads.OrderResult(false, "You may not order from " + terminal.terminalName()));
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

            // Trade rules: only the owner escapes the listing limits and the price.
            TerminalSettings settings = terminal.settings();
            boolean isOwner = player.getUUID().equals(terminal.ownerId());
            int price = 0;
            if (!isOwner && !settings.listings.isEmpty()) {
                TerminalSettings.Listing listing = settings.listings.stream()
                        .filter(l -> l.item().equals(payload.item())).findFirst().orElse(null);
                if (listing == null) {
                    context.reply(new Payloads.OrderResult(false, "That item is not offered here"));
                    return;
                }
                if (listing.maxPerOrder() > 0 && payload.count() > listing.maxPerOrder()) {
                    context.reply(new Payloads.OrderResult(false,
                            "At most " + listing.maxPerOrder() + " per order here"));
                    return;
                }
                price = listing.price() * payload.count();
            }
            // The money is held, not paid: it goes to the seller once the goods turn up.
            long hold = -1;
            if (price > 0) {
                int baseline = TerminalRegistry.stockAtAddress(
                        level.getServer(), payload.address(), payload.item());
                hold = Escrow.of(level.getServer()).hold(player, terminal.ownerId(), price,
                        payload.address(), payload.item(), payload.count(), Math.max(0, baseline));
                if (hold < 0) {
                    context.reply(new Payloads.OrderResult(false, Payments.available()
                            ? "Payment of " + price + " failed (not enough funds?)"
                            : "This terminal charges money, but Numismatics is not installed"));
                    return;
                }
            }

            boolean sent = terminal.order(
                    List.of(LogisticsTerminalBlockEntity.bigStack(new ItemStack(item), payload.count())),
                    payload.address());
            String what = payload.count() + " x " + item.getDescription().getString();
            if (!sent) {
                if (hold >= 0) {
                    Escrow.of(level.getServer()).refund(hold);
                }
                context.reply(new Payloads.OrderResult(false, "No packager could fill " + what
                        + " (is the network loaded and stocked?)"));
                return;
            }
            String paid = price > 0 ? " for " + price + " (held until it arrives)" : "";
            context.reply(new Payloads.OrderResult(true,
                    "Ordered " + what + paid + " to " + payload.address()));
        });
    }

    /** What the dispatcher sees, and optionally sends the trains (operators only). */
    private static void onRequestDispatch(Payloads.RequestDispatch payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            boolean mayRun = player.hasPermissions(2);
            List<com.vrlulu.createtradelogisticsoverhaul.dispatch.Dispatcher.Plan> plans =
                    com.vrlulu.createtradelogisticsoverhaul.dispatch.Dispatcher.plan(player.getServer());
            int sent = 0;
            if (payload.run() && mayRun) {
                for (var plan : plans) {
                    if (plan.isPossible()
                            && com.vrlulu.createtradelogisticsoverhaul.dispatch.Dispatcher
                                    .assign(plan, player.server.registryAccess())) {
                        sent++;
                    }
                }
                context.reply(new Payloads.OrderResult(true, "Dispatched " + sent + " train(s)"));
            } else if (payload.run()) {
                context.reply(new Payloads.OrderResult(false, "You need operator rights to dispatch"));
            }
            context.reply(new Payloads.DispatchStatus(
                    com.vrlulu.createtradelogisticsoverhaul.dispatch.DispatchService.enabled(),
                    DispatchJson.toJson(plans, mayRun)));
        });
    }

    /** Applies settings edited on the map, for players allowed to administrate the terminal. */
    private static void onUpdateTerminal(Payloads.UpdateTerminal payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (!(level.getBlockEntity(payload.terminal()) instanceof LogisticsTerminalBlockEntity terminal)) {
                context.reply(new Payloads.OrderResult(false, "That terminal is not loaded"));
                return;
            }
            if (!terminal.accessFor(player).atLeast(TerminalSettings.Access.ADMIN)) {
                context.reply(new Payloads.OrderResult(false, "Only the owner can change that terminal"));
                return;
            }
            String problem = TerminalJson.applyJson(terminal, payload.json(), level.getServer());
            terminal.setChanged();
            context.reply(problem == null
                    ? new Payloads.OrderResult(true, "Saved " + terminal.terminalName())
                    : new Payloads.OrderResult(false, problem));
            onRequestTerminals(new Payloads.RequestTerminals(), context);
        });
    }
}
