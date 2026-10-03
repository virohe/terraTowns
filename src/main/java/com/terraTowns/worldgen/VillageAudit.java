package com.terraTowns.worldgen;

import com.terraTowns.TerraTowns;
import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementManager;
import com.terraTowns.settlement.VillageRegistry;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dev-only check of generated villages (0.5), for the real-pack rig: the gym is injected through
 * Lithostitched and Cobblemon adds the Pok&eacute;centres, so only the real pack can show them.
 * Inert unless {@code terratowns.villageAudit=<count>}.
 *
 * <p>Finds the nearest village in each of several directions from the origin, generates its
 * whole area, and reports per village: how many Terra Towns gyms and Pok&eacute;centres it has
 * (one gym exactly, at most one Pok&eacute;centre), whether it was registered as a settlement,
 * whether its gym's desk is there, and whether the gym's villager is standing by it.</p>
 */
final class VillageAudit {

    private static final String PROPERTY = "terratowns.villageAudit";
    /** Chunks generated around a village's start: villages reach ~80 blocks from their centre. */
    private static final int AREA_CHUNKS = 6;
    /** In placement-grid cells, not chunks: with Terra Continental's village spacing a cell is ~2 km. */
    private static final int SEARCH_CELLS = 3;

    private VillageAudit() {
    }

    static boolean enabled() {
        return Integer.getInteger(PROPERTY, 0) > 0;
    }

    static Map<String, Object> run(ServerLevel level) {
        int wanted = Integer.getInteger(PROPERTY, 0);
        var villages = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getTag(StructureTags.VILLAGE).orElseThrow();
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int i = 0; i < dirs.length && rows.size() < wanted; i++) {
            BlockPos from = new BlockPos(dirs[i][0] * 1500, 64, dirs[i][1] * 1500);
            Pair<BlockPos, Holder<Structure>> hit = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, villages, from, SEARCH_CELLS, false);
            if (hit == null || !seen.add(ChunkPos.asLong(hit.getFirst().getX() >> 4, hit.getFirst().getZ() >> 4))) {
                continue;
            }
            rows.add(inspect(level, hit.getFirst(), hit.getSecond()));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("inspected", rows.size());
        summary.put("wanted", wanted);
        for (String k : new String[]{"exactlyOneGym", "atMostOnePokecenter", "registered", "deskFound", "villagerAtDesk"}) {
            summary.put(k, rows.stream().filter(r -> Boolean.TRUE.equals(r.get(k))).count());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("villages", rows);
        out.put("summary", summary);
        return out;
    }

    private static Map<String, Object> inspect(ServerLevel level, BlockPos at, Holder<Structure> structure) {
        ChunkPos c = new ChunkPos(at);
        for (int dx = -AREA_CHUNKS; dx <= AREA_CHUNKS; dx++) {
            for (int dz = -AREA_CHUNKS; dz <= AREA_CHUNKS; dz++) {
                level.getChunk(c.x + dx, c.z + dz); // generate it all (fires the chunk loads too)
            }
        }
        StructureStart start = level.getChunk(c.x, c.z).getAllStarts().get(structure.value());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("structure", structure.unwrapKey().map(k -> k.location().toString()).orElse("?"));
        row.put("at", at.getX() + "," + at.getZ());
        if (start == null || !start.isValid()) {
            row.put("error", "no start in its chunk");
            return row;
        }
        int gyms = VillageRegistry.countGyms(start);
        int pokecenters = 0;
        BoundingBox gymBox = null;
        for (StructurePiece piece : start.getPieces()) {
            if (piece instanceof PoolElementStructurePiece p) {
                String element = VillageRegistry.describe(p.getElement());
                if (element.contains("pokecenter")) {
                    pokecenters++;
                }
                if (element.contains(VillageRegistry.GYM_TEMPLATE)) {
                    gymBox = p.getBoundingBox();
                }
            }
        }
        row.put("pieces", start.getPieces().size());
        // What the pieces' elements read as (detection matches on these), and what blocks are
        // really in the village: the audit's own view, independent of the detection.
        row.put("elementSample", start.getPieces().stream().filter(p -> p instanceof PoolElementStructurePiece)
                .map(p -> { String e = ((PoolElementStructurePiece) p).getElement().toString(); return e.length() > 110 ? e.substring(0, 110) : e; })
                .distinct().limit(6).toList());
        Map<String, Integer> seenBlocks = new LinkedHashMap<>();
        BoundingBox vb = start.getBoundingBox();
        for (BlockPos p : BlockPos.betweenClosed(vb.minX(), vb.minY(), vb.minZ(), vb.maxX(), vb.maxY(), vb.maxZ())) {
            var st = level.getBlockState(p);
            String id = st.is(TerraTownsRegistries.GYM_LEADERS_DESK.get()) ? "desk"
                    : st.is(net.minecraft.world.level.block.Blocks.BELL) ? "bell"
                    : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString().equals("cobblemon:healing_machine") ? "healing_machine" : null;
            if (id != null) {
                seenBlocks.merge(id, 1, Integer::sum);
            }
        }
        row.put("blocksInVillage", seenBlocks);
        row.put("gyms", gyms);
        row.put("pokecenters", pokecenters);
        row.put("exactlyOneGym", gyms == 1);
        row.put("atMostOnePokecenter", pokecenters <= 1);

        VillageRegistry.drain(level);
        BlockPos center = start.getPieces().get(0).getBoundingBox().getCenter();
        SettlementData s = SettlementManager.get(level).containing(center, 48).orElse(null);
        row.put("registered", s != null && s.generatedGym());
        row.put("name", s != null ? s.name() : null);

        BlockPos desk = null;
        if (gymBox != null) {
            for (BlockPos p : BlockPos.betweenClosed(gymBox.minX(), gymBox.minY(), gymBox.minZ(),
                    gymBox.maxX(), gymBox.maxY(), gymBox.maxZ())) {
                if (level.getBlockState(p).is(TerraTownsRegistries.GYM_LEADERS_DESK.get())) {
                    desk = p.immutable();
                    break;
                }
            }
        }
        row.put("deskFound", desk != null);
        boolean villager = desk != null && !level.getEntitiesOfClass(Villager.class,
                new AABB(desk).inflate(4)).isEmpty();
        row.put("villagerAtDesk", villager);
        TerraTowns.LOGGER.info("[village-audit] {}", row);
        return row;
    }
}
