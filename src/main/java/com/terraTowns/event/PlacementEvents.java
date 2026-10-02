package com.terraTowns.event;

import com.terraTowns.TerraTowns;
import com.terraTowns.block.BuildingPlaqueBlock;
import com.terraTowns.structure.BuildingRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Game-bus handlers that drive {@link BuildingRegistry} from world events: placing a
 * {@link BuildingPlaqueBlock} registers its category with the surrounding settlement,
 * breaking one fires the (currently no-op) removal hook.
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class PlacementEvents {

    private PlacementEvents() {
    }

    /**
     * A plaque was hung: survey its room and name the first building type it qualifies for.
     * Removal needs no event — {@link BuildingPlaqueBlock#onRemove} covers every way a plaque
     * can go, where the old player-break event missed explosions and walls being knocked out.
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getPlacedBlock().getBlock() instanceof BuildingPlaqueBlock)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Player player = event.getEntity() instanceof Player p ? p : null;
        BuildingRegistry.refreshPlaque(level, event.getPos(), player, false);
    }
}
