package com.terraTowns.network;

import com.terraTowns.TerraTowns;
import com.terraTowns.merchant.MerchantPrice;
import com.terraTowns.merchant.MerchantStock;
import com.terraTowns.merchant.Merchants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → owner: a merchant's stock, prices and takings (0.6). Sent when the owner opens the
 * merchant's trades (for the emeralds count on them), when they open the stall screen at its
 * counter ({@code open}), and after every change they make there.
 *
 * <p>Only goods the merchant can sell at its level are sent: the rest wait out of sight until it
 * gets there (user, 2026-11-05). Prices are worked out on the server and sent finished, with
 * whether a cheaper or dearer step is allowed, so the price rules live only in
 * {@link MerchantStock}.</p>
 *
 * @param level the merchant's level, 1 to 5
 * @param slots how many slots it has at that level (two per level)
 */
public record MerchantInfoPacket(int merchantId, boolean open, int emeraldsMade, int level, int slots, List<Row> rows)
        implements CustomPacketPayload {

    /**
     * One of the merchant's goods.
     *
     * @param entry its index in the merchant's stock, what actions name it by
     * @param slot  the slot it's selling in, or -1 when Not Selling
     */
    public record Row(int entry, ItemStack item, int stock, int slot, int priceEmeralds, int priceItems,
                      int defaultEmeralds, int defaultItems, boolean ownerSet,
                      boolean canCheaper, boolean canDearer) {

        public boolean selling() {
            return slot >= 0;
        }

        public static Row of(int index, MerchantStock.Entry e) {
            MerchantPrice p = e.price();
            MerchantPrice d = e.defaultPrice();
            MerchantPrice low = e.lowestWorkerPrice();
            MerchantPrice cheaper = p.cheaper(e.item().getMaxStackSize());
            boolean canCheaper = !cheaper.samePerItem(p) && (low == null || !cheaper.cheaperThan(low));
            boolean canDearer = p.cheaperThan(d);
            return new Row(index, e.item(), e.total(), e.slot(), p.emeralds(), p.items(), d.emeralds(), d.items(),
                    e.ownerSetPrice(), canCheaper, canDearer);
        }
    }

    public static MerchantInfoPacket of(Villager merchant, boolean open) {
        MerchantStock stock = Merchants.stock(merchant);
        int level = Merchants.level(merchant);
        List<Row> rows = new ArrayList<>();
        List<MerchantStock.Entry> entries = stock.entries();
        for (int i = 0; i < entries.size(); i++) {
            if (Merchants.canSell(merchant, entries.get(i))) {
                rows.add(Row.of(i, entries.get(i)));
            }
        }
        return new MerchantInfoPacket(merchant.getId(), open, stock.emeraldsMade(), level, Merchants.slots(level), rows);
    }

    /** The row selling in {@code slot}, or null. */
    public Row inSlot(int slot) {
        for (Row r : rows) {
            if (r.slot() == slot) {
                return r;
            }
        }
        return null;
    }

    public int listedCount() {
        int n = 0;
        for (Row r : rows) {
            if (r.selling()) {
                n++;
            }
        }
        return n;
    }

    public static final Type<MerchantInfoPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "merchant_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MerchantInfoPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.merchantId);
                        buf.writeBoolean(pkt.open);
                        buf.writeVarInt(pkt.emeraldsMade);
                        buf.writeVarInt(pkt.level);
                        buf.writeVarInt(pkt.slots);
                        buf.writeVarInt(pkt.rows.size());
                        for (Row r : pkt.rows) {
                            buf.writeVarInt(r.entry());
                            ItemStack.STREAM_CODEC.encode(buf, r.item());
                            buf.writeVarInt(r.stock());
                            buf.writeVarInt(r.slot() + 1);
                            buf.writeVarInt(r.priceEmeralds());
                            buf.writeVarInt(r.priceItems());
                            buf.writeVarInt(r.defaultEmeralds());
                            buf.writeVarInt(r.defaultItems());
                            buf.writeBoolean(r.ownerSet());
                            buf.writeBoolean(r.canCheaper());
                            buf.writeBoolean(r.canDearer());
                        }
                    },
                    buf -> {
                        int id = buf.readVarInt();
                        boolean open = buf.readBoolean();
                        int emeralds = buf.readVarInt();
                        int level = buf.readVarInt();
                        int slots = buf.readVarInt();
                        int n = buf.readVarInt();
                        List<Row> rows = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            rows.add(new Row(buf.readVarInt(), ItemStack.STREAM_CODEC.decode(buf), buf.readVarInt(),
                                    buf.readVarInt() - 1, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                                    buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
                        }
                        return new MerchantInfoPacket(id, open, emeralds, level, slots, rows);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
