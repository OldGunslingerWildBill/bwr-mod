package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * The linker's functional binding screen: pick a port of the component, pick what to wire it to (a signal
 * or command of the linked device, another component's output, the redstone back face, CC), Apply. Display
 * only; the choices live in {@link BindModel}, placement in {@link BindLayout}, and Apply sends one
 * {@link PanelPayloads.Bind} that the server validates.
 */
public final class BindScreen extends Screen {
    private final BindModel model;
    private BindLayout layout;
    private KeyList list;
    private EditBox filterBox;
    private Button apply;

    public BindScreen(PanelBlockEntity block, PanelComponent component, PanelPayloads.LinkerScreen device) {
        super(Component.translatable("screen.bwr.panel_bind.title", component.label()));
        this.model = new BindModel(block, component, device);
    }

    @Override
    protected void init() {
        boolean listed = model.hasPorts() && model.listed();
        boolean scaled = model.hasPorts() && (model.usesScale() || model.usesSinkMode() || model.usesTransform());
        boolean presets = model.hasPorts() && model.usesTransform();
        int currentRows = model.port != null && model.port.output() ? model.sinkCount() + 1 : 1;
        layout = new BindLayout(width, height, currentRows, listed, scaled, presets);
        if (!model.hasPorts()) {
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                    .bounds(layout.left, layout.portY, layout.width, 20).build());
            return;
        }

        addRenderableWidget(CycleButton.<BindModel.PortChoice>builder(BindModel.PortChoice::text)
                .withValues(model.choices).withInitialValue(model.port)
                .displayOnlyValue()
                .create(layout.left, layout.portY, layout.width, 20, Component.empty(), (button, value) -> {
                    model.choose(value);
                    rebuildWidgets();
                }));
        addRenderableWidget(CycleButton.<BindModel.Mode>builder(mode -> Component.translatable(mode.translationKey()))
                .withValues(model.modes()).withInitialValue(model.mode)
                .displayOnlyValue()
                .create(layout.left, layout.modeY, layout.width, 20, Component.empty(), (button, value) -> {
                    model.setMode(value);
                    rebuildWidgets();
                }));

        if (model.port.output()) {
            for (int i = 0; i < model.sinkCount(); i++) {
                int position = i;
                addRenderableWidget(Button.builder(Component.translatable("screen.bwr.panel_bind.remove", i + 1)
                                .append(": ").append(model.sinkText(i)), button -> {
                            PacketDistributor.sendToServer(model.toRemoveSink(position));
                            onClose();
                        })
                        .bounds(layout.left, layout.currentY + i * BindLayout.ROW, layout.width, 20).build());
            }
        }

        if (listed) {
            filterBox = new EditBox(font, layout.left, layout.filterY, layout.width, 20,
                    Component.translatable("screen.bwr.panel_bind.filter"));
            filterBox.setValue(model.filter);
            filterBox.setResponder(value -> {
                model.filter = value;
                list.refill();
            });
            addRenderableWidget(filterBox);
            list = new KeyList(layout.width, layout.listHeight, layout.listY);
            list.setX(layout.left);
            addRenderableWidget(list);
            list.refill();
        }
        if (scaled) {
            int half = layout.width / 2;
            if (model.usesSinkMode()) {
                addRenderableWidget(CycleButton.booleanBuilder(Component.translatable("screen.bwr.panel_bind.sink_momentary"),
                                Component.translatable("screen.bwr.panel_bind.sink_follow"))
                        .withInitialValue(model.momentary)
                        .create(layout.left, layout.scaleY, half - 4, 20, Component.empty(), (button, value) -> {
                            model.momentary = value;
                            rebuildWidgets();
                        }));
            }
            if (model.usesFixed()) {
                EditBox fixed = new EditBox(font, layout.left + half, layout.scaleY, half, 20,
                        Component.translatable("screen.bwr.panel_bind.fixed"));
                fixed.setHint(Component.translatable("screen.bwr.panel_bind.fixed"));
                fixed.setValue(model.fixedText);
                fixed.setResponder(value -> {
                    model.fixedText = value;
                    refresh();
                });
                addRenderableWidget(fixed);
            } else if (model.usesTransform()) {
                // Scale and Offset side by side: displayed = raw x scale + offset.
                int quarter = layout.width / 4;
                EditBox scale = new EditBox(font, layout.left + 34, layout.scaleY, quarter - 36, 20,
                        Component.translatable("screen.bwr.panel_bind.scale"));
                scale.setValue(model.scaleText);
                scale.setResponder(value -> {
                    model.scaleText = value;
                    refresh();
                });
                addRenderableWidget(scale);
                EditBox offset = new EditBox(font, layout.left + half + 38, layout.scaleY, half - 38, 20,
                        Component.translatable("screen.bwr.panel_bind.offset"));
                offset.setValue(model.offsetText);
                offset.setResponder(value -> {
                    model.offsetText = value;
                    refresh();
                });
                addRenderableWidget(offset);
                addRenderableWidget(Button.builder(Component.translatable("screen.bwr.panel_bind.preset_raw"), button -> {
                    model.presetRaw();
                    rebuildWidgets();
                }).bounds(layout.left, layout.presetY, half - 2, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("screen.bwr.panel_bind.preset_percent"), button -> {
                    model.presetFractionToPercent();
                    rebuildWidgets();
                }).bounds(layout.left + half + 2, layout.presetY, half - 2, 20).build());
            } else {
                EditBox scale = new EditBox(font, layout.left + half, layout.scaleY, half, 20,
                        Component.translatable("screen.bwr.panel_bind.scale"));
                scale.setHint(Component.translatable("screen.bwr.panel_bind.scale"));
                scale.setValue(model.scaleText);
                scale.setResponder(value -> {
                    model.scaleText = value;
                    refresh();
                });
                addRenderableWidget(scale);
            }
        }

