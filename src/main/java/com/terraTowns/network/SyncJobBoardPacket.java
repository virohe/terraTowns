package com.terraTowns.network;

import com.terraTowns.TerraTowns;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → client: the settlement's job board (0.4), rendered in the desk's Jobs tab.
 *
 * <p>Evaluated entirely server-side and shipped as <b>pre-built labels</b>, exactly like
 * {@link SyncPromotionChecklistPacket}. Job status depends on villager professions inside the
 * settlement radius and on registered buildings — neither of which the client can see — so the
 * client is handed finished text plus two booleans and does nothing but pick a colour. No job
 * rule is duplicated client-side, which is what keeps {@code SettlementJob} the single place the
 * policy lives.</p>
 */
public record SyncJobBoardPacket(UUID settlementId, List<Entry> entries) implements CustomPacketPayload {

    /**
     * One job row.
     *
     * @param name      the job's display name ("Farmer")
     * @param detail    why it reads the way it does ("staffed", "needs Smithy", …)
     * @param unlocked  the settlement has earned this job
     * @param staffed   a villager currently holds it
     * @param workstations the blocks a villager can work at to fill this job
     */
    public record Entry(Component name, Component detail, boolean unlocked, boolean staffed,
                        List<ItemStack> workstations) {
    }

    public static final Type<SyncJobBoardPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "sync_job_board"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncJobBoardPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUUID(pkt.settlementId);
                        buf.writeVarInt(pkt.entries.size());
                        for (Entry entry : pkt.entries) {
                            ComponentSerialization.STREAM_CODEC.encode(buf, entry.name());
                            ComponentSerialization.STREAM_CODEC.encode(buf, entry.detail());
                            buf.writeBoolean(entry.unlocked());
                            buf.writeBoolean(entry.staffed());
                            ItemStack.LIST_STREAM_CODEC.encode(buf, entry.workstations());
                        }
                    },
                    buf -> {
                        UUID id = buf.readUUID();
                        int count = buf.readVarInt();
                        List<Entry> entries = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            Component name = ComponentSerialization.STREAM_CODEC.decode(buf);
                            Component detail = ComponentSerialization.STREAM_CODEC.decode(buf);
                            boolean unlocked = buf.readBoolean();
                            boolean staffed = buf.readBoolean();
                            List<ItemStack> stations = ItemStack.LIST_STREAM_CODEC.decode(buf);
                            entries.add(new Entry(name, detail, unlocked, staffed, stations));
                        }
                        return new SyncJobBoardPacket(id, entries);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
