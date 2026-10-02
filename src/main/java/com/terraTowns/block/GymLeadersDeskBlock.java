package com.terraTowns.block;

import com.terraTowns.settlement.GymDesk;
import com.mojang.serialization.MapCodec;
import com.terraTowns.network.TerraTownsNetwork;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * The <b>Gym Leader's Desk</b> — the settlement-authoring block. Right-clicking it opens a
 * custom {@link com.terraTowns.client.screen.GymLeadersDeskScreen} where the gym leader (or
 * any player with influence) names the settlement, its sections, and configures its banner.
 *
 * <p>Like an enchanting table it is a {@link BaseEntityBlock} with a live cube model. It owns
 * no settlement data itself: on interaction the server resolves the bound
 * {@link SettlementData} (via the {@link GymLeadersDeskBlockEntity}'s recorded id, falling
 * back to the nearest settlement) and streams it to the opening player, who edits it through
 * the network packets in {@link com.terraTowns.network}. The screen is a plain
 * {@code Screen} with no inventory, so the block does not open a menu — it just sends the
 * sync packet and the client opens the screen on receipt.</p>
 */
public class GymLeadersDeskBlock extends BaseEntityBlock {

    public static final MapCodec<GymLeadersDeskBlock> CODEC = simpleCodec(GymLeadersDeskBlock::new);

    /** Horizontal facing — the desk faces the player when placed, like a furnace/lectern. */
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public GymLeadersDeskBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Face the same way the player is looking (180° from the furnace-style "face the player"),
        // which orients this model's front correctly toward the placer.
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GymLeadersDeskBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // BaseEntityBlock defaults to INVISIBLE; the desk uses a normal cube model.
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        // Bind the desk to the settlement whose radius actually contains it (not merely the
        // nearest one — an unbounded "nearest" would bind a desk placed anywhere on the map to
        // the only/closest settlement). Left unbound if the desk is outside every settlement.
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof GymLeadersDeskBlockEntity desk) {
            SettlementData settlement = SettlementManager.get(serverLevel).containing(pos);
            if (settlement != null) {
                desk.setSettlementId(settlement.id());
                // One desk per settlement, and the bound gym leader gets it first (GymDesk).
                GymDesk.onPlaced(serverLevel, pos, settlement);
                SettlementManager.get(serverLevel).setDirty();
            }
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        SettlementManager manager = SettlementManager.get(serverLevel);
        SettlementData data = null;
        if (level.getBlockEntity(pos) instanceof GymLeadersDeskBlockEntity desk) {
            if (desk.getSettlementId() != null) {
                data = manager.byId(desk.getSettlementId());
            }
            if (data == null) {
                // Late binding: no id yet (or the old one is gone) — bind to the settlement whose
                // radius contains this desk. Stays unbound (and shows the message below) if there
                // is no settlement in range, so a desk in the wild no longer edits a far hamlet.
                data = manager.containing(pos);
                if (data != null) {
                    desk.setSettlementId(data.id());
                }
            }
        }

        if (data == null) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.terra_towns.desk.no_settlement"), true);
            return InteractionResult.CONSUME;
        }

        // The desk is locked until its gym leader has been defeated (see GymChallengeEvents).
        if (!data.isGymCleared()) {
            serverPlayer.displayClientMessage(Component.translatable(data.gymLeaderId() == null
                    ? "message.terra_towns.desk.locked_no_leader"
                    : "message.terra_towns.desk.locked"), true);
            return InteractionResult.CONSUME;
        }

        // Stream the settlement + its promotion checklist; the client opens the desk screen.
        TerraTownsNetwork.sendDeskSync(serverPlayer, serverLevel, data);
        return InteractionResult.CONSUME;
    }
}
