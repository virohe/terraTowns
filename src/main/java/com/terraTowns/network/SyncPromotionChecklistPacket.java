package com.terraTowns.network;

import com.terraTowns.TerraTowns;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → client: the settlement's promotion checklist, evaluated server-side (the milestone
 * counts need entity/POI scans the client can't do). Sent alongside {@link SyncSettlementPacket}
 * when the desk opens; rendered in the desk Overview tab next to the registered-building list.
 *
 * @param targetTierId the tier the settlement would promote to (empty string when the current
 *                     tier has no promotion path yet, e.g. TOWN in 0.3)
 */
public record SyncPromotionChecklistPacket(UUID settlementId, String targetTierId,
                                           List<Entry> entries) implements CustomPacketPayload {

    /** One checklist line: a pre-built display label ("Beds: 9 / 12") and whether it is met. */
    public record Entry(Component label, boolean met) {
    }

    public static final Type<SyncPromotionChecklistPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "sync_promotion_checklist"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncPromotionChecklistPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUUID(pkt.settlementId);
                        buf.writeUtf(pkt.targetTierId);
                        buf.writeVarInt(pkt.entries.size());
                        for (Entry entry : pkt.entries) {
                            ComponentSerialization.STREAM_CODEC.encode(buf, entry.label());
                            buf.writeBoolean(entry.met());
                        }
                    },
                    buf -> {
                        UUID id = buf.readUUID();
                        String tier = buf.readUtf();
                        int count = buf.readVarInt();
                        List<Entry> entries = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            Component label = ComponentSerialization.STREAM_CODEC.decode(buf);
                            entries.add(new Entry(label, buf.readBoolean()));
                        }
                        return new SyncPromotionChecklistPacket(id, tier, entries);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
