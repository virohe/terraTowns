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
 * Client → server: remove the section at {@code sectionIndex} from a settlement's section list.
 */
public record RemoveSectionPacket(UUID settlementId, int sectionIndex) implements CustomPacketPayload {

    public static final Type<RemoveSectionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "remove_section"));

    public static final StreamCodec<FriendlyByteBuf, RemoveSectionPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, RemoveSectionPacket::settlementId,
                    ByteBufCodecs.VAR_INT, RemoveSectionPacket::sectionIndex,
                    RemoveSectionPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
