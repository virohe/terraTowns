package com.terraTowns.client;

import com.terraTowns.TerraTowns;
import com.terraTowns.client.screen.MerchantStallScreen;
import com.terraTowns.client.screen.VanillaGui;
import com.terraTowns.network.MerchantInfoPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import javax.annotation.Nullable;

/**
 * The owner's view of a merchant's takings on its trade screen (0.6, DESIGN.md §2.8), and the
 * client end of {@link MerchantInfoPacket}.
 *
 * <p>A merchant's trade screen is any villager's, with one thing more for the player who holds
 * the settlement: the emeralds it has made, in the screen's top-right corner (user, 2026-11-05).
 * They're taken at the merchant's counter, not here. Until then the count and two buttons sat in
 * a panel beside the screen.</p>
 *
 * <p>The server sends the owner the merchant's figures just before vanilla opens its trade screen,
 * so they're shown on the next {@link MerchantScreen} that opens and dropped when it closes.
 * Vanilla's trade menu doesn't say which villager it belongs to, which is why the figures are taken
 * from the packet that arrived with it rather than looked up.</p>
 */
@EventBusSubscriber(modid = TerraTowns.MOD_ID, value = Dist.CLIENT)
public final class MerchantClient {

    /** The figures for the merchant whose trades are open (or about to open), if it's ours. */
    @Nullable
    private static MerchantInfoPacket current;
    /** When {@link #current} arrived, and the trade screen it has been attached to. */
    private static long receivedAt;
    @Nullable
    private static MerchantScreen attached;

    private MerchantClient() {
    }

    public static void handleInfo(MerchantInfoPacket info) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MerchantStallScreen stall && stall.merchantId() == info.merchantId()) {
            stall.refresh(info);
            return;
        }
        if (info.open()) {
            current = null;
            mc.setScreen(new MerchantStallScreen(info));
            return;
        }
        current = info;
        receivedAt = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof MerchantScreen screen) || current == null) {
            return;
        }
        // Figures that came with this screen, or are already shown on it (Init runs again on a
        // resize); a stale packet must never put a count on some other villager's trades.
        if (attached != screen && System.currentTimeMillis() - receivedAt > 3000) {
            current = null;
            return;
        }
        attached = screen;
    }

    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof MerchantScreen screen) || current == null || attached != screen) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        var font = Minecraft.getInstance().font;
        // Top right, clear of the centred "Merchant - Apprentice" title and the XP bar under it.
        int right = screen.getGuiLeft() + 276 - 6;
        int top = screen.getGuiTop();
        g.renderItem(new ItemStack(Items.EMERALD), right - 16, top + 2);
        String count = String.valueOf(current.emeraldsMade());
        g.drawString(font, count, right - font.width(count), top + 19, VanillaGui.LABEL, false);
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof MerchantScreen) {
            current = null;
            attached = null;
        }
    }
}
