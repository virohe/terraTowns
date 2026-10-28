package com.terraTowns.worldgen;

import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev-only measure of how steep the terrain is, for tuning Tectonic (user, 2026-10-16: hamlets
 * keep landing on a couple hundred blocks of coast backed by steep mountainside). Heights come
 * from the generator's noise ({@code getBaseHeight}), not built chunks, so it measures the
 * terrain the settings make, cheaply.
 *
 * <p>Two modes:</p>
 * <ul>
 *   <li>{@code -Dterratowns.terrainAudit=true}, alongside the rig's usual boot ({@link #run}):
 *       around each spawn hamlet, along 16 directions out to {@link #REACH} blocks every
 *       {@link #STEP}, how far inland until the ground stands 40 above the hamlet, the steepest
 *       32-block rise, and the share of steps steeper than 45° (directions starting in the sea
 *       are left out of the climb).</li>
 *   <li>{@code -Dterratowns.terrainOnly=true}, for sweeping settings ({@link #fixed}): no hamlets
 *       or villages, only measurements at places that don't move with the settings, so configs
 *       compare like for like; about 3 minutes a config instead of 10.</li>
 * </ul>
 * <p>Both also sample the spawn continent (1,500 seeded points: the share of its land steeper
 * than 45° and ~27°, its heights) and a {@link #GRID}-square height grid for a picture.</p>
 */
public final class TerrainAudit {

    private static final String PROPERTY = "terratowns.terrainAudit";
    private static final int STEP = 8;
    private static final int REACH = 400;
    private static final int RAYS = 16;
    /** Height-grid side in samples: 96 (768 blocks), or -Dterratowns.terrainGrid for the 3D views. */
    private static final int GRID = Integer.getInteger("terratowns.terrainGrid", 96);

    private TerrainAudit() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static boolean terrainOnly() {
        return Boolean.getBoolean("terratowns.terrainOnly");
    }

    /** With the rig's usual boot: around the spawn hamlets it just built. */
    static Map<String, Object> run(ServerLevel level) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sea = level.getSeaLevel();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> hamlets = new ArrayList<>();
        List<Integer> allClimbs = new ArrayList<>();
        int steepSteps = 0;
        int steps = 0;
        SettlementManager manager = SettlementManager.get(level);
        BlockPos first = null;
        for (SettlementData s : manager.all()) {
            if (!manager.isSpawnHamlet(s.id())) {
                continue;
            }
            BlockPos c = s.spawnPoint() != null ? s.spawnPoint() : s.center();
            if (first == null) {
                first = c;
            }
            int base = h(gen, rs, level, c.getX(), c.getZ());
            List<Integer> climbs = new ArrayList<>();
            int maxRise32 = 0;
            int hamletSteep = 0;
            int hamletSteps = 0;
            int maxHeight = base;
            for (int r = 0; r < RAYS; r++) {
                double a = Math.PI * 2 * r / RAYS;
                int n = REACH / STEP;
                int[] hs = new int[n + 1];
                for (int i = 0; i <= n; i++) {
                    hs[i] = h(gen, rs, level, c.getX() + (int) Math.round(Math.cos(a) * i * STEP),
                            c.getZ() + (int) Math.round(Math.sin(a) * i * STEP));
                    maxHeight = Math.max(maxHeight, hs[i]);
                }
                boolean seaward = hs[1] < sea && hs[2] < sea && hs[3] < sea;
                Integer climb = null;
                for (int i = 1; i <= n; i++) {
                    hamletSteps++;
                    if (Math.abs(hs[i] - hs[i - 1]) > STEP) {
                        hamletSteep++;
                    }
                    if (i >= 4) {
                        maxRise32 = Math.max(maxRise32, hs[i] - hs[i - 4]);
                    }
                    if (climb == null && hs[i] >= base + 40) {
                        climb = i * STEP;
                    }
                }
                if (!seaward) {
                    climbs.add(climb == null ? REACH + STEP : climb); // never within reach: "> 400"
                }
            }
            Collections.sort(climbs);
            allClimbs.addAll(climbs);
            steepSteps += hamletSteep;
            steps += hamletSteps;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", c.getX() + "," + c.getZ());
            row.put("height", base);
            row.put("climb40Median", median(climbs));
            row.put("climb40Min", climbs.isEmpty() ? null : climbs.get(0));
            row.put("maxRise32", maxRise32);
            row.put("steepShare", round(hamletSteep / (double) Math.max(1, hamletSteps)));
            row.put("maxHeightWithin400", maxHeight);
            hamlets.add(row);
        }
        Map<String, Object> around = new LinkedHashMap<>();
        around.put("climb40Median", median(allClimbs));
        around.put("climb40Under100Share", round(allClimbs.stream().filter(v -> v < 100).count() / (double) Math.max(1, allClimbs.size())));
        around.put("climb40Over400Share", round(allClimbs.stream().filter(v -> v > REACH).count() / (double) Math.max(1, allClimbs.size())));
        around.put("steepShare", round(steepSteps / (double) Math.max(1, steps)));
        out.put("aroundHamlets", around);
        out.put("hamlets", hamlets);
        out.put("spawnContinent", continent(gen, rs, level, sea));
        if (first != null) {
            out.put("grid", grid(gen, rs, level, sea, first));
        }
        return out;
    }

    /**
     * Terrain-only: the spawn continent sample, and 12 shorelines found by walking out from spawn
     * to the first sea, each measured back inland (how far until the ground is 20, 40 and 80
     * above sea level, and how many steps are steeper than 45°). The picture is taken at
     * {@code -Dterratowns.terrainCenter=x,z}, else at the first shoreline.
     */
    /**
     * How big the spawn continent really is (user, 2026-10-17: its configured 9,000-block bump
     * says nothing of the coastline, which Tectonic's own continent noise also shapes). Along
     * {@link #LAND_RAYS} directions out from the origin, the distance to the first real sea:
     * {@link #SEA_RUN} samples in a row at or below sea level, 32 blocks apart (256 blocks of
     * water, so rivers and lakes don't end the continent), up to 30,000 blocks.
     */
    public static Map<String, Object> landmass(ServerLevel level) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sea = level.getSeaLevel();
        List<Integer> coast = new ArrayList<>();
        for (int r = 0; r < LAND_RAYS; r++) {
            double cx = Math.cos(Math.PI * 2 * r / LAND_RAYS);
            double cz = Math.sin(Math.PI * 2 * r / LAND_RAYS);
            int wet = 0;
            int found = 30000;
            for (int d = 0; d <= 30000; d += 32) {
                if (h(gen, rs, level, (int) (cx * d), (int) (cz * d)) <= sea) {
                    if (++wet == SEA_RUN) {
                        found = d - (SEA_RUN - 1) * 32;
                        break;
                    }
                } else {
                    wet = 0;
                }
            }
            coast.add(found);
        }
        double meanSquare = coast.stream().mapToDouble(v -> (double) v * v).average().orElse(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rays", LAND_RAYS);
        out.put("coastMin", Collections.min(coast));
        out.put("coastMedian", median(coast));
        out.put("coastMax", Collections.max(coast));
        out.put("equivalentRadius", (int) Math.sqrt(meanSquare));
        out.put("coast", coast);
        return out;
    }

    private static final int LAND_RAYS = 36;
    private static final int SEA_RUN = 8;

    static Map<String, Object> fixed(ServerLevel level) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int sea = level.getSeaLevel();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> shores = new ArrayList<>();
        List<Integer> to20 = new ArrayList<>();
        List<Integer> to40 = new ArrayList<>();
        List<Integer> to80 = new ArrayList<>();
        int steep = 0;
        int steps = 0;
        BlockPos firstShore = null;
        for (int r = 0; r < 12; r++) {
            double cx = Math.cos(Math.PI * 2 * r / 12);
            double cz = Math.sin(Math.PI * 2 * r / 12);
            int shore = -1;
            for (int d = 0; d <= 20000; d += 32) {
                if (h(gen, rs, level, (int) (cx * d), (int) (cz * d)) <= sea) {
                    shore = Math.max(0, d - 32);
                    break;
                }
            }
            if (shore < 0) {
                continue;
            }
            int sx = (int) (cx * shore);
            int sz = (int) (cz * shore);
            if (firstShore == null) {
                firstShore = new BlockPos(sx, sea, sz);
            }
            Integer d20 = null;
            Integer d40 = null;
            Integer d80 = null;
            int prev = h(gen, rs, level, sx, sz);
            for (int i = 1; i * STEP <= 600; i++) {
                int y = h(gen, rs, level, (int) (sx - cx * i * STEP), (int) (sz - cz * i * STEP));
                steps++;
                if (Math.abs(y - prev) > STEP) {
                    steep++;
                }
                prev = y;
                if (d20 == null && y >= sea + 20) {
                    d20 = i * STEP;
                }
                if (d40 == null && y >= sea + 40) {
                    d40 = i * STEP;
                }
                if (d80 == null && y >= sea + 80) {
                    d80 = i * STEP;
                }
            }
            to20.add(d20 == null ? 608 : d20);
            to40.add(d40 == null ? 608 : d40);
            to80.add(d80 == null ? 608 : d80);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shore", sx + "," + sz);
            row.put("to20", d20);
            row.put("to40", d40);
            row.put("to80", d80);
            shores.add(row);
        }
        Map<String, Object> inland = new LinkedHashMap<>();
        inland.put("shoresFound", to40.size());
        inland.put("to20Median", median(to20));
        inland.put("to40Median", median(to40));
        inland.put("to80Median", median(to80));
        inland.put("steepShare", round(steep / (double) Math.max(1, steps)));
        out.put("fromShore", inland);
        out.put("shores", shores);
        out.put("spawnContinent", continent(gen, rs, level, sea));
        BlockPos at = firstShore;
        String center = System.getProperty("terratowns.terrainCenter");
        if (center != null) {
            String[] xz = center.split(",");
            at = new BlockPos(Integer.parseInt(xz[0].trim()), sea, Integer.parseInt(xz[1].trim()));
        }
        if (at != null) {
            out.put("grid", grid(gen, rs, level, sea, at));
        }
        return out;
    }

    /** 1,500 seeded points on the spawn continent: how much of its land is steep, how high. */
    private static Map<String, Object> continent(ChunkGenerator gen, RandomState rs, ServerLevel level, int sea) {
        RandomSource random = RandomSource.create(20261016L);
        List<Integer> heights = new ArrayList<>();
        int land = 0;
        int over45 = 0;
        int over27 = 0;
        for (int i = 0; i < 1500; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double d = Math.sqrt(random.nextDouble()) * 9000;
            int x = (int) (Math.cos(a) * d);
            int z = (int) (Math.sin(a) * d);
            int y = h(gen, rs, level, x, z);
            if (y <= sea + 1) {
                continue;
            }
            land++;
            heights.add(y);
            int slope = Math.max(Math.abs(h(gen, rs, level, x + STEP, z) - y), Math.abs(h(gen, rs, level, x, z + STEP) - y));
            if (slope > STEP) {
                over45++;
            }
            if (slope > STEP / 2) {
                over27++;
            }
        }
        Collections.sort(heights);
        Map<String, Object> continent = new LinkedHashMap<>();
        continent.put("landShare", round(land / 1500.0));
        continent.put("steeperThan45Share", round(over45 / (double) Math.max(1, land)));
        continent.put("steeperThan27Share", round(over27 / (double) Math.max(1, land)));
        continent.put("heightMedian", median(heights));
        continent.put("heightP90", heights.isEmpty() ? null : heights.get(heights.size() * 9 / 10));
        continent.put("heightMax", heights.isEmpty() ? null : heights.get(heights.size() - 1));
        return continent;
    }

    /** Heights on a {@link #GRID}-square grid around {@code at}, {@link #STEP} apart, for a picture. */
    private static Map<String, Object> grid(ChunkGenerator gen, RandomState rs, ServerLevel level, int sea, BlockPos at) {
        int[][] grid = new int[GRID][GRID];
        for (int gz = 0; gz < GRID; gz++) {
            for (int gx = 0; gx < GRID; gx++) {
                grid[gz][gx] = h(gen, rs, level, at.getX() + (gx - GRID / 2) * STEP, at.getZ() + (gz - GRID / 2) * STEP);
            }
        }
        Map<String, Object> picture = new LinkedHashMap<>();
        picture.put("center", at.getX() + "," + at.getZ());
        picture.put("step", STEP);
        picture.put("seaLevel", sea);
        picture.put("heights", grid);
        return picture;
    }

    private static Integer median(List<Integer> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static int h(ChunkGenerator gen, RandomState rs, ServerLevel level, int x, int z) {
        return gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
