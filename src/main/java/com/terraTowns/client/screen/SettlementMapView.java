package com.terraTowns.client.screen;

import com.mojang.blaze3d.platform.NativeImage;
import com.terraTowns.TerraTowns;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.settlement.SettlementMap;
import com.terraTowns.structure.BuildingCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.MapColor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The desk's settlement map (0.5.3): the server's top-down picture ({@link SettlementMap}) drawn
 * in a vanilla map frame, scaled to fit, with the settlement's buildings outlined in their type's
 * colour on top. Owns the picture's texture; {@link #close} frees it.
 */
final class SettlementMapView {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(TerraTowns.MOD_ID, "desk_settlement_map");
    private static final ResourceLocation FRAME = ResourceLocation.withDefaultNamespace("textures/map/map_background.png");
    /** Blank parchment where nothing is known (an unloaded chunk), in NativeImage's ABGR. */
    private static final int PARCHMENT = 0xFF9FC4D6;
    /** Outline of a structure that holds nothing a building type needs. */
    static final int UNRECOGNISED = 0x8A8A8A;
    private static final int FRAME_INSET = 6;

    /** A building on the map: where it is in the world, its type (null = unrecognised), its tooltip. */
    record Building(BoundingBox box, @Nullable BuildingCategory type, List<Component> tooltip) {
        int color() {
            return type == null ? UNRECOGNISED : type.color();
        }
    }

    private final SettlementMap.Image image;
    @Nullable
    private DynamicTexture texture;

    // Where the picture was last drawn, for turning world positions into screen ones.
    private float drawX;
    private float drawY;
    private float pixel; // screen pixels per map pixel

    SettlementMapView(SettlementMap.Image image) {
        this.image = image;
    }

    private void upload() {
        NativeImage img = new NativeImage(image.width(), image.height(), false);
        byte[] colors = image.colors();
        for (int z = 0; z < image.height(); z++) {
            for (int x = 0; x < image.width(); x++) {
                int packed = colors[z * image.width() + x] & 0xFF;
                img.setPixelRGBA(x, z, packed == 0 ? PARCHMENT : MapColor.getColorFromPackedId(packed));
            }
        }
        texture = new DynamicTexture(img);
        Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
    }

