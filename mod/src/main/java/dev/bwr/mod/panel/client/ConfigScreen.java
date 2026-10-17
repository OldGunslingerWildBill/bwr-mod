package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.ConfigField;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * The wrench's functional config screen: label, the type's config fields and
 * its input sources, generated from the type's schema with vanilla widgets.
 * Display only; edits live in {@link ConfigModel}, placement in
 * {@link ConfigLayout}, and Done sends one configure packet that the server
 * validates field by field.
 */
public final class ConfigScreen extends Screen {
    private final BlockPos owner;
    private final int componentId;
    private final ConfigModel model;
    private final String typeKey;
    private ConfigLayout layout;
    private ScrollRows rows;
    private Button done;
    private final List<Object[]> labels = new ArrayList<>();

    public ConfigScreen(PanelBlockEntity owner, PanelComponent component) {
        super(Component.translatable("screen.bwr.panel_config"));
        this.owner = owner.getBlockPos();
        this.componentId = component.id();
        this.model = new ConfigModel(component);
        this.typeKey = component.type().translationKey();
    }

    @Override
    protected void init() {
        layout = new ConfigLayout(width, height, model.rowCount());
        rows = new ScrollRows(layout);
        labels.clear();
        int row = 0;

        EditBox label = box(row++, model.label, 64);
        label.setResponder(value -> {
            model.label = value;
            refresh();
        });
        rowLabel(row - 1, Component.translatable("component.bwr.field.label"));

        for (ConfigModel.FieldRow fieldRow : model.fields) {
            ConfigField field = fieldRow.field;
            int y = layout.rowY(row);
            rowLabel(row, Component.translatable(field.labelKey()));
            switch (field.kind()) {
                case ENUM -> rows.add(addRenderableWidget(CycleButton.<String>builder(value -> Component.translatable(
                                "component.bwr.option." + field.key() + "." + value))
                        .withValues(field.options()).withInitialValue(fieldRow.text).displayOnlyValue()
                        .create(layout.widgetX, y, layout.widgetWidth, layout.rowHeight - 2, Component.empty(),
                                (button, value) -> {
                                    fieldRow.text = value;
                                    refresh();
                                })));
                case BOOL -> rows.add(addRenderableWidget(CycleButton.onOffBuilder(Boolean.parseBoolean(fieldRow.text)).displayOnlyValue()
                        .create(layout.widgetX, y, layout.widgetWidth, layout.rowHeight - 2, Component.empty(),
                                (button, value) -> {
                                    fieldRow.text = Boolean.toString(value);
                                    refresh();
                                })));
                default -> {
                    EditBox box = box(row, fieldRow.text, field.kind() == ConfigField.Kind.TEXT ? field.maxLength() : 24);
                    box.setResponder(value -> {
                        fieldRow.text = value;
                        refresh();
                    });
                }
            }
            row++;
        }

        for (ConfigModel.InputRow input : model.inputs) {
            int y = layout.rowY(row);
            rowLabel(row, Component.translatable(input.port.labelKey()));
            if (input.bound != null) {
                Button bound = rows.add(addRenderableWidget(Button.builder(input.bound, button -> {
                }).bounds(layout.widgetX, y, layout.widgetWidth, layout.rowHeight - 2).build()));
                bound.active = false;
                row++;
                continue;
            }
            int half = layout.widgetWidth / 2;
            rows.add(addRenderableWidget(CycleButton.booleanBuilder(Component.translatable("screen.bwr.panel_config.constant"),
                            Component.translatable("screen.bwr.panel_config.none"))
                    .withInitialValue(input.constant).displayOnlyValue()
                    .create(layout.widgetX, y, half - 2, layout.rowHeight - 2, Component.empty(),
                            (button, value) -> {
                                input.constant = value;
                                refresh();
                            })));
            EditBox value = new EditBox(font, layout.widgetX + half, y, layout.widgetWidth - half, layout.rowHeight - 2,
                    Component.empty());
            value.setValue(input.valueText);
            value.setResponder(text -> {
                input.valueText = text;
                refresh();
            });
            rows.add(addRenderableWidget(value));
            row++;
        }

        done = addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> {
            if (model.valid()) {
                PacketDistributor.sendToServer(new PanelPayloads.Configure(owner, componentId, model.toEdit()));
                onClose();
            }
        }).bounds(layout.left, layout.buttonY, layout.width / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(layout.left + layout.width / 2 + 2, layout.buttonY, layout.width / 2 - 2, 20).build());
        refresh();
    }

    private EditBox box(int row, String value, int maxLength) {
        EditBox box = new EditBox(font, layout.widgetX, layout.rowY(row), layout.widgetWidth, layout.rowHeight - 2,
                Component.empty());
        box.setMaxLength(Math.max(1, maxLength));
        box.setValue(value);
        rows.add(addRenderableWidget(box));
        return box;
    }

    private void rowLabel(int row, Component text) {
        // Labels are drawn in render() from this list so they do not need widgets.
        labels.add(new Object[] {row, text});
    }

    private void refresh() {
        if (done != null) {
            done.active = model.valid();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics, mouseX, mouseY, partial);
        graphics.fill(layout.left - 6, layout.top - 4, layout.left + layout.width + 6, layout.panelBottom + 2, 0xC0101010);
        graphics.drawString(font, Component.translatable("screen.bwr.panel_config.title",
                Component.translatable(typeKey)), layout.left, layout.titleY(), 0xFFFFFF);
        for (Object[] entry : labels) {
            int labelRow = (Integer) entry[0];
            if (rows.visible(labelRow)) {
                graphics.drawString(font, (Component) entry[1], layout.left, rows.y(labelRow) + 5, 0xCCCCCC);
            }
        }
        int row = 1;
        for (ConfigModel.FieldRow fieldRow : model.fields) {
            if (fieldRow.field.kind() == ConfigField.Kind.COLOR && rows.visible(row)) {
                Integer color = ConfigModel.parseColor(fieldRow.text);
                int y = rows.y(row);
                graphics.fill(layout.left + layout.labelWidth - 16, y + 2, layout.left + layout.labelWidth - 4,
                        y + layout.rowHeight - 4, color == null ? 0xFF000000 : 0xFF000000 | color);
            }
            row++;
        }
        // Screen.render blurs the background again. We already drew it above; drawing it twice
        // blurs this screen's title and labels while widgets remain sharp.
        for (Renderable widget : renderables) {
            widget.render(graphics, mouseX, mouseY, partial);
        }
        rows.renderScrollbar(graphics);
    }

    /** The wheel over a selector turns it (vanilla); anywhere else it scrolls the rows when they overflow. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY) || rows.scroll(scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
