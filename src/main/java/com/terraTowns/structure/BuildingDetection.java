package com.terraTowns.structure;

import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementData.Structure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out what the structures a settlement came with are (0.5.2): a generated village has no
 * plaques, so until now none of its buildings counted.
 *
 * <p><b>The rule</b> (user, 2026-10-12): a structure holding a workstation is the building that
 * workstation belongs to, by the same table a plaque uses ({@link BuildingSurvey#requiredBlocks}:
 * a smithing table makes a Smithy, a healing machine a Pok&eacute;centre, the gym's desk a Gym);
 * one holding only beds is Housing; one holding neither (a lamp post, a well) is nothing. Where
 * several types match, the first in {@link BuildingCategory} order wins. A spawn hamlet's house
 * is only ever Housing. A plaque hung inside a structure takes over: the player named that
 * building, so the structure itself stops counting.</p>
 *
 * <p>Structures are re-checked a few at a time on every settlement scan
 * ({@link #tick}), so breaking a structure's workstation re-types it within about 20 seconds, and
 * all at once ({@link #refreshAll}) right before anything the player sees or relies on: the desk
 * opening, a gym challenge. Only structures whose chunks are loaded are looked at; the rest
 * keep what they were last found to be.</p>
 */
public final class BuildingDetection {

    /** Structures re-checked per settlement scan (every 2 s): a village of ~80 in ~20 s. */
    private static final int PER_TICK = 8;

    /** Which building types each block can make a structure (bed-based types excluded). */
    private static volatile Map<Block, List<BuildingCategory>> blockTypes;
    /** The profession a block is the workstation of, if any (cached per state). */
    private static final Map<BlockState, Optional<String>> WORKSTATION = new ConcurrentHashMap<>();

    private BuildingDetection() {
    }

    /** Re-check the next few structures; @return true if anything changed. */
    public static boolean tick(ServerLevel level, SettlementData settlement) {
        boolean changed = false;
        int n = Math.min(PER_TICK, settlement.structures().size());
        for (int i = 0; i < n; i++) {
            int index = settlement.nextDetectionIndex();
            changed |= classify(level, settlement, settlement.structures().get(index));
        }
        return changed;
    }

    /** Re-check every loaded structure now; @return true if anything changed. */
    public static boolean refreshAll(ServerLevel level, SettlementData settlement) {
        boolean changed = false;
        for (Structure st : settlement.structures()) {
            changed |= classify(level, settlement, st);
        }
        return changed;
    }

    /** Look at what {@code st} holds now; @return true if its record changed. */
    public static boolean classify(ServerLevel level, SettlementData settlement, Structure st) {
        BoundingBox b = st.box();
        if (!level.hasChunksAt(b.minX(), b.minZ(), b.maxX(), b.maxZ())) {
            return false;
        }
        Map<Block, List<BuildingCategory>> types = blockTypes(level);
        Map<BuildingCategory, Integer> hits = new EnumMap<>(BuildingCategory.class);
        List<String> workstations = new ArrayList<>();
        int beds = 0;
        for (BlockPos p : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
            BlockState state = level.getBlockState(p);
            if (state.isAir()) {
                continue;
            }
            if (state.is(BlockTags.BEDS)) {
                if (state.getValue(BedBlock.PART) == BedPart.HEAD) {
                    beds++;
                }
                continue;
            }
            List<BuildingCategory> cats = types.get(state.getBlock());
            if (cats != null) {
                for (BuildingCategory c : cats) {
                    hits.merge(c, 1, Integer::sum);
                }
            }
            workstation(state).ifPresent(workstations::add);
        }
        BuildingCategory type = null;
        if (st.housingOnly()) {
            type = beds >= BuildingSurvey.HOUSING_MIN_BEDS ? BuildingCategory.HOUSING : null;
        } else {
            for (BuildingCategory c : BuildingCategory.values()) {
                if (hits.getOrDefault(c, 0) > 0) {
                    type = c;
                    break;
                }
            }
            if (type == null && beds >= BuildingSurvey.HOUSING_MIN_BEDS) {
                type = BuildingCategory.HOUSING;
            }
        }
        boolean plaqued = false;
        for (Long start : settlement.plaqueStarts()) {
            if (b.isInside(BlockPos.of(start))) {
                plaqued = true;
                break;
            }
        }
        return st.update(type, beds, workstations, plaqued);
    }

    /** The profession whose workstation {@code state} is, e.g. "toolsmith"; not the gym's desk. */
    private static Optional<String> workstation(BlockState state) {
        return WORKSTATION.computeIfAbsent(state, s -> {
            Optional<Holder<PoiType>> poi = PoiTypes.forState(s);
            if (poi.isEmpty()) {
                return Optional.empty();
            }
            for (VillagerProfession p : BuiltInRegistries.VILLAGER_PROFESSION) {
                if (p != TerraTownsRegistries.GYM_LEADER_PROFESSION.get() && p.heldJobSite().test(poi.get())) {
                    return Optional.of(p.name());
                }
            }
            return Optional.empty();
        });
    }

    private static Map<Block, List<BuildingCategory>> blockTypes(ServerLevel level) {
        Map<Block, List<BuildingCategory>> map = blockTypes;
        if (map == null) {
            map = new java.util.HashMap<>();
            for (BuildingCategory c : BuildingCategory.values()) {
                if (BuildingSurvey.bedBased(c)) {
                    continue;
                }
                for (Block block : BuildingSurvey.requiredBlocks(level, c)) {
                    map.computeIfAbsent(block, k -> new ArrayList<>()).add(c);
                }
            }
            blockTypes = map;
        }
        return map;
    }

    /** A structure as a generated piece reads: "minecraft:village/plains/houses/plains_small_house_1". */
    @Nullable
    public static String templateOf(String described) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+").matcher(described);
        return m.find() ? m.group() : null;
    }
}
