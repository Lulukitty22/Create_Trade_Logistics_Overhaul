package com.vrlulu.createtradelogisticsoverhaul.logistics;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The Logistics Terminal block: right-click it with a tuned stock link to attach it to that
 * network, then it appears on the logistics map.
 */
public class LogisticsTerminalBlock extends HorizontalDirectionalBlock implements net.minecraft.world.level.block.EntityBlock {
    public static final MapCodec<LogisticsTerminalBlock> CODEC = simpleCodec(LogisticsTerminalBlock::new);

    public LogisticsTerminalBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LogisticsTerminalBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof LogisticsTerminalBlockEntity terminal) {
            terminal.setOwner(player);
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof LogisticsTerminalBlockEntity terminal)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.getItem() instanceof LogisticallyLinkedBlockItem && LogisticallyLinkedBlockItem.isTuned(stack)) {
            UUID network = LogisticallyLinkedBlockItem.networkFromStack(stack);
            if (!level.isClientSide) {
                terminal.tuneTo(network);
                player.displayClientMessage(Component.literal("Terminal connected to that logistics network"), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof LogisticsTerminalBlockEntity terminal)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!terminal.isTuned()) {
            player.displayClientMessage(Component.literal(
                    "Not connected yet - right-click with a tuned Stock Link"), false);
            return InteractionResult.SUCCESS;
        }
        int kinds = terminal.stock().size();
        player.displayClientMessage(Component.literal(
                terminal.terminalName() + (terminal.address().isBlank() ? "" : " (" + terminal.address() + ")")
                        + ": " + kinds + " item types in stock"), false);
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return null;   // nothing to tick yet; stock is read on demand
    }
}
