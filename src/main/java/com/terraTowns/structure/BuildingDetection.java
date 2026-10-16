package com.terraTowns.structure;

import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementData.Structure;
import com.terraTowns.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
 * plaques, so until then none of its buildings counted.
 *
 * <p><b>The rule</b> (user, 2026-10-12): a structure holding a workstation is the building that
 * workstation belongs to, by the same table a plaque uses ({@link BuildingSurvey#requiredBlocks}:
 * a smithing table makes a Smithy, a healing machine a Pok&eacute;centre, the gym's desk a Gym;
 * chests worth 20 single chests a Storage Center); one holding only beds is Housing; one holding
 * neither (a lamp post, a well) is nothing. Where several types match, the first in
 * {@link BuildingCategory} order wins. A plaque hung inside a structure takes over: the player
 * named that building, so the structure itself stops counting.</p>
 *
 * <p><b>Farms by what they are</b> (0.5.4): vanilla puts a composter in every crop farm, but
 * none in an animal pen, so a village whose only farms were pens had no Farms (playtest). A
 * generated farm or pen counts as Farms by its template while anything of it stands; a spawn
 * hamlet's while its farmland or fences do. A spawn hamlet's house is only ever Housing ({@link Structure#onlyAs}).</p>
 *
 * <p><b>When it looks</b> (0.5.4): a structure is re-checked when a block inside it changes
 * ({@link #onBlockChanged}, marking it dirty for the next settlement scan), and every structure
 * when the desk opens or a gym is challenged ({@link #refreshAll}), which also catches what no
 * block event reports. Until 0.5.4 a few were re-checked on every scan whether anything had
 * changed or not. Only structures whose chunks are loaded are looked at.</p>
 */
public final class BuildingDetection {

    /** Dirty structures re-checked per settlement scan (every 2 s), at most. */
    private static final int PER_TICK = 16;

    /** Which building types each block can make a structure (bed-based types excluded). */
    private static volatile Map<Block, List<BuildingCategory>> blockTypes;
    /** The profession a block is the workstation of, if any (cached per state). */
    private static final Map<BlockState, Optional<String>> WORKSTATION = new ConcurrentHashMap<>();

    private BuildingDetection() {
    }

    /**
     * A block changed (any placement, break, explosion or piston move that notifies its
     * neighbours): the structure it is in needs another look.
     */
    public static void onBlockChanged(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || server.dimension() != Level.OVERWORLD) {
            return;
        }
        for (SettlementData s : SettlementManager.get(server).all()) {
            if (s.structures().isEmpty() || !s.covers(pos)) {
                continue;
            }
            for (Structure st : s.structures()) {
                if (st.box().isInside(pos)) {
                    st.markDirty();
                }
            }
        }
    }

    /** Re-check the structures marked dirty (and any never classified); @return true if anything changed. */
    public static boolean tick(ServerLevel level, SettlementData settlement) {
        boolean changed = false;
        int done = 0;
        for (Structure st : settlement.structures()) {
            if (done >= PER_TICK) {
                break;
            }
            if (st.dirty() || !st.classified()) {
                changed |= classify(level, settlement, st);
                done++;
            }
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
        int farmland = 0;
        int fences = 0;
        int solid = 0;
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
            solid++;
            if (state.is(Blocks.FARMLAND)) {
                farmland++;
            } else if (state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES)) {
                fences++;
            }
            List<BuildingCategory> cats = types.get(state.getBlock());
            if (cats != null) {
                for (BuildingCategory c : cats) {
                    hits.merge(c, 1, Integer::sum);
                }
            }
            workstation(state).ifPresent(workstations::add);
        }
        boolean farmStands = farmland > 0 || fences > 0 || hits.getOrDefault(BuildingCategory.FARM_PLOTS, 0) > 0;
        BuildingCategory type = null;
        if (st.onlyAs() == BuildingCategory.HOUSING) {
            type = beds >= BuildingSurvey.HOUSING_MIN_BEDS ? BuildingCategory.HOUSING : null;
        } else if (st.onlyAs() == BuildingCategory.FARM_PLOTS) {
            type = farmStands ? BuildingCategory.FARM_PLOTS : null;
        } else {
            for (BuildingCategory c : BuildingCategory.values()) {
                if (hits.getOrDefault(c, 0) >= BuildingSurvey.minimum(c)) {
                    type = c;
                    break;
                }
            }
            // A generated farm or pen is Farms by what it is: BWG's Salem pens are walled with
            // stone slabs and stairs, not fences (real-pack rig, 2026-10-16).
            if (type == null && isFarmTemplate(st.template()) && solid > 0) {
                type = BuildingCategory.FARM_PLOTS;
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

    /** A generated crop farm or animal pen, by its template: "plains_small_farm_1", "plains_animal_pen_2". */
    static boolean isFarmTemplate(String template) {
        String name = template.substring(template.lastIndexOf('/') + 1);
        return name.contains("farm") || name.contains("animal_pen") || name.contains("_pen_") || name.endsWith("_pen");
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
