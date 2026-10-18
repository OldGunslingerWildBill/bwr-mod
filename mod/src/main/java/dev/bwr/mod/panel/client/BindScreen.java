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
    private EditBox portBox;
    private Button apply;
    /** Ports a selector page shows; a component with more has a pager above the selector. */
    static final int PORTS_PER_PAGE = 12;
    private int portPage = -1;

    public BindScreen(PanelBlockEntity block, PanelComponent component, PanelPayloads.LinkerScreen device) {
        super(Component.translatable("screen.bwr.panel_bind.title", component.label()));
        this.model = new BindModel(block, component, device);
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null && !dev.bwr.mod.panel.LinkTools.heldWrench(player).isEmpty()) {
            model.preselect(); // the Networking Wrench offers the best fitting signal, so one click confirms
        }
    }

    @Override
    protected void init() {
        boolean listed = model.hasPorts() && model.listed();
        boolean scaled = model.hasPorts() && (model.usesScale() || model.usesSinkMode() || model.usesTransform());
        boolean presets = model.hasPorts() && model.usesTransform();
        int currentRows = model.port != null && model.port.output() ? model.sinkCount() + 1 : 1;
        boolean pager = model.hasPorts() && model.choices.size() > PORTS_PER_PAGE;
        layout = new BindLayout(width, height, currentRows, listed, scaled, presets, pager);
        if (!model.hasPorts()) {
            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                    .bounds(layout.left, layout.portY, layout.width, 20).build());
            return;
        }

        List<BindModel.PortChoice> shown = model.choices;
        if (pager) {
            int pages = (model.choices.size() + PORTS_PER_PAGE - 1) / PORTS_PER_PAGE;
            if (portPage < 0 || portPage >= pages) {
                portPage = Math.max(0, model.choices.indexOf(model.port)) / PORTS_PER_PAGE;
            }
            shown = model.choices.subList(portPage * PORTS_PER_PAGE, Math.min(model.choices.size(), (portPage + 1) * PORTS_PER_PAGE));
            if (!shown.contains(model.port)) {
                model.choose(shown.get(0));
            }
            int quarter = layout.width / 4;
            Button previous = Button.builder(Component.literal("<"), button -> {
                portPage--;
                model.choose(model.choices.get(portPage * PORTS_PER_PAGE));
                rebuildWidgets();
            }).bounds(layout.left, layout.pagerY, quarter - 2, 20).build();
            previous.active = portPage > 0;
            addRenderableWidget(previous);
            Button following = Button.builder(Component.literal(">"), button -> {
                portPage++;
                model.choose(model.choices.get(portPage * PORTS_PER_PAGE));
                rebuildWidgets();
            }).bounds(layout.left + layout.width - quarter + 2, layout.pagerY, quarter - 2, 20).build();
            following.active = portPage < pages - 1;
            addRenderableWidget(following);
        }
        addRenderableWidget(CycleButton.<BindModel.PortChoice>builder(BindModel.PortChoice::text)
                .withValues(shown).withInitialValue(model.port)
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
            if (model.mode == BindModel.Mode.CCNET) {
                // The CC network mode: a port of the panel's CC network, looked up on the server.
                portBox = new EditBox(font, layout.left, layout.filterY, layout.width - 72, 20,
                        Component.translatable("screen.bwr.panel_bind.cc_port"));
                portBox.setMaxLength(4);
                portBox.setFilter(text -> text.chars().allMatch(Character::isDigit));
                portBox.setHint(Component.translatable("screen.bwr.panel_bind.cc_port"));
                portBox.setValue(model.portText);
                portBox.setResponder(value -> model.portText = value);
                addRenderableWidget(portBox);
                addRenderableWidget(Button.builder(Component.translatable("screen.bwr.panel_bind.cc_lookup"), button -> {
                    int number = model.portNumber();
                    if (number >= 0) {
                        model.rememberLookup();
                        PacketDistributor.sendToServer(new dev.bwr.mod.panel.networking.NetworkingScreenPayloads.CcPortLookup(
                                model.owner, model.componentId, number));
                    }
                }).bounds(layout.left + layout.width - 68, layout.filterY, 68, 20).build());
            } else {
                filterBox = new EditBox(font, layout.left, layout.filterY, layout.width, 20,
                        Component.translatable("screen.bwr.panel_bind.filter"));
                filterBox.setValue(model.filter);
                filterBox.setResponder(value -> {
                    model.filter = value;
                    list.refill();
                });
                addRenderableWidget(filterBox);
            }
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
        boolean ccMode = model.mode == BindModel.Mode.CCNET;
        Component status = ccMode ? ccStatus() : model.device.ccPort() >= 0
                ? Component.translatable("screen.bwr.panel_bind.cc_hint")
                : model.deviceUsable()
                ? Component.translatable("screen.bwr.panel_bind.device",
                        (model.device.machineName().isEmpty() ? "" : model.device.machineName() + " - ")
                                + Component.translatable(model.device.deviceName()).getString() + " "
                                + model.device.device().toShortString())
                : Component.translatable("screen.bwr.panel_bind.link." + model.device.status());
        graphics.drawString(font, status, layout.left, layout.statusY,
                ccMode ? (model.ccUsable() ? 0x9FE0FF : 0xFFDD33) : model.deviceUsable() ? 0x9FE0FF : 0xFFB070);
        if (model.hasPorts() && model.choices.size() > PORTS_PER_PAGE) {
            int pages = (model.choices.size() + PORTS_PER_PAGE - 1) / PORTS_PER_PAGE;
            graphics.drawCenteredString(font, Component.translatable("screen.bwr.panel_bind.page", portPage + 1, pages),
                    layout.left + layout.width / 2, layout.pagerY + 6, 0xCCCCCC);
        }
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

    /** The status line of the CC network mode: what the last port lookup found. */
    private Component ccStatus() {
        var device = model.device;
        if (device.ccPort() < 0) {
            return Component.translatable("screen.bwr.panel_bind.cc_type_port");
        }
        String port = dev.bwr.mod.panel.MachinePorts.format(device.ccPort());
        return switch (device.status()) {
            case PanelPayloads.LINK_OK -> Component.translatable("screen.bwr.panel_bind.cc_found", port,
                    (device.machineName().isEmpty() ? "" : device.machineName() + " - ")
                            + Component.translatable(device.deviceName()).getString());
            case PanelPayloads.LINK_PORT_CONFLICT -> Component.translatable("screen.bwr.panel_bind.cc_conflict", port,
                    device.machineName());
            case PanelPayloads.LINK_PORT_EMPTY -> Component.translatable("screen.bwr.panel_bind.cc_empty", port);
            default -> Component.translatable("screen.bwr.panel_bind.link." + device.status());
        };
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
