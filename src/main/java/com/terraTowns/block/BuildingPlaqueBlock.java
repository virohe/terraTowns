package com.terraTowns.block;

import com.mojang.serialization.MapCodec;
import com.terraTowns.structure.BuildingRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SignApplicator;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * The Building Plaque: an oak wall sign that names the building it hangs in.
 *
 * <p>It is a real vanilla wall sign — same model, same renderer — and its text IS its state:
 * the sign reads the building type it registers, instead of a line of text on the HUD. It can
 * only go on a wall. Right-clicking it cycles through the building types the room actually
 * qualifies for (see {@link com.terraTowns.structure.BuildingSurvey}); a room with a loom and a
 * smithing table offers exactly Farm Plots and Smithy. Dye, glow ink and honeycomb work on it
 * like any sign; the text itself can't be edited by hand.</p>
 *
 * <p>Registration follows the plaque's whole lifetime: it is cleared by {@link #onRemove}, which
 * runs however the plaque goes — broken, burned, blown up, or popped off when its wall is
 * removed — not only when a player breaks it.</p>
 */
public class BuildingPlaqueBlock extends WallSignBlock {

    public static final MapCodec<WallSignBlock> CODEC = simpleCodec(BuildingPlaqueBlock::create);

    public BuildingPlaqueBlock(Properties properties) {
        super(WoodType.OAK, properties);
    }

    private static WallSignBlock create(Properties properties) {
        return new BuildingPlaqueBlock(properties);
    }

    @Override
    public MapCodec<WallSignBlock> codec() {
        return CODEC;
    }

    /**
     * Vanilla wall signs borrow their name from the STANDING sign item. This block's item is its
     * own, and that item names itself after the block — so the inherited version would recurse
     * forever and crash the first time anything asked. Name it directly.
     */
    @Override
    public String getDescriptionId() {
        return "block.terra_towns.building_plaque";
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BuildingPlaqueBlockEntity(pos, state);
    }

    /** Walls only: clicking a floor or ceiling places nothing, and it faces out of the clicked wall. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (context.getClickedFace().getAxis().isVertical()) {
            return null;
        }
        BlockState base = super.getStateForPlacement(context);
        if (base == null) {
            return null;
        }
        BlockState facing = base.setValue(FACING, context.getClickedFace());
        return facing.canSurvive(context.getLevel(), context.getClickedPos()) ? facing : null;
    }

    /** Sign items (dye, glow ink, honeycomb) work as on any sign; anything else cycles the type. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof SignApplicator) {
            return super.useItemOn(stack, state, level, pos, player, hand, hit);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** Right-click: move to the next building type this room qualifies for. Never opens the sign editor. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (level instanceof ServerLevel serverLevel) {
            BuildingRegistry.refreshPlaque(serverLevel, pos, player, true);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            BuildingRegistry.onPlaqueRemoved(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
