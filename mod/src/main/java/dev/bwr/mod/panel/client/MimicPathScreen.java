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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The style screen of one mimic path: colour (the control room palette and a {@code #RRGGBB} field), width, solid or
 * dashed, its shape like a draw.io connector (straight or elbow route; sharp, rounded or curved corners and the corner
 * radius), what each end looks like, the arrow heads' size and colour, arrows along the path and, for a path with a
 * signal bound, the level above which it is lit. Everything is static. Done sends one {@link MimicPayloads.Configure};
 * the server validates every field and updates every copy of the path in the neighbouring blocks.
 */
public final class MimicPathScreen extends Screen {
    /** The control room palette (PANEL-MODELS-SPEC.md) and the default blue of the mimic line, as buttons. */
    private static final int[] PALETTE = {0xE02020, 0x20B040, 0xF0F0E0, 0xFFB000, 0x3070E0, MimicStyle.DEFAULT_COLOR};
    private static final String[] PALETTE_NAMES = {"red", "green", "white", "amber", "blue", "default"};
    private static final String[] LABELS = {"color", "width", "dashed", "route", "corners", "radius", "start", "end",
            "arrow_size", "arrow_color", "flow", "threshold"};
    private static final int ROW_RADIUS = 5;
    private static final int ROW_ARROW_COLOR = 9;
    private static final int ROW_THRESHOLD = 11;

    private final BlockPos owner;
    private final int localId;
    private final boolean bound;
    private String colorText;
    private int lineWidth;
    private boolean dashed;
    private String route;
    private String corners;
    private int radius;
    private int startEnd;
    private int endEnd;
    private int arrowSize;
    /** Blank: the arrows take the line's colour. */
    private String arrowColorText;
    private int flow;
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
        this.colorText = hex(style.color);
        this.lineWidth = style.width;
        this.dashed = style.dashed;
        this.route = style.route;
        this.corners = style.corners;
        this.radius = style.radius;
        this.startEnd = style.startEnd;
        this.endEnd = style.endEnd;
        this.arrowSize = style.arrowSize;
        this.arrowColorText = style.arrowOwnColor ? hex(style.arrowColor) : "";
        this.flow = style.flowEvery;
        this.thresholdText = number(style.threshold);
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    @Override
    protected void init() {
        layout = new ConfigLayout(width, height, LABELS.length);
        int h = layout.rowHeight - 2;
        int row = 0;

        colorBox = new EditBox(font, layout.widgetX + 6 * 22, layout.rowY(row), layout.widgetWidth - 6 * 22, h,
                Component.translatable("screen.bwr.mimic_path.color"));
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
                colorText = hex(rgb);
                colorBox.setValue(colorText);
                refresh();
            }).bounds(layout.widgetX + i * 22, layout.rowY(row), 20, h).build();
            swatch.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("screen.bwr.mimic_path.palette." + PALETTE_NAMES[i])));
            addRenderableWidget(swatch);
        }
        row++;

        addRenderableWidget(CycleButton.<Integer>builder(value -> Component.literal(Integer.toString(value)))
                .withValues(List.of(1, 2, 3, 4)).withInitialValue(lineWidth).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, h, Component.empty(),
                        (button, value) -> lineWidth = value));
        addRenderableWidget(CycleButton.booleanBuilder(Component.translatable("screen.bwr.mimic_path.dashed.on"),
                        Component.translatable("screen.bwr.mimic_path.dashed.off"))
                .withInitialValue(dashed).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, h, Component.empty(),
                        (button, value) -> dashed = value));
        addRenderableWidget(CycleButton.<String>builder(value -> Component.translatable("screen.bwr.mimic_path.route." + value))
                .withValues(MimicStyle.ROUTES).withInitialValue(route).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, h, Component.empty(),
                        (button, value) -> route = value));
        addRenderableWidget(CycleButton.<String>builder(value -> Component.translatable("screen.bwr.mimic_path.corners." + value))
                .withValues(MimicStyle.CORNERS).withInitialValue(corners).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, h, Component.empty(),
                        (button, value) -> corners = value));
        List<Integer> radii = new ArrayList<>();
        for (int r = MimicStyle.MIN_RADIUS; r <= MimicStyle.MAX_RADIUS; r++) {
            radii.add(r);
        }
        addRenderableWidget(CycleButton.<Integer>builder(value -> Component.translatable("screen.bwr.mimic_path.cells", value))
                .withValues(radii).withInitialValue(radius).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, h, Component.empty(),
                        (button, value) -> radius = value));
        addRenderableWidget(CycleButton.<Integer>builder(value -> Component.translatable("screen.bwr.mimic_path.end." + value))
                .withValues(List.of(MimicStyle.END_NONE, MimicStyle.END_ARROW, MimicStyle.END_DOT))
                .withInitialValue(startEnd).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, h, Component.empty(),
                        (button, value) -> startEnd = value));
        addRenderableWidget(CycleButton.<Integer>builder(value -> Component.translatable("screen.bwr.mimic_path.end." + value))
                .withValues(List.of(MimicStyle.END_NONE, MimicStyle.END_ARROW, MimicStyle.END_DOT))
                .withInitialValue(endEnd).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, h, Component.empty(),
                        (button, value) -> endEnd = value));
        List<Integer> sizes = new ArrayList<>();
        for (int s = 0; s <= MimicStyle.MAX_ARROW_SIZE; s++) {
            sizes.add(s);
        }
        addRenderableWidget(CycleButton.<Integer>builder(value -> value == 0
                        ? Component.translatable("screen.bwr.mimic_path.arrow_size.auto")
                        : Component.translatable("screen.bwr.mimic_path.cells", value))
                .withValues(sizes).withInitialValue(arrowSize).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, h, Component.empty(),
                        (button, value) -> arrowSize = value));
        EditBox arrowColor = new EditBox(font, layout.widgetX, layout.rowY(row++), layout.widgetWidth, h,
                Component.translatable("screen.bwr.mimic_path.arrow_color"));
        arrowColor.setMaxLength(8);
        arrowColor.setValue(arrowColorText);
        arrowColor.setHint(Component.translatable("screen.bwr.mimic_path.arrow_color.same"));
        arrowColor.setResponder(value -> {
            arrowColorText = value;
            refresh();
        });
        addRenderableWidget(arrowColor);
        addRenderableWidget(CycleButton.<Integer>builder(value -> value == 0
                        ? Component.translatable("screen.bwr.mimic_path.flow.off")
                        : Component.translatable("screen.bwr.mimic_path.flow.every", value))
                .withValues(List.of(0, 8, 16, 32)).withInitialValue(flow).displayOnlyValue()
                .create(layout.widgetX, layout.rowY(row++), layout.widgetWidth, h, Component.empty(),
                        (button, value) -> flow = value));
        EditBox threshold = new EditBox(font, layout.widgetX, layout.rowY(row++), layout.widgetWidth, h,
                Component.translatable("screen.bwr.mimic_path.threshold"));
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

    /** The arrows' own colour; null when blank (same as the line) or not a colour. */
    private Integer parsedArrowColor() {
        return arrowColorText.isBlank() ? null : ConfigModel.parseColor(arrowColorText);
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
        return parsedColor() != null && parsedThreshold() != null
                && (arrowColorText.isBlank() || parsedArrowColor() != null);
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
        edit.putString("Route", route);
        edit.putString("Corners", corners);
        edit.putInt("Radius", radius);
        edit.putInt("StartEnd", startEnd);
        edit.putInt("EndEnd", endEnd);
        edit.putInt("ArrowSize", arrowSize);
        Integer arrow = parsedArrowColor();
        edit.putBoolean("ArrowOwnColor", arrow != null);
        if (arrow != null) {
            edit.putInt("ArrowColor", arrow);
        }
        edit.putInt("FlowEvery", flow);
        edit.putDouble("Threshold", parsedThreshold());
        return edit;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics, mouseX, mouseY, partial);
        graphics.fill(layout.left - 6, layout.top - 4, layout.left + layout.width + 6, layout.panelBottom + 2, 0xC0101010);
        graphics.drawString(font, title, layout.left, layout.titleY(), 0xFFFFFF);
        boolean rounded = MimicStyle.CORNERS_ROUNDED.equals(corners);
        for (int i = 0; i < LABELS.length; i++) {
            boolean faded = (i == ROW_THRESHOLD && !bound) || (i == ROW_RADIUS && !rounded);
            graphics.drawString(font, Component.translatable("screen.bwr.mimic_path." + LABELS[i]), layout.left,
                    layout.rowY(i) + 5, faded ? 0x777777 : 0xCCCCCC);
        }
        for (int i = 0; i < PALETTE.length; i++) {
            graphics.fill(layout.widgetX + i * 22 + 3, layout.rowY(0) + 3, layout.widgetX + i * 22 + 17,
                    layout.rowY(0) + layout.rowHeight - 5, 0xFF000000 | PALETTE[i]);
        }
        Integer color = parsedColor();
        graphics.fill(layout.left + layout.labelWidth - 16, layout.rowY(0) + 2, layout.left + layout.labelWidth - 4,
                layout.rowY(0) + layout.rowHeight - 4, color == null ? 0xFF000000 : 0xFF000000 | color);
        Integer arrow = arrowColorText.isBlank() ? color : parsedArrowColor();
        graphics.fill(layout.left + layout.labelWidth - 16, layout.rowY(ROW_ARROW_COLOR) + 2, layout.left + layout.labelWidth - 4,
                layout.rowY(ROW_ARROW_COLOR) + layout.rowHeight - 4, arrow == null ? 0xFF000000 : 0xFF000000 | arrow);
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
    private static String number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1.0e9 ? Long.toString((long) value) : Double.toString(value);
    }
}
