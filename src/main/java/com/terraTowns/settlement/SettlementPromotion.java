package com.terraTowns.settlement;

import com.terraTowns.structure.BuildingRegistry;
import com.terraTowns.gym.GymLeaderEntity;
import com.terraTowns.npc.ProfessorEntity;
import com.terraTowns.npc.RivalEntity;
import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.structure.BuildingCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes whether a settlement meets the milestones to grow to its next tier.
 *
 * <p>The tier ladder is Hamlet → Village → Town → City. Each transition has its own milestone
 * set; the <em>gym-leader challenge</em> is the action that actually performs the promotion (see
 * {@code GymChallengeEvents}), so "gym cleared" is not a milestone here — these are the conditions
 * that must hold for a winning challenge to result in a promotion.</p>
 *
 * <ul>
 *   <li><b>Hamlet → Village:</b> ≥ 10 villagers, ≥ 12 beds, ≥ 7 villagers with a profession.</li>
 *   <li><b>Village → Town:</b> every town-required {@link BuildingCategory} present + ≥ 20 beds
 *       (housing); farms are covered by the {@code FARM_PLOTS} category.</li>
 *   <li><b>Town → City:</b> deferred to v2.0 — not promotable in 0.3.</li>
 * </ul>
 */
public final class SettlementPromotion {

    // Hamlet -> Village milestones.
    public static final int VILLAGE_MIN_POPULATION = 10;
    public static final int VILLAGE_MIN_BEDS = 12;
    public static final int VILLAGE_MIN_JOBS = 7;

    // Village -> Town housing bar (buildings come from BuildingCategory#requiredForTown).
    public static final int TOWN_MIN_BEDS = 20;

    private SettlementPromotion() {
    }

    /** A single promotion requirement line: a display label and whether it is currently met. */
    public record Requirement(Component label, boolean met) {
    }

    /**
     * The result of evaluating a settlement's promotion readiness.
     *
     * @param to           the tier it would promote to, or null if the current tier can't promote
     * @param requirements the per-condition checklist (empty when not promotable)
     * @param allMet       true if {@code to != null} and every requirement is met
     */
    public record Eval(@Nullable SettlementTier to, List<Requirement> requirements, boolean allMet) {
    }

    public static Eval evaluate(ServerLevel level, SettlementData settlement) {
        return switch (settlement.tier()) {
            case HAMLET -> hamletToVillage(level, settlement);
            case VILLAGE -> villageToTown(level, settlement);
            default -> new Eval(null, List.of(), false); // TOWN/CITY: not promotable in 0.3
        };
    }

    private static Eval hamletToVillage(ServerLevel level, SettlementData settlement) {
        int population = countVillagers(level, settlement);
        int jobs = countProfessionedVillagers(level, settlement);
        int beds = countBeds(level, settlement);

        List<Requirement> reqs = new ArrayList<>();
        reqs.add(req("population", population, VILLAGE_MIN_POPULATION));
        reqs.add(req("beds", beds, VILLAGE_MIN_BEDS));
        reqs.add(req("jobs", jobs, VILLAGE_MIN_JOBS));
        // Every settlement will host a gym, so the gym is part of every step up — registered
        // with a plaque on a building holding the Gym Leader's Desk.
        BuildingRegistry.revalidate(level, settlement);
        reqs.add(new Requirement(Component.translatable("promo.terra_towns.req.building",
                Component.translatable("building.terra_towns." + BuildingCategory.GYM.id())),
                settlement.hasBuilding(BuildingCategory.GYM)));
        reqs.add(rivalRequirement(settlement));
        return new Eval(SettlementTier.VILLAGE, reqs, allMet(reqs));
    }

    private static Eval villageToTown(ServerLevel level, SettlementData settlement) {
        // Re-survey first: the periodic re-check runs every 20 s, and a smithing table carried
        // from one room to the next would otherwise count for both until it caught up.
        com.terraTowns.structure.BuildingRegistry.revalidate(level, settlement);
        List<Requirement> reqs = new ArrayList<>();
        for (BuildingCategory category : BuildingCategory.values()) {
            if (category.requiredForTown()) {
                boolean present = settlement.hasBuilding(category);
                reqs.add(new Requirement(Component.translatable("promo.terra_towns.req.building",
                        Component.translatable("building.terra_towns." + category.id())), present));
            }
        }
        int beds = countBeds(level, settlement);
        reqs.add(new Requirement(Component.translatable("promo.terra_towns.req.housing", beds, TOWN_MIN_BEDS),
                beds >= TOWN_MIN_BEDS));
        reqs.add(rivalRequirement(settlement));
        return new Eval(SettlementTier.TOWN, reqs, allMet(reqs));
    }

    /**
     * Beat your rival here, once per tier. The rival only shows up in a settlement the player
     * holds influence over (won by beating its gym leader), so this also means the town's own
     * trainer has to have stood up for it at every step.
     */
    private static Requirement rivalRequirement(SettlementData settlement) {
        return new Requirement(Component.translatable("promo.terra_towns.req.rival"),
                settlement.rivalBeatenThisTier());
    }

    private static Requirement req(String key, int current, int required) {
        return new Requirement(
                Component.translatable("promo.terra_towns.req." + key, current, required),
                current >= required);
    }

    private static boolean allMet(List<Requirement> reqs) {
        return reqs.stream().allMatch(Requirement::met);
    }

    // --- counting helpers --------------------------------------------------

    private static AABB radiusBox(SettlementData settlement) {
        double r = settlement.registrationRadius();
        return AABB.ofSize(Vec3.atCenterOf(settlement.center()), 2.0 * r, 2.0 * r, 2.0 * r);
    }

    /**
     * A townsperson, as opposed to one of the mod's trainer NPCs. The Professor, rivals and the
     * gym-leader entity all extend {@link Villager} so they can reuse its AI and renderer, which
     * meant every one of them was being counted as population toward promotion.
     */
    public static boolean isResident(Villager v) {
        return !(v instanceof ProfessorEntity) && !(v instanceof RivalEntity)
                && !(v instanceof GymLeaderEntity);
    }

    private static int countVillagers(ServerLevel level, SettlementData settlement) {
        return level.getEntitiesOfClass(Villager.class, radiusBox(settlement),
                SettlementPromotion::isResident).size();
    }

    /**
     * Residents holding a working profession. Excludes NITWIT (a profession value, but one that
     * can never take a job) and the gym leader (who runs the gym, not the economy) -- both used
     * to count toward "villagers with jobs".
     */
    private static int countProfessionedVillagers(ServerLevel level, SettlementData settlement) {
        return (int) level.getEntitiesOfClass(Villager.class, radiusBox(settlement),
                        SettlementPromotion::isResident).stream()
                .map(v -> v.getVillagerData().getProfession())
                .filter(p -> p != VillagerProfession.NONE && p != VillagerProfession.NITWIT
                        && p != TerraTownsRegistries.GYM_LEADER_PROFESSION.get())
                .count();
    }

    private static int countBeds(ServerLevel level, SettlementData settlement) {
        BlockPos center = settlement.center();
        return (int) level.getPoiManager().getCountInRange(
                holder -> holder.is(PoiTypes.HOME), center, settlement.registrationRadius(),
                PoiManager.Occupancy.ANY);
    }
}
