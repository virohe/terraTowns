package com.terraTowns.worldgen;

import com.terraTowns.TerraTowns;
import com.terraTowns.npc.ProfessorEntity;
import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Generates the spawn hamlet by stamping a curated set of <em>vanilla plains village</em>
 * templates (residential houses + farms only — no job-site buildings) around a centre.
 *
 * <p><b>Site planner:</b> the site is scanned first — a grid of candidate plot centres around
 * the hamlet is scored for flatness, dryness, foliage <em>and compactness</em> (closer to the
 * well scores better), the well/spawn goes to the flattest clear patch near the middle, and
 * buildings are assigned best-plot-first (largest pieces first).</p>
 *
 * <p><b>Vertical placement — the entrance cell sits on the ground.</b> Vanilla's plains
 * templates are not vertically uniform (some carry one or two sub-grade fill layers under their
 * floor; the lamp's y=0 layer is air), so no single "local y=0 goes at ground level" rule
 * works. What they all share is the cell vanilla's own street jigsaws connect to: the
 * {@code building_entrance} jigsaw on houses, farms and pens, a street connector on town
 * centres. That cell is always the first air block above the outside ground, so placement lands
 * it there. Measured from the 1.21.1 templates:</p>
 *
 * <pre>
 *   entrance is a bottom-half stair    small_house_1-5/7/8, medium_house_2 -> floor 1 up, step in front
 *   stair beside a structure-void cell small_house_6, medium_house_1       -> floor 1 up, step in front
 *   walking-level air over dirt_path   big_house_1                         -> floor flush with the ground
 *   town centres, pens                 street / fence-gate at walking level -> ground layer at ground
 * </pre>
 *
 * <p>0.3.14 put each template's FLOOR layer at ground level instead. That was right for
 * big_house_1 only: for every other house the entrance stair replaced the ground block, so its
 * top sat half a block below the grass beside it — the "sunken step" from playtesting.</p>
 *
 * <p><b>Per-structure ground height ("patio" level):</b> the outside ground is read at the first
 * column clear of the piece in front of its real door, computed from the door's template-local
 * position through the applied rotation. The uphill side may sink into the slope up to
 * {@link #MAX_BURY} blocks and the downhill side may be underpinned up to {@link #MAX_STILT}
 * blocks; a plot exceeding either is rejected and the piece tries the next plot. Underpinning
 * goes under the floor course only — never under an overhang (see {@code placeTemplateAt}).</p>
 *
 * <p><b>Cohesion (paths + rotation):</b> each piece is rotated so its entrance faces the well,
 * using the template's {@code building_entrance} jigsaw (authoritative — present in every
 * template here except the lamp) with the door as a fallback, and a dirt walkway runs from the
 * real doorstep column back to the well.</p>
 *
 * <p><b>Population:</b> vanilla village house templates contain <em>zero</em> baked entities —
 * their villagers come from {@code minecraft:bottom} jigsaws pointing at the
 * {@code village/plains/villagers} pool, which {@link JigsawReplacementProcessor} would
 * otherwise silently flatten into oak planks. Those jigsaw positions are read before placement
 * and a villager is spawned on each, so villagers arrive <em>inside their own houses</em> next
 * to the beds {@code SettlementPromotion} counts.</p>
 */
public final class HamletPiece {

    private static final String POOL = "village/plains/";

    /** Residential houses by size (no job sites), so a hamlet mixes small/medium/big. */
    private static final List<String> SMALL_HOUSES = List.of(
            "houses/plains_small_house_1", "houses/plains_small_house_2",
            "houses/plains_small_house_3", "houses/plains_small_house_4",
            "houses/plains_small_house_5", "houses/plains_small_house_6",
            "houses/plains_small_house_7", "houses/plains_small_house_8");
    private static final List<String> MEDIUM_HOUSES = List.of(
            "houses/plains_medium_house_1", "houses/plains_medium_house_2");
    private static final List<String> BIG_HOUSES = List.of(
            "houses/plains_big_house_1");

    /** Crop farms and animal pens — every hamlet gets at least one of each. */
    private static final List<String> CROP_FARMS = List.of(
            "houses/plains_small_farm_1", "houses/plains_large_farm_1");
    private static final List<String> ANIMAL_PENS = List.of(
            "houses/plains_animal_pen_1", "houses/plains_animal_pen_2");

    private static final String LAMP = "plains_lamp_1";

    /**
     * The hamlet's centre piece: vanilla's plains well-with-bell. Its bell is a vanilla
     * meeting-point marker, so villagers gather at it the way they do in a real village, and it
     * reads as a clear centre from any direction. Replaces the old 5x5 flattened patch with a
     * single stone brick and torch, which nobody could find.
     */
    private static final String CENTER = "town_centers/plains_meeting_point_1";

    /**
     * TEMPORARY (2026-09-26, playtest request): clear every tree inside the hamlet's perimeter so
     * the layout can be inspected without canopy in the way. This is a viewing aid, not a design
     * decision — whether hamlets should clear their surroundings permanently, partially, or not
     * at all is still open. Flip to false to restore normal behaviour.
     */
    private static final boolean CLEAR_HAMLET_TREES = true;
    /** How far past the outermost building the temporary tree clear reaches. */
    private static final int TREE_CLEAR_MARGIN = 4;
    /** How high above the ground the temporary tree clear reaches — enough for tall canopies. */
    private static final int TREE_CLEAR_HEIGHT = 32;
    /** Largest single tree (in logs) the clear will fell — a guard against eating a giant forest. */
    private static final int TREE_MAX_LOGS = 2500;

    /**
     * Lamps stand beside the walkways, spread out: the first by the well, each next one as far
     * from every lamp so far as a path-side spot allows, until none is at least
     * {@link #LAMP_SPACING} from the rest or there are {@link #LAMP_MAX}. 0.4.6 placed lamps
     * until no ground at all was dark, simulating block light to do it — 15 to 24 lamps a
     * hamlet, which was too many and too much work at world creation for what it bought.
     */
    private static final int LAMP_MAX = 8;
    private static final int LAMP_SPACING = 12;
    /** Dev audit only: how far past the outermost building the lighting report looks. */
    private static final int LIGHT_MARGIN = 4;
    private static final int LIGHT_FLOOR = 1;

    /** Walkway routing costs (A*, per column step). Existing walkway is cheap, so lanes merge. */
    private static final int STEP_COST = 10;
    private static final int STEP_ON_PATH = 4;
    private static final int STEP_BESIDE_BUILDING = 6;
    private static final int STEP_PER_BLOCK_CLIMB = 8;
    private static final int STEP_STEEP = 60;
    private static final int ROUTE_REACH = 24;

    // --- site-planner tuning -------------------------------------------------

    /**
     * Radius (blocks) around the well within which building plots are considered. NOT clamped
     * to the site finder's validated footprint (radius 20) — a disc that small physically can't
     * hold 7–9 pieces once each reserves its footprint + margins, which produced near-empty
     * two-building hamlets in playtesting. Drift onto bad ground is prevented instead by each
     * plot's own wet/steep check plus the {@link #COMPACTNESS_WEIGHT} pull toward the well.
     */
    private static final int PLOT_RADIUS = 34;
    /**
     * Grid spacing (blocks) between candidate plot centres. Tightened from 5: at 5 the plots
     * were quantised coarsely enough that pieces could not nestle, and the harness measured
     * 1418 overlap rejections against only 53 placed pieces — hamlets came out at ~2.5 houses
     * instead of the intended 4–5. A finer grid packs the same disc far better.
     */
    private static final int PLOT_STEP = 3;
    /** How far (blocks) the well may slide from the site centre to find a flat clear patch. */
    private static final int WELL_SEARCH_RADIUS = 10;
    /** Grid step of the well search. Divides {@link #WELL_SEARCH_RADIUS} so offset 0 is sampled. */
    private static final int WELL_SEARCH_STEP = 5;
    /**
     * Max ground-height spread across a plot before it is rejected outright. This is a coarse
     * pre-filter only — the real guarantees are the per-piece {@link #MAX_BURY} and
     * {@link #MAX_STILT} caps, which measure the actual rotated footprint rather than a sparse
     * sample grid, so this can stay loose enough not to starve hilly sites.
     */
    private static final int MAX_PLOT_RELIEF = 3;
    /** Clearance (blocks) added around each placed piece when reserving its plot. */
    private static final int PLOT_MARGIN = 2;
    /**
     * Max blocks the uphill walls may sink into a slope when a piece sits at door ("patio")
     * level. A plot that would bury deeper is rejected and the piece tries the next candidate.
     */
    private static final int MAX_BURY = 2;
    /**
     * Max blocks of foundation that may be poured under the downhill walls — the mirror image
     * of {@link #MAX_BURY}, and the cap that was missing. Only the uphill side was ever
     * checked, so a drop hiding between the plot scorer's sample points let a piece ride a
     * multi-block cobblestone pedestal: exactly the "raised up on a cobblestone foundation"
     * failure from playtesting.
     */
    private static final int MAX_STILT = 2;
    /** Bury limit for open pieces (pens, farms): one block, which the fence or rim just replaces. */
    private static final int OPEN_PIECE_MAX_BURY = 1;
    /**
     * Max non-terrain solid blocks (trunks, canopy, giant modded flora) tolerated inside a
     * piece's own volume. Vegetation up to this is cleared before stamping — vanilla villages
     * overwrite what they land on — and beyond it the plot is rejected, so a house is never
     * driven into the middle of a mature tree but a wooded site still gets a hamlet.
     */
    private static final int MAX_PIECE_OBSTRUCTIONS = 120;
    /**
     * Obstruction score cap. Uncapped, a wooded plot scored in the hundreds and swamped both
     * the relief term and {@link #COMPACTNESS_WEIGHT} entirely, so compactness silently stopped
     * working in exactly the biomes that scatter hamlets worst.
     */
    private static final int OBSTRUCTION_SCORE_CAP = 120;
    /**
     * Compactness weight: added per block of squared distance from the well (relative to
     * {@link #PLOT_RADIUS}) so proximity is a real scoring term, not just a sort tiebreak.
     */
    private static final int COMPACTNESS_WEIGHT = 60;
    /**
     * How far below grade {@link #restoreCarvedGround} will refill ground the template carved
     * out. Only templates whose bottom layers sit below grade (small_house_8's garden) reach it.
     */
    private static final int BACKFILL_MAX_DEPTH = 12;
    /**
     * Fewer houses than this and the hamlet is rebuilt once with the bury/stilt caps relaxed by
     * one. A player claims one of these as their starting settlement, so a site rough enough to
     * seat only one or two houses is a worse outcome than a foundation a block deeper.
     */
    private static final int MIN_HOUSES = 3;
    /** Upper bound on villagers spawned from a hamlet's {@code villagers} jigsaws. */
    private static final int MAX_VILLAGERS = 10;

    /** Blocks a top-block path may be laid over (grass/dirt family); anything else is skipped. */
    private static final BlockState[] PATHABLE = {
            Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
            Blocks.COARSE_DIRT.defaultBlockState(), Blocks.PODZOL.defaultBlockState(),
            Blocks.ROOTED_DIRT.defaultBlockState(), Blocks.MYCELIUM.defaultBlockState(),
            // Coastal sites: without these a beach hamlet's lanes simply never appeared.
            Blocks.SAND.defaultBlockState(), Blocks.RED_SAND.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
    };

    /** Door blocks a template might use for its entrance. */
    private static final Block[] DOOR_BLOCKS = {
            Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR, Blocks.BIRCH_DOOR,
            Blocks.JUNGLE_DOOR, Blocks.ACACIA_DOOR, Blocks.DARK_OAK_DOOR,
    };

    // --- instance state ------------------------------------------------------
    // Placement is main-thread only (ServerStartedEvent / player login), one hamlet at a time.
    // Instance scope exists so the ground-height cache below has a natural lifetime: groundTop
    // is asked for the same few thousand columns tens of thousands of times per hamlet.

    private final ServerLevel level;
    private final RandomSource random;
    private final StructureTemplateManager templates;
    private final Map<Long, Integer> groundCache = new HashMap<>();
    /**
     * Every column a walkway has been routed across. Later pieces may not be placed over them;
     * without this, a house placed after its neighbour's path simply landed on top of it.
     */
    private final Set<Long> pathColumns = new HashSet<>();
    /**
     * The well's footprint. Walkways run INTO it — it is the plaza they lead to — where every other
     * footprint stops them. Treating it like a house left every path a block or two short of
     * the plaza with grass in between, reading as "no path to the well".
     */
    @Nullable
    private int[] centerFootprint;
    /**
     * Every piece placed so far (the build's footprint list). Clearing a new piece's plot must
     * never reach into one: 0.4.6 lamps cleared "vegetation" in a one-block margin around
     * themselves, and to that clear a farm's farmland and crops are just non-terrain blocks —
     * a lamp set beside a farm tore up the corner of it.
     */
    private List<int[]> placedFootprints = List.of();
    /**
     * Live bury/stilt caps. Normally {@link #MAX_BURY}/{@link #MAX_STILT}; raised by one for a
     * single retry pass when a site is too rough to seat a believable hamlet (see
     * {@link #MIN_HOUSES}). Quality-first is still the default — this only trades a slightly
     * deeper foundation for not dropping a player into a one-house "hamlet".
     */
    private int buryCap = MAX_BURY;
    private int stiltCap = MAX_STILT;

    private HamletPiece(ServerLevel level) {
        this.level = level;
        this.random = level.getRandom();
        this.templates = level.getStructureManager();
    }

    /**
     * Build the hamlet near {@code origin} and return a safe, on-land spawn position at its
     * centre (feet on solid ground).
     */
    public static BlockPos place(ServerLevel level, BlockPos origin) {
        return new HamletPiece(level).build(origin);
    }

    private BlockPos build(BlockPos origin) {
        // The finder reads noise heights, which don't show carved water crags. If the chosen
        // centre actually fell on a below-sea-level crag, slide the whole hamlet to the nearest
        // dry, above-sea column so we never build the spawn (or houses) into water.
        BlockPos dry = nearestDryColumn(origin.getX(), origin.getZ(), 20);

        // 1. The well/spawn goes to the flattest, clearest small patch near the site centre.
        int wellX = dry.getX();
        int wellZ = dry.getZ();
        int bestWell = Integer.MAX_VALUE;
        for (int dx = -WELL_SEARCH_RADIUS; dx <= WELL_SEARCH_RADIUS; dx += WELL_SEARCH_STEP) {
            for (int dz = -WELL_SEARCH_RADIUS; dz <= WELL_SEARCH_RADIUS; dz += WELL_SEARCH_STEP) {
                int score = plotScore(dry.getX() + dx, dry.getZ() + dz, 5);
                if (score < bestWell) {
                    bestWell = score;
                    wellX = dry.getX() + dx;
                    wellZ = dry.getZ() + dz;
                }
            }
        }
        // The centre goes in first, before any plot is scored, so every building is laid out
        // around it and every walkway ends at it. The flattened spawn patch survives only as a
        // fallback for a site too rough to seat the well (the same bury/stilt caps apply).
        List<int[]> rects = new ArrayList<>();      // reserved plots (with margin): {cx, cz, halfX, halfZ}
        List<int[]> footprints = new ArrayList<>(); // tight footprints: {minX, minZ, maxX, maxZ, topY}
        placedFootprints = footprints;
        List<BlockPos> villagerSpots = new ArrayList<>();
        BlockPos spawn;
        Placement center = placeCenter(wellX, wellZ);
        if (center != null) {
            rects.add(new int[]{wellX, wellZ, center.sizeX() / 2 + PLOT_MARGIN, center.sizeZ() / 2 + PLOT_MARGIN});
            centerFootprint = new int[]{center.minX(), center.minZ(),
                    center.minX() + center.sizeX() - 1, center.minZ() + center.sizeZ() - 1,
                    center.baseY() + center.sizeY()};
            footprints.add(centerFootprint);
            if (center.approach() != null) {
                // The spot in front of the well (where the player arrives) joins the plaza too.
                layPath(center.approach().getX(), center.approach().getZ(), null, wellX, wellZ, footprints);
            }
            // Stand the player just outside the well, on the ground in front of it.
            spawn = center.approach() != null
                    ? center.approach().above()
                    : new BlockPos(wellX, center.grade() + 1, wellZ);
            HamletAudit.piece(this, CENTER, Rotation.NONE, wellX, wellZ, center);
        } else {
            TerraTowns.LOGGER.info("Hamlet centre at {},{} too rough for the well — using a cleared patch",
                    wellX, wellZ);
            HamletAudit.skipped(CENTER);
            spawn = centerPatch(wellX, wellZ);
            rects.add(new int[]{wellX, wellZ, 5, 5});
            centerFootprint = new int[]{wellX - 2, wellZ - 2, wellX + 2, wellZ + 2, spawn.getY() + 5};
            footprints.add(centerFootprint);
        }
        groundCache.clear(); // the centre reshaped the ground under it; drop the stale heights

        // 2. Score a grid of candidate plot centres — flat, dry, foliage-free, AND close to the
        // well (compactness is a real weighted term, not just a sort tiebreak).
        List<int[]> candidates = new ArrayList<>(); // {x, z, score}
        for (int dx = -PLOT_RADIUS; dx <= PLOT_RADIUS; dx += PLOT_STEP) {
            for (int dz = -PLOT_RADIUS; dz <= PLOT_RADIUS; dz += PLOT_STEP) {
                if (dx * dx + dz * dz > PLOT_RADIUS * PLOT_RADIUS) {
                    continue;
                }
                int score = plotScore(wellX + dx, wellZ + dz, 6);
                if (score == Integer.MAX_VALUE) {
                    continue;
                }
                int distSq = dx * dx + dz * dz;
                int compactness = (int) ((long) distSq * COMPACTNESS_WEIGHT / (PLOT_RADIUS * PLOT_RADIUS));
                candidates.add(new int[]{wellX + dx, wellZ + dz, score + compactness});
            }
        }
        candidates.sort(Comparator.comparingInt(c -> c[2]));
        boolean[] used = new boolean[candidates.size()];

        // 3. Assemble the whole build list, then place it STRICTLY biggest-footprint-first.
        // Ordering only within the house phase left the 13x9 large farm and the 13x11 medium
        // house arriving after five houses had already claimed the central plots: across the
        // first harness run the large farm failed to place in 7 of 8 hamlets. Area order lets
        // every oversized piece bid before the small ones box it out.
        int farmCount = 2 + random.nextInt(3);
        List<String> build = new ArrayList<>(houseSet());
        int houseCount = build.size();
        List<String> farms = new ArrayList<>();
        farms.add(pick(CROP_FARMS));
        farms.add(pick(ANIMAL_PENS));
        while (farms.size() < farmCount) {
            farms.add(pick(random.nextBoolean() ? CROP_FARMS : ANIMAL_PENS));
        }
        build.addAll(farms);
        build.sort(Comparator.comparingInt(this::footprintArea).reversed());

        int houses = 0;
        int placedFarms = 0;
        List<BlockPos> penSpots = new ArrayList<>();
        List<String> unplaced = new ArrayList<>();
        for (String path : build) {
            if (!placeOne(path, candidates, used, rects, footprints, villagerSpots, penSpots,
                    wellX, wellZ)) {
                unplaced.add(path);
                continue;
            }
            if (path.contains("_house_")) {
                houses++;
            } else {
                placedFarms++;
            }
        }

        // Rough site: one relaxed retry rather than handing a player a one-house hamlet.
        if (houses < MIN_HOUSES && !unplaced.isEmpty()) {
            buryCap = MAX_BURY + 1;
            stiltCap = MAX_STILT + 1;
            TerraTowns.LOGGER.debug("Hamlet at {},{} seated only {} houses — retrying with "
                    + "relaxed caps (bury {}, stilt {})", wellX, wellZ, houses, buryCap, stiltCap);
            for (String path : unplaced) {
                if (!placeOne(path, candidates, used, rects, footprints, villagerSpots, penSpots,
                        wellX, wellZ)) {
                    continue;
                }
                if (path.contains("_house_")) {
                    houses++;
                } else {
                    placedFarms++;
                }
            }
            buryCap = MAX_BURY;
            stiltCap = MAX_STILT;
        }
        if (houses < houseCount) {
            TerraTowns.LOGGER.debug("Hamlet placed {}/{} houses", houses, houseCount);
        }

        if (CLEAR_HAMLET_TREES) {
            clearTreesAround(footprints);
        }

        // 4. Lamps last, beside the walkways (see LAMP_MAX).
        Map<String, Object> lighting = placeLamps(footprints);

        BlockPos hamletCenter = new BlockPos(wellX, spawn.getY(), wellZ);
        spawnProfessor(spawn, footprints);
        int villagers = spawnVillagers(hamletCenter, villagerSpots);
        spawnAnimals(penSpots);
        TerraTowns.LOGGER.info("Placed spawn hamlet at {} ({} houses, {} farms, {} villagers, {} plots scored)",
                hamletCenter, houses, placedFarms, villagers, candidates.size());
        HamletAudit.hamlet(hamletCenter, houses, placedFarms, villagers, candidates.size(), lighting);
        return spawn;
    }

    /**
     * Place one build-list entry, choosing its foundation material and recording animal pens.
     *
     * @return true if it was seated.
     */
    private boolean placeOne(String path, List<int[]> candidates, boolean[] used, List<int[]> rects,
                             List<int[]> footprints, List<BlockPos> villagerSpots,
                             List<BlockPos> penSpots, int wellX, int wellZ) {
        BlockState foundation = path.contains("_house_")
                ? Blocks.COBBLESTONE.defaultBlockState()
                : Blocks.OAK_LOG.defaultBlockState();
        int[] spot = placePiece(path, candidates, used, rects, footprints, villagerSpots,
                foundation, wellX, wellZ, true);
        if (spot == null) {
            return false;
        }
        if (path.contains("animal_pen")) {
            penSpots.add(new BlockPos(spot[0], 0, spot[1]));
        }
        return true;
    }

    /** @return the template's unrotated footprint area, or 0 if it is missing. */
    private int footprintArea(String path) {
        return templates.get(ResourceLocation.withDefaultNamespace(POOL + path))
                .map(t -> t.getSize().getX() * t.getSize().getZ())
                .orElse(0);
    }

    /** Big → small house selection: 4 houses (big, medium, 2×small) or 5 (adds a medium). */
    private List<String> houseSet() {
        List<String> out = new ArrayList<>();
        out.add(pick(BIG_HOUSES));
        out.add(pick(MEDIUM_HOUSES));
        if (random.nextBoolean()) {
            out.add(pick(MEDIUM_HOUSES));
        }
        out.add(pick(SMALL_HOUSES));
        out.add(pick(SMALL_HOUSES));
        return out;
    }

    /**
     * Suitability of a plot centred at (cx, cz) with half-extent {@code half}: lower is better,
     * {@link Integer#MAX_VALUE} means unusable (wet or too steep). Sampled on a 5×5 grid (25
     * points at {@code half/2} spacing) rather than 3×3 — at 3×3 the samples sat 6 blocks apart
     * on the largest plots, so a ridge or dip <em>between</em> them read as flat and the piece
     * discovered the real relief only at placement time.
     */
    private int plotScore(int cx, int cz, int half) {
        int sea = level.getSeaLevel();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int ix = -2; ix <= 2; ix++) {
            for (int iz = -2; iz <= 2; iz++) {
                int y = groundTop(cx + ix * half / 2, cz + iz * half / 2);
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        if (min < sea) {
            return Integer.MAX_VALUE; // wet plot
        }
        int relief = max - min;
        if (relief > MAX_PLOT_RELIEF) {
            return Integer.MAX_VALUE; // too steep
        }
        int obstructions = countObstructions(cx - half, cz - half, 2 * half + 1, 2 * half + 1, 12);
        return relief * 8 + Math.min(obstructions, OBSTRUCTION_SCORE_CAP);
    }

    /**
     * Assign the best free, non-overlapping plot to this template, rotate it to face the well,
     * build it there, and (if {@code layPath}) lay a doorstep-to-well walkway.
     *
     * @return the plot centre used, or null if the template is missing or no clear plot remains.
     */
    @Nullable
    private int[] placePiece(String path, List<int[]> candidates, boolean[] used, List<int[]> rects,
                             List<int[]> footprints, List<BlockPos> villagerSpots,
                             BlockState foundation, int wellX, int wellZ, boolean layPath) {
        Optional<StructureTemplate> maybe = templates.get(ResourceLocation.withDefaultNamespace(POOL + path));
        if (maybe.isEmpty()) {
            TerraTowns.LOGGER.warn("Hamlet template missing: {}{}", POOL, path);
            return null;
        }
        StructureTemplate template = maybe.get();
        Profile profile = profile(path, template);

        for (int i = 0; i < candidates.size(); i++) {
            if (used[i]) {
                continue;
            }
            int[] c = candidates.get(i);
            int cx = c[0];
            int cz = c[1];

            // Rotate so the piece's entrance faces back toward the well/path — the same
            // "doors face the street" trick that makes vanilla villages read as one settlement.
            Direction toWell = cardinalToward(cx, cz, wellX, wellZ);
            Rotation rotation = profile.front() == null
                    ? Rotation.NONE
                    : rotationToFace(profile.front(), toWell);
            Vec3i sizeR = template.getSize(rotation);
            int halfX = sizeR.getX() / 2 + PLOT_MARGIN;
            int halfZ = sizeR.getZ() / 2 + PLOT_MARGIN;

            if (overlapsAny(rects, cx, cz, halfX, halfZ)) {
                HamletAudit.reject("overlap", 0);
                continue;
            }
            if (crossesPath(cx - sizeR.getX() / 2, cz - sizeR.getZ() / 2, sizeR.getX(), sizeR.getZ())) {
                HamletAudit.reject("path", 0);
                continue;
            }

            Placement placed = placeTemplateAt(template, profile, cx, cz, foundation, rotation);
            if (placed == null) {
                // Bury / stilt / obstruction cap failed HERE. The plot stays OPEN: marking it
                // used burned it for every later, smaller piece too, which was thinning
                // hamlets for no reason.
                continue;
            }
            used[i] = true;
            rects.add(new int[]{cx, cz, halfX, halfZ});
            footprints.add(new int[]{placed.minX(), placed.minZ(),
                    placed.minX() + sizeR.getX() - 1, placed.minZ() + sizeR.getZ() - 1,
                    placed.baseY() + placed.sizeY()});
            villagerSpots.addAll(placed.villagerSpots());

            if (layPath && placed.approach() != null) {
                layPath(placed.approach().getX(), placed.approach().getZ(),
                        rotateDirection(profile.front(), rotation), wellX, wellZ, footprints);
            }
            HamletAudit.piece(this, path, rotation, cx, cz, placed);
            return new int[]{cx, cz};
        }
        TerraTowns.LOGGER.debug("No clear plot left for hamlet piece {}{} — skipped", POOL, path);
        HamletAudit.skipped(path);
        return null;
    }

    // --- template introspection ---------------------------------------------

    /**
     * Everything read off a template before placement: which way its entrance faces, where its
     * door is, which local layer is its floor, and where vanilla would have spawned villagers.
     */
    private record Profile(@Nullable Direction front, @Nullable BlockPos door, int floorLocalY,
                           int walkLocalY, List<BlockPos> villagerJigsaws, boolean open) {
    }

    /**
     * Read a template's placement profile.
     *
     * <p>Front direction comes from the {@code minecraft:building_entrance} jigsaw, whose
     * {@code front()} is the authoritative outward direction and which is present in every
     * template here except the lamp. The door's own position is only a fallback: it is a
     * centroid guess with an arbitrary axis tie-break, and templates like small_house_5 carry
     * interior doors that can outvote the entrance. First horizontal jigsaw is the last resort.</p>
     *
     * <p>Floor course is {@code doorY - 1} — the door block stands at walking level by
     * definition, so the block under it is the floor. Cross-checked against every doored
     * template's bed heights. Doorless pieces' floor course is their y=0 layer. The walk cell —
     * what lands on the first air block above the outside ground — is the building_entrance
     * jigsaw, else a street connector, else the door, else y=0.</p>
     */
    private Profile profile(String path, StructureTemplate template) {
        StructurePlaceSettings plain = new StructurePlaceSettings();
        Direction front = null;
        Direction firstHorizontal = null;
        Integer entranceY = null;
        Integer streetY = null;
        List<BlockPos> villagerJigsaws = new ArrayList<>();

        for (StructureTemplate.StructureBlockInfo info : template.filterBlocks(BlockPos.ZERO, plain, Blocks.JIGSAW)) {
            CompoundTag nbt = info.nbt();
            String name = nbt == null ? "" : nbt.getString("name");
            String pool = nbt == null ? "" : nbt.getString("pool");
            Direction f = info.state().getValue(JigsawBlock.ORIENTATION).front();
            if (f.getAxis().isHorizontal()) {
                if ("minecraft:building_entrance".equals(name)) {
                    front = f;
                    entranceY = info.pos().getY();
                } else if (firstHorizontal == null) {
                    firstHorizontal = f;
                }
            }
            if (pool.contains("villagers")) {
                villagerJigsaws.add(info.pos());
            }
            if (streetY == null && name.contains("street")) {
                streetY = info.pos().getY();
            }
        }

        BlockPos door = entranceDoor(template, plain, front);

        if (front == null && door != null) {
            Vec3i size = template.getSize();
            double offX = door.getX() - (size.getX() - 1) / 2.0;
            double offZ = door.getZ() - (size.getZ() - 1) / 2.0;
            front = Math.abs(offX) >= Math.abs(offZ)
                    ? (offX >= 0 ? Direction.EAST : Direction.WEST)
                    : (offZ >= 0 ? Direction.SOUTH : Direction.NORTH);
        }
        if (front == null) {
            front = firstHorizontal;
        }

        // Floor course: the layer the door stands on. Doorless pieces' floor is their y=0 layer.
        int floorLocalY = door != null ? door.getY() - 1 : 0;
        // Walk cell: the template cell that must land on the first air block above the outside
        // ground (see placeTemplateAt). Vanilla marks it for us — the building_entrance jigsaw
        // on houses, farms and pens, a street connector on town centres — so no per-template
        // table is needed. The lamp has neither; its post simply stands on the ground.
        int walkLocalY = entranceY != null ? entranceY
                : streetY != null ? streetY
                : door != null ? door.getY()
                : 0;

        // Open pieces (pens, farms, lamps): no door, and not the town centre, whose plaza is
        // placed by its own rules.
        boolean open = door == null && !path.startsWith("town_centers/");
        return new Profile(front, door, floorLocalY, walkLocalY, villagerJigsaws, open);
    }

    /**
     * @return the lower half of the template's <em>entrance</em> door — the one furthest toward
     *         {@code front}. small_house_5 carries two interior doors on its upper floor;
     *         picking whichever happened to come first in the file would have read the wrong
     *         floor height. Null when the template has no door at all (farms, pens, lamp).
     */
    @Nullable
    private BlockPos entranceDoor(StructureTemplate template, StructurePlaceSettings plain,
                                  @Nullable Direction front) {
        BlockPos best = null;
        int bestRank = Integer.MIN_VALUE;
        for (Block doorBlock : DOOR_BLOCKS) {
            for (StructureTemplate.StructureBlockInfo info : template.filterBlocks(BlockPos.ZERO, plain, doorBlock)) {
                if (info.state().hasProperty(DoorBlock.HALF)
                        && info.state().getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                    continue;
                }
                BlockPos pos = info.pos();
                // Rank by how far toward the entrance side the door sits; with no front known,
                // prefer the lowest door, which is the ground-floor entrance.
                int rank = front == null
                        ? -pos.getY()
                        : pos.getX() * front.getStepX() + pos.getZ() * front.getStepZ();
                if (best == null || rank > bestRank) {
                    best = pos;
                    bestRank = rank;
                }
            }
        }
        return best;
    }

    /** @return the rotation that turns {@code front} into {@code desired} (standard MC cycle: N→E→S→W). */
    private static Rotation rotationToFace(Direction front, Direction desired) {
        for (Rotation r : Rotation.values()) {
            if (rotateDirection(front, r) == desired) {
                return r;
            }
        }
        return Rotation.NONE; // unreachable: front/desired are always horizontal cardinals
    }

    @Nullable
    private static Direction rotateDirection(@Nullable Direction dir, Rotation rotation) {
        if (dir == null) {
            return null;
        }
        int steps = switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
        };
        Direction result = dir;
        for (int i = 0; i < steps; i++) {
            result = switch (result) {
                case NORTH -> Direction.EAST;
                case EAST -> Direction.SOUTH;
                case SOUTH -> Direction.WEST;
                case WEST -> Direction.NORTH;
                default -> result; // UP/DOWN can't occur for a horizontal jigsaw front
            };
        }
        return result;
    }

    /**
     * Map a template-local (x, z) onto its offset inside the <em>rotated</em> footprint, i.e.
     * 0..sxR-1 by 0..szR-1. This is vanilla's rotation-about-the-origin
     * ({@code StructureTemplate.transform} with a ZERO pivot) re-normalised so the minimum
     * corner is (0, 0) — the form both the origin offset and the door column need.
     */
    private static int[] rotateLocal(int x, int z, Rotation rotation, int sx, int sz) {
        return switch (rotation) {
            case NONE -> new int[]{x, z};
            case CLOCKWISE_90 -> new int[]{sz - 1 - z, x};
            case CLOCKWISE_180 -> new int[]{sx - 1 - x, sz - 1 - z};
            case COUNTERCLOCKWISE_90 -> new int[]{z, sx - 1 - x};
        };
    }

    /** @return the nearest cardinal direction from (fromX,fromZ) toward (toX,toZ). */
    private static Direction cardinalToward(int fromX, int fromZ, int toX, int toZ) {
        int dx = toX - fromX;
        int dz = toZ - fromZ;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // --- stamping ------------------------------------------------------------

    /** What a successful stamp produced, for path routing, villager spawning and the audit. */
    record Placement(int minX, int minZ, int sizeX, int sizeY, int sizeZ, int baseY, int floorY,
                     int maxStilt, int maxBury, int obstructions, boolean hasDoor,
                     int minPerimeterGround, int maxPerimeterGround,
                     @Nullable BlockPos door, @Nullable BlockPos approach,
                     List<BlockPos> villagerSpots,
                     int grade, int rise, boolean stairInFront, boolean stairSunk,
                     int pouredAboveFloor) {
    }

    /**
     * Stamp one (possibly rotated) template centred (in X/Z) on its plot so that the template's
     * own <b>floor layer</b> lands on the ground outside its door.
     *
     * <p>Placement uses the default ZERO rotation pivot, so the world position handed to
     * {@code placeInWorld} must be where the template's origin voxel lands — not the rotated
     * footprint's min corner. The origin's offset from that corner depends on the rotation (a
     * 90°/270° turn swaps which corner the origin becomes); the four cases below are the
     * standard 2D-rotation-about-a-corner result under Minecraft's N→E→S→W convention.</p>
     *
     * @return the placement, or null if the plot failed the bury / stilt / obstruction caps.
     */
    @Nullable
    private Placement placeTemplateAt(StructureTemplate template, Profile profile, int cx, int cz,
                                      BlockState foundation, Rotation rotation) {
        Vec3i unrotated = template.getSize();
        int sx = unrotated.getX();
        int sy = unrotated.getY();
        int sz = unrotated.getZ();
        Vec3i sizeR = template.getSize(rotation);
        int sxR = sizeR.getX();
        int szR = sizeR.getZ();

        int minWorldX = cx - sxR / 2;
        int minWorldZ = cz - szR / 2;
        int originOffsetX = switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> sz - 1;
            case CLOCKWISE_180 -> sx - 1;
            case COUNTERCLOCKWISE_90 -> 0;
        };
        int originOffsetZ = switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 0;
            case CLOCKWISE_180 -> sz - 1;
            case COUNTERCLOCKWISE_90 -> sx - 1;
        };
        int originX = minWorldX + originOffsetX;
        int originZ = minWorldZ + originOffsetZ;

        // The real door column, carried through the rotation — not the front edge's midpoint.
        // medium_house_1's door sits 2 blocks off its edge centre, so the midpoint sampled the
        // wrong ground column for the patio height and aimed the path at a blank wall.
        Direction worldFront = rotateDirection(profile.front(), rotation);
        BlockPos doorColumn = null;
        BlockPos doorstepColumn = null;
        if (profile.door() != null) {
            int[] r = rotateLocal(profile.door().getX(), profile.door().getZ(), rotation, sx, sz);
            doorColumn = new BlockPos(minWorldX + r[0], 0, minWorldZ + r[1]);
        } else if (worldFront != null) {
            // Doorless (farm, pen, lamp): the entrance is the middle of the front edge.
            int dx = switch (worldFront) {
                case EAST -> sxR - 1;
                case WEST -> 0;
                default -> sxR / 2;
            };
            int dz = switch (worldFront) {
                case SOUTH -> szR - 1;
                case NORTH -> 0;
                default -> szR / 2;
            };
            doorColumn = new BlockPos(minWorldX + dx, 0, minWorldZ + dz);
        }
        if (doorColumn != null && worldFront != null) {
            // Walk outward until genuinely clear of the footprint. The door is not on the
            // footprint edge: vanilla puts the doorstep block (the building_entrance jigsaw,
            // which becomes a stair or a path block) one further out, still inside the
            // template. Sampling grade on that cell measured the piece's own doorstep rather
            // than the ground a player actually approaches across.
            BlockPos c = doorColumn;
            for (int step = 0; step < 4; step++) {
                c = c.relative(worldFront);
                boolean insideX = c.getX() >= minWorldX && c.getX() < minWorldX + sxR;
                boolean insideZ = c.getZ() >= minWorldZ && c.getZ() < minWorldZ + szR;
                if (!insideX || !insideZ) {
                    break;
                }
            }
            doorstepColumn = c;
        }

        // "Patio" height: the ground of the first column OUTSIDE the piece — the surface the
        // player actually walks up from. Falls back to the plot centre for the lamp.
        int patioY = doorstepColumn != null
                ? groundTop(doorstepColumn.getX(), doorstepColumn.getZ())
                : groundTop(cx, cz);

        // Per-column ground over the rotated footprint, and the perimeter extremes that decide
        // whether this plot is placeable at all.
        int[][] ground = new int[sxR][szR];
        int maxPerimeter = Integer.MIN_VALUE;
        int minPerimeter = Integer.MAX_VALUE;
        for (int dx = 0; dx < sxR; dx++) {
            for (int dz = 0; dz < szR; dz++) {
                int g = groundTop(minWorldX + dx, minWorldZ + dz);
                ground[dx][dz] = g;
                if (dx == 0 || dx == sxR - 1 || dz == 0 || dz == szR - 1) {
                    maxPerimeter = Math.max(maxPerimeter, g);
                    minPerimeter = Math.min(minPerimeter, g);
                }
            }
        }

        // Vertical placement. The template's WALK cell -- its building_entrance jigsaw, or a town
        // centre's street connector -- lands on the first air block above the outside ground.
        // That is the height vanilla's own street jigsaws put it at, and it is the only rule that
        // fits every template: a house whose entrance is a stair gets its floor raised one block
        // with the stair resting ON the ground as a step up (small houses, medium_house_2), while
        // big_house_1, whose entrance cell is walking-level air over a dirt path, comes out flush.
        // The 0.3.14 rule put the FLOOR at ground level instead, which sank every entrance stair
        // into the ground so its top sat half a block below the grass beside it.
        int grade = patioY;
        int baseY = grade + 1 - profile.walkLocalY();
        int floorY = baseY + profile.floorLocalY();   // world Y of the floor course
        int maxBury = maxPerimeter - grade;
        int maxStilt = grade - minPerimeter;
        // Open pieces (pens, farms — no door) have no walls to hold back a hillside: their fence
        // ring's air cells cut a trench into the slope while the ground inside stayed as it was.
        int pieceBuryCap = profile.open() ? Math.min(buryCap, OPEN_PIECE_MAX_BURY) : buryCap;
        if (maxBury > pieceBuryCap) {
            HamletAudit.reject("bury", maxBury);
            return null;
        }
        if (maxStilt > stiltCap) {
            HamletAudit.reject("stilt", maxStilt);
            return null;
        }

        int obstructions = countObstructions(minWorldX, minWorldZ, sxR, szR, sy + 2);
        if (obstructions > MAX_PIECE_OBSTRUCTIONS) {
            HamletAudit.reject("obstructions", obstructions);
            return null;
        }
        clearVegetation(minWorldX - 1, minWorldZ - 1, sxR + 2, szR + 2, sy + 4);

        // Remember each column's surface block before the template cuts into it, so ground it
        // carves out at or below grade can be put back in kind (grass stays grass, sand stays sand).
        BlockState[][] surface = new BlockState[sxR][szR];
        for (int dx = 0; dx < sxR; dx++) {
            for (int dz = 0; dz < szR; dz++) {
                surface[dx][dz] = level.getBlockState(
                        new BlockPos(minWorldX + dx, ground[dx][dz], minWorldZ + dz));
            }
        }

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(false)
                .setRotation(rotation)
                .addProcessor(JigsawReplacementProcessor.INSTANCE);
        template.placeInWorld(level, new BlockPos(originX, baseY, originZ), BlockPos.ZERO, settings,
                random, Block.UPDATE_CLIENTS);

        // Underpin: pour foundation under the FLOOR COURSE only. A column whose lowest solid
        // block sits above the floor course is an overhang -- a roof eave, an awning -- and must
        // stay open. 0.3.14 underpinned "the piece's lowest solid block" in every column, which
        // in an eave column is the eave itself, so it poured cobblestone from under the roof
        // edge down to the ground: a second wall around every house, meeting the roof, paving
        // over the wall torches. (Wall torches have no collision, so the scan passed them by.)
        // It also never overwrites anything that is already there.
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int pouredAboveFloor = 0;
        for (int dx = 0; dx < sxR; dx++) {
            for (int dz = 0; dz < szR; dz++) {
                int wx = minWorldX + dx;
                int wz = minWorldZ + dz;
                int lowest = Integer.MIN_VALUE;
                for (int y = baseY; y < baseY + sy; y++) {
                    BlockState s = level.getBlockState(p.set(wx, y, wz));
                    if (!s.isAir() && !s.getCollisionShape(level, p).isEmpty()) {
                        lowest = y;
                        break;
                    }
                }
                if (lowest == Integer.MIN_VALUE || lowest > floorY) {
                    continue; // open column, or an overhang -- nothing to hold up
                }
                for (int y = lowest - 1; y > ground[dx][dz]; y--) {
                    BlockState current = level.getBlockState(p.set(wx, y, wz));
                    if (!current.isAir() && !current.canBeReplaced()) {
                        continue;
                    }
                    if (y > floorY) {
                        pouredAboveFloor++; // must stay 0 -- the regression this block fixed
                    }
                    set(new BlockPos(wx, y, wz), foundation);
                }
            }
        }

        restoreCarvedGround(minWorldX, minWorldZ, sxR, szR, baseY, grade, ground, surface);
        if (profile.open()) {
            levelOpenInterior(minWorldX, minWorldZ, sxR, szR, floorY, ground);
        }

        // Villager spawn points, carried through the rotation. Vanilla would have grown these
        // jigsaws into villagers; JigsawReplacementProcessor has just flattened them to planks.
        List<BlockPos> villagerSpots = new ArrayList<>();
        for (BlockPos jig : profile.villagerJigsaws()) {
            int[] r = rotateLocal(jig.getX(), jig.getZ(), rotation, sx, sz);
            villagerSpots.add(new BlockPos(minWorldX + r[0], baseY + jig.getY() + 1, minWorldZ + r[1]));
        }

        // Entry check, read back from the world rather than from the arithmetic above: how far
        // the doorway rises above the outside ground, and whether there is a step in front of
        // the door -- and if so, whether its top is sunk below the ground beside it.
        int rise = 0;
        boolean stairInFront = false;
        boolean stairSunk = false;
        if (profile.door() != null && doorColumn != null && worldFront != null) {
            rise = (baseY + profile.door().getY()) - (grade + 1);
            BlockPos step = doorColumn.relative(worldFront);
            for (int y = grade - 1; y <= grade + 1; y++) {
                Block b = level.getBlockState(p.set(step.getX(), y, step.getZ())).getBlock();
                if (b instanceof StairBlock || b instanceof SlabBlock) {
                    stairInFront = true;
                    stairSunk = y <= grade;
                }
            }
        }

        BlockPos doorWorld = doorColumn == null ? null
                : new BlockPos(doorColumn.getX(),
                        baseY + (profile.door() != null ? profile.door().getY() : profile.walkLocalY()),
                        doorColumn.getZ());
        BlockPos doorstepWorld = doorstepColumn == null ? null
                : new BlockPos(doorstepColumn.getX(), grade, doorstepColumn.getZ());
        return new Placement(minWorldX, minWorldZ, sxR, sy, szR, baseY, floorY, maxStilt, maxBury,
                obstructions, profile.door() != null, minPerimeter, maxPerimeter,
                doorWorld, doorstepWorld, villagerSpots,
                grade, rise, stairInFront, stairSunk, pouredAboveFloor);
    }

    /**
     * Level the natural ground inside an open piece down to its floor. A pen's interior is
     * structure void above the floor, so any ground higher than the floor stayed standing inside
     * the fence — steps and pits in the pen. Only natural terrain above the floor course is
     * removed; the piece's own blocks are untouched.
     */
    private void levelOpenInterior(int minX, int minZ, int sizeX, int sizeZ, int floorY, int[][] ground) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int y = ground[dx][dz]; y > floorY; y--) {
                    BlockState st = level.getBlockState(p.set(minX + dx, y, minZ + dz));
                    if (isTerrain(st) && !st.is(Blocks.DIRT_PATH)) {
                        set(p.immutable(), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    /**
     * Put back ground the template carved out at or below grade.
     *
     * <p>Vanilla templates use real air blocks (not structure void) for open cells inside their
     * bounding box, and a template whose bottom layer sits at or below grade stamps that air
     * straight into the terrain. Only cells at or below BOTH the column's original surface and
     * the outside grade are refilled -- anything higher is the piece's own interior, or a slope
     * it was deliberately sunk into (see {@link #MAX_BURY}), and must stay open. The top cell
     * gets the column's original surface block back; anything under it gets dirt.</p>
     */
    private void restoreCarvedGround(int minX, int minZ, int sizeX, int sizeZ, int baseY, int grade,
                                     int[][] ground, BlockState[][] surface) {
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                int top = Math.min(grade, ground[dx][dz]);
                for (int y = Math.max(baseY, top - BACKFILL_MAX_DEPTH); y <= top; y++) {
                    BlockPos pos = new BlockPos(minX + dx, y, minZ + dz);
                    if (!level.getBlockState(pos).isAir()) {
                        continue;
                    }
                    BlockState fill = (y == top && isTerrain(surface[dx][dz]))
                            ? surface[dx][dz]
                            : Blocks.DIRT.defaultBlockState();
                    set(pos, fill);
                }
            }
        }
    }

    // --- paths ---------------------------------------------------------------

    /**
     * Lay a one-block-wide dirt-path walkway from a building's doorstep to the well — top block
     * only, following whatever height the terrain already has at each column.
     *
     * <p>The route is found, not drawn: A* over the ground columns, never through another
     * piece, preferring flat ground and joining walkways already laid so lanes merge into a
     * network. Until 0.4.6 it was an L of two straight legs that simply skipped over any
     * building in the way, so a lane went into one side of a farm and came out the other.
     * If no route exists (water all round) the old straight L is laid as a fallback.</p>
     */
    private void layPath(int doorX, int doorZ, @Nullable Direction doorOut, int wellX, int wellZ,
                         List<int[]> footprints) {
        List<int[]> columns = route(doorX, doorZ, footprints);
        if (columns == null) {
            columns = straightRoute(doorX, doorZ, doorOut, wellX, wellZ);
        }
        for (int[] column : columns) {
            pathColumns.add(columnKey(column[0], column[1]));
            if (insideFootprintExcept(footprints, centerFootprint, column[0], column[1])) {
                continue;
            }
            int y = groundTop(column[0], column[1]);
            if (y < level.getSeaLevel()) {
                continue; // don't path over water
            }
            BlockPos top = new BlockPos(column[0], y, column[1]);
            BlockState current = level.getBlockState(top);
            for (BlockState candidate : PATHABLE) {
                if (current.is(candidate.getBlock()) || isModdedSoil(current)) {
                    set(top, Blocks.DIRT_PATH.defaultBlockState());
                    // Pop any small plant sitting on the new path block so the lane stays
                    // visible (grass/flowers are replaceable cover, not terrain).
                    BlockPos above = top.above();
                    BlockState plant = level.getBlockState(above);
                    if (!plant.isAir() && plant.canBeReplaced() && plant.getFluidState().isEmpty()) {
                        set(above, Blocks.AIR.defaultBlockState());
                    }
                    break;
                }
            }
        }
    }

    /** A* from a doorstep to the well's plaza. @return the columns, door first, or null. */
    @Nullable
    private List<int[]> route(int fromX, int fromZ, List<int[]> footprints) {
        int[] goal = centerFootprint;
        if (goal == null) {
            return null;
        }
        // Bounded to the hamlet's own ground (the plot search already loaded it): a route that
        // wandered further would generate fresh chunks mid-build, all inside one server tick.
        int wellX = (goal[0] + goal[2]) / 2, wellZ = (goal[1] + goal[3]) / 2;
        int lim = PLOT_RADIUS + ROUTE_REACH / 2;
        int minX = Math.max(Math.min(fromX, goal[0]) - ROUTE_REACH, wellX - lim);
        int maxX = Math.min(Math.max(fromX, goal[2]) + ROUTE_REACH, wellX + lim);
        int minZ = Math.max(Math.min(fromZ, goal[1]) - ROUTE_REACH, wellZ - lim);
        int maxZ = Math.min(Math.max(fromZ, goal[3]) + ROUTE_REACH, wellZ + lim);
        if (fromX < minX || fromX > maxX || fromZ < minZ || fromZ > maxZ) {
            return null;
        }
        int w = maxX - minX + 1, h = maxZ - minZ + 1;
        int[] best = new int[w * h];
        int[] from = new int[w * h];
        java.util.Arrays.fill(best, Integer.MAX_VALUE);
        long started = System.nanoTime();
        java.util.PriorityQueue<int[]> open = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingInt((int[] a) -> a[0]));
        int startIdx = (fromZ - minZ) * w + (fromX - minX);
        best[startIdx] = 0;
        from[startIdx] = -1;
        open.add(new int[]{heuristic(fromX, fromZ, goal), startIdx});
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty()) {
            int[] top = open.poll();
            int idx = top[1];
            if (System.nanoTime() - started > 250_000_000L) {
                TerraTowns.LOGGER.warn("Hamlet walkway search from {},{} gave up after 250 ms", fromX, fromZ);
                return null;
            }
            int x = minX + idx % w, z = minZ + idx / w;
            if (top[0] - heuristic(x, z, goal) > best[idx]) {
                continue; // stale entry
            }
            if (x >= goal[0] && x <= goal[2] && z >= goal[1] && z <= goal[3]) {
                java.util.ArrayDeque<int[]> out = new java.util.ArrayDeque<>();
                for (int i = idx; i != -1; i = from[i]) {
                    out.addFirst(new int[]{minX + i % w, minZ + i / w});
                }
                return new ArrayList<>(out);
            }
            int g = groundTop(x, z);
            for (int[] d : steps) {
                int nx = x + d[0], nz = z + d[1];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                    continue;
                }
                boolean inGoal = nx >= goal[0] && nx <= goal[2] && nz >= goal[1] && nz <= goal[3];
                if (!inGoal && insideFootprint(footprints, nx, nz)) {
                    continue; // never through another piece
                }
                int ng = groundTop(nx, nz);
                if (!inGoal && (ng < level.getSeaLevel()
                        || !level.getFluidState(new BlockPos(nx, ng + 1, nz)).isEmpty())) {
                    continue; // no lanes across water
                }
                int climb = Math.abs(ng - g);
                int cost = pathColumns.contains(columnKey(nx, nz)) ? STEP_ON_PATH : STEP_COST;
                cost += climb <= 1 ? climb * STEP_PER_BLOCK_CLIMB : climb * STEP_STEEP;
                if (!inGoal && besideFootprint(footprints, nx, nz)) {
                    cost += STEP_BESIDE_BUILDING; // keep a gap along walls where there's room
                }
                int ni = (nz - minZ) * w + (nx - minX);
                int nb = best[idx] + cost;
                if (nb < best[ni]) {
                    best[ni] = nb;
                    from[ni] = idx;
                    open.add(new int[]{nb + heuristic(nx, nz, goal), ni});
                }
            }
        }
        return null;
    }

    /** Admissible A* estimate: cheapest possible steps to the goal rectangle. */
    private static int heuristic(int x, int z, int[] goal) {
        int dx = x < goal[0] ? goal[0] - x : Math.max(0, x - goal[2]);
        int dz = z < goal[1] ? goal[1] - z : Math.max(0, z - goal[3]);
        return (dx + dz) * STEP_ON_PATH;
    }

    private static boolean besideFootprint(List<int[]> footprints, int x, int z) {
        return insideFootprint(footprints, x + 1, z) || insideFootprint(footprints, x - 1, z)
                || insideFootprint(footprints, x, z + 1) || insideFootprint(footprints, x, z - 1);
    }

    /** The pre-0.4.7 route: straight out of the door, then an L to the well. */
    private static List<int[]> straightRoute(int doorX, int doorZ, @Nullable Direction doorOut, int wellX, int wellZ) {
        List<int[]> columns = new ArrayList<>();
        columns.add(new int[]{doorX, doorZ});
        int x = doorX;
        int z = doorZ;
        if (doorOut != null && doorOut.getStepX() != 0) {
            while (x != wellX) {
                x += Integer.signum(wellX - x);
                columns.add(new int[]{x, z});
            }
            while (z != wellZ) {
                z += Integer.signum(wellZ - z);
                columns.add(new int[]{x, z});
            }
        } else {
            while (z != wellZ) {
                z += Integer.signum(wellZ - z);
                columns.add(new int[]{x, z});
            }
            while (x != wellX) {
                x += Integer.signum(wellX - x);
                columns.add(new int[]{x, z});
            }
        }
        return columns;
    }

    /** @return true if any walkway column falls inside the given rectangle. */
    private boolean crossesPath(int minX, int minZ, int sizeX, int sizeZ) {
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                if (pathColumns.contains(columnKey(minX + dx, minZ + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Place the vanilla well at the hamlet centre, or return null if the site can't seat it. */
    @Nullable
    private Placement placeCenter(int cx, int cz) {
        Optional<StructureTemplate> maybe = templates.get(ResourceLocation.withDefaultNamespace(POOL + CENTER));
        if (maybe.isEmpty()) {
            TerraTowns.LOGGER.warn("Hamlet centre template missing: {}{}", POOL, CENTER);
            return null;
        }
        StructureTemplate template = maybe.get();
        return placeTemplateAt(template, profile(CENTER, template), cx, cz,
                Blocks.COBBLESTONE.defaultBlockState(), Rotation.NONE);
    }

    /**
     * TEMPORARY viewing aid ({@link #CLEAR_HAMLET_TREES}): fell every tree that stands in, or
     * reaches into, the hamlet's perimeter — the WHOLE tree, whatever it is made of.
     *
     * <p>Trees are found from the inside out: any trunk block in the perimeter, plus the trunk
     * behind any leaves hanging into it. A "trunk block" is anything woody or fleshy growing
     * above the ground ({@link #isTreePart}): logs, and also the modded parts that aren't tagged
     * as logs — Oh The Biomes We've Gone's flower stems and petal blocks, fungi, fruit. 0.4.7
     * followed logs only, which left giant-flower heads sitting in the hamlet. Each tree is
     * followed block to block (diagonals included), capped at {@link #TREE_MAX_LOGS}.</p>
     *
     * <p>Then the debris: leaves decay by vanilla's rule, instantly (a leaf with no log within six
     * leaf-steps goes, so neighbouring trees keep theirs); anything that can no longer stay put
     * without the tree — vines, shelf fungi, hanging fruit, moss carpet — comes down with it; and
     * a small clump of soil left hanging in the air (the dirt big trees plant under their
     * trunks, a root plate on a branch) is removed. Roots below the ground are backfilled with
     * dirt. A tree too big to fell whole ({@link #TREE_MAX_LOGS}) is left standing whole rather
     * than cut partway. Anything inside a building's footprint below its roof, anything that
     * looks built (planks, stairs, fences, glass, block entities…) and player-placed
     * (persistent) leaves are never touched.</p>
     */
    private void clearTreesAround(List<int[]> footprints) {
        if (footprints.isEmpty()) {
            return;
        }
        int[] box = perimeter(footprints, TREE_CLEAR_MARGIN);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        java.util.ArrayDeque<BlockPos> trunkSeeds = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<BlockPos> leafSeeds = new java.util.ArrayDeque<>();
        for (int x = box[0]; x <= box[2]; x++) {
            for (int z = box[1]; z <= box[3]; z++) {
                int ground = groundTop(x, z);
                int from = Math.max(ground + 1, roofAbove(footprints, x, z));
                for (int y = from; y <= ground + TREE_CLEAR_HEIGHT; y++) {
                    BlockState st = level.getBlockState(p.set(x, y, z));
                    if (isNaturalLeaf(st)) {
                        leafSeeds.add(p.immutable());
                    } else if (isTreePart(footprints, p, st)) {
                        trunkSeeds.add(p.immutable());
                    }
                }
            }
        }
        // Leaves hanging into the hamlet lead back to the trunk they belong to.
        Set<BlockPos> seenLeaf = new HashSet<>(leafSeeds);
        java.util.ArrayDeque<int[]> leafWalk = new java.util.ArrayDeque<>();
        for (BlockPos l : leafSeeds) {
            leafWalk.add(new int[]{l.getX(), l.getY(), l.getZ(), 0});
        }
        while (!leafWalk.isEmpty()) {
            int[] c = leafWalk.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = new BlockPos(c[0] + d.getStepX(), c[1] + d.getStepY(), c[2] + d.getStepZ());
                BlockState st = level.getBlockState(n);
                if (isNaturalLeaf(st)) {
                    if (c[3] < 6 && seenLeaf.add(n)) {
                        leafWalk.add(new int[]{n.getX(), n.getY(), n.getZ(), c[3] + 1});
                    }
                } else if (isTreePart(footprints, n, st)) {
                    trunkSeeds.add(n);
                }
            }
        }

        // Fell each tree: flood trunk-to-trunk from the seeds.
        Set<BlockPos> felled = new HashSet<>();
        Set<BlockPos> tooBig = new HashSet<>();
        for (BlockPos seed : trunkSeeds) {
            if (felled.contains(seed) || tooBig.contains(seed)) {
                continue;
            }
            Set<BlockPos> tree = new HashSet<>();
            java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
            q.add(seed);
            tree.add(seed);
            while (!q.isEmpty() && tree.size() < TREE_MAX_LOGS) {
                BlockPos c = q.poll();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            BlockPos n = c.offset(dx, dy, dz);
                            if (!tree.contains(n) && !felled.contains(n)
                                    && isTreePart(footprints, n, level.getBlockState(n))) {
                                tree.add(n);
                                q.add(n);
                            }
                        }
                    }
                }
            }
            if (tree.size() >= TREE_MAX_LOGS) {
                // A giant (or trees grown together into one mass). Cutting it partway is what
                // left soil and canopy hanging in the air; leave the whole thing standing.
                tooBig.addAll(tree);
                continue;
            }
            felled.addAll(tree);
        }
        if (felled.isEmpty() && leafSeeds.isEmpty()) {
            return;
        }
        int ax = Integer.MAX_VALUE, ay = Integer.MAX_VALUE, az = Integer.MAX_VALUE;
        int bx = Integer.MIN_VALUE, by = Integer.MIN_VALUE, bz = Integer.MIN_VALUE;
        Set<BlockPos> removed = new HashSet<>();
        for (BlockPos b : felled) {
            // Underground (roots, a trunk's base), backfill with dirt so no hole is left; if that
            // dirt turns out to be hanging in the air, the floating-soil sweep below takes it.
            boolean underground = b.getY() <= groundTop(b.getX(), b.getZ());
            set(b, underground ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState());
            removed.add(b);
            ax = Math.min(ax, b.getX()); ay = Math.min(ay, b.getY()); az = Math.min(az, b.getZ());
            bx = Math.max(bx, b.getX()); by = Math.max(by, b.getY()); bz = Math.max(bz, b.getZ());
        }
        for (BlockPos b : leafSeeds) {
            ax = Math.min(ax, b.getX()); ay = Math.min(ay, b.getY()); az = Math.min(az, b.getZ());
            bx = Math.max(bx, b.getX()); by = Math.max(by, b.getY()); bz = Math.max(bz, b.getZ());
        }

        // Instant leaf decay, vanilla's rule: keep leaves within six leaf-steps of a remaining log.
        int m = 7;
        Set<BlockPos> kept = new HashSet<>();
        java.util.ArrayDeque<int[]> decay = new java.util.ArrayDeque<>();
        for (BlockPos q : BlockPos.betweenClosed(ax - m, ay - m, az - m, bx + m, by + m, bz + m)) {
            // A building's own log beams and a farm's log frame don't hold a tree's leaves up:
            // counting them kept felled canopies hanging next to houses.
            if (level.getBlockState(q).is(BlockTags.LOGS) && !isStructure(footprints, q)) {
                decay.add(new int[]{q.getX(), q.getY(), q.getZ(), 0});
            }
        }
        while (!decay.isEmpty()) {
            int[] c = decay.poll();
            if (c[3] >= 6) {
                continue;
            }
            for (Direction d : Direction.values()) {
                BlockPos n = new BlockPos(c[0] + d.getStepX(), c[1] + d.getStepY(), c[2] + d.getStepZ());
                if (!kept.contains(n) && isNaturalLeaf(level.getBlockState(n))) {
                    kept.add(n);
                    decay.add(new int[]{n.getX(), n.getY(), n.getZ(), c[3] + 1});
                }
            }
        }
        for (BlockPos q : BlockPos.betweenClosed(ax - m, ay - m, az - m, bx + m, by + m, bz + m)) {
            if (isNaturalLeaf(level.getBlockState(q)) && !kept.contains(q)) {
                BlockPos imm = q.immutable();
                set(imm, Blocks.AIR.defaultBlockState());
                removed.add(imm);
            }
        }

        // Whatever hung off the tree and can't stay without it: vines (whole chains), shelf
        // fungi, fruit, carpets. Repeated so a chain unravels from the top down.
        Set<BlockPos> frontier = new HashSet<>(removed);
        for (int round = 0; round < 24 && !frontier.isEmpty(); round++) {
            Set<BlockPos> next = new HashSet<>();
            for (BlockPos r : frontier) {
                for (Direction d : Direction.values()) {
                    BlockPos n = r.relative(d);
                    BlockState st = level.getBlockState(n);
                    if (!st.isAir() && st.getFluidState().isEmpty() && !isTerrain(st)
                            && !isStructure(footprints, n) && !st.canSurvive(level, n)) {
                        set(n, Blocks.AIR.defaultBlockState());
                        next.add(n);
                    }
                }
            }
            frontier = next;
        }

        // Soil left hanging in the air where a trunk stood on it: a small clump of terrain not
        // connected to the ground.
        Set<BlockPos> checked = new HashSet<>();
        for (BlockPos r : felled) {
            for (int i = -1; i < 6; i++) { // the spot itself (it may have been backfilled), then its sides
                BlockPos n = i < 0 ? r : r.relative(Direction.values()[i]);
                if (checked.contains(n) || !isTerrain(level.getBlockState(n)) || isStructure(footprints, n)) {
                    continue;
                }
                Set<BlockPos> clump = floatingClump(n, 64);
                checked.addAll(clump);
                if (clump.size() < 64) {
                    for (BlockPos c : clump) {
                        set(c, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    /** Dev harness: run the tree clear around {@code footprints} (see JobAudit#treeFelling). */
    static void fellTreesForAudit(ServerLevel level, List<int[]> footprints) {
        HamletPiece h = new HamletPiece(level);
        h.placedFootprints = footprints;
        h.clearTreesAround(footprints);
    }

    /**
     * The terrain blocks connected to {@code start}, stopping once {@code limit} are found (the
     * real ground is far bigger than that, so reaching the limit means "attached").
     */
    private Set<BlockPos> floatingClump(BlockPos start, int limit) {
        Set<BlockPos> clump = new HashSet<>();
        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
        clump.add(start);
        q.add(start);
        while (!q.isEmpty() && clump.size() < limit) {
            BlockPos c = q.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (!clump.contains(n) && isTerrain(level.getBlockState(n))) {
                    clump.add(n);
                    q.add(n);
                }
            }
        }
        return clump;
    }

    /**
     * Part of a tree for felling: a log, or any other solid natural growth (modded stems, petal
     * blocks, caps, fungi, fruit) — outside every placed piece, and not something that looks
     * built. Roots count too; below the ground they're backfilled rather than left as holes.
     */
    private boolean isTreePart(List<int[]> footprints, BlockPos pos, BlockState st) {
        if (st.isAir() || !st.getFluidState().isEmpty() || isTerrain(st) || st.is(BlockTags.LEAVES)
                || isStructure(footprints, pos)) {
            return false;
        }
        if (st.is(BlockTags.LOGS)) {
            return true;
        }
        if (st.canBeReplaced() || st.hasBlockEntity() || looksBuilt(st)
                || st.getCollisionShape(level, pos).isEmpty()) {
            return false; // grass and flowers, containers, construction, anything you walk through
        }
        return true;
    }

    /** Blocks a player or a structure would have placed, never felled as "tree". */
    private static boolean looksBuilt(BlockState st) {
        return st.is(BlockTags.PLANKS) || st.is(BlockTags.STAIRS) || st.is(BlockTags.SLABS)
                || st.is(BlockTags.WALLS) || st.is(BlockTags.FENCES) || st.is(BlockTags.FENCE_GATES)
                || st.is(BlockTags.DOORS) || st.is(BlockTags.TRAPDOORS) || st.is(BlockTags.BEDS)
                || st.is(BlockTags.WOOL) || st.is(BlockTags.STONE_BRICKS) || st.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_BLOCKS)
                || st.is(net.neoforged.neoforge.common.Tags.Blocks.COBBLESTONES) || st.is(Blocks.BRICKS)
                || st.is(Blocks.HAY_BLOCK) || st.is(BlockTags.RAILS);
    }

    private static boolean isNaturalLeaf(BlockState st) {
        return st.is(BlockTags.LEAVES)
                && !(st.hasProperty(LeavesBlock.PERSISTENT) && st.getValue(LeavesBlock.PERSISTENT));
    }

    /** @return true if {@code pos} is part of a placed piece (inside a footprint, below its roof). */
    private static boolean isStructure(List<int[]> footprints, BlockPos pos) {
        for (int[] f : footprints) {
            if (pos.getX() >= f[0] && pos.getX() <= f[2] && pos.getZ() >= f[1] && pos.getZ() <= f[3]
                    && pos.getY() < f[4]) {
                return true;
            }
        }
        return false;
    }

    /** The top of any footprint over this column (Integer.MIN_VALUE if none). */
    private static int roofAbove(List<int[]> footprints, int x, int z) {
        int top = Integer.MIN_VALUE;
        for (int[] f : footprints) {
            if (x >= f[0] && x <= f[2] && z >= f[1] && z <= f[3]) {
                top = Math.max(top, f[4]);
            }
        }
        return top;
    }

    /** {minX, minZ, maxX, maxZ} of all footprints, grown by {@code margin}. */
    private static int[] perimeter(List<int[]> footprints, int margin) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] f : footprints) {
            minX = Math.min(minX, f[0]);
            minZ = Math.min(minZ, f[1]);
            maxX = Math.max(maxX, f[2]);
            maxZ = Math.max(maxZ, f[3]);
        }
        return new int[]{minX - margin, minZ - margin, maxX + margin, maxZ + margin};
    }

    /**
     * Put up the hamlet's lamps: path-side spots only, the first nearest the well, each next the
     * spot farthest from every lamp so far; stop at {@link #LAMP_MAX} or when no spot is at least
     * {@link #LAMP_SPACING} from the rest.
     *
     * @return figures for the dev audit (lamp count; with the audit on, light coverage and maps).
     */
    private Map<String, Object> placeLamps(List<int[]> footprints) {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        Optional<StructureTemplate> lampTemplate = templates.get(ResourceLocation.withDefaultNamespace(POOL + LAMP));
        if (lampTemplate.isEmpty() || footprints.isEmpty() || centerFootprint == null) {
            return stats;
        }
        Profile lampProfile = profile(LAMP, lampTemplate.get());
        Vec3i lampSize = lampTemplate.get().getSize();
        if (HamletAudit.ENABLED) {
            // Measured before the lamps go in: a lamp's torches may hang over a lane, by design.
            int underPieces = 0;
            for (long key : pathColumns) {
                if (insideFootprintExcept(footprints, centerFootprint, (int) (key >> 32), (int) key)) {
                    underPieces++;
                }
            }
            stats.put("pathColumnsUnderPieces", underPieces); // must be 0: lanes route around pieces
        }

        // Candidates: beside a walkway, not on it.
        Set<Long> seen = new HashSet<>();
        List<int[]> candidates = new ArrayList<>();
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (long key : pathColumns) {
            int px = (int) (key >> 32), pz = (int) key;
            for (int[] d : sides) {
                int cx = px + d[0], cz = pz + d[1];
                if (seen.add(columnKey(cx, cz)) && lampSpotOk(footprints, lampSize, cx, cz)) {
                    candidates.add(new int[]{cx, cz});
                }
            }
        }
        int wellX = (centerFootprint[0] + centerFootprint[2]) / 2;
        int wellZ = (centerFootprint[1] + centerFootprint[3]) / 2;

        List<int[]> lamps = new ArrayList<>();
        Set<Long> lampColumns = new HashSet<>();
        while (lamps.size() < LAMP_MAX && !candidates.isEmpty()) {
            int bestI = -1;
            long bestScore = Long.MIN_VALUE;
            for (int i = 0; i < candidates.size(); i++) {
                int[] c = candidates.get(i);
                long score;
                if (lamps.isEmpty()) {
                    score = -((long) (c[0] - wellX) * (c[0] - wellX) + (long) (c[1] - wellZ) * (c[1] - wellZ));
                } else {
                    score = Long.MAX_VALUE;
                    for (int[] l : lamps) {
                        score = Math.min(score, (long) Math.max(Math.abs(c[0] - l[0]), Math.abs(c[1] - l[1])));
                    }
                    if (score < LAMP_SPACING) {
                        continue;
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestI = i;
                }
            }
            if (bestI < 0) {
                break;
            }
            int[] c = candidates.remove(bestI);
            if (!lampSpotOk(footprints, lampSize, c[0], c[1])) {
                continue; // a lamp placed since made this spot too close to something
            }
            Placement lamp = placeTemplateAt(lampTemplate.get(), lampProfile, c[0], c[1],
                    Blocks.COBBLESTONE.defaultBlockState(), Rotation.NONE);
            if (lamp == null) {
                continue;
            }
            footprints.add(new int[]{lamp.minX(), lamp.minZ(), lamp.minX() + lamp.sizeX() - 1,
                    lamp.minZ() + lamp.sizeZ() - 1, lamp.baseY() + lamp.sizeY()});
            lamps.add(c);
            lampColumns.add(columnKey(c[0], c[1]));
        }
        stats.put("lamps", lamps.size());
        if (HamletAudit.ENABLED) {
            auditLighting(footprints, lampColumns, stats);
        }
        return stats;
    }

    /**
     * Dev audit only: how much of the hamlet the lamps (and every other light) actually reach,
     * from a {@link LightField} over the real blocks, plus top-down maps.
     */
    private void auditLighting(List<int[]> footprints, Set<Long> lampColumns, Map<String, Object> stats) {
        int[] area = perimeter(footprints, LIGHT_MARGIN);
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int x = area[0]; x <= area[2]; x++) {
            for (int z = area[1]; z <= area[3]; z++) {
                int g = groundTop(x, z);
                lowest = Math.min(lowest, g);
                highest = Math.max(highest, g);
            }
        }
        LightField field = new LightField(area[0] - 15, lowest - 4, area[1] - 15,
                area[2] + 15, highest + 16, area[3] + 15);
        field.compute(level);
        List<int[]> targets = new ArrayList<>(); // {x, feetY, z, required, isPath}
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = area[0]; x <= area[2]; x++) {
            for (int z = area[1]; z <= area[3]; z++) {
                if (insideFootprint(footprints, x, z)) {
                    continue;
                }
                int g = groundTop(x, z);
                if (g < level.getSeaLevel() || !level.getBlockState(p.set(x, g + 1, z)).getCollisionShape(level, p).isEmpty()
                        || !level.getFluidState(p).isEmpty()) {
                    continue;
                }
                targets.add(new int[]{x, g + 1, z, LIGHT_FLOOR, pathColumns.contains(columnKey(x, z)) ? 1 : 0});
            }
        }
        int lit = 0, paths = 0, pathsLit = 0;
        for (int[] t : targets) {
            int l = field.get(t[0], t[1], t[2]);
            if (l >= LIGHT_FLOOR) {
                lit++;
            }
            if (t[4] == 1) {
                paths++;
                if (l >= LIGHT_FLOOR) {
                    pathsLit++;
                }
            }
        }
        stats.put("groundSpots", targets.size());
        stats.put("groundLitPct", targets.isEmpty() ? 100.0 : Math.round(1000.0 * lit / targets.size()) / 10.0);
        stats.put("pathLitPct", paths == 0 ? 100.0 : Math.round(1000.0 * pathsLit / paths) / 10.0);
        stats.put("map", lightMap(area, targets, field, footprints, lampColumns));
        stats.put("topMap", topMap(area, stats, lampColumns));
    }

    /**
     * Top-down picture of the hamlet's lighting for the dev audit, one string per row (north
     * first): {@code L} lamp, {@code #} building, {@code =} lit walkway, {@code !} dark walkway,
     * {@code .} lit ground, {@code X} dark ground, blank for water.
     */
    private static List<String> lightMap(int[] area, List<int[]> targets, LightField field,
                                         List<int[]> footprints, Set<Long> lampColumns) {
        int w = area[2] - area[0] + 1;
        int h = area[3] - area[1] + 1;
        char[][] grid = new char[h][w];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int wx = area[0] + x, wz = area[1] + z;
                grid[z][x] = lampColumns.contains(columnKey(wx, wz)) ? 'L'
                        : insideFootprint(footprints, wx, wz) ? '#' : ' ';
            }
        }
        for (int[] t : targets) {
            int x = t[0] - area[0], z = t[2] - area[1];
            if (grid[z][x] != ' ') {
                continue;
            }
            int l = field.get(t[0], t[1], t[2]);
            grid[z][x] = t[4] == 1 ? (l >= LIGHT_FLOOR ? '=' : '!') : (l >= LIGHT_FLOOR ? '.' : 'X');
        }
        List<String> rows = new ArrayList<>();
        for (char[] row : grid) {
            rows.add(new String(row));
        }
        return rows;
    }

    /**
     * Top-down picture of the hamlet's actual surface for the dev audit, north row first:
     * {@code P} lamp post, {@code =} dirt path, {@code ~} water, {@code L} lantern/torch, {@code T} log,
     * {@code *} leaves, {@code .} grass/dirt/sand, {@code #} anything else (buildings).
     */
    private List<String> topMap(int[] area, Map<String, Object> stats, Set<Long> lampColumns) {
        List<String> rows = new ArrayList<>();
        Map<String, Integer> other = new java.util.TreeMap<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int z = area[1]; z <= area[3]; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = area[0]; x <= area[2]; x++) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                BlockState st = level.getBlockState(p.set(x, y, z));
                char c;
                if (lampColumns.contains(columnKey(x, z))) {
                    c = 'P';
                } else if (st.is(Blocks.DIRT_PATH)) {
                    c = '=';
                } else if (!st.getFluidState().isEmpty()) {
                    c = '~';
                } else if (st.getLightEmission(level, p) > 0) {
                    c = 'L';
                } else if (st.is(BlockTags.LOGS)) {
                    c = 'T';
                } else if (st.is(BlockTags.LEAVES)) {
                    c = '*';
                } else if (isTerrain(st)) {
                    c = '.';
                } else {
                    c = '#';
                    other.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString(),
                            1, Integer::sum);
                }
                row.append(c);
            }
            rows.add(row.toString());
        }
        stats.put("topMapOtherBlocks", other);
        return rows;
    }

    /**
     * A lamp's post may stand here: dry, solid ground, off the paths, and its whole template
     * plus a one-block gap clear of every piece — so nothing it clears or stamps can touch one.
     */
    private boolean lampSpotOk(List<int[]> footprints, Vec3i lampSize, int cx, int cz) {
        if (pathColumns.contains(columnKey(cx, cz))) {
            return false;
        }
        int rx = lampSize.getX() / 2 + 1, rz = lampSize.getZ() / 2 + 1;
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dz = -rz; dz <= rz; dz++) {
                if (insideFootprint(footprints, cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        int g = groundTop(cx, cz);
        return g >= level.getSeaLevel() && isTerrain(level.getBlockState(new BlockPos(cx, g, cz)));
    }

    private static boolean insideFootprintExcept(List<int[]> footprints, @Nullable int[] except, int x, int z) {
        for (int[] f : footprints) {
            if (f != except && x >= f[0] && x <= f[2] && z >= f[1] && z <= f[3]) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideFootprint(List<int[]> footprints, int x, int z) {
        for (int[] f : footprints) {
            if (x >= f[0] && x <= f[2] && z >= f[1] && z <= f[3]) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsAny(List<int[]> rects, int cx, int cz, int halfX, int halfZ) {
        for (int[] r : rects) {
            if (Math.abs(cx - r[0]) <= halfX + r[2] && Math.abs(cz - r[1]) <= halfZ + r[3]) {
                return true;
            }
        }
        return false;
    }

    // --- terrain -------------------------------------------------------------

    /**
     * @return the nearest column to (cx,cz) whose real ground is at or above sea level,
     *         searching rings outward to {@code maxR}; falls back to (cx,cz) if none found.
     */
    private BlockPos nearestDryColumn(int cx, int cz, int maxR) {
        int sea = level.getSeaLevel();
        if (groundTop(cx, cz) >= sea) {
            return new BlockPos(cx, 0, cz);
        }
        for (int r = 1; r <= maxR; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // ring only
                    }
                    if (groundTop(cx + dx, cz + dz) >= sea) {
                        return new BlockPos(cx + dx, 0, cz + dz);
                    }
                }
            }
        }
        return new BlockPos(cx, 0, cz);
    }

    /**
     * Level a small 5×5 patch at the hamlet centre so the player has a clean, lit, on-land
     * place to spawn (with a small well as a landmark). Returns the standing position. This is
     * the one deliberate exception to "never reshape terrain" — it is the spawn safety net.
     */
    private BlockPos centerPatch(int cx, int cz) {
        int median = footprintBaseY(cx - 2, cz - 2, 5, 5);
        int sea = level.getSeaLevel();
        // Safety net: never let the spawn sit underwater — lift the patch to sea level if the
        // centre is still below it, and fill the crag beneath.
        int floorY = Math.max(median, sea);
        BlockState cap = median >= sea
                ? surfaceCap(cx, groundTop(cx, cz), cz)
                : Blocks.GRASS_BLOCK.defaultBlockState();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = floorY - 1; y >= floorY - 8; y--) {
                    set(new BlockPos(cx + dx, y, cz + dz), Blocks.DIRT.defaultBlockState());
                }
                set(new BlockPos(cx + dx, floorY, cz + dz), cap);
                for (int y = floorY + 1; y <= floorY + 5; y++) {
                    set(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        // A tiny stone-brick well rim with a torch beside it — cozy landmark at spawn.
        set(new BlockPos(cx + 2, floorY, cz + 2), Blocks.STONE_BRICKS.defaultBlockState());
        set(new BlockPos(cx + 2, floorY + 1, cz + 2), Blocks.TORCH.defaultBlockState());
        return new BlockPos(cx, floorY + 1, cz);
    }

    /**
     * @return the <em>median</em> ground height across a footprint (corners + centre) — used
     *         only by the spawn {@link #centerPatch}.
     */
    private int footprintBaseY(int minX, int minZ, int sx, int sz) {
        int[] h = {
                groundTop(minX, minZ),
                groundTop(minX + sx - 1, minZ),
                groundTop(minX, minZ + sz - 1),
                groundTop(minX + sx - 1, minZ + sz - 1),
                groundTop(minX + sx / 2, minZ + sz / 2),
        };
        Arrays.sort(h);
        return h[h.length / 2];
    }

    /**
     * Count obstruction blocks — anything solid that is not {@linkplain #isTerrain terrain}
     * (tree trunks, canopies, giant modded mushrooms/flora, hay, …) — standing in a would-be
     * footprint. The scan is anchored <em>per column</em> to that column's own ground rather
     * than to a single centre sample, so on a slope the box no longer drifts above the terrain
     * at one end and below it at the other.
     */
    private int countObstructions(int minX, int minZ, int sizeX, int sizeZ, int height) {
        int count = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                int base = groundTop(minX + dx, minZ + dz);
                for (int y = base + 1; y <= base + height; y++) {
                    BlockState s = level.getBlockState(p.set(minX + dx, y, minZ + dz));
                    if (!s.isAir() && !s.canBeReplaced() && !isTerrain(s) && s.getFluidState().isEmpty()
                            && !(CLEAR_HAMLET_TREES && (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)))) {
                        count++; // trees don't count while the hamlet fells its trees anyway: a
                                 // wooded coast otherwise failed every house plot (0 houses)
                    }
                }
            }
        }
        return count;
    }

    /**
     * Remove standing vegetation (trunks, canopy, modded flora) from a piece's own volume
     * before it is stamped, so a house is never left with a tree growing through its roof.
     * Vanilla villages overwrite what they land on; this is the same trade, bounded to the
     * reserved plot and run <em>before</em> placement so it can never eat the piece itself.
     */
    private void clearVegetation(int minX, int minZ, int sizeX, int sizeZ, int height) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                int base = groundTop(minX + dx, minZ + dz);
                for (int y = base + 1; y <= base + height; y++) {
                    BlockState s = level.getBlockState(p.set(minX + dx, y, minZ + dz));
                    if (!s.isAir() && !isTerrain(s) && s.getFluidState().isEmpty()
                            && !isStructure(placedFootprints, p)) {
                        set(new BlockPos(minX + dx, y, minZ + dz), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    /**
     * What counts as GROUND. This is an <b>allowlist of terrain materials</b> (via vanilla tags,
     * which well-made biome mods add their soil/stone variants to), replacing the old "sturdy
     * block that isn't on my foliage denylist" test. The denylist could never keep up with
     * modded flora — a BWG Enchanted Tangle giant-trunk block passed as "ground" and a house
     * generated on top of the tree. Anything not recognised here (any trunk, canopy, mushroom
     * cap, vanilla or modded) is simply not ground, and the scan digs past it to the soil below.
     */
    /**
     * Grass and dirt from other mods (Oh The Biomes We've Gone's lush grass, …) take a path the
     * way vanilla grass does. The fixed list above missed them, so in the real pack the lanes
     * stopped wherever the ground turned into a modded grass — most of the way to the well.
     * Anything tagged {@code #minecraft:dirt} counts, except the soils a shovel can't path.
     */
    private static boolean isModdedSoil(BlockState s) {
        return s.is(BlockTags.DIRT) && !s.is(Blocks.MUD) && !s.is(Blocks.MUDDY_MANGROVE_ROOTS)
                && !s.is(Blocks.MOSS_BLOCK);
    }

    private static boolean isTerrain(BlockState s) {
        return s.is(BlockTags.DIRT)                    // dirt, grass block, podzol, mycelium, mud, moss, …
                || s.is(BlockTags.SAND)
                || s.is(BlockTags.BASE_STONE_OVERWORLD) // stone, deepslate, granite, diorite, andesite, tuff
                || s.is(BlockTags.TERRACOTTA)
                || s.is(Blocks.GRAVEL)
                || s.is(Blocks.CLAY)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                || s.is(Blocks.SNOW_BLOCK)
                || s.is(Blocks.CALCITE) || s.is(Blocks.BASALT) || s.is(Blocks.SMOOTH_BASALT)
                || s.is(Blocks.PACKED_MUD)
                || s.is(Blocks.DIRT_PATH);              // our own walkways read as ground too
    }

    /**
     * @return the Y of the top <em>terrain</em> block at (x,z). The chunk is force-loaded (so
     *         far-away hamlets read real ground instead of the void), then the scan walks down
     *         from the surface past everything that isn't {@linkplain #isTerrain recognised
     *         terrain} — trees, giant modded trunks, shelf canopies, snow layers, water — to
     *         the actual soil. Safety fallback: a column with no recognisable terrain at all
     *         (exotic modded ground) uses the first sturdy block instead.
     *
     *         <p>Memoised per hamlet: plot scoring, footprint sampling, obstruction counting,
     *         underpinning, paths and mob spawns re-ask for the same few thousand columns tens
     *         of thousands of times, and every miss is a chunk lookup plus a full column walk.</p>
     */
    private int groundTop(int x, int z) {
        long key = (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
        Integer cached = groundCache.get(key);
        if (cached != null) {
            return cached;
        }
        level.getChunk(x >> 4, z >> 4); // force full generation so the heightmap/blocks are real
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(
                x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
        int min = level.getMinBuildHeight();
        int firstSturdy = Integer.MIN_VALUE;
        int result = Integer.MIN_VALUE;
        while (p.getY() > min) {
            BlockState s = level.getBlockState(p);
            if (isTerrain(s)) {
                result = p.getY();
                break;
            }
            if (firstSturdy == Integer.MIN_VALUE && s.isFaceSturdy(level, p, Direction.UP)) {
                firstSturdy = p.getY();
            }
            p.move(Direction.DOWN);
        }
        if (result == Integer.MIN_VALUE) {
            result = firstSturdy != Integer.MIN_VALUE ? firstSturdy : level.getSeaLevel();
        }
        groundCache.put(key, result);
        return result;
    }

    /** Ground height at a column, bypassing the memo — used by the audit to re-measure. */
    int measureGround(int x, int z) {
        groundCache.remove((((long) x) << 32) ^ (z & 0xFFFFFFFFL));
        return groundTop(x, z);
    }

    ServerLevel level() {
        return level;
    }

    /**
     * The actual ground block at (x, groundY, z) — used so the spawn patch matches the biome
     * surface. Never returns a non-terrain block; anything unrecognised falls back to grass.
     */
    private BlockState surfaceCap(int x, int groundY, int z) {
        BlockState s = level.getBlockState(new BlockPos(x, groundY, z));
        return (s.isAir() || !isTerrain(s)) ? Blocks.GRASS_BLOCK.defaultBlockState() : s;
    }

    // --- population ----------------------------------------------------------

    private void spawnProfessor(BlockPos spawn, List<int[]> footprints) {
        EntityType<ProfessorEntity> type = TerraTownsRegistries.PROFESSOR.get();
        // Beside the player's spawn, on whichever side isn't inside the well (or anything else).
        BlockPos spot = spawn;
        for (Direction d : new Direction[]{Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH}) {
            BlockPos c = spawn.relative(d);
            if (!insideFootprint(footprints, c.getX(), c.getZ())) {
                spot = c;
                break;
            }
        }
        ProfessorEntity professor = type.spawn(level, spot, MobSpawnType.STRUCTURE);
        if (professor == null) {
            TerraTowns.LOGGER.warn("Failed to spawn hamlet Professor at {}", spot);
        }
    }

    /**
     * Populate the hamlet on the villager jigsaws vanilla itself uses, so villagers start in
     * their own houses beside the beds they will claim. Falls back to a ring around the well
     * only if a hamlet somehow placed no doored house at all.
     *
     * @return how many villagers were spawned.
     */
    private int spawnVillagers(BlockPos origin, List<BlockPos> villagerSpots) {
        int spawned = 0;
        for (BlockPos spot : villagerSpots) {
            if (spawned >= MAX_VILLAGERS) {
                break;
            }
            if (EntityType.VILLAGER.spawn(level, spot, MobSpawnType.STRUCTURE) != null) {
                spawned++;
            }
        }
        if (spawned == 0) {
            for (int[] s : new int[][]{{8, 0}, {-8, 1}, {1, -8}, {-7, -7}}) {
                if (spawnMob(EntityType.VILLAGER, origin.getX() + s[0], origin.getZ() + s[1])) {
                    spawned++;
                }
            }
        }
        return spawned;
    }

    /** Spawn a few farm animals at each placed animal pen. */
    private void spawnAnimals(List<BlockPos> penSpots) {
        @SuppressWarnings("unchecked")
        EntityType<? extends Mob>[] kinds = new EntityType[]{
                EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN};
        for (BlockPos pen : penSpots) {
            EntityType<? extends Mob> kind = kinds[random.nextInt(kinds.length)];
            int count = 2 + random.nextInt(2); // 2-3 animals
            for (int i = 0; i < count; i++) {
                int dx = random.nextInt(3) - 1;
                int dz = random.nextInt(3) - 1;
                spawnMob(kind, pen.getX() + dx, pen.getZ() + dz);
            }
        }
    }

    private boolean spawnMob(EntityType<? extends Mob> type, int x, int z) {
        int y = groundTop(x, z);
        if (y < level.getSeaLevel()) {
            return false; // don't drop mobs into water
        }
        return type.spawn(level, new BlockPos(x, y + 1, z), MobSpawnType.STRUCTURE) != null;
    }

    private String pick(List<String> options) {
        return options.get(random.nextInt(options.size()));
    }

    private void set(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }
}
