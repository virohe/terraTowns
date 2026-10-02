package com.terraTowns.network;

import com.terraTowns.TerraTowns;
import com.terraTowns.settlement.SettlementData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: the full record of a single settlement. Sent when a player opens a Gym
 * Leader's Desk (to populate the screen) and re-sent after every server-side edit so the open
 * screen and the client cache stay authoritative.
 *
 * <p>{@link SettlementData} is a plain serializable POJO with no server-only dependencies, so
 * it is reused verbatim on the client. It is transported as NBT because it already owns a
 * registry-aware {@link SettlementData#toNbt} / {@link SettlementData#fromNbt} pair (needed for
 * the banner {@code ItemStack}); the {@link RegistryFriendlyByteBuf} supplies the registries.</p>
 *
 * @param openScreen true only for the desk right-click response — the client opens the desk
 *                   screen. False for background refreshes (edit echoes, promotion updates),
 *                   which update the cache and any already-open screen without popping UI.
 */
public record SyncSettlementPacket(SettlementData data, boolean openScreen) implements CustomPacketPayload {

    public static final Type<SyncSettlementPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "sync_settlement"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncSettlementPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeBoolean(pkt.openScreen);
                        buf.writeNbt(pkt.data.toNbt(buf.registryAccess()));
                    },
                    buf -> {
                        boolean open = buf.readBoolean();
                        return new SyncSettlementPacket(
                                SettlementData.fromNbt(buf.readNbt(), buf.registryAccess()), open);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
