package com.terraTowns.client;

import com.terraTowns.TerraTowns;
import com.terraTowns.client.hud.SettlementHudOverlay;
import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Client-only, mod-bus setup. All three trainer entities extend {@link net.minecraft.world.entity.npc.Villager},
 * so they reuse the vanilla {@link VillagerRenderer}. Without a renderer the client hits a
 * null {@code EntityRenderer} the moment one of them is spawned (e.g. the hamlet Professor
 * during world creation), so this registration is required, not cosmetic.
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID, value = Dist.CLIENT)
public final class TerraTownsClientEvents {

    private TerraTownsClientEvents() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(TerraTownsRegistries.GYM_LEADER.get(), VillagerRenderer::new);
        event.registerEntityRenderer(TerraTownsRegistries.PROFESSOR.get(), VillagerRenderer::new);
        event.registerEntityRenderer(TerraTownsRegistries.RIVAL.get(), VillagerRenderer::new);
        // The Building Plaque is a vanilla wall sign underneath, drawn by vanilla's sign renderer.
        event.registerBlockEntityRenderer(TerraTownsRegistries.BUILDING_PLAQUE_BE.get(), SignRenderer::new);
    }

    /** Villagers can wear armour (Guards put on what they're given), so draw it. */
    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        VillagerRenderer villagers = event.getRenderer(EntityType.VILLAGER);
        if (villagers != null) {
            villagers.addLayer(new VillagerArmorLayer(villagers, event.getEntityModels(),
                    event.getContext().getModelManager()));
        }
    }

    /** Register the top-centre settlement HUD above the rest of the in-game overlays. */
    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR,
                ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "settlement_hud"),
                new SettlementHudOverlay());
    }
}
