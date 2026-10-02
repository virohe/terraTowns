package com.terraTowns.client.hud;

import com.terraTowns.client.ClientSettlementCache;
import com.terraTowns.settlement.SettlementData;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Top-centre HUD showing the settlement the player is currently inside: its name (large, white
 * with shadow), the configured banner icon, and — once section boundaries exist (0.3) — the
 * current section name in its section colour.
 *
 * <p>Registered as a {@link LayeredDraw.Layer} via {@code RegisterGuiLayersEvent}. It is a pure
 * reader: the "which settlement / section am I in" lookup and the fade animation are computed on
 * a tick cadence in {@link com.terraTowns.client.ClientSettlementEvents} and cached in
 * {@link ClientSettlementCache}, so this runs no distance maths per frame.</p>
 */
public class SettlementHudOverlay implements LayeredDraw.Layer {

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        // Suppress while a screen is open or the HUD is hidden (F1).
        if (mc.player == null || mc.options.hideGui || mc.screen != null) {
            return;
        }

        ClientSettlementCache cache = ClientSettlementCache.INSTANCE;
        float alpha = cache.alpha();
        SettlementData settlement = cache.displayedSettlement();
        if (settlement == null || alpha <= 0.02f) {
            return;
        }

        Font font = mc.font;
        int centerX = graphics.guiWidth() / 2;
        int alphaByte = (int) (alpha * 255.0f) << 24;

        String name = settlement.name() != null && !settlement.name().isBlank()
                ? settlement.name()
                : Component.translatable("hud.terra_towns.unnamed").getString();

        // Settlement name (centred, with shadow), faded via the alpha byte of the colour.
        graphics.drawCenteredString(font, name, centerX, 6, alphaByte | 0xFFFFFF);

        // Banner icon to the left of the name. Item rendering ignores our alpha, so only draw it
        // once the overlay is mostly opaque to avoid a hard pop-in.
        ItemStack banner = settlement.banner();
        if (banner != null && !banner.isEmpty() && alpha > 0.6f) {
            int iconX = centerX - font.width(name) / 2 - 22;
            graphics.renderItem(banner, iconX, 2);
        }

        // Section name (0.2: no boundaries yet, so this is null; the hook is in place for 0.3).
        SettlementData.SettlementSection section = cache.currentSection();
        if (section != null) {
            int sectionColor = alphaByte | (GuiColors.rgb(section.color()));
            graphics.drawCenteredString(font, section.name(), centerX, 18, sectionColor);
        }
    }

    /** Small helper so the overlay can reuse the screen's hex-colour parsing without a client dep loop. */
    private static final class GuiColors {
        static int rgb(String hex) {
            try {
                return Integer.parseInt(hex, 16) & 0xFFFFFF;
            } catch (NumberFormatException e) {
                return 0xFFFFFF;
            }
        }
    }
}
