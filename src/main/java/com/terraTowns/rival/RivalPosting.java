package com.terraTowns.rival;

import com.terraTowns.TerraTowns;
import com.terraTowns.influence.InfluenceTracker;
import com.terraTowns.npc.RivalEntity;
import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Keeps each online player's rival posted to a settlement they are actually standing in.
 *
 * <p>Posting is lazy and player-driven on purpose. Spawning a rival into every settlement at
 * world start would put dozens of idle trainer NPCs into the world, all of them ticking and
 * most of them never seen; a rival that appears in the town you walked into is both cheaper and
 * reads as the rival <em>following</em> you, which is the intent.</p>
 *
 * <p>Conditions to post: the player is inside a settlement's radius, holds influence there (so a
 * rival never ambushes a town the player has no stake in), and no rival of theirs is already
 * present, and it isn't still away licking its wounds ({@link RivalPool#RETURN_COOLDOWN_TICKS}).
 * Rank comes from {@link RivalPool#rankFor} at the moment of posting.</p>
 *
 * <p><b>Beating the rival advances the settlement</b> it was posted to: each tier's promotion
 * needs the rival beaten there since the settlement reached that tier
 * ({@link SettlementData#rivalBeatenThisTier}).</p>
 */
public final class RivalPosting {

    /** How far from the settlement's spawn point to look for somewhere the rival can stand. */
    private static final int SEARCH_RADIUS = 8;

    private RivalPosting() {
    }

    /** Run one posting pass across all online players. Called from the throttled tick scan. */
    public static void tick(MinecraftServer server, ServerLevel level, SettlementManager manager) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        RivalPool pool = RivalPool.get(level);
        InfluenceTracker influence = InfluenceTracker.get(level);

        for (ServerPlayer player : players) {
            if (player.level() != level) {
                continue;
            }
            SettlementData here = settlementContaining(manager, player);
            if (here == null || !influence.hasInfluence(player.getUUID(), here.id())) {
                continue;
            }
            if (rivalPresent(level, here, player.getUUID())
                    || pool.onCooldown(player.getUUID(), level.getGameTime())) {
                continue;
            }
            post(level, pool, here, player.getUUID(), player.getGameProfile().getName());
        }
    }

    /** @return the settlement whose radius contains the player, or null. */
    private static SettlementData settlementContaining(SettlementManager manager, ServerPlayer player) {
        for (SettlementData s : manager.all()) {
            double r = s.registrationRadius();
            if (player.blockPosition().distSqr(s.center()) <= r * r) {
                return s;
            }
        }
        return null;
    }

    public static boolean rivalPresent(ServerLevel level, SettlementData settlement, UUID playerId) {
        double r = settlement.registrationRadius();
        AABB box = AABB.ofSize(Vec3.atCenterOf(settlement.center()), 2.0 * r, 2.0 * r, 2.0 * r);
        return !level.getEntitiesOfClass(RivalEntity.class, box, e -> e.belongsTo(playerId)).isEmpty();
    }

    /**
     * Spawn {@code playerId}'s rival in {@code settlement}, standing on the ground near the
     * settlement's spawn point.
     *
     * <p>It used to appear four blocks from the settlement centre at the top of the heightmap.
     * Since 0.4.4 the centre is the well, so that put the rival on the well's roof. The spawn
     * point is where the player first arrives and is guaranteed walkable, so the rival waits
     * beside it — on real ground, never on a roof.</p>
     *
     * <p>Public so the dev harness can post a rival for a stand-in player; normal play only
     * reaches it through {@link #tick}.</p>
     *
     * @return the rival, or null if no standable spot was found.
     */
    @Nullable
    public static RivalEntity post(ServerLevel level, RivalPool pool, SettlementData settlement,
                                   UUID playerId, String playerName) {
        BlockPos spot = standingSpotNear(level,
                settlement.spawnPoint() != null ? settlement.spawnPoint() : settlement.center());
        if (spot == null) {
            TerraTowns.LOGGER.warn("No standable spot to post {}'s rival in settlement {}",
                    playerName, settlement.id());
            return null;
        }
        RivalPool.Record record = pool.record(playerId);
        int rank = pool.rankFor(level, playerId);
        RivalEntity rival = TerraTownsRegistries.RIVAL.get().spawn(level, spot, MobSpawnType.EVENT);
        if (rival == null) {
            TerraTowns.LOGGER.warn("Could not post rival for {} at {}", playerName, spot);
            return null;
        }
        rival.post(playerId, record.rivalId(), rank);
        rival.setPersistenceRequired();
        // A rival looks exactly like any other villager, so without a visible tag it can't be
        // found at all -- the likely reason playtesting turned up "no rivals".
        rival.setCustomName(Component.translatable("entity.terra_towns.rival.tag", rank));
        rival.setCustomNameVisible(true);
        pool.setPostedAt(playerId, settlement.id());
        TerraTowns.LOGGER.debug("Posted rank-{} rival for {} at {}", rank, playerName, spot);
        return rival;
    }

    /**
     * The player beat their rival. Records the win (rank goes up, the one-day cooldown starts),
     * credits the settlement it was posted to toward that settlement's next promotion, and
     * un-posts it. The caller removes the entity.
     *
     * @return the settlement credited, or null if the rival wasn't posted anywhere known.
     */
    @Nullable
    public static SettlementData defeated(ServerLevel level, UUID playerId) {
        RivalPool pool = RivalPool.get(level);
        UUID postedAt = pool.record(playerId).postedAt();
        pool.recordDefeat(playerId, level.getGameTime());
        pool.setPostedAt(playerId, null);
        if (postedAt == null) {
            return null;
        }
        SettlementManager manager = SettlementManager.get(level);
        SettlementData settlement = manager.byId(postedAt);
        if (settlement != null && !settlement.rivalBeatenThisTier()) {
            settlement.markRivalBeaten();
            manager.setDirty();
        }
        return settlement;
    }

    /**
     * The nearest spot to {@code origin}, searching outward in rings, where a villager can stand:
     * a solid block underfoot and two clear blocks above it, within two blocks of the origin's
     * height so it is the same walking level and not a roof. Skips the origin column itself —
     * that is where the player spawns.
     */
    @Nullable
    private static BlockPos standingSpotNear(ServerLevel level, BlockPos origin) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int r = 2; r <= SEARCH_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // ring only
                    }
                    for (int dy = -2; dy <= 2; dy++) {
                        int x = origin.getX() + dx;
                        int y = origin.getY() + dy;
                        int z = origin.getZ() + dz;
                        boolean floor = level.getBlockState(p.set(x, y - 1, z)).isFaceSturdy(level, p, Direction.UP);
                        boolean feet = level.getBlockState(p.set(x, y, z)).getCollisionShape(level, p).isEmpty();
                        boolean head = level.getBlockState(p.set(x, y + 1, z)).getCollisionShape(level, p).isEmpty();
                        boolean dry = level.getFluidState(p.set(x, y, z)).isEmpty();
                        if (floor && feet && head && dry) {
                            return new BlockPos(x, y, z);
                        }
                    }
                }
            }
        }
        return null;
    }
}
