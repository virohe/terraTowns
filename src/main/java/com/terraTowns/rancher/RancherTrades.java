package com.terraTowns.rancher;

import com.terraTowns.TerraTowns;
import com.terraTowns.registry.TerraTownsRegistries;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.BasicItemListing;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;

import java.util.List;

/**
 * The Rancher's trades (TT-218): what the pens give, sold for emeralds, and the feed they need,
 * bought. Provisional: no design for them yet, so they follow vanilla's pattern (buy a crop, sell
 * goods; prices and XP of the same order as the shepherd's and butcher's at each level). Being in the
 * trade tables, they also give each good its level for the Merchant ({@code merchant.ItemLevels}).
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID)
public final class RancherTrades {

    private RancherTrades() {
    }

    @SubscribeEvent
    public static void onTrades(VillagerTradesEvent event) {
        if (event.getType() != TerraTownsRegistries.RANCHER_PROFESSION.get()) {
            return;
        }
        var trades = event.getTrades();
        // Novice: buys wheat (feed), sells eggs.
        add(trades.get(1), new BasicItemListing(new ItemStack(Items.WHEAT, 20), new ItemStack(Items.EMERALD), 16, 2, 0.05F),
                new BasicItemListing(1, new ItemStack(Items.EGG, 6), 12, 1, 0.05F));
        // Apprentice: buys hay bales, sells leather.
        add(trades.get(2), new BasicItemListing(new ItemStack(Items.HAY_BLOCK, 3), new ItemStack(Items.EMERALD), 12, 10, 0.05F),
                new BasicItemListing(1, new ItemStack(Items.LEATHER, 2), 12, 5, 0.05F));
        // Journeyman: sells leads and milk.
        add(trades.get(3), new BasicItemListing(1, new ItemStack(Items.LEAD, 1), 12, 10, 0.05F),
                new BasicItemListing(2, new ItemStack(Items.MILK_BUCKET, 1), 12, 10, 0.05F));
        // Expert: sells saddles, buys golden carrots (horse feed).
        add(trades.get(4), new BasicItemListing(6, new ItemStack(Items.SADDLE, 1), 8, 15, 0.05F),
                new BasicItemListing(new ItemStack(Items.GOLDEN_CARROT, 3), new ItemStack(Items.EMERALD), 12, 15, 0.05F));
        // Master: sells name tags and iron horse armour.
        add(trades.get(5), new BasicItemListing(10, new ItemStack(Items.NAME_TAG, 1), 6, 30, 0.05F),
                new BasicItemListing(12, new ItemStack(Items.IRON_HORSE_ARMOR, 1), 6, 30, 0.05F));
    }

    private static void add(List<VillagerTrades.ItemListing> level, VillagerTrades.ItemListing... listings) {
        level.addAll(List.of(listings));
    }
}
