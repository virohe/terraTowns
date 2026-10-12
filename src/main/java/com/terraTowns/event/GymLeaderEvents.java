package com.terraTowns.event;

import com.terraTowns.TerraTowns;
import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.settlement.GymDesk;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Making and losing gym leaders (0.5.1, see {@link GymDesk}): right-clicking an unemployed
 * villager with a Gym Leader's Desk in hand appoints it (the desk isn't used up), and a leader's
 * death or conversion to a zombie leaves the post vacant.
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class GymLeaderEvents {

    private GymLeaderEvents() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Villager villager)
                || villager.getVillagerData().getProfession() != VillagerProfession.NONE
                || !event.getItemStack().is(TerraTownsRegistries.GYM_LEADERS_DESK_ITEM.get())) {
            return;
        }
        // Holding a desk at an unemployed villager means "appoint": no head shake, no trade screen.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getHand() != InteractionHand.MAIN_HAND
                || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        GymDesk.Appointment result = GymDesk.appoint(level, villager, player.getUUID());
        if (result != GymDesk.Appointment.APPOINTED) {
            villager.setUnhappyCounter(40);
            villager.playSound(SoundEvents.VILLAGER_NO, 1.0F, villager.getVoicePitch());
            player.displayClientMessage(Component.translatable(
                    "message.terra_towns.appoint." + result.name().toLowerCase(java.util.Locale.ROOT)), true);
            return;
        }
        SettlementData settlement = SettlementManager.get(level).containing(villager.blockPosition());
        villager.playSound(SoundEvents.VILLAGER_CELEBRATE, 1.0F, villager.getVoicePitch());
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getEyeY(), villager.getZ(),
                8, 0.4, 0.4, 0.4, 0.0);
        player.sendSystemMessage(Component.translatable("message.terra_towns.appoint.appointed",
                settlement != null && settlement.name() != null ? Component.literal(settlement.name())
                        : Component.translatable("hud.terra_towns.unnamed"))
                .withStyle(ChatFormatting.AQUA));
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Villager villager && villager.level() instanceof ServerLevel level) {
            GymDesk.onLeaderGone(level, villager);
        }
    }

    /** A zombie that kills a villager converts it: the villager is discarded, it never "dies". */
    @SubscribeEvent
    public static void onConverted(LivingConversionEvent.Post event) {
        if (event.getEntity() instanceof Villager villager && villager.level() instanceof ServerLevel level) {
            GymDesk.onLeaderGone(level, villager);
        }
    }
}
