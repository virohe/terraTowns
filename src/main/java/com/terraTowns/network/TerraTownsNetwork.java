package com.terraTowns.network;

import com.terraTowns.TerraTowns;
import com.terraTowns.settlement.JobBoard;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementManager;
import com.terraTowns.settlement.SettlementPromotion;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The Terra Towns network channel: registration, server-side handlers, and send helpers.
 *
 * <p>Four client → server edit packets drive {@link SettlementData} mutations; two server →
 * client packets sync settlement records back (one settlement on desk open / after an edit,
 * and the full set on world join). All server handlers re-sync the affected settlement so the
 * open desk screen and the client cache never drift from the authoritative
 * {@link SettlementManager} record.</p>
 *
 * <p>Client-bound payloads are handled in {@code com.terraTowns.client.ClientPacketHandlers},
 * referenced only through lambdas that are never invoked on a dedicated server — so that
 * client-only class is never loaded server-side.</p>
 */
public final class TerraTownsNetwork {

    private static final String VERSION = "1";

    private TerraTownsNetwork() {
    }

    /** Wire the payload-registration listener onto the mod event bus (called from {@link TerraTowns}). */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(TerraTownsNetwork::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);

        // Client -> server (edits).
        registrar.playToServer(UpdateSettlementNamePacket.TYPE, UpdateSettlementNamePacket.STREAM_CODEC,
                TerraTownsNetwork::handleUpdateName);
        registrar.playToServer(UpdateSettlementBannerPacket.TYPE, UpdateSettlementBannerPacket.STREAM_CODEC,
                TerraTownsNetwork::handleUpdateBanner);
        registrar.playToServer(AddSectionPacket.TYPE, AddSectionPacket.STREAM_CODEC,
                TerraTownsNetwork::handleAddSection);
        registrar.playToServer(RemoveSectionPacket.TYPE, RemoveSectionPacket.STREAM_CODEC,
                TerraTownsNetwork::handleRemoveSection);

