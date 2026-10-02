package com.terraTowns.client.screen;

import com.terraTowns.network.AddSectionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Modal for adding a named section: a name field plus a six-swatch preset colour picker. On
 * confirm it fires an {@link AddSectionPacket} (boundary is created empty in 0.2) and returns to
 * the parent {@link GymLeadersDeskScreen}, which refreshes when the server echoes the change.
 */
public class AddSectionScreen extends Screen {

    /** Six preset section colours, as 6-digit hex tokens matching {@link GymLeadersDeskScreen#parseColor}. */
    static final String[] PRESET_COLORS = {
            "FF5555", // red
            "FFAA00", // orange
            "FFFF55", // yellow
            "55FF55", // green
            "5555FF", // blue
            "AA00AA", // purple
    };

    private static final int PANEL_W = 220;
    private static final int PANEL_H = 140;
    private static final int SWATCH = 24;

    private final GymLeadersDeskScreen parent;
    private final UUID settlementId;

    private int leftPos;
    private int topPos;
    private int swatchTop;
    private EditBox nameField;
    private int selectedColor = 0;

    public AddSectionScreen(GymLeadersDeskScreen parent, UUID settlementId) {
        super(Component.translatable("screen.terra_towns.desk.section.add"));
        this.parent = parent;
        this.settlementId = settlementId;
    }

    @Override
    protected void init() {
        leftPos = (width - PANEL_W) / 2;
        topPos = (height - PANEL_H) / 2;
        swatchTop = topPos + 68;

        nameField = new EditBox(font, leftPos + 16, topPos + 34, PANEL_W - 32, 20,
                Component.translatable("screen.terra_towns.desk.section.name"));
        nameField.setMaxLength(32);
        addRenderableWidget(nameField);
        setInitialFocus(nameField);

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                        b -> minecraft.setScreen(parent))
                .bounds(leftPos + 16, topPos + PANEL_H - 28, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.terra_towns.desk.section.confirm"),
                        b -> confirm())
                .bounds(leftPos + PANEL_W - 106, topPos + PANEL_H - 28, 90, 20).build());
    }

    private void confirm() {
        String name = nameField.getValue().trim();
        if (name.isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(new AddSectionPacket(settlementId, name, PRESET_COLORS[selectedColor]));
        minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            int x = leftPos + 16 + i * (SWATCH + 6);
            if (mouseX >= x && mouseX < x + SWATCH && mouseY >= swatchTop && mouseY < swatchTop + SWATCH) {
                selectedColor = i;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, leftPos + 16, topPos + 12, VanillaGui.LABEL, false);
        graphics.drawString(font, Component.translatable("screen.terra_towns.desk.section.color"),
                leftPos + 16, topPos + 58, VanillaGui.LABEL, false);

        for (int i = 0; i < PRESET_COLORS.length; i++) {
            int x = leftPos + 16 + i * (SWATCH + 6);
            int color = GymLeadersDeskScreen.parseColor(PRESET_COLORS[i]);
            if (i == selectedColor) {
                // Dark selection ring: white vanished against the light vanilla panel.
                graphics.fill(x - 2, swatchTop - 2, x + SWATCH + 2, swatchTop + SWATCH + 2, VanillaGui.LABEL);
            }
            graphics.fill(x, swatchTop, x + SWATCH, swatchTop + SWATCH, color);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Same vanilla container look as the desk, and the same cheap dim instead of
        // super.renderBackground's full-screen blur shader (the desk's FPS-drop fix).
        graphics.fillGradient(0, 0, this.width, this.height, 0xC0101010, 0xD0101010);
        VanillaGui.panel(graphics, leftPos, topPos, PANEL_W, PANEL_H);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
