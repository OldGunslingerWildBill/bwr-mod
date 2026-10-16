package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.mimic.MimicPath;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.WiringOps;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;

/**
 * The linker's screen for a mimic path: choose one signal of the linked plant device and the path is drawn in its
 * colour while that signal is above the path's threshold and dim grey otherwise. It only indicates (a path has no
 * outputs). Apply sends one {@link PanelPayloads.Bind} addressed to {@code -localId}; the server runs the same checks
 * as for a component and binds every copy of the path.
 */
public final class MimicBindScreen extends Screen {
    private final BlockPos owner;
    private final int localId;
    private final PanelPayloads.LinkerScreen device;
    private final Component current;
    private String filter = "";
    private PanelPayloads.SignalEntry selected;
    private KeyList list;
    private Button apply;
    private int left;
    private int panelWidth;

    public MimicBindScreen(PanelBlockEntity block, MimicPath path, PanelPayloads.LinkerScreen device) {
        super(Component.translatable("screen.bwr.mimic_bind.title"));
        this.owner = block.getBlockPos();
        this.localId = path.localId();
        this.device = device;
        this.current = BindModel.describeSource(path.source());
    }

    private boolean usable() {
        return device.status() == PanelPayloads.LINK_OK;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(340, width - 20);
        left = (width - panelWidth) / 2;
        int listTop = 78;
        int buttonY = height - 28;
        if (usable()) {
            EditBox filterBox = new EditBox(font, left, 54, panelWidth, 20, Component.translatable("screen.bwr.panel_bind.filter"));
            filterBox.setValue(filter);
            filterBox.setResponder(value -> {
                filter = value;
                list.refill();
            });
            addRenderableWidget(filterBox);
            list = new KeyList(panelWidth, Math.max(40, buttonY - listTop - 6), listTop);
            list.setX(left);
            addRenderableWidget(list);
            list.refill();
        }
        int third = (panelWidth - 8) / 3;
        apply = addRenderableWidget(Button.builder(Component.translatable("screen.bwr.panel_bind.apply"), button -> {
            if (usable() && selected != null) {
                PacketDistributor.sendToServer(request(WiringOps.BindKind.PLANT, selected.key()));
                onClose();
            }
        }).bounds(left, buttonY, third, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.bwr.panel_bind.clear"), button -> {
            PacketDistributor.sendToServer(request(WiringOps.BindKind.NONE, ""));
            onClose();
        }).bounds(left + third + 4, buttonY, third, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + 2 * (third + 4), buttonY, third, 20).build());
        refresh();
    }

    private PanelPayloads.Bind request(WiringOps.BindKind kind, String key) {
        return new PanelPayloads.Bind(owner, -localId, false, 0, kind.ordinal(), kind == WiringOps.BindKind.PLANT ? device.device() : BlockPos.ZERO,
                key, BlockPos.ZERO, 0, 0, 1.0);
    }

    private void refresh() {
        if (apply != null) {
            apply.active = usable() && selected != null;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics, mouseX, mouseY, partial);
        graphics.fill(left - 6, 4, left + panelWidth + 6, height - 4, 0xC0101010);
        graphics.drawString(font, title, left, 10, 0xFFFFFF);
        Component status = usable()
                ? Component.translatable("screen.bwr.panel_bind.device",
                        Component.translatable(device.deviceName()).getString() + " " + device.device().toShortString())
                : Component.translatable("screen.bwr.panel_bind.link." + device.status());
        graphics.drawString(font, status, left, 24, usable() ? 0x9FE0FF : 0xFFB070);
        graphics.drawString(font, Component.translatable("screen.bwr.panel_bind.current", current), left, 38, 0xCCCCCC);
        for (var widget : renderables) {
            widget.render(graphics, mouseX, mouseY, partial);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The signals of the linked device. */
    private final class KeyList extends ObjectSelectionList<KeyList.Row> {
        KeyList(int listWidth, int listHeight, int y) {
            super(MimicBindScreen.this.minecraft, listWidth, listHeight, y, 14);
        }

        void refill() {
            clearEntries();
            String needle = filter.toLowerCase(Locale.ROOT);
            for (PanelPayloads.SignalEntry entry : device.signals()) {
                if (needle.isEmpty() || entry.key().toLowerCase(Locale.ROOT).contains(needle)
                        || entry.label().toLowerCase(Locale.ROOT).contains(needle)) {
                    Row row = new Row(entry);
                    addEntry(row);
                    if (selected != null && selected.key().equals(entry.key())) {
                        setSelected(row);
                    }
                }
            }
            refresh();
        }

        @Override
        public int getRowWidth() {
            return width - 12;
        }

        private final class Row extends ObjectSelectionList.Entry<Row> {
            private final PanelPayloads.SignalEntry entry;

            Row(PanelPayloads.SignalEntry entry) {
                this.entry = entry;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight,
                               int mouseX, int mouseY, boolean hovering, float partial) {
                String unit = entry.unit().isEmpty() ? "" : " (" + entry.unit() + ")";
                graphics.drawString(font, entry.label() + unit, left + 2, top + 2, 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                selected = entry;
                setSelected(this);
                refresh();
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(entry.label());
            }
        }
    }
}
