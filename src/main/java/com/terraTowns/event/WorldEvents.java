package com.terraTowns.event;

import com.terraTowns.TerraTowns;
import com.terraTowns.settlement.SettlementManager;
import com.terraTowns.worldgen.CoastalSpawnFinder;
import com.terraTowns.worldgen.HamletAudit;
import com.terraTowns.worldgen.HamletPiece;
import com.terraTowns.worldgen.JobAudit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-lifecycle handler that builds the starting hamlets, once per save.
 *
 * <p>At world creation it finds up to {@link #CANDIDATE_COUNT} cozy, flat, beach/plains/forest
 * coastal sites clustered on one continent and <b>builds a hamlet at each</b>, leaving them
 * unowned. Players claim one on first login (see {@link PlayerEvents}). Building everything up
 * front means a not-yet-claimed hamlet can never be generated on top of something a player has
 * already built. It is a one-shot cost — {@link SettlementManager#hasSpawnHamlets()} guards it,
 * so every later world load skips it entirely.</p>
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class WorldEvents {

    /** How many starting hamlets to seed the continent with (capacity for this many players). */
    public static final int CANDIDATE_COUNT = 8;
    /**
     * Minimum spacing between hamlets. Bumped well past the old 256 (which had two hamlets
     * landing ~268 blocks apart, reading as "two villages too close") so each is clearly its
     * own town with breathing room.
     */
    public static final int CANDIDATE_SPACING = 600;
    /** Hamlets must lie within this radius of the first one, keeping them on one continent. */
    public static final int CANDIDATE_ANCHOR_RADIUS = 6_000;

    private WorldEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        SettlementManager manager = SettlementManager.get(level);
        if (manager.hasSpawnHamlets()) {
            // Dev-only: a SECOND boot against an already-generated world is the only place the
            // job/rival NBT round trip can actually be observed, so the harness reuses it as a
            // reload test. Inert without -Dterratowns.hamletAudit. See HamletAudit#reload.
            HamletAudit.reload(server);
            return;
        }
        if (!manager.pendingHamlets().isEmpty()) {
            TerraTowns.LOGGER.info("[boot] resuming {} unbuilt spawn hamlets", manager.pendingHamlets().size());
            return; // onServerTick carries on building them
        }

        long findStart = System.nanoTime();
        List<BlockPos> sites = CoastalSpawnFinder.findCandidates(
                level, CANDIDATE_COUNT, CANDIDATE_SPACING, CANDIDATE_ANCHOR_RADIUS);
        long findMs = (System.nanoTime() - findStart) / 1_000_000L;

        if (sites.isEmpty()) {
            // Leave the flag unset so a later start can retry once worldgen settings change.
            TerraTowns.LOGGER.warn("No spawn-hamlet sites found; will retry next start");
            return;
        }
        TerraTowns.LOGGER.info("[boot] found {} spawn-hamlet sites in {} ms", sites.size(), findMs);
        manager.recordHamletCounts(CANDIDATE_COUNT, sites.size());

        // (The dev job harness is the exception: it stages every hamlet's villagers in one pass
        // right after the build, which needs them all still loaded - so it builds in one go.)
        if (server.isDedicatedServer() && !JobAudit.ENABLED) {
            // One per tick, starting with the first (players' spawn) on the next tick: each can
            // take several seconds in a big modpack (it loads and generates its chunks), and a
            // dedicated server's watchdog kills any single tick over max-tick-time (60 s) — which
            // all eight at once exceeded once the spawn continent stopped being frozen and every
            // site qualified; the site search alone can take a good part of that tick.
            manager.setPendingHamlets(sites);
        } else {
            // Single player: this is still the loading screen, the best place for the wait.
            int n = 0;
            for (BlockPos site : sites) {
                buildNext(server, level, manager, site, ++n);
            }
            finishSpawnHamlets(server, manager);
        }
    }

    /** Build one queued starting hamlet per server tick until the queue is empty. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        SettlementManager manager = SettlementManager.get(level);
        if (manager.hasSpawnHamlets() || manager.pendingHamlets().isEmpty()) {
            return;
        }
        BlockPos site = manager.takePendingHamlet();
        buildNext(server, level, manager, site, manager.spawnHamletCount() + 1);
        if (manager.pendingHamlets().isEmpty()) {
            finishSpawnHamlets(server, manager);
        }
    }

    private static void buildNext(MinecraftServer server, ServerLevel level, SettlementManager manager,
                                  BlockPos site, int number) {
        long start = System.nanoTime();
        BlockPos spawn = HamletPiece.place(level, site);
        manager.addSpawnHamlet(site, null, spawn, "Hamlet " + number);
        if (number == 1) {
            level.setDefaultSpawnPos(spawn, 0.0f);
        }
        TerraTowns.LOGGER.info("[boot] built spawn hamlet {} in {} ms", number, (System.nanoTime() - start) / 1_000_000L);
    }

    private static void finishSpawnHamlets(MinecraftServer server, SettlementManager manager) {
        manager.markSpawnHamletsPlaced();
        TerraTowns.LOGGER.info("[boot] all {} spawn hamlets built", manager.spawnHamletCount());
        // Dev-only: inert unless -Dterratowns.hamletAudit=<path> was passed. See HamletAudit.
        HamletAudit.finish(server);
    }
}
