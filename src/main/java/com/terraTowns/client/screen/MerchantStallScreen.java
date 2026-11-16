package com.terraTowns.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.terraTowns.network.MerchantActionPacket;
import com.terraTowns.network.MerchantInfoPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The owner's view of a merchant's stall (0.6, DESIGN.md §2.8), opened at the merchant's counter.
 *
 * <p>Laid out as a villager's trade screen (user, 2026-11-05). On the left, the merchant's slots,
 * two per level, each showing the trade a buyer will see; on the right, where a trade screen has
 * its slots and inventory, the emeralds made and the picked slot's goods and price. Goods are
 * either <i>Selling</i> (in a slot) or <i>Not Selling</i>; goods above the merchant's level aren't
 * shown at all, since it can't sell them yet.</p>
 *
 * <p>Choosing what a slot sells opens the picker over the screen: every good the merchant holds,
 * with a search box, a filter (by category or by creative tab, {@link GoodsGroups}) and a sort,
 * because a merchant will hold hundreds of different goods. Randomize fills the empty slots; Shift
 * rerolls them all. Every change goes to the server, and the screen redraws from its answer.</p>
 */
public class MerchantStallScreen extends Screen {

    private static final ResourceLocation VILLAGER_LOCATION =
            ResourceLocation.withDefaultNamespace("textures/gui/container/villager.png");
    private static final ResourceLocation TRADE_ARROW = ResourceLocation.withDefaultNamespace("container/villager/trade_arrow");
    private static final ResourceLocation TRADE_ARROW_OUT =
            ResourceLocation.withDefaultNamespace("container/villager/trade_arrow_out_of_stock");
    private static final ResourceLocation SCROLLER = ResourceLocation.withDefaultNamespace("container/villager/scroller");
    private static final ResourceLocation SCROLLER_DISABLED =
            ResourceLocation.withDefaultNamespace("container/villager/scroller_disabled");

    /** Vanilla's trade screen and its list: seven 88 x 20 trades from (5, 18), scroller at x 94. */
    private static final int W = 276;
    private static final int H = 166;
    private static final int LIST_ROWS = 7;
    private static final int BODY = 0xFFC6C6C6;
    /** The right-hand part, where a trade screen has its slots. */
    private static final int RX = 106;
    private static final int RW = W - RX - 8;
    /** The picker: its list from y 38, rows 18 high. */
    private static final int PICK_Y = 38;
    private static final int PICK_ROW = 18;
    private static final int PICK_ROWS = 6;

    private enum Sort {
        STOCK, NAME, PRICE, NEWEST
    }

    private final int merchantId;
    private MerchantInfoPacket info;
    private int selectedSlot;
    private int listScroll;
    private boolean dragging;
    private int left;
    private int top;

    // The picker, while it's open for a slot.
    private int pickerSlot = -1;
    private String search = "";
    private Sort sort = Sort.STOCK;
    private boolean byTabs;
    private int filter = -1;
    private boolean filterMenu;
    private int pickScroll;
    private List<GoodsGroups.Group> groups = List.of();
    private EditBox searchBox;

    public MerchantStallScreen(MerchantInfoPacket info) {
        super(Component.translatable("screen.terra_towns.stall.title"));
        this.merchantId = info.merchantId();
        this.info = info;
    }

    public int merchantId() {
        return merchantId;
    }

    /** New figures from the server after a change: everything shown follows them. */
    public void refresh(MerchantInfoPacket info) {
        this.info = info;
        rebuildWidgets();
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        selectedSlot = Mth.clamp(selectedSlot, 0, Math.max(0, info.slots() - 1));
        if (pickerSlot >= 0) {
            initPicker();
        } else {
            initStall();
        }
    }

    // --- the stall ----------------------------------------------------------------------------

