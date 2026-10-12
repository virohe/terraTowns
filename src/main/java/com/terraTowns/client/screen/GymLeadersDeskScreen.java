package com.terraTowns.client.screen;

import com.terraTowns.network.RemoveSectionPacket;
import com.terraTowns.network.SyncJobBoardPacket;
import com.terraTowns.network.SyncPromotionChecklistPacket;
import com.terraTowns.network.UpdateSettlementBannerPacket;
import com.terraTowns.network.UpdateSettlementNamePacket;
import com.terraTowns.registry.TerraTownsRegistries;
import com.terraTowns.settlement.SettlementData;
import com.terraTowns.structure.BuildingCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Gym Leader's Desk GUI — a plain (menu-less) {@link Screen} opened when the server streams
 * a {@link SettlementData} in response to a right-click on a {@link com.terraTowns.block.GymLeadersDeskBlock}.
 *
 * <p>Laid out like vanilla's creative inventory: item-icon tabs along the top (the selected tab
 * merges into the panel, the others sit behind it, hovering one names it), the current tab's name
 * as the title, and a vanilla container panel beneath, all drawn by {@link VanillaGui} with colours
 * sampled from vanilla's own textures. Tabs: <b>Overview</b> (name, banner, tier, a progress
 * summary, registered buildings), <b>Progress</b> (every requirement for the next tier), <b>Jobs</b> (the job board, with the workstations that staff each job),
 * <b>Sections</b> (named districts), and a <b>Heat map</b> stub.</p>
 *
 * <p>Edits are optimistic-free: they are sent as packets and the server echoes a fresh
 * {@link com.terraTowns.network.SyncSettlementPacket} which {@link #refresh} applies in place.</p>
 */
public class GymLeadersDeskScreen extends Screen {

    private enum Tab {
        OVERVIEW("overview", () -> new ItemStack(TerraTownsRegistries.GYM_LEADERS_DESK_ITEM.get())),
        PROGRESS("progress", () -> new ItemStack(Items.WRITABLE_BOOK)),
        JOBS("jobs", () -> new ItemStack(Items.SMITHING_TABLE)),
        /** Sections and the old heat-map stub, merged into one map of the settlement (0.5.3). */
        MAP("map", () -> new ItemStack(Items.FILLED_MAP));

        private final String key;
        private final Supplier<ItemStack> icon;

        Tab(String key, Supplier<ItemStack> icon) {
            this.key = key;
            this.icon = icon;
        }

        /** The map's title follows the settlement: "Village Map". */
        Component title(SettlementData data) {
            return this == MAP
                    ? Component.translatable("screen.terra_towns.desk.tab.map",
                            Component.translatable("settlement.terra_towns.tier." + data.tier().id()))
                    : Component.translatable("screen.terra_towns.desk.tab." + key);
        }
    }

    /** The map tab's sub-tabs (user's design, 2026-10-12). */
    private enum MapMode {
        PLAIN("plain"), BUILDINGS("buildings"), SECTIONS("sections");

        private final String key;

        MapMode(String key) {
            this.key = key;
        }
    }

    private static final int WIDTH = 256;
    private static final int HEIGHT = 200;
    private static final int PAD = 8;
    private static final int LINE = 10;

    private SettlementData data;
    private Tab tab = Tab.OVERVIEW;

    private int leftPos;
    private int topPos;

    // Overview widgets / regions.
    @Nullable
    private EditBox nameField;
    private int bannerSlotX;
    private int bannerSlotY;

    // Promotion checklist (server-evaluated; arrives via SyncPromotionChecklistPacket on open).
    @Nullable
    private String checklistTierId;
    private List<SyncPromotionChecklistPacket.Entry> checklist = List.of();

    // Job board (server-evaluated; arrives via SyncJobBoardPacket alongside the checklist).
    private List<SyncJobBoardPacket.Entry> jobs = List.of();

    // Map tab (0.5.3): the picture arrives with the desk sync (SyncSettlementMapPacket).
    @Nullable
    private SettlementMapView map;
    private MapMode mapMode = MapMode.BUILDINGS;
    private boolean legendOpen;
    /** Legend entries switched off: building type ids, or "unrecognised". */
    private final java.util.Set<String> hiddenTypes = new java.util.HashSet<>();
    private static final int MAP_TOP = 38;
    private static final int LEGEND_W = 112;
    private static final int LEGEND_ROW = 11;

    // Sections state.
    private int selectedSection = -1;
    private int sectionListTop;
    private static final int SECTION_ROW_H = 14;

    public GymLeadersDeskScreen(SettlementData data) {
        super(Component.translatable("block.terra_towns.gym_leaders_desk"));
        this.data = data;
    }

    public UUID settlementId() {
        return data.id();
    }

    /** Apply a fresh server sync (post-edit echo) without losing the current tab. */
    public void refresh(SettlementData newData) {
        this.data = newData;
        if (selectedSection >= newData.sections().size()) {
            selectedSection = -1;
        }
        rebuildWidgets();
    }

    /** Dev only ({@code RenderCheck}): show the map tab, with buildings and the legend or plain. */
    public void showMapForCheck(boolean buildings) {
        commitName();
        tab = Tab.MAP;
        mapMode = buildings ? MapMode.BUILDINGS : MapMode.PLAIN;
        legendOpen = buildings;
        rebuildWidgets();
    }

    /** Install the settlement map picture (sent with the desk sync). */
    public void setMap(com.terraTowns.settlement.SettlementMap.Image image) {
        if (map != null) {
            map.close();
        }
        map = new SettlementMapView(image);
    }

    /** Install the server-evaluated job board shown in the Jobs tab. */
    public void setJobBoard(List<SyncJobBoardPacket.Entry> entries) {
        this.jobs = entries;
    }

    /** Install the server-evaluated promotion checklist shown in the Overview tab. */
    public void setChecklist(String targetTierId, List<SyncPromotionChecklistPacket.Entry> entries) {
        this.checklistTierId = targetTierId.isEmpty() ? null : targetTierId;
        this.checklist = entries;
    }

    @Override
    protected void init() {
        leftPos = (width - WIDTH) / 2;
        // Centre the panel AND the tab row above it, as one unit.
        topPos = (height - HEIGHT + VanillaGui.TAB_RISE) / 2;
        nameField = null;
        switch (tab) {
            case OVERVIEW -> initOverview();
            case MAP -> initMap();
            case PROGRESS, JOBS -> { /* drawn in render */ }
        }
    }

    private void initOverview() {
        nameField = new EditBox(font, leftPos + PAD + 1, topPos + 31, 180, 16,
                Component.translatable("screen.terra_towns.desk.name"));
        nameField.setMaxLength(48);
        nameField.setValue(data.name() == null ? "" : data.name());
        addRenderableWidget(nameField);

        bannerSlotX = leftPos + WIDTH - PAD - 18 - 14;
        bannerSlotY = topPos + 30;
    }

    private void initMap() {
        int x = leftPos + PAD;
        for (MapMode mode : MapMode.values()) {
            Button b = Button.builder(Component.translatable("screen.terra_towns.desk.map.mode." + mode.key),
                    btn -> {
                        mapMode = mode;
                        rebuildWidgets();
                    }).bounds(x, topPos + 18, 62, 16).build();
            b.active = mode != mapMode; // the selected sub-tab reads as pressed
            addRenderableWidget(b);
            x += 64;
        }
        if (mapMode == MapMode.BUILDINGS) {
            addRenderableWidget(Button.builder(Component.translatable(legendOpen
                            ? "screen.terra_towns.desk.map.legend.hide" : "screen.terra_towns.desk.map.legend.show"),
                    btn -> {
                        legendOpen = !legendOpen;
                        rebuildWidgets();
                    }).bounds(leftPos + WIDTH - PAD - 50, topPos + 18, 50, 16).build());
        }
        if (mapMode == MapMode.SECTIONS) {
            initSections();
        }
    }

    private void initSections() {
        sectionListTop = topPos + MAP_TOP;
        int buttonW = (WIDTH - 2 * PAD - 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.terra_towns.desk.section.add"),
                b -> minecraft.setScreen(new AddSectionScreen(this, data.id())))
                .bounds(leftPos + PAD, topPos + HEIGHT - 28, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.terra_towns.desk.section.remove"),
                b -> removeSelectedSection())
                .bounds(leftPos + PAD + buttonW + 4, topPos + HEIGHT - 28, buttonW, 20).build());
    }

    private void switchTab(Tab newTab) {
        if (newTab == tab) {
            return;
        }
        commitName();
        this.tab = newTab;
        rebuildWidgets();
    }

    private void removeSelectedSection() {
        if (selectedSection >= 0 && selectedSection < data.sections().size()) {
            PacketDistributor.sendToServer(new RemoveSectionPacket(data.id(), selectedSection));
            selectedSection = -1;
        }
    }

    /** Send the name to the server if the field differs from the known value. */
    private void commitName() {
        if (nameField == null) {
            return;
        }
        String current = data.name() == null ? "" : data.name();
        String edited = nameField.getValue().trim();
        if (!edited.equals(current)) {
            PacketDistributor.sendToServer(new UpdateSettlementNamePacket(data.id(), edited));
        }
    }

    // --- input ---------------------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter commits the name field without closing the screen.
        if ((keyCode == 257 || keyCode == 335) && nameField != null && nameField.isFocused()) {
            commitName();
            return true;
        }
        // Close on the inventory key (E by default) — the same key that closes every other
        // block-opened screen (crafting table, lectern, anvil). This is a plain Screen rather
        // than a container menu, so vanilla's automatic "E closes it" (which lives in
        // AbstractContainerScreen) doesn't apply for free. Not while typing a name, though.
        boolean typing = nameField != null && nameField.isFocused();
        if (!typing && minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            if (VanillaGui.overTab(mouseX, mouseY, leftPos, topPos, i)) {
                switchTab(tabs[i]);
                return true;
            }
        }
        if (tab == Tab.OVERVIEW && isOverBannerSlot(mouseX, mouseY)) {
            setBannerFromHand();
            return true;
        }
        if (tab == Tab.MAP && mapMode == MapMode.BUILDINGS && legendOpen) {
            List<String> entries = legendEntries();
            int lx = leftPos + WIDTH - PAD - LEGEND_W;
            int ly = topPos + MAP_TOP;
            for (int i = 0; i < entries.size(); i++) {
                int rowY = ly + 4 + i * LEGEND_ROW;
                if (mouseX >= lx && mouseX < lx + LEGEND_W && mouseY >= rowY && mouseY < rowY + LEGEND_ROW) {
                    String key = entries.get(i);
                    if (!hiddenTypes.remove(key)) {
                        hiddenTypes.add(key);
                    }
                    return true;
                }
            }
        }
        if (tab == Tab.MAP && mapMode == MapMode.SECTIONS) {
            int rows = data.sections().size();
            for (int i = 0; i < rows; i++) {
                int rowY = sectionListTop + i * SECTION_ROW_H;
                if (mouseX >= leftPos + PAD && mouseX <= leftPos + WIDTH - PAD
                        && mouseY >= rowY && mouseY < rowY + SECTION_ROW_H) {
                    selectedSection = (selectedSection == i) ? -1 : i;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isOverBannerSlot(double mouseX, double mouseY) {
        return mouseX >= bannerSlotX && mouseX < bannerSlotX + 18
                && mouseY >= bannerSlotY && mouseY < bannerSlotY + 18;
    }

    /**
     * Banner picking: with no inventory slots on a plain screen, clicking the banner slot adopts
     * the banner the player is holding in their main hand (or clears it if the hand is empty /
     * not a banner). The map-based banner designer is a later milestone.
     */
    private void setBannerFromHand() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        ItemStack banner = held.getItem() instanceof BannerItem ? held.copyWithCount(1) : ItemStack.EMPTY;
        PacketDistributor.sendToServer(new UpdateSettlementBannerPacket(data.id(), banner));
    }

    // --- rendering -------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Vanilla's in-game container dim (renderTransparentBackground's gradient). NOT
        // super.renderBackground: that runs the full-screen menu-blur shader every frame, which
        // caused a large FPS drop while the desk was open.
        graphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);

        // Creative-inventory order: unselected tabs behind the panel, the selected tab in front
        // of it so its bottom edge merges into the page.
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] != tab) {
                VanillaGui.topTab(graphics, i, leftPos, topPos, false);
            }
        }
        VanillaGui.panel(graphics, leftPos, topPos, WIDTH, HEIGHT);
        VanillaGui.topTab(graphics, tab.ordinal(), leftPos, topPos, true);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            graphics.renderItem(tabs[i].icon.get(), VanillaGui.tabX(leftPos, i) + 5, VanillaGui.tabY(topPos) + 9);
        }
        graphics.drawString(font, tab.title(data), leftPos + PAD, topPos + 6, VanillaGui.LABEL, false);

        switch (tab) {
            case OVERVIEW -> renderOverview(graphics);
            case PROGRESS -> renderProgress(graphics);
            case JOBS -> renderJobs(graphics, mouseX, mouseY);
            case MAP -> renderMap(graphics, mouseX, mouseY);
        }

        for (int i = 0; i < tabs.length; i++) {
            if (VanillaGui.overTab(mouseX, mouseY, leftPos, topPos, i)) {
                graphics.renderTooltip(font, tabs[i].title(data), mouseX, mouseY);
            }
        }
    }

    /** Draw {@code text} wrapped to {@code width}; @return the y just below the last line. */
    private int drawWrapped(GuiGraphics graphics, Component text, int x, int y, int width, int colour) {
        for (FormattedCharSequence line : font.split(text, width)) {
            graphics.drawString(font, line, x, y, colour, false);
            y += LINE;
        }
        return y;
    }

    private void renderOverview(GuiGraphics graphics) {
        int x = leftPos + PAD;
        graphics.drawString(font, Component.translatable("screen.terra_towns.desk.name"),
                x, topPos + 20, VanillaGui.LABEL, false);
        graphics.drawString(font, Component.translatable("screen.terra_towns.desk.banner"),
                bannerSlotX - 4, topPos + 20, VanillaGui.LABEL, false);
        VanillaGui.slot(graphics, bannerSlotX, bannerSlotY);
        ItemStack banner = data.banner();
        if (banner != null && !banner.isEmpty()) {
            graphics.renderItem(banner, bannerSlotX + 1, bannerSlotY + 1);
        }

        Component tierName = Component.translatable("settlement.terra_towns.tier." + data.tier().id());
        graphics.drawString(font, Component.translatable("screen.terra_towns.desk.tier", tierName),
                x, topPos + 56, VanillaGui.LABEL, false);

        // Left column: a one-line summary of the next step up; the full list is the Progress tab
        // (0.5.2: the town checklist runs to ~20 lines).
        int leftW = 140;
        int y = topPos + 72;
        if (checklistTierId != null) {
            long met = checklist.stream().filter(SyncPromotionChecklistPacket.Entry::met).count();
            y = drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.progress",
                            Component.translatable("settlement.terra_towns.tier." + checklistTierId)),
                    x, y, leftW, VanillaGui.SUBTLE) + 2;
            drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.progress.summary",
                    met, checklist.size()), x, y, leftW, VanillaGui.LABEL);
        } else {
            drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.progress.none"),
                    x, y, leftW, VanillaGui.SUBTLE);
        }

        // Right column: registered buildings with how many of each, or how to register one.
        int colX = x + leftW + 8;
        int colW = leftPos + WIDTH - PAD - colX;
        y = drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.buildings"),
                colX, topPos + 72, colW, VanillaGui.SUBTLE) + 2;
        boolean any = false;
        for (BuildingCategory category : BuildingCategory.values()) {
            int count = data.buildingCount(category);
            if (count > 0) {
                Component name = Component.translatable("building.terra_towns." + category.id());
                y = drawWrapped(graphics, Component.literal("• ").append(name)
                                .append(count > 1 ? Component.literal(" ×" + count) : Component.empty()),
                        colX, y, colW, VanillaGui.LABEL);
                any = true;
            }
        }
        if (!any) {
            y = drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.buildings.none"),
                    colX, y, colW, VanillaGui.LABEL) + 2;
            drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.buildings.hint"),
                    colX, y, colW, VanillaGui.SUBTLE);
        }
    }

    /**
     * Progress tab (0.5.2): every requirement for the next tier, in two columns, each with a
     * mark. Built on the server ({@code SettlementPromotion}), so no rule is re-derived here.
     */
    private void renderProgress(GuiGraphics graphics) {
        int x = leftPos + PAD;
        if (checklistTierId == null) {
            drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.progress.none"),
                    x, topPos + 22, WIDTH - 2 * PAD, VanillaGui.SUBTLE);
            return;
        }
        int y = drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.progress",
                        Component.translatable("settlement.terra_towns.tier." + checklistTierId)),
                x, topPos + 20, WIDTH - 2 * PAD, VanillaGui.SUBTLE) + 2;
        int colW = (WIDTH - 2 * PAD - 8) / 2;
        int half = (checklist.size() + 1) / 2;
        int top = y;
        for (int i = 0; i < checklist.size(); i++) {
            SyncPromotionChecklistPacket.Entry entry = checklist.get(i);
            int colX = i < half ? x : x + colW + 8;
            if (i == half) {
                y = top;
            }
            Component line = Component.literal(entry.met() ? "✔ " : "✘ ")
                    .withStyle(entry.met() ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED)
                    .append(entry.label().copy().withStyle(style -> style.withColor(VanillaGui.LABEL)));
            y = drawWrapped(graphics, line, colX, y, colW, VanillaGui.LABEL);
        }
    }

    /**
     * Jobs tab: one row per settlement job — a mark and the job's name, a line saying what to do
     * next, and on the right the workstation blocks a villager can work at to fill it (hover one
     * for its name). Every word and icon arrives pre-built from the server, so no job rule is
     * re-derived here.
     */
    private void renderJobs(GuiGraphics graphics, int mouseX, int mouseY) {
        if (jobs.isEmpty()) {
            drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.jobs.none"),
                    leftPos + PAD, topPos + 22, WIDTH - 2 * PAD, VanillaGui.SUBTLE);
            return;
        }
        int rowH = 34;
        int y = topPos + 20;
        ItemStack hovered = ItemStack.EMPTY;
        for (SyncJobBoardPacket.Entry entry : jobs) {
            int slots = Math.min(entry.workstations().size(), 3);
            int textW = WIDTH - 2 * PAD - slots * 18 - (slots > 0 ? 4 : 0);

            String mark = entry.staffed() ? "✔ " : (entry.unlocked() ? "○ " : "✖ ");
            ChatFormatting colour = entry.staffed() ? ChatFormatting.DARK_GREEN
                    : (entry.unlocked() ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY);
            graphics.drawString(font, Component.literal(mark).withStyle(colour)
                            .append(entry.name().copy().withStyle(style -> style.withColor(VanillaGui.LABEL))),
                    leftPos + PAD, y, VanillaGui.LABEL, false);
            drawWrapped(graphics, entry.detail(), leftPos + PAD + 10, y + LINE, textW - 10, VanillaGui.SUBTLE);

            for (int i = 0; i < slots; i++) {
                int sx = leftPos + WIDTH - PAD - (slots - i) * 18;
                int sy = y + 2;
                VanillaGui.slot(graphics, sx, sy);
                ItemStack stack = entry.workstations().get(i);
                graphics.renderItem(stack, sx + 1, sy + 1);
                if (mouseX >= sx && mouseX < sx + 18 && mouseY >= sy && mouseY < sy + 18) {
                    hovered = stack;
                }
            }
            y += rowH;
        }
        if (!hovered.isEmpty()) {
            graphics.renderTooltip(font, hovered, mouseX, mouseY);
        }
    }

    private void renderSections(GuiGraphics graphics) {
        if (data.sections().isEmpty()) {
            drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.section.none"),
                    leftPos + PAD, sectionListTop, WIDTH - 2 * PAD, VanillaGui.SUBTLE);
            return;
        }
        for (int i = 0; i < data.sections().size(); i++) {
            SettlementData.SettlementSection section = data.sections().get(i);
            int rowY = sectionListTop + i * SECTION_ROW_H;
            if (i == selectedSection) {
                graphics.fill(leftPos + PAD - 2, rowY - 2, leftPos + WIDTH - PAD + 2, rowY + SECTION_ROW_H - 2,
                        0x30000000);
            }
            int swatch = parseColor(section.color());
            graphics.fill(leftPos + PAD + 2, rowY, leftPos + PAD + 12, rowY + 10, 0xFF000000);
            graphics.fill(leftPos + PAD + 3, rowY + 1, leftPos + PAD + 11, rowY + 9, swatch);
            graphics.drawString(font, section.name(), leftPos + PAD + 18, rowY + 1, VanillaGui.LABEL, false);
        }
    }

    /**
     * Map tab (0.5.3): a bird's-eye view of the settlement's footprint in a vanilla map frame.
     * "No outline" is the map alone; "Buildings" outlines every registered or detected building
     * in its type's colour (hover one for what it is; the legend switches types on and off);
     * "Sections" is the settlement's named districts.
     */
    private void renderMap(GuiGraphics graphics, int mouseX, int mouseY) {
        if (mapMode == MapMode.SECTIONS) {
            renderSections(graphics);
            return;
        }
        int x = leftPos + PAD;
        int y = topPos + MAP_TOP;
        int w = WIDTH - 2 * PAD;
        int h = HEIGHT - MAP_TOP - PAD;
        boolean legend = mapMode == MapMode.BUILDINGS && legendOpen;
        if (legend) {
            // Beside the map, not over it: over it, it hid the village centre (render check).
            w -= LEGEND_W + 4;
        }
        if (map == null) {
            drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.map.loading"),
                    x, y + 4, w, VanillaGui.SUBTLE);
            return;
        }
        map.draw(graphics, x, y, w, h);
        List<Component> tooltip = null;
        if (mapMode == MapMode.BUILDINGS) {
            SettlementMapView.Building hovered = null;
            long hoveredArea = Long.MAX_VALUE;
            for (SettlementMapView.Building b : SettlementMapView.buildings(data)) {
                if (hiddenTypes.contains(legendKey(b.type()))) {
                    continue;
                }
                map.outline(graphics, b.box(), b.color());
                long area = (long) b.box().getXSpan() * b.box().getZSpan();
                if (mouseX < x + w && map.over(b.box(), mouseX, mouseY) && area < hoveredArea) {
                    hovered = b;
                    hoveredArea = area;
                }
            }
            if (hovered != null) {
                tooltip = hovered.tooltip();
            }
        }
        if (data.deskPos() != null) {
            map.marker(graphics, data.deskPos().getX() + 0.5, data.deskPos().getZ() + 0.5, 0xFFE04848);
        }
        if (minecraft != null && minecraft.player != null) {
            map.marker(graphics, minecraft.player.getX(), minecraft.player.getZ(), 0xFFFFFFFF);
        }
        if (legend) {
            renderLegend(graphics, leftPos + WIDTH - PAD - LEGEND_W, y);
        }
        if (tooltip != null) {
            graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    /** One row per building type on the map (and "unrecognised"): swatch, name, count; click to hide. */
    private void renderLegend(GuiGraphics graphics, int lx, int ly) {
        List<String> entries = legendEntries();
        int h = 8 + entries.size() * LEGEND_ROW;
        graphics.fill(lx - 1, ly - 1, lx + LEGEND_W + 1, ly + h + 1, 0xFF000000);
        graphics.fill(lx, ly, lx + LEGEND_W, ly + h, 0xF0C6C6C6);
        List<SettlementMapView.Building> buildings = SettlementMapView.buildings(data);
        for (int i = 0; i < entries.size(); i++) {
            String key = entries.get(i);
            BuildingCategory type = key.equals("unrecognised") ? null : BuildingCategory.byId(key);
            int rowY = ly + 4 + i * LEGEND_ROW;
            boolean hidden = hiddenTypes.contains(key);
            int rgb = type == null ? SettlementMapView.UNRECOGNISED : type.color();
            graphics.fill(lx + 4, rowY + 1, lx + 11, rowY + 8, 0xFF000000);
            graphics.fill(lx + 5, rowY + 2, lx + 10, rowY + 7, hidden ? 0xFFC6C6C6 : 0xFF000000 | rgb);
            long count = buildings.stream().filter(b -> b.type() == type).count();
            Component label = (type == null ? Component.translatable("screen.terra_towns.desk.map.unrecognised")
                    : SettlementMapView.name(type)).copy().append(" ×" + count);
            graphics.drawString(font, font.plainSubstrByWidth(label.getString(), LEGEND_W - 18), lx + 15, rowY + 1,
                    hidden ? VanillaGui.SUBTLE : VanillaGui.LABEL, false);
        }
    }

    /** Legend keys for the types on the map, in type order, "unrecognised" last. */
    private List<String> legendEntries() {
        java.util.Set<BuildingCategory> present = java.util.EnumSet.noneOf(BuildingCategory.class);
        boolean unrecognised = false;
        for (SettlementMapView.Building b : SettlementMapView.buildings(data)) {
            if (b.type() == null) {
                unrecognised = true;
            } else {
                present.add(b.type());
            }
        }
        List<String> out = new java.util.ArrayList<>();
        present.forEach(c -> out.add(c.id()));
        if (unrecognised) {
            out.add("unrecognised");
        }
        return out;
    }

    private static String legendKey(@Nullable BuildingCategory type) {
        return type == null ? "unrecognised" : type.id();
    }

    /** Parse a 6-digit hex colour token (e.g. {@code "FF5555"}) into an opaque ARGB int. */
    static int parseColor(String hex) {
        try {
            return 0xFF000000 | (Integer.parseInt(hex, 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return 0xFFFFFFFF;
        }
    }

    @Override
    public void onClose() {
        commitName();
        super.onClose();
    }

    @Override
    public void removed() {
        if (map != null) {
            map.close();
            map = null;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
