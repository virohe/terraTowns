package com.terraTowns.worldgen;

import com.terraTowns.influence.InfluenceTracker;
import com.terraTowns.rival.RivalPool;
import com.terraTowns.settlement.JobBoard;
import com.terraTowns.settlement.GymDesk;
import com.terraTowns.settlement.SettlementPromotion;
import com.terraTowns.structure.BuildingDetection;
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
import net.minecraft.world.entity.item.ItemEntity;
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
        report.put("buildings", buildings(level, manager));
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
     * room with a composter and a smithing table offers exactly Farms and Smithy; the sign names
     * the type; only that type's job is open; a building takes ONE plaque (the first keeps it);
     * a plaque on the outside wall surveys the room behind; a workstation set into a shared wall
     * belongs to one building; outdoor plaques can't see through walls and can't crowd one spot;
     * taking workstations away un-registers, before the promotion checklist is read.
     */
    private static Map<String, Object> plaques(ServerLevel level, SettlementManager manager) {
        Map<String, Object> out = new LinkedHashMap<>();
        SettlementData s = manager.all().iterator().next();
        s.setGymCleared(true);
        // Only the plaques under test may register anything: set aside the hamlet's own detected
        // houses and farms (0.5.2+), which would open the Farmer job whatever a plaque says.
        List<SettlementData.Structure> setAside = new ArrayList<>(s.structures());
        s.structures().clear();
        BlockPos o = s.center().above(40);
        BlockState plaqueState = TerraTownsRegistries.BUILDING_PLAQUE.get().defaultBlockState();

        shell(level, o, o.offset(6, 4, 6));
        BlockPos composter = o.offset(4, 1, 1);
        BlockPos table = o.offset(4, 1, 2);
        level.setBlock(composter, Blocks.COMPOSTER.defaultBlockState(), 3);
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

        // 5. Outdoors: a plaque on a post against the room's wall, five blocks from the composter, can't
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
        level.setBlock(composter, Blocks.AIR.defaultBlockState(), 3);
        BuildingRegistry.revalidate(level, s);
        boolean unregistered = s.plaqueAt(outside) == null;
        level.setBlock(composter, Blocks.COMPOSTER.defaultBlockState(), 3);
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
        s.structures().addAll(setAside);
        out.put("verdict", verdict);
        return out;
    }

    /**
     * The gym-leader and desk rules (GymDesk, 0.5.1) on a real settlement: appointing a leader by
     * hand, who may and may not be appointed, the desk following its leader, and a death leaving
     * the post vacant. Vanilla's villager AI is stood in for by the exact state changes it makes
     * (forgetting a job site that's gone), since nothing ticks in the harness.
     */
    private static Map<String, Object> gymDesk(ServerLevel level, SettlementManager manager) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<SettlementData> all = new ArrayList<>(manager.all());
        SettlementData s = all.size() > 1 ? all.get(1) : all.get(0);
        BlockPos base = s.spawnPoint() != null ? s.spawnPoint() : s.center();
        s.setGymLeaderId(null);
        s.setDeskPos(null);
        s.setGymCleared(false);
        UUID owner = s.ownerId();
        net.minecraft.world.entity.ai.village.poi.PoiManager pois = level.getPoiManager();
        var deskPoi = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.POINT_OF_INTEREST_TYPE)
                .getHolderOrThrow(TerraTownsRegistries.GYM_LEADERS_DESK_POI_KEY);
        VillagerProfession gymLeader = TerraTownsRegistries.GYM_LEADER_PROFESSION.get();

        // No villager's own AI can take a desk; the leader holds one once given it.
        boolean notClaimable = !deskPoi.is(net.minecraft.tags.PoiTypeTags.ACQUIRABLE_JOB_SITE)
                && !gymLeader.acquirableJobSite().test(deskPoi);
        boolean heldAsJobSite = gymLeader.heldJobSite().test(deskPoi);

        BlockPos p1 = deskSpot(level, base.offset(3, 0, 3));
        placeDesk(level, s, p1);
        boolean deskWaitsForLeader = p1.equals(s.deskPos()) && pois.getFreeTickets(p1) == 1 && s.gymLeaderId() == null;

        // Appointed by hand: becomes the leader, gets the desk, locked from the start.
        Villager v = villagerAt(level, base.offset(1, 0, 0));
        GymDesk.Appointment first = GymDesk.appoint(level, v, null);
        boolean appointed = first == GymDesk.Appointment.APPOINTED && v.getUUID().equals(s.gymLeaderId())
                && holds(v, p1) && v.getVillagerXp() > 0;

        // One leader per settlement; only the unemployed; only on the settlement's ground; only yours.
        Villager w = villagerAt(level, base.offset(-1, 0, 0));
        boolean secondRefused = GymDesk.appoint(level, w, null) == GymDesk.Appointment.HAS_LEADER
                && w.getVillagerData().getProfession() == VillagerProfession.NONE;
        Villager farmer = villagerAt(level, base.offset(0, 0, 1));
        farmer.setVillagerData(farmer.getVillagerData().setProfession(VillagerProfession.FARMER));
        boolean employedRefused = GymDesk.appoint(level, farmer, null) == GymDesk.Appointment.NOT_UNEMPLOYED;
        BlockPos away = s.footprint() == null ? null
                : deskSpot(level, new BlockPos(s.footprint().maxX() + 40, base.getY(), base.getZ())).above();
        Villager stray = away != null && manager.containing(away) == null ? villagerAt(level, away) : null;
        boolean outsideRefused = stray != null
                && GymDesk.appoint(level, stray, null) == GymDesk.Appointment.NOT_IN_SETTLEMENT;
        s.setOwnerId(UUID.nameUUIDFromBytes("terra-towns-desk-audit".getBytes(StandardCharsets.UTF_8)));
        boolean notYoursRefused = GymDesk.appoint(level, w, null) == GymDesk.Appointment.NOT_YOURS;
        s.setOwnerId(owner);

        // The player picks the desk up to move it: vanilla forgets the job site; the locked
        // leader keeps its profession and its post.
        level.setBlock(p1, Blocks.AIR.defaultBlockState(), 3);
        v.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
        com.terraTowns.event.SettlementTickEvents.bindGymLeaderIfNeeded(level.getServer(), level, manager, s);
        GymDesk.tick(level, s);
        boolean keepsPostWithoutDesk = v.getUUID().equals(s.gymLeaderId())
                && v.getVillagerData().getProfession() == gymLeader;

        // ...and puts it down somewhere else: it goes straight back to the leader.
        BlockPos p2 = deskSpot(level, base.offset(-3, 0, 3));
        placeDesk(level, s, p2);
        boolean backToLeader = holds(v, p2);

        // A second desk: nothing happens, and nobody has it.
        BlockPos p3 = deskSpot(level, base.offset(0, 0, -4));
        placeDesk(level, s, p3);
        boolean secondInert = p2.equals(s.deskPos()) && holds(v, p2) && pois.getFreeTickets(p3) == 1;

        // Someone else holding the profession here (a claimant from before 0.5.1) is stood down.
        w.setVillagerData(w.getVillagerData().setProfession(gymLeader));
        GymDesk.tick(level, s);
        boolean usurperStoodDown = w.getVillagerData().getProfession() == VillagerProfession.NONE && holds(v, p2);

        // The settlement's desk goes and the spare one still standing takes over, for the leader.
        level.setBlock(p2, Blocks.AIR.defaultBlockState(), 3);
        v.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
        GymDesk.tick(level, s);
        boolean spareTakesOver = p3.equals(s.deskPos()) && holds(v, p3);

        // A player moving the desk (2026-10-02 crash): placement runs inside the packet task, and
        // vanilla defers registering the new desk's POI until that task ends. So onPlaced, and a
        // settlement scan before the deferred task, see a desk block with no POI under it.
        level.setBlock(p3, Blocks.AIR.defaultBlockState(), 3);
        BlockPos p4 = deskSpot(level, base.offset(3, 0, 3));
        level.setBlock(p4, TerraTownsRegistries.GYM_LEADERS_DESK.get().defaultBlockState(), 3);
        pois.remove(p4); // not registered yet, as during the player's placement
        boolean survivesUnregistered;
        try {
            GymDesk.onPlaced(level, p4, s);
            GymDesk.tick(level, s);
            survivesUnregistered = true;
        } catch (RuntimeException e) {
            survivesUnregistered = false;
        }
        pois.add(p4, deskPoi); // the deferred task runs
        GymDesk.tick(level, s);
        boolean playerMoveGoesToLeader = survivesUnregistered && p4.equals(s.deskPos()) && holds(v, p4);

        // The leader dies: the post is vacant, nobody is put in it unasked (a hamlet isn't a
        // generated village), and a successor can be appointed.
        v.kill();
        com.terraTowns.event.SettlementTickEvents.bindGymLeaderIfNeeded(level.getServer(), level, manager, s);
        boolean deathVacates = s.gymLeaderId() == null;
        boolean successorAppointed = GymDesk.appoint(level, w, null) == GymDesk.Appointment.APPOINTED
                && w.getUUID().equals(s.gymLeaderId()) && holds(w, p4);

        level.setBlock(p4, Blocks.AIR.defaultBlockState(), 3);
        for (Villager x : new Villager[]{v, w, farmer, stray}) {
            if (x != null) {
                x.discard();
            }
        }
        s.setGymLeaderId(null);
        s.setDeskPos(null);
        s.setGymCleared(false);

        Map<String, Object> verdict = new LinkedHashMap<>();
        // Deleting the desk's acquirable-job-site tag (0.5.0 dev) once left gyms leaderless;
        // since 0.5.1 it is meant to be gone, and a desk must never be claimable again.
        verdict.put("deskNotClaimableByVillagers", notClaimable);
        verdict.put("deskHeldAsLeadersJobSite", heldAsJobSite);
        verdict.put("deskWaitsForLeader", deskWaitsForLeader);
        verdict.put("appointedLeaderGetsDeskLocked", appointed);
        verdict.put("secondLeaderRefused", secondRefused);
        verdict.put("employedVillagerRefused", employedRefused);
        verdict.put("outsideFootprintRefused", outsideRefused);
        verdict.put("othersSettlementRefused", notYoursRefused);
        verdict.put("leaderKeepsPostWithoutDesk", keepsPostWithoutDesk);
        verdict.put("newDeskGoesBackToLeader", backToLeader);
        verdict.put("secondDeskInert", secondInert);
        verdict.put("usurperStoodDown", usurperStoodDown);
        verdict.put("spareDeskTakesOverForLeader", spareTakesOver);
        verdict.put("playerMoveSurvivesUnregisteredPoi", survivesUnregistered);
        verdict.put("playerMoveGoesToLeader", playerMoveGoesToLeader);
        verdict.put("leaderDeathVacatesPost", deathVacates);
        verdict.put("successorAppointed", successorAppointed);
        out.put("verdict", verdict);
        return out;
    }

    private static Villager villagerAt(ServerLevel level, BlockPos pos) {
        return net.minecraft.world.entity.EntityType.VILLAGER.spawn(level, pos, net.minecraft.world.entity.MobSpawnType.EVENT);
    }

    /**
     * Building detection and the promotion ladder (0.5.2): hamlet houses register as Housing,
     * what a structure holds decides its type, a plaque inside one takes over, the bed rule, and
     * the checklists asking for what each step should.
     */
    private static Map<String, Object> buildings(ServerLevel level, SettlementManager manager) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> verdict = new LinkedHashMap<>();

        // Beds: 15% over the larger of the step's minimum and the actual population, rounded up.
        verdict.put("bedsFifteenPercentOver", SettlementPromotion.bedsNeeded(10, 10) == 12
                && SettlementPromotion.bedsNeeded(20, 20) == 23 && SettlementPromotion.bedsNeeded(40, 40) == 46
                && SettlementPromotion.bedsNeeded(20, 30) == 35 && SettlementPromotion.bedsNeeded(10, 4) == 12);

        // Every spawn hamlet's houses were detected, and each is Housing.
        List<SettlementData> hamlets = manager.all().stream().filter(x -> manager.isSpawnHamlet(x.id())).toList();
        int houses = 0;
        int housing = 0;
        for (SettlementData h : hamlets) {
            houses += (int) h.structures().stream().filter(x -> x.onlyAs() == BuildingCategory.HOUSING).count();
            housing += h.buildingCount(BuildingCategory.HOUSING);
        }
        verdict.put("hamletHousesAreHousing", housing + " / " + houses);
        verdict.put("hamletHousesRecorded", houses >= 4 * hamlets.size());
        out.put("hamletHouses", houses);

        SettlementData h = hamlets.get(0);
        SettlementData.Structure house = h.structures().get(0);
        // A hamlet house stays Housing whatever is put in it.
        BlockPos inside = openFloorIn(level, house.box());
        boolean housingOnly = false;
        if (inside != null) {
            level.setBlock(inside, Blocks.SMITHING_TABLE.defaultBlockState(), 3);
            BuildingDetection.classify(level, h, house);
            housingOnly = house.type() == BuildingCategory.HOUSING;
            level.setBlock(inside, Blocks.AIR.defaultBlockState(), 3);
            BuildingDetection.classify(level, h, house);
        }
        verdict.put("hamletHouseStaysHousing", housingOnly);

        // A generated structure: nothing, then a bed (Housing), then a workstation (its building).
        BlockPos o = h.center().above(110);
        shell(level, o, o.offset(6, 4, 6));
        net.minecraft.world.level.levelgen.structure.BoundingBox box =
                new net.minecraft.world.level.levelgen.structure.BoundingBox(o.getX(), o.getY(), o.getZ(),
                        o.getX() + 6, o.getY() + 4, o.getZ() + 6);
        SettlementData.Structure st = new SettlementData.Structure(box, "terra_towns:audit/test", null);
        h.structures().add(st);
        int smithiesBefore = h.buildingCount(BuildingCategory.SMITHY);
        BuildingDetection.classify(level, h, st);
        boolean emptyIsNothing = st.classified() && st.type() == null && !st.counts();
        BlockPos foot = o.offset(2, 1, 3);
        level.setBlock(foot, Blocks.RED_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT)
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, net.minecraft.core.Direction.NORTH), 2);
        level.setBlock(foot.north(), Blocks.RED_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD)
                .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, net.minecraft.core.Direction.NORTH), 2);
        BuildingDetection.classify(level, h, st);
        boolean bedIsHousing = st.type() == BuildingCategory.HOUSING && st.beds() == 1;
        BlockPos table = o.offset(4, 1, 4);
        level.setBlock(table, Blocks.SMITHING_TABLE.defaultBlockState(), 3);
        BuildingDetection.classify(level, h, st);
        boolean workstationWins = st.type() == BuildingCategory.SMITHY && st.workstations().contains("toolsmith")
                && h.buildingCount(BuildingCategory.SMITHY) == smithiesBefore + 1;

        // A plaque inside takes over: the structure stops counting, the plaque's building counts.
        BlockPos plaquePos = o.offset(1, 2, 1);
        h.setPlaque(plaquePos, BuildingCategory.SMITHY, java.util.Set.of(), o.offset(3, 1, 3), box);
        BuildingDetection.classify(level, h, st);
        boolean plaqueOverrides = st.plaqued() && !st.counts() && h.buildingCount(BuildingCategory.SMITHY) == smithiesBefore + 1;
        h.removePlaque(plaquePos);
        BuildingDetection.classify(level, h, st);
        boolean countsAgain = st.counts() && h.buildingCount(BuildingCategory.SMITHY) == smithiesBefore + 1;

        // Taken out again: nothing.
        level.setBlock(table, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(foot.north(), Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(foot, Blocks.AIR.defaultBlockState(), 2);
        BuildingDetection.classify(level, h, st);
        boolean emptiedIsNothing = st.type() == null;
        h.structures().remove(st);
        for (BlockPos p : BlockPos.betweenClosed(o, o.offset(6, 4, 6))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
        verdict.put("emptyStructureIsNothing", emptyIsNothing);
        verdict.put("bedOnlyIsHousing", bedIsHousing);
        verdict.put("workstationDecidesType", workstationWins);
        verdict.put("plaqueInsideOverrides", plaqueOverrides);
        verdict.put("countsAgainWithoutPlaque", countsAgain);
        verdict.put("emptiedIsNothing", emptiedIsNothing);

        // 0.5.4: hamlet farms registered as Farms; a block change marks a structure dirty; a
        // barrel makes a Fishery, not Farms; a Storage Center needs 20 chests; a generated
        // animal pen (no workstation) is Farms by its fences.
        int hamletFarms = 0;
        for (SettlementData hm : hamlets) {
            hamletFarms += (int) hm.structures().stream()
                    .filter(x -> x.onlyAs() == BuildingCategory.FARM_PLOTS && x.type() == BuildingCategory.FARM_PLOTS).count();
        }
        verdict.put("hamletFarmsAreFarms", hamletFarms >= 2 * hamlets.size());
        out.put("hamletFarms", hamletFarms);

        shell(level, o, o.offset(6, 4, 6));
        SettlementData.Structure probe = new SettlementData.Structure(box, "minecraft:village/plains/houses/test", null);
        h.structures().add(probe);
        BuildingDetection.classify(level, h, probe);
        boolean cleanAfterClassify = !probe.dirty();
        level.setBlock(o.offset(3, 1, 3), Blocks.BARREL.defaultBlockState(), 3);
        boolean blockChangeMarksDirty = cleanAfterClassify && probe.dirty();
        BuildingDetection.tick(level, h);
        boolean barrelIsFishery = probe.type() == BuildingCategory.FISHERY && !probe.dirty();
        level.setBlock(o.offset(3, 1, 3), Blocks.AIR.defaultBlockState(), 3);
        int placed = 0;
        for (BlockPos p : BlockPos.betweenClosed(o.offset(1, 1, 1), o.offset(5, 1, 5))) {
            if (placed < 19) {
                level.setBlock(p, Blocks.CHEST.defaultBlockState(), 2);
                placed++;
            }
        }
        BuildingDetection.classify(level, h, probe);
        boolean nineteenIsNot = probe.type() != BuildingCategory.STORAGE_CENTER;
        level.setBlock(o.offset(1, 2, 1), Blocks.CHEST.defaultBlockState(), 2);
        BuildingDetection.classify(level, h, probe);
        boolean twentyIsStorage = probe.type() == BuildingCategory.STORAGE_CENTER;
        for (BlockPos p : BlockPos.betweenClosed(o.offset(1, 1, 1), o.offset(5, 2, 5))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
        h.structures().remove(probe);
        SettlementData.Structure pen = new SettlementData.Structure(box, "minecraft:village/plains/houses/plains_animal_pen_2", null);
        h.structures().add(pen);
        level.setBlock(o.offset(2, 1, 2), Blocks.OAK_FENCE.defaultBlockState(), 2);
        BuildingDetection.classify(level, h, pen);
        boolean penIsFarm = pen.type() == BuildingCategory.FARM_PLOTS;
        h.structures().remove(pen);
        for (BlockPos p : BlockPos.betweenClosed(o, o.offset(6, 4, 6))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
        verdict.put("blockChangeMarksDirty", blockChangeMarksDirty);
        verdict.put("barrelIsFishery", barrelIsFishery);
        verdict.put("storageCenterNeeds20Chests", nineteenIsNot && twentyIsStorage);
        verdict.put("generatedPenIsFarms", penIsFarm);

        // "New buildings": plaques registered at village tier or above.
        com.terraTowns.settlement.SettlementTier tierBefore = h.tier();
        BlockPos oldPlaque = o.offset(0, -1, 0);
        h.setPlaque(oldPlaque, BuildingCategory.FARM_PLOTS, java.util.Set.of(), oldPlaque, null);
        h.setTier(com.terraTowns.settlement.SettlementTier.VILLAGE);
        BlockPos newPlaque = o.offset(0, -2, 0);
        h.setPlaque(newPlaque, BuildingCategory.STORAGE_CENTER, java.util.Set.of(), newPlaque, null);
        boolean newCounted = h.buildingsRegisteredSince(com.terraTowns.settlement.SettlementTier.VILLAGE) == 1;

        // The town checklist: staffed workers, trades, new buildings, the Pokecentre.
        List<SettlementPromotion.Requirement> town = SettlementPromotion.evaluate(level, h).requirements();
        boolean townAsks = countKey(town, "promo.terra_towns.req.workers") == 4
                && countKey(town, "promo.terra_towns.req.trades") == 1
                && countKey(town, "promo.terra_towns.req.new_structures") == 1
                && namesBuilding(town, "promo.terra_towns.req.building", "pokecenter")
                && namesBuilding(town, "promo.terra_towns.req.building_count", "housing")
                && !namesBuilding(town, "promo.terra_towns.req.building", "battle_facility");
        h.removePlaque(oldPlaque);
        h.removePlaque(newPlaque);
        h.setTier(tierBefore);

        // The village checklist: buildings (Inn included) and Housing, no workstations.
        List<SettlementPromotion.Requirement> village = SettlementPromotion.evaluate(level, h).requirements();
        boolean villageAsks = namesBuilding(village, "promo.terra_towns.req.building", "inn_tavern")
                && namesBuilding(village, "promo.terra_towns.req.building", "gym")
                && namesBuilding(village, "promo.terra_towns.req.building_count", "housing")
                && countKey(village, "promo.terra_towns.req.workers") == 0
                && countKey(village, "promo.terra_towns.req.trades") == 0
                && countKey(village, "promo.terra_towns.req.jobs") == 0;
        verdict.put("newBuildingsCountFromVillageTier", newCounted);
        verdict.put("townChecklistAsksWorkersAndNewBuildings", townAsks);
        verdict.put("villageChecklistAsksBuildingsOnly", villageAsks);
        out.put("villageChecklist", village.stream().map(r -> r.label().getString() + (r.met() ? " ✔" : " ✘")).toList());
        out.put("verdict", verdict);
        return out;
    }

    /** An air block inside {@code box} standing on something solid: somewhere to put a block. */
    @javax.annotation.Nullable
    private static BlockPos openFloorIn(ServerLevel level, net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        for (BlockPos p : BlockPos.betweenClosed(box.minX() + 1, box.minY() + 1, box.minZ() + 1,
                box.maxX() - 1, box.maxY() - 1, box.maxZ() - 1)) {
            if (level.getBlockState(p).isAir() && level.getBlockState(p.below()).isSolidRender(level, p.below())) {
                return p.immutable();
            }
        }
        return null;
    }

    /** Requirements labelled {@code key}, or a key under it ("...workers" counts "...workers.farmer"). */
    private static long countKey(List<SettlementPromotion.Requirement> reqs, String key) {
        return reqs.stream().filter(r -> r.label().getContents() instanceof TranslatableContents t
                && (t.getKey().equals(key) || t.getKey().startsWith(key + "."))).count();
    }

    /** Does a requirement of {@code key} name the building {@code id} (its first argument)? */
    private static boolean namesBuilding(List<SettlementPromotion.Requirement> reqs, String key, String id) {
        return reqs.stream().anyMatch(r -> r.label().getContents() instanceof TranslatableContents t
                && t.getKey().equals(key) && t.getArgs().length > 0
                && t.getArgs()[0] instanceof Component c && c.getContents() instanceof TranslatableContents a
                && a.getKey().equals("building.terra_towns." + id));
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

        Map<String, Object> armour = new LinkedHashMap<>();
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
                // Armour is a Guard's quirk only: an unemployed villager leaves it lying there.
                ItemEntity ignored = dropAt(level, idle, net.minecraft.world.item.Items.IRON_CHESTPLATE);
                armour.put("nonGuardIgnoresArmour", !com.terraTowns.settlement.GuardArmor.tryPickUp(idle)
                        && ignored.isAlive() && idle.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty());
                ignored.discard();

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

                // The Guard's quirk: dropped armour gets picked up and worn, better pieces swapped in.
                var chest = net.minecraft.world.entity.EquipmentSlot.CHEST;
                var head = net.minecraft.world.entity.EquipmentSlot.HEAD;
                ItemEntity iron = dropAt(level, idle, net.minecraft.world.item.Items.IRON_CHESTPLATE);
                ItemEntity cap = dropAt(level, idle, net.minecraft.world.item.Items.LEATHER_HELMET);
                com.terraTowns.settlement.GuardArmor.tryPickUp(idle);
                armour.put("putsOnChestplate", idle.getItemBySlot(chest).is(net.minecraft.world.item.Items.IRON_CHESTPLATE) && !iron.isAlive());
                armour.put("putsOnHelmet", idle.getItemBySlot(head).is(net.minecraft.world.item.Items.LEATHER_HELMET) && !cap.isAlive());
                ItemEntity worse = dropAt(level, idle, net.minecraft.world.item.Items.LEATHER_CHESTPLATE);
                com.terraTowns.settlement.GuardArmor.tryPickUp(idle);
                armour.put("keepsBetterPiece", idle.getItemBySlot(chest).is(net.minecraft.world.item.Items.IRON_CHESTPLATE) && worse.isAlive());
                worse.discard();
                ItemEntity better = dropAt(level, idle, net.minecraft.world.item.Items.DIAMOND_CHESTPLATE);
                com.terraTowns.settlement.GuardArmor.tryPickUp(idle);
                boolean ironDropped = !level.getEntitiesOfClass(ItemEntity.class, idle.getBoundingBox().inflate(2.0),
                        e -> e.getItem().is(net.minecraft.world.item.Items.IRON_CHESTPLATE)).isEmpty();
                armour.put("swapsForBetter", idle.getItemBySlot(chest).is(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE)
                        && !better.isAlive() && ironDropped);
                level.getEntitiesOfClass(ItemEntity.class, idle.getBoundingBox().inflate(2.0)).forEach(ItemEntity::discard);

                // Fetching is vanilla's (walk to what it wantsToPickUp); the mixin makes a guard
                // want armour that beats what it wears, and nothing worse. Calling the real
                // villager method proves the mixin is applied, not just compiled.
                var netherite = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_CHESTPLATE);
                var ironChest = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE);
                armour.put("wantsBetterArmour", idle.wantsToPickUp(netherite) && !idle.wantsToPickUp(ironChest));
                Villager other = null;
                for (Villager v : level.getEntitiesOfClass(Villager.class, box)) {
                    if (!v.isBaby() && !com.terraTowns.settlement.GuardArmor.isGuard(v)
                            && com.terraTowns.settlement.SettlementPromotion.isResident(v)) {
                        other = v;
                        break;
                    }
                }
                armour.put("othersDontWantArmour", other != null && !other.wantsToPickUp(ironChest));
            }
            s.setTier(tierBefore);
            JobBoard.refresh(level, s);
        }
        verdict.put("keptOutOfHamlet", keptOutOfHamlet);
        verdict.put("recruitedInVillage", recruitedInVillage);
        verdict.put("holdsThePost", holdsThePost);
        verdict.put("guardAppointed", appointed);
        for (String k : new String[]{"nonGuardIgnoresArmour", "putsOnChestplate", "putsOnHelmet", "keepsBetterPiece", "swapsForBetter",
                "wantsBetterArmour", "othersDontWantArmour"}) {
            verdict.put(k, armour.getOrDefault(k, false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", verdict);
        return out;
    }

    /** An item lying at {@code v}'s feet, as if a player had just thrown it there (no pickup delay). */
    private static ItemEntity dropAt(ServerLevel level, Villager v, net.minecraft.world.item.Item item) {
        ItemEntity e = new ItemEntity(level, v.getX(), v.getY(), v.getZ(), new net.minecraft.world.item.ItemStack(item));
        e.setNoPickUpDelay();
        level.addFreshEntity(e);
        return e;
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
