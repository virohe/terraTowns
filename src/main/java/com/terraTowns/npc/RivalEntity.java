package com.terraTowns.npc;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
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
    private static final String TAG_RETIRED = "TerraTownsRivalRetired";
    /** Synced: the client names a retired rival as a plain villager (Jade and the like read it). */
    private static final EntityDataAccessor<Boolean> DATA_RETIRED =
            SynchedEntityData.defineId(RivalEntity.class, EntityDataSerializers.BOOLEAN);

    /** The player this rival belongs to. Null on a creative-spawned egg until assigned. */
    @Nullable
    private UUID ownerId;
    /** Stable identity of the rivalry, matching {@code RivalPool.Record#rivalId}. */
    @Nullable
    private UUID rivalId;
    /** Rank this rival was posted at, refreshed on each posting. */
    private int rank = 1;
    /**
     * Beaten and settled down: an ordinary villager from then on, in the village it was beaten
     * in. A playtest rival had taken a job there, and vanishing on defeat took the job with it
     * (user, 2026-11-05). Kept in {@link #DATA_RETIRED}.
     */

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

    /** @return true if this rival belongs to {@code playerId} and is still their rival. */
    public boolean belongsTo(UUID playerId) {
        return !retired() && ownerId != null && ownerId.equals(playerId);
    }

    public boolean retired() {
        return entityData.get(DATA_RETIRED);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_RETIRED, false);
    }

    /**
     * A retired rival is named as any villager is ("Villager", "Farmer"): under its own entity
     * type it read "Rival Trainer" in Jade, and a player took it for a rival that wouldn't fight
     * (playtest, 2026-11-05).
     */
    @Override
    protected Component getTypeName() {
        if (!retired()) {
            return super.getTypeName();
        }
        ResourceLocation prof = BuiltInRegistries.VILLAGER_PROFESSION.getKey(getVillagerData().getProfession());
        return Component.translatable(EntityType.VILLAGER.getDescriptionId() + '.'
                + (!"minecraft".equals(prof.getNamespace()) ? prof.getNamespace() + '.' : "") + prof.getPath());
    }

    /**
     * Beaten: stays where it is as a villager of the settlement, keeping its job and trades,
     * and stops being anyone's rival. The rivalry itself lives in {@code RivalPool}, so the
     * player's next rival is a fresh posting at the higher rank.
     */
    public void retire() {
        entityData.set(DATA_RETIRED, true);
        setCustomName(null);
        setCustomNameVisible(false);
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
        tag.putBoolean(TAG_RETIRED, retired());
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
        entityData.set(DATA_RETIRED, tag.getBoolean(TAG_RETIRED));
    }
}
