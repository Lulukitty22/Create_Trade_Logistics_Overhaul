package com.vrlulu.createtradelogisticsoverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Opens the terminal window. Kept apart from the block so the block never mentions a client class:
 * this one is only ever loaded on the client side.
 */
public final class TerminalScreenOpener {
    private TerminalScreenOpener() {
    }

    public static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new TerminalScreen(pos));
    }
}
