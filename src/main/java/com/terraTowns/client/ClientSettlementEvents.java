package com.terraTowns.client;

import com.terraTowns.TerraTowns;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client game-bus glue for the settlement HUD:
 * <ul>
 *   <li>Every tick, advance the HUD toast animation (fade in, hold, fade out).</li>
 *   <li>Every 20 ticks, recompute which settlement the player is inside (the expensive lookup),
 *       so the render layer never does distance maths per frame.</li>
 *   <li>On disconnect, clear the {@link ClientSettlementCache}.</li>
 * </ul>
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID, value = Dist.CLIENT)
public final class ClientSettlementEvents {

    private static int tickCounter;

    private ClientSettlementEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        ClientSettlementCache cache = ClientSettlementCache.INSTANCE;
        cache.tick();
        if (++tickCounter >= 20) {
            tickCounter = 0;
            cache.updateInside(player.blockPosition());
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSettlementCache.INSTANCE.clear();
        tickCounter = 0;
    }
}
