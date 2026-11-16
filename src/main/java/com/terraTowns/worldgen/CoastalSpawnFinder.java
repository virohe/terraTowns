package com.terraTowns.worldgen;

import com.terraTowns.TerraTowns;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds a <em>warm, cozy, near-waterfront</em> site for the spawn hamlet — the Pallet-Town
 * feel: temperate land you'd want to settle, on the edge of a real continent, a stone's
 * throw from saltwater (not a river).
 *
 * <p>Each candidate is evaluated with <b>biome lookups only</b> — no per-candidate
 * surface-height sampling — so the search is fast and world creation does not hang. The
 * winner's surface Y is computed exactly once, at the end.</p>
 *
 * <p>A candidate qualifies when:</p>
 * <ul>
 *   <li>its biome is one of the {@linkplain #COZY cozy land biomes} (also rules out
 *       ocean/river/snowy/stony/desert shores in one step),</li>
 *   <li>the nearest ocean is within the {@link #MIN_SHORE_DISTANCE}..{@link #MAX_SHORE_DISTANCE}
 *       band — close enough to be waterfront, but far enough inland that the hamlet (and the
 *       player's spawn) sit on dry land, not in the surf,</li>
 *   <li>it is backed by a continent, not a tiny island,</li>
 *   <li>the surrounding land is temperate (neither frozen nor scorching),</li>
 *   <li>no existing settlement is too close.</li>
 * </ul>
 */
public final class CoastalSpawnFinder {

    /** Bounded so world creation can never hang: a cozy coast is relocated to, at most, this far. */
    public static final int MAX_RADIUS = 12_000;
    public static final int STEP = 24;

    /**
     * Shoreline distance band: keep the hamlet near the water, but far enough inland that the
     * whole (5-house) footprint sits on dry land. The centre ends up ~22-50 blocks from the
     * water, which puts the seaward edge right at the shore — still a waterfront hamlet.
     */
    public static final int MIN_SHORE_DISTANCE = 22;
    public static final int MAX_SHORE_DISTANCE = 50;

    /** Step used when measuring distance-to-shore (matches biome quart resolution closely). */
    private static final int SHORE_SCAN_STEP = 4;

    /** Radius (blocks) around the centre that must be solid cozy land (no beach) — kills the
     *  "grass pocket inside a beach" loophole. Kept below {@link #MIN_SHORE_DISTANCE}. */
    private static final int CORE_RADIUS = 10;

    /** Radius at which we check that the site is backed by a continent, not an island. */
    private static final int BACKING_RADIUS = 128;
    /** Of the 8 backing samples, at least this many must be land. */
    private static final int MIN_BACKING_LAND = 4;

    /** Radii (blocks) at which the surrounding land is checked for a temperate climate. */
    private static final int[] NEIGHBORHOOD_RADII = {96, 192};
    private static final float NB_MIN_TEMP = 0.15f;
    private static final float NB_MAX_TEMP = 1.5f;

    /** Keep the hamlet clear of any pre-existing settlement. */
    private static final int CLAIM_CLEARANCE = 256;

    /** Half-extent (blocks) of the hamlet footprint, used for the buildable-ground check. */
    private static final int FOOTPRINT_HALF = 20;
    /** Max surface-height spread allowed across the footprint's land (blocks). Lower = flatter. */
    private static final int MAX_RELIEF = 3;
    /** Fraction of the footprint that must be dry land (above sea level), not pond/bay. */
    private static final double MIN_LAND_FRACTION = 0.75;

    /**
     * Minimum number of buildable house plots around a site ({@link #plotCount}). The footprint
     * check above samples nine points; a site could pass it and still be a thin strip between
     * water and a slope — one such site on the 2026-09-26 test seed fitted 2 houses and no farms.
     */
    private static final int MIN_PLOTS = 20;
    private static final int PLOT_RADIUS = 34;
    private static final int PLOT_STEP = 6;
    private static final int PLOT_RELIEF = 3;

    /**
     * The checks a site must pass. {@link #STRICT} is the real bar; {@link #RELAXED} runs only when
     * the strict search found nothing anywhere, so a world never ends up without a starting hamlet
     * (the user, 2026-11-05: the search must get it right on the first load; "no sites, retry next
     * start" re-ran the same deterministic search, and failed the same way, on every load).
     *
     * @param anyTemperateLand also accept non-cozy land biomes with a temperate climate
     * @param pleasantShore    require the whole shore band to be beach/cozy/water
     */
    private record Rules(String name, boolean anyTemperateLand, boolean pleasantShore,
                         int maxRelief, double minLandFraction, int minPlots, int plotRelief) {
    }

    private static final Rules STRICT = new Rules("strict", false, true,
            MAX_RELIEF, MIN_LAND_FRACTION, MIN_PLOTS, PLOT_RELIEF);
    private static final Rules RELAXED = new Rules("relaxed", true, false,
            5, 0.6, 12, 4);
    /** Rules for the search in progress (server thread only). */
    private static Rules rules = STRICT;

    /** 8 compass directions used for the ring scans. */
    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * Land biomes a starting hamlet may sit in — warm/temperate and inviting. Built from
     * {@link #cozy} entries so adding more (vanilla or modded) is a one-line change.
     *
     * <p><b>Extending for "Oh The Biomes You'll Go":</b> add the BYG biome ids in the marked
     * section below (e.g. {@code cozy("byg", "<biome_path>")}). They are harmless no-ops until
     * BYG is installed, so they can be listed ahead of time once the exact ids are confirmed.</p>
     */
    private static final List<ResourceKey<Biome>> COZY = buildCozyList();

    private CoastalSpawnFinder() {
    }

    private static List<ResourceKey<Biome>> buildCozyList() {
        List<ResourceKey<Biome>> list = new ArrayList<>();
        // --- Vanilla cozy land biomes ---
        list.add(Biomes.PLAINS);
        list.add(Biomes.SUNFLOWER_PLAINS);
        list.add(Biomes.MEADOW);
        list.add(Biomes.FOREST);
        list.add(Biomes.FLOWER_FOREST);
        list.add(Biomes.BIRCH_FOREST);
        list.add(Biomes.OLD_GROWTH_BIRCH_FOREST);
        list.add(Biomes.DARK_FOREST);
        list.add(Biomes.CHERRY_GROVE);
        list.add(Biomes.SAVANNA);
        list.add(Biomes.SAVANNA_PLATEAU);
        // NOTE: beaches are deliberately NOT cozy — a hamlet should sit on grass/forest land
        // *next to* a beach, not on the sand itself. Beaches are still allowed as shoreline
        // (see pleasantShore), so a hamlet can border one.

        // --- Oh The Biomes We've Gone (namespace "biomeswevegone") ---
        // Warm/temperate, inviting biomes only. Hot/dry (deserts, badlands, chaparral), cold
        // (boreal/tundra/taiga/frosted/glacier), and wet (swamp/bog/jungle/marsh) variants are
        // intentionally left out. Harmless no-ops if BWG isn't installed.
        final String bwg = "biomeswevegone";
        list.add(biomeKey(bwg,"allium_shrubland"));
        list.add(biomeKey(bwg,"amaranth_grassland"));
        list.add(biomeKey(bwg,"black_forest"));
        list.add(biomeKey(bwg,"cika_woods"));
        list.add(biomeKey(bwg,"coconino_meadow"));
        list.add(biomeKey(bwg,"ebony_woods"));
        list.add(biomeKey(bwg,"enchanted_tangle"));
        list.add(biomeKey(bwg,"forgotten_forest"));
        list.add(biomeKey(bwg,"maple_taiga"));
        list.add(biomeKey(bwg,"orchard"));
        list.add(biomeKey(bwg,"overgrowth_woodlands"));
        list.add(biomeKey(bwg,"prairie"));
        list.add(biomeKey(bwg,"pumpkin_valley"));
        list.add(biomeKey(bwg,"redwood_thicket"));
        list.add(biomeKey(bwg,"rose_fields"));
        list.add(biomeKey(bwg,"sakura_grove"));
        list.add(biomeKey(bwg,"skyris_vale"));
        list.add(biomeKey(bwg,"temperate_grove"));
        list.add(biomeKey(bwg,"zelkova_forest"));

        return List.copyOf(list);
    }

    /** Build a biome {@link ResourceKey} from a namespace + path (for modded biomes). */
    private static ResourceKey<Biome> biomeKey(String namespace, String path) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    /** Convenience: the single nearest qualifying site (used where one hamlet is wanted). */
    public static Optional<BlockPos> find(ServerLevel level) {
        List<BlockPos> one = findCandidates(level, 1, 0, MAX_RADIUS);
        return one.isEmpty() ? Optional.empty() : Optional.of(one.get(0));
    }

    // --- coastline tracing --------------------------------------------------

    /** Boundary-trace cell size (blocks). Coarse enough to be fast, fine enough to follow bays. */
    private static final int CELL = 32;
    /** Max boundary cells followed around one landmass (safety budget — worldgen must not hang). */
    private static final int TRACE_BUDGET = 120_000;
    /** Distinct landmasses tried (a tiny island near spawn may yield no qualifying sites). */
    private static final int SEED_TRIES = 3;
    /**
     * Wall-clock budget for the FALLBACK searches only (coast trace, spiral scan), which run when
     * Terra Continental has no plan. The planned-coast search has no budget: it ends when it has
     * every hamlet or has tried every coast cell (the user, 2026-11-05).
     */
    private static final long SEARCH_BUDGET_NANOS = 480_000_000_000L; // 8 min
    /**
     * Inland probe distances (blocks) tried at each boundary cell, marching away from the ocean
     * along the cell's recorded seaward direction — aimed at the {@link #MIN_SHORE_DISTANCE}..
     * {@link #MAX_SHORE_DISTANCE} qualifying band rather than blind offsets. Dense (6-block
     * steps) so small qualifying pockets can't slip between probes; failed probes usually die
     * on the first cheap biome sample, so density is nearly free.
     */
    private static final int[] INLAND_PROBES = {8, 14, 20, 26, 32, 38, 44, 50};
    /** Clockwise Moore neighbourhood, starting east. */
    private static final int[][] MOORE = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    /** One traced boundary cell: its cell coordinate plus the seaward (ocean-side) direction. */
    private record CoastCell(int cx, int cz, int seaDx, int seaDz) {
    }

    /**
     * Collect up to {@code count} qualifying cozy-coastal sites spread <b>around the spawn
     * continent's coastline</b>: the coastline of the landmass nearest the origin is traced as a
     * closed loop, every stretch of it is tested for qualifying sites, and the winners are chosen
     * at even intervals along the <em>perimeter</em> — so hamlets ring the continent instead of
     * clustering on the side the old outward spiral reached first.
     *
     * <p>{@code anchorRadius} is legacy and ignored: tracing a single landmass's coast already
     * guarantees all candidates share one continent.</p>
     */
    public static List<BlockPos> findCandidates(ServerLevel level, int count, int minSpacing, int anchorRadius) {
        Probe.begin(QuartPos.fromBlock(level.getChunkSource().getGenerator().getSeaLevel()));
        spacingProblem = null;
        try {
            rules = STRICT;
            List<BlockPos> sites = search(level, count, minSpacing, anchorRadius);
            if (sites.isEmpty() && level.getServer().isRunning()) {
                TerraTowns.LOGGER.warn("No hamlet site passed the full checks anywhere; searching again with relaxed checks");
                com.terraTowns.integration.LoadingMapHook.message("No ideal coast found. Looking for a good-enough one");
                rules = RELAXED;
                sites = search(level, count, minSpacing, anchorRadius);
                TerraTowns.LOGGER.warn("Relaxed search found {} hamlet sites", sites.size());
            }
            return sites;
        } finally {
            rules = STRICT;
            Probe.end();
        }
    }

    private static List<BlockPos> search(ServerLevel level, int count, int minSpacing, int anchorRadius) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        BiomeSource biomeSource = generator.getBiomeSource();
        Climate.Sampler sampler = randomState.sampler();
        SettlementManager manager = SettlementManager.get(level);
        int seaLevel = generator.getSeaLevel();

        List<BlockPos> planned = alongPlannedCoast(level, count, minSpacing,
                generator, randomState, biomeSource, sampler, manager, seaLevel);
        if (planned != null && !planned.isEmpty()) {
            return planned;
        }

        Map<Long, Boolean> landCache = new HashMap<>();
        Set<Long> tracedCells = new HashSet<>();

        // Spiral outward (in cell space) looking for a coastal seed cell; trace that landmass's
        // whole coastline; if it yields no qualifying site (tiny island), move on to the next
        // landmass — up to SEED_TRIES.
        int x = 0, z = 0, dirX = 1, dirZ = 0, segLen = 1, segPass = 0, turns = 0;
        int maxCells = 2 * (MAX_RADIUS / CELL) + 1;
        long cellBudget = (long) maxCells * maxCells;
        int landmasses = 0;
        long deadline = System.nanoTime() + SEARCH_BUDGET_NANOS;

        for (long i = 0; i < cellBudget && landmasses < SEED_TRIES && System.nanoTime() < deadline; i++) {
            if (!tracedCells.contains(cellKey(x, z))
                    && isLandCell(landCache, biomeSource, sampler, seaLevel, x, z)) {
                int[] back = oceanNeighbor4(landCache, biomeSource, sampler, seaLevel, x, z);
                if (back != null) {
                    landmasses++;
                    List<CoastCell> boundary = traceBoundary(landCache, biomeSource, sampler, seaLevel, x, z, back);
                    for (CoastCell cell : boundary) {
                        tracedCells.add(cellKey(cell.cx(), cell.cz()));
                    }
                    long testStart = System.nanoTime();
                    List<BlockPos> sites = pickAroundPerimeter(boundary, count, minSpacing, deadline,
                            generator, level, randomState, biomeSource, sampler, manager, seaLevel);
                    TerraTowns.LOGGER.info("Coastline trace #{}: {} boundary cells, {} sites placed ({} ms)",
                            landmasses, boundary.size(), sites.size(),
                            (System.nanoTime() - testStart) / 1_000_000L);
                    dumpBiomeMap(biomeSource, sampler, seaLevel, boundary, sites);
                    if (!sites.isEmpty()) {
                        return sites;
                    }
                }
            }
            x += dirX;
            z += dirZ;
            if (++segPass == segLen) {
                segPass = 0;
                int t = dirX;
                dirX = -dirZ;
                dirZ = t;
                if (++turns == 2) {
                    turns = 0;
                    segLen++;
                }
            }
        }
        TerraTowns.LOGGER.warn("Coastline tracing found no qualifying sites on {} landmasses — "
                + "falling back to the dense spiral scan", landmasses);
        return spiralScan(level, count, minSpacing, anchorRadius,
                generator, randomState, biomeSource, sampler, manager, seaLevel);
    }

    // --- along Terra Continental's planned coast -------------------------------

    /**
     * Least distance from a hamlet to a planned village (the user, 2026-10-31: a fair walk between the
     * two, so the world reads as big from the moment a player spawns). About a 4.5-minute walk; the
     * spawn continent's villages are ~3000 blocks apart, so most of its coast is this far from them.
     */
    public static final int VILLAGE_CLEARANCE = 1200;
    /**
     * Offsets (blocks) from a plan coast cell toward its sea neighbour, tried in order: the shore lies
     * somewhere in the 128 blocks between the two, and the site sits 22-50 blocks inland of it.
     */
    private static final int[] PLAN_PROBES = {112, 100, 88, 76, 64, 52, 40, 28, 16, 4, -8, -20, -32, -44};

    /**
     * Hamlets spread evenly round the spawn continent's coast as Terra Continental plans it (the user,
     * 2026-10-31: the 32-block trace of the biome coast ran 120000 cells without closing and bunched all
     * eight hamlets on the west side). Farthest-point, like Continental's villages: each hamlet goes on
     * the coast cell farthest in a straight line from the hamlets so far, the first on the cell farthest
     * from the continent's villages' middle. Spacing by distance ALONG the coast was tried first and gave
     * a fjord-cut peninsula three of the eight (rig, same seed): its coast is long, its land isn't.
     * A cell under {@link #VILLAGE_CLEARANCE} from a village counts at half its distance, so the
     * clearance gives way only where the coast leaves no other choice; such a hamlet is logged. A cell
     * with no qualifying spot is dropped and the next best tried; when no cell is left, fewer hamlets
     * (quality first, as before).
     *
     * @return null when Terra Continental has no plan, so the caller traces the coast itself
     */
    private static List<BlockPos> alongPlannedCoast(ServerLevel level, int count, int minSpacing,
                                                    ChunkGenerator generator, RandomState randomState,
                                                    BiomeSource biomeSource, Climate.Sampler sampler,
                                                    SettlementManager manager, int seaLevel) {
        int[] coast = com.terraTowns.integration.SpawnContinentHook.coast(level);
        int[] villages = com.terraTowns.integration.SpawnContinentHook.villages(level);
        if (coast == null || villages == null || coast.length < 4 * count) {
            return null;
        }
        long start = System.nanoTime();
        int m = coast.length / 4;
        double[] clear = new double[m];
        for (int i = 0; i < m; i++) {
            clear[i] = villageDistance(villages, coast[4 * i], coast[4 * i + 1]);
        }
        double midX = 0, midZ = 0;
        for (int v = 0; v + 1 < villages.length; v += 2) {
            midX += villages[v];
            midZ += villages[v + 1];
        }
        midX /= Math.max(1, villages.length / 2);
        midZ /= Math.max(1, villages.length / 2);
        TerraTowns.LOGGER.info("Planned coast: {} cells, {} villages", m, villages.length / 2);

        List<BlockPos> found = new ArrayList<>();
        List<Integer> foundCells = new ArrayList<>();
        boolean[] dropped = new boolean[m];
        int[] stats = new int[STAT_NAMES.length];
        com.terraTowns.integration.LoadingMapHook.message("Checking the coast for hamlet sites: 0 of " + count + " found");
        // No time budget here (the user, 2026-11-05): the search runs until it has every hamlet
        // or has tried every coast cell. The coast is finite, so it always ends; an 8-minute cap
        // could stop it with good cells untried and hand the world fewer hamlets for good.
        while (found.size() < count && level.getServer().isRunning()) {
            int best = -1;
            double bestScore = -1;
            for (int i = 0; i < m; i++) {
                if (dropped[i]) continue;
                int x = coast[4 * i], z = coast[4 * i + 1];
                double d;
                if (found.isEmpty()) {
                    d = Math.hypot(x - midX, z - midZ);
                } else {
                    d = Double.MAX_VALUE;
                    for (BlockPos p : found) d = Math.min(d, Math.hypot(p.getX() - x, p.getZ() - z));
                }
                double score = clear[i] >= VILLAGE_CLEARANCE ? d : d / 2;
                if (score > bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best < 0) break;
            dropped[best] = true;
            int clearance = clear[best] >= VILLAGE_CLEARANCE ? VILLAGE_CLEARANCE : 0;
            BlockPos site = probePlanCell(coast, best, villages, clearance, found, minSpacing, stats,
                    generator, level, randomState, biomeSource, sampler, manager, seaLevel);
            if (site == null) continue;
            found.add(site);
            foundCells.add(best);
            double toVillage = villageDistance(villages, site.getX(), site.getZ());
            com.terraTowns.integration.LoadingMapHook.chosen(site.getX(), site.getZ());
            com.terraTowns.integration.LoadingMapHook.message(
                    "Checking the coast for hamlet sites: " + found.size() + " of " + count + " found");
            TerraTowns.LOGGER.info("Spawn-hamlet candidate #{} at {} (planned coast, {} blocks from the nearest village{})",
                    found.size(), site, Math.round(toVillage), toVillage < VILLAGE_CLEARANCE ? ", under the clearance" : "");
        }
        if (found.size() < count) {
            TerraTowns.LOGGER.info("Planned coast gave {} of {} hamlet sites (quality-first)", found.size(), count);
        }
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < STAT_NAMES.length; i++) {
            report.append(i > 0 ? ", " : "").append(STAT_NAMES[i]).append(' ').append(stats[i]);
        }
        TerraTowns.LOGGER.info("Site-filter tallies ({}): {}", rules.name(), report);
        TerraTowns.LOGGER.info("Planned coast: {} sites placed ({} ms)", found.size(), (System.nanoTime() - start) / 1_000_000L);
        if (level.getServer().isRunning()) { // stopped for a new seed: a partial set says nothing
            spacingProblem = assessSpacing(coast, foundCells, found, count);
        }
        Probe.report("planned coast, " + rules.name());
        List<CoastCell> drawn = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            drawn.add(new CoastCell(coast[4 * i] / CELL, coast[4 * i + 1] / CELL, coast[4 * i + 2], coast[4 * i + 3]));
        }
        dumpBiomeMap(biomeSource, sampler, seaLevel, drawn, found);
        return found;
    }

    /** Why the last planned-coast search's hamlets look poorly spread; null if fine or not judged. */
    private static String spacingProblem;

    public static String lastSpacingProblem() {
        return spacingProblem;
    }

    /**
     * Even spread round the coast would put a hamlet every {@code coast length / count} blocks of
     * shore. A stretch of coast with none over this many times that is "half the coast empty".
     */
    static final double ARC_GAP_WARN = 2.5;
    /**
     * Two hamlets nearer each other in a straight line than this share of that spacing are crowded,
     * up to {@link #CROWD_MAX} blocks: a fjord coast is long for its size, and on the 180 km2 test
     * continent (121 km of coast) a quarter of the even share was 3792, with a fine nearest pair at 3974;
     * another fine world (143 km2) had 2082, the user's poor one 733.
     */
    static final double CROWD_WARN = 0.25;
    static final int CROWD_MAX = 1500;

    /**
     * Are the hamlets poorly spread round the coast? The user's 2026-11-10 world: on a 58 km2
     * continent the eight sat in pairs 742 and 813 blocks apart along the south and north-east, and
     * none on the west coast. Two tests, logged with their numbers: the longest stretch of coast with
     * no hamlet (measured along the plan's coast loop), and the nearest pair in a straight line.
     * Fewer than two hamlets: nothing to judge (the count warning covers that).
     */
    static String assessSpacing(int[] coast, List<Integer> cells, List<BlockPos> found, int count) {
        int m = coast.length / 4;
        if (found.size() < 2 || m < 2) return null;
        double[] at = new double[m];
        double length = 0;
        for (int i = 0; i < m; i++) {
            at[i] = length;
            int j = (i + 1) % m;
            length += Math.hypot(coast[4 * j] - coast[4 * i], coast[4 * j + 1] - coast[4 * i + 1]);
        }
        double[] s = new double[cells.size()];
        for (int k = 0; k < s.length; k++) s[k] = at[cells.get(k)];
        java.util.Arrays.sort(s);
        double maxGap = length - s[s.length - 1] + s[0];
        for (int k = 1; k < s.length; k++) maxGap = Math.max(maxGap, s[k] - s[k - 1]);
        double nearest = Double.MAX_VALUE;
        for (int a = 0; a < found.size(); a++) {
            for (int b = a + 1; b < found.size(); b++) {
                nearest = Math.min(nearest, Math.hypot(found.get(a).getX() - found.get(b).getX(),
                        found.get(a).getZ() - found.get(b).getZ()));
            }
        }
        double even = length / count;
        List<String> why = new ArrayList<>();
        if (maxGap > ARC_GAP_WARN * length / found.size()) {
            why.add(Math.round(100 * maxGap / length) + "% of the coast has no hamlet");
        }
        if (nearest < Math.min(CROWD_WARN * even, CROWD_MAX)) {
            why.add("two hamlets " + Math.round(nearest) + " blocks apart");
        }
        TerraTowns.LOGGER.info("Hamlet spread: coast {} blocks (even spacing {}), longest empty stretch {}% of it, nearest pair {} blocks; {}",
                Math.round(length), Math.round(even), Math.round(100 * maxGap / length), Math.round(nearest),
                why.isEmpty() ? "looks fine" : "non-optimal: " + String.join(", ", why));
        return why.isEmpty() ? null : String.join(", ", why);
    }

    /** Probe one plan coast cell, from the sea side inland; null if no spot qualifies. */
    private static BlockPos probePlanCell(int[] coast, int i, int[] villages, int clearance,
                                          List<BlockPos> found, int minSpacing, int[] stats,
                                          ChunkGenerator generator, ServerLevel level, RandomState randomState,
                                          BiomeSource biomeSource, Climate.Sampler sampler,
                                          SettlementManager manager, int seaLevel) {
        int cx = coast[4 * i], cz = coast[4 * i + 1], sx = coast[4 * i + 2], sz = coast[4 * i + 3];
        double norm = Math.hypot(sx, sz);
        for (int t : PLAN_PROBES) {
            int bx = cx + (int) Math.round(sx * t / norm);
            int bz = cz + (int) Math.round(sz * t / norm);
            if (villageDistance(villages, bx, bz) >= clearance
                    && farEnough(found, bx, bz, minSpacing)
                    && qualifies(generator, level, randomState, biomeSource, sampler, manager, seaLevel, bx, bz, stats)) {
                int surface = generator.getBaseHeight(bx, bz, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
                return new BlockPos(bx, surface, bz);
            }
        }
        return null;
    }

    private static double villageDistance(int[] villages, int x, int z) {
        double d = Double.MAX_VALUE;
        for (int v = 0; v + 1 < villages.length; v += 2) {
            d = Math.min(d, Math.hypot(villages[v] - x, villages[v + 1] - z));
        }
        return d;
    }

    /**
     * Legacy dense spiral scan (the pre-coastline-trace search): tests every {@link #STEP}-block
     * cell outward from the origin. Kept as a safety net for seeds where tracing finds nothing —
     * its candidates may cluster on one stretch of coast, but a clustered spawn beats no spawn.
     */
    private static List<BlockPos> spiralScan(ServerLevel level, int count, int minSpacing, int anchorRadius,
                                             ChunkGenerator generator, RandomState randomState,
                                             BiomeSource biomeSource, Climate.Sampler sampler,
                                             SettlementManager manager, int seaLevel) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos anchor = null;
        int x = 0, z = 0, dirX = 1, dirZ = 0, segLen = 1, segPass = 0, turns = 0;
        int maxCells = 2 * (MAX_RADIUS / STEP) + 1;
        long cellBudget = (long) maxCells * maxCells;
        // A last resort when neither the plan nor the coast trace found anything: bounded in time,
        // since a million cells of full checks could otherwise hold the loading screen for an hour.
        long deadline = System.nanoTime() + SEARCH_BUDGET_NANOS;

        for (long i = 0; i < cellBudget && found.size() < count && System.nanoTime() < deadline
                && level.getServer().isRunning(); i++) {
            int bx = x * STEP;
            int bz = z * STEP;
            if (Math.abs(bx) <= MAX_RADIUS && Math.abs(bz) <= MAX_RADIUS
                    && farEnough(found, bx, bz, minSpacing)
                    && withinAnchor(anchor, bx, bz, anchorRadius)
                    && qualifies(generator, level, randomState, biomeSource, sampler, manager, seaLevel, bx, bz)) {
                int surface = generator.getBaseHeight(bx, bz, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
                BlockPos pos = new BlockPos(bx, surface, bz);
                found.add(pos);
                com.terraTowns.integration.LoadingMapHook.chosen(bx, bz);
                com.terraTowns.integration.LoadingMapHook.message(
                        "Searching wider for hamlet sites: " + found.size() + " of " + count + " found");
                if (anchor == null) {
                    anchor = pos;
                }
                TerraTowns.LOGGER.info("Spawn-hamlet candidate #{} at {} (spiral fallback)", found.size(), pos);
            }
            x += dirX;
            z += dirZ;
            if (++segPass == segLen) {
                segPass = 0;
                int t = dirX;
                dirX = -dirZ;
                dirZ = t;
                if (++turns == 2) {
                    turns = 0;
                    segLen++;
                }
            }
        }
        if (found.size() < count) {
            TerraTowns.LOGGER.warn("Only found {}/{} cozy coastal hamlet candidates within {} blocks",
                    found.size(), count, MAX_RADIUS);
        }
        return found;
    }

    private static boolean withinAnchor(BlockPos anchor, int bx, int bz, int anchorRadius) {
        if (anchor == null) {
            return true;
        }
        long dx = anchor.getX() - bx;
        long dz = anchor.getZ() - bz;
        return dx * dx + dz * dz <= (long) anchorRadius * anchorRadius;
    }

    /** Moore-neighbour boundary tracing: walk the land/ocean edge clockwise until the loop closes. */
    private static List<CoastCell> traceBoundary(Map<Long, Boolean> cache, BiomeSource source,
                                                 Climate.Sampler sampler, int seaLevel,
                                                 int seedX, int seedZ, int[] back) {
        List<CoastCell> out = new ArrayList<>();
        int curX = seedX, curZ = seedZ;
        int backX = back[0], backZ = back[1];
        final int startX = seedX, startZ = seedZ, startBackX = backX, startBackZ = backZ;

        for (int steps = 0; steps < TRACE_BUDGET; steps++) {
            // The backtrack cell is always the water side here — record it as the seaward
            // direction so candidate probing knows which way "inland" is.
            out.add(new CoastCell(curX, curZ,
                    Integer.signum(backX - curX), Integer.signum(backZ - curZ)));
            int startDir = mooreIndex(backX - curX, backZ - curZ);
            int prevX = backX, prevZ = backZ;
            boolean advanced = false;
            for (int k = 1; k <= 8; k++) {
                int d = (startDir + k) % 8;
                int nx = curX + MOORE[d][0];
                int nz = curZ + MOORE[d][1];
                if (isLandCell(cache, source, sampler, seaLevel, nx, nz)) {
                    backX = prevX;
                    backZ = prevZ;
                    curX = nx;
                    curZ = nz;
                    advanced = true;
                    break;
                }
                prevX = nx;
                prevZ = nz;
            }
            if (!advanced) {
                break; // one-cell island
            }
            if (curX == startX && curZ == startZ && backX == startBackX && backZ == startBackZ) {
                break; // closed the loop (Jacob's stopping criterion)
            }
        }
        return out;
    }

    /**
     * Pick up to {@code count} sites spread around the coastline: {@code count} points of
     * interest are spaced evenly along the traced boundary, and each searches <b>back and
     * forth</b> from its POI (offsets 0, +1, −1, +2, −2… boundary cells) until a fully
     * qualifying site turns up or the search meets its neighbour halfway
     * ({@code perimeter/16} cells each side — the collision rule).
     *
     * <p><b>Quality-first policy:</b> sites must pass the full strictness gate in
     * {@link #qualifies}; a POI whose whole span has no qualifying site places nothing. Fewer,
     * perfect hamlets beat a full set of mediocre ones — the op login notice reports the
     * shortfall so the operator can regenerate the world instead.</p>
     */
    private static List<BlockPos> pickAroundPerimeter(List<CoastCell> boundary, int count, int minSpacing,
                                                      long deadline, ChunkGenerator generator,
                                                      ServerLevel level, RandomState randomState,
                                                      BiomeSource biomeSource, Climate.Sampler sampler,
                                                      SettlementManager manager, int seaLevel) {
        int perimeter = boundary.size();
        int span = Math.max(1, perimeter / 16); // stop halfway to the neighbouring POI
        List<BlockPos> found = new ArrayList<>();
        int[] stats = new int[STAT_NAMES.length];

        com.terraTowns.integration.LoadingMapHook.message("Checking the coast for hamlet sites: 0 of " + count + " found");
        for (int k = 0; k < count && level.getServer().isRunning(); k++) {
            if (System.nanoTime() > deadline) {
                TerraTowns.LOGGER.warn("Spawn-search budget exhausted after {} sites — keeping what qualified",
                        found.size());
                break;
            }
            int poi = (int) ((long) k * perimeter / count);
            BlockPos site = searchNearPoi(boundary, poi, span, found, minSpacing, deadline, stats,
                    generator, level, randomState, biomeSource, sampler, manager, seaLevel);
            if (site != null) {
                found.add(site);
                com.terraTowns.integration.LoadingMapHook.chosen(site.getX(), site.getZ());
                com.terraTowns.integration.LoadingMapHook.message(
                        "Checking the coast for hamlet sites: " + found.size() + " of " + count + " found");
                TerraTowns.LOGGER.info("Spawn-hamlet candidate #{} at {} (coast POI {}/{})",
                        found.size(), site, k + 1, count);
            } else {
                TerraTowns.LOGGER.info("No qualifying site near coast POI {}/{} — skipped (quality-first)",
                        k + 1, count);
            }
        }

        // Which criterion is the binding constraint? Report the per-check rejection tallies.
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < STAT_NAMES.length; i++) {
            if (i > 0) {
                report.append(", ");
            }
            report.append(STAT_NAMES[i]).append(' ').append(stats[i]);
        }
        TerraTowns.LOGGER.info("Site-filter tallies: {}", report);
        // Which biomes the cozy check turned away, most common first. When one pack's coast is
        // dominated by biomes nobody listed, this names them instead of just counting them.
        StringBuilder rejected = new StringBuilder();
        REJECTED_BIOMES.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(25)
                .forEach(e -> rejected.append(e.getKey()).append(' ').append(e.getValue()).append(", "));
        TerraTowns.LOGGER.info("Biomes rejected as not-cozy: {}", rejected);
        REJECTED_BIOMES.clear();
        return found;
    }

    /** Back-and-forth around one POI: offset 0, then +1, −1, +2, −2… out to {@code span} cells. */
    private static BlockPos searchNearPoi(List<CoastCell> boundary, int poi, int span,
                                          List<BlockPos> found, int minSpacing, long deadline, int[] stats,
                                          ChunkGenerator generator, ServerLevel level,
                                          RandomState randomState, BiomeSource biomeSource,
                                          Climate.Sampler sampler, SettlementManager manager, int seaLevel) {
        int perimeter = boundary.size();
        for (int off = 0; off <= span; off++) {
            if (System.nanoTime() > deadline) {
                return null;
            }
            BlockPos site = probeCell(boundary.get(Math.floorMod(poi + off, perimeter)), found, minSpacing, stats,
                    generator, level, randomState, biomeSource, sampler, manager, seaLevel);
            if (site == null && off > 0) {
                site = probeCell(boundary.get(Math.floorMod(poi - off, perimeter)), found, minSpacing, stats,
                        generator, level, randomState, biomeSource, sampler, manager, seaLevel);
            }
            if (site != null) {
                return site;
            }
        }
        return null;
    }

    /** Probe one boundary cell, marching inland along its seaward direction. */
    private static BlockPos probeCell(CoastCell cell, List<BlockPos> found, int minSpacing, int[] stats,
                                      ChunkGenerator generator, ServerLevel level,
                                      RandomState randomState, BiomeSource biomeSource,
                                      Climate.Sampler sampler, SettlementManager manager, int seaLevel) {
        int inlandX = -cell.seaDx();
        int inlandZ = -cell.seaDz();
        for (int d : INLAND_PROBES) {
            int bx = cell.cx() * CELL + inlandX * d;
            int bz = cell.cz() * CELL + inlandZ * d;
            if (Math.abs(bx) <= MAX_RADIUS && Math.abs(bz) <= MAX_RADIUS
                    && farEnough(found, bx, bz, minSpacing)
                    && qualifies(generator, level, randomState, biomeSource, sampler, manager, seaLevel, bx, bz, stats)) {
                int surface = generator.getBaseHeight(bx, bz, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
                return new BlockPos(bx, surface, bz);
            }
        }
        return null;
    }

    /** Land test on the coarse cell grid (cached). Everything outside MAX_RADIUS counts as ocean. */
    private static boolean isLandCell(Map<Long, Boolean> cache, BiomeSource source,
                                      Climate.Sampler sampler, int seaLevel, int cx, int cz) {
        long key = cellKey(cx, cz);
        Boolean cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        int bx = cx * CELL;
        int bz = cz * CELL;
        boolean land = Math.abs(bx) <= MAX_RADIUS && Math.abs(bz) <= MAX_RADIUS
                && !biomeAt(source, sampler, bx, seaLevel, bz).is(BiomeTags.IS_OCEAN);
        cache.put(key, land);
        return land;
    }

    /** @return an ocean 4-neighbour of the given land cell (as a cell coordinate), or null. */
    private static int[] oceanNeighbor4(Map<Long, Boolean> cache, BiomeSource source,
                                        Climate.Sampler sampler, int seaLevel, int cx, int cz) {
        int[][] four = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : four) {
            if (!isLandCell(cache, source, sampler, seaLevel, cx + d[0], cz + d[1])) {
                return new int[]{cx + d[0], cz + d[1]};
            }
        }
        return null;
    }

    private static long cellKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private static int mooreIndex(int dx, int dz) {
        for (int i = 0; i < MOORE.length; i++) {
            if (MOORE[i][0] == dx && MOORE[i][1] == dz) {
                return i;
            }
        }
        return 0;
    }

    /** Labels for the per-check rejection tallies (indexes into the {@code stats} array). */
    /**
     * Dev-only: with {@code -Dterratowns.biomeMap=<path>}, write the biome at every 64 blocks
     * across the whole search square, plus the traced coastline and the sites picked, as JSON —
     * for rendering a picture of what the finder was looking at. Samples the biome generator
     * directly, so nothing is generated. Inert without the property.
     */
    private static void dumpBiomeMap(BiomeSource source, Climate.Sampler sampler, int seaLevel,
                                     List<CoastCell> boundary, List<BlockPos> sites) {
        String out = System.getProperty("terratowns.biomeMap");
        if (out == null) {
            return;
        }
        int step = 64;
        int n = 2 * MAX_RADIUS / step + 1;
        Map<String, Integer> palette = new java.util.LinkedHashMap<>();
        StringBuilder grid = new StringBuilder();
        for (int iz = 0; iz < n; iz++) {
            for (int ix = 0; ix < n; ix++) {
                String id = biomeAt(source, sampler, -MAX_RADIUS + ix * step, seaLevel, -MAX_RADIUS + iz * step)
                        .getRegisteredName();
                int idx = palette.computeIfAbsent(id, k -> palette.size());
                grid.append(idx).append(ix == n - 1 ? "" : ",");
            }
            grid.append(iz == n - 1 ? "" : ";");
        }
        StringBuilder coast = new StringBuilder();
        for (CoastCell c : boundary) {
            coast.append(c.cx() * CELL).append(',').append(c.cz() * CELL).append(';');
        }
        StringBuilder picked = new StringBuilder();
        for (BlockPos p : sites) {
            picked.append(p.getX()).append(',').append(p.getZ()).append(';');
        }
        StringBuilder json = new StringBuilder("{\"step\":").append(step).append(",\"origin\":").append(-MAX_RADIUS)
                .append(",\"n\":").append(n).append(",\"palette\":[");
        int i = 0;
        for (String id : palette.keySet()) {
            json.append(i++ == 0 ? "" : ",").append('"').append(id).append('"');
        }
        json.append("],\"grid\":\"").append(grid).append("\",\"coast\":\"").append(coast)
                .append("\",\"sites\":\"").append(picked).append("\"}");
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(out), json);
            TerraTowns.LOGGER.info("[biome-map] wrote {} ({}x{} samples)", out, n, n);
        } catch (java.io.IOException e) {
            TerraTowns.LOGGER.error("[biome-map] could not write {}", out, e);
        }
    }

    /** Not-cozy rejections by biome id, reported with the tallies. */
    private static final Map<String, Integer> REJECTED_BIOMES = new HashMap<>();

    private static final String[] STAT_NAMES = {
            "not-cozy", "core", "shore-band", "rough-shore", "island", "climate", "claimed", "terrain", "cramped", "PASSED"};

    private static boolean qualifies(ChunkGenerator generator, ServerLevel level, RandomState randomState,
                                     BiomeSource biomeSource, Climate.Sampler sampler,
                                     SettlementManager manager, int seaLevel, int bx, int bz) {
        return qualifies(generator, level, randomState, biomeSource, sampler, manager, seaLevel, bx, bz, null);
    }

    /**
     * Qualification for one column. Cheap biome checks run first; the expensive terrain-relief
     * check (which samples noise surface heights) runs last, only for columns that already
     * passed everything else. When {@code stats} is non-null, the index of the check that
     * rejected the column (see {@link #STAT_NAMES}) is tallied so a world-gen run can report
     * which criterion is the binding constraint.
     */
    private static boolean qualifies(ChunkGenerator generator, ServerLevel level, RandomState randomState,
                                     BiomeSource biomeSource, Climate.Sampler sampler,
                                     SettlementManager manager, int seaLevel, int bx, int bz, int[] stats) {
        if (!level.getServer().isRunning()) {
            return false; // "Try a New Seed" stopped the server: every search runs out at once
        }
        com.terraTowns.integration.LoadingMapHook.probe(bx, bz);
        Holder<Biome> here = biomeAt(biomeSource, sampler, bx, seaLevel, bz);
        if (!settleable(here)) {
            if (stats != null) {
                REJECTED_BIOMES.merge(here.getRegisteredName(), 1, Integer::sum);
            }
            return tally(stats, 0);
        }
        if (!solidCozyCore(biomeSource, sampler, bx, seaLevel, bz)) {
            return tally(stats, 1);
        }
        int shore = nearestOceanDistance(biomeSource, sampler, bx, seaLevel, bz);
        if (shore < MIN_SHORE_DISTANCE || shore > MAX_SHORE_DISTANCE) {
            return tally(stats, 2);
        }
        if (rules.pleasantShore() && !pleasantShore(biomeSource, sampler, bx, seaLevel, bz)) {
            return tally(stats, 3);
        }
        if (!continentalBacking(biomeSource, sampler, bx, seaLevel, bz)) {
            return tally(stats, 4);
        }
        if (!temperateNeighborhood(biomeSource, sampler, bx, seaLevel, bz)) {
            return tally(stats, 5);
        }
        if (!notClaimed(manager, bx, bz)) {
            return tally(stats, 6);
        }
        if (!buildableFootprint(generator, level, randomState, seaLevel, bx, bz)) {
            return tally(stats, 7);
        }
        int plots = plotCount(generator, level, randomState, seaLevel, bx, bz);
        if (plots < rules.minPlots()) {
            TerraTowns.LOGGER.info("Hamlet site {},{} rejected as cramped: {} plots (need {})", bx, bz, plots, rules.minPlots());
            return tally(stats, 8);
        }
        TerraTowns.LOGGER.info("Hamlet site {},{}: {} plots", bx, bz, plots);
        tally(stats, 9);
        return true;
        // NOTE: village avoidance was attempted here via ChunkGeneratorStructureState
        // .hasStructureChunkInRange, but village structure sets have exclusion zones that
        // recursively scan other sets, so a per-candidate area scan fans out and hangs the
        // server thread (OOM/lockup). Proper, biome-aware village avoidance needs a dedicated,
        // generation-free design — tracked as a backlog rework, intentionally not done here.
    }

    /** Record a rejection/pass tally; returns true only for the PASSED index for inline use. */
    private static boolean tally(int[] stats, int index) {
        if (stats != null) {
            stats[index]++;
        }
        return index == STAT_NAMES.length - 1;
    }

    /**
     * True if the hamlet's immediate core is solid cozy land, not a tiny grass pocket inside a
     * beach. Closes the "loophole" where a 1-tile cozy spot surrounded by sand passed
     * {@link #isCozy}: every sampled point within {@link #CORE_RADIUS} must be cozy (no beach).
     * Beaches are still fine further out, at the shoreline.
     */
    private static boolean solidCozyCore(BiomeSource source, Climate.Sampler sampler, int x, int seaLevel, int z) {
        for (int[] dir : DIRS) {
            Holder<Biome> b = biomeAt(source, sampler, x + dir[0] * CORE_RADIUS, seaLevel, z + dir[1] * CORE_RADIUS);
            if (b.is(BiomeTags.IS_BEACH) || !settleable(b)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True if the whole coastal band is welcoming — every sampled tile is ocean, river, beach,
     * or a {@linkplain #COZY cozy} land biome. This is an allowlist on purpose: any other shore
     * (stony, gravelly, snowy, rocky, mod-added oddities) fails, so we get sandy beaches or
     * plains/forest meeting the water and nothing rougher.
     */
    private static boolean pleasantShore(BiomeSource source, Climate.Sampler sampler, int x, int seaLevel, int z) {
        for (int d = SHORE_SCAN_STEP; d <= MAX_SHORE_DISTANCE; d += SHORE_SCAN_STEP) {
            for (int[] dir : DIRS) {
                Holder<Biome> b = biomeAt(source, sampler, x + dir[0] * d, seaLevel, z + dir[1] * d);
                if (b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_RIVER)
                        || b.is(BiomeTags.IS_BEACH) || isCozy(b)) {
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    /**
     * True if the hamlet footprint is genuinely buildable: its centre is dry land, most of it
     * is dry land (not a pond/bay), and the land that is there is reasonably flat. Heights are
     * read with the <em>ocean-floor</em> heightmap (water excluded), so a calm bay reads as
     * below-sea-level terrain rather than masquerading as flat ground at sea level.
     */
    private static boolean buildableFootprint(ChunkGenerator generator, ServerLevel level,
                                              RandomState randomState, int seaLevel, int x, int z) {
        // Centre must be dry land — that's where the player spawns and the well/Professor go.
        if (terrainTop(generator, level, randomState, x, z) <= seaLevel) {
            return false;
        }
        int total = 0;
        int land = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int[] xs = new int[9], zs = new int[9];
        for (int dx = -FOOTPRINT_HALF; dx <= FOOTPRINT_HALF; dx += FOOTPRINT_HALF) {
            for (int dz = -FOOTPRINT_HALF; dz <= FOOTPRINT_HALF; dz += FOOTPRINT_HALF) {
                xs[total] = x + dx;
                zs[total] = z + dz;
                total++;
            }
        }
        for (int h : terrainTops(generator, level, randomState, xs, zs)) {
            if (h > seaLevel) {
                land++;
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        // Mostly dry land, and the land that's there is reasonably flat.
        return land >= total * rules.minLandFraction() && (max - min) <= rules.maxRelief();
    }

    /**
     * Roughly how many house plots the hamlet builder would find around (x, z), from noise
     * heights: plot centres on a 6-block grid within {@link #PLOT_RADIUS}, each checked over the
     * 3x3 heights around it (dry, relief at most {@link #PLOT_RELIEF}). Run only for sites that
     * passed every other check. Calibrated 2026-09-27 against the builder's own 3-block,
     * 5x5-sample count on two worlds: cramped sites scored 14-15 here (59-63 there), the good
     * ones 26-76 (121-343); the finer count told them apart no better and took 4x as long
     * (~4 s a site against Tectonic's noise, which put the site search over the watchdog).
     */
    private static int plotCount(ChunkGenerator generator, ServerLevel level, RandomState randomState,
                                 int seaLevel, int x, int z) {
        int step = PLOT_STEP;
        int reach = PLOT_RADIUS / step + 1;
        int n = 2 * reach + 1;
        int[] xs = new int[n * n], zs = new int[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                xs[i * n + j] = x + (i - reach) * step;
                zs[i * n + j] = z + (j - reach) * step;
            }
        }
        int[] h = terrainTops(generator, level, randomState, xs, zs);
        int plots = 0;
        int r = PLOT_RADIUS / step;
        for (int i = -r; i <= r; i++) {
            for (int j = -r; j <= r; j++) {
                if (i * i + j * j > r * r) {
                    continue;
                }
                int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
                for (int a = -1; a <= 1; a++) {
                    for (int b = -1; b <= 1; b++) {
                        int y = h[(i + a + reach) * n + (j + b + reach)];
                        min = Math.min(min, y);
                        max = Math.max(max, y);
                    }
                }
                if (min > seaLevel && max - min <= rules.plotRelief()) {
                    plots++;
                }
            }
        }
        return plots;
    }

    /** Terrain surface height (water excluded) from the generator's noise. */
    private static int terrainTop(ChunkGenerator generator, ServerLevel level, RandomState randomState, int x, int z) {
        Probe.heightCalls++;
        Map<Long, Integer> cache = Probe.heights;
        long key = cellKey(x, z);
        if (cache != null) {
            Integer h = cache.get(key);
            if (h != null) return h;
        }
        long t = System.nanoTime();
        int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
        Probe.heightNanos += System.nanoTime() - t;
        Probe.heightMisses++;
        if (cache != null) cache.put(key, h);
        return h;
    }

    /**
     * Many terrain heights at once, the uncached ones computed in parallel. One height costs ~127 ms
     * in this pack (real-pack rig, 2026-11-05: 1793 of them were 227 s of a 232 s search), and a
     * site's plot count needs 169. Heights are pure functions of the seed and worldgen already
     * evaluates the same noise on many threads, so the result is the same as computing them in turn.
     */
    private static int[] terrainTops(ChunkGenerator generator, ServerLevel level, RandomState randomState,
                                     int[] xs, int[] zs) {
        int[] out = new int[xs.length];
        Map<Long, Integer> cache = Probe.heights;
        List<Integer> todo = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            Probe.heightCalls++;
            Integer h = cache == null ? null : cache.get(cellKey(xs[i], zs[i]));
            if (h != null) {
                out[i] = h;
            } else {
                todo.add(i);
            }
        }
        if (todo.isEmpty()) return out;
        long t = System.nanoTime();
        java.util.concurrent.ForkJoinPool pool = Probe.pool;
        Runnable work = () -> todo.parallelStream().forEach(i -> out[i] = generator.getBaseHeight(
                xs[i], zs[i], Heightmap.Types.OCEAN_FLOOR_WG, level, randomState));
        if (pool == null || todo.size() == 1) {
            work.run();
        } else {
            pool.submit(work).join();
        }
        Probe.heightNanos += System.nanoTime() - t;
        Probe.heightMisses += todo.size();
        if (cache != null) {
            for (int i : todo) cache.put(cellKey(xs[i], zs[i]), out[i]);
        }
        return out;
    }

    /** A biome a hamlet may stand in under the current {@link #rules}. */
    private static boolean settleable(Holder<Biome> biome) {
        if (isCozy(biome)) return true;
        if (!rules.anyTemperateLand()) return false;
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH)) return false;
        float t = biome.value().getBaseTemperature();
        return t >= NB_MIN_TEMP && t <= NB_MAX_TEMP;
    }

    /**
     * Per-search caches and timings. A site check samples rings of biomes and grids of heights
     * around its spot, and the spots tried along one coast cell sit 12 blocks apart, so most
     * samples repeat; both are pure functions of the seed, so a cache changes nothing but time.
     * The counts and times are logged after each search (the user, 2026-11-05: find where the
     * first load's time goes). Server thread only; the caches exist only during a search.
     */
    private static final class Probe {
        static Map<Long, Holder<Biome>> biomes;
        static Map<Long, Integer> heights;
        static long biomeCalls, biomeMisses, biomeNanos, heightCalls, heightMisses, heightNanos, start;
        static int seaQuart;
        /** Threads for {@link #terrainTops}; leaves two cores for the server and the loading screen. */
        static java.util.concurrent.ForkJoinPool pool;

        static void begin(int seaLevelQuart) {
            seaQuart = seaLevelQuart;
            int threads = Boolean.getBoolean("terratowns.serialHeights") ? 1
                    : Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
            pool = threads > 1 ? new java.util.concurrent.ForkJoinPool(threads) : null;
            biomes = Boolean.getBoolean("terratowns.noSearchCache") ? null : new HashMap<>();
            heights = biomes == null ? null : new HashMap<>();
            biomeCalls = biomeMisses = biomeNanos = heightCalls = heightMisses = heightNanos = 0;
            start = System.nanoTime();
        }

        static void end() {
            report("whole search");
            if (pool != null) pool.shutdown();
            pool = null;
            biomes = null;
            heights = null;
        }

        static void report(String what) {
            TerraTowns.LOGGER.info("[search-profile] {}: {} ms total; biome samples {} ({} computed, {} ms); "
                            + "heights {} ({} computed, {} ms, {} threads); caches {}",
                    what, (System.nanoTime() - start) / 1_000_000L,
                    biomeCalls, biomeMisses, biomeNanos / 1_000_000L,
                    heightCalls, heightMisses, heightNanos / 1_000_000L, pool == null ? 1 : pool.getParallelism(),
                    biomes == null ? "off" : "on");
        }
    }

    private static boolean farEnough(List<BlockPos> found, int bx, int bz, int minSpacing) {
        long minSq = (long) minSpacing * minSpacing;
        for (BlockPos p : found) {
            long dx = p.getX() - bx;
            long dz = p.getZ() - bz;
            if (dx * dx + dz * dz < minSq) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCozy(Holder<Biome> biome) {
        for (ResourceKey<Biome> key : COZY) {
            if (biome.is(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the distance (blocks) to the nearest ocean biome, scanning out to
     *         {@link #MAX_SHORE_DISTANCE}, or {@link Integer#MAX_VALUE} if none is that close.
     */
    private static int nearestOceanDistance(BiomeSource source, Climate.Sampler sampler, int x, int seaLevel, int z) {
        for (int d = SHORE_SCAN_STEP; d <= MAX_SHORE_DISTANCE; d += SHORE_SCAN_STEP) {
            for (int[] dir : DIRS) {
                if (biomeAt(source, sampler, x + dir[0] * d, seaLevel, z + dir[1] * d).is(BiomeTags.IS_OCEAN)) {
                    return d;
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    private static boolean continentalBacking(BiomeSource source, Climate.Sampler sampler, int x, int seaLevel, int z) {
        int land = 0;
        for (int[] dir : DIRS) {
            if (!biomeAt(source, sampler, x + dir[0] * BACKING_RADIUS, seaLevel, z + dir[1] * BACKING_RADIUS)
                    .is(BiomeTags.IS_OCEAN)) {
                land++;
            }
        }
        return land >= MIN_BACKING_LAND;
    }

    private static boolean temperateNeighborhood(BiomeSource source, Climate.Sampler sampler, int x, int seaLevel, int z) {
        for (int r : NEIGHBORHOOD_RADII) {
            for (int[] dir : DIRS) {
                Holder<Biome> b = biomeAt(source, sampler, x + dir[0] * r, seaLevel, z + dir[1] * r);
                if (b.is(BiomeTags.IS_OCEAN)) {
                    continue;
                }
                float t = b.value().getBaseTemperature();
                if (t < NB_MIN_TEMP || t > NB_MAX_TEMP) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Holder<Biome> biomeAt(BiomeSource source, Climate.Sampler sampler, int x, int y, int z) {
        int qx = QuartPos.fromBlock(x), qy = QuartPos.fromBlock(y), qz = QuartPos.fromBlock(z);
        Probe.biomeCalls++;
        Map<Long, Holder<Biome>> cache = Probe.biomes;
        // Every caller samples at sea level, so (qx, qz) is the key; a different y skips the cache.
        boolean cacheable = cache != null && qy == Probe.seaQuart;
        long key = cellKey(qx, qz);
        if (cacheable) {
            Holder<Biome> b = cache.get(key);
            if (b != null) return b;
        }
        long t = System.nanoTime();
        Holder<Biome> b = source.getNoiseBiome(qx, qy, qz, sampler);
        Probe.biomeNanos += System.nanoTime() - t;
        Probe.biomeMisses++;
        if (cacheable) cache.put(key, b);
        return b;
    }

    private static boolean notClaimed(SettlementManager manager, int x, int z) {
        SettlementData nearest = manager.nearest(new BlockPos(x, 0, z)).orElse(null);
        if (nearest == null) {
            return true;
        }
        int dx = nearest.center().getX() - x;
        int dz = nearest.center().getZ() - z;
        return (long) dx * dx + (long) dz * dz > (long) CLAIM_CLEARANCE * CLAIM_CLEARANCE;
    }
}
