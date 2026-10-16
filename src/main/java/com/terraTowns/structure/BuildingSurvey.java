package com.terraTowns.structure;

import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.neoforged.neoforge.common.Tags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What a Building Plaque is actually in: the room it hangs in, and which building types that
 * room qualifies for.
 *
 * <p><b>The rule</b> (playtest request, 2026-09-26): a plaque can only name a building type the
 * building has the workstation for: a Smithy any smith's workstation (smithing table, blast
 * furnace, grindstone, fletching table, cauldron), Farms a composter or beehive, and so on
 * ({@link #WORKSTATIONS}, {@link #ANCHORS}; 0.5.4). A room with a loom and a smithing table offers
 * exactly Smithy and Loom Shed. Detection of generated buildings reads the same table.</p>
 *
 * <p><b>The room</b> is found by flooding the open space next to the plaque. A wall sign faces
 * into the room it is mounted in, so the space in front of it is tried first; if that space
 * runs out under the open sky it is not a room, and the space on the far side of the wall is
 * tried instead (a plaque hung on a building's outside wall). Walls, roofs, doors and glass
 * all stop the flood, so it stays inside one building. If neither side is enclosed — a farm
 * plot, a market stall — the same flood runs outdoors within {@value #OPEN_AIR_RADIUS} blocks:
 * it still never passes through a wall, so a plaque on a fence post can't count the smithing
 * table inside the house next to it.</p>
 *
 * <p>A room only counts blocks no other plaque has claimed ({@link Result#without}).</p>
 */
public final class BuildingSurvey {

    /** How far the room flood may reach from its start before it is judged "not a room". */
    private static final int ROOM_REACH_XZ = 16;
    private static final int ROOM_REACH_Y = 10;
    private static final int ROOM_MAX_CELLS = 3000;
    /** Radius of the fallback scan for open-air buildings. */
    private static final int OPEN_AIR_RADIUS = 6;
    /** Beds needed for an Inn / Tavern — enough that an ordinary house doesn't qualify. */
    public static final int INN_MIN_BEDS = 3;
    /** Beds needed for Housing (0.5.2): one. */
    public static final int HOUSING_MIN_BEDS = 1;

    private BuildingSurvey() {
    }

    /**
     * @param enclosed true if the plaque is in (or on the wall of) a closed room
     * @param cells    every block in or bounding that room, by position
     * @param start    the open cell the survey started from — this building's identity
     * @param space    every open cell of the room (or of the outdoor survey)
     */
    public record Result(boolean enclosed, Map<BlockPos, BlockState> cells, BlockPos start, Set<BlockPos> space) {

        /** This room minus the blocks other plaques already rest on. */
        public Result without(Set<BlockPos> claimed) {
            if (claimed.isEmpty()) {
                return this;
            }
            Map<BlockPos, BlockState> left = new java.util.HashMap<>(cells);
            left.keySet().removeAll(claimed);
            return new Result(enclosed, left, start, space);
        }

        /** The box around the room and its walls: the building, on the settlement map. */
        public net.minecraft.world.level.levelgen.structure.BoundingBox box() {
            java.util.List<BlockPos> all = new java.util.ArrayList<>(space);
            all.addAll(cells.keySet());
            return net.minecraft.world.level.levelgen.structure.BoundingBox.encapsulatingPositions(all)
                    .orElse(new net.minecraft.world.level.levelgen.structure.BoundingBox(start));
        }

        /** Bed heads in the room. */
        public List<BlockPos> beds() {
            return cells.entrySet().stream()
                    .filter(e -> e.getValue().is(BlockTags.BEDS) && e.getValue().getValue(BedBlock.PART) == BedPart.HEAD)
                    .map(Map.Entry::getKey)
                    .toList();
        }
    }

    /** Survey the building the plaque at {@code pos}, facing {@code facing}, is part of. */
    public static Result of(ServerLevel level, BlockPos pos, Direction facing) {
        Result inFront = flood(level, pos, pos, true, ROOM_REACH_XZ, ROOM_REACH_Y);
        if (inFront != null) {
            return inFront;
        }
        BlockPos behind = pos.relative(facing.getOpposite(), 2);
        if (level.getBlockState(behind).getCollisionShape(level, behind).isEmpty()) {
            Result beyondWall = flood(level, behind, pos, true, ROOM_REACH_XZ, ROOM_REACH_Y);
            if (beyondWall != null) {
                return beyondWall;
            }
        }
        // Outdoors: same flood, no roof needed, a short reach, and it never gives up — it just
        // stops at the reach limit.
        return flood(level, pos, pos, false, OPEN_AIR_RADIUS, OPEN_AIR_RADIUS);
    }

    /**
     * Flood the open space from {@code start}, collecting it and every block bounding it.
     *
     * @param enclosed true: a room — null if it reaches the sky or outgrows the reach limits.
     *                 false: outdoors — the sky is fine, and the reach limit just bounds the flood.
     */
    @javax.annotation.Nullable
    private static Result flood(ServerLevel level, BlockPos start, BlockPos plaque, boolean enclosed,
                                int reachXZ, int reachY) {
        Set<BlockPos> seen = new HashSet<>();
        Set<BlockPos> boundary = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            BlockPos cell = queue.poll();
            if (enclosed && cell.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cell.getX(), cell.getZ())) {
                return null; // open to the sky: this is outdoors, not a room
            }
            for (Direction d : Direction.values()) {
                BlockPos next = cell.relative(d);
                if (seen.contains(next) || boundary.contains(next)) {
                    continue;
                }
                BlockState state = level.getBlockState(next);
                boolean open = !next.equals(plaque) && state.getCollisionShape(level, next).isEmpty()
                        && state.getFluidState().isEmpty();
                if (!open) {
                    boundary.add(next);
                    continue;
                }
                if (Math.abs(next.getX() - start.getX()) > reachXZ
                        || Math.abs(next.getZ() - start.getZ()) > reachXZ
                        || Math.abs(next.getY() - start.getY()) > reachY
                        || seen.size() >= ROOM_MAX_CELLS) {
                    if (enclosed) {
                        return null; // too big to be one building's room
                    }
                    continue; // outdoors: the edge of the survey
                }
                seen.add(next);
                queue.add(next);
            }
        }
        Map<BlockPos, BlockState> cells = new java.util.HashMap<>();
        for (BlockPos p : boundary) {
            if (!p.equals(plaque)) {
                cells.put(p, level.getBlockState(p));
            }
        }
        return new Result(enclosed, cells, start, seen);
    }

    // --- which building types a room qualifies for --------------------------------------------

    /**
     * Which villagers' workstations make which building (user's table, 2026-10-16; DESIGN.md
     * §2.3). A building is typed by the workstation it holds; how jobs group the trades is a
     * separate matter ({@code SettlementJob}). Supplied, not stored: Terra Towns' own Guard
     * profession doesn't exist yet when this class loads. Before 0.5.4 this followed the job
     * table, so a fisherman's barrel made Farm Plots and a butcher's smoker a Market Stall.
     */
    private static final Map<BuildingCategory, Supplier<List<VillagerProfession>>> WORKSTATIONS = Map.of(
            BuildingCategory.FARM_PLOTS, () -> List.of(VillagerProfession.FARMER),
            BuildingCategory.SMITHY, () -> List.of(VillagerProfession.TOOLSMITH, VillagerProfession.WEAPONSMITH,
                    VillagerProfession.ARMORER, VillagerProfession.FLETCHER, VillagerProfession.LEATHERWORKER),
            BuildingCategory.LIBRARY, () -> List.of(VillagerProfession.LIBRARIAN, VillagerProfession.CARTOGRAPHER),
            BuildingCategory.TEMPLE, () -> List.of(VillagerProfession.CLERIC),
            BuildingCategory.FISHERY, () -> List.of(VillagerProfession.FISHERMAN),
            BuildingCategory.WORKSHOP, () -> List.of(VillagerProfession.MASON),
            BuildingCategory.BUTCHERS_SHOP, () -> List.of(VillagerProfession.BUTCHER),
            BuildingCategory.LOOM_SHED, () -> List.of(VillagerProfession.SHEPHERD),
            BuildingCategory.BARRACKS_GUARD_POST, () -> List.of(TerraTownsRegistries.GUARD_PROFESSION.get()));

    /**
     * Blocks that make a building without being any vanilla profession's workstation. The
     * beehive is the beekeeper's (Friends &amp; Foes) and stands for it whether or not that mod is
     * installed. BATTLE_FACILITY has none yet: it is city-tier and not designed.
     */
    private static final Map<BuildingCategory, List<ResourceLocation>> ANCHORS = Map.of(
            BuildingCategory.GYM, List.of(ResourceLocation.fromNamespaceAndPath("terra_towns", "gym_leaders_desk")),
            BuildingCategory.POKECENTER, List.of(ResourceLocation.fromNamespaceAndPath("cobblemon", "healing_machine"),
                    ResourceLocation.fromNamespaceAndPath("cobblemon", "pc")),
            BuildingCategory.POKEMART, List.of(ResourceLocation.fromNamespaceAndPath("cobblemon", "display_case"),
                    ResourceLocation.fromNamespaceAndPath("cobblemon", "tm_machine")),
            BuildingCategory.MARKET_STALL, List.of(ResourceLocation.fromNamespaceAndPath("terra_towns", "market_counter")),
            BuildingCategory.FARM_PLOTS, List.of(ResourceLocation.withDefaultNamespace("beehive")),
            BuildingCategory.TRAIN_STATION, List.of(ResourceLocation.fromNamespaceAndPath("create", "track_station")));

    /**
     * A Storage Center holds chests worth this many single chests (user, 2026-10-16): each chest
     * block counts one, so a double chest counts two and ten of them are enough.
     */
    public static final int STORAGE_MIN_CHESTS = 20;

    /** How many of its blocks a building type needs: 20 chests, else one. */
    public static int minimum(BuildingCategory category) {
        return category == BuildingCategory.STORAGE_CENTER ? STORAGE_MIN_CHESTS : 1;
    }

    /** @return every building type {@code room} qualifies for, in enum order. */
    public static List<BuildingCategory> qualifying(ServerLevel level, Result room) {
        return java.util.Arrays.stream(BuildingCategory.values())
                .filter(c -> qualifies(level, room, c))
                .toList();
    }

    public static boolean qualifies(ServerLevel level, Result room, BuildingCategory category) {
        if (category == BuildingCategory.INN_TAVERN) {
            return room.beds().size() >= INN_MIN_BEDS;
        }
        if (category == BuildingCategory.HOUSING) {
            return room.beds().size() >= HOUSING_MIN_BEDS;
        }
        Set<BlockPos> blocks = backing(level, room, category);
        return !blocks.isEmpty() && blocks.size() >= minimum(category);
    }

    /** Is {@code category} counted in beds rather than in blocks of its own? */
    public static boolean bedBased(BuildingCategory category) {
        return category == BuildingCategory.INN_TAVERN || category == BuildingCategory.HOUSING;
    }

    /**
     * Everything in {@code room} that could qualify it as any type: what its plaque claims.
     * The building owns all its workstations whichever type it's named as, so one set into the
     * wall it shares with the next room can't register that room too.
     */
    public static Set<BlockPos> claimable(ServerLevel level, Result room) {
        Set<BlockPos> out = new HashSet<>();
        for (BuildingCategory c : BuildingCategory.values()) {
            out.addAll(backing(level, room, c));
        }
        return out;
    }

    /**
     * The blocks in {@code room} that qualify it as {@code category} — what a plaque naming it
     * claims. All of them, not just one: a second smithing table in the same Smithy can't go on
     * to back a second plaque.
     */
    public static Set<BlockPos> backing(ServerLevel level, Result room, BuildingCategory category) {
        Set<BlockPos> out = new HashSet<>();
        if (bedBased(category)) {
            out.addAll(room.beds());
            return out;
        }
        Set<Block> wanted = requiredBlocks(level, category);
        room.cells().forEach((p, s) -> {
            if (wanted.contains(s.getBlock())) {
                out.add(p);
            }
        });
        return out;
    }

    /** The blocks, any one of which counts toward {@code category}. */
    public static Set<Block> requiredBlocks(ServerLevel level, BuildingCategory category) {
        Set<Block> out = new HashSet<>();
        if (category == BuildingCategory.STORAGE_CENTER) {
            // Chests and trapped chests, and other mods' wooden chests; not ender chests (they
            // store nothing for the town) and not barrels (a fisherman's workstation).
            for (Block b : BuiltInRegistries.BLOCK) {
                if (b instanceof ChestBlock || b.defaultBlockState().is(Tags.Blocks.CHESTS_WOODEN)) {
                    out.add(b);
                }
            }
            return out;
        }
        List<VillagerProfession> professions = WORKSTATIONS.getOrDefault(category, List::of).get();
        if (!professions.isEmpty()) {
            Registry<PoiType> pois = level.registryAccess().registryOrThrow(Registries.POINT_OF_INTEREST_TYPE);
            for (VillagerProfession profession : professions) {
                pois.holders()
                        .filter(h -> profession.heldJobSite().test(h))
                        .forEach(h -> h.value().matchingStates().forEach(s -> out.add(s.getBlock())));
            }
        }
        for (ResourceLocation id : ANCHORS.getOrDefault(category, List.of())) {
            BuiltInRegistries.BLOCK.getOptional(id).ifPresent(out::add);
        }
        return out;
    }
}
