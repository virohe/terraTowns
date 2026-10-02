package com.terraTowns;

import com.terraTowns.network.TerraTownsNetwork;
import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.settlement.SettlementManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main mod class for <b>Cobblemon: Terra Towns</b>.
 *
 * <p>Terra Towns overhauls the settlement system into a four-tier progression
 * (hamlet -&gt; village -&gt; town -&gt; city) driven by gyms, an influence /
 * progression system, structure registration, and player-built routes. It is a
 * standalone companion to <i>Terra Continental</i> and integrates (optionally) with
 * Cobblemon, GeckoLib and Create.</p>
 *
 * <p>This is a scaffold: subsystems are stubbed with their intended responsibilities
 * documented inline. See {@code DESIGN.md} for the full architecture and roadmap.</p>
 */
@Mod(TerraTowns.MOD_ID)
public final class TerraTowns {

    public static final String MOD_ID = "terra_towns";
    public static final Logger LOGGER = LoggerFactory.getLogger("Terra Towns");

    public TerraTowns(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Cobblemon: Terra Towns initializing");

        // Register all deferred content (blocks, entities, items, creative tab).
        TerraTownsRegistries.register(modEventBus);

        // Register the network channel: settlement-editing packets (Gym Leader's Desk) + the
        // client HUD sync packets.
        TerraTownsNetwork.register(modEventBus);

        // Settlement state lives in level-attached SavedData; nothing to register on
        // the mod bus for it, but we touch the class here so its constants load early
        // and to document where world state is rooted.
        SettlementManager.touch();

        // Game-bus handlers (block place -> building registration, professor interaction,
        // spawn-hamlet placement, settlement sync-on-join, debug commands) are auto-registered
        // via @EventBusSubscriber (PlacementEvents, TrainerEvents, WorldEvents, PlayerEvents,
        // TerraTownsCommands). Client-only mod-bus setup (entity renderers, the Gym Leader's Desk
        // HUD layer) lives in TerraTownsClientEvents. Mod-bus entity attributes are wired in
        // TerraTownsRegistries.register above.
    }
}
