package com.terraTowns.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws Terra Towns' screens in vanilla's own container style, so they read as part of the game
 * rather than as a mod's debug panel.
 *
 * <p>Every colour here was sampled from vanilla's {@code gui/container/generic_54.png} (1.21.1),
 * not picked by eye: a 1px black outline with cut corners, a 2px white highlight on the top and
 * left, a {@code #C6C6C6} body, a 2px {@code #555555} shadow on the bottom and right. Slots are
 * {@code #373737} top/left, {@code #FFFFFF} bottom/right, {@code #8B8B8B} inside. Tabs are
 * vanilla's own creative-inventory sprites, so a selected tab merges into the panel below it
 * exactly the way the creative menu's do.</p>
 */
public final class VanillaGui {

    /** Vanilla's label colour on a container background (the "Inventory" / chest title grey). */
    public static final int LABEL = 0xFF404040;
    /** Secondary text on a container background — readable on #C6C6C6, quieter than LABEL. */
    public static final int SUBTLE = 0xFF6B6B6B;

    public static final int TAB_W = 26;
    public static final int TAB_H = 32;
    /** Horizontal distance between tab origins, as in vanilla's creative inventory. */
    public static final int TAB_STEP = 27;
    /** How far above the panel's top edge a top tab starts. */
    public static final int TAB_RISE = 28;

    private static final int OUTLINE = 0xFF000000;
    private static final int HIGHLIGHT = 0xFFFFFFFF;
    private static final int BODY = 0xFFC6C6C6;
    private static final int SHADOW = 0xFF555555;
    private static final int SLOT_DARK = 0xFF373737;
    private static final int SLOT_INNER = 0xFF8B8B8B;

    private VanillaGui() {
    }

    /** A vanilla container panel occupying {@code [x, x+w) × [y, y+h)}. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        // Outline, with the one-pixel diagonal corners vanilla's textures have.
        g.fill(x + 2, y, x + w - 2, y + 1, OUTLINE);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, OUTLINE);
        g.fill(x, y + 2, x + 1, y + h - 2, OUTLINE);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, OUTLINE);
        g.fill(x + 1, y + 1, x + 2, y + 2, OUTLINE);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, OUTLINE);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, OUTLINE);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, OUTLINE);
        // Body, then the bevel: highlight top/left, shadow bottom/right.
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, BODY);
        g.fill(x + 2, y + 1, x + w - 3, y + 3, HIGHLIGHT);
        g.fill(x + 1, y + 2, x + 3, y + h - 3, HIGHLIGHT);
        g.fill(x + 3, y + h - 3, x + w - 2, y + h - 1, SHADOW);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 2, SHADOW);
    }

    /** An empty 18×18 inventory slot with its top-left at {@code (x, y)}. */
    public static void slot(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, SLOT_DARK);
        g.fill(x + 1, y + 1, x + 18, y + 18, HIGHLIGHT);
        g.fill(x + 1, y + 1, x + 17, y + 17, SLOT_INNER);
    }

    /**
     * One creative-inventory tab above a panel. {@code column} picks vanilla's sprite variant —
     * column 0 has the square left edge that lines up with the panel's corner.
     */
    public static void topTab(GuiGraphics g, int column, int panelX, int panelY, boolean selected) {
        int variant = Math.max(1, Math.min(7, column + 1));
        ResourceLocation sprite = ResourceLocation.withDefaultNamespace(
                "container/creative_inventory/tab_top_" + (selected ? "selected_" : "unselected_") + variant);
        g.blitSprite(sprite, tabX(panelX, column), tabY(panelY), TAB_W, TAB_H);
    }

    public static int tabX(int panelX, int column) {
        return panelX + column * TAB_STEP;
    }

    public static int tabY(int panelY) {
        return panelY - TAB_RISE;
    }

    /** @return true if {@code (mx, my)} is over the tab in {@code column}. */
    public static boolean overTab(double mx, double my, int panelX, int panelY, int column) {
        int x = tabX(panelX, column);
        int y = tabY(panelY);
        return mx >= x && mx < x + TAB_W && my >= y && my < y + TAB_H - 4;
    }
}
