package com.terraTowns.integration;

import com.cobblemon.mod.common.Cobblemon;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Boundary for all Cobblemon API access. Methods here must only be called when
 * {@link Integrations#COBBLEMON} is true; otherwise they return safe fallbacks so the
 * rest of the mod runs without Cobblemon present.
 *
 * <p>Responsibilities (TODO, behind the soft-dep gate):</p>
 * <ul>
 *   <li>{@link #badgeCount(Player)} – read the player's badge count to drive gym scaling.</li>
 *   <li>battle triggers – start a gym battle when a {@code GymLeaderEntity} is challenged.</li>
 *   <li>Pokecenter heal – heal the player's party at a registered Pokecenter.</li>
 * </ul>
 *
 * <p>This stub intentionally references no Cobblemon types yet so the module compiles
 * with the {@code enable_integrations} flag off. Real calls will be added inside the
 * {@code if (Integrations.COBBLEMON)} guards once the API is on the classpath.</p>
 */
public final class CobblemonHook {

    private CobblemonHook() {
    }

    /**
     * @return true if {@code player} has at least one Pokémon in their party. Used to gate a
     *         gym challenge: winning a fight the player was never actually put into (0.3/0.4's
     *         placeholder battle) made no sense for a player with no team at all — a trainer
     *         with nothing to send out shouldn't be able to "beat" the gym leader. False when
     *         Cobblemon is absent, which reads as "can't challenge" rather than a crash.
     */
    public static boolean hasParty(ServerPlayer player) {
        if (!Integrations.COBBLEMON) {
            return false;
        }
        return Cobblemon.INSTANCE.getStorage().getParty(player).occupied() > 0;
    }

    /** @return the player's Cobblemon badge count, or 0 when Cobblemon is absent. */
    public static int badgeCount(Player player) {
        if (!Integrations.COBBLEMON) {
            return 0;
        }
        // TODO: query Cobblemon player data for badge count
        return 0;
    }

    /** Start a gym battle between {@code player} and the gym leader. No-op without Cobblemon. */
    public static void startGymBattle(Player player /* , GymLeaderEntity leader */) {
        if (!Integrations.COBBLEMON) {
            return;
        }
        // TODO: invoke Cobblemon battle start with a team built from leader.challengeRating()
    }

    /** Heal the player's party (Pokecenter). No-op without Cobblemon. */
    public static void healParty(Player player) {
        if (!Integrations.COBBLEMON) {
            return;
        }
        // TODO: invoke Cobblemon party heal
    }
}
