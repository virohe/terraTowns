package com.terraTowns.block;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * The Merchant's counter (0.5.4): the workstation that makes a Market Stall, and since 0.6 the
 * Merchant's job site (DESIGN.md §2.8, {@code merchant.Merchants}).
 * Shaped and textured as an iron stair until its own model exists (user, 2026-10-16).
 */
public class MarketCounterBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<MarketCounterBlock> CODEC = simpleCodec(MarketCounterBlock::new);

    /** A stair: a bottom slab, and a top half along the back (the side it faces away from). */
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        VoxelShape bottom = Block.box(0, 0, 0, 16, 8, 16);
        SHAPES.put(Direction.NORTH, Shapes.or(bottom, Block.box(0, 8, 8, 16, 16, 16)));
        SHAPES.put(Direction.SOUTH, Shapes.or(bottom, Block.box(0, 8, 0, 16, 16, 8)));
        SHAPES.put(Direction.WEST, Shapes.or(bottom, Block.box(8, 8, 0, 16, 16, 16)));
        SHAPES.put(Direction.EAST, Shapes.or(bottom, Block.box(0, 8, 0, 8, 16, 16)));
    }

    public MarketCounterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** The low step faces the player who placed it, as a stair's does. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /**
     * The stall screen: what the merchant who works this counter has on sale, at what price, and
     * its emeralds. Only here, and only for the settlement's owner (user, 2026-11-05).
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level instanceof ServerLevel server && player instanceof ServerPlayer sp) {
            com.terraTowns.event.MerchantEvents.openStall(server, sp, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }
}
