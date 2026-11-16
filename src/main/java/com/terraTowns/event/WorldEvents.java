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

    /** /reload may have changed the #terra_towns:job tags: classify professions again. */
    @SubscribeEvent
    public static void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (event.getUpdateCause() == net.neoforged.neoforge.event.TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD
                && server != null && server.overworld() != null) {
            com.terraTowns.settlement.ProfessionJobs.classifyAll(server.overworld());
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        com.terraTowns.worldgen.HamletChunkPrefetch.reset();
        // Which job each profession belongs to: tags, else a guess (0.5.5). Before anything
        // staffs a job or counts workers.
        com.terraTowns.settlement.ProfessionJobs.classifyAll(level);
        if (com.terraTowns.worldgen.TerrainAudit.terrainOnly()) {
            // Dev: sweeping terrain settings or auditing a seed's villages; no hamlets. With an
            // audit path it measures the terrain and halts; without, the server just waits.
            if (HamletAudit.ENABLED) {
                HamletAudit.terrainOnly(server);
            }
            String landmass = System.getProperty("terratowns.landmassAudit");
            if (landmass != null) {
                HamletAudit.writeTo(landmass, java.util.Map.of("landmass", com.terraTowns.worldgen.TerrainAudit.landmass(level)));
            }
            return;
        }
        SettlementManager manager = SettlementManager.get(level);
        if (manager.hasSpawnHamlets()) {
            // Dev-only: a SECOND boot against an already-generated world is the only place the
            // job/rival NBT round trip can actually be observed, so the harness reuses it as a
            // reload test. Inert without -Dterratowns.hamletAudit. See HamletAudit#reload.
            HamletAudit.reload(server);
            return;
        }
        if (!manager.pendingHamlets().isEmpty()) {
            // A first load that stopped part-way (game closed, crash). Carry on with the saved
            // sites; never search again, and never rebuild a hamlet already built.
            TerraTowns.LOGGER.info("[boot] resuming {} unbuilt spawn hamlets", manager.pendingHamlets().size());
            if (!server.isDedicatedServer() || JobAudit.ENABLED) {
                buildPendingNow(server, level, manager);
            }
            return; // dedicated: onServerTick carries on building them
        }

        if (!server.isRunning()) {
            // Already stopping: the loading screen's "Try a New Seed" is throwing this world away.
            return;
        }
        long findStart = System.nanoTime();
        com.terraTowns.integration.LoadingMapHook.message("Looking for a sheltered coast for the starting hamlets");
        List<BlockPos> sites = CoastalSpawnFinder.findCandidates(
                level, CANDIDATE_COUNT, CANDIDATE_SPACING, CANDIDATE_ANCHOR_RADIUS);
        long findMs = (System.nanoTime() - findStart) / 1_000_000L;
        if (!server.isRunning()) {
            // Stopped part-way for a new seed: the search gave up early, so record nothing.
            TerraTowns.LOGGER.info("[boot] server stopping; spawn-hamlet search abandoned");
            return;
        }
        warnAboutSites(sites);

        if (sites.isEmpty()) {
            // Even the relaxed checks found nothing. Settle it anyway: the search is deterministic,
            // so a retry on the next start would only repeat the same long, failing search on
            // every load (the user, 2026-11-05). The op notice reports 0 of N; players spawn at
            // the world spawn.
            TerraTowns.LOGGER.error("No spawn-hamlet site found even with relaxed checks; this world has no starting hamlets");
            manager.recordHamletCounts(CANDIDATE_COUNT, 0);
            finishSpawnHamlets(server, manager);
            return;
        }
        TerraTowns.LOGGER.info("[boot] found {} spawn-hamlet sites in {} ms", sites.size(), findMs);
        manager.recordHamletCounts(CANDIDATE_COUNT, sites.size());

        // Save the sites before building anything: a load cut short from here on resumes with
        // the building instead of searching again (the user, 2026-11-05).
        manager.setPendingHamlets(sites);
        checkpoint(level, false);

        // (The dev job harness is the exception: it stages every hamlet's villagers in one pass
        // right after the build, which needs them all still loaded - so it builds in one go.)
        if (server.isDedicatedServer() && !JobAudit.ENABLED) {
            // One per tick, starting with the first (players' spawn) on the next tick: each can
            // take several seconds in a big modpack (it loads and generates its chunks), and a
            // dedicated server's watchdog kills any single tick over max-tick-time (60 s) — which
            // all eight at once exceeded once the spawn continent stopped being frozen and every
            // site qualified; the site search alone can take a good part of that tick.
            return;
        }
        // Single player: this is still the loading screen, the best place for the wait.
        buildPendingNow(server, level, manager);
    }

    /**
     * Tell the loading screen when the starting hamlets came out poorly, before minutes go into
     * building them, so the player can try a new seed (the user, 2026-11-10: hamlets in tight pairs
     * along half the coast).
     */
    private static void warnAboutSites(List<BlockPos> sites) {
        if (sites.size() < CANDIDATE_COUNT) {
            com.terraTowns.integration.LoadingMapHook.warn(
                    "Only " + sites.size() + " out of " + CANDIDATE_COUNT + " hamlets could be placed");
        }
        if (CoastalSpawnFinder.lastSpacingProblem() != null) {
            com.terraTowns.integration.LoadingMapHook.warn("Hamlet placement appears to be non-optimal");
        }
    }

    /** Build every queued starting hamlet in one go (single player: the loading screen). */
    private static void buildPendingNow(MinecraftServer server, ServerLevel level, SettlementManager manager) {
        int total = manager.spawnHamletCount() + manager.pendingHamlets().size();
        com.terraTowns.integration.LoadingMapHook.clearProbe();
        while (!manager.pendingHamlets().isEmpty()) {
            if (!server.isRunning()) {
                // "Try a New Seed": the world is being thrown away; don't build the rest.
                TerraTowns.LOGGER.info("[boot] server stopping; {} spawn hamlets left unbuilt", manager.pendingHamlets().size());
                com.terraTowns.worldgen.HamletChunkPrefetch.releaseAll(level);
                return;
            }
            BlockPos site = manager.takePendingHamlet();
            int n = manager.spawnHamletCount() + 1;
            com.terraTowns.integration.LoadingMapHook.message("Building hamlet " + n + " of " + total);
            com.terraTowns.integration.LoadingMapHook.building(site.getX(), site.getZ());
            buildNext(server, level, manager, site, n);
            com.terraTowns.integration.LoadingMapHook.built(site.getX(), site.getZ());
        }
        com.terraTowns.integration.LoadingMapHook.clearProbe();
        com.terraTowns.integration.LoadingMapHook.message("Hamlets built. Taking you to the first one");
        finishSpawnHamlets(server, manager);
    }

    /**
     * Write the settlement data to disk now, and with {@code chunks} the world's changed chunks
     * too. The world otherwise saves only on autosave or quit, but the chunks of a finished hamlet
     * reach disk as soon as they unload, while the next one builds. A load cut short then left
     * hamlets on disk that the saved data didn't know about, and the next start built them again
     * on top (the user, 2026-11-05: two wells and two sets of houses on one site).
     */
    private static void checkpoint(ServerLevel level, boolean chunks) {
        long start = System.nanoTime();
        if (chunks) {
            // Not save(true): a flushing save also unloads every chunk waiting to unload, which
            // pulled the built hamlets out from under the dev job audit (harness, 2026-11-05).
            // Queue the writes, then wait for them, so the chunks are on disk before the data
            // that says the hamlet exists.
            level.getChunkSource().save(false);
            level.getChunkSource().chunkMap.flushWorker();
        }
        level.getDataStorage().save();
        TerraTowns.LOGGER.info("[boot] checkpoint saved{} in {} ms", chunks ? " with chunks" : "",
                (System.nanoTime() - start) / 1_000_000L);
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
        // The site is already off the queue; saving that first means a load cut short in the
        // middle of this build skips the site next time rather than building over a half-made
        // hamlet. One lost hamlet beats two on one spot.
        checkpoint(level, false);
        long start = System.nanoTime();
        // This site's chunks and the next ones' generate in parallel from here on.
        com.terraTowns.worldgen.HamletChunkPrefetch.ahead(level, site, manager.pendingHamlets());
        HamletPiece.Built built = HamletPiece.place(level, site);
        com.terraTowns.worldgen.HamletChunkPrefetch.release(level, site);
        BlockPos spawn = built.spawn();
        com.terraTowns.settlement.SettlementData hamlet = manager.addSpawnHamlet(site, null, spawn, "Hamlet " + number);
        hamlet.setFootprint(built.bounds());
        hamlet.addHouses(built.houses());
        hamlet.addFarms(built.farms());
        com.terraTowns.structure.BuildingDetection.refreshAll(level, hamlet);
        if (number == 1) {
            level.setDefaultSpawnPos(spawn, 0.0f);
        }
        TerraTowns.LOGGER.info("[boot] built spawn hamlet {} in {} ms", number, (System.nanoTime() - start) / 1_000_000L);
        checkpoint(level, true);
    }

    private static void finishSpawnHamlets(MinecraftServer server, SettlementManager manager) {
        // What was actually built (a site skipped after a cut-short load counts as missing).
        manager.recordHamletCounts(manager.hamletTarget(), manager.spawnHamletCount());
        manager.markSpawnHamletsPlaced();
        com.terraTowns.worldgen.HamletChunkPrefetch.releaseAll(server.overworld());
        checkpoint(server.overworld(), false);
        TerraTowns.LOGGER.info("[boot] all {} spawn hamlets built", manager.spawnHamletCount());
        // Dev-only: inert unless -Dterratowns.hamletAudit=<path> was passed. See HamletAudit.
        HamletAudit.finish(server);
    }
}
