package com.terraTowns.merchant;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.INBTSerializable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a Merchant holds (0.6, DESIGN.md §2.8), attached to the merchant villager itself so it
 * travels and saves with it: the goods it took in from the village's workers, the owner's
 * choices about them, and the emeralds it has made.
 *
 * <p>Goods are kept per item and per worker ({@link Share}), because the cap is per worker: "up
 * to 8x a worker's restock per worker and item" (three farmers each restocking 8 bread let the
 * merchant hold 192, not 64). Each share also remembers that worker's asking price, which is
 * where the Merchant's default and floor prices come from.</p>
 */
public final class MerchantStock implements INBTSerializable<CompoundTag> {

    /** How many restocks' worth of one item from one worker a merchant holds (user, 2026-10-16). */
    public static final int RESTOCKS_HELD = 8;

    /** One worker's part of an item's stock: how many, how big its restock is, what it asks. */
    public static final class Share {
        int count;
        int restock;
        MerchantPrice price;

        Share(int count, int restock, MerchantPrice price) {
            this.count = count;
            this.restock = restock;
            this.price = price;
        }

        public int count() {
            return count;
        }

        public int cap() {
            return RESTOCKS_HELD * restock;
        }
    }

    /** One item the merchant has had in stock. Kept at zero stock so the owner's choices stay. */
    public static final class Entry {
        final ItemStack item;
        final Map<UUID, Share> shares = new LinkedHashMap<>();
        /** The owner's lowered price, or null for the default. */
        @Nullable
        MerchantPrice ownerPrice;
        /**
         * The slot it's selling in, or -1 if it isn't selling. A merchant has two slots per
         * level (user, 2026-11-05), each holding one of its goods; its trades are its slots in
         * order. New goods fill empty slots as they arrive ({@link #fillSlots}).
         */
        int slot = -1;
        /** The owner took it out of its slot: empty slots don't take it back by themselves. */
        boolean kept;

        Entry(ItemStack item) {
            this.item = item.copyWithCount(1);
        }

        public ItemStack item() {
            return item;
        }

        /** Selling: in one of the merchant's slots. */
        public boolean listed() {
            return slot >= 0;
        }

        public int slot() {
            return slot;
        }

        public boolean kept() {
            return kept;
        }

        public int total() {
            int n = 0;
            for (Share s : shares.values()) {
                n += s.count;
            }
            return n;
        }

        /** The highest price any worker asks, or null if no worker's price is known. */
        @Nullable
        public MerchantPrice highestWorkerPrice() {
            MerchantPrice best = null;
            for (Share s : shares.values()) {
                if (best == null || s.price.dearerThan(best)) {
                    best = s.price;
                }
            }
            return best;
        }

        @Nullable
        public MerchantPrice lowestWorkerPrice() {
            MerchantPrice best = null;
            for (Share s : shares.values()) {
                if (best == null || s.price.cheaperThan(best)) {
                    best = s.price;
                }
            }
            return best;
        }

        /** The highest worker price plus 10%, rounded against the buyer. */
        public MerchantPrice defaultPrice() {
            MerchantPrice high = highestWorkerPrice();
            return high == null ? new MerchantPrice(1, 1) : high.markedUp(item.getMaxStackSize());
        }

        /** What the merchant asks now: the owner's price if they set one, else the default. */
        public MerchantPrice price() {
            MerchantPrice def = defaultPrice();
            if (ownerPrice == null) {
                return def;
            }
            // Workers' prices move (a new worker, a pricier one); the owner may only ever have
            // LOWERED the price, and never below the cheapest worker.
            MerchantPrice low = lowestWorkerPrice();
            if (ownerPrice.dearerThan(def)) {
                return def;
            }
            if (low != null && ownerPrice.cheaperThan(low)) {
                return low.normalised(item.getMaxStackSize());
            }
            return ownerPrice;
        }

        public boolean ownerSetPrice() {
            return ownerPrice != null;
        }

