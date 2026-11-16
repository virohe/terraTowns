package com.terraTowns.merchant;

import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;

/**
 * A price as a villager trade states it: {@code emeralds} emeralds for {@code items} items
 * (bread is 1 emerald for 6; a diamond hoe 4 emeralds for 1). Prices are compared and marked up
 * as exact fractions, never as floats, so "the highest price plus 10%, rounded against the buyer"
 * gives the same answer on every machine (DESIGN.md §2.8).
 *
 * <p>Only two shapes are ever produced for the Merchant's own trades, because those are the steps
 * the owner moves between with the cheaper/dearer buttons: {@code (e, 1)} when an item costs an
 * emerald or more, {@code (1, n)} when an emerald buys several.</p>
 */
public record MerchantPrice(int emeralds, int items) {

    /** The most emeralds a single trade can ask for: one stack in the cost slot. */
    public static final int MAX_EMERALDS = 64;

    public MerchantPrice {
        if (emeralds < 1 || items < 1) {
            throw new IllegalArgumentException(emeralds + " for " + items);
        }
    }

    /** Is this dearer per item than {@code other}? e1/n1 &gt; e2/n2, in exact arithmetic. */
    public boolean dearerThan(MerchantPrice other) {
        return (long) emeralds * other.items > (long) other.emeralds * items;
    }

    public boolean cheaperThan(MerchantPrice other) {
        return other.dearerThan(this);
    }

    /** The same price per item (2 for 12 is 1 for 6). */
    public boolean samePerItem(MerchantPrice other) {
        return (long) emeralds * other.items == (long) other.emeralds * items;
    }

    /**
     * The Merchant's default: this worker price plus 10%, rounded up against the buyer
     * (user's rule, 2026-10-16). 5 emeralds an item becomes 6, 1 becomes 2; 50 items an emerald
     * becomes 45 (the user's example said 55, which would be cheaper than the worker; this follows
     * the rule, pending their word). The lot is capped at a stack: an emerald for 45 eggs would not
     * fit in the result slot, so it is an emerald for 16.
     */
    public MerchantPrice markedUp(int maxStack) {
        long e11 = 11L * emeralds;
        long n10 = 10L * items;
        if (e11 >= n10) {
            // An item costs an emerald or more: ceil(1.1 * e / n) emeralds each.
            long e = (e11 + n10 - 1) / n10;
            return new MerchantPrice((int) Math.min(MAX_EMERALDS, e), 1);
        }
        // An emerald buys several: floor(n / (1.1 * e)) of them, at least one.
        long n = Math.max(1, n10 / e11);
        return new MerchantPrice(1, (int) Math.min(Math.max(1, maxStack), n));
    }

    /** One step cheaper for the buyer: an emerald less, or one more item per emerald. */
    public MerchantPrice cheaper(int maxStack) {
        if (items == 1 && emeralds > 1) {
            return new MerchantPrice(emeralds - 1, 1);
        }
        int n = emeralds == 1 ? items + 1 : items;
        return new MerchantPrice(1, Math.min(Math.max(1, maxStack), n));
    }

    /** One step dearer for the buyer: one item fewer per emerald, or an emerald more. */
    public MerchantPrice dearer() {
        if (emeralds == 1 && items > 1) {
            return new MerchantPrice(1, items - 1);
        }
        return new MerchantPrice(Math.min(MAX_EMERALDS, emeralds + 1), 1);
    }

    /**
     * The Merchant's price for one lot, normalised to one of its two shapes. A worker's own price
     * (say 3 emeralds for 2) is reshaped the same way as a markup with no markup: rounded against
     * the buyer, so a floor read from a worker is never undercut by rounding.
     */
    public MerchantPrice normalised(int maxStack) {
        if (items == 1 || emeralds == 1 && items <= maxStack) {
            return this;
        }
        if (emeralds >= items) {
            return new MerchantPrice(Math.min(MAX_EMERALDS, (emeralds + items - 1) / items), 1);
        }
        return new MerchantPrice(1, Math.min(Math.max(1, maxStack), Math.max(1, items / emeralds)));
    }

    /** "6 emeralds" or "5 for 1 emerald", for chat and tooltips. */
    public String describe() {
        return items == 1 ? emeralds + (emeralds == 1 ? " emerald" : " emeralds")
                : items + " for " + emeralds + (emeralds == 1 ? " emerald" : " emeralds");
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("E", emeralds);
        tag.putInt("N", items);
        return tag;
    }

    @Nullable
    public static MerchantPrice fromNbt(CompoundTag tag) {
        int e = tag.getInt("E");
        int n = tag.getInt("N");
        return e >= 1 && n >= 1 ? new MerchantPrice(e, n) : null;
    }
}
