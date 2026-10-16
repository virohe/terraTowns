package com.terraTowns.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.MapColor;

/**
 * A top-down picture of a settlement's ground (0.5.3), for the desk's map tab: one byte per
 * column in vanilla's own map palette ({@link MapColor} packed with a brightness), shaded the
 * way a vanilla map is (a column higher than the one north of it is lit, lower is shaded; water
 * darkens with depth). Built on the server when the desk opens, from loaded chunks only; a
 * column in an unloaded chunk is 0, which the client draws as blank parchment.
 *
 * <p>It covers the settlement's footprint ({@link SettlementData#footprint}), or the
 * registration radius around its centre without one, at one block per pixel up to
 * {@link #MAX_SIDE} pixels a side, coarser beyond.</p>
 */
public final class SettlementMap {

    /**
     * Pixels along the longer side, at most. 512 since 0.5.4, so a big town stays one block per
     * pixel and zooming in on it shows real detail (playtest: big towns were hard to read).
     */
    public static final int MAX_SIDE = 512;

    /**
     * @param originX world x of pixel column 0
     * @param originZ world z of pixel row 0
     * @param scale   blocks per pixel
     * @param colors  {@code width * height} packed map colours, row by row (z), 0 = unknown
     */
    public record Image(int originX, int originZ, int scale, int width, int height, byte[] colors) {
    }

    /**
     * A villager on the settlement's ground, for the map's building details (0.5.4): what it
     * does, and where it works and sleeps ({@link Long#MIN_VALUE} for none; packed BlockPos).
     */
    public record Resident(String name, String profession, long jobSite, long home) {
    }

    private SettlementMap() {
    }

    public static java.util.List<Resident> residents(ServerLevel level, SettlementData settlement) {
        java.util.List<Resident> out = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.npc.Villager v : level.getEntitiesOfClass(
                net.minecraft.world.entity.npc.Villager.class, settlement.area(),
                v -> settlement.covers(v.blockPosition()) && !v.isBaby())) {
            var brain = v.getBrain();
            long job = brain.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                    .map(g -> g.pos().asLong()).orElse(Long.MIN_VALUE);
            long home = brain.getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME)
                    .map(g -> g.pos().asLong()).orElse(Long.MIN_VALUE);
            String name = v.hasCustomName() ? v.getCustomName().getString() : "";
            out.add(new Resident(name, v.getVillagerData().getProfession().name(), job, home));
        }
        return out;
    }

    public static Image build(ServerLevel level, SettlementData settlement) {
        BoundingBox fp = settlement.footprint();
        int minX, minZ, maxX, maxZ;
        if (fp != null) {
            minX = fp.minX();
            minZ = fp.minZ();
            maxX = fp.maxX();
            maxZ = fp.maxZ();
        } else {
            int r = settlement.registrationRadius();
            minX = settlement.center().getX() - r;
            minZ = settlement.center().getZ() - r;
            maxX = settlement.center().getX() + r;
            maxZ = settlement.center().getZ() + r;
        }
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;
        int scale = Math.max(1, (int) Math.ceil(Math.max(spanX, spanZ) / (double) MAX_SIDE));
        int width = (spanX + scale - 1) / scale;
        int height = (spanZ + scale - 1) / scale;
        byte[] colors = new byte[width * height];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] previous = new int[width]; // the surface height one row north, per column
        for (int px = 0; px < width; px++) {
            previous[px] = surface(level, minX + px * scale, minZ - scale);
        }
        for (int pz = 0; pz < height; pz++) {
            int z = minZ + pz * scale;
            for (int px = 0; px < width; px++) {
                int x = minX + px * scale;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    previous[px] = Integer.MIN_VALUE;
                    continue;
                }
                int y = surface(level, x, z);
                pos.set(x, y, z);
                BlockState state = level.getBlockState(pos);
                // Look through what a map doesn't draw (glass, air pockets) to what it does.
                for (int i = 0; i < 8 && state.getMapColor(level, pos) == MapColor.NONE && pos.getY() > level.getMinBuildHeight(); i++) {
                    pos.move(0, -1, 0);
                    state = level.getBlockState(pos);
                }
                MapColor color = state.getMapColor(level, pos);
                MapColor.Brightness brightness;
                if (state.getFluidState().is(FluidTags.WATER)) {
                    int depth = 0;
                    BlockPos.MutableBlockPos down = pos.mutable();
                    while (depth < 16 && level.getBlockState(down).getFluidState().is(FluidTags.WATER)) {
                        down.move(0, -1, 0);
                        depth++;
                    }
                    double d = depth * 0.1 + ((px + pz) & 1) * 0.2;
                    brightness = d < 0.5 ? MapColor.Brightness.HIGH : d > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                } else {
                    int north = previous[px];
                    brightness = north == Integer.MIN_VALUE || y == north ? MapColor.Brightness.NORMAL
                            : y > north ? MapColor.Brightness.HIGH : MapColor.Brightness.LOW;
                }
                previous[px] = y;
                colors[pz * width + px] = color == MapColor.NONE ? 0 : color.getPackedId(brightness);
            }
        }
        return new Image(minX, minZ, scale, width, height, colors);
    }

    /** The top block of a column (not the air above it); MIN_VALUE where it isn't loaded. */
    private static int surface(ServerLevel level, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return Integer.MIN_VALUE;
        }
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
    }
}
