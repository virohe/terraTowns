package com.terraTowns.network;

import com.terraTowns.TerraTowns;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Client → server: set (or clear) a settlement's banner. Sent when the banner slot in the
 * overview tab changes. Uses the registry-aware {@link ItemStack#OPTIONAL_STREAM_CODEC} so an
 * empty stack (banner cleared) is legal and the banner's data components travel with it.
 */
public record UpdateSettlementBannerPacket(UUID settlementId, ItemStack banner) implements CustomPacketPayload {

    public static final Type<UpdateSettlementBannerPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "update_settlement_banner"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateSettlementBannerPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, UpdateSettlementBannerPacket::settlementId,
                    ItemStack.OPTIONAL_STREAM_CODEC, UpdateSettlementBannerPacket::banner,
                    UpdateSettlementBannerPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
