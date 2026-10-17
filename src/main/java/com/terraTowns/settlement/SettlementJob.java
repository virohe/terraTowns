package com.terraTowns.settlement;

import com.terraTowns.structure.BuildingCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.VillagerProfession;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * The jobs a settlement's villagers can hold, each a group of trades (0.5.5, user's grouping
 * 2026-10-16). Which profession is in which job is decided by {@link ProfessionJobs} (tags, then
 * a guess for other mods' professions); which buildings open a job lives here.
 *
 * <ul>
 *   <li><b>Farmer</b> — farmer, beekeeper, rancher (0.5.6): Farms.</li>
 *   <li><b>Smith</b> — armorer, fletcher, leatherworker, toolsmith, weaponsmith: Smithy.</li>
 *   <li><b>Artisan</b> — butcher, cartographer, cleric, fisherman, librarian, shepherd: any of
 *       their buildings (Library, Temple, Fishery, Butcher's Shop, Loom Shed).</li>
 *   <li><b>Builder</b> — mason: Workshop.</li>
 *   <li><b>Guard</b> — Terra Towns' guard: Guard Post.</li>
 *   <li><b>Merchant</b> — the Merchant (0.6): Market Stall. No profession yet.</li>
 * </ul>
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

    FARMER("farmer", Set.of(BuildingCategory.FARM_PLOTS)),
    SMITH("smith", Set.of(BuildingCategory.SMITHY)),
    /** The trades that make goods but aren't farming or smithing (0.5.5). */
    ARTISAN("artisan", Set.of(BuildingCategory.LIBRARY, BuildingCategory.TEMPLE, BuildingCategory.FISHERY,
            BuildingCategory.BUTCHERS_SHOP, BuildingCategory.LOOM_SHED)),
    /** Raises the next building. A mason's Workshop since 0.5.5 (a Warehouse before). */
    BUILDER("builder", Set.of(BuildingCategory.WORKSHOP)),
    /**
     * Watches the walls. Vanilla has no guard, so Terra Towns adds the profession, worked at
     * the vanilla target block (TT-201).
     */
    GUARD("guard", Set.of(BuildingCategory.BARRACKS_GUARD_POST)),
    /**
     * Runs a Market Stall. Its villager, the Merchant, arrives in 0.6 (DESIGN.md §2.8); until
     * then the job opens with the stall but nobody can hold it. Butchers, leatherworkers and
     * cartographers held it until 0.5.5.
     */
    MERCHANT("merchant", Set.of(BuildingCategory.MARKET_STALL));

    private final String id;
    private final Set<BuildingCategory> buildings;

    SettlementJob(String id, Set<BuildingCategory> buildings) {
        this.id = id;
        this.buildings = buildings;
    }

    public String id() {
        return id;
    }

    /** The buildings, any one of which opens this job. */
    public Set<BuildingCategory> buildings() {
        return buildings;
    }

    /** The building named when the job is locked for want of one (the first, in type order). */
    public BuildingCategory unlockedBy() {
        for (BuildingCategory c : BuildingCategory.values()) {
            if (buildings.contains(c)) {
                return c;
            }
        }
        throw new IllegalStateException(id);
    }

    /** Does {@code settlement} have any building that opens this job? */
    public boolean hasBuilding(SettlementData settlement) {
        for (BuildingCategory c : buildings) {
            if (settlement.hasBuilding(c)) {
                return true;
            }
        }
        return false;
    }

    /** Professions whose holders belong to this job ({@link ProfessionJobs}). */
    public List<VillagerProfession> eligibleProfessions() {
        return ProfessionJobs.professionsOf(this);
    }

    /** @return true if a villager of {@code profession} belongs to this job. */
    public boolean accepts(VillagerProfession profession) {
        return ProfessionJobs.jobOf(profession) == this;
    }

    /**
     * The settlement tier this job opens at: its buildings' tier (see
     * {@code BuildingRegistry.tierAllows}). Merchants and guards wait for the village.
     */
    public boolean tierAllows(SettlementData settlement) {
        for (BuildingCategory c : buildings) {
            if (com.terraTowns.structure.BuildingRegistry.tierAllows(settlement, c)) {
                return true;
            }
        }
        return false;
    }

    /** @return true if some profession can hold this job (the Merchant can't until 0.6). */
    public boolean isStaffable() {
        return !eligibleProfessions().isEmpty();
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

    /** @return the job a villager of {@code profession} belongs to, or null. */
    @Nullable
    public static SettlementJob forProfession(VillagerProfession profession) {
        return ProfessionJobs.jobOf(profession);
    }
}
