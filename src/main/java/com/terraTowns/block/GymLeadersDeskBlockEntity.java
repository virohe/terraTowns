package com.terraTowns.block;

import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Block entity for the {@link GymLeadersDeskBlock}.
 *
 * <p>The desk holds <b>no settlement state itself</b> — the authoritative
 * {@link com.terraTowns.settlement.SettlementData} lives in the server-side
 * {@link com.terraTowns.settlement.SettlementManager}. All this entity stores is the
 * {@link #settlementId} of the settlement it belongs to, resolved to the nearest
 * settlement when the desk is placed (see {@link GymLeadersDeskBlock#setPlacedBy}). On
 * interaction the block looks the settlement up by this id and streams it to the opening
 * player, who edits it through packets rather than through this block entity.</p>
 */
public class GymLeadersDeskBlockEntity extends BlockEntity {

    @Nullable
    private UUID settlementId;

    public GymLeadersDeskBlockEntity(BlockPos pos, BlockState state) {
        super(TerraTownsRegistries.GYM_LEADERS_DESK_BE.get(), pos, state);
    }

    @Nullable
    public UUID getSettlementId() {
        return settlementId;
    }

    public void setSettlementId(@Nullable UUID settlementId) {
        this.settlementId = settlementId;
        setChanged();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        settlementId = tag.contains("SettlementId") ? tag.getUUID("SettlementId") : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        if (settlementId != null) {
            tag.putUUID("SettlementId", settlementId);
        }
    }
}
