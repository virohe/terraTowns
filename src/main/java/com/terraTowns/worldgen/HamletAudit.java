package com.terraTowns.worldgen;

import com.google.gson.GsonBuilder;
import com.terraTowns.TerraTowns;
import com.terraTowns.rival.RivalPool;
import com.terraTowns.settlement.SettlementManager;
import com.terraTowns.settlement.SettlementJob;
import com.terraTowns.settlement.SettlementData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Development-only placement scorecard for {@link HamletPiece}.
 *
 * <p>Entirely inert unless the system property {@code terratowns.hamletAudit} is set to an
 * output path, so nothing here runs in normal play. When it is set, every placed piece is
 * <em>re-measured against the world after it was stamped</em> — not merely reported from the
 * numbers placement believed — and the results are written as JSON. That distinction is the
 * whole point: the bugs this exists to catch (a house floating over its own foundation layer,
 * a doorstep a player cannot step onto) were ones where placement's internal bookkeeping was
 * self-consistent and the world was still wrong.</p>
 *
 * <p>Metrics per piece, all of which should read 0 on flat ground:</p>
 * <ul>
 *   <li>{@code stepUp} — height a player must climb from the doorstep into the doorway. The
 *       direct measure of "the stair is raised and inaccessible".</li>
 *   <li>{@code stiltVisible} — tallest run of exposed foundation under a perimeter wall. The
 *       direct measure of "dirt-block stilt" / "cobblestone pedestal".</li>
 *   <li>{@code buried} — deepest the uphill wall sinks below grade.</li>
 *   <li>{@code floatingColumns} — footprint columns with an air gap between the piece's lowest
 *       block and the ground. Should always be 0; anything else is a hole under the build.</li>
 *   <li>{@code doorOk} — the block where placement thinks the door is really is a door.</li>
 *   <li>{@code pathAtDoorstep} — the walkway actually reaches the doorstep column.</li>
 * </ul>
 *
 * <p>Set {@code terratowns.hamletAuditExit=true} alongside it to halt the server once the
 * report is written, which is what makes an unattended generate-measure-fix loop possible.</p>
 */
public final class HamletAudit {

    private static final String OUT_PROPERTY = "terratowns.hamletAudit";
    private static final String EXIT_PROPERTY = "terratowns.hamletAuditExit";

    /** True when a report path was supplied on the command line. */
    public static final boolean ENABLED = System.getProperty(OUT_PROPERTY) != null;

    private static final List<Map<String, Object>> PIECES = new ArrayList<>();
    private static final List<Map<String, Object>> HAMLETS = new ArrayList<>();
    private static final Map<String, Integer> REJECTS = new LinkedHashMap<>();
    private static final Map<String, Integer> REJECT_WORST = new LinkedHashMap<>();
    private static final Map<String, Integer> SKIPPED = new LinkedHashMap<>();

    private HamletAudit() {
    }

    /** Count a plot rejected for {@code reason} with the measured value that failed the cap. */
    static void reject(String reason, int value) {
        if (!ENABLED) {
            return;
        }
        REJECTS.merge(reason, 1, Integer::sum);
        REJECT_WORST.merge(reason, value, Math::max);
    }

    /** Count a piece that ran out of candidate plots entirely. */
    static void skipped(String template) {
        if (!ENABLED) {
            return;
        }
        SKIPPED.merge(template, 1, Integer::sum);
    }

    /** Record one placed piece, re-measuring it against the world. */
    static void piece(HamletPiece builder, String template, Rotation rotation, int cx, int cz,
                      HamletPiece.Placement p) {
        if (!ENABLED) {
            return;
        }
        ServerLevel level = builder.level();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("template", template);
        row.put("rotation", rotation.name());
        row.put("plotX", cx);
        row.put("plotZ", cz);
        row.put("minX", p.minX());
        row.put("minZ", p.minZ());
        if (template.contains("pen") || template.contains("farm")) {
            // Open pieces: natural ground left standing above the floor inside the footprint
            // (the 0.4.7 pen-on-a-slope steps). Must be 0.
            int bumps = 0;
            for (int x = p.minX() + 1; x < p.minX() + p.sizeX() - 1; x++) {
                for (int z = p.minZ() + 1; z < p.minZ() + p.sizeZ() - 1; z++) {
                    net.minecraft.world.level.block.state.BlockState above =
                            level.getBlockState(new net.minecraft.core.BlockPos(x, p.floorY() + 1, z));
                    if (above.is(net.minecraft.tags.BlockTags.DIRT) || above.is(net.minecraft.tags.BlockTags.SAND)
                            || above.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)) {
                        bumps++;
                    }
                }
            }
            row.put("openInteriorBumps", bumps);
        }
        row.put("sizeX", p.sizeX());
        row.put("sizeY", p.sizeY());
        row.put("sizeZ", p.sizeZ());
        row.put("plannedMinGround", p.minPerimeterGround());
        row.put("plannedMaxGround", p.maxPerimeterGround());
        row.put("baseY", p.baseY());
        row.put("floorY", p.floorY());
        row.put("plannedStilt", p.maxStilt());
        row.put("plannedBury", p.maxBury());
        row.put("obstructionsBefore", p.obstructions());

