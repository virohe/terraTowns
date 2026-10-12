package com.terraTowns.event;

import com.terraTowns.TerraTowns;
import com.terraTowns.network.TerraTownsNetwork;
import com.terraTowns.rival.RivalPosting;
import com.terraTowns.settlement.JobBoard;
import com.terraTowns.settlement.GymDesk;
import com.terraTowns.settlement.GuardRecruitment;
import com.terraTowns.settlement.VillageRegistry;
import com.terraTowns.structure.BuildingDetection;
import com.terraTowns.structure.BuildingRegistry;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementJob;
import com.terraTowns.settlement.SettlementManager;
import com.terraTowns.settlement.SettlementPromotion;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/**
 * Server-tick maintenance for settlements. Runs a throttled scan (every {@link #SCAN_INTERVAL}
 * ticks) rather than reacting to per-entity events, since there is no vanilla "villager changed
 * profession" hook.
 *
 * <p>Fills a vacant gym-leader post where the settlement does that itself
 * ({@link #bindGymLeaderIfNeeded}; players appoint leaders otherwise, see {@code GymDesk}), keeps
 * the desk with its leader, re-surveys plaques, announces promotion readiness, and refreshes jobs.</p>
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class SettlementTickEvents {

    /** Server ticks between settlement scans (~2 seconds at 20 tps). */
    private static final int SCAN_INTERVAL = 40;

    private static int counter;
    /** Scans run so far; plaques are re-surveyed on every {@link #PLAQUE_CHECK_EVERY}th. */
    private static int scans;
    private static final int PLAQUE_CHECK_EVERY = 10;

    private SettlementTickEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++counter < SCAN_INTERVAL) {
            return;
        }
        counter = 0;
        scans++;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        SettlementManager manager = SettlementManager.get(level);
        RivalPosting.tick(server, level, manager);
        VillageRegistry.drain(level);
        for (SettlementData settlement : manager.all()) {
            // Everything below reads the settlement's villagers. With nobody nearby those
            // entities aren't loaded and every entity query comes back empty — which, taken at
            // face value, unbinds a living gym leader, drops every job holder, and resets the
            // promotion toast, all of which then re-fire the moment the player walks back.
            // An unloaded settlement hasn't changed; leave it alone until it's loaded again.
            if (!level.isPositionEntityTicking(settlement.center())) {
                continue;
            }
            if (scans % PLAQUE_CHECK_EVERY == 0) {
                BuildingRegistry.revalidate(level, settlement);
            }
            if (BuildingDetection.tick(level, settlement)) {
                manager.setDirty();
            }
            bindGymLeaderIfNeeded(server, level, manager, settlement);
            GymDesk.tick(level, settlement);
            // Only worth evaluating promotion once there's a gym leader to challenge.
            if (settlement.gymLeaderId() != null) {
                checkPromotionReadiness(server, level, manager, settlement);
            }
            GuardRecruitment.recruit(level, settlement);
            refreshJobs(server, level, manager, settlement);
        }
    }

    /**
     * Keep the settlement's job board current (0.4) and announce each job the first time it is
     * earned. Unlocks are one-way, so this announces at most once per job per settlement; the
     * staffing half is re-evaluated every scan because villagers wander, die and change trade.
     */
    private static void refreshJobs(MinecraftServer server, ServerLevel level,
                                    SettlementManager manager, SettlementData settlement) {
        List<SettlementJob> unlocked = JobBoard.refresh(level, settlement);
        if (unlocked.isEmpty()) {
            return;
        }
        manager.setDirty();
        if (settlement.ownerId() == null) {
            return;
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(settlement.ownerId());
        if (owner == null) {
            return;
        }
        Component settlementName = settlement.name() != null
                ? Component.literal(settlement.name())
                : Component.translatable("hud.terra_towns.unnamed");
        for (SettlementJob job : unlocked) {
            owner.sendSystemMessage(Component.translatable("message.terra_towns.job.unlocked",
                    job.displayName(), settlementName).withStyle(ChatFormatting.AQUA));
        }
    }

    /**
     * Fire the "[Tier] Ready for Promotion!" toast to the owner the first time a settlement meets
     * its next-tier milestones, and reset the flag if eligibility later lapses so it can re-fire.
     */
    private static void checkPromotionReadiness(MinecraftServer server, ServerLevel level,
                                                SettlementManager manager, SettlementData settlement) {
        SettlementPromotion.Eval eval = SettlementPromotion.evaluate(level, settlement);
        boolean ready = eval.to() != null && eval.allMet();
        if (ready == settlement.isPromotionReadyAnnounced()) {
            return;
        }
        settlement.setPromotionReadyAnnounced(ready);
        if (ready && settlement.ownerId() != null) {
            ServerPlayer owner = server.getPlayerList().getPlayer(settlement.ownerId());
            if (owner != null) {
                TerraTownsNetwork.sendPromotionReadyToast(owner, settlement.tier().id());
            }
        }
    }

    /**
     * Fill a vacant gym-leader post where the settlement does it itself (0.5.1): adopt a villager
     * already holding the profession here (a world from before 0.5.1, when villagers claimed
     * desks), else, for an unowned generated village, spawn a leader beside its desk so the
     * village always has somebody to challenge. Anywhere else the post waits for a player to
     * appoint someone ({@link GymDesk#appoint}). A binding is only ever dropped by the leader's
     * death ({@link GymDesk#onLeaderGone}).
     */
    public static void bindGymLeaderIfNeeded(MinecraftServer server, ServerLevel level,
                                              SettlementManager manager, SettlementData settlement) {
        if (settlement.gymLeaderId() != null) {
            return;
        }
        List<Villager> holders = GymDesk.villagersWithProfession(level, settlement);
        Villager leader = holders.isEmpty() ? null : holders.get(0);
        if (leader == null && settlement.generatedGym() && settlement.ownerId() == null) {
            leader = GymDesk.appointLeader(level, settlement);
        }
        if (leader != null) {
            GymDesk.bind(level, manager, settlement, leader);
        }
    }
}