        // Server -> client (sync). Handlers live in a client-only class, reached via a lambda so
        // the class is not classloaded on a dedicated server.
        registrar.playToClient(SyncSettlementPacket.TYPE, SyncSettlementPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.terraTowns.client.ClientPacketHandlers.handleSyncSettlement(payload)));
        registrar.playToClient(SyncAllSettlementsPacket.TYPE, SyncAllSettlementsPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.terraTowns.client.ClientPacketHandlers.handleSyncAll(payload)));
        registrar.playToClient(PromotionReadyToastPacket.TYPE, PromotionReadyToastPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.terraTowns.client.ClientPacketHandlers.handlePromotionReady(payload)));
        registrar.playToClient(SyncPromotionChecklistPacket.TYPE, SyncPromotionChecklistPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.terraTowns.client.ClientPacketHandlers.handleChecklist(payload)));
        registrar.playToClient(SyncJobBoardPacket.TYPE, SyncJobBoardPacket.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> com.terraTowns.client.ClientPacketHandlers.handleJobBoard(payload)));
    }

    // --- send helpers (server side) ----------------------------------------

    /** Background settlement refresh: updates the client cache + any open screen, opens nothing. */
    public static void sendSyncSettlement(ServerPlayer player, SettlementData data) {
        PacketDistributor.sendToPlayer(player, new SyncSettlementPacket(data, false));
    }

    /**
     * The desk-open response: the settlement (with the open-screen flag) plus the server-evaluated
     * promotion checklist rendered in the Overview tab.
     */
    public static void sendDeskSync(ServerPlayer player, ServerLevel level, SettlementData data) {
        PacketDistributor.sendToPlayer(player, new SyncSettlementPacket(data, true));
        SettlementPromotion.Eval eval = SettlementPromotion.evaluate(level, data);
        List<SyncPromotionChecklistPacket.Entry> entries = new ArrayList<>();
        for (SettlementPromotion.Requirement requirement : eval.requirements()) {
            entries.add(new SyncPromotionChecklistPacket.Entry(requirement.label(), requirement.met()));
        }
        PacketDistributor.sendToPlayer(player, new SyncPromotionChecklistPacket(
                data.id(), eval.to() == null ? "" : eval.to().id(), entries));
        sendJobBoard(player, level, data);
    }

    /**
     * The Jobs tab payload. Re-staffs first so the board reflects the villagers standing in the
     * settlement right now rather than whatever the last background scan happened to catch —
     * opening the desk is exactly when a stale board would be noticed.
     */
    public static void sendJobBoard(ServerPlayer player, ServerLevel level, SettlementData data) {
        JobBoard.restaff(level, data);
        List<SyncJobBoardPacket.Entry> jobs = new ArrayList<>();
        for (JobBoard.JobStatus status : JobBoard.board(data).values()) {
            Component detail = switch (status.status()) {
                case STAFFED -> Component.translatable("screen.terra_towns.desk.jobs.staffed");
                case UNSTAFFED -> Component.translatable("screen.terra_towns.desk.jobs.unstaffed");
                case AWAITING_NPC -> Component.translatable("screen.terra_towns.desk.jobs.awaiting_npc");
                case LOCKED_GYM -> Component.translatable("screen.terra_towns.desk.jobs.locked_gym");
                case LOCKED_BUILDING -> Component.translatable("screen.terra_towns.desk.jobs.locked_building",
                        Component.translatable("building.terra_towns." + status.job().unlockedBy().id()));
                case LOCKED_TIER -> Component.translatable("screen.terra_towns.desk.jobs.locked_tier");
            };
            jobs.add(new SyncJobBoardPacket.Entry(status.job().displayName(), detail,
                    data.hasJobUnlocked(status.job()), status.holder() != null,
                    JobBoard.workstations(level, status.job())));
        }
        PacketDistributor.sendToPlayer(player, new SyncJobBoardPacket(data.id(), jobs));
    }

    public static void sendAllSettlements(ServerPlayer player, Collection<SettlementData> settlements) {
        PacketDistributor.sendToPlayer(player, new SyncAllSettlementsPacket(new ArrayList<>(settlements)));
    }

    /** Pop the "[tier] Ready for Promotion!" toast on the given player's client. */
    public static void sendPromotionReadyToast(ServerPlayer player, String tierId) {
        PacketDistributor.sendToPlayer(player, new PromotionReadyToastPacket(tierId));
    }

    // --- server handlers ---------------------------------------------------

    private static void handleUpdateName(UpdateSettlementNamePacket packet, IPayloadContext context) {
        mutate(context, packet.settlementId(), (manager, data) -> data.setName(packet.name()));
    }

    private static void handleUpdateBanner(UpdateSettlementBannerPacket packet, IPayloadContext context) {
        mutate(context, packet.settlementId(),
                (manager, data) -> data.setBanner(packet.banner().isEmpty() ? null : packet.banner()));
    }

    private static void handleAddSection(AddSectionPacket packet, IPayloadContext context) {
        mutate(context, packet.settlementId(), (manager, data) ->
                // Boundary is empty in 0.2 — the map-drawing tool arrives in 0.3.
                data.sections().add(new SettlementData.SettlementSection(
                        packet.sectionName(), packet.color(), new ArrayList<>())));
    }

    private static void handleRemoveSection(RemoveSectionPacket packet, IPayloadContext context) {
        mutate(context, packet.settlementId(), (manager, data) -> {
            int index = packet.sectionIndex();
            if (index >= 0 && index < data.sections().size()) {
                data.sections().remove(index);
            }
        });
    }

    /**
     * Shared server-side edit path: resolve the player + settlement, apply {@code mutation},
     * mark the table dirty, and re-sync the edited settlement to the acting player.
     */
    private static void mutate(IPayloadContext context, UUID settlementId, SettlementMutation mutation) {
        // Hop to the server thread before touching the SavedData table.
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            SettlementManager manager = SettlementManager.get(level);
            SettlementData data = manager.byId(settlementId);
            if (data == null) {
                return;
            }
            mutation.apply(manager, data);
            manager.setDirty();
            sendSyncSettlement(player, data);
        });
    }

    @FunctionalInterface
    private interface SettlementMutation {
        void apply(SettlementManager manager, SettlementData data);
    }
}
