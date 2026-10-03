package com.terraTowns.event;

import com.terraTowns.TerraTowns;
import com.terraTowns.network.TerraTownsNetwork;
import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.rival.RivalPosting;
import com.terraTowns.settlement.JobBoard;
import com.terraTowns.settlement.GymDesk;
import com.terraTowns.settlement.GuardRecruitment;
import com.terraTowns.settlement.VillageRegistry;
import com.terraTowns.structure.BuildingRegistry;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementJob;
import com.terraTowns.settlement.SettlementManager;
import com.terraTowns.settlement.SettlementPromotion;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.UUID;

/**
 * Server-tick maintenance for settlements. Runs a throttled scan (every {@link #SCAN_INTERVAL}
 * ticks) rather than reacting to per-entity events, since there is no vanilla "villager changed
 * profession" hook.
 *
 * <p><b>Stage 1 (0.3):</b> binds the gym leader. When a jobless villager claims a Gym Leader's
 * Desk (acquiring the {@code gym_leader} profession — see
 * {@link TerraTownsRegistries#GYM_LEADER_PROFESSION}), the first such villager found within a
 * settlement's radius is recorded as that settlement's {@link SettlementData#gymLeaderId} and the
 * owner is notified. This is also where the promotion-eligibility check will live (Stage 2).</p>
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
     * If {@code settlement} has no bound gym leader, look for a villager that has claimed a desk
     * (the {@code gym_leader} profession) within its radius, bind the first one, and tell the owner.
     */
    public static void bindGymLeaderIfNeeded(MinecraftServer server, ServerLevel level,
                                              SettlementManager manager, SettlementData settlement) {
        double r = settlement.registrationRadius();
        AABB box = AABB.ofSize(Vec3.atCenterOf(settlement.center()), 2.0 * r, 2.0 * r, 2.0 * r);
        boolean succession = false;
        if (settlement.gymLeaderId() != null) {
            // A binding is dropped only on positive evidence the leader is gone: the villager is
            // loaded but dead, OR it can't be found AND a different villager has claimed the desk.
            // "Can't be found" alone is not evidence — an entity at the edge of the loaded area
            // reads the same as a dead one — and treating it as death is what made a living leader
            // unbind whenever the player walked away. Losing the profession isn't death either:
            // it's what happens when the desk is picked up to be moved, and the leader gets the
            // new desk back (GymDesk). Until 0.4.8 it cost the leader the gym.
            UUID boundId = settlement.gymLeaderId();
            Entity current = level.getEntity(boundId);
            boolean dead = current != null && !current.isAlive();
            boolean replaced = current == null && !level.getEntitiesOfClass(Villager.class, box,
                    v -> !v.getUUID().equals(boundId)
                            && v.getVillagerData().getProfession() == TerraTownsRegistries.GYM_LEADER_PROFESSION.get())
                    .isEmpty();
            if (!dead && !replaced) {
                return;
            }
            TerraTowns.LOGGER.info("Gym leader {} for settlement {} is gone — rebinding", boundId, settlement.id());
            settlement.setGymLeaderId(null);
            succession = true;
            manager.setDirty();
        }
        List<Villager> leaders = level.getEntitiesOfClass(Villager.class, box,
                v -> v.getVillagerData().getProfession() == TerraTownsRegistries.GYM_LEADER_PROFESSION.get());
        if (leaders.isEmpty()) {
            return;
        }
        Villager leader = leaders.get(0);
        settlement.setGymLeaderId(leader.getUUID());
        manager.setDirty();
        TerraTowns.LOGGER.info("Bound gym leader {} to settlement {}", leader.getUUID(), settlement.id());

        // A new leader has to be beaten before the desk is yours again: the gym reverts to
        // uncleared, which is exactly the desk's lock (GymLeadersDeskBlock) and the challenge's
        // "first win unlocks the desk" path. Jobs already unlocked are kept — a leader dying to a
        // zombie shouldn't strip the town's workforce.
        boolean relocked = succession && settlement.isGymCleared();
        if (relocked) {
            settlement.setGymCleared(false);
        }

        if (settlement.ownerId() != null) {
            ServerPlayer owner = server.getPlayerList().getPlayer(settlement.ownerId());
            if (owner != null) {
                Component name = settlement.name() != null
                        ? Component.literal(settlement.name())
                        : Component.translatable("hud.terra_towns.unnamed");
                owner.sendSystemMessage(Component.translatable(relocked
                        ? "message.terra_towns.gym_leader.succeeded"
                        : "message.terra_towns.gym_leader.claimed", name).withStyle(ChatFormatting.AQUA));
            }
        }
    }
}
