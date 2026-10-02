package com.terraTowns.event;

import com.terraTowns.TerraTowns;
import com.terraTowns.npc.ProfessorEntity;
import com.terraTowns.npc.RivalEntity;
import com.terraTowns.rival.RivalPool;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.rival.RivalPosting;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Game-bus handlers for trainer-NPC interaction.
 *
 * <p>Right-clicking the {@link ProfessorEntity} triggers the one-shot starter gift (tracked on
 * player data — see {@link ProfessorEntity#tryGiveStarterGift(Player)}). Right-clicking a
 * {@link RivalEntity} fights it (0.4).</p>
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class TrainerEvents {

    private TrainerEvents() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide) {
            return;
        }
        if (event.getTarget() instanceof RivalEntity rival) {
            if (event.getEntity() instanceof ServerPlayer player
                    && event.getLevel() instanceof ServerLevel level) {
                challengeRival(level, player, rival);
            }
            event.setCanceled(true);
            return;
        }
        if (!(event.getTarget() instanceof ProfessorEntity professor)) {
            return;
        }
        Player player = event.getEntity();
        if (professor.tryGiveStarterGift(player)) {
            player.displayClientMessage(Component.translatable("message.terra_towns.professor.gift"), false);
        } else {
            player.displayClientMessage(Component.translatable("message.terra_towns.professor.already"), false);
        }
        event.setCanceled(true);
    }

    /**
     * Fight the player's rival.
     *
     * <p><b>0.4 placeholder, same as the gym:</b> the player wins, because Cobblemon battles are
     * not wired yet. Everything around the outcome is real — the defeat is recorded, the rivalry
     * ranks up, and the entity leaves so the next posting arrives stronger — so when the real
     * battle lands, only the win/lose line changes.</p>
     */
    private static void challengeRival(ServerLevel level, ServerPlayer player, RivalEntity rival) {
        if (!rival.belongsTo(player.getUUID())) {
            player.displayClientMessage(
                    Component.translatable("message.terra_towns.rival.not_yours"), true);
            return;
        }
        RivalPool pool = RivalPool.get(level);
        SettlementData credited = RivalPosting.defeated(level, player.getUUID());
        int defeats = pool.record(player.getUUID()).defeats();
        int nextRank = pool.rankFor(level, player.getUUID());

        player.sendSystemMessage(Component.translatable("message.terra_towns.rival.defeated",
                rival.rank(), defeats).withStyle(ChatFormatting.GREEN));
        if (credited != null) {
            player.sendSystemMessage(Component.translatable("message.terra_towns.rival.credited",
                    credited.name()).withStyle(ChatFormatting.GREEN));
        }
        player.sendSystemMessage(Component.translatable("message.terra_towns.rival.returns", nextRank)
                .withStyle(ChatFormatting.GRAY));

        // The rivalry lives in RivalPool, not in this entity — dropping it is free, and it is
        // what lets the next posting show up at the higher rank rather than re-using a stale one.
        rival.discard();
    }
}
