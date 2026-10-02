package com.terraTowns.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;

/**
 * Block light over a box of the world, computed the way vanilla's light engine spreads block
 * light: every emitter starts at its emission level, and each step into a neighbour costs
 * {@code max(1, that block's light opacity)}, so walls stop it, leaves and water dim it, and open
 * air costs one level per block.
 *
 * <p>Used to place a hamlet's lamps. Vanilla's own engine can't be asked mid-generation — its
 * updates run asynchronously and haven't happened yet while the hamlet is being built — so the
 * hamlet computes the light its lamps WILL give from the real blocks around them. Houses shade
 * the far side of themselves here exactly as they will in game, which a distance-only estimate
 * gets badly wrong.</p>
 */
final class LightField {

    private final int x0, y0, z0, sx, sy, sz;
    private final byte[] light;
    private final byte[] opacity;

    LightField(int x0, int y0, int z0, int x1, int y1, int z1) {
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.sx = x1 - x0 + 1;
        this.sy = y1 - y0 + 1;
        this.sz = z1 - z0 + 1;
        this.light = new byte[sx * sy * sz];
        this.opacity = new byte[sx * sy * sz];
    }

    private int index(int x, int y, int z) {
        int ix = x - x0, iy = y - y0, iz = z - z0;
        if (ix < 0 || iy < 0 || iz < 0 || ix >= sx || iy >= sy || iz >= sz) {
            return -1;
        }
        return (iy * sz + iz) * sx + ix;
    }

    /** Read every block in the box and spread light from every emitter in it. */
    void compute(ServerLevel level) {
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = y0; y < y0 + sy; y++) {
            for (int z = z0; z < z0 + sz; z++) {
                for (int x = x0; x < x0 + sx; x++) {
                    read(level, p.set(x, y, z), queue);
                }
            }
        }
        spread(queue);
    }

    /** Re-read a sub-box (a lamp was just built there) and spread its new light outward. */
    void update(ServerLevel level, int ax, int ay, int az, int bx, int by, int bz) {
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = ay; y <= by; y++) {
            for (int z = az; z <= bz; z++) {
                for (int x = ax; x <= bx; x++) {
                    if (index(x, y, z) >= 0) {
                        read(level, p.set(x, y, z), queue);
                    }
                }
            }
        }
        spread(queue);
    }

    private void read(ServerLevel level, BlockPos pos, ArrayDeque<int[]> queue) {
        int i = index(pos.getX(), pos.getY(), pos.getZ());
        BlockState s = level.getBlockState(pos);
        opacity[i] = (byte) Math.max(0, Math.min(15, s.getLightBlock(level, pos)));
        int emit = s.getLightEmission(level, pos);
        if (emit > light[i]) {
            light[i] = (byte) emit;
            queue.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
        }
    }

    private void spread(ArrayDeque<int[]> queue) {
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int level = light[index(c[0], c[1], c[2])];
            if (level <= 1) {
                continue;
            }
            for (Direction d : Direction.values()) {
                int nx = c[0] + d.getStepX(), ny = c[1] + d.getStepY(), nz = c[2] + d.getStepZ();
                int ni = index(nx, ny, nz);
                if (ni < 0) {
                    continue;
                }
                int next = level - Math.max(1, opacity[ni]);
                if (next > light[ni]) {
                    light[ni] = (byte) next;
                    queue.add(new int[]{nx, ny, nz});
                }
            }
        }
    }

    /** Block light at a position, 0 outside the box. */
    int get(int x, int y, int z) {
        int i = index(x, y, z);
        return i < 0 ? 0 : light[i];
    }
}
