package com.terraTowns.settlement;

import com.terraTowns.TerraTowns;
import com.terraTowns.block.GymLeadersDeskBlock;
import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * Who holds a settlement's Gym Leader's Desk, and which desk counts.
 *
 * <p><b>One desk per settlement.</b> The settlement remembers its desk ({@link SettlementData#deskPos}).
 * A second desk placed while that one stands is inert: its job-site ticket is taken on
 * placement, so no villager can ever claim it, and nothing else happens. If the settlement's
 * desk goes, a spare desk still standing in the settlement takes over.</p>
 *
 * <p><b>The bound gym leader has priority.</b> Moving the desk (break it, place it somewhere
 * else) used to cost the leader its profession — vanilla drops an untraded villager's job the
 * moment its job site disappears — and the binding went with it, so whoever grabbed the new
 * desk first became leader. Now the binding survives losing the desk, and a new desk is handed
 * straight to the bound leader. A villager that isn't the leader but holds the profession here
 * is stood down in the leader's favour.</p>
 *
 * <p><b>Beaten leaders are locked,</b> the way a villager you've traded with is: vanilla keeps
 * a villager's profession without a job site once it has any trading XP, so the first defeat
 * gives the leader one point. A locked leader stays gym leader with no desk at all.</p>
 *
 * <p>A new leader is only picked when the bound one is actually dead (or gone, with somebody
 * else holding the desk): the succession path in {@code SettlementTickEvents}.</p>
 */
public final class GymDesk {

    private GymDesk() {
    }

    /** A desk was just placed at {@code pos}. Called from the desk block, server side. */
    public static void onPlaced(ServerLevel level, BlockPos pos, SettlementData settlement) {
        // A player's placement runs inside its packet task, and vanilla defers registering the
        // desk's POI until that task ends; queued after it, this runs once the POI exists. Run
        // straight away, it found no POI to hand over and crashed the server (2026-10-02 playtest).
        level.getServer().execute(() -> placedNow(level, pos, settlement));
    }

    private static void placedNow(ServerLevel level, BlockPos pos, SettlementData settlement) {
        if (!isDesk(level, pos)) {
            return; // gone again before the queue got to it
        }
        BlockPos current = settlement.deskPos();
        if (current != null && !current.equals(pos) && isDesk(level, current)) {
            occupy(level, pos); // a second desk: inert
            TerraTowns.LOGGER.info("Second gym desk at {} in settlement {} left inert (desk is at {})",
                    pos, settlement.id(), current);
            return;
        }
        settlement.setDeskPos(pos);
        handToLeader(level, settlement);
    }

    /**
     * Periodic upkeep (settlement scan, loaded settlements only): re-home the settlement's desk
     * if it's gone, give it to the bound leader, stand down any usurper, and lock a beaten leader.
     */
    public static void tick(ServerLevel level, SettlementData settlement) {
        BlockPos desk = settlement.deskPos();
        if (desk != null && !level.isLoaded(desk)) {
            return; // can't tell whether it's still there
        }
        if (desk == null || !isDesk(level, desk)) {
            // Also how a settlement from before 0.4.8 (no desk recorded) adopts the desk it has.
            BlockPos spare = findSpareDesk(level, settlement, desk);
            if (spare != null) {
                boolean held = villagersWithProfession(level, settlement).stream().anyMatch(v ->
                        v.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(g -> g.pos().equals(spare)).orElse(false));
                if (!held && level.getPoiManager().getFreeTickets(spare) == 0) {
                    level.getPoiManager().release(spare); // it was held inert; now it's the desk
                }
                settlement.setDeskPos(spare);
            } else if (desk != null) {
                settlement.setDeskPos(null);
            }
        }
        handToLeader(level, settlement);
        Villager leader = boundLeader(level, settlement);
        if (leader != null && settlement.isGymCleared()) {
            lock(leader);
        }
    }

    /** The first defeat locks the leader in (one point of trading XP, as a first trade would). */
    public static void lock(Villager leader) {
        if (leader.getVillagerXp() == 0) {
            leader.setVillagerXp(1);
        }
    }

    /**
     * Make sure the settlement's desk belongs to its bound leader: stand down anyone else
     * holding the profession here, then give the leader the desk if it hasn't got it. (A leader
     * that isn't loaded can't be told from a dead one, so nothing is reserved for it: a dead
     * leader's desk must stay claimable for its successor.)
     */
    private static void handToLeader(ServerLevel level, SettlementData settlement) {
        BlockPos desk = settlement.deskPos();
        if (settlement.gymLeaderId() == null) {
            return; // no leader yet: the desk stays free for the first villager to claim
        }
        Villager leader = boundLeader(level, settlement);
        if (leader == null) {
            return;
        }
        for (Villager other : villagersWithProfession(level, settlement)) {
            if (!other.getUUID().equals(leader.getUUID())) {
                standDown(level, other);
            }
        }
        if (desk == null) {
            return;
        }
        PoiManager pois = level.getPoiManager();
        if (!pois.existsAtPosition(TerraTownsRegistries.GYM_LEADERS_DESK_POI_KEY, desk)) {
            return; // not registered yet (see onPlaced); releasing it would throw. Next scan.
        }
        Optional<GlobalPos> site = leader.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        boolean holdsIt = site.isPresent() && site.get().pos().equals(desk)
                && leader.getVillagerData().getProfession() == TerraTownsRegistries.GYM_LEADER_PROFESSION.get();
        if (holdsIt) {
            return;
        }
        if (site.isPresent()) {
            leader.releasePoi(MemoryModuleType.JOB_SITE); // whatever it held before
        }
        if (pois.getFreeTickets(desk) == 0) {
            pois.release(desk); // reserved for the leader (or held by a villager just stood down)
        }
        occupy(level, desk);
        leader.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        leader.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), desk));
        leader.setVillagerData(leader.getVillagerData().setProfession(TerraTownsRegistries.GYM_LEADER_PROFESSION.get()));
        leader.refreshBrain(level);
        TerraTowns.LOGGER.info("Gym desk at {} handed to leader {} (settlement {})", desk, leader.getUUID(), settlement.id());
    }

    private static void standDown(ServerLevel level, Villager v) {
        v.releasePoi(MemoryModuleType.JOB_SITE);
        v.setVillagerXp(0);
        v.setVillagerData(v.getVillagerData().setProfession(VillagerProfession.NONE));
        v.refreshBrain(level);
        TerraTowns.LOGGER.info("Stood down {} as gym leader: the settlement's leader has priority", v.getUUID());
    }

    /** Take the desk's one job-site ticket so no villager's AI can claim it. */
    private static void occupy(ServerLevel level, BlockPos desk) {
        level.getPoiManager().take(h -> h.is(TerraTownsRegistries.GYM_LEADERS_DESK_POI_KEY),
                (h, p) -> p.equals(desk), desk, 1);
    }

    @Nullable
    private static Villager boundLeader(ServerLevel level, SettlementData settlement) {
        if (settlement.gymLeaderId() == null) {
            return null;
        }
        Entity e = level.getEntity(settlement.gymLeaderId());
        return e instanceof Villager v && v.isAlive() ? v : null;
    }

    private static List<Villager> villagersWithProfession(ServerLevel level, SettlementData settlement) {
        double r = settlement.registrationRadius();
        AABB box = AABB.ofSize(Vec3.atCenterOf(settlement.center()), 2.0 * r, 2.0 * r, 2.0 * r);
        return level.getEntitiesOfClass(Villager.class, box,
                v -> v.getVillagerData().getProfession() == TerraTownsRegistries.GYM_LEADER_PROFESSION.get());
    }

    /** Another desk standing in the settlement, if any (not {@code except}). */
    @Nullable
    private static BlockPos findSpareDesk(ServerLevel level, SettlementData settlement, @Nullable BlockPos except) {
        return level.getPoiManager().getInRange(h -> h.is(TerraTownsRegistries.GYM_LEADERS_DESK_POI_KEY),
                        settlement.center(), settlement.registrationRadius(), PoiManager.Occupancy.ANY)
                .map(r -> r.getPos())
                .filter(p -> !p.equals(except) && isDesk(level, p))
                .findFirst().orElse(null);
    }

    private static boolean isDesk(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof GymLeadersDeskBlock;
    }
}
