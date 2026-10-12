package com.terraTowns.settlement;

import com.terraTowns.TerraTowns;
import com.terraTowns.block.GymLeadersDeskBlock;
import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a settlement's gym leader is, and which desk is theirs.
 *
 * <p><b>Leaders are appointed, never recruited</b> (0.5.1). Until 0.5.0 the desk was a vanilla
 * workstation and whichever villager claimed it first became leader. In a playtest the wrong
 * villager claimed a village gym's desk, couldn't get through the gym's door, and the gym was
 * left with nobody to challenge. Now no villager can claim a desk at all (it is not an
 * acquirable job site), and a leader is made in one of two ways:</p>
 * <ul>
 *   <li>a player right-clicks an unemployed villager with a Gym Leader's Desk in hand
 *       ({@link #appoint}); or</li>
 *   <li>an unowned generated village spawns one beside its gym's desk ({@link #appointLeader}),
 *       so a village always has somebody to challenge.</li>
 * </ul>
 * <p>Either way the leader is bound to the settlement whose footprint it stands in
 * ({@link SettlementData#covers}), one per settlement, and locked the way a villager you've
 * traded with is (one point of trading XP: vanilla then never takes its profession away, desk or
 * no desk). The post falls vacant only when the leader dies or is turned into a zombie
 * ({@link #onLeaderGone}).</p>
 *
 * <p><b>One desk per settlement.</b> The settlement remembers its desk ({@link SettlementData#deskPos})
 * and hands it to the leader as its job site, which is what walks the leader there to work. A
 * second desk placed while that one stands does nothing. If the settlement's desk goes, a spare
 * desk still standing in the settlement takes over. A villager holding the profession here that
 * isn't the bound leader (only possible in a world from before 0.5.1) is stood down.</p>
 */
public final class GymDesk {

    /** Why an appointment was or wasn't made ({@link #appoint}). */
    public enum Appointment {
        APPOINTED, NOT_UNEMPLOYED, NOT_IN_SETTLEMENT, NOT_YOURS, HAS_LEADER
    }

    private GymDesk() {
    }

    /**
     * A player (or the harness, with no player) appoints {@code villager} gym leader of the
     * settlement it stands in. Refused unless it is an unemployed adult, inside a settlement's
     * footprint, the settlement is unowned or the player's, and it has no leader already.
     */
    public static Appointment appoint(ServerLevel level, Villager villager, @Nullable UUID player) {
        if (villager.isBaby() || villager.getVillagerData().getProfession() != VillagerProfession.NONE) {
            return Appointment.NOT_UNEMPLOYED;
        }
        SettlementManager manager = SettlementManager.get(level);
        SettlementData settlement = manager.containing(villager.blockPosition());
        if (settlement == null) {
            return Appointment.NOT_IN_SETTLEMENT;
        }
        if (settlement.ownerId() != null && !settlement.ownerId().equals(player)) {
            return Appointment.NOT_YOURS;
        }
        if (settlement.gymLeaderId() != null && !leaderMissing(level, settlement)) {
            return Appointment.HAS_LEADER;
        }
        makeLeader(villager);
        bind(level, manager, settlement, villager);
        return Appointment.APPOINTED;
    }

    /**
     * Spawn an unowned generated village's leader beside its gym's desk. Null while the desk
     * isn't loaded or there's nowhere beside it to stand. The caller binds it.
     */
    @Nullable
    public static Villager appointLeader(ServerLevel level, SettlementData settlement) {
        BlockPos desk = settlement.deskPos();
        if (desk == null || !isDesk(level, desk)) {
            desk = findSpareDesk(level, settlement, null);
            if (desk == null) {
                return null;
            }
            settlement.setDeskPos(desk);
        }
        BlockPos at = standingSpot(level, desk);
        if (at == null) {
            return null;
        }
        Villager v = EntityType.VILLAGER.create(level);
        if (v == null) {
            return null;
        }
        Direction facing = level.getBlockState(desk).getValue(HorizontalDirectionalBlock.FACING);
        v.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, facing.toYRot(), 0.0F);
        v.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.STRUCTURE, null);
        v.setVillagerData(v.getVillagerData().setType(VillagerType.byBiome(level.getBiome(at))));
        makeLeader(v);
        level.addFreshEntity(v);
        TerraTowns.LOGGER.info("Spawned gym leader {} at {} beside desk {} (settlement {})",
                v.getUUID(), at, desk, settlement.id());
        return v;
    }

    /** Bind {@code leader} to the settlement and give it the desk, if there is one. */
    public static void bind(ServerLevel level, SettlementManager manager, SettlementData settlement, Villager leader) {
        settlement.setGymLeaderId(leader.getUUID());
        manager.setDirty();
        TerraTowns.LOGGER.info("Bound gym leader {} to settlement {}", leader.getUUID(), settlement.id());
        handToLeader(level, settlement);
    }

    /** The gym profession, locked in, and never despawning. */
    private static void makeLeader(Villager v) {
        v.setVillagerData(v.getVillagerData().setProfession(TerraTownsRegistries.GYM_LEADER_PROFESSION.get()));
        lock(v);
        v.setPersistenceRequired();
    }

    /** One point of trading XP, as a first trade would: vanilla then keeps the profession. */
    public static void lock(Villager leader) {
        if (leader.getVillagerXp() == 0) {
            leader.setVillagerXp(1);
        }
    }

    /**
     * A villager died or was converted: if it led a settlement's gym, the post is vacant. The
     * owner is told so they can appoint a successor; an unowned generated village gets one on
     * the next settlement scan.
     */
    public static void onLeaderGone(ServerLevel level, Villager villager) {
        SettlementManager manager = SettlementManager.get(level);
        for (SettlementData s : manager.all()) {
            if (!villager.getUUID().equals(s.gymLeaderId())) {
                continue;
            }
            s.setGymLeaderId(null);
            manager.setDirty();
            TerraTowns.LOGGER.info("Gym leader {} of settlement {} is gone", villager.getUUID(), s.id());
            ServerPlayer owner = s.ownerId() == null ? null : level.getServer().getPlayerList().getPlayer(s.ownerId());
            if (owner != null) {
                owner.sendSystemMessage(Component.translatable("message.terra_towns.gym_leader.gone",
                        s.name() != null ? Component.literal(s.name()) : Component.translatable("hud.terra_towns.unnamed"))
                        .withStyle(ChatFormatting.GOLD));
            }
        }
    }

    /**
     * Is the bound leader provably gone without a death we saw (discarded by a command or
     * another mod)? Only when the whole settlement is loaded and it still can't be found: a
     * leader merely out of loaded range reads the same as a missing one.
     */
    private static boolean leaderMissing(ServerLevel level, SettlementData settlement) {
        if (level.getEntity(settlement.gymLeaderId()) != null) {
            return false;
        }
        AABB a = settlement.area();
        int y = settlement.center().getY();
        for (double x : new double[]{a.minX, a.maxX - 1}) {
            for (double z : new double[]{a.minZ, a.maxZ - 1}) {
                if (!level.isPositionEntityTicking(BlockPos.containing(x, y, z))) {
                    return false;
                }
            }
        }
        return true;
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
            TerraTowns.LOGGER.info("Second gym desk at {} in settlement {} left inert (desk is at {})",
                    pos, settlement.id(), current);
            return;
        }
        settlement.setDeskPos(pos);
        handToLeader(level, settlement);
    }

    /**
     * Periodic upkeep (settlement scan, loaded settlements only): re-home the settlement's desk
     * if it's gone, give it to the leader, stand down anyone else holding the profession, and
     * lock a leader bound before 0.5.1 (when leaders were only locked once beaten).
     */
    public static void tick(ServerLevel level, SettlementData settlement) {
        BlockPos desk = settlement.deskPos();
        if (desk != null && !level.isLoaded(desk)) {
            return; // can't tell whether it's still there
        }
        if (desk == null || !isDesk(level, desk)) {
            // Also how a settlement from before 0.4.8 (no desk recorded) adopts the desk it has.
            settlement.setDeskPos(findSpareDesk(level, settlement, desk));
        }
        handToLeader(level, settlement);
        Villager leader = boundLeader(level, settlement);
        if (leader != null) {
            lock(leader);
        }
    }

    /**
     * Make sure the settlement's desk belongs to its bound leader: stand down anyone else
     * holding the profession here, then make the desk the leader's job site if it isn't.
     */
    private static void handToLeader(ServerLevel level, SettlementData settlement) {
        Villager leader = boundLeader(level, settlement);
        if (leader == null) {
            return;
        }
        for (Villager other : villagersWithProfession(level, settlement)) {
            if (!other.getUUID().equals(leader.getUUID())) {
                standDown(level, other);
            }
        }
        BlockPos desk = settlement.deskPos();
        if (desk == null) {
            return; // stays leader, deskless, until one is placed
        }
        PoiManager pois = level.getPoiManager();
        if (!pois.existsAtPosition(TerraTownsRegistries.GYM_LEADERS_DESK_POI_KEY, desk)) {
            return; // not registered yet (see onPlaced); taking it would throw. Next scan.
        }
        Optional<GlobalPos> site = leader.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        if (site.isPresent() && site.get().pos().equals(desk)) {
            return;
        }
        if (site.isPresent()) {
            leader.releasePoi(MemoryModuleType.JOB_SITE); // whatever it held before
        }
        if (pois.getFreeTickets(desk) > 0) {
            // The leader's job site, as vanilla would have recorded a claim; when it loses the
            // desk vanilla gives this back.
            pois.take(h -> h.is(TerraTownsRegistries.GYM_LEADERS_DESK_POI_KEY), (h, p) -> p.equals(desk), desk, 1);
        }
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

    @Nullable
    private static Villager boundLeader(ServerLevel level, SettlementData settlement) {
        if (settlement.gymLeaderId() == null) {
            return null;
        }
        Entity e = level.getEntity(settlement.gymLeaderId());
        return e instanceof Villager v && v.isAlive() ? v : null;
    }

    /**
     * Living villagers on the settlement's ground holding the gym profession. (A leader just
     * killed lies there for its death animation, still a gym leader; adopting it re-bound the
     * post to a corpse.)
     */
    public static List<Villager> villagersWithProfession(ServerLevel level, SettlementData settlement) {
        return level.getEntitiesOfClass(Villager.class, settlement.area(),
                v -> v.isAlive() && v.getVillagerData().getProfession() == TerraTownsRegistries.GYM_LEADER_PROFESSION.get()
                        && settlement.covers(v.blockPosition()));
    }

    /** Another desk standing on the settlement's ground, if any (not {@code except}). */
    @Nullable
    private static BlockPos findSpareDesk(ServerLevel level, SettlementData settlement, @Nullable BlockPos except) {
        BoundingBox fp = settlement.footprint();
        int reach = fp == null ? settlement.registrationRadius()
                : (int) Math.ceil(Math.hypot(fp.getXSpan(), fp.getZSpan()) / 2.0);
        BlockPos from = fp == null ? settlement.center() : fp.getCenter();
        return level.getPoiManager().getInRange(h -> h.is(TerraTownsRegistries.GYM_LEADERS_DESK_POI_KEY),
                        from, reach, PoiManager.Occupancy.ANY)
                .map(r -> r.getPos())
                .filter(p -> !p.equals(except) && settlement.covers(p) && isDesk(level, p))
                .findFirst().orElse(null);
    }

    /** A free spot beside the desk (in front, behind, then the sides), else on top of it. */
    @Nullable
    private static BlockPos standingSpot(ServerLevel level, BlockPos desk) {
        Direction facing = level.getBlockState(desk).getValue(HorizontalDirectionalBlock.FACING);
        for (Direction d : new Direction[]{facing, facing.getOpposite(), facing.getClockWise(), facing.getCounterClockWise()}) {
            BlockPos p = desk.relative(d);
            if (canStand(level, p)) {
                return p;
            }
        }
        return canStand(level, desk.above()) ? desk.above() : null;
    }

    private static boolean canStand(ServerLevel level, BlockPos p) {
        // Pathfindable rather than empty: a carpet aisle is somewhere to stand.
        return level.getBlockState(p).isPathfindable(PathComputationType.LAND)
                && level.getBlockState(p.above()).isPathfindable(PathComputationType.LAND)
                && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty();
    }

    private static boolean isDesk(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof GymLeadersDeskBlock;
    }
}