        // --- the doorstep test: can a player walk in? ---
        // Only meaningful for pieces that really have a door. Farms and pens get a synthetic
        // "door" at their front-edge midpoint so they can be rotated and pathed; scoring that
        // as a doorway reported 13 phantom failures in the first harness run.
        if (p.hasDoor() && p.door() != null && p.approach() != null) {
            BlockState doorState = level.getBlockState(p.door());
            row.put("doorOk", doorState.is(BlockTags.DOORS));
            row.put("doorBlock", key(doorState));
            // rise: how far the doorway sits above the outside walking level. 0 = flush, 1 = a
            // step up, which is only walkable with a stair or slab in front of the door.
            row.put("stepUp", p.rise());
            row.put("stairInFront", p.stairInFront());
            row.put("stairSunk", p.stairSunk());
            boolean inaccessible = p.rise() < 0 || p.rise() > 1
                    || (p.rise() == 1 && !p.stairInFront()) || p.stairSunk();
            row.put("inaccessible", inaccessible);
        }
        row.put("grade", p.grade());
        row.put("pouredAboveFloor", p.pouredAboveFloor());
        if (p.approach() != null) {
            int approachGround = builder.measureGround(p.approach().getX(), p.approach().getZ());
            BlockState stepState = level.getBlockState(
                    new BlockPos(p.approach().getX(), approachGround, p.approach().getZ()));
            row.put("pathAtApproach", stepState.is(Blocks.DIRT_PATH));
            row.put("approachBlock", key(stepState));
        }

