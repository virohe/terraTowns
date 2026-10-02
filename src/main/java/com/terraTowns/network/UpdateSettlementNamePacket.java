package com.terraTowns.network;

import com.terraTowns.TerraTowns;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client → server: rename a settlement. Sent when the player edits the name field in the
 * Gym Leader's Desk overview tab and commits it (Enter, tab-switch, or closing the screen).
 */
public record UpdateSettlementNamePacket(UUID settlementId, String name) implements CustomPacketPayload {

    public static final Type<UpdateSettlementNamePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "update_settlement_name"));

    public static final StreamCodec<FriendlyByteBuf, UpdateSettlementNamePacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, UpdateSettlementNamePacket::settlementId,
                    ByteBufCodecs.STRING_UTF8, UpdateSettlementNamePacket::name,
                    UpdateSettlementNamePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
