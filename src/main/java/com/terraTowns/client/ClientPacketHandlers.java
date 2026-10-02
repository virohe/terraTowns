package com.terraTowns.client;

import com.terraTowns.client.screen.GymLeadersDeskScreen;
import com.terraTowns.network.PromotionReadyToastPacket;
import com.terraTowns.network.SyncAllSettlementsPacket;
import com.terraTowns.network.SyncJobBoardPacket;
import com.terraTowns.network.SyncPromotionChecklistPacket;
import com.terraTowns.network.SyncSettlementPacket;
import com.terraTowns.settlement.SettlementData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Client-side handlers for the server → client sync packets. Referenced only through lambdas in
 * {@link com.terraTowns.network.TerraTownsNetwork} so this class is never loaded on a dedicated
 * server. All methods run on the client thread (the registrar handler enqueues the work).
 */
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {
    }

    /**
     * A single settlement arrived — cache it, refresh an already-open desk screen in place, and
     * open the screen only when the packet says so (the desk right-click response). Background
     * refreshes (edit echoes, promotion updates) never pop UI.
     */
    public static void handleSyncSettlement(SyncSettlementPacket packet) {
        SettlementData data = packet.data();
        ClientSettlementCache.INSTANCE.put(data);

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof GymLeadersDeskScreen desk && desk.settlementId().equals(data.id())) {
            desk.refresh(data);
        } else if (packet.openScreen()) {
            mc.setScreen(new GymLeadersDeskScreen(data));
        }
    }

    /** Promotion checklist for the desk's Overview tab — ignore unless that screen is open. */
    public static void handleChecklist(SyncPromotionChecklistPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof GymLeadersDeskScreen desk
                && desk.settlementId().equals(packet.settlementId())) {
            desk.setChecklist(packet.targetTierId(), packet.entries());
        }
    }

    /** Job board for the desk's Jobs tab — ignore unless that screen is open. */
    public static void handleJobBoard(SyncJobBoardPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof GymLeadersDeskScreen desk
                && desk.settlementId().equals(packet.settlementId())) {
            desk.setJobBoard(packet.entries());
        }
    }

    /** Full settlement set on join — replace the HUD cache wholesale. */
    public static void handleSyncAll(SyncAllSettlementsPacket packet) {
        ClientSettlementCache.INSTANCE.replaceAll(packet.settlements());
    }

    /** Pop a "[Tier] Ready for Promotion!" toast. */
    public static void handlePromotionReady(PromotionReadyToastPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        Component tier = Component.translatable("settlement.terra_towns.tier." + packet.tierId());
        Component title = Component.translatable("toast.terra_towns.promotion_ready.title", tier)
                .withStyle(ChatFormatting.GOLD);
        Component message = Component.translatable("toast.terra_towns.promotion_ready.desc");
        SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, message);
    }
}
