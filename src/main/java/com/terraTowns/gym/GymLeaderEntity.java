package com.terraTowns.gym;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The gym leader NPC bound to a settlement's gym.
 *
 * <p><b>Entity-model decision:</b> gym leaders (and the Professor) extend
 * {@link Villager} rather than being wholly custom mobs. Reusing Villager gives us
 * vanilla pathing, settlement POI affinity, panic/AI behaviour and the existing
 * villager-overhaul surface for free, which matters because the design turns ordinary
 * villagers into trainers — a gym leader is "just" a special trainer. Cobblemon battle
 * behaviour and GeckoLib animation are layered on top (TODO, feature-gated), not baked
 * into the entity hierarchy.</p>
 *
 * <p><b>Strength locking:</b> a gym leader's difficulty is frozen the first time it is
 * challenged so that grinding badges elsewhere doesn't retroactively trivialise it. The
 * open question of <i>badge-count scaling vs. Pokemon-level scaling</i> is isolated in
 * {@link #lockStrength(int)} / {@link #challengeRating()} so the policy can be swapped
 * without touching the rest of the system.</p>
 */
public class GymLeaderEntity extends Villager {

    /** Difficulty tier frozen at first challenge (the challenger's badge count). */
    private int strengthTier;

    /** True once {@link #lockStrength(int)} has frozen this leader's difficulty. */
    private boolean strengthLocked;

    /** The settlement this leader belongs to (mirrors {@code SettlementData.gymLeaderId}). */
    @Nullable
    private UUID settlementId;

    /** Biome-derived specialisation (e.g. FIRE in volcanic/desert, WATER near coast). */
    @Nullable
    private GymType gymType;

    public GymLeaderEntity(EntityType<? extends Villager> entityType, Level level) {
        super(entityType, level);
    }

    public boolean isStrengthLocked() {
        return strengthLocked;
    }

    /** Freeze difficulty at first challenge. No-op if already locked. */
    public void lockStrength(int badgeCount) {
        if (!strengthLocked) {
            strengthTier = Math.max(0, badgeCount);
            strengthLocked = true;
        }
    }

    public int strengthTier() {
        return strengthTier;
    }

    /**
     * @return the effective challenge rating used to build the leader's Cobblemon team.
     *         Currently a direct function of the locked strength tier; swap the body to
     *         move to level-based scaling.
     */
    public int challengeRating() {
        return Math.max(0, strengthTier);
    }

    @Nullable
    public UUID settlementId() {
        return settlementId;
    }

    public void setSettlementId(@Nullable UUID settlementId) {
        this.settlementId = settlementId;
    }

    @Nullable
    public GymType gymType() {
        return gymType;
    }

    public void setGymType(@Nullable GymType gymType) {
        this.gymType = gymType;
    }

    // TODO(0.1): onChallenge(player) -> lockStrength(badgeCount via CobblemonHook), start battle
    // TODO(0.1): onDefeated(player) -> grant influence, mark SettlementData.gymCleared, unlock desk
}
