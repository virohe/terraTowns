package com.terraTowns.client;

import com.terraTowns.TerraTowns;
import com.terraTowns.rancher.Ranchers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A fence gate looks open while a villager walks through it (user, 2026-11-09), though it stays
 * closed: the server never opens it, so no animal gets out ({@code FenceGateBlockMixin}). Only this
 * client's copy of the block is changed; then it's put back, unless the server has changed the gate
 * since.
 *
 * <p>It's drawn open while a villager is actually in the gate (its centre over the gate's block),
 * and shut {@value #HOLD_TICKS} ticks after the last has stepped out of it: long enough that a
 * villager nudged half out and back doesn't flap it, short enough that it shuts behind one that has
 * gone through (playtest, 2026-11-10). It used to stay open while a villager was merely near, and a
 * Rancher at work by a small pen's gate kept it open all day.</p>
 *
 * <p>Side effect: while it's drawn open, this client thinks you could walk through too, and the
 * server puts you back if you try.</p>
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID, value = Dist.CLIENT)
public final class RancherGateVisuals {

    /** How near the player villagers are watched. */
    private static final double RANGE = 48.0;
    /** How long a gate stays drawn open after the last villager in it has stepped out: half a second. */
    private static final int HOLD_TICKS = 10;

    /** A gate drawn open: the open state drawn, and the last tick a villager was in it. */
    private record Drawn(BlockState open, long lastNear) {
    }

    private static final Map<BlockPos, Drawn> DRAWN_OPEN = new HashMap<>();
    private static ClientLevel drawnIn;

    private RancherGateVisuals() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != drawnIn) {
            DRAWN_OPEN.clear(); // a new world: nothing of the old one is drawn here
            drawnIn = level;
        }
        if (level == null || mc.player == null) {
            return;
        }
        long now = level.getGameTime();
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, mc.player.getBoundingBox().inflate(RANGE));
        for (Villager v : villagers) {
            BlockPos at = BlockPos.containing(v.getX(), v.getY() + 0.2, v.getZ());
            BlockState state = level.getBlockState(at);
            if (Ranchers.isClosedGate(state) && !DRAWN_OPEN.containsKey(at)) {
                BlockState open = state.setValue(FenceGateBlock.OPEN, true);
                level.setBlock(at, open, Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
                DRAWN_OPEN.put(at.immutable(), new Drawn(open, now));
                level.playLocalSound(at, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1.0F,
                        level.getRandom().nextFloat() * 0.1F + 0.9F, false);
            }
        }
        for (Iterator<Map.Entry<BlockPos, Drawn>> it = DRAWN_OPEN.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Drawn> e = it.next();
            BlockPos pos = e.getKey();
            boolean inGate = villagers.stream().anyMatch(v -> BlockPos.containing(v.getX(), v.getY() + 0.2, v.getZ()).equals(pos));
            if (inGate) {
                e.setValue(new Drawn(e.getValue().open(), now));
                continue;
            }
            if (now - e.getValue().lastNear() < HOLD_TICKS) {
                continue;
            }
            if (level.getBlockState(pos) == e.getValue().open()) {
                level.setBlock(pos, e.getValue().open().setValue(FenceGateBlock.OPEN, false),
                        Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
                level.playLocalSound(pos, SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1.0F,
                        level.getRandom().nextFloat() * 0.1F + 0.9F, false);
            }
            it.remove();
        }
    }
}
