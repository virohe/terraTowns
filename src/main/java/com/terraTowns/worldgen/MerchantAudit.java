package com.terraTowns.worldgen;

import com.terraTowns.merchant.ItemLevels;
import com.terraTowns.merchant.MerchantHooks;
import com.terraTowns.merchant.MerchantPrice;
import com.terraTowns.merchant.MerchantStock;
import com.terraTowns.merchant.Merchants;
import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.settlement.JobBoard;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementJob;
import com.terraTowns.settlement.SettlementManager;
import com.terraTowns.settlement.SettlementTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Merchant (0.6, DESIGN.md §2.8), end to end in the harness world: the price rule on the
 * user's own examples, a counter handing a villager the job in a village but not a hamlet, the
 * overstock rules (unsold goods only, the 8-restock cap per worker, the once-a-day hand-over and
 * no double hand-over), vanilla's restock going through the mixin, trades taking stock and making
 * emeralds through the real {@code notifyTrade} path, taking them, passive sales, who may trade
 * with whom, and the stock surviving a save. Everything it adds is taken away again.
 */
final class MerchantAudit {

    private MerchantAudit() {
    }

    static Map<String, Object> run(ServerLevel level, SettlementManager manager) {
        Map<String, Object> verdict = new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        prices(verdict);

        verdict.put("professionRegistered", BuiltInRegistries.VILLAGER_PROFESSION
                .getOptional(ResourceLocation.fromNamespaceAndPath("terra_towns", "merchant")).isPresent());
        verdict.put("staffable", SettlementJob.MERCHANT.isStaffable());
        verdict.put("workstationIsCounter", JobBoard.workstations(level, SettlementJob.MERCHANT).stream()
                .anyMatch(st -> st.is(TerraTownsRegistries.MARKET_COUNTER_ITEM.get())));
        verdict.put("notVanillaClaimable", level.registryAccess().registryOrThrow(Registries.POINT_OF_INTEREST_TYPE)
                .getHolder(TerraTownsRegistries.MARKET_COUNTER_POI_KEY)
                .map(h -> !h.is(PoiTypeTags.ACQUIRABLE_JOB_SITE)).orElse(false));

        // Every settlement's centre is on its own ground: the staged villagers stand there, and
        // every merchant rule finds a villager's settlement by position. A hamlet whose well landed
        // away from its site used to fail this (one in eight), and these checks with it.
        List<String> centres = new ArrayList<>();
        for (SettlementData o : manager.all()) {
            BlockPos c = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, o.center());
            SettlementData found = manager.containing(c);
            if (found != o) {
                centres.add(o.name() + " " + o.center().toShortString() + " -> "
                        + (found == null ? "nothing" : found.name() + " " + found.center().toShortString()));
            }
        }
        out.put("centresOffTheirGround", centres);
        verdict.put("everyCentreOnItsOwnGround", centres.isEmpty());
        SettlementData s = manager.all().iterator().next();
        SettlementTier tierBefore = s.tier();
        UUID ownerBefore = s.ownerId();
        BlockPos counter = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, s.center().offset(-3, 0, -3));
        List<Villager> spawned = new ArrayList<>();
        Villager merchant = null;
        try {
            level.setBlock(counter, TerraTownsRegistries.MARKET_COUNTER.get().defaultBlockState(), 3);
            // One villager free to take the counter, so recruitment never depends on who's idle.
            spawned.add(villager(level, s, VillagerProfession.NONE));

            s.setTier(SettlementTier.HAMLET);
            verdict.put("keptOutOfHamlet", Merchants.recruit(level, s) == 0 && Merchants.merchantsIn(level, s).isEmpty());
            s.setTier(SettlementTier.VILLAGE);
            Merchants.recruit(level, s);
            List<Villager> merchants = Merchants.merchantsIn(level, s);
            verdict.put("recruitedInVillage", merchants.size() == 1);
            if (merchants.size() != 1) {
                out.put("merchantsFound", merchants.size());
                out.put("verdict", verdict);
                return out;
            }
            merchant = merchants.get(0);
            Villager m = merchant;
            verdict.put("holdsTheCounter", m.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                    .map(g -> g.pos().equals(counter)).orElse(false));
            JobBoard.refresh(level, s);
            JobBoard.restaffUnchecked(level, s);
            verdict.put("merchantAppointed", m.getUUID().equals(s.jobHolder(SettlementJob.MERCHANT)));
            MerchantStock stock = Merchants.stock(m);
            verdict.put("counterFindsItsMerchant", Merchants.atCounter(level, counter) == m);

            // Goods have levels (user, 2026-11-05): a farmer's bread is Novice, pumpkin pie
            // Apprentice, cookies Journeyman, golden carrots Master. A Novice merchant takes none
            // of the golden carrots; they stay with the farmer.
            verdict.put("itemLevelsFromTradeTables", ItemLevels.of(level, new ItemStack(Items.BREAD)) == 1
                    && ItemLevels.of(level, new ItemStack(Items.PUMPKIN_PIE)) == 2
                    && ItemLevels.of(level, new ItemStack(Items.COOKIE)) == 3
                    && ItemLevels.of(level, new ItemStack(Items.GOLDEN_CARROT)) == 5
                    && ItemLevels.known(level, new ItemStack(Items.BREAD)));
            Villager carrots = worker(level, s, spawned, Items.GOLDEN_CARROT, 3, 1, 2, 8);
            verdict.put("noviceRefusesHigherGoods", Merchants.level(m) == 1
                    && Merchants.captureInto(carrots, List.of(m)) == 0
                    && stock.entry(new ItemStack(Items.GOLDEN_CARROT)) == null
                    && carrots.getOffers().get(0).getUses() == 2);
            // The rest of the stock tests use goods of every level: a Master takes them all.
            m.setVillagerData(m.getVillagerData().setLevel(5));

            // "A farmer selling 8 bread who sold 2 sends 6" (user's example; cookies here).
            Villager cookies = worker(level, s, spawned, Items.COOKIE, 1, 1, 2, 8);
            Merchants.captureInto(cookies, List.of(m));
            verdict.put("unsoldSixOfEightGoToMerchant", total(stock, Items.COOKIE) == 6);

            // Bread at 2, 4 and 5 emeralds from three farmers, each restocking 8: the merchant holds
            // 64 from each (192) and asks the top price plus 10%, rounded up: 6.
            List<Villager> bakers = List.of(
                    worker(level, s, spawned, Items.BREAD, 2, 1, 0, 8),
                    worker(level, s, spawned, Items.BREAD, 4, 1, 0, 8),
                    worker(level, s, spawned, Items.BREAD, 5, 1, 0, 8));
            for (int i = 0; i < 10; i++) {
                for (Villager b : bakers) {
                    Merchants.captureInto(b, List.of(m));
                }
            }
            MerchantStock.Entry bread = stock.entry(new ItemStack(Items.BREAD));
            verdict.put("heldUpTo8RestocksPerWorker", total(stock, Items.BREAD) + " / 192");
            verdict.put("defaultIsTopWorkerPricePlus10", bread != null && bread.price().equals(new MerchantPrice(6, 1)));

            // Not for sale: Cobblemon's items (the Pokémart's), enchanting with a book, things a
            // villager buys from you.
            Optional<Item> pokeBall = BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("cobblemon", "poke_ball"));
            // By id, so it is tested even where Cobblemon isn't loaded (the dev harness).
            verdict.put("cobblemonItemsNotSold", !Merchants.sellable(ResourceLocation.fromNamespaceAndPath("cobblemon", "poke_ball"))
                    && pokeBall.map(i -> !Merchants.sellable(new ItemStack(i))).orElse(true)
                    && Merchants.sellable(ResourceLocation.withDefaultNamespace("bread")));
            out.put("cobblemonLoaded", pokeBall.isPresent());
            verdict.put("twoCostOfferNotSold", Merchants.workerPrice(new MerchantOffer(new ItemCost(Items.EMERALD, 5),
                    Optional.of(new ItemCost(Items.BOOK)), new ItemStack(Items.ENCHANTED_BOOK), 12, 1, 0.05F)) == null);
            verdict.put("buyingOfferNotSold", Merchants.workerPrice(new MerchantOffer(new ItemCost(Items.WHEAT, 20),
                    new ItemStack(Items.EMERALD), 16, 2, 0.05F)) == null);

            // Its trades are its stock.
            Merchants.refreshOffers(m);
            MerchantOffer breadOffer = offerFor(m, Items.BREAD);
            verdict.put("offersItsStock", breadOffer != null && breadOffer.getCostA().is(Items.EMERALD)
                    && breadOffer.getCostA().getCount() == 6 && breadOffer.getResult().getCount() == 1
                    && breadOffer.getMaxUses() == 192 && offerFor(m, Items.COOKIE) != null);
            // A purchase gives the XP vanilla gives for goods of that level.
            MerchantOffer cookieOffer = offerFor(m, Items.COOKIE);
            verdict.put("purchaseGivesXpByLevel", breadOffer != null && breadOffer.getXp() == Merchants.xpFor(1)
                    && cookieOffer != null && cookieOffer.getXp() == Merchants.xpFor(3) && Merchants.xpFor(1) > 0);

            // The owner may lower a price, never below the cheapest worker (2), never above the default.
            if (bread != null) {
                for (int i = 0; i < 10; i++) {
                    stock.setOwnerPrice(bread, bread.price().cheaper(64));
                }
                verdict.put("ownerLowersToCheapestWorker", bread.price().equals(new MerchantPrice(2, 1)));
                stock.setOwnerPrice(bread, new MerchantPrice(9, 1));
                verdict.put("ownerCantRaiseAboveDefault", bread.price().equals(new MerchantPrice(6, 1)) && !bread.ownerSetPrice());
                stock.setOwnerPrice(bread, new MerchantPrice(4, 1));
                verdict.put("ownerPriceSticks", bread.price().equals(new MerchantPrice(4, 1)));
                stock.setOwnerPrice(bread, (MerchantPrice) null);
            }

            // A sale through vanilla's own notifyTrade (which posts NeoForge's trade event).
            Merchants.refreshOffers(m);
            breadOffer = offerFor(m, Items.BREAD);
            int emeraldsBefore = stock.emeraldsMade();
            if (breadOffer != null) {
                m.notifyTrade(breadOffer);
            }
            verdict.put("tradeTakesStock", total(stock, Items.BREAD) + " / 191");
            verdict.put("tradeMakesEmeralds", (stock.emeraldsMade() - emeraldsBefore) + " / 6");

            // The owner's own purchase takes stock but makes no emeralds (they'd come straight back).
            FakePlayer buyer = FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(
                    UUID.nameUUIDFromBytes("terra-towns-merchant-audit-buyer".getBytes(StandardCharsets.UTF_8)), "[MerchantBuyer]"));
            s.setOwnerId(buyer.getUUID());
            Merchants.refreshOffers(m);
            breadOffer = offerFor(m, Items.BREAD);
            int madeBeforeOwner = stock.emeraldsMade();
            if (breadOffer != null) {
                m.setTradingPlayer(buyer);
                m.notifyTrade(breadOffer);
                m.setTradingPlayer(null);
            }
            verdict.put("ownerPurchaseMakesNoEmeralds", total(stock, Items.BREAD) == 190
                    && stock.emeraldsMade() == madeBeforeOwner);
            s.setOwnerId(ownerBefore);

            // A merchant never restocks itself: its offers are rebuilt from what it holds.
            m.restock();
            breadOffer = offerFor(m, Items.BREAD);
            verdict.put("merchantRestockIsItsStock", breadOffer != null && breadOffer.getUses() == 0
                    && breadOffer.getMaxUses() == total(stock, Items.BREAD));

            // The owner takes the emeralds made.
            FakePlayer owner = FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(
                    UUID.nameUUIDFromBytes("terra-towns-merchant-audit".getBytes(StandardCharsets.UTF_8)), "[MerchantAudit]"));
            owner.getInventory().clearContent();
            int made = stock.emeraldsMade();
            int moved = Merchants.takeEmeralds(owner, m);
            verdict.put("ownerTakesEmeralds", made > 0 && moved == made && stock.emeraldsMade() == 0
                    && owner.getInventory().countItem(Items.EMERALD) == made);
            owner.getInventory().clearContent();

            // Goods the owner keeps aren't offered.
            MerchantStock.Entry cookie = stock.entry(new ItemStack(Items.COOKIE));
            if (cookie != null) {
                stock.setListed(cookie, false, 10);
                Merchants.refreshOffers(m);
                verdict.put("keptGoodsNotOffered", offerFor(m, Items.COOKIE) == null && offerFor(m, Items.BREAD) != null);
                stock.setListed(cookie, true, 10);
            }

            // Passive sales to the village's other villagers: a full village buys at every
            // chance, an empty one never.
            int breadBefore = total(stock, Items.BREAD);
            int emeraldsPassive = stock.emeraldsMade();
            if (cookie != null) {
                stock.setListed(cookie, false, 10);
            }
            int sold = Merchants.sellPassively(stock, 10, Merchants.PASSIVE_FULL_VILLAGE, RandomSource.create(1));
            verdict.put("passiveSalesUseStock", sold + " / 10");
            verdict.put("passiveSalesEarn", breadBefore - total(stock, Items.BREAD) == 10
                    && stock.emeraldsMade() - emeraldsPassive == 60);
            verdict.put("noBuyersNoSales", Merchants.sellPassively(stock, 10, 0, RandomSource.create(1)) == 0);
            if (cookie != null) {
                stock.setListed(cookie, true, 10);
            }

            // Vanilla's restock (through the mixin): a pie baker who sold 3 of 8 sends 5.
            Villager pies = worker(level, s, spawned, Items.PUMPKIN_PIE, 1, 1, 3, 8);
            pies.restock();
            verdict.put("vanillaRestockSendsOverstock", total(stock, Items.PUMPKIN_PIE) == 5
                    && pies.getOffers().get(0).getUses() == 0);

            // The daily hand-over for a worker nobody bought from, once a day only; and vanilla's
            // turn-of-day restock doesn't send the same goods again.
            Villager apples = worker(level, s, spawned, Items.APPLE, 1, 4, 0, 4);
            Merchants.dailyOverstock(level, s, List.of(m));
            int applesOnce = total(stock, Items.APPLE);
            Merchants.dailyOverstock(level, s, List.of(m));
            MerchantHooks.beforeRestock(apples);
            verdict.put("dailyHandOverOnceADay", applesOnce + " / " + total(stock, Items.APPLE));
            verdict.put("dailyHandOverWholeStock", applesOnce == 16);
            apples.getOffers().get(0).increaseUses();
            MerchantHooks.beforeRestock(apples);
            verdict.put("salesSinceHandOverStillSent", total(stock, Items.APPLE) == 16 + 12);

            // Who trades with whom.
            Villager baker = bakers.get(0);
            s.setOwnerId(UUID.nameUUIDFromBytes("terra-towns-merchant-audit-owner".getBytes(StandardCharsets.UTF_8)));
            verdict.put("outsiderRefusedByWorker", !Merchants.canTrade(level, owner, baker));
            verdict.put("outsiderTradesWithMerchant", Merchants.canTrade(level, owner, m));
            s.setOwnerId(owner.getUUID());
            verdict.put("ownerTradesWithWorker", Merchants.canTrade(level, owner, baker));
            s.setOwnerId(null);
            verdict.put("unheldSettlementTradesFreely", Merchants.canTrade(level, owner, baker));

            // Two slots per level: a Novice sells two goods, its own level's only; a third can't go
            // on sale until something comes off.
            m.setVillagerData(m.getVillagerData().setLevel(1));
            stock.receive(UUID.randomUUID(), new ItemStack(Items.WHEAT), 8, 8, new MerchantPrice(1, 1));
            stock.receive(UUID.randomUUID(), new ItemStack(Items.BEETROOT), 8, 8, new MerchantPrice(1, 1));
            // Everything on sale, as at a Master: dropping to Novice has to take most of it off.
            for (MerchantStock.Entry e : stock.entries()) {
                stock.setListed(e, true, 10);
            }
            Merchants.refreshOffers(m);
            boolean onlyNovice = m.getOffers().stream()
                    .allMatch(o -> ItemLevels.of(level, o.getResult()) <= 1);
            MerchantStock.Entry third = null;
            for (MerchantStock.Entry e : stock.entries()) {
                if (!e.listed() && ItemLevels.of(level, e.item()) == 1 && e.total() > 0) {
                    third = e;
                }
            }
            verdict.put("noviceHasTwoSlots", stock.listedCount() == 2 && m.getOffers().size() <= 2 && onlyNovice
                    && third != null && !stock.setListed(third, true, Merchants.slots(1)));
            // The stall's slots (2026-11-05): the owner picks what each slot sells; what it
            // replaces stops selling and stays out of empty slots; empty slots take new goods;
            // the randomizer fills only empty slots, or with Shift every slot.
            MerchantStock slots = new MerchantStock();
            for (Item it : List.of(Items.BREAD, Items.APPLE, Items.COOKIE, Items.CARROT)) {
                slots.receive(UUID.randomUUID(), new ItemStack(it), 8, 8, new MerchantPrice(1, 1));
            }
            MerchantStock.Entry sBread = slots.entry(new ItemStack(Items.BREAD));
            MerchantStock.Entry sApple = slots.entry(new ItemStack(Items.APPLE));
            MerchantStock.Entry sCookie = slots.entry(new ItemStack(Items.COOKIE));
            MerchantStock.Entry sCarrot = slots.entry(new ItemStack(Items.CARROT));
            slots.fillSlots(2);
            boolean filledInOrder = sBread.slot() == 0 && sApple.slot() == 1 && !sCookie.listed();
            boolean picked = slots.setSlot(sCarrot, 0, 2, true) && sCarrot.slot() == 0 && !sBread.listed()
                    && sBread.kept() && !slots.setSlot(sCookie, 2, 2, true) && !slots.setSlot(sCookie, 1, 2, false);
            boolean cleared = slots.clearSlot(1) && !sApple.listed() && slots.inSlot(1) == null;
            slots.fillSlots(2);
            boolean emptyTakesNew = slots.inSlot(1) == sCookie;
            slots.clearSlot(1);
            int filled = slots.randomize(2, e -> true, RandomSource.create(5), false);
            boolean fillsEmptyOnly = filled == 1 && sCarrot.slot() == 0 && slots.inSlot(1) != null;
            boolean rerollsAll = slots.randomize(2, e -> true, RandomSource.create(6), true) == 2
                    && slots.listedCount() == 2;
            boolean randomizeRespectsLevel = slots.randomize(4, e -> e != sCookie, RandomSource.create(7), true) == 3
                    && !sCookie.listed();
            verdict.put("slotsFillInOrder", filledInOrder);
            verdict.put("ownerPicksSlotGoods", picked);
            verdict.put("stopSellingEmptiesSlot", cleared);
            verdict.put("emptySlotTakesNewGoods", emptyTakesNew);
            verdict.put("randomizeFillsEmptySlots", fillsEmptyOnly);
            verdict.put("shiftRandomizeRerollsAll", rerollsAll && randomizeRespectsLevel);
            CompoundTag slotTag = slots.serializeNBT(level.registryAccess());
            MerchantStock slotsBack = new MerchantStock();
            slotsBack.deserializeNBT(level.registryAccess(), slotTag);
            verdict.put("slotsSurviveSave", slotsBack.listedCount() == slots.listedCount()
                    && slotsBack.inSlot(0) != null && slotsBack.inSlot(0).item().is(slots.inSlot(0).item().getItem()));

            // A Novice merchant's stall shows only goods it can sell (cookies are Journeyman).
            com.terraTowns.network.MerchantInfoPacket shown = com.terraTowns.network.MerchantInfoPacket.of(m, true);
            verdict.put("aboveLevelGoodsHidden", Merchants.level(m) == 1 && !shown.rows().isEmpty()
                    && shown.rows().stream().noneMatch(r -> r.item().is(Items.COOKIE))
                    && stock.entry(new ItemStack(Items.COOKIE)) != null);

            // Its trades are its slots, in slot order.
            m.setVillagerData(m.getVillagerData().setLevel(5));
            MerchantStock.Entry apple = stock.entry(new ItemStack(Items.APPLE));
            MerchantStock.Entry breadE = stock.entry(new ItemStack(Items.BREAD));
            boolean ordered = false;
            if (apple != null && breadE != null) {
                stock.setSlot(apple, 0, 10, true);
                stock.setSlot(breadE, 1, 10, true);
                Merchants.refreshOffers(m);
                ordered = m.getOffers().size() >= 2 && m.getOffers().get(0).getResult().is(Items.APPLE)
                        && m.getOffers().get(1).getResult().is(Items.BREAD);
            }
            verdict.put("tradesFollowSlotOrder", ordered);
            m.setVillagerData(m.getVillagerData().setLevel(1));
            verdict.put("slotsGrowWithLevel", Merchants.slots(1) == 2 && Merchants.slots(2) == 4 && Merchants.slots(5) == 10);

            // Levels come from purchases, capped by the settlement's tier: Apprentice in a
            // village, Expert in a town, Master in a city. Vanilla's own check, through the mixin.
            java.lang.reflect.Method shouldLevel = Villager.class.getDeclaredMethod("shouldIncreaseLevel");
            shouldLevel.setAccessible(true);
            m.setVillagerXp(500);
            s.setTier(SettlementTier.VILLAGE);
            m.setVillagerData(m.getVillagerData().setLevel(1));
            boolean villageOneUp = (boolean) shouldLevel.invoke(m);
            m.setVillagerData(m.getVillagerData().setLevel(2));
            boolean villageTwoStops = !(boolean) shouldLevel.invoke(m);
            s.setTier(SettlementTier.TOWN);
            boolean townTwoUp = (boolean) shouldLevel.invoke(m);
            m.setVillagerData(m.getVillagerData().setLevel(4));
            boolean townFourStops = !(boolean) shouldLevel.invoke(m);
            s.setTier(SettlementTier.VILLAGE);
            m.setVillagerXp(0);
            m.setVillagerData(m.getVillagerData().setLevel(5));
            verdict.put("levelCappedByTier", villageOneUp && villageTwoStops && townTwoUp && townFourStops
                    && Merchants.maxLevel(SettlementTier.CITY) == 5);

            // It keeps near its counter while working or idle, not at night or the gathering.
            net.minecraft.world.entity.ai.Brain<Villager> brain = m.getBrain();
            BlockPos away = counter.offset(Merchants.STAY_RADIUS + 6, 0, 0);
            m.moveTo(away.getX() + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, away.getX(), away.getZ()),
                    away.getZ() + 0.5);
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            brain.setActiveActivityIfPossible(net.minecraft.world.entity.schedule.Activity.WORK);
            boolean sentBack = Merchants.keepNear(m) && brain.getMemory(MemoryModuleType.WALK_TARGET)
                    .map(t -> t.getTarget().currentBlockPosition().equals(counter)).orElse(false);
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);
            // Vanilla won't switch a villager to meeting without a bell nearby, so the rule itself.
            boolean meetsFreely = !Merchants.staysAtCounter(net.minecraft.world.entity.schedule.Activity.MEET);
            boolean sleepsFreely = !Merchants.staysAtCounter(net.minecraft.world.entity.schedule.Activity.REST)
                    && !Merchants.staysAtCounter(net.minecraft.world.entity.schedule.Activity.PANIC)
                    && Merchants.staysAtCounter(net.minecraft.world.entity.schedule.Activity.IDLE);
            verdict.put("keepsNearCounterByDay", sentBack);
            verdict.put("freeAtGatheringAndNight", meetsFreely && sleepsFreely);
            brain.eraseMemory(MemoryModuleType.WALK_TARGET);

            // The stock saves and loads whole.
            CompoundTag saved = stock.serializeNBT(level.registryAccess());
            MerchantStock loaded = new MerchantStock();
            loaded.deserializeNBT(level.registryAccess(), saved);
            MerchantStock.Entry loadedBread = loaded.entry(new ItemStack(Items.BREAD));
            verdict.put("stockSurvivesSave", loaded.totalItems() == stock.totalItems()
                    && loaded.emeraldsMade() == stock.emeraldsMade() && loadedBread != null
                    && loadedBread.price().equals(new MerchantPrice(6, 1))
                    && loaded.entries().size() == stock.entries().size());
            out.put("stock", describe(stock));
        } catch (RuntimeException | ReflectiveOperationException e) {
            verdict.put("threw", false);
            out.put("exception", e.toString());
            com.terraTowns.TerraTowns.LOGGER.error("Merchant audit threw", e);
        } finally {
            for (Villager v : spawned) {
                v.discard();
            }
            level.setBlock(counter, Blocks.AIR.defaultBlockState(), 3);
            if (merchant != null && !spawned.contains(merchant)) {
                // One of the hamlet's own villagers took the counter: give it its old life back.
                merchant.removeData(TerraTownsRegistries.MERCHANT_STOCK.get());
                merchant.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
                merchant.setVillagerData(merchant.getVillagerData().setProfession(VillagerProfession.NONE));
                merchant.setOffers(new MerchantOffers());
                merchant.refreshBrain(level);
            }
            s.setOwnerId(ownerBefore);
            s.setTier(tierBefore);
            s.setJobHolder(SettlementJob.MERCHANT, null);
            JobBoard.refresh(level, s);
        }
        out.put("verdict", verdict);
        return out;
    }

    /** The price rule on the user's examples (DESIGN.md §2.8). */
    private static void prices(Map<String, Object> verdict) {
        verdict.put("fiveBecomesSix", new MerchantPrice(5, 1).markedUp(64).equals(new MerchantPrice(6, 1)));
        verdict.put("oneBecomesTwo", new MerchantPrice(1, 1).markedUp(64).equals(new MerchantPrice(2, 1)));
        verdict.put("fiftyAnEmeraldBecomes45", new MerchantPrice(1, 50).markedUp(64).equals(new MerchantPrice(1, 45)));
        verdict.put("breadSixForOneBecomesFive", new MerchantPrice(1, 6).markedUp(64).equals(new MerchantPrice(1, 5)));
        verdict.put("lotFitsAStack", new MerchantPrice(1, 50).markedUp(16).equals(new MerchantPrice(1, 16)));
        verdict.put("tenBecomesEleven", new MerchantPrice(10, 1).markedUp(64).equals(new MerchantPrice(11, 1)));
        verdict.put("stepsCheaperAndDearer", new MerchantPrice(2, 1).cheaper(64).equals(new MerchantPrice(1, 1))
                && new MerchantPrice(1, 1).cheaper(64).equals(new MerchantPrice(1, 2))
                && new MerchantPrice(1, 2).dearer().equals(new MerchantPrice(1, 1))
                && new MerchantPrice(1, 1).dearer().equals(new MerchantPrice(2, 1)));
    }

    private static Villager villager(ServerLevel level, SettlementData s, VillagerProfession profession) {
        Villager v = EntityType.VILLAGER.create(level);
        BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, s.center());
        v.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        v.setVillagerData(v.getVillagerData().setProfession(profession));
        v.setOffers(new MerchantOffers());
        level.addFreshEntity(v);
        return v;
    }

    /** A farmer whose one offer is {@code emeralds} for {@code count} of {@code item}. */
    private static Villager worker(ServerLevel level, SettlementData s, List<Villager> spawned, Item item,
                                   int emeralds, int count, int uses, int maxUses) {
        Villager v = villager(level, s, VillagerProfession.FARMER);
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, emeralds), Optional.empty(),
                new ItemStack(item, count), uses, maxUses, 1, 0.05F));
        v.setOffers(offers);
        spawned.add(v);
        return v;
    }

    private static int total(MerchantStock stock, Item item) {
        MerchantStock.Entry e = stock.entry(new ItemStack(item));
        return e == null ? 0 : e.total();
    }

    private static MerchantOffer offerFor(Villager merchant, Item item) {
        for (MerchantOffer o : merchant.getOffers()) {
            if (o.getResult().is(item)) {
                return o;
            }
        }
        return null;
    }

    private static List<String> describe(MerchantStock stock) {
        List<String> out = new ArrayList<>();
        for (MerchantStock.Entry e : stock.entries()) {
            out.add(BuiltInRegistries.ITEM.getKey(e.item().getItem()) + " x" + e.total() + " at "
                    + e.price().describe() + (e.listed() ? "" : " (kept)"));
        }
        out.add("emeralds made " + stock.emeraldsMade());
        return out;
    }
}
