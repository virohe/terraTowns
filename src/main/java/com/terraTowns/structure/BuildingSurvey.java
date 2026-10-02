package com.terraTowns.structure;

import com.terraTowns.settlement.SettlementJob;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a Building Plaque is actually in: the room it hangs in, and which building types that
 * room qualifies for.
 *
 * <p><b>The rule</b> (playtest request, 2026-09-26): a plaque can only name a building type the
 * building has the workstation for. For the job buildings that falls straight out of the job
 * table — a building qualifies as a Smithy if it holds any workstation a Smith can work at,
 * as Farm Plots if it holds any Farmer workstation (composter, barrel or loom), and so on — so
 * one table, {@link SettlementJob}, drives both. The other types each name their own anchor
 * block below. A room with a loom and a smithing table offers exactly Farm Plots and Smithy.</p>
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

    /** Building types that are job buildings, and the job each one houses. */
    private static final Map<BuildingCategory, SettlementJob> JOB_BUILDINGS = new EnumMap<>(BuildingCategory.class);

    static {
        for (SettlementJob job : SettlementJob.values()) {
            JOB_BUILDINGS.putIfAbsent(job.unlockedBy(), job);
        }
    }

    /**
     * Anchor blocks for the building types no job defines. Only these are hand-picked; see the
     * class doc. BATTLE_FACILITY has none yet — it is city-tier and not designed. The Guard Post
     * had the bell until 0.4.12, when the Guard job got its workstation (the target block).
     */
    private static final Map<BuildingCategory, List<ResourceLocation>> ANCHORS = Map.of(
            BuildingCategory.GYM, List.of(ResourceLocation.fromNamespaceAndPath("terra_towns", "gym_leaders_desk")),
            BuildingCategory.POKECENTER, List.of(ResourceLocation.fromNamespaceAndPath("cobblemon", "healing_machine"),
                    ResourceLocation.fromNamespaceAndPath("cobblemon", "pc")),
            BuildingCategory.POKEMART, List.of(ResourceLocation.fromNamespaceAndPath("cobblemon", "display_case"),
                    ResourceLocation.fromNamespaceAndPath("cobblemon", "tm_machine")),
            BuildingCategory.TRAIN_STATION, List.of(ResourceLocation.fromNamespaceAndPath("create", "track_station")));

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
        return !backing(level, room, category).isEmpty();
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
        if (category == BuildingCategory.INN_TAVERN) {
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

    /** The blocks, any one of which lets a room qualify as {@code category}. */
    public static Set<Block> requiredBlocks(ServerLevel level, BuildingCategory category) {
        Set<Block> out = new HashSet<>();
        SettlementJob job = JOB_BUILDINGS.get(category);
        if (job != null) {
            Registry<PoiType> pois = level.registryAccess().registryOrThrow(Registries.POINT_OF_INTEREST_TYPE);
            for (VillagerProfession profession : job.eligibleProfessions()) {
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