    void close() {
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE);
            texture = null;
        }
    }

    /** Draw the frame and the picture, as large as fits {@code [x, x+w) × [y, y+h)}. */
    void draw(GuiGraphics g, int x, int y, int w, int h) {
        if (texture == null) {
            upload();
        }
        g.blit(FRAME, x, y, w, h, 0.0F, 0.0F, 64, 64, 64, 64);
        int innerW = w - 2 * FRAME_INSET;
        int innerH = h - 2 * FRAME_INSET;
        pixel = Math.min(innerW / (float) image.width(), innerH / (float) image.height());
        int pw = Math.round(image.width() * pixel);
        int ph = Math.round(image.height() * pixel);
        int px = x + FRAME_INSET + (innerW - pw) / 2;
        int py = y + FRAME_INSET + (innerH - ph) / 2;
        drawX = px;
        drawY = py;
        g.blit(TEXTURE, px, py, pw, ph, 0.0F, 0.0F, image.width(), image.height(), image.width(), image.height());
    }

    /** Screen x of world x (the west edge of that block). */
    float screenX(double worldX) {
        return drawX + (float) ((worldX - image.originX()) / image.scale()) * pixel;
    }

    float screenY(double worldZ) {
        return drawY + (float) ((worldZ - image.originZ()) / image.scale()) * pixel;
    }

    /** Outline {@code b} in {@code rgb}, two pixels thick when the map is drawn large enough. */
    void outline(GuiGraphics g, BoundingBox b, int rgb) {
        int x0 = Math.round(screenX(b.minX()));
        int y0 = Math.round(screenY(b.minZ()));
        int x1 = Math.max(x0 + 2, Math.round(screenX(b.maxX() + 1)));
        int y1 = Math.max(y0 + 2, Math.round(screenY(b.maxZ() + 1)));
        int c = 0xFF000000 | rgb;
        // A light tint inside: at a village's scale a house is a few pixels, and an outline alone
        // was hard to pick out of the trees (render check, 2026-10-12).
        g.fill(x0, y0, x1, y1, 0x66000000 | rgb);
        g.fill(x0, y0, x1, y0 + 1, c);
        g.fill(x0, y1 - 1, x1, y1, c);
        g.fill(x0, y0, x0 + 1, y1, c);
        g.fill(x1 - 1, y0, x1, y1, c);
    }

    /** Is the screen point over {@code b} as drawn? */
    boolean over(BoundingBox b, double mx, double my) {
        return mx >= screenX(b.minX()) && mx < Math.max(screenX(b.minX()) + 2, screenX(b.maxX() + 1))
                && my >= screenY(b.minZ()) && my < Math.max(screenY(b.minZ()) + 2, screenY(b.maxZ() + 1));
    }

    /** A small square marker centred on a world position, if it's on the map. */
    void marker(GuiGraphics g, double worldX, double worldZ, int fill) {
        float sx = screenX(worldX);
        float sy = screenY(worldZ);
        if (sx < drawX || sy < drawY || sx > drawX + image.width() * pixel || sy > drawY + image.height() * pixel) {
            return;
        }
        int x = Math.round(sx);
        int y = Math.round(sy);
        g.fill(x - 3, y - 3, x + 3, y + 3, 0xFF000000);
        g.fill(x - 2, y - 2, x + 2, y + 2, fill);
    }

    /**
     * Every building to outline: each plaque's building (its room, or a small square at the
     * plaque before 0.5.2 recorded rooms) and each detected structure no plaque took over.
     */
    static List<Building> buildings(SettlementData data) {
        List<Building> out = new ArrayList<>();
        data.plaques().forEach((pos, type) -> {
            BoundingBox box = data.plaqueBox(pos);
            if (box == null) {
                BlockPos p = BlockPos.of(pos);
                box = new BoundingBox(p.getX() - 1, p.getY(), p.getZ() - 1, p.getX() + 1, p.getY(), p.getZ() + 1);
            }
            out.add(new Building(box, type, List.of(name(type),
                    Component.translatable("screen.terra_towns.desk.map.plaque").withStyle(s -> s.withColor(0xFFAAAAAA)))));
        });
        for (SettlementData.Structure st : data.structures()) {
            if (!st.classified() || st.plaqued()) {
                continue;
            }
            List<Component> tip = new ArrayList<>();
            tip.add(st.type() == null ? Component.translatable("screen.terra_towns.desk.map.unrecognised") : name(st.type()));
            if (st.beds() > 0) {
                tip.add(Component.translatable("screen.terra_towns.desk.map.beds", st.beds()));
            }
            if (!st.workstations().isEmpty()) {
                tip.add(Component.translatable("screen.terra_towns.desk.map.workstations", workstations(st.workstations())));
            }
            tip.add(Component.translatable(st.housingOnly() ? "screen.terra_towns.desk.map.came_with_hamlet"
                    : "screen.terra_towns.desk.map.came_with_village").withStyle(s -> s.withColor(0xFFAAAAAA)));
            out.add(new Building(st.box(), st.type(), tip));
        }
        return out;
    }

    static Component name(BuildingCategory type) {
        return Component.translatable("building.terra_towns." + type.id());
    }

    /** "Toolsmith, Farmer ×2". */
    private static String workstations(List<String> professions) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String p : professions) {
            String name = p.contains(":") ? p.substring(p.indexOf(':') + 1) : p;
            counts.merge(name.isEmpty() ? p : Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase(Locale.ROOT),
                    1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((n, c) -> parts.add(c > 1 ? n + " ×" + c : n));
        return String.join(", ", parts);
    }
}
