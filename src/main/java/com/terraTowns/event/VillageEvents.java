package com.terraTowns.event;

import com.terraTowns.TerraTowns;
import com.terraTowns.settlement.VillageRegistry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import com.terraTowns.structure.BuildingDetection;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Villages are noticed as their start chunks load (see {@link VillageRegistry}), and changes
 * inside any settlement's structures are passed to {@link BuildingDetection}.
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class VillageEvents {

    private VillageEvents() {
    }

    /** A block in a settlement's structure changed: it gets re-checked (0.5.4, BuildingDetection). */
    @SubscribeEvent
    public static void onBlockChanged(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof Level level) {
            BuildingDetection.onBlockChanged(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof Level level && event.getChunk() instanceof LevelChunk chunk) {
            VillageRegistry.onChunkLoad(level, chunk);
        }
    }
}
