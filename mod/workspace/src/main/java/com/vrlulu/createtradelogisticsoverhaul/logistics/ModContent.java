package com.vrlulu.createtradelogisticsoverhaul.logistics;

import com.vrlulu.createtradelogisticsoverhaul.CreateTradeLogisticsOverhaul;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/** Blocks, items and the creative tab. */
public final class ModContent {
    private static final String ID = CreateTradeLogisticsOverhaul.ID;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);

    public static final Supplier<Block> TERMINAL = BLOCKS.register("logistics_terminal",
            () -> new LogisticsTerminalBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(2.0f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.COPPER)));

    public static final Supplier<Item> TERMINAL_ITEM = ITEMS.register("logistics_terminal",
            () -> new BlockItem(TERMINAL.get(), new Item.Properties()));

    public static final Supplier<BlockEntityType<LogisticsTerminalBlockEntity>> TERMINAL_BE =
            BLOCK_ENTITIES.register("logistics_terminal", () -> BlockEntityType.Builder
                    .of(LogisticsTerminalBlockEntity::new, TERMINAL.get())
                    .build(null));

    public static final Supplier<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.literal("Create Trade Logistics"))
            .icon(() -> TERMINAL_ITEM.get().getDefaultInstance())
            .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
            .displayItems((params, output) -> output.accept(TERMINAL_ITEM.get()))
            .build());

    private ModContent() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }
}