        // --- the pedestal test ---
        // Measured as "is any of the piece's own footprint standing over air?", which is
        // unambiguous. The first harness run tried to measure exposed foundation height by
        // digging for natural ground beneath, which mis-read cliffs and overhangs as
        // 16-block stilts; air under a placed block cannot be misread.
        int airGapColumns = 0;
        int worstAirGap = 0;
        String worstColumn = null;
        for (int dx = 0; dx < p.sizeX(); dx++) {
            for (int dz = 0; dz < p.sizeZ(); dz++) {
                int x = p.minX() + dx;
                int z = p.minZ() + dz;
                int lowest = lowestSolid(level, x, z, p.baseY(), p.baseY() + p.sizeY() - 1);
                if (lowest == Integer.MIN_VALUE || lowest > p.floorY()) {
                    // Open column, or an overhang (roof eave, awning) — meant to have air under
                    // it. Counting those is what made the pre-0.4.4 underpin look correct while
                    // it was pouring cobblestone pillars under every eave.
                    continue;
                }
                int gap = 0;
                BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
                for (int y = lowest - 1; y >= lowest - 24; y--) {
                    if (!level.getBlockState(q.set(x, y, z)).isAir()) {
                        break;
                    }
                    gap++;
                }
                if (gap > 0) {
                    airGapColumns++;
                    if (gap > worstAirGap) {
                        worstAirGap = gap;
                        worstColumn = "groundTopNow=" + builder.measureGround(x, z) + " "
                                + profile(level, x, z, lowest);
                    }
                }
            }
        }
        row.put("airGapColumns", airGapColumns);
        row.put("worstAirGap", worstAirGap);
        if (worstColumn != null) {
            row.put("worstColumn", worstColumn);
        }
        row.put("villagerSpots", p.villagerSpots().size());
        PIECES.add(row);
    }

    /** Record one finished hamlet. */
    static void hamlet(BlockPos center, int houses, int farms, int villagers, int plotsScored,
                       Map<String, Object> lighting) {
        if (!ENABLED) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("x", center.getX());
        row.put("y", center.getY());
        row.put("z", center.getZ());
        row.put("houses", houses);
        row.put("farms", farms);
        row.put("villagers", villagers);
        row.put("plotsScored", plotsScored);
        row.put("pieces", PIECES.size());
        row.put("lighting", lighting);
        HAMLETS.add(row);
    }

    /**
     * Second-boot reload check: report what actually came back off disk, then write and halt.
     *
     * <p>This is the only observation point for the persistence round trip. {@link JobAudit}
     * runs inside the first boot and the server halts immediately after, so nothing there ever
     * reloads a save — job unlocks, job holders and rival records could all have been silently
     * failing to serialise and every in-run check would still have passed.</p>
     */
    public static void reload(MinecraftServer server) {
        if (!ENABLED) {
            return;
        }
        ServerLevel level = server.overworld();
        SettlementManager manager = SettlementManager.get(level);
        Map<String, Object> loaded = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        int withJobs = 0;
        int withHolders = 0;
        for (SettlementData s : manager.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("settlement", s.name());
            row.put("gymCleared", s.isGymCleared());
            row.put("buildings", s.registeredBuildings().size());
            row.put("unlockedJobs", s.unlockedJobs().stream().map(SettlementJob::id).toList());
            row.put("jobHolders", s.jobHolders().size());
            if (!s.unlockedJobs().isEmpty()) {
                withJobs++;
            }
            if (!s.jobHolders().isEmpty()) {
                withHolders++;
            }
            rows.add(row);
        }
        loaded.put("settlements", rows);

        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("settlementsLoaded", manager.all().size());
        verdict.put("settlementsWithUnlockedJobs", withJobs);
        verdict.put("settlementsWithJobHolders", withHolders);
        verdict.put("rivalRecordsLoaded", RivalPool.get(level).players().size());
        loaded.put("verdict", verdict);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", "reload");
        report.put("afterReload", loaded);
        write(report);
        maybeHalt(server);
    }

    /**
     * Write the report and, if asked, halt the server. Called once hamlet generation is done.
     */
    public static void finish(MinecraftServer server) {
        if (!ENABLED) {
            return;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("hamlets", HAMLETS);
        report.put("pieces", PIECES);
        report.put("summary", summarise());
        if (JobAudit.enabled()) {
            report.put("jobs", JobAudit.run(server.overworld()));
        }
        write(report);
        maybeHalt(server);
    }

    private static void write(Map<String, Object> report) {
        Path out = Path.of(System.getProperty(OUT_PROPERTY));
        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(report, w);
            }
            TerraTowns.LOGGER.info("[hamlet-audit] wrote {} ({} hamlets, {} pieces)",
                    out.toAbsolutePath(), HAMLETS.size(), PIECES.size());
        } catch (IOException e) {
            TerraTowns.LOGGER.error("[hamlet-audit] could not write {}", out, e);
        }
    }

    private static void maybeHalt(MinecraftServer server) {
        if (Boolean.getBoolean(EXIT_PROPERTY)) {
            TerraTowns.LOGGER.info("[hamlet-audit] halting server ({}=true)", EXIT_PROPERTY);
            server.halt(false);
        }
    }

    private static Map<String, Object> summarise() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("pieceCount", PIECES.size());
        // maxInt starts at 0, so on its own it can never report a negative value -- a door set
        // BELOW the outside ground was invisible to "worstStepUp" for every run before 0.4.4.
        s.put("worstStepUp", maxInt("stepUp"));
        s.put("worstStepDown", minInt("stepUp"));
        s.put("sunkenStairs", PIECES.stream()
                .filter(r -> Boolean.TRUE.equals(r.get("stairSunk"))).count());
        s.put("inaccessibleEntrances", PIECES.stream()
                .filter(r -> Boolean.TRUE.equals(r.get("inaccessible"))).count());
        s.put("foundationPouredAboveFloor", sumInt("pouredAboveFloor"));
        s.put("worstBuried", maxInt("plannedBury"));
        s.put("worstAirGap", maxInt("worstAirGap"));
        s.put("piecesWithAirGap", countNonZero("airGapColumns"));
        s.put("totalAirGapColumns", sumInt("airGapColumns"));
        s.put("openInteriorBumps", sumInt("openInteriorBumps"));
        s.put("doorsWrong", PIECES.stream()
                .filter(r -> Boolean.FALSE.equals(r.get("doorOk"))).count());
        s.put("approachesWithoutPath", PIECES.stream()
                .filter(r -> Boolean.FALSE.equals(r.get("pathAtApproach"))).count());
        s.put("rejects", REJECTS);
        s.put("rejectWorstValue", REJECT_WORST);
        s.put("piecesSkippedEntirely", SKIPPED);
        return s;
    }

    /** A readable vertical slice of a column, for diagnosing what is under a placed piece. */
    private static String profile(ServerLevel level, int x, int z, int from) {
        StringBuilder sb = new StringBuilder("(" + x + "," + z + ") ");
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int y = from + 1; y >= from - 12; y--) {
            String name = key(level.getBlockState(q.set(x, y, z)));
            sb.append('y').append(y).append('=')
              .append(name.replace("minecraft:", "")).append(' ');
        }
        return sb.toString().trim();
    }

    private static int maxInt(String field) {
        int max = 0;
        for (Map<String, Object> r : PIECES) {
            Object v = r.get(field);
            if (v instanceof Integer i) {
                max = Math.max(max, i);
            }
        }
        return max;
    }

    private static int minInt(String field) {
        int min = 0;
        for (Map<String, Object> r : PIECES) {
            Object v = r.get(field);
            if (v instanceof Integer i) {
                min = Math.min(min, i);
            }
        }
        return min;
    }

    private static int sumInt(String field) {
        int sum = 0;
        for (Map<String, Object> r : PIECES) {
            Object v = r.get(field);
            if (v instanceof Integer i) {
                sum += i;
            }
        }
        return sum;
    }

    private static long countNonZero(String field) {
        return PIECES.stream()
                .filter(r -> r.get(field) instanceof Integer i && i != 0)
                .count();
    }

    /** @return the Y of the lowest collidable block in a column within [lo, hi], or MIN_VALUE. */
    private static int lowestSolid(ServerLevel level, int x, int z, int lo, int hi) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = lo; y <= hi; y++) {
            BlockState s = level.getBlockState(p.set(x, y, z));
            if (!s.isAir() && !s.getCollisionShape(level, p).isEmpty()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static String key(BlockState state) {
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }
}
