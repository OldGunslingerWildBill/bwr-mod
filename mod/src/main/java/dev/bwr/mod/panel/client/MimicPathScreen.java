package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.mimic.MimicPath;
import dev.bwr.mod.panel.mimic.MimicPayloads;
import dev.bwr.mod.panel.mimic.MimicStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Locale;

/**
 * The style screen of one mimic path (spec 2): colour (the control room palette and a {@code #RRGGBB} field),
 * width, solid or dashed, what each end looks like, arrows along the path and, for a path with a signal bound, whether
 * they move and the level above which it is lit. Done sends one {@link MimicPayloads.Configure}; the server validates
 * every field and updates every copy of the path in the neighbouring blocks.
 */
public final class MimicPathScreen extends Screen {
    /** The control room palette (PANEL-MODELS-SPEC.md) and the default blue of the mimic line, as buttons. */
    private static final int[] PALETTE = {0xE02020, 0x20B040, 0xF0F0E0, 0xFFB000, 0x3070E0, MimicStyle.DEFAULT_COLOR};
    private static final String[] PALETTE_NAMES = {"red", "green", "white", "amber", "blue", "default"};

    private final BlockPos owner;
    private final int localId;
    private final boolean bound;
    private String colorText;
    private int lineWidth;
    private boolean dashed;
    private int startEnd;
    private int endEnd;
    private int flow;
    private boolean animate;
    private String thresholdText;
    private ConfigLayout layout;
    private Button done;
    private EditBox colorBox;

    public MimicPathScreen(PanelBlockEntity block, MimicPath path) {
        super(Component.translatable("screen.bwr.mimic_path.title"));
        this.owner = block.getBlockPos();
        this.localId = path.localId();
        this.bound = path.bound();
        MimicStyle style = path.style();
        this.colorText = String.format(Locale.ROOT, "#%06X", style.color & 0xFFFFFF);
        this.lineWidth = style.width;
        this.dashed = style.dashed;
        this.startEnd = style.startEnd;
        this.endEnd = style.endEnd;
        this.flow = style.flowEvery;
        this.animate = style.animate;
        this.thresholdText = ConfigModelFormat.number(style.threshold);
    }

    private static final int ROWS = 8;

