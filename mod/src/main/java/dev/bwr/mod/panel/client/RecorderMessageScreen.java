package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.component.PaperlessRecorderData;
import dev.bwr.mod.panel.net.PanelPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** The FUNC strip's Free message: a text box, and the text is written on the recorder's trend. */
public final class RecorderMessageScreen extends Screen {
    private final BlockPos owner;
    private final int id;
    private EditBox box;

    public RecorderMessageScreen(BlockPos owner, int id) {
        super(Component.translatable("screen.bwr.recorder.free_message"));
        this.owner = owner;
        this.id = id;
    }

    @Override
    protected void init() {
        int width = Math.min(260, this.width - 20);
        int left = (this.width - width) / 2;
        box = new EditBox(font, left, this.height / 2 - 10, width, 20, Component.translatable("screen.bwr.recorder.free_message"));
        box.setMaxLength(PaperlessRecorderData.MESSAGE_MAX);
        addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> send())
                .bounds(left, this.height / 2 + 16, width / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + width / 2 + 2, this.height / 2 + 16, width / 2 - 2, 20).build());
    }

    private void send() {
        submit(box.getValue());
    }

    /** Writes {@code text} on the recorder's trend (what Done does with the box) and closes the screen. */
    public void submit(String text) {
        if (!text.isBlank()) {
            PacketDistributor.sendToServer(new PanelPayloads.RecorderMessage(owner, id, text));
        }
        onClose();
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 257 || key == 335) { // enter
            send();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics, mouseX, mouseY, partial);
        graphics.drawCenteredString(font, title, this.width / 2, this.height / 2 - 28, 0xFFFFFF);
        for (var widget : renderables) {
            widget.render(graphics, mouseX, mouseY, partial);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
