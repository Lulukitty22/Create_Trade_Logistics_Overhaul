package com.vrlulu.createtradelogisticsoverhaul.logistics;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
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

/**
 * The Logistics Terminal block: right-click it with a tuned stock link to attach it to that
 * network, then it appears on the logistics map.
 */
public class LogisticsTerminalBlock extends HorizontalDirectionalBlock
        implements IBE<LogisticsTerminalBlockEntity> {
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

    // Create's LogisticallyLinkedBlockItem casts the block it is placing to IBE when it tunes a
    // stack, so a linked block that does not implement this simply cannot be bound.
    @Override
    public Class<LogisticsTerminalBlockEntity> getBlockEntityClass() {
        return LogisticsTerminalBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends LogisticsTerminalBlockEntity> getBlockEntityType() {
        return ModContent.TERMINAL_BE.get();
    }

    /** SmartBlockEntity needs telling when it is destroyed rather than merely unloaded. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        IBE.onRemove(state, level, pos, newState);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof LogisticsTerminalBlockEntity terminal)) {
            return;
        }
        if (placer instanceof Player player) {
            terminal.setOwner(player);
        }
        // Placed from a stack that was already tuned (the Redstone Requester works the same way):
        // carry that network over, so no second step is needed.
        if (LogisticallyLinkedBlockItem.isTuned(stack)) {
            terminal.tuneTo(LogisticallyLinkedBlockItem.networkFromStack(stack));
        }
    }

    /** Empty hand: open the terminal's window. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof LogisticsTerminalBlockEntity terminal)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        // No permission check here: the block entity's settings never travel to the client, so a
        // check on this side would read defaults and could lock the owner out of their own terminal.
        // The window shows the access level the server sent with the terminal list, and the server
        // checks again on every save.
        com.vrlulu.createtradelogisticsoverhaul.client.TerminalScreenOpener.open(pos);
        return InteractionResult.SUCCESS;
    }

}
