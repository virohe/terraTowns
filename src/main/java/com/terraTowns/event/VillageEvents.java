package com.terraTowns.event;

import com.terraTowns.TerraTowns;
import com.terraTowns.settlement.VillageRegistry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import com.terraTowns.structure.BuildingDetection;
import com.terraTowns.structure.PlayerBuildings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Villages are noticed as their start chunks load (see {@link VillageRegistry}), and changes
 * inside any settlement's structures are passed to {@link BuildingDetection}. A workstation or bed
 * a player places may make a new building ({@link PlayerBuildings}).
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

    /**
     * A player placed a block: if it's a workstation or a bed in a settlement, the building it
     * stands in registers itself. Queued behind the placement, which finishes after this event
     * (a bed's head half, a workstation's point of interest).
     */
    @SubscribeEvent
    public static void onPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof Player player) {
            BlockPos pos = event.getPos().immutable();
            level.getServer().execute(() -> PlayerBuildings.onPlaced(level, pos, player));
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof Level level && event.getChunk() instanceof LevelChunk chunk) {
            VillageRegistry.onChunkLoad(level, chunk);
        }
    }
}