    @Override
    protected void init() {
        layout = new ConfigLayout(width, height, ROWS);
        int row = 0;

        colorBox = new EditBox(font, layout.widgetX + 6 * 22, layout.rowY(row), layout.widgetWidth - 6 * 22,
                layout.rowHeight - 2, Component.translatable("screen.bwr.mimic_path.color"));
        colorBox.setMaxLength(8);
        colorBox.setValue(colorText);
        colorBox.setResponder(value -> {
            colorText = value;
            refresh();
        });
        addRenderableWidget(colorBox);
        for (int i = 0; i < PALETTE.length; i++) {
            int rgb = PALETTE[i];
            Button swatch = Button.builder(Component.empty(), button -> {
                colorText = String.format(Locale.ROOT, "#%06X", rgb);
                colorBox.setValue(colorText);
                refresh();
            }).bounds(layout.widgetX + i * 22, layout.rowY(row), 20, layout.rowHeight - 2).build();
            swatch.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("screen.bwr.mimic_path.palette." + PALETTE_NAMES[i])));
            addRenderableWidget(swatch);
        }
        row++;

        addRenderableWidget(CycleButton.<Integer>builder(value -> Component.literal(Integer.toString(value)))
                .withValues(List.of(1, 2, 3, 4)).withInitialValue(lineWidth).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, layout.rowHeight - 2, Component.empty(),
                        (button, value) -> lineWidth = value));
        addRenderableWidget(CycleButton.booleanBuilder(Component.translatable("screen.bwr.mimic_path.dashed.on"),
                        Component.translatable("screen.bwr.mimic_path.dashed.off"))
                .withInitialValue(dashed).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, layout.rowHeight - 2, Component.empty(),
                        (button, value) -> dashed = value));
        addRenderableWidget(CycleButton.<Integer>builder(value -> Component.translatable("screen.bwr.mimic_path.end." + value))
                .withValues(List.of(MimicStyle.END_NONE, MimicStyle.END_ARROW, MimicStyle.END_DOT))
                .withInitialValue(startEnd).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, layout.rowHeight - 2, Component.empty(),
                        (button, value) -> startEnd = value));
        addRenderableWidget(CycleButton.<Integer>builder(value -> Component.translatable("screen.bwr.mimic_path.end." + value))
                .withValues(List.of(MimicStyle.END_NONE, MimicStyle.END_ARROW, MimicStyle.END_DOT))
                .withInitialValue(endEnd).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, layout.rowHeight - 2, Component.empty(),
                        (button, value) -> endEnd = value));
        addRenderableWidget(CycleButton.<Integer>builder(value -> value == 0
                        ? Component.translatable("screen.bwr.mimic_path.flow.off")
                        : Component.translatable("screen.bwr.mimic_path.flow.every", value))
                .withValues(List.of(0, 8, 16, 32)).withInitialValue(flow).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, layout.rowHeight - 2, Component.empty(),
                        (button, value) -> flow = value));
        addRenderableWidget(CycleButton.onOffBuilder(animate).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, layout.rowHeight - 2, Component.empty(),
                        (button, value) -> animate = value));
        EditBox threshold = new EditBox(font, layout.widgetX, layout.rowY(row++), layout.widgetWidth,
                layout.rowHeight - 2, Component.translatable("screen.bwr.mimic_path.threshold"));
        threshold.setMaxLength(16);
        threshold.setValue(thresholdText);
        threshold.setResponder(value -> {
            thresholdText = value;
            refresh();
        });
        addRenderableWidget(threshold);

        done = addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> {
            if (valid()) {
                PacketDistributor.sendToServer(new MimicPayloads.Configure(owner, localId, edit()));
                onClose();
            }
        }).bounds(layout.left, layout.buttonY, layout.width / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(layout.left + layout.width / 2 + 2, layout.buttonY, layout.width / 2 - 2, 20).build());
        refresh();
    }

    private Integer parsedColor() {
        return ConfigModel.parseColor(colorText);
    }

    private Double parsedThreshold() {
        try {
            double value = Double.parseDouble(thresholdText.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean valid() {
        return parsedColor() != null && parsedThreshold() != null;
    }

    private void refresh() {
        if (done != null) {
            done.active = valid();
        }
    }

    private CompoundTag edit() {
        CompoundTag edit = new CompoundTag();
        edit.putInt("Color", parsedColor());
        edit.putInt("Width", lineWidth);
        edit.putBoolean("Dashed", dashed);
        edit.putInt("StartEnd", startEnd);
        edit.putInt("EndEnd", endEnd);
        edit.putInt("FlowEvery", flow);
        edit.putBoolean("Animate", animate);
        edit.putDouble("Threshold", parsedThreshold());
        return edit;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics, mouseX, mouseY, partial);
        graphics.fill(layout.left - 6, layout.top - 4, layout.left + layout.width + 6, layout.panelBottom + 2, 0xC0101010);
        graphics.drawString(font, title, layout.left, layout.titleY(), 0xFFFFFF);
        String[] labels = {"screen.bwr.mimic_path.color", "screen.bwr.mimic_path.width", "screen.bwr.mimic_path.dashed",
                "screen.bwr.mimic_path.start", "screen.bwr.mimic_path.end", "screen.bwr.mimic_path.flow",
                "screen.bwr.mimic_path.animate", "screen.bwr.mimic_path.threshold"};
        for (int i = 0; i < labels.length; i++) {
            int shade = (i >= 6 && !bound) ? 0x777777 : 0xCCCCCC;
            graphics.drawString(font, Component.translatable(labels[i]), layout.left, layout.rowY(i) + 5, shade);
        }
        for (int i = 0; i < PALETTE.length; i++) {
            graphics.fill(layout.widgetX + i * 22 + 3, layout.rowY(0) + 3, layout.widgetX + i * 22 + 17,
                    layout.rowY(0) + layout.rowHeight - 5, 0xFF000000 | PALETTE[i]);
        }
        Integer color = parsedColor();
        graphics.fill(layout.left + layout.labelWidth - 16, layout.rowY(0) + 2, layout.left + layout.labelWidth - 4,
                layout.rowY(0) + layout.rowHeight - 4, color == null ? 0xFF000000 : 0xFF000000 | color);
        if (!bound) {
            graphics.drawString(font, Component.translatable("screen.bwr.mimic_path.unbound"), layout.left,
                    layout.buttonY - 10, 0x999999);
        }
        for (Renderable widget : renderables) {
            widget.render(graphics, mouseX, mouseY, partial);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Number to text for a field: no trailing zeros. */
    private static final class ConfigModelFormat {
        static String number(double value) {
            return value == Math.rint(value) && Math.abs(value) < 1.0e9 ? Long.toString((long) value) : Double.toString(value);
        }
    }
}
