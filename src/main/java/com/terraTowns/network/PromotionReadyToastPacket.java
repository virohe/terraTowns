package com.terraTowns.network;

import com.terraTowns.TerraTowns;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: pop a "[Tier] Ready for Promotion!" toast for the settlement owner when their
 * settlement first meets the milestones to grow to its next tier. Carries the <em>current</em>
 * tier id (the tier that is ready to be promoted).
 */
public record PromotionReadyToastPacket(String tierId) implements CustomPacketPayload {

    public static final Type<PromotionReadyToastPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "promotion_ready_toast"));

    public static final StreamCodec<FriendlyByteBuf, PromotionReadyToastPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, PromotionReadyToastPacket::tierId,
                    PromotionReadyToastPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
