package com.terraTowns.network;

import com.terraTowns.TerraTowns;
import com.terraTowns.settlement.SettlementMap;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server → client: the desk's settlement map picture (0.5.3, {@link SettlementMap}), sent with
 * the rest of the desk sync when the desk opens. About one byte per block of the footprint:
 * ~35 KB for a large village.
 */
public record SyncSettlementMapPacket(UUID settlementId, SettlementMap.Image image) implements CustomPacketPayload {

    public static final Type<SyncSettlementMapPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "sync_settlement_map"));

    public static final StreamCodec<FriendlyByteBuf, SyncSettlementMapPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, pkt.settlementId);
                SettlementMap.Image i = pkt.image;
                buf.writeInt(i.originX());
                buf.writeInt(i.originZ());
                buf.writeVarInt(i.scale());
                buf.writeVarInt(i.width());
                buf.writeVarInt(i.height());
                buf.writeByteArray(i.colors());
            },
            buf -> {
                UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
                int originX = buf.readInt();
                int originZ = buf.readInt();
                int scale = buf.readVarInt();
                int width = buf.readVarInt();
                int height = buf.readVarInt();
                byte[] colors = buf.readByteArray(width * height);
                return new SyncSettlementMapPacket(id, new SettlementMap.Image(originX, originZ, scale, width, height, colors));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