    private void initStall() {
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, info.slots() - LIST_ROWS));
        for (int i = 0; i < LIST_ROWS && listScroll + i < info.slots(); i++) {
            addRenderableWidget(new SlotButton(left + 5, top + 18 + i * 20, listScroll + i));
        }
        Button take = addRenderableWidget(Button.builder(Component.translatable("screen.terra_towns.stall.take"),
                b -> send(MerchantActionPacket.Action.TAKE_EMERALDS, -1, 0))
                .bounds(left + W - 8 - 40, top + 28, 40, 18).build());
        take.active = info.emeraldsMade() > 0;

        MerchantInfoPacket.Row row = info.inSlot(selectedSlot);
        int y = top + 90;
        int x = left + W - 8 - 36;
        if (row != null) {
            int entry = row.entry();
            Button reset = addRenderableWidget(Button.builder(Component.translatable("screen.terra_towns.stall.reset"),
                            b -> send(MerchantActionPacket.Action.RESET_PRICE, entry, 0))
                    .bounds(x, y, 36, 20)
                    .tooltip(Tooltip.create(Component.translatable("screen.terra_towns.stall.reset.tip",
                            priceText(row.defaultEmeralds(), row.defaultItems()))))
                    .build());
            reset.active = row.ownerSet();
            x -= 18;
            Button dearer = addRenderableWidget(Button.builder(Component.literal("+"),
                            b -> send(MerchantActionPacket.Action.DEARER, entry, hasShiftDown() ? 5 : 1))
                    .bounds(x, y, 16, 20)
                    .tooltip(Tooltip.create(Component.translatable("screen.terra_towns.stall.dearer.tip")
                            .append(" ").append(Component.translatable("screen.terra_towns.stall.step.tip"))))
                    .build());
            dearer.active = row.canDearer();
            x -= 18;
            Button cheaper = addRenderableWidget(Button.builder(Component.literal("-"),
                            b -> send(MerchantActionPacket.Action.CHEAPER, entry, hasShiftDown() ? 5 : 1))
                    .bounds(x, y, 16, 20)
                    .tooltip(Tooltip.create(Component.translatable("screen.terra_towns.stall.cheaper.tip")
                            .append(" ").append(Component.translatable("screen.terra_towns.stall.step.tip"))))
                    .build());
            cheaper.active = row.canCheaper();
        }
        int half = (RW - 2) / 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.terra_towns.stall.change"),
                        b -> openPicker(selectedSlot))
                .bounds(left + RX, top + 116, half, 20).build());
        Button stop = addRenderableWidget(Button.builder(Component.translatable("screen.terra_towns.stall.stop"),
                        b -> send(MerchantActionPacket.Action.CLEAR_SLOT, -1, selectedSlot))
                .bounds(left + RX + half + 2, top + 116, RW - half - 2, 20).build());
        stop.active = row != null;
        Button randomize = addRenderableWidget(Button.builder(Component.translatable("screen.terra_towns.stall.randomize"),
                        b -> send(MerchantActionPacket.Action.RANDOMIZE, -1, hasShiftDown() ? 1 : 0))
                .bounds(left + RX, top + 140, RW, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.terra_towns.stall.randomize.tip")))
                .build());
        randomize.active = !info.rows().isEmpty();
    }

    /** Open the picker for {@code slot} (public for the render check). */
    public void openPicker(int slot) {
        selectedSlot = slot;
        pickerSlot = slot;
        pickScroll = 0;
        filterMenu = false;
        rebuildWidgets();
    }

    private void closePicker() {
        pickerSlot = -1;
        filterMenu = false;
        rebuildWidgets();
    }

    private void send(MerchantActionPacket.Action action, int entry, int arg) {
        PacketDistributor.sendToServer(new MerchantActionPacket(merchantId, action, entry, arg));
    }

    /** "6 each" or "5 for 1". */
    public static Component priceText(int emeralds, int items) {
        return items == 1
                ? Component.translatable("screen.terra_towns.stall.price.each", emeralds)
                : Component.translatable("screen.terra_towns.stall.price.lot", items, emeralds);
    }

    // --- the picker ---------------------------------------------------------------------------

    private void initPicker() {
        groups = byTabs ? GoodsGroups.creativeTabs() : GoodsGroups.categories();
        if (filter >= groups.size()) {
            filter = -1;
        }
        searchBox = new EditBox(font, left + 6, top + 18, 120, 16, Component.translatable("screen.terra_towns.stall.pick.search"));
        searchBox.setHint(Component.translatable("screen.terra_towns.stall.pick.search"));
        searchBox.setValue(search);
        searchBox.setResponder(text -> {
            search = text;
            pickScroll = 0;
        });
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);
        // Out of the picker with the slot as it was (user, 2026-11-05); Escape does the same.
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> closePicker())
                .bounds(left + W - 8 - 44, top + 3, 44, 13).build());
        Component filterName = filter < 0 ? Component.translatable("screen.terra_towns.stall.filter.all")
                : groups.get(filter).name();
        addRenderableWidget(Button.builder(Component.translatable("screen.terra_towns.stall.filter", filterName),
                        b -> {
                            filterMenu = !filterMenu;
                            pickScroll = 0;
                            rebuildWidgets();
                        })
                .bounds(left + 130, top + 17, 72, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.terra_towns.stall.sort",
                                Component.translatable("screen.terra_towns.stall.sort." + sort.name().toLowerCase(Locale.ROOT))),
                        b -> {
                            sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
                            rebuildWidgets();
                        })
                .bounds(left + 204, top + 17, 66, 18).build());
        if (filterMenu) {
            Button categories = addRenderableWidget(Button.builder(Component.translatable("screen.terra_towns.stall.filter.categories"),
                            b -> setGrouping(false))
                    .bounds(left + 6, top + PICK_Y, 131, 16).build());
            categories.active = byTabs;
            Button tabs = addRenderableWidget(Button.builder(Component.translatable("screen.terra_towns.stall.filter.tabs"),
                            b -> setGrouping(true))
                    .bounds(left + 139, top + PICK_Y, 131, 16).build());
            tabs.active = !byTabs;
        }
    }

    private void setGrouping(boolean tabs) {
        byTabs = tabs;
        filter = -1;
        rebuildWidgets();
    }

    /** The goods the picker lists now: searched, filtered and sorted. */
    private List<MerchantInfoPacket.Row> picked() {
        String q = search.trim().toLowerCase(Locale.ROOT);
        List<MerchantInfoPacket.Row> out = new ArrayList<>();
        for (MerchantInfoPacket.Row r : info.rows()) {
            if (!q.isEmpty() && !r.item().getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)
                    && !BuiltInRegistries.ITEM.getKey(r.item().getItem()).toString().contains(q)) {
                continue;
            }
            if (filter >= 0 && !groups.get(filter).test().test(r.item())) {
                continue;
            }
            out.add(r);
        }
        Comparator<MerchantInfoPacket.Row> by = switch (sort) {
            case STOCK -> Comparator.comparingInt(MerchantInfoPacket.Row::stock).reversed();
            case NAME -> Comparator.comparing(r -> r.item().getHoverName().getString().toLowerCase(Locale.ROOT));
            case PRICE -> Comparator.comparingDouble(r -> (double) r.priceEmeralds() / r.priceItems());
            case NEWEST -> Comparator.comparingInt(MerchantInfoPacket.Row::entry).reversed();
        };
        out.sort(by);
        return out;
    }

    /** The filter options that hold any of the merchant's goods, by index into {@link #groups}. */
    private List<Integer> usefulGroups() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            GoodsGroups.Group g = groups.get(i);
            if (info.rows().stream().anyMatch(r -> g.test().test(r.item()))) {
                out.add(i);
            }
        }
        return out;
    }

    /** Lines in the picker's list: "Not Selling" then the goods, or the filter options. */
    private int pickLines() {
        return filterMenu ? usefulGroups().size() + 1 : picked().size() + 1;
    }

    private int pickTop() {
        return top + PICK_Y + (filterMenu ? 18 : 0);
    }

    private int pickVisible() {
        return filterMenu ? PICK_ROWS - 1 : PICK_ROWS;
    }

    private boolean pickerClick(double mouseX, double mouseY) {
        int y0 = pickTop();
        if (mouseX < left + 6 || mouseX >= left + 270 || mouseY < y0 || mouseY >= y0 + pickVisible() * PICK_ROW) {
            return false;
        }
        int line = pickScroll + (int) ((mouseY - y0) / PICK_ROW);
        if (filterMenu) {
            List<Integer> useful = usefulGroups();
            if (line == 0) {
                filter = -1;
            } else if (line - 1 < useful.size()) {
                filter = useful.get(line - 1);
            } else {
                return true;
            }
            filterMenu = false;
            pickScroll = 0;
            rebuildWidgets();
            return true;
        }
        List<MerchantInfoPacket.Row> rows = picked();
        if (line == 0) {
            send(MerchantActionPacket.Action.CLEAR_SLOT, -1, pickerSlot);
        } else if (line - 1 < rows.size()) {
            send(MerchantActionPacket.Action.SET_SLOT, rows.get(line - 1).entry(), pickerSlot);
        } else {
            return true;
        }
        closePicker();
        return true;
    }

    // --- drawing --------------------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        // Vanilla's trade screen, its trade list and scroll bar as they are; the part holding its
        // slots and inventory painted over for the stall's own controls (the whole of it, under
        // the picker).
        g.blit(VILLAGER_LOCATION, left, top, 0, 0.0F, 0.0F, W, H, 512, 256);
        g.fill(left + (pickerSlot >= 0 ? 4 : 101), top + 4, left + W - 4, top + H - 4, BODY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (pickerSlot >= 0) {
            renderPicker(g, mouseX, mouseY);
        } else {
            renderStall(g, mouseX, mouseY);
        }
    }

    private void renderStall(GuiGraphics g, int mouseX, int mouseY) {
        Component trades = Component.translatable("merchant.trades");
        g.drawString(font, trades, left + 5 - font.width(trades) / 2 + 48, top + 6, VanillaGui.LABEL, false);
        Component heading = Component.translatable("merchant.title", title,
                Component.translatable("merchant.level." + info.level()));
        g.drawString(font, heading, left + RX + (RW - font.width(heading)) / 2, top + 6, VanillaGui.LABEL, false);
        Component slots = Component.translatable("screen.terra_towns.stall.slots", info.listedCount(), info.slots());
        g.drawString(font, slots, left + RX + (RW - font.width(slots)) / 2, top + 16, VanillaGui.SUBTLE, false);
        renderScroller(g);

        g.renderItem(new ItemStack(Items.EMERALD), left + RX, top + 29);
        g.drawString(font, Component.translatable("screen.terra_towns.stall.emeralds_made", info.emeraldsMade()),
                left + RX + 20, top + 33, VanillaGui.LABEL, false);
        g.fill(left + RX, top + 50, left + RX + RW, top + 51, 0xFF8B8B8B);

        MerchantInfoPacket.Row row = info.inSlot(selectedSlot);
        VanillaGui.slot(g, left + RX, top + 56);
        if (row == null) {
            g.drawString(font, Component.translatable("screen.terra_towns.stall.empty_slot"), left + RX + 22, top + 57,
                    VanillaGui.LABEL, false);
            g.drawString(font, Component.translatable("screen.terra_towns.stall.not_selling"), left + RX + 22, top + 67,
                    VanillaGui.SUBTLE, false);
            return;
        }
        g.renderItem(row.item(), left + RX + 1, top + 57);
        g.drawString(font, font.plainSubstrByWidth(row.item().getHoverName().getString(), RW - 22),
                left + RX + 22, top + 57, VanillaGui.LABEL, false);
        g.drawString(font, Component.translatable("screen.terra_towns.stall.in_stock", row.stock()),
                left + RX + 22, top + 67, VanillaGui.SUBTLE, false);
        g.drawString(font, Component.translatable("screen.terra_towns.stall.price"), left + RX, top + 80,
                VanillaGui.SUBTLE, false);
        g.drawString(font, priceText(row.priceEmeralds(), row.priceItems()), left + RX, top + 96,
                row.ownerSet() ? 0xFF2E6B2E : VanillaGui.LABEL, false);
        if (mouseX >= left + RX && mouseX < left + RX + 18 && mouseY >= top + 56 && mouseY < top + 74) {
            g.renderTooltip(font, row.item(), mouseX, mouseY);
        }
        for (var child : children()) {
            if (child instanceof SlotButton b && b.isHovered()) {
                b.tooltip(g, mouseX, mouseY);
            }
        }
    }

    private void renderPicker(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("screen.terra_towns.stall.pick.title", pickerSlot + 1),
                left + 8, top + 6, VanillaGui.LABEL, false);
        int y0 = pickTop();
        int visible = pickVisible();
        int lines = pickLines();
        pickScroll = Mth.clamp(pickScroll, 0, Math.max(0, lines - visible));
        g.fill(left + 5, y0 - 1, left + 271, y0 + visible * PICK_ROW + 1, 0xFF8B8B8B);
        List<MerchantInfoPacket.Row> rows = filterMenu ? List.of() : picked();
        List<Integer> useful = filterMenu ? usefulGroups() : List.of();
        for (int i = 0; i < visible && pickScroll + i < lines; i++) {
            int line = pickScroll + i;
            int y = y0 + i * PICK_ROW;
            boolean hover = mouseX >= left + 6 && mouseX < left + 270 && mouseY >= y && mouseY < y + PICK_ROW;
            g.fill(left + 6, y, left + 270, y + PICK_ROW - 1, hover ? 0xFFE0E0E0 : BODY);
            if (filterMenu) {
                boolean on = line == 0 ? filter < 0 : filter == useful.get(line - 1);
                Component name = line == 0 ? Component.translatable("screen.terra_towns.stall.filter.all")
                        : groups.get(useful.get(line - 1)).name();
                g.drawString(font, name, left + 10, y + 5, on ? 0xFF2E6B2E : VanillaGui.LABEL, false);
                continue;
            }
            if (line == 0) {
                g.drawString(font, Component.translatable("screen.terra_towns.stall.pick.none"), left + 10, y + 5,
                        VanillaGui.SUBTLE, false);
                continue;
            }
            MerchantInfoPacket.Row r = rows.get(line - 1);
            g.renderItem(r.item(), left + 8, y + 1);
            int colour = r.stock() > 0 ? VanillaGui.LABEL : VanillaGui.SUBTLE;
            g.drawString(font, font.plainSubstrByWidth(r.item().getHoverName().getString(), 100), left + 28, y + 5, colour, false);
            Component stock = Component.translatable("screen.terra_towns.stall.in_stock", r.stock());
            g.drawString(font, stock, left + 132, y + 5, VanillaGui.SUBTLE, false);
            Component price = priceText(r.priceEmeralds(), r.priceItems());
            g.drawString(font, price, left + 266 - font.width(price), y + 5, colour, false);
            if (r.selling()) {
                g.drawString(font, "#" + (r.slot() + 1), left + 116, y + 5, 0xFF2E6B2E, false);
            }
            if (hover && mouseX < left + 26) {
                g.renderTooltip(font, r.item(), mouseX, mouseY);
            } else if (hover && r.selling()) {
                g.renderTooltip(font, Component.translatable("screen.terra_towns.stall.pick.in_slot", r.slot() + 1), mouseX, mouseY);
            }
        }
        if (!filterMenu && rows.isEmpty()) {
            g.drawString(font, Component.translatable("screen.terra_towns.stall.pick.nothing"), left + 10,
                    y0 + PICK_ROW + 5, VanillaGui.SUBTLE, false);
        }
    }

    private void renderScroller(GuiGraphics g) {
        int extra = info.slots() + 1 - LIST_ROWS;
        if (extra > 1) {
            int j = 139 - (27 + (extra - 1) * 139 / extra);
            int k = 1 + j / extra + 139 / extra;
            int y = Math.min(113, listScroll * k);
            if (listScroll == extra - 1) {
                y = 113;
            }
            g.blitSprite(SCROLLER, left + 94, top + 18 + y, 0, 6, 27);
        } else {
            g.blitSprite(SCROLLER_DISABLED, left + 94, top + 18, 0, 6, 27);
        }
    }

    // --- input ----------------------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (pickerSlot >= 0) {
            pickScroll = Mth.clamp((int) (pickScroll - scrollY), 0, Math.max(0, pickLines() - pickVisible()));
            return true;
        }
        int before = listScroll;
        listScroll = Mth.clamp((int) (listScroll - scrollY), 0, Math.max(0, info.slots() - LIST_ROWS));
        if (listScroll != before) {
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (pickerSlot >= 0) {
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            return pickerClick(mouseX, mouseY);
        }
        dragging = info.slots() > LIST_ROWS && mouseX > left + 94 && mouseX < left + 100
                && mouseY > top + 18 && mouseY <= top + 18 + 140;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!dragging || pickerSlot >= 0) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        int max = info.slots() - LIST_ROWS;
        float f = ((float) mouseY - (top + 18) - 13.5F) / (139 - 27.0F);
        int to = Mth.clamp((int) (f * max + 0.5F), 0, max);
        if (to != listScroll) {
            listScroll = to;
            rebuildWidgets();
        }
        return true;
    }

    /**
     * Escape backs out a step at a time: the filter menu, then the picker, then the screen. The
     * inventory key closes the screen as it closes a villager's trades, except while typing a search.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && pickerSlot >= 0) {
            if (filterMenu) {
                filterMenu = false;
                rebuildWidgets();
            } else {
                closePicker();
            }
            return true;
        }
        boolean typing = pickerSlot >= 0 && searchBox != null && searchBox.isFocused();
        if (!typing && minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * One slot in the list, drawn as vanilla draws a villager's trade: the emeralds it costs, the
     * arrow (crossed out when it's sold out), and the goods; an empty slot says so. Clicking picks
     * it for the controls on the right; clicking it again (or an empty one) opens the picker.
     */
    private final class SlotButton extends Button {

        private final int slot;

        SlotButton(int x, int y, int slot) {
            super(x, y, 88, 20, Component.empty(), b -> {
            }, DEFAULT_NARRATION);
            this.slot = slot;
        }

        @Override
        public void onPress() {
            if (slot == selectedSlot || info.inSlot(slot) == null) {
                openPicker(slot);
            } else {
                selectedSlot = slot;
                rebuildWidgets();
            }
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(g, mouseX, mouseY, partialTick);
            MerchantInfoPacket.Row r = info.inSlot(slot);
            int y = getY() + 1;
            if (r == null) {
                Component empty = Component.translatable("screen.terra_towns.stall.empty_slot");
                g.drawString(font, empty, getX() + (width - font.width(empty)) / 2, getY() + 6, 0xFFA0A0A0, true);
            } else {
                ItemStack cost = new ItemStack(Items.EMERALD, r.priceEmeralds());
                ItemStack goods = r.item().copyWithCount(Math.max(1, r.priceItems()));
                g.pose().pushPose();
                g.pose().translate(0.0F, 0.0F, 100.0F);
                g.renderFakeItem(cost, getX() + 5, y);
                g.renderItemDecorations(font, cost, getX() + 5, y);
                RenderSystem.enableBlend();
                g.blitSprite(r.stock() >= r.priceItems() ? TRADE_ARROW : TRADE_ARROW_OUT, getX() + 55, y + 3, 0, 10, 9);
                g.renderFakeItem(goods, getX() + 68, y);
                g.renderItemDecorations(font, goods, getX() + 68, y);
                g.pose().popPose();
            }
            if (slot == selectedSlot) {
                g.pose().pushPose();
                g.pose().translate(0.0F, 0.0F, 200.0F);
                g.renderOutline(getX(), getY(), width, height, 0xFFFFFFFF);
                g.pose().popPose();
            }
        }

        void tooltip(GuiGraphics g, int mouseX, int mouseY) {
            MerchantInfoPacket.Row r = info.inSlot(slot);
            if (r != null && mouseX >= getX() + 68 && mouseX < getX() + 84) {
                g.renderTooltip(font, r.item(), mouseX, mouseY);
            } else {
                g.renderTooltip(font, Component.translatable(r == null ? "screen.terra_towns.stall.empty_slot.tip"
                        : "screen.terra_towns.stall.slot.tip"), mouseX, mouseY);
            }
        }
    }
}