        /** Take {@code n} items out, from the largest shares first. @return how many came out. */
        int take(int n) {
            int left = n;
            List<Share> bySize = new ArrayList<>(shares.values());
            bySize.sort(Comparator.comparingInt((Share s) -> s.count).reversed());
            for (Share s : bySize) {
                int t = Math.min(left, s.count);
                s.count -= t;
                left -= t;
                if (left == 0) {
                    break;
                }
            }
            return n - left;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private int emeraldsMade;
    /** Game time passive sales were last settled up to; -1 until the first scan sees it. */
    private long passiveSalesAt = -1;

    public List<Entry> entries() {
        return entries;
    }

    @Nullable
    public Entry entry(ItemStack item) {
        for (Entry e : entries) {
            if (ItemStack.isSameItemSameComponents(e.item, item)) {
                return e;
            }
        }
        return null;
    }

    @Nullable
    public Entry entry(int index) {
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    /**
     * Take in a worker's unsold goods. Whatever would go past that worker's cap for the item is
     * turned away (it is simply gone, as vanilla's restock throws it away).
     *
     * @param restock that worker's full restock of the item, in items (max uses x lot size)
     * @return how many items were taken in
     */
    public int receive(UUID worker, ItemStack item, int count, int restock, MerchantPrice workerPrice) {
        if (count <= 0 || restock <= 0) {
            return 0;
        }
        Entry e = entry(item);
        if (e == null) {
            e = new Entry(item);
            entries.add(e);
        }
        Share s = e.shares.get(worker);
        if (s == null) {
            s = new Share(0, restock, workerPrice);
            e.shares.put(worker, s);
        }
        s.restock = restock;
        s.price = workerPrice;
        int taken = Math.max(0, Math.min(count, s.cap() - s.count));
        s.count += taken;
        return taken;
    }

    /** Room left for {@code worker}'s {@code item}, given its restock size. */
    public int room(UUID worker, ItemStack item, int restock) {
        Entry e = entry(item);
        Share s = e == null ? null : e.shares.get(worker);
        return RESTOCKS_HELD * restock - (s == null ? 0 : s.count);
    }

    /** A sale of {@code items} items for {@code emeralds}. @return items actually taken. */
    public int sell(Entry e, int items, int emeralds) {
        int taken = e.take(items);
        if (taken > 0) {
            emeraldsMade += emeralds;
        }
        return taken;
    }

    public int emeraldsMade() {
        return emeraldsMade;
    }

    /** Empty the "emeralds made" section. @return how many there were. */
    public int takeEmeralds(int max) {
        int n = Math.min(max, emeraldsMade);
        emeraldsMade -= n;
        return n;
    }

    public long passiveSalesAt() {
        return passiveSalesAt;
    }

    public void setPassiveSalesAt(long time) {
        passiveSalesAt = time;
    }

    /** Goods selling, each in a slot. */
    public int listedCount() {
        int n = 0;
        for (Entry e : entries) {
            if (e.listed()) {
                n++;
            }
        }
        return n;
    }

    /** The goods selling in {@code slot}, or null if it's empty. */
    @Nullable
    public Entry inSlot(int slot) {
        for (Entry e : entries) {
            if (e.slot == slot) {
                return e;
            }
        }
        return null;
    }

    public int indexOf(Entry e) {
        return entries.indexOf(e);
    }

    /**
     * The owner puts {@code e} in {@code slot}. Whatever sold there before stops selling (kept back,
     * so an empty slot won't take it again by itself); if {@code e} was in another slot, that one
     * is left empty.
     *
     * @param canSell whether the merchant can sell these goods at its level
     * @return false if the slot isn't one of the merchant's or it can't sell them
     */
    public boolean setSlot(Entry e, int slot, int slots, boolean canSell) {
        if (slot < 0 || slot >= slots || !canSell) {
            return false;
        }
        Entry was = inSlot(slot);
        if (was != null && was != e) {
            was.slot = -1;
            was.kept = true;
        }
        e.slot = slot;
        e.kept = false;
        return true;
    }

    /** The owner empties {@code slot}: its goods stop selling. @return false if it was empty. */
    public boolean clearSlot(int slot) {
        Entry e = inSlot(slot);
        if (e == null) {
            return false;
        }
        e.slot = -1;
        e.kept = true;
        return true;
    }

    /**
     * Selling or not, as one switch: on, into the first empty slot; off, out of its slot.
     *
     * @return false if it was to go on and there was no empty slot (or it can't be sold yet)
     */
    public boolean setListed(Entry e, boolean listed, int slots) {
        return setListed(e, listed, slots, true);
    }

    public boolean setListed(Entry e, boolean listed, int slots, boolean canSell) {
        if (!listed) {
            if (e.listed()) {
                clearSlot(e.slot);
            } else {
                e.kept = true;
            }
            return true;
        }
        if (e.listed()) {
            return true;
        }
        int free = firstEmpty(slots);
        return free >= 0 && setSlot(e, free, slots, canSell);
    }

    private int firstEmpty(int slots) {
        for (int i = 0; i < slots; i++) {
            if (inSlot(i) == null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Fit the slots to the merchant: goods past its slots, or above its level, stop selling (not
     * marked kept, so they come back when it levels up); then empty slots take goods the owner
     * hasn't kept back, in the order they came in. A fresh stall sells something.
     *
     * @return true if anything changed
     */
    public boolean fillSlots(int slots) {
        return fillSlots(slots, e -> true);
    }

    public boolean fillSlots(int slots, java.util.function.Predicate<Entry> canSell) {
        boolean changed = false;
        for (Entry e : entries) {
            if (e.listed() && (e.slot >= slots || !canSell.test(e) || inSlot(e.slot) != e)) {
                e.slot = -1;
                changed = true;
            }
        }
        for (int i = 0; i < slots; i++) {
            if (inSlot(i) != null) {
                continue;
            }
            for (Entry e : entries) {
                if (!e.listed() && !e.kept && e.total() > 0 && canSell.test(e)) {
                    e.slot = i;
                    changed = true;
                    break;
                }
            }
        }
        return changed;
    }

    /**
     * The randomizer: empty slots take random goods in stock that aren't selling yet, kept-back
     * goods included (the owner asked); with {@code all}, every slot is emptied first.
     *
     * @return how many slots it filled
     */
    public int randomize(int slots, java.util.function.Predicate<Entry> canSell,
                         net.minecraft.util.RandomSource random, boolean all) {
        if (all) {
            for (Entry e : entries) {
                if (e.listed()) {
                    e.slot = -1;
                }
            }
        }
        int filled = 0;
        for (int i = 0; i < slots; i++) {
            if (inSlot(i) != null) {
                continue;
            }
            List<Entry> candidates = new ArrayList<>();
            for (Entry e : entries) {
                if (!e.listed() && e.total() > 0 && canSell.test(e)) {
                    candidates.add(e);
                }
            }
            if (candidates.isEmpty()) {
                break;
            }
            Entry e = candidates.get(random.nextInt(candidates.size()));
            e.slot = i;
            e.kept = false;
            filled++;
        }
        return filled;
    }

    /** Set the owner's price, clamped between the cheapest worker and the default. */
    public void setOwnerPrice(Entry e, @Nullable MerchantPrice price) {
        if (price == null || price.samePerItem(e.defaultPrice())) {
            e.ownerPrice = null;
            return;
        }
        e.ownerPrice = price;
        e.ownerPrice = e.price();
        if (e.ownerPrice.samePerItem(e.defaultPrice())) {
            e.ownerPrice = null;
        }
    }

    public int totalItems() {
        int n = 0;
        for (Entry e : entries) {
            n += e.total();
        }
        return n;
    }

    // --- saving ------------------------------------------------------------

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("EmeraldsMade", emeraldsMade);
        tag.putLong("PassiveSalesAt", passiveSalesAt);
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag et = new CompoundTag();
            et.put("Item", e.item.save(provider));
            et.putInt("Slot", e.slot);
            if (e.kept) {
                et.putBoolean("Kept", true);
            }
            if (e.ownerPrice != null) {
                et.put("OwnerPrice", e.ownerPrice.toNbt());
            }
            ListTag shares = new ListTag();
            e.shares.forEach((worker, s) -> {
                CompoundTag st = new CompoundTag();
                st.putUUID("Worker", worker);
                st.putInt("Count", s.count);
                st.putInt("Restock", s.restock);
                st.put("Price", s.price.toNbt());
                shares.add(st);
            });
            et.put("Shares", shares);
            list.add(et);
        }
        tag.put("Entries", list);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        entries.clear();
        emeraldsMade = tag.getInt("EmeraldsMade");
        passiveSalesAt = tag.contains("PassiveSalesAt") ? tag.getLong("PassiveSalesAt") : -1;
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        int legacySlot = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag et = list.getCompound(i);
            ItemStack item = ItemStack.parseOptional(provider, et.getCompound("Item"));
            if (item.isEmpty()) {
                continue; // an item from a mod that has since been removed
            }
            Entry e = new Entry(item);
            if (et.contains("Slot")) {
                e.slot = et.getInt("Slot");
                e.kept = et.getBoolean("Kept");
            } else {
                // Before numbered slots: goods on sale take slots in their order, and anything
                // else could only have been the owner's choice.
                boolean listed = et.getBoolean("Listed");
                e.slot = listed ? legacySlot++ : -1;
                e.kept = et.contains("Kept") ? et.getBoolean("Kept") : !listed;
            }
            if (et.contains("OwnerPrice")) {
                e.ownerPrice = MerchantPrice.fromNbt(et.getCompound("OwnerPrice"));
            }
            ListTag shares = et.getList("Shares", Tag.TAG_COMPOUND);
            for (int j = 0; j < shares.size(); j++) {
                CompoundTag st = shares.getCompound(j);
                MerchantPrice p = MerchantPrice.fromNbt(st.getCompound("Price"));
                if (p != null) {
                    e.shares.put(st.getUUID("Worker"), new Share(st.getInt("Count"), st.getInt("Restock"), p));
                }
            }
            entries.add(e);
        }
    }
}
