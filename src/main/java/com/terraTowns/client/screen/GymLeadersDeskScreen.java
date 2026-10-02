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
 * sampled from vanilla's own textures. Four tabs: <b>Overview</b> (name, banner, tier, progress,
 * registered buildings), <b>Jobs</b> (the job board, with the workstations that staff each job),
 * <b>Sections</b> (named districts), and a <b>Heat map</b> stub.</p>
 *
 * <p>Edits are optimistic-free: they are sent as packets and the server echoes a fresh
 * {@link com.terraTowns.network.SyncSettlementPacket} which {@link #refresh} applies in place.</p>
 */
public class GymLeadersDeskScreen extends Screen {

    private enum Tab {
        OVERVIEW("overview", () -> new ItemStack(TerraTownsRegistries.GYM_LEADERS_DESK_ITEM.get())),
        JOBS("jobs", () -> new ItemStack(Items.SMITHING_TABLE)),
        SECTIONS("sections", () -> new ItemStack(Items.WHITE_BANNER)),
        HEATMAP("heatmap", () -> new ItemStack(Items.MAP));

        private final String key;
        private final Supplier<ItemStack> icon;

        Tab(String key, Supplier<ItemStack> icon) {
            this.key = key;
            this.icon = icon;
        }

        Component title() {
            return Component.translatable("screen.terra_towns.desk.tab." + key);
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
            case SECTIONS -> initSections();
            case JOBS, HEATMAP -> { /* drawn in render */ }
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

    private void initSections() {
        sectionListTop = topPos + 22;
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
        if (tab == Tab.SECTIONS) {
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
        graphics.drawString(font, tab.title(), leftPos + PAD, topPos + 6, VanillaGui.LABEL, false);

        switch (tab) {
            case OVERVIEW -> renderOverview(graphics);
            case JOBS -> renderJobs(graphics, mouseX, mouseY);
            case SECTIONS -> renderSections(graphics);
            case HEATMAP -> renderHeatmap(graphics);
        }

        for (int i = 0; i < tabs.length; i++) {
            if (VanillaGui.overTab(mouseX, mouseY, leftPos, topPos, i)) {
                graphics.renderTooltip(font, tabs[i].title(), mouseX, mouseY);
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

        // Left column: progress toward the next tier. Wider than the right one, because its
        // lines are the long ones ("Villagers with jobs: 2 / 7" used to run off the panel).
        int leftW = 140;
        int y = topPos + 72;
        if (checklistTierId != null) {
            y = drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.progress",
                            Component.translatable("settlement.terra_towns.tier." + checklistTierId)),
                    x, y, leftW, VanillaGui.SUBTLE) + 2;
            for (SyncPromotionChecklistPacket.Entry entry : checklist) {
                Component line = Component.literal(entry.met() ? "✔ " : "✘ ")
                        .withStyle(entry.met() ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED)
                        .append(entry.label().copy().withStyle(style -> style.withColor(VanillaGui.LABEL)));
                y = drawWrapped(graphics, line, x, y, leftW, VanillaGui.LABEL);
            }
        } else {
            drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.progress.none"),
                    x, y, leftW, VanillaGui.SUBTLE);
        }

        // Right column: registered buildings, or how to register one.
        int colX = x + leftW + 8;
        int colW = leftPos + WIDTH - PAD - colX;
        y = drawWrapped(graphics, Component.translatable("screen.terra_towns.desk.buildings"),
                colX, topPos + 72, colW, VanillaGui.SUBTLE) + 2;
        boolean any = false;
        for (BuildingCategory category : BuildingCategory.values()) {
            if (data.hasBuilding(category)) {
                y = drawWrapped(graphics, Component.literal("• ")
                                .append(Component.translatable("building.terra_towns." + category.id())),
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

    private void renderHeatmap(GuiGraphics graphics) {
        Component stub = Component.translatable("screen.terra_towns.desk.heatmap.stub");
        graphics.drawString(font, stub, leftPos + (WIDTH - font.width(stub)) / 2,
                topPos + HEIGHT / 2 - 4, VanillaGui.SUBTLE, false);
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
    public boolean isPauseScreen() {
        return false;
    }
}
