package com.terraTowns.npc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A rival trainer: the recurring opponent posted to whichever settlement its player is active
 * in, growing stronger as that player's influence spreads (see {@code RivalPool}).
 *
 * <p>Like the Professor and Gym Leader this extends {@link Villager} so it inherits settlement
 * AI and renders with the vanilla villager renderer — no new model or texture, which is why
 * three trainer NPCs have cost no art so far.</p>
 *
 * <p>The entity holds only <em>identity</em>: which player it belongs to, which rival record it
 * is, and the rank it was posted at. The authoritative record lives in {@code RivalPool}
 * ({@link net.minecraft.world.level.saveddata.SavedData}), so a rival that is killed, despawned
 * or lost to a chunk problem costs the player nothing — a fresh one is posted carrying the same
 * history. Entities are disposable; the rivalry is not.</p>
 */
public class RivalEntity extends Villager {

    private static final String TAG_OWNER = "TerraTownsRivalOwner";
    private static final String TAG_RIVAL_ID = "TerraTownsRivalId";
    private static final String TAG_RANK = "TerraTownsRivalRank";

    /** The player this rival belongs to. Null on a creative-spawned egg until assigned. */
    @Nullable
    private UUID ownerId;
    /** Stable identity of the rivalry, matching {@code RivalPool.Record#rivalId}. */
    @Nullable
    private UUID rivalId;
    /** Rank this rival was posted at, refreshed on each posting. */
    private int rank = 1;

    public RivalEntity(EntityType<? extends Villager> entityType, Level level) {
        super(entityType, level);
    }

    @Nullable
    public UUID ownerId() {
        return ownerId;
    }

    @Nullable
    public UUID rivalId() {
        return rivalId;
    }

    public int rank() {
        return rank;
    }

    /** Bind this entity to a player's rivalry at a given rank. */
    public void post(UUID ownerId, UUID rivalId, int rank) {
        this.ownerId = ownerId;
        this.rivalId = rivalId;
        this.rank = rank;
    }

    /** @return true if this rival belongs to {@code playerId}. */
    public boolean belongsTo(UUID playerId) {
        return ownerId != null && ownerId.equals(playerId);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerId != null) {
            tag.putUUID(TAG_OWNER, ownerId);
        }
        if (rivalId != null) {
            tag.putUUID(TAG_RIVAL_ID, rivalId);
        }
        tag.putInt(TAG_RANK, rank);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(TAG_OWNER)) {
            ownerId = tag.getUUID(TAG_OWNER);
        }
        if (tag.hasUUID(TAG_RIVAL_ID)) {
            rivalId = tag.getUUID(TAG_RIVAL_ID);
        }
        if (tag.contains(TAG_RANK)) {
            rank = tag.getInt(TAG_RANK);
        }
    }
}
