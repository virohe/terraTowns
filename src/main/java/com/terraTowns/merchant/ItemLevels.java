package com.terraTowns.merchant;

import com.terraTowns.TerraTowns;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.HashMap;
import java.util.Map;

/**
 * The level each good belongs to (user, 2026-11-05): a Novice merchant sells only what a Novice
 * worker sells (a farmer's bread), an Apprentice also what an Apprentice sells, and so on. An
 * item's level is the lowest level at which any profession sells it, read from the trade tables
 * every villager's offers are drawn from ({@link VillagerTrades#TRADES}; NeoForge merges other
 * mods' professions into the same map).
 *
 * <p>The tables hold recipes for offers, not offers, so each is drawn a few times with a stand-in
 * villager to see what it makes. Cartographers' map trades are skipped: drawing one runs a
 * structure search, and a map is no good for sale (it costs a compass besides). Built once per
 * server, on first use.</p>
 */
public final class ItemLevels {

    /** Draws per recipe: enough to see each variant of a recipe that picks among a few items. */
    private static final int DRAWS = 6;

    private static volatile Map<Item, Integer> levels;
    private static volatile Object builtFor;

    private ItemLevels() {
    }

    /**
     * The level {@code item} is sold at, 1 to 5. Goods no profession's table sells (another
     * mod's offers added in code) count as level 1 rather than never being sold.
     */
    public static int of(ServerLevel level, ItemStack item) {
        Integer l = table(level).get(item.getItem());
        return l == null ? 1 : l;
    }

    /** Is {@code item} in any profession's table (the harness reads this)? */
    public static boolean known(ServerLevel level, ItemStack item) {
        return table(level).containsKey(item.getItem());
    }

    private static Map<Item, Integer> table(ServerLevel level) {
        Map<Item, Integer> map = levels;
        if (map == null || builtFor != level.getServer()) {
            map = build(level);
            levels = map;
            builtFor = level.getServer();
        }
        return map;
    }

    private static Map<Item, Integer> build(ServerLevel level) {
        Map<Item, Integer> map = new HashMap<>();
        Villager stand = new Villager(EntityType.VILLAGER, level);
        RandomSource random = RandomSource.create(20261105L);
        int skipped = 0;
        for (Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> p : VillagerTrades.TRADES.entrySet()) {
            for (Int2ObjectMap.Entry<VillagerTrades.ItemListing[]> byLevel : p.getValue().int2ObjectEntrySet()) {
                int tier = Math.max(1, Math.min(5, byLevel.getIntKey()));
                stand.setVillagerData(stand.getVillagerData().setProfession(p.getKey()).setLevel(tier));
                for (VillagerTrades.ItemListing listing : byLevel.getValue()) {
                    if (listing instanceof VillagerTrades.TreasureMapForEmeralds
                            || listing.getClass().getSimpleName().contains("Map")) {
                        continue;
                    }
                    for (int i = 0; i < DRAWS; i++) {
                        MerchantOffer offer;
                        try {
                            offer = listing.getOffer(stand, random);
                        } catch (RuntimeException e) {
                            skipped++;
                            break; // a recipe that needs a real villager in a real place
                        }
                        if (offer != null && Merchants.workerPrice(offer) != null) {
                            map.merge(offer.getResult().getItem(), tier, Math::min);
                        }
                    }
                }
            }
        }
        TerraTowns.LOGGER.info("Merchant goods: {} items by level from the trade tables ({} recipes skipped)",
                map.size(), skipped);
        return map;
    }
}
