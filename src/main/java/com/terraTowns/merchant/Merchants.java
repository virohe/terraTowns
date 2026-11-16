package com.terraTowns.merchant;

import com.terraTowns.influence.InfluenceTracker;
import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.settlement.GuardRecruitment;
import com.terraTowns.settlement.ProfessionJobs;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementJob;
import com.terraTowns.settlement.SettlementManager;
import com.terraTowns.settlement.SettlementPromotion;
import com.terraTowns.settlement.SettlementTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Merchant (0.6, user's design 2026-10-16, DESIGN.md §2.8). A villager profession worked at
 * the Market Stall's counter. It makes nothing: it sells what the village's workers make.
 *
 * <ul>
 *   <li><b>Stock</b> comes from the workers' overstock: when a worker restocks, what players did
 *       not buy of its previous stock goes to a merchant ({@link #captureOverstock}). Vanilla only
 *       restocks a villager someone bought from, so a worker nobody traded with hands over its
 *       whole stock once a day instead ({@link #dailyOverstock}); otherwise a stall in a village
 *       the owner never trades in would stay empty forever. Up to 8 restocks per worker and item.</li>
 *   <li><b>Prices</b> are {@link MerchantPrice#markedUp the highest worker price plus 10%}; the
 *       owner may lower one, never below the cheapest worker.</li>
 *   <li><b>Takings</b> collect as "emeralds made" until the owner takes them.</li>
 *   <li><b>Passive sales</b> to the village's other villagers use stock up and earn a little over
 *       time, so a stall always makes something ({@link #passiveSales}).</li>
 *   <li><b>Who trades with whom</b>: in a settlement someone holds, players without influence
 *       there trade only with its merchants ({@link #canTrade}).</li>
 *   <li><b>Levels</b> (user, 2026-11-05): a merchant levels up from players' purchases as any
 *       villager does, up to Apprentice in a village, Expert in a town and Master in a city
 *       ({@link #maxLevel}). It sells only goods of its level or below ({@link ItemLevels}), takes in
 *       nothing above it (that stays with the worker), and has two slots per level
 *       ({@link #slots}).</li>
 *   <li><b>At its counter</b>: it keeps near its counter all day, except at night and when the
 *       village gathers in the afternoon ({@link #keepNear}).</li>
 * </ul>
 */
public final class Merchants {

    /** Game ticks between a merchant's chances of a passive sale (two minutes). */
    public static final int PASSIVE_INTERVAL = 2400;
    /** How many missed chances are caught up after the stall was unloaded: one day's worth. */
    public static final int PASSIVE_CATCH_UP = 24000 / PASSIVE_INTERVAL;
    /** With this many other villagers, every chance is a sale; with fewer, proportionally fewer. */
    public static final int PASSIVE_FULL_VILLAGE = 10;
    /** How far a merchant strays from its counter while it's working (or idle) in the day. */
    public static final int STAY_RADIUS = 6;
    /**
     * XP a purchase gives, by the level of the goods: what vanilla's own sale trades give at
     * each level (a Novice's bread 2, a Master's golden carrot 30).
     */
    private static final int[] XP_BY_LEVEL = {2, 10, 20, 30, 30};

    private Merchants() {
    }

    // --- who is a merchant -------------------------------------------------

    public static boolean isMerchant(Villager v) {
        return v.getVillagerData().getProfession() == TerraTownsRegistries.MERCHANT_PROFESSION.get();
    }

    public static MerchantStock stock(Villager merchant) {
        return merchant.getData(TerraTownsRegistries.MERCHANT_STOCK.get());
    }

    /** The adult merchants standing in {@code settlement}. */
    public static List<Villager> merchantsIn(ServerLevel level, SettlementData settlement) {
        return level.getEntitiesOfClass(Villager.class, settlement.area(),
                v -> !v.isBaby() && isMerchant(v) && settlement.covers(v.blockPosition()));
    }

    /** Make merchants of unemployed residents, one per free counter, in villages and up. */
    public static int recruit(ServerLevel level, SettlementData settlement) {
        return GuardRecruitment.handOut(level, settlement, TerraTownsRegistries.MARKET_COUNTER_POI_KEY,
                TerraTownsRegistries.MERCHANT_PROFESSION.get());
    }

    // --- levels -----------------------------------------------------------------

    public static int level(Villager merchant) {
        return Math.max(1, Math.min(5, merchant.getVillagerData().getLevel()));
    }

    /** Two slots per level: two goods on sale at Novice, ten at Master. */
    public static int slots(int level) {
        return 2 * level;
    }

    /**
     * The highest level a merchant in a {@code tier} settlement reaches (user, 2026-11-05):
     * Apprentice in a village, Expert in a town, Master in a city.
     */
    public static int maxLevel(SettlementTier tier) {
        return switch (tier) {
            case HAMLET -> 1;
            case VILLAGE -> 2;
            case TOWN -> 4;
            case CITY -> 5;
        };
    }

    /** May this merchant level up now? Vanilla's own check, capped by its settlement's tier. */
    public static boolean mayLevelUp(Villager merchant) {
        if (!(merchant.level() instanceof ServerLevel level)) {
            return true;
        }
        SettlementData s = settlementOf(level, merchant);
        return level(merchant) < maxLevel(s == null ? SettlementTier.VILLAGE : s.tier());
    }

    /** Goods of the merchant's level or below. */
    public static boolean canSell(Villager merchant, MerchantStock.Entry e) {
        return !(merchant.level() instanceof ServerLevel level) || ItemLevels.of(level, e.item()) <= level(merchant);
    }

    public static int xpFor(int itemLevel) {
        return XP_BY_LEVEL[Math.max(1, Math.min(5, itemLevel)) - 1];
    }

    // --- what a merchant may sell --------------------------------------------

    /**
     * The goods a worker's offer makes and what it asks for them, or null if it isn't a worker
     * selling goods: the result must be an item for emeralds alone (no book-plus-emeralds
     * enchanting, nothing a villager buys from you). Cobblemon's items are the Pokémart's, not
     * the Merchant's (user, 2026-10-16).
     */
    @Nullable
    public static MerchantPrice workerPrice(MerchantOffer offer) {
        ItemStack result = offer.getResult();
        ItemCost costA = offer.getItemCostA();
        if (result.isEmpty() || result.is(Items.EMERALD) || !costA.itemStack().is(Items.EMERALD)
                || offer.getItemCostB().isPresent() || !sellable(result)) {
            return null;
        }
        return new MerchantPrice(Math.max(1, costA.count()), result.getCount());
    }

    /** Not a Cobblemon item: those belong to the Pokémart. */
    public static boolean sellable(ItemStack stack) {
        return sellable(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    public static boolean sellable(net.minecraft.resources.ResourceLocation itemId) {
        return !"cobblemon".equals(itemId.getNamespace());
    }

    /** A worker whose overstock a merchant takes: in a job, and not a merchant itself. */
    public static boolean isWorker(Villager v) {
        if (v.isBaby() || !SettlementPromotion.isResident(v) || isMerchant(v)) {
            return false;
        }
        SettlementJob job = ProfessionJobs.jobOf(v.getVillagerData().getProfession());
        return job != null && job != SettlementJob.MERCHANT;
    }

    // --- stock in ----------------------------------------------------------

    /**
     * {@code worker} is about to restock (vanilla's restock, or the once-a-day hand-over): send what
     * players didn't buy of its current stock to the settlement's merchants, the one with the most
     * room for it first. Nothing goes anywhere without a merchant to take it.
     *
     * @return how many items the merchants took in
     */
    public static int captureOverstock(ServerLevel level, Villager worker) {
        worker.setData(TerraTownsRegistries.OVERSTOCK_DAY.get(), level.getDayTime() / 24000L);
        if (!isWorker(worker)) {
            return 0;
        }
        SettlementData settlement = SettlementManager.get(level).containing(worker.blockPosition());
        if (settlement == null || settlement.tier().ordinal() < SettlementTier.VILLAGE.ordinal()) {
            return 0;
        }
        List<Villager> merchants = merchantsIn(level, settlement);
        if (merchants.isEmpty()) {
            return 0;
        }
        return captureInto(worker, merchants);
    }

    /** The hand-over itself, for callers that already know the merchants (and the harness). */
    public static int captureInto(Villager worker, List<Villager> merchants) {
        UUID id = worker.getUUID();
        int moved = 0;
        ServerLevel level = (ServerLevel) worker.level();
        for (MerchantOffer offer : worker.getOffers()) {
            MerchantPrice price = workerPrice(offer);
            if (price == null) {
                continue;
            }
            ItemStack item = offer.getResult();
            // Goods above a merchant's level stay with the worker (user, 2026-11-05).
            int itemLevel = ItemLevels.of(level, item);
            int lot = item.getCount();
            int unsold = Math.max(0, offer.getMaxUses() - offer.getUses()) * lot;
            int restock = offer.getMaxUses() * lot;
            while (unsold > 0) {
                Villager best = null;
                int bestRoom = 0;
                for (Villager m : merchants) {
                    if (level(m) < itemLevel) {
                        continue;
                    }
                    int room = stock(m).room(id, item, restock);
                    if (room > bestRoom) {
                        best = m;
                        bestRoom = room;
                    }
                }
                if (best == null) {
                    break; // every merchant is full of this worker's goods: the rest is lost
                }
                int taken = stock(best).receive(id, item, unsold, restock, price);
                if (taken <= 0) {
                    break;
                }
                unsold -= taken;
                moved += taken;
            }
        }
        return moved;
    }

    /**
     * The once-a-day hand-over for workers vanilla didn't restock today (nobody bought from them).
     * Only villages with a merchant pay for the scan.
     */
    public static int dailyOverstock(ServerLevel level, SettlementData settlement, List<Villager> merchants) {
        if (merchants.isEmpty()) {
            return 0;
        }
        long today = level.getDayTime() / 24000L;
        int moved = 0;
        for (Villager v : level.getEntitiesOfClass(Villager.class, settlement.area(),
                v -> settlement.covers(v.blockPosition()))) {
            if (isWorker(v) && v.getData(TerraTownsRegistries.OVERSTOCK_DAY.get()) < today) {
                v.setData(TerraTownsRegistries.OVERSTOCK_DAY.get(), today);
                moved += captureInto(v, merchants);
                // The worker has restocked: its unsold goods went to the merchant, its stall is full.
                for (MerchantOffer offer : v.getOffers()) {
                    offer.resetUses();
                }
            }
        }
        return moved;
    }

    // --- stock out: trades ---------------------------------------------------

    /**
     * Rebuild a merchant's offers from its slots, in slot order: each slot's goods at their current
     * price, usable as many times as the stock allows (sold out shows as vanilla's crossed-out
     * trade). No reputation discount (price multiplier 0): the price is the owner's to set. Empty
     * slots first take goods as they come in; each purchase gives the XP vanilla gives for goods
     * of that level.
     */
    public static void refreshOffers(Villager merchant) {
        MerchantStock stock = stock(merchant);
        int level = level(merchant);
        int slots = slots(level);
        ServerLevel server = merchant.level() instanceof ServerLevel sl ? sl : null;
        stock.fillSlots(slots, e -> canSell(merchant, e));
        MerchantOffers offers = new MerchantOffers();
        for (int slot = 0; slot < slots; slot++) {
            MerchantStock.Entry e = stock.inSlot(slot);
            if (e == null) {
                continue;
            }
            int itemLevel = server == null ? 1 : ItemLevels.of(server, e.item());
            MerchantPrice price = e.price();
            int lots = e.total() / price.items();
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, price.emeralds()), Optional.empty(),
                    e.item().copyWithCount(price.items()), 0, lots, xpFor(itemLevel), 0.0F));
        }
        merchant.setOffers(offers);
    }

    /**
     * A player bought from a merchant: take the goods out of stock, add the emeralds made. The
     * owner's own purchases make no emeralds: they would only come back to the owner through
     * "emeralds made", which made everything on the stall free to them.
     */
    public static void onTrade(Villager merchant, MerchantOffer offer, boolean byOwner) {
        MerchantStock stock = stock(merchant);
        MerchantStock.Entry e = stock.entry(offer.getResult());
        if (e == null) {
            return;
        }
        ItemStack paid = offer.getCostA();
        stock.sell(e, offer.getResult().getCount(), !byOwner && paid.is(Items.EMERALD) ? paid.getCount() : 0);
    }

    /**
     * Sales to the village's other villagers since the last scan: a chance every
     * {@link #PASSIVE_INTERVAL} ticks, caught up for up to a day after the stall was unloaded. Each
     * sale is one lot of a listed item at its price, so stock goes down and emeralds come in.
     *
     * @return how many lots sold
     */
    public static int passiveSales(ServerLevel level, SettlementData settlement, Villager merchant) {
        MerchantStock stock = stock(merchant);
        long now = level.getGameTime();
        if (stock.passiveSalesAt() < 0 || stock.passiveSalesAt() > now) {
            stock.setPassiveSalesAt(now);
            return 0;
        }
        long chances = (now - stock.passiveSalesAt()) / PASSIVE_INTERVAL;
        if (chances <= 0 || merchant.isTrading()) {
            return 0;
        }
        stock.setPassiveSalesAt(stock.passiveSalesAt() + chances * PASSIVE_INTERVAL);
        int buyers = 0;
        for (Villager v : level.getEntitiesOfClass(Villager.class, settlement.area(),
                v -> settlement.covers(v.blockPosition()))) {
            if (!v.isBaby() && SettlementPromotion.isResident(v) && !isMerchant(v)) {
                buyers++;
            }
        }
        return sellPassively(stock, (int) Math.min(chances, PASSIVE_CATCH_UP), buyers, merchant.getRandom());
    }

    /** The dice of {@link #passiveSales}, apart from the world so the harness can roll them. */
    public static int sellPassively(MerchantStock stock, int chances, int buyers,
                                    net.minecraft.util.RandomSource random) {
        int sold = 0;
        for (int i = 0; i < chances && buyers > 0; i++) {
            if (random.nextInt(PASSIVE_FULL_VILLAGE) >= buyers) {
                continue;
            }
            List<MerchantStock.Entry> forSale = new ArrayList<>();
            for (MerchantStock.Entry e : stock.entries()) {
                if (e.listed() && e.total() >= e.price().items()) {
                    forSale.add(e);
                }
            }
            if (forSale.isEmpty()) {
                break;
            }
            MerchantStock.Entry e = forSale.get(random.nextInt(forSale.size()));
            MerchantPrice price = e.price();
            stock.sell(e, price.items(), price.emeralds());
            sold++;
        }
        return sold;
    }

    // --- the owner -----------------------------------------------------------

    /** The settlement {@code merchant} stands in, or null. */
    @Nullable
    public static SettlementData settlementOf(ServerLevel level, Villager v) {
        return SettlementManager.get(level).containing(v.blockPosition());
    }

    /** Does {@code player} own the settlement this merchant works in? */
    public static boolean isOwner(ServerLevel level, Player player, Villager merchant) {
        SettlementData s = settlementOf(level, merchant);
        return s != null && s.isOwnedBy(player.getUUID());
    }

    /**
     * Who trades with whom: the player with influence over a settlement trades with every
     * villager in it; anyone else only with its merchants (user, 2026-10-16). A settlement nobody
     * holds yet (a village whose gym is unbeaten, a wild hamlet) has no one to answer to, so it
     * trades as vanilla villages do; otherwise a player could trade with nobody at all before their
     * first gym. Villagers outside any settlement, and the mod's trainers, are never restricted.
     */
    public static boolean canTrade(ServerLevel level, Player player, Villager villager) {
        if (!SettlementPromotion.isResident(villager) || isMerchant(villager)) {
            return true;
        }
        VillagerProfession p = villager.getVillagerData().getProfession();
        SettlementJob job = ProfessionJobs.jobOf(p);
        if (job == null || job == SettlementJob.GUARD) {
            return true; // no trades to restrict: unemployed, nitwit, gym leader, guard
        }
        SettlementData s = settlementOf(level, villager);
        if (s == null || s.ownerId() == null) {
            return true;
        }
        return s.isOwnedBy(player.getUUID()) || InfluenceTracker.get(level).hasInfluence(player.getUUID(), s.id());
    }

    /**
     * Move up to a stack of the merchant's emeralds into the owner's inventory at a time; what
     * doesn't fit stays in "emeralds made".
     *
     * @return how many emeralds moved
     */
    public static int takeEmeralds(Player owner, Villager merchant) {
        MerchantStock stock = stock(merchant);
        int moved = 0;
        while (stock.emeraldsMade() > 0) {
            int n = Math.min(64, stock.emeraldsMade());
            ItemStack emeralds = new ItemStack(Items.EMERALD, n);
            owner.getInventory().add(emeralds);
            int added = n - emeralds.getCount();
            if (added <= 0) {
                break;
            }
            stock.takeEmeralds(added);
            moved += added;
        }
        return moved;
    }

    // --- the counter -----------------------------------------------------------

    /** The merchant whose job site is the counter at {@code counter}, or null. */
    @Nullable
    public static Villager atCounter(ServerLevel level, BlockPos counter) {
        GlobalPos site = GlobalPos.of(level.dimension(), counter);
        for (Villager v : level.getEntitiesOfClass(Villager.class, new AABB(counter).inflate(48), Merchants::isMerchant)) {
            if (v.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(site::equals).isPresent()) {
                return v;
            }
        }
        return null;
    }

    /** Working or idle in the day: at the counter. Resting, meeting, panicking, hiding: free. */
    public static boolean staysAtCounter(@Nullable Activity activity) {
        return activity == Activity.WORK || activity == Activity.IDLE;
    }

    /**
     * Keep a merchant at its stall (user, 2026-11-05): while it's working or idle in the day and
     * has strayed more than {@link #STAY_RADIUS} from its counter, send it back. At night (rest)
     * and at the afternoon gathering (meet) it goes where every villager goes; panicking from a
     * zombie is never overruled.
     *
     * @return true if it was sent back
     */
    public static boolean keepNear(Villager merchant) {
        Brain<Villager> brain = merchant.getBrain();
        if (!staysAtCounter(brain.getActiveNonCoreActivity().orElse(null))) {
            return false;
        }
        GlobalPos site = brain.getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (site == null || site.dimension() != merchant.level().dimension()
                || merchant.blockPosition().closerThan(site.pos(), STAY_RADIUS)) {
            return false;
        }
        brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(site.pos(), 0.5F, 2));
        return true;
    }

    // --- the scan --------------------------------------------------------------

    /** One settlement scan's merchant work: recruit, hand over today's overstock, passive sales. */
    public static void tick(ServerLevel level, SettlementData settlement) {
        if (settlement.tier().ordinal() < SettlementTier.VILLAGE.ordinal()) {
            return;
        }
        recruit(level, settlement);
        List<Villager> merchants = merchantsIn(level, settlement);
        if (merchants.isEmpty()) {
            return;
        }
        dailyOverstock(level, settlement, merchants);
        for (Villager m : merchants) {
            passiveSales(level, settlement, m);
            keepNear(m);
        }
    }
}
