package com.terraTowns.settlement;

import com.terraTowns.structure.BuildingCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.VillagerProfession;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * The staffed roles a settlement can fill: the five jobs on the 0.4 roadmap.
 *
 * <p><b>This enum is the single place every job policy decision lives.</b> Which building
 * unlocks a job, and which villagers are eligible to hold it, are both open design questions
 * (see {@code DESIGN.md} §4) — isolating them here means changing the answer is a one-file
 * edit, the same way {@code GymLeaderEntity.lockStrength} isolates gym scaling.</p>
 *
 * <p><b>Why appointment rather than gating.</b> The obvious reading of "job unlocks after gym
 * clear" is to stop villagers taking jobs until then. That was tried and rejected: vanilla
 * re-acquires a workstation within seconds of being demoted, so villagers visibly flicker
 * between employed and jobless on every scan, and a profession held without a claimed
 * workstation cannot restock trades. Instead a job is <em>staffed</em> — the settlement
 * appoints a villager who already practises a matching trade — which is additive, leaves
 * vanilla villager behaviour completely intact, and still gates the settlement-level benefit
 * behind the gym clear and the right building.</p>
 */
public enum SettlementJob {

    /** Works the fields. Unlocked by the farm plots every hamlet already ships with. */
    FARMER("farmer", BuildingCategory.FARM_PLOTS,
            List.of(VillagerProfession.FARMER, VillagerProfession.FISHERMAN,
                    VillagerProfession.SHEPHERD)),

    /** Keeps the town in tools and armour. */
    SMITH("smith", BuildingCategory.SMITHY,
            List.of(VillagerProfession.TOOLSMITH, VillagerProfession.WEAPONSMITH,
                    VillagerProfession.ARMORER)),

    /**
     * Watches the walls. Vanilla has no guard profession, so this job currently has no
     * eligible villagers and can be unlocked but not staffed — it is waiting on the custom
     * trainer NPCs in 1.5, and is deliberately listed with an empty eligibility set rather
     * than mapped onto an unrelated vanilla trade.
     */
    GUARD("guard", BuildingCategory.BARRACKS_GUARD_POST, List.of()),

    /** Runs the market stalls. */
    MERCHANT("merchant", BuildingCategory.MARKET_STALL,
            List.of(VillagerProfession.BUTCHER, VillagerProfession.LEATHERWORKER,
                    VillagerProfession.CARTOGRAPHER)),

    /** Raises the next building. Unlocked by the warehouse that stores its materials. */
    BUILDER("builder", BuildingCategory.WAREHOUSE,
            List.of(VillagerProfession.MASON, VillagerProfession.FLETCHER));

    private final String id;
    private final BuildingCategory unlockedBy;
    private final List<VillagerProfession> eligible;

    SettlementJob(String id, BuildingCategory unlockedBy, List<VillagerProfession> eligible) {
        this.id = id;
        this.unlockedBy = unlockedBy;
        this.eligible = eligible;
    }

    public String id() {
        return id;
    }

    /** The building whose registration unlocks this job. */
    public BuildingCategory unlockedBy() {
        return unlockedBy;
    }

    /** Vanilla professions whose holders may be appointed to this job. */
    public List<VillagerProfession> eligibleProfessions() {
        return eligible;
    }

    /** @return true if a villager of {@code profession} could hold this job. */
    public boolean accepts(VillagerProfession profession) {
        return eligible.contains(profession);
    }

    /**
     * The settlement tier this job opens at: its building's tier (see
     * {@code BuildingRegistry.tierAllows}). Merchants and guards wait for the village.
     */
    public boolean tierAllows(SettlementData settlement) {
        return com.terraTowns.structure.BuildingRegistry.tierAllows(settlement, unlockedBy);
    }

    /**
     * @return true if this job can ever be filled by a villager. {@link #GUARD} cannot yet —
     *         callers should surface it as "unlocked, awaiting trainer NPCs" rather than as a
     *         job the player has failed to staff.
     */
    public boolean isStaffable() {
        return !eligible.isEmpty();
    }

    public Component displayName() {
        return Component.translatable("job.terra_towns." + id);
    }

    /** @return the job with this {@link #id()}, or null if unknown. */
    @Nullable
    public static SettlementJob byId(String id) {
        for (SettlementJob j : values()) {
            if (j.id.equals(id)) {
                return j;
            }
        }
        return null;
    }

    /** @return the job a villager of {@code profession} could be appointed to, or null. */
    @Nullable
    public static SettlementJob forProfession(VillagerProfession profession) {
        for (SettlementJob j : values()) {
            if (j.accepts(profession)) {
                return j;
            }
        }
        return null;
    }

    /** Every job, as an immutable set — convenience for callers building EnumSets. */
    public static Set<SettlementJob> all() {
        return Set.of(values());
    }
}
