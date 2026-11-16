package com.terraTowns.block;

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

/**
 * The Rancher's manger (TT-218): its workstation, and what makes an animal pen a Farm. Shaped and
 * modelled as an anvil until its own model exists (user, 2026-11-05; the bell was the first plan),
 * so it faces and collides as an anvil does.
 */
public class MangerBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<MangerBlock> CODEC = simpleCodec(MangerBlock::new);

    // Vanilla's anvil, piece for piece.
    private static final VoxelShape BASE = Block.box(2.0, 0.0, 2.0, 14.0, 4.0, 14.0);
    private static final VoxelShape X_AXIS = Shapes.or(BASE, Block.box(3.0, 4.0, 4.0, 13.0, 5.0, 12.0),
            Block.box(4.0, 5.0, 6.0, 12.0, 10.0, 10.0), Block.box(0.0, 10.0, 3.0, 16.0, 16.0, 13.0));
    private static final VoxelShape Z_AXIS = Shapes.or(BASE, Block.box(4.0, 4.0, 3.0, 12.0, 5.0, 13.0),
            Block.box(6.0, 5.0, 4.0, 10.0, 10.0, 12.0), Block.box(3.0, 10.0, 0.0, 13.0, 16.0, 16.0));

    public MangerBlock(Properties properties) {
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

    /** Lengthwise across the player's view, as an anvil is placed. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getClockWise());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? X_AXIS : Z_AXIS;
    }
}
