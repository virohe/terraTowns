package com.terraTowns.event;

import com.terraTowns.TerraTowns;
import com.terraTowns.influence.InfluenceTracker;
import com.terraTowns.integration.CobblemonHook;
import com.terraTowns.network.TerraTownsNetwork;
import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.GymDesk;
import com.terraTowns.settlement.SettlementManager;
import com.terraTowns.settlement.SettlementPromotion;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Right-clicking a gym-leader villager <b>challenges</b> them. This is the universal "promote"
 * action for a settlement: on a win, if the settlement meets its next-tier milestones
 * ({@link SettlementPromotion}), it grows to the next tier; otherwise the player is shown the
 * remaining checklist. The first win also unlocks the settlement's Gym Leader's Desk.
 *
 * <p><b>0.3 placeholder:</b> the player always wins, since Cobblemon battle integration isn't
 * wired yet. When it lands, only the win/lose determination changes.</p>
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class GymChallengeEvents {

    private GymChallengeEvents() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Villager villager)
                || villager.getVillagerData().getProfession() != TerraTownsRegistries.GYM_LEADER_PROFESSION.get()) {
            return;
        }
        // Suppress the vanilla (empty) trade screen on both hands/sides; the desk is the UI.
        event.setCanceled(true);

        if (event.getHand() != InteractionHand.MAIN_HAND
                || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        challenge(level, player, villager);
    }

    /**
     * Turn a challenge down the way vanilla villagers turn things down: the head shake and "no"
     * sound a villager with nothing to trade gives, plus one short action-bar line saying why —
     * the same channel vanilla uses for a bed that can't be slept in. Deliberately no chat
     * message: the full checklist already lives on the Gym Leader's Desk.
     */
    private static void decline(Villager gymLeader, ServerPlayer player, String reasonKey) {
        gymLeader.setUnhappyCounter(40);
        gymLeader.playSound(SoundEvents.VILLAGER_NO, 1.0f, gymLeader.getVoicePitch());
        player.displayClientMessage(Component.translatable(reasonKey), true);
    }

    private static void challenge(ServerLevel level, ServerPlayer player, Villager gymLeader) {
        SettlementManager manager = SettlementManager.get(level);
        SettlementData settlement = null;
        for (SettlementData s : manager.all()) {
            if (gymLeader.getUUID().equals(s.gymLeaderId())) {
                settlement = s;
                break;
            }
        }
        if (settlement == null) {
            player.displayClientMessage(
                    Component.translatable("message.terra_towns.challenge.unbound"), true);
            return;
        }

        boolean firstWin = !settlement.isGymCleared();
        SettlementPromotion.Eval eval = SettlementPromotion.evaluate(level, settlement);
        boolean ready = eval.to() != null && eval.allMet();

        // A challenge must MEAN something: the first-ever win unlocks the desk, and later wins
        // promote a ready settlement. Otherwise the gym leader declines the bout — no win spam,
        // and progress lives in the desk UI, not chat.
        if (!firstWin && !ready) {
            decline(gymLeader, player, eval.to() == null
                    ? "message.terra_towns.challenge.max_tier"
                    : "message.terra_towns.challenge.not_ready");
            return;
        }

        // A challenge the player has no way to actually fight isn't a win — with no Cobblemon
        // battle wired yet (0.3/0.4 placeholder), the only real check available is whether the
        // player brought a team at all.
        if (!CobblemonHook.hasParty(player)) {
            decline(gymLeader, player, "message.terra_towns.challenge.no_party");
            return;
        }

        // Placeholder battle outcome: the player wins (real Cobblemon battles arrive later).
        player.sendSystemMessage(
                Component.translatable("message.terra_towns.challenge.won").withStyle(ChatFormatting.GREEN));
        if (firstWin) {
            InfluenceTracker.get(level).grant(player.getUUID(), settlement.id());
            settlement.setGymCleared(true);
            // Beaten, the leader is locked in like a villager you've traded with: it stays gym
            // leader even while its desk is being moved.
            GymDesk.lock(gymLeader);
            player.sendSystemMessage(Component.translatable("message.terra_towns.challenge.unlocked")
                    .withStyle(ChatFormatting.AQUA));
        }
        if (ready) {
            settlement.setTier(eval.to());
            settlement.setPromotionReadyAnnounced(false);
            player.sendSystemMessage(Component.translatable("message.terra_towns.promoted",
                    Component.translatable("settlement.terra_towns.tier." + eval.to().id()))
                    .withStyle(ChatFormatting.GOLD));
        }
        manager.setDirty();
        TerraTownsNetwork.sendSyncSettlement(player, settlement);
    }
}
