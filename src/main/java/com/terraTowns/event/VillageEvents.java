package com.terraTowns.event;

import com.terraTowns.TerraTowns;
import com.terraTowns.settlement.VillageRegistry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Villages are noticed as their start chunks load (see {@link VillageRegistry}). */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class VillageEvents {

    private VillageEvents() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof Level level && event.getChunk() instanceof LevelChunk chunk) {
            VillageRegistry.onChunkLoad(level, chunk);
        }
    }
}
