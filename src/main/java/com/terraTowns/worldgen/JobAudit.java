package com.terraTowns.worldgen;

import com.terraTowns.influence.InfluenceTracker;
import com.terraTowns.rival.RivalPool;
import com.terraTowns.settlement.JobBoard;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementJob;
import com.terraTowns.settlement.SettlementManager;
import com.terraTowns.structure.BuildingCategory;
import com.terraTowns.structure.BuildingRegistry;
import com.terraTowns.npc.RivalEntity;
import com.terraTowns.rival.RivalPosting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import com.terraTowns.block.BuildingPlaqueBlockEntity;
import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import com.terraTowns.settlement.SettlementTier;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Development-only end-to-end exercise of the 0.4 progression systems — jobs and rivals — run
 * by the headless harness.
 *
 * <p><b>This mutates settlements</b> — it clears their gyms, registers every building and
 * forces professions onto villagers — so it is gated behind its own system property
 * ({@code terratowns.jobAuditMutate}) rather than sharing {@link HamletAudit}'s, and that
 * property is set by exactly one thing: the {@code hamletTest} Gradle run, whose world is
 * disposable. Never set it against a world anyone cares about.</p>
 *
 * <p>What it proves, which reading the code cannot: that an unlock actually fires once both
 * conditions are met, that a villager practising a matching trade is actually appointed, and
 * that {@link SettlementJob#GUARD} reports as awaiting-NPC rather than as a failure. It does
 * NOT cover the NBT round trip — the harness halts the server immediately afterwards and never
 * reloads the save.</p>
 */
public final class JobAudit {

    private static final String MUTATE_PROPERTY = "terratowns.jobAuditMutate";
    /** True when the dev job harness will run (see {@link #MUTATE_PROPERTY}). */
    public static final boolean ENABLED = Boolean.getBoolean(MUTATE_PROPERTY);

    private JobAudit() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(MUTATE_PROPERTY);
    }

    /**
     * Run the exercise across every settlement and return a JSON-ready report.
     *
     * <p>Staged deliberately: the board is sampled with nothing done, then after the gym is
     * cleared, then after the buildings are registered, then after a villager is given a
     * matching trade — so a regression points at which condition stopped working instead of
     * just "no jobs".</p>
     */
    static Map<String, Object> run(ServerLevel level) {
        Map<String, Object> report = new LinkedHashMap<>();
        SettlementManager manager = SettlementManager.get(level);
        List<Map<String, Object>> rows = new ArrayList<>();

        // Runs first, while no settlement has any legacy registrations for it to hide behind.
        report.put("plaques", plaques(level, manager));
        report.put("gymDesk", gymDesk(level, manager));
        report.put("hamletAssign", hamletAssign(manager));
        report.put("treeFelling", treeFelling(level, manager));
        report.put("stonePath", stonePath(level, manager));

        for (SettlementData settlement : manager.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("settlement", settlement.name());

            // Stage 0: untouched. Everything should be locked behind the gym.
            JobBoard.refresh(level, settlement);
            row.put("beforeAnything", summarise(settlement));

            // Stage 1: gym cleared, still no buildings registered.
            settlement.setGymCleared(true);
            JobBoard.refresh(level, settlement);
            row.put("afterGymCleared", summarise(settlement));

            // Stage 2: every building registered — all five jobs should unlock.
            for (BuildingCategory c : BuildingCategory.values()) {
                settlement.registerBuilding(c);
            }
            JobBoard.refresh(level, settlement);
            row.put("afterBuildings", summarise(settlement));
            // Merchants and guards open at village tier: locked in a hamlet, open in a village.
            row.put("tierGatedInHamlet", summarise(settlement).contains("merchant=LOCKED_TIER")
                    && summarise(settlement).contains("guard=LOCKED_TIER"));
            SettlementTier tierBefore = settlement.tier();
            settlement.setTier(SettlementTier.VILLAGE);
            JobBoard.refresh(level, settlement);
            row.put("openInVillage", !summarise(settlement).contains("LOCKED"));
            settlement.setTier(tierBefore);
            JobBoard.refresh(level, settlement);

            // Stage 3: give one villager a farmer's trade and see if it gets appointed. Uses
            // the unguarded staffing call: no player has joined yet, so nothing is "entity
            // ticking" and the guarded one would (correctly) refuse to touch anything.
            row.put("villagersInRange", giveOneVillagerATrade(level, settlement));
            JobBoard.refresh(level, settlement);
            JobBoard.restaffUnchecked(level, settlement);
            row.put("afterTrade", summarise(settlement));
            row.put("farmerHolder", String.valueOf(settlement.jobHolder(SettlementJob.FARMER)));

            // Stage 4: the guarded call on a settlement nobody is near must keep the holder it
            // has. Before 0.4.4 it dropped every holder the moment the player walked away.
            java.util.UUID before = settlement.jobHolder(SettlementJob.FARMER);
            JobBoard.restaff(level, settlement);
            row.put("holderSurvivesUnloaded", before != null
                    && before.equals(settlement.jobHolder(SettlementJob.FARMER)));
            rows.add(row);
        }

        report.put("guard", guard(level, manager));
        manager.setDirty();
        report.put("settlements", rows);
        report.put("summary", verdict(rows));
        report.put("rivals", rivals(level, manager));
        Map<String, Object> recipes = new LinkedHashMap<>();
        for (String id : new String[]{"gym_leaders_desk", "building_plaque", "route_marker"}) {
            recipes.put(id, level.getServer().getRecipeManager()
                    .byKey(ResourceLocation.fromNamespaceAndPath("terra_towns", id)).isPresent());
        }
        report.put("recipesLoaded", recipes);
        Map<String, Object> stations = new LinkedHashMap<>();
        for (SettlementJob job : SettlementJob.values()) {
            stations.put(job.id(), JobBoard.workstations(level, job).stream()
                    .map(st -> String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem())))
                    .toList());
        }
        report.put("workstationsByJob", stations);
        return report;
    }

    /**
     * The plaque rules, end to end, on real blocks in the sky over the first hamlet: a sealed
     * room with a loom and a smithing table offers exactly Farm Plots and Smithy; the sign names
     * the type; only that type's job is open; a building takes ONE plaque (the first keeps it);
     * a plaque on the outside wall surveys the room behind; a workstation set into a shared wall
     * belongs to one building; outdoor plaques can't see through walls and can't crowd one spot;
     * taking workstations away un-registers, before the promotion checklist is read.
     */
    private static Map<String, Object> plaques(ServerLevel level, SettlementManager manager) {
        Map<String, Object> out = new LinkedHashMap<>();
        SettlementData s = manager.all().iterator().next();
        s.setGymCleared(true);
        BlockPos o = s.center().above(40);
        BlockState plaqueState = TerraTownsRegistries.BUILDING_PLAQUE.get().defaultBlockState();

        shell(level, o, o.offset(6, 4, 6));
        BlockPos loom = o.offset(4, 1, 1);
        BlockPos table = o.offset(4, 1, 2);
        level.setBlock(loom, Blocks.LOOM.defaultBlockState(), 3);
        level.setBlock(table, Blocks.SMITHING_TABLE.defaultBlockState(), 3);

        // 1. Cycling offers exactly what the workstations allow; the sign and the job board agree.
        BlockPos inside = hang(level, plaqueState, o.offset(1, 2, 3), Direction.EAST);
        java.util.Set<String> offered = new java.util.TreeSet<>();
        boolean onlyCurrentJobOpen = true;
        boolean signMatches = true;
        for (int click = 0; click < 4; click++) {
            BuildingCategory current = s.plaqueAt(inside);
            offered.add(current == null ? "none" : current.id());
            JobBoard.refresh(level, s);
            for (JobBoard.JobStatus st : JobBoard.board(s).values()) {
                boolean open = st.status() == JobBoard.Status.STAFFED || st.status() == JobBoard.Status.UNSTAFFED
                        || st.status() == JobBoard.Status.AWAITING_NPC;
                if (open != (st.job().unlockedBy() == current)) {
                    onlyCurrentJobOpen = false;
                }
            }
            signMatches &= signSays(level, inside, current == null ? "none" : "building." + current.id());
            BuildingRegistry.refreshPlaque(level, inside, null, true);
        }
        out.put("offeredWhileCycling", offered);

        // 2. One plaque per building: a second (outside wall, same room) and a third are refused.
        BlockPos outside = hang(level, plaqueState, o.offset(-1, 2, 3), Direction.WEST);
        BlockPos third = hang(level, plaqueState, o.offset(3, 2, 5), Direction.NORTH);
        boolean secondRefused = s.plaqueAt(outside) == null && signSays(level, outside, "plaque.already");
        boolean thirdRefused = s.plaqueAt(third) == null && signSays(level, third, "plaque.already");

        // 3. With the inside plaque gone, the outside one surveys the room behind its wall...
        level.setBlock(inside, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(third, Blocks.AIR.defaultBlockState(), 3);
        BuildingRegistry.refreshPlaque(level, outside, null, false);
        BuildingCategory outsideNames = s.plaqueAt(outside);
        out.put("outsidePlaqueNames", outsideNames == null ? "none" : outsideNames.id());
        // ...and now it is the first, so a new inside plaque is the one refused.
        inside = hang(level, plaqueState, o.offset(1, 2, 3), Direction.EAST);
        boolean firstKeepsIt = s.plaqueAt(inside) == null && s.plaqueAt(outside) == outsideNames;
        level.setBlock(inside, Blocks.AIR.defaultBlockState(), 3);

        // 4. A grindstone set into the wall shared with a second room belongs to the first building.
        shell(level, o.offset(6, 0, 0), o.offset(12, 4, 6));
        BlockPos wallStation = o.offset(6, 2, 1);
        level.setBlock(wallStation, Blocks.GRINDSTONE.defaultBlockState(), 3);
        BuildingRegistry.refreshPlaque(level, outside, null, false); // re-survey: it now claims the grindstone
        BlockPos roomB = hang(level, plaqueState, o.offset(11, 2, 3), Direction.WEST);
        boolean sharedWallOnce = s.plaqueAt(roomB) == null;
        level.setBlock(roomB, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(wallStation, Blocks.COBBLESTONE.defaultBlockState(), 3);

        // 5. Outdoors: a plaque on a post against the room's wall, five blocks from the loom, can't
        // see through the wall — and can once there's a hole in it (control).
        s.removePlaque(outside); // free the room's workstations, so only the wall stands in the way
        BlockPos post = o.offset(-2, 2, 1);
        level.setBlock(post, Blocks.COBBLESTONE.defaultBlockState(), 3);
        BlockPos onPost = hang(level, plaqueState, post.east(), Direction.EAST);
        boolean postSeesNothing = s.plaqueAt(onPost) == null;
        level.setBlock(o.offset(0, 2, 1), Blocks.AIR.defaultBlockState(), 3);
        BuildingRegistry.refreshPlaque(level, onPost, null, false);
        boolean postSeesThroughHole = s.plaqueAt(onPost) != null;
        level.setBlock(o.offset(0, 2, 1), Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.setBlock(onPost, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(post, Blocks.AIR.defaultBlockState(), 3);

        // 6. Outdoor plaques: one per spot. Three posts in the open, each beside a smithing table;
        // the second is three blocks from the first (same spot), the third fourteen away.
        BlockPos yard = o.offset(-20, 0, -20);
        BlockPos[] outdoor = new BlockPos[3];
        int[] offsets = {0, 3, 14};
        for (int i = 0; i < 3; i++) {
            BlockPos base = yard.offset(offsets[i], 2, 0);
            level.setBlock(base, Blocks.COBBLESTONE.defaultBlockState(), 3);
            level.setBlock(base.offset(0, -1, 2), Blocks.SMITHING_TABLE.defaultBlockState(), 3);
            outdoor[i] = hang(level, plaqueState, base.south(), Direction.SOUTH);
        }
        boolean outdoorFirst = s.plaqueAt(outdoor[0]) != null;
        boolean outdoorCrowdRefused = s.plaqueAt(outdoor[1]) == null;
        boolean outdoorApartOk = s.plaqueAt(outdoor[2]) != null;
        for (int i = 0; i < 3; i++) {
            BlockPos base = yard.offset(offsets[i], 2, 0);
            level.setBlock(outdoor[i], Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(base, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(base.offset(0, -1, 2), Blocks.AIR.defaultBlockState(), 3);
        }

        // 7. The promotion checklist re-surveys first: name the room a Smithy, carry the table
        // off, and ask for the checklist straight away.
        BuildingRegistry.refreshPlaque(level, outside, null, false);
        for (int i = 0; i < 4 && s.plaqueAt(outside) != BuildingCategory.SMITHY; i++) {
            BuildingRegistry.refreshPlaque(level, outside, null, true);
        }
        boolean wasSmithy = s.plaqueAt(outside) == BuildingCategory.SMITHY;
        SettlementTier tierBefore = s.tier();
        s.setTier(SettlementTier.VILLAGE);
        level.setBlock(table, Blocks.AIR.defaultBlockState(), 3);
        com.terraTowns.settlement.SettlementPromotion.evaluate(level, s);
        boolean promotionSeesItGone = wasSmithy && !s.hasBuilding(BuildingCategory.SMITHY);
        s.setTier(tierBefore);

        // 8. Everything carried off: the periodic re-check un-registers; a workstation brought
        // back re-registers; and breaking the plaque clears it.
        level.setBlock(loom, Blocks.AIR.defaultBlockState(), 3);
        BuildingRegistry.revalidate(level, s);
        boolean unregistered = s.plaqueAt(outside) == null;
        level.setBlock(loom, Blocks.LOOM.defaultBlockState(), 3);
        BuildingRegistry.refreshPlaque(level, outside, null, false);
        boolean reRegistered = s.plaqueAt(outside) == BuildingCategory.FARM_PLOTS;
        level.setBlock(outside, Blocks.AIR.defaultBlockState(), 3);
        boolean clearedOnRemove = s.plaqueAt(outside) == null;

        // 9. Promotion out of a hamlet needs a registered gym.
        boolean hamletNeedsGym = com.terraTowns.settlement.SettlementPromotion.evaluate(level, s).requirements()
                .stream().anyMatch(r -> r.label().getContents() instanceof TranslatableContents t
                        && t.getKey().equals("promo.terra_towns.req.building") && t.getArgs().length > 0
                        && t.getArgs()[0] instanceof Component c && c.getContents() instanceof TranslatableContents a
                        && a.getKey().equals("building.terra_towns.gym") && !r.met());

        for (BlockPos p : BlockPos.betweenClosed(o.offset(-3, 0, 0), o.offset(12, 4, 6))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
        s.setGymCleared(false);

        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("offersExactlyFarmPlotsAndSmithy", offered.equals(new java.util.TreeSet<>(
                java.util.List.of(BuildingCategory.FARM_PLOTS.id(), BuildingCategory.SMITHY.id()))));
        verdict.put("signTextNamesTheType", signMatches);
        verdict.put("onlyTheNamedTypesJobIsOpen", onlyCurrentJobOpen);
        verdict.put("secondPlaqueInBuildingRefused", secondRefused);
        verdict.put("thirdPlaqueInBuildingRefused", thirdRefused);
        verdict.put("outsideWallSurveysRoomBehind", outsideNames == BuildingCategory.FARM_PLOTS
                || outsideNames == BuildingCategory.SMITHY);
        verdict.put("firstPlaqueKeepsTheBuilding", firstKeepsIt);
        verdict.put("sharedWallWorkstationCountsOnce", sharedWallOnce);
        verdict.put("outdoorPostCantSeeThroughWalls", postSeesNothing);
        verdict.put("outdoorPostSeesThroughAHole", postSeesThroughHole);
        verdict.put("outdoorPlaqueRegisters", outdoorFirst);
        verdict.put("outdoorPlaqueTooCloseRefused", outdoorCrowdRefused);
        verdict.put("outdoorPlaqueFarEnoughOk", outdoorApartOk);
        verdict.put("promotionReSurveysFirst", promotionSeesItGone);
        verdict.put("removingWorkstationsUnregisters", unregistered);
        verdict.put("reRegistersWhenWorkstationReturns", reRegistered);
        verdict.put("removingPlaqueUnregisters", clearedOnRemove);
        verdict.put("hamletPromotionNeedsGym", hamletNeedsGym);
        out.put("verdict", verdict);
        return out;
    }

    /**
     * The desk rules (GymDesk) on a real settlement, with vanilla's own villager AI stood in for
     * by the exact state changes it makes (claiming a job site; dropping an untraded villager's
     * profession when its job site goes), since nothing ticks in the harness.
     */
    private static Map<String, Object> gymDesk(ServerLevel level, SettlementManager manager) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<SettlementData> all = new ArrayList<>(manager.all());
        SettlementData s = all.size() > 1 ? all.get(1) : all.get(0);
        BlockPos base = s.spawnPoint() != null ? s.spawnPoint() : s.center();
        s.setGymLeaderId(null);
        s.setDeskPos(null);
        s.setGymCleared(false);
        net.minecraft.world.entity.ai.village.poi.PoiManager pois = level.getPoiManager();

        BlockPos p1 = deskSpot(level, base.offset(3, 0, 3));
        placeDesk(level, s, p1);
        boolean claimableWithoutLeader = pois.getFreeTickets(p1) == 1 && p1.equals(s.deskPos());

        // A villager claims it, as vanilla's AcquirePoi + AssignProfessionFromJobSite would.
        Villager v = net.minecraft.world.entity.EntityType.VILLAGER.spawn(level, base.offset(1, 0, 0),
                net.minecraft.world.entity.MobSpawnType.EVENT);
        pois.take(h -> h.is(TerraTownsRegistries.GYM_LEADERS_DESK_POI_KEY), (h, q) -> q.equals(p1), p1, 1);
        v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE,
                net.minecraft.core.GlobalPos.of(level.dimension(), p1));
        v.setVillagerData(v.getVillagerData().setProfession(TerraTownsRegistries.GYM_LEADER_PROFESSION.get()));
        com.terraTowns.event.SettlementTickEvents.bindGymLeaderIfNeeded(level.getServer(), level, manager, s);
        boolean bound = v.getUUID().equals(s.gymLeaderId());

        // The player picks the desk up to move it: vanilla drops the untraded leader's profession.
        level.setBlock(p1, Blocks.AIR.defaultBlockState(), 3);
        v.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
        v.setVillagerData(v.getVillagerData().setProfession(VillagerProfession.NONE));
        com.terraTowns.event.SettlementTickEvents.bindGymLeaderIfNeeded(level.getServer(), level, manager, s);
        com.terraTowns.settlement.GymDesk.tick(level, s);
        boolean bindingSurvives = v.getUUID().equals(s.gymLeaderId());

        // ...and puts it down somewhere else: it goes straight back to the leader.
        BlockPos p2 = deskSpot(level, base.offset(-3, 0, 3));
        placeDesk(level, s, p2);
        boolean backToLeader = holds(v, p2) && pois.getFreeTickets(p2) == 0;

        // A second desk: nobody can claim it, and nothing changes.
        BlockPos p3 = deskSpot(level, base.offset(0, 0, -4));
        placeDesk(level, s, p3);
        boolean secondInert = pois.getFreeTickets(p3) == 0 && p2.equals(s.deskPos()) && holds(v, p2);

        // Someone else holding the profession here is stood down in the leader's favour.
        Villager w = net.minecraft.world.entity.EntityType.VILLAGER.spawn(level, base.offset(-1, 0, 0),
                net.minecraft.world.entity.MobSpawnType.EVENT);
        w.setVillagerData(w.getVillagerData().setProfession(TerraTownsRegistries.GYM_LEADER_PROFESSION.get()));
        com.terraTowns.settlement.GymDesk.tick(level, s);
        boolean usurperStoodDown = w.getVillagerData().getProfession() == VillagerProfession.NONE && holds(v, p2);

        // Beaten: locked like a traded villager (vanilla keeps its job with no job site).
        boolean unlockedBefore = v.getVillagerXp() == 0;
        s.setGymCleared(true);
        com.terraTowns.settlement.GymDesk.tick(level, s);
        boolean locked = unlockedBefore && v.getVillagerXp() > 0;

        // The settlement's desk goes and the spare one still standing takes over — for the leader.
        level.setBlock(p2, Blocks.AIR.defaultBlockState(), 3);
        v.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
        com.terraTowns.settlement.GymDesk.tick(level, s);
        boolean spareTakesOver = p3.equals(s.deskPos()) && holds(v, p3)
                && v.getVillagerData().getProfession() == TerraTownsRegistries.GYM_LEADER_PROFESSION.get();

        level.setBlock(p3, Blocks.AIR.defaultBlockState(), 3);
        v.discard();
        w.discard();
        s.setGymLeaderId(null);
        s.setDeskPos(null);
        s.setGymCleared(false);

        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("firstDeskClaimableWithoutLeader", claimableWithoutLeader);
        verdict.put("claimantBound", bound);
        verdict.put("bindingSurvivesDeskMove", bindingSurvives);
        verdict.put("newDeskGoesBackToLeader", backToLeader);
        verdict.put("secondDeskInert", secondInert);
        verdict.put("usurperStoodDown", usurperStoodDown);
        verdict.put("beatenLeaderLocked", locked);
        verdict.put("spareDeskTakesOverForLeader", spareTakesOver);
        out.put("verdict", verdict);
        return out;
    }

    /** Admin reassignment (/terraTowns assign) at the data level: move a stand-in player twice. */
    private static Map<String, Object> hamletAssign(SettlementManager manager) {
        List<SettlementData> hamlets = manager.all().stream().filter(x -> manager.isSpawnHamlet(x.id())).toList();
        Map<String, Object> verdict = new LinkedHashMap<>();
        if (hamlets.size() < 2) {
            verdict.put("skipped", "fewer than two hamlets");
            return verdict;
        }
        UUID ghost = UUID.nameUUIDFromBytes("terra-towns-assign-audit".getBytes(StandardCharsets.UTF_8));
        SettlementData a = hamlets.get(0), b = hamlets.get(1);
        UUID aOwner = a.ownerId(), bOwner = b.ownerId();
        String aName = a.name(), bName = b.name();
        a.setOwnerId(null);
        b.setOwnerId(null);
        SettlementData left0 = manager.reassignSpawnHamlet(ghost, "Ghost", a);
        verdict.put("firstAssignmentHasNothingToRelease", left0 == null && a.isOwnedBy(ghost));
        SettlementData left1 = manager.reassignSpawnHamlet(ghost, "Ghost", b);
        verdict.put("movingReleasesTheOldHamlet", left1 == a && a.ownerId() == null && b.isOwnedBy(ghost));
        verdict.put("ownsExactlyOne", manager.spawnHamletOwnedBy(ghost).orElse(null) == b
                && manager.all().stream().filter(x -> x.isOwnedBy(ghost)).count() == 1);
        verdict.put("reassigningToSameIsNoOp", manager.reassignSpawnHamlet(ghost, "Ghost", b) == null && b.isOwnedBy(ghost));
        a.setOwnerId(aOwner);
        a.setName(aName);
        b.setOwnerId(bOwner);
        b.setName(bName);
        return verdict;
    }

    /**
     * The hamlet tree clear on a staged scene in the sky: a stone platform (ground), a "building"
     * footprint with a log beam in it, and around it the artifacts from the 2026-09-27 playtest.
     * Vanilla stand-ins for the modded growth: a huge mushroom (stem and cap blocks are neither
     * logs nor leaves — like BWG's allium petal blocks and florus stems); a tree whose canopy
     * reaches the beam (0.4.7 counted the beam as the tree's log and kept those leaves); and a
     * soil plate on a branch hanging past the platform's edge (felling the branch left it floating).
     */
    private static Map<String, Object> treeFelling(ServerLevel level, SettlementManager manager) {
        BlockPos o = manager.all().iterator().next().center().above(80);
        int y = o.getY();
        java.util.function.BiFunction<Integer, Integer, BlockPos> at = (dx, dz) -> new BlockPos(o.getX() + dx, y, o.getZ() + dz);
        // Platform: x 0..29, z 8..29 (z 7 and below is open air).
        for (int dx = 0; dx <= 29; dx++) {
            for (int dz = 8; dz <= 29; dz++) {
                level.setBlock(at.apply(dx, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        // The "building": footprint x 10..16, z 10..16, roof at y+6, with a log beam on its edge.
        List<int[]> footprints = new ArrayList<>();
        footprints.add(new int[]{o.getX() + 10, o.getZ() + 10, o.getX() + 16, o.getZ() + 16, y + 6});
        BlockPos beam = at.apply(16, 12).above(3);
        level.setBlock(beam, Blocks.OAK_LOG.defaultBlockState(), 3);

        // Tree beside it, canopy reaching the beam.
        BlockPos trunk = at.apply(19, 12);
        for (int h = 1; h <= 5; h++) {
            level.setBlock(trunk.above(h), Blocks.OAK_LOG.defaultBlockState(), 3);
        }
        List<BlockPos> leaves = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 3; dy <= 5; dy++) {
                    BlockPos lp = trunk.offset(dx, dy, dz);
                    if (level.getBlockState(lp).isAir()) {
                        level.setBlock(lp, Blocks.OAK_LEAVES.defaultBlockState(), 2);
                        leaves.add(lp);
                    }
                }
            }
        }
        // A player-placed (persistent) leaf in the same canopy must survive.
        BlockPos persistent = trunk.offset(0, 6, 0);
        level.setBlock(persistent, Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);

        // Huge mushroom: stem and cap, neither logs nor leaves.
        BlockPos stem = at.apply(13, 19);
        for (int h = 1; h <= 4; h++) {
            level.setBlock(stem.above(h), Blocks.MUSHROOM_STEM.defaultBlockState(), 3);
        }
        List<BlockPos> cap = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos cp = stem.offset(dx, 5, dz);
                level.setBlock(cp, Blocks.RED_MUSHROOM_BLOCK.defaultBlockState(), 3);
                cap.add(cp);
            }
        }

        // Tree on the platform's edge with a branch out over the air carrying a soil plate.
        BlockPos edge = at.apply(13, 8);
        for (int h = 1; h <= 4; h++) {
            level.setBlock(edge.above(h), Blocks.OAK_LOG.defaultBlockState(), 3);
        }
        BlockPos branch = at.apply(13, 7).above(1);
        BlockPos plate = branch.above();
        level.setBlock(branch, Blocks.OAK_LOG.defaultBlockState(), 3);
        level.setBlock(plate, Blocks.DIRT.defaultBlockState(), 3);

        HamletPiece.fellTreesForAudit(level, footprints);

        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("treeFelled", level.getBlockState(trunk.above(3)).isAir());
        verdict.put("canopyByBeamGone", leaves.stream().allMatch(lp -> level.getBlockState(lp).isAir()));
        verdict.put("beamUntouched", level.getBlockState(beam).is(Blocks.OAK_LOG));
        verdict.put("persistentLeafKept", level.getBlockState(persistent).is(Blocks.OAK_LEAVES));
        verdict.put("mushroomStemFelled", level.getBlockState(stem.above(2)).isAir());
        verdict.put("mushroomCapGone", cap.stream().allMatch(cp -> level.getBlockState(cp).isAir()));
        verdict.put("hangingSoilGone", level.getBlockState(plate).isAir() && level.getBlockState(branch).isAir());
        verdict.put("groundUntouched", level.getBlockState(at.apply(19, 12)).is(Blocks.STONE)
                && level.getBlockState(at.apply(13, 8)).is(Blocks.STONE));

        for (BlockPos q : BlockPos.betweenClosed(at.apply(0, 5), at.apply(29, 29).above(10))) {
            if (!level.getBlockState(q).isAir()) {
                level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", verdict);
        return out;
    }

    /**
     * A walkway across bare rock, on a staged strip in the sky (TT-209): plaza at the west end,
     * a doorstep at the east end, grass for the western half and stone for the eastern half, so
     * the door opens onto stone like a house cut into a hillside. Walkways only turned soil into
     * path, so every stone column stayed stone: the lane started wherever the soil did, and the
     * step out of the door landed on rock. The fixed-seed hamlets no longer put a house on
     * stone, so this scene is the only thing that exercises it.
     */
    private static Map<String, Object> stonePath(ServerLevel level, SettlementManager manager) {
        // Centred on the hamlet: the route search reads ground across its whole bounding box, and
        // off to one side that meant generating fresh chunks inside one tick (watchdog kill).
        BlockPos o = manager.all().iterator().next().center().above(90).offset(-15, 0, -3);
        int y = o.getY();
        java.util.function.BiFunction<Integer, Integer, BlockPos> at = (dx, dz) -> new BlockPos(o.getX() + dx, y, o.getZ() + dz);
        // Strip: x 0..30, z 0..6. Grass for x <= 14, stone beyond.
        for (int dx = 0; dx <= 30; dx++) {
            for (int dz = 0; dz <= 6; dz++) {
                level.setBlock(at.apply(dx, dz), (dx <= 14 ? Blocks.GRASS_BLOCK : Blocks.STONE).defaultBlockState(), 3);
            }
        }
        int[] plaza = {o.getX() + 1, o.getZ() + 1, o.getX() + 5, o.getZ() + 5, y + 5};
        BlockPos door = at.apply(28, 3);
        HamletPiece.layPathForAudit(level, door, Direction.WEST, plaza);

        // Continuity: every x between the doorstep and the plaza has a path block somewhere
        // across the strip (the route may wiggle in z, but must not skip a column).
        java.util.function.IntPredicate pathedAt = dx -> {
            for (int dz = 0; dz <= 6; dz++) {
                if (level.getBlockState(at.apply(dx, dz)).is(Blocks.DIRT_PATH)) {
                    return true;
                }
            }
            return false;
        };
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("doorstepIsPath", level.getBlockState(door).is(Blocks.DIRT_PATH));
        verdict.put("laneUnbrokenOnStone", java.util.stream.IntStream.rangeClosed(15, 28).allMatch(pathedAt));
        verdict.put("laneUnbrokenOnGrass", java.util.stream.IntStream.rangeClosed(6, 14).allMatch(pathedAt));

        for (BlockPos q : BlockPos.betweenClosed(at.apply(0, 0), at.apply(30, 6).above(2))) {
            if (!level.getBlockState(q).isAir()) {
                level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", verdict);
        return out;
    }

    private static BlockPos deskSpot(ServerLevel level, BlockPos near) {
        return level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, near);
    }

    /** Place a desk the way a player does: the block, then the desk's placement hook. */
    private static void placeDesk(ServerLevel level, SettlementData s, BlockPos pos) {
        level.setBlock(pos, TerraTownsRegistries.GYM_LEADERS_DESK.get().defaultBlockState(), 3);
        com.terraTowns.settlement.GymDesk.onPlaced(level, pos, s);
    }

    private static boolean holds(Villager v, BlockPos desk) {
        return v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                .map(g -> g.pos().equals(desk)).orElse(false)
                && v.getVillagerData().getProfession() == TerraTownsRegistries.GYM_LEADER_PROFESSION.get();
    }

    /** A hollow cobblestone box from {@code a} to {@code b}, air inside. */
    private static void shell(ServerLevel level, BlockPos a, BlockPos b) {
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            boolean edge = p.getX() == a.getX() || p.getX() == b.getX() || p.getY() == a.getY()
                    || p.getY() == b.getY() || p.getZ() == a.getZ() || p.getZ() == b.getZ();
            level.setBlock(p, edge ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /** Hang a plaque at {@code pos} facing {@code facing} and let it register, as placing one does. */
    private static BlockPos hang(ServerLevel level, BlockState plaqueState, BlockPos pos, Direction facing) {
        level.setBlock(pos, plaqueState.setValue(WallSignBlock.FACING, facing), 3);
        BuildingRegistry.refreshPlaque(level, pos, null, false);
        return pos;
    }

    /** @return true if the plaque's second line is the lang key {@code sign.terra_towns.<key>.1}. */
    private static boolean signSays(ServerLevel level, BlockPos pos, String key) {
        if (!(level.getBlockEntity(pos) instanceof BuildingPlaqueBlockEntity sign)) {
            return false;
        }
        return sign.getFrontText().getMessage(1, false).getContents() instanceof TranslatableContents t
                && t.getKey().equals("sign.terra_towns." + key + ".1");
    }

    /**
     * Exercise rival rank scaling against a synthetic player.
     *
     * <p>No real player is needed: rank is a pure function of influence breadth and defeat
     * history, so granting a made-up UUID influence over N settlements and reading the rank back
     * tests the actual policy in {@link RivalPool#rankFor} rather than a re-implementation of
     * it. Checks the two growth terms and the {@link RivalPool#MAX_RANK} clamp.</p>
     */
    private static Map<String, Object> rivals(ServerLevel level, SettlementManager manager) {
        Map<String, Object> out = new LinkedHashMap<>();
        RivalPool pool = RivalPool.get(level);
        InfluenceTracker influence = InfluenceTracker.get(level);
        UUID ghost = UUID.nameUUIDFromBytes("terra-towns-rival-audit".getBytes(StandardCharsets.UTF_8));

        List<SettlementData> settlements = new ArrayList<>(manager.all());
        List<Integer> rankByBreadth = new ArrayList<>();
        rankByBreadth.add(pool.rankFor(level, ghost)); // 0 settlements
        for (SettlementData s : settlements) {
            influence.grant(ghost, s.id());
            rankByBreadth.add(pool.rankFor(level, ghost));
        }
        out.put("settlementsGranted", settlements.size());
        out.put("rankByInfluenceCount", rankByBreadth);

        int beforeDefeat = pool.rankFor(level, ghost);
        pool.recordDefeat(ghost, level.getGameTime());
        int afterDefeat = pool.rankFor(level, ghost);
        out.put("rankBeforeDefeat", beforeDefeat);
        out.put("rankAfterDefeat", afterDefeat);

        for (int i = 0; i < RivalPool.MAX_RANK * 2; i++) {
            pool.recordDefeat(ghost, level.getGameTime());
        }
        int clamped = pool.rankFor(level, ghost);
        out.put("rankAfterManyDefeats", clamped);

        // Posting: spawn a real rival for the stand-in player in every hamlet and check it landed
        // somewhere a player would actually find it -- standing on solid ground with headroom,
        // level with the spawn point (not on a roof), close by, tagged, and visible to the
        // "is a rival already here?" check that stops double-posting.
        List<Map<String, Object>> posts = new ArrayList<>();
        int postedWell = 0;
        int attempted = 0;
        for (SettlementData s : settlements) {
            if (s.spawnPoint() == null) {
                continue;
            }
            attempted++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("settlement", s.name());
            RivalEntity rival = RivalPosting.post(level, pool, s, ghost, "harness");
            if (rival == null) {
                row.put("posted", false);
                posts.add(row);
                continue;
            }
            BlockPos at = rival.blockPosition();
            boolean onGround = level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP);
            boolean headroom = level.getBlockState(at).getCollisionShape(level, at).isEmpty()
                    && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty();
            int dy = at.getY() - s.spawnPoint().getY();
            double dist = Math.sqrt(at.distSqr(s.spawnPoint()));
            boolean tagged = rival.hasCustomName() && rival.isCustomNameVisible();
            boolean found = RivalPosting.rivalPresent(level, s, ghost);
            boolean good = onGround && headroom && Math.abs(dy) <= 2 && dist <= 12 && tagged && found;
            row.put("posted", true);
            row.put("onGround", onGround);
            row.put("headroom", headroom);
            row.put("dyFromSpawn", dy);
            row.put("distFromSpawn", Math.round(dist * 10) / 10.0);
            row.put("tagged", tagged);
            row.put("foundByPresenceCheck", found);
            row.put("standsUnder", key(level, at.below()));
            if (good) {
                postedWell++;
            }
            posts.add(row);
            rival.discard();
        }
        out.put("posting", posts);

        // Beating the rival: it credits the settlement it was posted to (once per tier, and the
        // promotion checklist shows it), then stays away for a day instead of re-posting on the
        // next scan.
        SettlementData home = settlements.stream().filter(s -> s.spawnPoint() != null).findFirst().orElse(null);
        boolean credited = false, requirementShown = false, requirementMet = false;
        boolean awayToday = false, backTomorrow = false, notPostedWhileAway = false;
        if (home != null) {
            boolean requirementBefore = rivalRequirementMet(level, home);
            RivalEntity rival = RivalPosting.post(level, pool, home, ghost, "harness");
            SettlementData got = RivalPosting.defeated(level, ghost);
            if (rival != null) {
                rival.discard();
            }
            long now = level.getGameTime();
            credited = got == home && home.rivalBeatenThisTier();
            requirementShown = rivalRequirementPresent(level, home);
            requirementMet = !requirementBefore && rivalRequirementMet(level, home);
            awayToday = pool.onCooldown(ghost, now + RivalPool.RETURN_COOLDOWN_TICKS - 1);
            backTomorrow = !pool.onCooldown(ghost, now + RivalPool.RETURN_COOLDOWN_TICKS);
            // The real posting pass must skip a player whose rival is away. No player is online
            // in the harness, so check the exact condition tick() uses.
            notPostedWhileAway = pool.onCooldown(ghost, now);
        }

        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("defeatCreditsTheSettlement", credited);
        verdict.put("promotionListsBeatYourRival", requirementShown);
        verdict.put("promotionRivalRequirementFlipsOnWin", requirementMet);
        verdict.put("rivalAwayForTheDay", awayToday && notPostedWhileAway);
        verdict.put("rivalBackNextDay", backTomorrow);
        verdict.put("postedStandingNearSpawn", postedWell + " / " + attempted);
        verdict.put("growsWithInfluence", rankByBreadth.size() > 1
                && rankByBreadth.get(rankByBreadth.size() - 1) > rankByBreadth.get(0));
        verdict.put("growsWithDefeats", afterDefeat > beforeDefeat);
        verdict.put("clampedAtMax", clamped == RivalPool.MAX_RANK);
        out.put("verdict", verdict);

        // Leave no trace: the ghost's influence would otherwise show up in settlement counts.
        for (SettlementData s : settlements) {
            influence.revoke(ghost, s.id());
        }
        return out;
    }

    private static boolean rivalRequirementPresent(ServerLevel level, SettlementData s) {
        return com.terraTowns.settlement.SettlementPromotion.evaluate(level, s).requirements().stream()
                .anyMatch(JobAudit::isRivalRequirement);
    }

    private static boolean rivalRequirementMet(ServerLevel level, SettlementData s) {
        return com.terraTowns.settlement.SettlementPromotion.evaluate(level, s).requirements().stream()
                .anyMatch(r -> isRivalRequirement(r) && r.met());
    }

    private static boolean isRivalRequirement(com.terraTowns.settlement.SettlementPromotion.Requirement r) {
        return r.label().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals("promo.terra_towns.req.rival");
    }

    /** Turn the staged rows into pass/fail counts the harness can assert on at a glance. */
    private static Map<String, Object> verdict(List<Map<String, Object>> rows) {
        Map<String, Object> v = new LinkedHashMap<>();
        int lockedBeforeGym = 0;
        int unlockedAfterBuildings = 0;
        int farmerStaffed = 0;
        int holderSurvives = 0;
        for (Map<String, Object> row : rows) {
            if (Boolean.TRUE.equals(row.get("holderSurvivesUnloaded"))) {
                holderSurvives++;
            }
            if (String.valueOf(row.get("beforeAnything")).contains("LOCKED_GYM")) {
                lockedBeforeGym++;
            }
            String after = String.valueOf(row.get("afterBuildings"));
            if (!after.contains("LOCKED_GYM") && !after.contains("LOCKED_BUILDING")) {
                unlockedAfterBuildings++;
            }
            if (String.valueOf(row.get("afterTrade")).contains("farmer=STAFFED")) {
                farmerStaffed++;
            }
        }
        v.put("settlements", rows.size());
        v.put("allJobsLockedBeforeGymClear", lockedBeforeGym);
        v.put("allJobsUnlockedAfterBuildings", unlockedAfterBuildings);
        v.put("farmerActuallyStaffed", farmerStaffed);
        v.put("holderSurvivesWhileUnloaded", holderSurvives);
        v.put("merchantGuardLockedInHamlet", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("tierGatedInHamlet"))).count());
        v.put("allJobsOpenInVillage", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("openInVillage"))).count());
        return v;
    }

    private static String key(ServerLevel level, BlockPos pos) {
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock()));
    }

    private static String summarise(SettlementData settlement) {
        StringBuilder sb = new StringBuilder();
        for (JobBoard.JobStatus st : JobBoard.board(settlement).values()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(st.job().id()).append('=').append(st.status().name());
        }
        return sb.toString();
    }

    /**
     * Force a farmer's trade onto one villager in range so staffing has something to find.
     *
     * @return how many villagers were in range at all.
     */
    /**
     * The Guard job end to end (TT-201): it can be staffed; its workstation is the vanilla
     * target block; a room with a target qualifies as a Guard Post while a room with only a
     * bell no longer does; and a villager practising the Guard profession in a village is
     * appointed. Runs after the per-settlement stages, so gym and buildings are already done.
     * Looks the profession up by id, so before it existed this reported missing, not a crash.
     */
    private static Map<String, Object> guard(ServerLevel level, SettlementManager manager) {
        Map<String, Object> verdict = new LinkedHashMap<>();
        java.util.Optional<VillagerProfession> profession = net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION
                .getOptional(ResourceLocation.fromNamespaceAndPath("terra_towns", "guard"));
        verdict.put("professionRegistered", profession.isPresent());
        verdict.put("staffable", SettlementJob.GUARD.isStaffable());
        verdict.put("workstationIsTarget", JobBoard.workstations(level, SettlementJob.GUARD).stream()
                .anyMatch(stack -> stack.is(net.minecraft.world.item.Items.TARGET)));
        java.util.Set<net.minecraft.world.level.block.Block> post =
                com.terraTowns.structure.BuildingSurvey.requiredBlocks(level, BuildingCategory.BARRACKS_GUARD_POST);
        verdict.put("postNeedsTarget", post.contains(Blocks.TARGET));
        verdict.put("bellAloneIsNotAPost", !post.contains(Blocks.BELL));

        // Villagers never claim a post on their own: Guards are handed out to villages only.
        verdict.put("notVanillaClaimable", level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.POINT_OF_INTEREST_TYPE)
                .getHolder(TerraTownsRegistries.GUARD_POST_POI_KEY)
                .map(h -> !h.is(net.minecraft.tags.PoiTypeTags.ACQUIRABLE_JOB_SITE)).orElse(false));

        boolean keptOutOfHamlet = false;
        boolean recruitedInVillage = false;
        boolean holdsThePost = false;
        boolean appointed = false;
        if (profession.isPresent()) {
            SettlementData s = manager.all().iterator().next();
            SettlementTier tierBefore = s.tier();
            BlockPos ground = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    s.center().offset(3, 0, 3));
            level.setBlock(ground, Blocks.TARGET.defaultBlockState(), 3);
            double r = s.registrationRadius();
            AABB box = AABB.ofSize(Vec3.atCenterOf(s.center()), 2.0 * r, 2.0 * r, 2.0 * r);
            Villager idle = null;
            for (Villager v : level.getEntitiesOfClass(Villager.class, box)) {
                if (!v.isBaby() && com.terraTowns.settlement.SettlementPromotion.isResident(v)
                        && !v.getUUID().equals(s.gymLeaderId()) && !s.isEmployed(v.getUUID())) {
                    idle = v;
                    break;
                }
            }
            if (idle != null) {
                idle.setVillagerData(idle.getVillagerData().setProfession(VillagerProfession.NONE));
                idle.refreshBrain(level);

                s.setTier(SettlementTier.HAMLET);
                keptOutOfHamlet = com.terraTowns.settlement.GuardRecruitment.recruit(level, s) == 0
                        && idle.getVillagerData().getProfession() == VillagerProfession.NONE;

                s.setTier(SettlementTier.VILLAGE);
                com.terraTowns.settlement.GuardRecruitment.recruit(level, s);
                recruitedInVillage = idle.getVillagerData().getProfession() == profession.get();
                holdsThePost = idle.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                        .map(g -> g.pos().equals(ground)).orElse(false);
                JobBoard.refresh(level, s);
                JobBoard.restaffUnchecked(level, s);
                appointed = idle.getUUID().equals(s.jobHolder(SettlementJob.GUARD));
            }
            s.setTier(tierBefore);
            JobBoard.refresh(level, s);
        }
        verdict.put("keptOutOfHamlet", keptOutOfHamlet);
        verdict.put("recruitedInVillage", recruitedInVillage);
        verdict.put("holdsThePost", holdsThePost);
        verdict.put("guardAppointed", appointed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", verdict);
        return out;
    }

    private static int giveOneVillagerATrade(ServerLevel level, SettlementData settlement) {
        double r = settlement.registrationRadius();
        AABB box = AABB.ofSize(Vec3.atCenterOf(settlement.center()), 2.0 * r, 2.0 * r, 2.0 * r);
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, box);
        for (Villager v : villagers) {
            // Only a real townsperson: the Professor and rivals are Villager subclasses too, and
            // handing one of them the trade tested nothing (they are excluded from jobs).
            if (v.isBaby() || v.getUUID().equals(settlement.gymLeaderId())
                    || !com.terraTowns.settlement.SettlementPromotion.isResident(v)) {
                continue;
            }
            v.setVillagerData(v.getVillagerData().setProfession(VillagerProfession.FARMER));
            v.refreshBrain(level);
            break;
        }
        return villagers.size();
    }
}
