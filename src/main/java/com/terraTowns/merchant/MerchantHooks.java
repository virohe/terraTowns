package com.terraTowns.merchant;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

/** Entry points for {@code VillagerMixin}, kept out of the mixin so they can be tested. */
public final class MerchantHooks {

    private MerchantHooks() {
    }

    /**
     * Called as a villager is about to restock.
     *
     * @return true to cancel the restock (merchants), false to let vanilla restock (workers,
     *         after their overstock has gone to the merchants)
     */
    public static boolean beforeRestock(Villager villager) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (Merchants.isMerchant(villager)) {
            if (!villager.isTrading()) {
                Merchants.refreshOffers(villager);
            }
            return true;
        }
        // Vanilla also "restocks" every working villager at the turn of the day (catchUpDemand),
        // sold or not. A worker that already handed over today and has sold nothing since has no
        // new stock to give: sending it again would hand the merchant the same goods twice.
        boolean soldSince = false;
        for (net.minecraft.world.item.trading.MerchantOffer offer : villager.getOffers()) {
            soldSince |= offer.getUses() > 0;
        }
        long today = level.getDayTime() / 24000L;
        if (!soldSince && villager.getData(com.terraTowns.registry.TerraTownsRegistries.OVERSTOCK_DAY.get()) >= today) {
            return false;
        }
        Merchants.captureOverstock(level, villager);
        return false;
    }
}
