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
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes whether a settlement meets the milestones to grow to its next tier.
 *
 * <p>The tier ladder is Hamlet → Village → Town → City. Each transition has its own milestone
 * set; the <em>gym-leader challenge</em> is the action that actually performs the promotion (see
 * {@code GymChallengeEvents}), so "gym cleared" is not a milestone here — these are the conditions
 * that must hold for a winning challenge to result in a promotion.</p>
 *
 * <p><b>The ladder</b> (user's decisions, 2026-10-12; DESIGN.md §2.3). Each step asks for
 * something new: a hamlet buildings, since its few villagers can't staff every trade yet; a
 * village staffed workstations and buildings put up since it became one; a city (2.0) more of
 * everything.</p>
 * <ul>
 *   <li><b>Hamlet → Village:</b> Gym, Inn, Farm Plots, Smithy, Housing ×6; 10 villagers.</li>
 *   <li><b>Village → Town:</b> the village's buildings plus Pok&eacute;centre, Pok&eacute;mart,
 *       Market Stall, Warehouse and Guard Post; Housing ×10; 3 buildings registered since it became
 *       a village; working 3 farmers, 2 smiths, 1 guard, 1 merchant, 6 different trades in all;
 *       20 villagers.</li>
 *   <li><b>Town → City:</b> v2.0 — not promotable yet.</li>
 * </ul>
 * <p>Every step also needs 15% more beds than villagers ({@link #bedsNeeded}) and a rival win.</p>
 *
 * <p>Villagers and beds are counted on the settlement's ground ({@link SettlementData#covers}).</p>
 */
public final class SettlementPromotion {

    // Hamlet -> Village.
    public static final int VILLAGE_MIN_POPULATION = 10;
    public static final int VILLAGE_MIN_HOUSING = 6;
    private static final List<BuildingCategory> VILLAGE_BUILDINGS = List.of(
            BuildingCategory.GYM, BuildingCategory.INN_TAVERN, BuildingCategory.FARM_PLOTS, BuildingCategory.SMITHY);

    // Village -> Town.
    public static final int TOWN_MIN_POPULATION = 20;
    public static final int TOWN_MIN_HOUSING = 10;
    public static final int TOWN_NEW_BUILDINGS = 3;
    public static final int TOWN_DISTINCT_TRADES = 6;
    private static final List<BuildingCategory> TOWN_BUILDINGS = List.of(
            BuildingCategory.GYM, BuildingCategory.INN_TAVERN, BuildingCategory.FARM_PLOTS, BuildingCategory.SMITHY,
            BuildingCategory.POKECENTER, BuildingCategory.POKEMART, BuildingCategory.MARKET_STALL,
            BuildingCategory.STORAGE_CENTER, BuildingCategory.BARRACKS_GUARD_POST);
    /** Workers a town needs per job, in checklist order. */
    private static final List<Object[]> TOWN_WORKERS = List.of(
            new Object[]{SettlementJob.FARMER, 3}, new Object[]{SettlementJob.SMITH, 2},
            new Object[]{SettlementJob.GUARD, 1}, new Object[]{SettlementJob.MERCHANT, 1});

    /** Beds per villager: "always about 15% more beds than villagers" (user, 2026-10-12). */
    public static final double BEDS_PER_VILLAGER = 1.15;

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

    /**
     * Beds a step up needs: 15% over the larger of the step's minimum population and the
     * settlement's actual one, rounded up (12, 23 and 46 at the minimums). A settlement past the
     * minimum still has to bed everybody.
     */
    public static int bedsNeeded(int minPopulation, int population) {
        return (int) Math.ceil(Math.max(minPopulation, population) * BEDS_PER_VILLAGER - 1e-9);
    }

    public static Eval evaluate(ServerLevel level, SettlementData settlement) {
        return switch (settlement.tier()) {
            case HAMLET -> hamletToVillage(level, settlement);
            case VILLAGE -> villageToTown(level, settlement);
            default -> new Eval(null, List.of(), false); // TOWN/CITY: city is v2.0
        };
    }

    private static Eval hamletToVillage(ServerLevel level, SettlementData settlement) {
        // Re-survey first: the periodic re-check runs every 20 s, and a smithing table carried
        // from one room to the next would otherwise count for both until it caught up.
        BuildingRegistry.revalidate(level, settlement);
        List<Requirement> reqs = new ArrayList<>();
        for (BuildingCategory c : VILLAGE_BUILDINGS) {
            reqs.add(building(settlement, c));
        }
        reqs.add(buildingCount(settlement, BuildingCategory.HOUSING, VILLAGE_MIN_HOUSING));
        population(level, settlement, VILLAGE_MIN_POPULATION, reqs);
        reqs.add(rivalRequirement(settlement));
        return new Eval(SettlementTier.VILLAGE, reqs, allMet(reqs));
    }

    private static Eval villageToTown(ServerLevel level, SettlementData settlement) {
        BuildingRegistry.revalidate(level, settlement);
        List<Requirement> reqs = new ArrayList<>();
        for (BuildingCategory c : TOWN_BUILDINGS) {
            reqs.add(building(settlement, c));
        }
        reqs.add(buildingCount(settlement, BuildingCategory.HOUSING, TOWN_MIN_HOUSING));
        reqs.add(req("new_structures", settlement.buildingsRegisteredSince(SettlementTier.VILLAGE), TOWN_NEW_BUILDINGS));
        List<Villager> residents = residents(level, settlement);
        for (Object[] w : TOWN_WORKERS) {
            SettlementJob job = (SettlementJob) w[0];
            int need = (Integer) w[1];
            int have = (int) residents.stream().filter(v -> job.accepts(v.getVillagerData().getProfession())).count();
            reqs.add(new Requirement(Component.translatable("promo.terra_towns.req.workers." + job.id(), have, need),
                    have >= need));
        }
        reqs.add(req("trades", distinctTrades(residents), TOWN_DISTINCT_TRADES));
        population(level, settlement, TOWN_MIN_POPULATION, reqs);
        reqs.add(rivalRequirement(settlement));
        return new Eval(SettlementTier.TOWN, reqs, allMet(reqs));
    }

    /** The villager count and the beds for them. */
    private static void population(ServerLevel level, SettlementData settlement, int min, List<Requirement> reqs) {
        int population = residents(level, settlement).size();
        reqs.add(req("population", population, min));
        reqs.add(req("beds", countBeds(level, settlement), bedsNeeded(min, population)));
    }

    /** A building that has to be there: labelled with its name alone. */
    private static Requirement building(SettlementData settlement, BuildingCategory category) {
        return new Requirement(Component.translatable("promo.terra_towns.req.building",
                Component.translatable("building.terra_towns." + category.id())), settlement.hasBuilding(category));
    }

    /** A building needed several times over: "Housing: 4 / 6". */
    private static Requirement buildingCount(SettlementData settlement, BuildingCategory category, int need) {
        int have = settlement.buildingCount(category);
        return new Requirement(Component.translatable("promo.terra_towns.req.building_count",
                Component.translatable("building.terra_towns." + category.id()), have, need), have >= need);
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

    /**
     * A townsperson, as opposed to one of the mod's trainer NPCs. The Professor, rivals and the
     * gym-leader entity all extend {@link Villager} so they can reuse its AI and renderer, which
     * meant every one of them was being counted as population toward promotion.
     */
    public static boolean isResident(Villager v) {
        return !(v instanceof ProfessorEntity) && !(v instanceof RivalEntity)
                && !(v instanceof GymLeaderEntity);
    }

    /** Residents standing on the settlement's ground. */
    private static List<Villager> residents(ServerLevel level, SettlementData settlement) {
        return level.getEntitiesOfClass(Villager.class, settlement.area(),
                v -> isResident(v) && settlement.covers(v.blockPosition()));
    }

    /**
     * Different trades being worked: residents' professions, without NITWIT (it can never take a
     * job) and the gym leader (who runs the gym, not the economy).
     */
    private static int distinctTrades(List<Villager> residents) {
        Set<VillagerProfession> trades = new HashSet<>();
        for (Villager v : residents) {
            VillagerProfession p = v.getVillagerData().getProfession();
            if (p != VillagerProfession.NONE && p != VillagerProfession.NITWIT
                    && p != TerraTownsRegistries.GYM_LEADER_PROFESSION.get()) {
                trades.add(p);
            }
        }
        return trades.size();
    }

    /** Beds (their heads, as vanilla's HOME points of interest) on the settlement's ground. */
    private static int countBeds(ServerLevel level, SettlementData settlement) {
        BoundingBox fp = settlement.footprint();
        BlockPos from = fp == null ? settlement.center() : fp.getCenter();
        int reach = fp == null ? settlement.registrationRadius()
                : (int) Math.ceil(Math.hypot(fp.getXSpan(), fp.getZSpan()) / 2.0);
        return (int) level.getPoiManager().getInSquare(holder -> holder.is(PoiTypes.HOME), from, reach,
                        PoiManager.Occupancy.ANY)
                .filter(r -> settlement.covers(r.getPos()))
                .count();
    }
}
