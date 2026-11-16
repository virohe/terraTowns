package com.terraTowns.event;

import com.terraTowns.TerraTowns;
import com.terraTowns.merchant.MerchantPrice;
import com.terraTowns.merchant.MerchantStock;
import com.terraTowns.merchant.Merchants;
import com.terraTowns.network.MerchantActionPacket;
import com.terraTowns.network.MerchantInfoPacket;
import com.terraTowns.settlement.SettlementData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Players and merchants (0.6, see {@link Merchants}).
 *
 * <ul>
 *   <li>Right-clicking a villager you may not trade with (someone else's settlement, not a
 *       merchant) is refused with a line saying why.</li>
 *   <li>Right-clicking a merchant shows its trades, rebuilt from its stock each time, like any
 *       villager's; its owner also sees the emeralds it has made in the screen's corner.</li>
 *   <li>The stall itself (what's on sale, prices, taking the emeralds) is only at the merchant's
 *       counter: the owner right-clicks it ({@link #openStall}; user, 2026-11-05).</li>
 * </ul>
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class MerchantEvents {

    /** How far the owner may stand from the merchant they're managing (it keeps near its counter). */
    private static final double REACH = 16.0;

    private MerchantEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Villager villager)
                || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player)
                || villager.isBaby() || villager.isTrading()) {
            return;
        }
        if (Merchants.isMerchant(villager)) {
            Merchants.refreshOffers(villager);
            if (event.getHand() == InteractionHand.MAIN_HAND && Merchants.isOwner(level, player, villager)) {
                sendInfo(player, villager, false);
            }
            return;
        }
        if (!Merchants.canTrade(level, player, villager)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
            if (event.getHand() == InteractionHand.MAIN_HAND) {
                villager.setUnhappyCounter(40);
                villager.playSound(SoundEvents.VILLAGER_NO, 1.0F, villager.getVoicePitch());
                SettlementData s = Merchants.settlementOf(level, villager);
                player.displayClientMessage(Component.translatable("message.terra_towns.merchant.outsider",
                        s != null && s.name() != null ? Component.literal(s.name())
                                : Component.translatable("hud.terra_towns.unnamed"))
                        .withStyle(ChatFormatting.GOLD), true);
            }
        }
    }

    /**
     * The owner right-clicked a Market Counter: the stall screen of the merchant who works it.
     * Anyone else, or a counter nobody works yet, gets a line saying so.
     */
    public static void openStall(ServerLevel level, ServerPlayer player, BlockPos counter) {
        Villager merchant = Merchants.atCounter(level, counter);
        if (merchant == null) {
            player.displayClientMessage(Component.translatable("message.terra_towns.stall.no_merchant")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (!Merchants.isOwner(level, player, merchant)) {
            player.displayClientMessage(Component.translatable("message.terra_towns.stall.not_yours")
                    .withStyle(ChatFormatting.GOLD), true);
            return;
        }
        Merchants.refreshOffers(merchant);
        sendInfo(player, merchant, true);
    }

    @SubscribeEvent
    public static void onTrade(TradeWithVillagerEvent event) {
        if (event.getAbstractVillager() instanceof Villager villager && Merchants.isMerchant(villager)
                && villager.level() instanceof ServerLevel) {
            boolean byOwner = event.getEntity() instanceof ServerPlayer player
                    && Merchants.isOwner((ServerLevel) villager.level(), player, villager);
            Merchants.onTrade(villager, event.getMerchantOffer(), byOwner);
            if (byOwner) {
                sendInfo((ServerPlayer) event.getEntity(), villager, false);
            }
        }
    }

    public static void sendInfo(ServerPlayer player, Villager merchant, boolean open) {
        PacketDistributor.sendToPlayer(player, MerchantInfoPacket.of(merchant, open));
    }

    /** An owner's change from the stall screen. */
    public static void handleAction(MerchantActionPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        Entity entity = level.getEntity(packet.merchantId());
        if (!(entity instanceof Villager merchant) || !Merchants.isMerchant(merchant)
                || merchant.distanceTo(player) > REACH || !Merchants.isOwner(level, player, merchant)) {
            return;
        }
        MerchantStock stock = Merchants.stock(merchant);
        MerchantStock.Entry e = stock.entry(packet.entry());
        int slots = Merchants.slots(Merchants.level(merchant));
        // A price step at a time, as many as asked (Shift-click asks for 5), never more than 64.
        int steps = Math.max(1, Math.min(64, packet.arg()));
        switch (packet.action()) {
            case TAKE_EMERALDS -> {
                int moved = Merchants.takeEmeralds(player, merchant);
                if (moved > 0) {
                    player.displayClientMessage(Component.translatable("message.terra_towns.merchant.took", moved), true);
                }
            }
            case SET_SLOT -> {
                if (e != null) {
                    stock.setSlot(e, packet.arg(), slots, Merchants.canSell(merchant, e));
                }
            }
            case CLEAR_SLOT -> stock.clearSlot(packet.arg());
            case RANDOMIZE -> {
                if (stock.randomize(slots, x -> Merchants.canSell(merchant, x), merchant.getRandom(),
                        packet.arg() == 1) == 0) {
                    player.displayClientMessage(Component.translatable("message.terra_towns.stall.nothing_to_add")
                            .withStyle(ChatFormatting.GRAY), true);
                }
            }
            case CHEAPER -> {
                for (int i = 0; e != null && i < steps; i++) {
                    stock.setOwnerPrice(e, e.price().cheaper(e.item().getMaxStackSize()));
                }
            }
            case DEARER -> {
                for (int i = 0; e != null && i < steps; i++) {
                    stock.setOwnerPrice(e, e.price().dearer());
                }
            }
            case RESET_PRICE -> {
                if (e != null) {
                    stock.setOwnerPrice(e, (MerchantPrice) null);
                }
            }
        }
        if (!merchant.isTrading()) {
            Merchants.refreshOffers(merchant);
        }
        sendInfo(player, merchant, true);
    }
}
