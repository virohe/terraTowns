package com.terraTowns.network;

import com.terraTowns.TerraTowns;
import com.terraTowns.settlement.SettlementData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: every known settlement, sent once when a player joins the world. Populates
 * the {@link com.terraTowns.client.ClientSettlementCache} that backs the settlement HUD overlay.
 */
public record SyncAllSettlementsPacket(List<SettlementData> settlements) implements CustomPacketPayload {

    public static final Type<SyncAllSettlementsPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "sync_all_settlements"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncAllSettlementsPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.settlements.size());
                        for (SettlementData data : pkt.settlements) {
                            buf.writeNbt(data.toNbt(buf.registryAccess()));
                        }
                    },
                    buf -> {
                        int count = buf.readVarInt();
                        List<SettlementData> list = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            list.add(SettlementData.fromNbt(buf.readNbt(), buf.registryAccess()));
                        }
                        return new SyncAllSettlementsPacket(list);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
