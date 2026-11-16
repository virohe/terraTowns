package com.terraTowns.worldgen;

import com.terraTowns.TerraTowns;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the starting hamlets' chunks in parallel ahead of their builds (the user, 2026-11-10:
 * "feels like a place that could use a lot of optimization"). Rig profile, 8 hamlets on the real
 * pack: 42-86 s a build, the first lava pass alone 15-50 s for ~210 chunks, because
 * {@link HamletPiece} asks for them one blocking {@code getChunk} at a time on the server thread
 * while the chunk workers sit mostly idle.
 *
 * <p>Before a site is built, its chunks and the next {@link #AHEAD} sites' get load tickets, so the
 * workers generate them all at once while the server thread builds; a site's tickets go once it is
 * built. One full-status ticket per chunk (distance 0, the level {@code getChunk}'s own ticket
 * uses) rather than one region ticket, whose inner chunks would tick: lava flowing and mobs spawning
 * on land nobody is near. Not saved: a load cut short simply starts over with no tickets.</p>
 */
public final class HamletChunkPrefetch {

    /** Sites prefetched past the one being built. Each is ~290 chunks; three sites stay well in memory. */
    static final int AHEAD = 2;
    /**
     * Chunks each way from the site: the build's first lava pass loads ~110 blocks round the
     * settled centre ({@code PLOT_RADIUS + ROUTE_REACH + 2 * WELL_SEARCH_RADIUS} plus two chunks),
     * and settling moves the centre a little.
     */
    static final int RADIUS = 8;

    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("terra_towns_hamlet_prefetch", Comparator.comparingLong(ChunkPos::toLong));

    /** Site -> the chunks ticketed for it. */
    private static final Map<BlockPos, List<ChunkPos>> held = new HashMap<>();

    private HamletChunkPrefetch() {
    }

    /** Ticket {@code current} and the next sites still waiting, unless already ticketed. */
    public static void ahead(ServerLevel level, BlockPos current, List<BlockPos> pending) {
        long start = System.nanoTime();
        int added = hold(level, current);
        for (int i = 0; i < Math.min(AHEAD, pending.size()); i++) {
            added += hold(level, pending.get(i));
        }
        if (added > 0) {
            TerraTowns.LOGGER.info("[build-profile] prefetch: {} chunks ticketed, {} sites held ({} ms)",
                    added, held.size(), (System.nanoTime() - start) / 1_000_000L);
        }
    }

    private static int hold(ServerLevel level, BlockPos site) {
        if (held.containsKey(site)) {
            return 0;
        }
        ChunkPos c = new ChunkPos(site);
        List<ChunkPos> chunks = new java.util.ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                ChunkPos p = new ChunkPos(c.x + dx, c.z + dz);
                level.getChunkSource().addRegionTicket(TICKET, p, 0, p);
                chunks.add(p);
            }
        }
        held.put(site, chunks);
        return chunks.size();
    }

    /** The site is built: its chunks may unload as usual. */
    public static void release(ServerLevel level, BlockPos site) {
        List<ChunkPos> chunks = held.remove(site);
        if (chunks == null) {
            return;
        }
        for (ChunkPos p : chunks) {
            level.getChunkSource().removeRegionTicket(TICKET, p, 0, p);
        }
    }

    /** A new server: forget sites from the last one (their tickets went with its levels). */
    public static void reset() {
        held.clear();
    }

    /** Drop every ticket (all built, or the server is stopping). */
    public static void releaseAll(ServerLevel level) {
        for (BlockPos site : List.copyOf(held.keySet())) {
            release(level, site);
        }
    }
}
