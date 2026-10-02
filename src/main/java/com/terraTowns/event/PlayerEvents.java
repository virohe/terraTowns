package com.terraTowns.event;

import com.terraTowns.TerraTowns;
import com.terraTowns.network.TerraTownsNetwork;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementManager;
import com.terraTowns.worldgen.CoastalSpawnFinder;
import com.terraTowns.worldgen.HamletPiece;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Optional;

/**
 * Assigns each player their own starting hamlet on first login.
 *
 * <p>The first time a player joins a world that has unassigned candidate sites (seeded by
 * {@link WorldEvents}), one candidate is popped, its hamlet is built, and the settlement is
 * bound to that player. From then on only that player can build it up (enforced in
 * {@link com.terraTowns.structure.BuildingRegistry}). The player is teleported in and the
 * hamlet becomes their respawn point. Players who already own a hamlet are left alone.</p>
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class PlayerEvents {

    private PlayerEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        ServerLevel overworld = server.overworld();
        SettlementManager manager = SettlementManager.get(overworld);

        // Already has a hamlet? Leave them where they are.
        if (manager.spawnHamletOwnedBy(player.getUUID()).isPresent()) {
            return;
        }

        String playerName = player.getGameProfile().getName();
        RandomSource random = overworld.getRandom();

        // Normal path: claim one of the pre-built, unowned starting hamlets.
        SettlementData hamlet = manager.claimUnownedHamlet(player.getUUID(), random).orElse(null);
        if (hamlet != null) {
            hamlet.setName(playerName + "'s Hamlet");
        } else {
            // Fallback: more players than pre-built hamlets — build a fresh one on demand.
            // CoastalSpawnFinder honours claim-clearance from existing settlements, so this
            // can never land on top of an existing hamlet.
            Optional<BlockPos> site = CoastalSpawnFinder.find(overworld);
            if (site.isEmpty()) {
                TerraTowns.LOGGER.warn("No hamlet available to assign to {}", playerName);
                return;
            }
            BlockPos spawn = HamletPiece.place(overworld, site.get());
            hamlet = manager.addSpawnHamlet(site.get(), player.getUUID(), spawn, playerName + "'s Hamlet");
        }

        BlockPos spawn = hamlet.spawnPoint() != null ? hamlet.spawnPoint() : hamlet.center();
        player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        player.setRespawnPosition(overworld.dimension(), spawn, 0.0f, true, false);

        TerraTowns.LOGGER.info("Assigned hamlet {} ({}) to {} at {}",
                hamlet.id(), hamlet.name(), playerName, spawn);
    }

    /**
     * Push the full settlement set to the joining client so the settlement HUD overlay has data
     * to draw. Runs at {@link EventPriority#LOWEST} so it observes any hamlet just claimed/built
     * by {@link #onPlayerLogin} above.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerLoginSyncSettlements(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null) {
            return;
        }
        SettlementManager manager = SettlementManager.get(player.getServer().overworld());
        TerraTownsNetwork.sendAllSettlements(player, manager.all());
    }

    /**
     * If world generation couldn't place the full complement of starting hamlets (the coastal
     * finder ran out of suitable sites), quietly let <b>server admins</b> know when they log in —
     * once per server session per op. Regular players never see it. The placement happens at
     * server start before anyone is online, so login is the earliest an admin can be told.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onOpLoginHamletShortfall(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null) {
            return;
        }
        if (!player.hasPermissions(2)) {
            return; // operators / server admins only
        }
        SettlementManager manager = SettlementManager.get(player.getServer().overworld());
        if (manager.hasHamletShortfall() && manager.markShortfallNotified(player.getUUID())) {
            player.sendSystemMessage(Component.translatable(
                    "message.terra_towns.hamlet_shortfall",
                    manager.hamletPlaced(), manager.hamletTarget()).withStyle(ChatFormatting.GOLD));
        }
    }
}