        int third = (layout.width - 8) / 3;
        apply = addRenderableWidget(Button.builder(Component.translatable("screen.bwr.panel_bind.apply"), button -> {
            if (model.canApply()) {
                PacketDistributor.sendToServer(model.toBind());
                onClose();
            }
        }).bounds(layout.left, layout.buttonY, third, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.bwr.panel_bind.clear"), button -> {
            PacketDistributor.sendToServer(model.toClear());
            onClose();
        }).bounds(layout.left + third + 4, layout.buttonY, third, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(layout.left + 2 * (third + 4), layout.buttonY, third, 20).build());
        refresh();
    }

    private void refresh() {
        if (apply != null) {
            apply.active = model.canApply();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics, mouseX, mouseY, partial);
        graphics.fill(layout.left - 6, layout.top, layout.left + layout.width + 6, layout.bottom + 4, 0xC0101010);
        graphics.drawString(font, title, layout.left, layout.titleY, 0xFFFFFF);
        Component status = model.deviceUsable()
                ? Component.translatable("screen.bwr.panel_bind.device",
                        Component.translatable(model.device.deviceName()).getString() + " "
                                + model.device.device().toShortString())
                : Component.translatable("screen.bwr.panel_bind.link." + model.device.status());
        graphics.drawString(font, status, layout.left, layout.statusY, model.deviceUsable() ? 0x9FE0FF : 0xFFB070);
        if (model.hasPorts()) {
            if (model.port != null && !model.port.output()) {
                graphics.drawString(font, Component.translatable("screen.bwr.panel_bind.current",
                        model.current().get(0)), layout.left, layout.currentY + 5, 0xCCCCCC);
            } else {
                List<Component> lines = model.current();
                graphics.drawString(font, lines.get(lines.size() - 1), layout.left,
                        layout.currentY + model.sinkCount() * BindLayout.ROW + 5, 0xCCCCCC);
            }
            if (model.usesTransform()) {
                graphics.drawString(font, Component.translatable("screen.bwr.panel_bind.scale"), layout.left,
                        layout.scaleY + 6, 0xCCCCCC);
                graphics.drawString(font, Component.translatable("screen.bwr.panel_bind.offset"),
                        layout.left + layout.width / 2 + 2, layout.scaleY + 6, 0xCCCCCC);
            }
            if (model.usesScale() && !model.usesSinkMode()) {
                graphics.drawString(font, Component.translatable("screen.bwr.panel_bind.scale"), layout.left,
                        layout.scaleY + 6, 0xCCCCCC);
            }
        }
        // The backdrop was already drawn above. Calling Screen.render here applies a second
        // blur after the heading and status have been drawn.
        for (var widget : renderables) {
            widget.render(graphics, mouseX, mouseY, partial);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The scrolling list of signals, commands or component outputs for the current mode. */
    private final class KeyList extends ObjectSelectionList<KeyList.Row> {
        KeyList(int listWidth, int listHeight, int y) {
            super(BindScreen.this.minecraft, listWidth, listHeight, y, 14);
        }

        void refill() {
            clearEntries();
            for (BindModel.Entry entry : model.entries()) {
                Row row = new Row(entry);
                addEntry(row);
                if (model.selected != null && model.selected.key().equals(entry.key())
                        && model.selected.refId() == entry.refId()) {
                    setSelected(row);
                }
            }
            refresh();
        }

        @Override
        public int getRowWidth() {
            return width - 12;
        }

        private final class Row extends ObjectSelectionList.Entry<Row> {
            private final BindModel.Entry entry;

            Row(BindModel.Entry entry) {
                this.entry = entry;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight,
                               int mouseX, int mouseY, boolean hovering, float partial) {
                graphics.drawString(font, entry.label(), left + 2, top + 2, 0xFFFFFF);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                boolean wasMomentary = model.momentary;
                String wasFixed = model.fixedText;
                model.select(entry);
                setSelected(this);
                if (wasMomentary != model.momentary || !wasFixed.equals(model.fixedText)) {
                    rebuildWidgets();
                }
                refresh();
                return true;
            }

            @Override
            public Component getNarration() {
                return entry.label();
            }
        }
    }
}
