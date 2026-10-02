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
 * Client → server: add a named section to a settlement. The boundary polygon is created empty
 * for 0.2 (the in-world map-drawing tool lands in 0.3); this only wires the name + colour.
 */
public record AddSectionPacket(UUID settlementId, String sectionName, String color) implements CustomPacketPayload {

    public static final Type<AddSectionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "add_section"));

    public static final StreamCodec<FriendlyByteBuf, AddSectionPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, AddSectionPacket::settlementId,
                    ByteBufCodecs.STRING_UTF8, AddSectionPacket::sectionName,
                    ByteBufCodecs.STRING_UTF8, AddSectionPacket::color,
                    AddSectionPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
