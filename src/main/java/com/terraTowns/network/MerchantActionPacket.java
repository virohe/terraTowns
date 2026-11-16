package com.terraTowns.network;

import com.terraTowns.TerraTowns;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Owner → server: a change to a merchant's stall (0.6). {@code entry} is a row's
 * {@link MerchantInfoPacket.Row#entry() entry index}; {@code arg} depends on the action. The server
 * checks ownership and reach before doing anything.
 *
 * <ul>
 *   <li>{@code SET_SLOT}: put {@code entry} selling in slot {@code arg}.</li>
 *   <li>{@code CLEAR_SLOT}: slot {@code arg} stops selling (Not Selling).</li>
 *   <li>{@code RANDOMIZE}: fill the empty slots at random; {@code arg} 1 rerolls every slot.</li>
 *   <li>{@code CHEAPER} / {@code DEARER}: {@code arg} price steps for {@code entry} (Shift-click: 5).</li>
 *   <li>{@code RESET_PRICE}: {@code entry} back to the default price.</li>
 * </ul>
 */
public record MerchantActionPacket(int merchantId, Action action, int entry, int arg) implements CustomPacketPayload {

    public enum Action {
        TAKE_EMERALDS, SET_SLOT, CLEAR_SLOT, RANDOMIZE, CHEAPER, DEARER, RESET_PRICE
    }

    public static final Type<MerchantActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "merchant_action"));

    public static final StreamCodec<FriendlyByteBuf, MerchantActionPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, MerchantActionPacket::merchantId,
                    ByteBufCodecs.VAR_INT.map(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal),
                    MerchantActionPacket::action,
                    ByteBufCodecs.VAR_INT, MerchantActionPacket::entry,
                    ByteBufCodecs.VAR_INT, MerchantActionPacket::arg,
                    MerchantActionPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
