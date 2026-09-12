package com.vrlulu.createtradelogisticsoverhaul.dispatch;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * /ctlo commands.
 *
 * <p>"dispatch" only reports what it would do. "dispatch run" actually hands schedules to trains,
 * so nothing touches a live railway until it's asked to.
 */
@EventBusSubscriber(modid = CreateTradeLogisticsOverhaul.ID)
public final class DispatchCommand {
    private DispatchCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ctlo")
                .requires(source -> source.hasPermission(2));

        root.then(Commands.literal("packages").executes(context -> {
            List<Dispatcher.Waiting> waiting = Dispatcher.waitingPackages();
            CommandSourceStack source = context.getSource();
            if (waiting.isEmpty()) {
                source.sendSuccess(() -> Component.literal("No packages waiting at any station postbox"), false);
                return 0;
            }
            source.sendSuccess(() -> Component.literal(waiting.size() + " package groups waiting:")
                    .withStyle(ChatFormatting.GOLD), false);
            for (Dispatcher.Waiting w : waiting) {
                source.sendSuccess(() -> Component.literal(
                        "  " + w.atStation() + " -> " + w.toAddress() + "  x" + w.count()), false);
            }
            return waiting.size();
        }));

        root.then(Commands.literal("dispatch")
                .executes(context -> report(context.getSource(), false))
                .then(Commands.literal("run").executes(context -> report(context.getSource(), true))));

        event.getDispatcher().register(root);
    }

    private static int report(CommandSourceStack source, boolean actuallyRun) {
        List<Dispatcher.Plan> plans = Dispatcher.plan();
        if (plans.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Nothing to dispatch: no packages are waiting"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(actuallyRun ? "Dispatching:" : "Would dispatch (dry run):")
                .withStyle(ChatFormatting.GOLD), false);
        int done = 0;
        for (Dispatcher.Plan plan : plans) {
            if (!plan.isPossible()) {
                source.sendSuccess(() -> Component.literal("  x " + plan.pickupStation() + " -> "
                        + plan.address() + ": " + plan.problem()).withStyle(ChatFormatting.RED), false);
                continue;
            }
            source.sendSuccess(() -> Component.literal("  " + plan.trainName() + ": "
                    + String.join(" > ", plan.stops())).withStyle(ChatFormatting.GREEN), false);
            if (actuallyRun && Dispatcher.assign(plan, source.registryAccess())) {
                done++;
            }
        }
        int assigned = done;
        if (actuallyRun) {
            source.sendSuccess(() -> Component.literal("Sent " + assigned + " train(s)"), true);
        } else {
            source.sendSuccess(() -> Component.literal("Run /ctlo dispatch run to actually send them"), false);
        }
        return plans.size();
    }
}
