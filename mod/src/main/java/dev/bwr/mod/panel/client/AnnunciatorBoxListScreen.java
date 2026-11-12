package dev.bwr.mod.panel.client;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.AnnunciatorTrigger;
import dev.bwr.mod.panel.component.InputSource;
import dev.bwr.mod.panel.model.AnnunciatorBoxList;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.AnnunciatorBoxRows;
import dev.bwr.mod.panel.wiring.PlantSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The box list: every light of one annunciator box (windows joined into a box, in any style) on one read-only
 * screen. Per light: its number as the box prints it, its legend, its lens colour, its light address, what its input
 * is wired to (and its alarm sound when that is not the panel alarm and there is room), its trigger and what it shows
 * now. A filter narrows the list to the lights that are not wired, lit, or
 * driven by a computer; clicking a light opens its wrench screen the way the wrench does.
 *
 * <p>Everything but one thing is read from the panels the client already holds, again every half second, so the
 * settings, the wiring and the state follow what happens while the screen is open. The one thing is a machine's own
 * name: the server is asked for the names once, when the screen opens ({@link PanelPayloads.BoxListOpen}), and until
 * (or unless) it answers, a plant signal reads as it does in the wrench screen, by signal and position.
 *
 * <p>Nothing here may break the game: whatever goes wrong while reading or drawing leaves an empty or partial list.
 */
public final class AnnunciatorBoxListScreen extends Screen {
    /** Client ticks between two readings of the panels. */
    private static final int PERIOD = 10;

    private final BlockPos owner;
    private final int componentId;
    private final Map<BlockPos, String> machineNames = new HashMap<>();
    private BoxListLayout layout;
    private LightList list;
    private AnnunciatorBoxList.Filter filter = AnnunciatorBoxList.Filter.ALL;
    /** Every light of the box, in reading order. */
    private List<AnnunciatorBoxRows.Row> rows = List.of();
    /** The lights that pass the filter, as the list shows them. */
    private List<AnnunciatorBoxRows.Row> shown = List.of();
    private boolean requested;
    private boolean named;
    private int age;

    public AnnunciatorBoxListScreen(BlockPos owner, int componentId) {
        super(Component.translatable("screen.bwr.box_list.title"));
        this.owner = owner.immutable();
        this.componentId = componentId;
    }

    @Override
    protected void init() {
        layout = new BoxListLayout(width, height);
        read();
        addRenderableWidget(CycleButton.<AnnunciatorBoxList.Filter>builder(
                        value -> Component.translatable("screen.bwr.box_list.filter." + value.name().toLowerCase(Locale.ROOT)))
                .withValues(AnnunciatorBoxList.Filter.values()).withInitialValue(filter)
                .create(layout.left, layout.filterY, layout.width, 20, Component.translatable("screen.bwr.box_list.filter"),
                        (button, value) -> {
                            filter = value;
                            show(true);
                        }));
        list = new LightList(layout.width, layout.listHeight, layout.listY);
        list.setX(layout.left);
        addRenderableWidget(list);
        show(true);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(layout.left, layout.buttonY, layout.width, 20).build());
        if (!requested) {
            requested = true;
            try {
                PacketDistributor.sendToServer(new PanelPayloads.BoxListOpen(owner, componentId));
            } catch (RuntimeException failure) {
                LogUtils.getLogger().warn("The annunciator box list could not ask for its machine names", failure);
            }
        }
    }

    /** Reads the box from the panels the client holds; anything unreadable is an empty box. */
    private void read() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.level != null && client.level.isLoaded(owner)
                    && client.level.getBlockEntity(owner) instanceof PanelBlockEntity block) {
                rows = AnnunciatorBoxRows.rows(block, componentId);
            } else {
                rows = List.of();
            }
        } catch (RuntimeException failure) {
            rows = List.of();
        }
    }

    /**
     * Puts the lights that pass the filter in the list. While they are the same lights in the same order the entries
     * stay (and the view stays where it was scrolled to) and only take the fresh rows; {@code force} refills anyway.
     */
    private void show(boolean force) {
        List<AnnunciatorBoxRows.Row> next;
        try {
            next = AnnunciatorBoxRows.filter(rows, filter);
        } catch (RuntimeException failure) {
            next = List.of();
        }
        boolean same = !force && list != null && next.size() == shown.size() && next.size() == list.children().size();
        for (int i = 0; same && i < next.size(); i++) {
            same = next.get(i).id() == shown.get(i).id() && next.get(i).owner().equals(shown.get(i).owner());
        }
        shown = next;
        if (list == null) {
            return;
        }
        if (same) {
            for (int i = 0; i < next.size(); i++) {
                list.children().get(i).row = next.get(i);
            }
        } else {
            list.fill(next);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (++age % PERIOD == 0) {
            read();
            show(false);
        }
    }

    /** The server's answer: what the machines the box reads are called. An answer for another box is ignored. */
    public void accept(PanelPayloads.BoxNames payload) {
        if (payload == null || payload.id() != componentId || !owner.equals(payload.pos()) || payload.names() == null) {
            return;
        }
        machineNames.clear();
        for (PanelPayloads.DeviceName entry : payload.names()) {
            if (entry == null || entry.device() == null) {
                continue;
            }
            String name = entry.name() == null || entry.name().isEmpty()
                    ? Component.translatable(entry.blockKey() == null ? "" : entry.blockKey()).getString() : entry.name();
            if (!name.isEmpty()) {
                machineNames.put(entry.device(), name);
            }
        }
        named = true;
    }

    /** What a light's input is wired to, in the words of the wrench screen; a named machine by its name. */
    private Component sourceText(AnnunciatorBoxRows.Row row) {
        InputSource source = row.source();
        switch (row.wiring()) {
            case NONE:
                return Component.translatable("screen.bwr.box_list.not_wired");
            case COMPUTER:
                return Component.translatable("screen.bwr.box_list.computer");
            case PLANT:
                if (source instanceof PlantSource plant) {
                    String name = machineNames.get(plant.device());
                    if (name != null) {
                        return Component.translatable("screen.bwr.box_list.machine", name, plant.key());
                    }
                }
                return BindModel.describeSource(source);
            default:
                return BindModel.describeSource(source);
        }
    }

    /** A light's alarm sound when it is not the default: "sound: Synthesised horn", "sound: mypack:alarm". Empty for the panel alarm. */
    private static String soundNote(AnnunciatorData data) {
        if (dev.bwr.mod.panel.component.AnnunciatorSounds.SYNTH.equals(data.alarmSound)) {
            return Component.translatable("screen.bwr.box_list.sound",
                    Component.translatable("component.bwr.option.AlarmSound.synth")).getString();
        }
        if (dev.bwr.mod.panel.component.AnnunciatorSounds.CUSTOM.equals(data.alarmSound)) {
            return Component.translatable("screen.bwr.box_list.sound", data.customSound.isEmpty()
                    ? Component.translatable("component.bwr.option.AlarmSound.custom").getString() : data.customSound).getString();
        }
        return "";
    }

    private static Component stateText(AnnunciatorBoxList.Show show) {
        return Component.translatable("screen.bwr.box_list.state." + show.name().toLowerCase(Locale.ROOT));
    }

    private static int stateColour(AnnunciatorBoxList.Show show) {
        return switch (show) {
            case ALARM -> 0xFF6060;
            case ACKNOWLEDGED -> 0xFFCC40;
            case OFF -> 0xB0B0B0;
            case NO_SIGNAL -> 0x808080;
        };
    }

    private String clip(Component text, int maxWidth) {
        return font.plainSubstrByWidth(text.getString(), Math.max(0, maxWidth));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics, mouseX, mouseY, partial);
        graphics.fill(layout.left - 6, layout.top, layout.left + layout.width + 6, layout.bottom + 4, 0xC0101010);
        try {
            String name = AnnunciatorBoxRows.boxName(rows);
            Component heading = name.isEmpty() ? title : Component.translatable("screen.bwr.box_list.title_named", name);
            graphics.drawString(font, clip(heading, layout.width), layout.left, layout.titleY, 0xFFFFFF);
            AnnunciatorBoxList.Counts counts = AnnunciatorBoxRows.counts(rows);
            graphics.drawString(font, clip(Component.translatable("screen.bwr.box_list.counts", counts.lights(), counts.wired(),
                    counts.notWired(), counts.inAlarm()), layout.width), layout.left, layout.countsY, 0x9FE0FF);
            int rowLeft = layout.left + BoxListLayout.LIST_MARGIN / 2 + 2;
            int rowWidth = BoxListLayout.rowWidth(layout.width);
            graphics.drawString(font, clip(Component.translatable("screen.bwr.box_list.head.light"), BoxListLayout.middleX() - 2),
                    rowLeft + 2, layout.headY, 0xCCCCCC);
            graphics.drawString(font, clip(Component.translatable("screen.bwr.box_list.head.legend"), BoxListLayout.middleWidth(rowWidth)),
                    rowLeft + BoxListLayout.middleX(), layout.headY, 0xCCCCCC);
            graphics.drawString(font, clip(Component.translatable("screen.bwr.box_list.head.state"), BoxListLayout.SIDE_WIDTH),
                    rowLeft + BoxListLayout.sideX(rowWidth), layout.headY, 0xCCCCCC);
            graphics.drawString(font, clip(Component.translatable("screen.bwr.box_list.hint"), layout.width), layout.left,
                    layout.hintY, 0xCCCCCC);
            graphics.drawString(font, clip(Component.translatable(named ? "screen.bwr.box_list.note_named" : "screen.bwr.box_list.note"),
                    layout.width), layout.left, layout.noteY, 0x909090);
        } catch (RuntimeException failure) {
            // a heading that cannot be drawn is left out; the list below still draws
        }
        // The backdrop was already drawn above; Screen.render would blur it a second time over the heading.
        for (var widget : renderables) {
            widget.render(graphics, mouseX, mouseY, partial);
        }
        if (shown.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.bwr.box_list.empty"), layout.left + layout.width / 2,
                    layout.listY + layout.listHeight / 2 - 4, 0x909090);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The scrolling list of lights, two lines of text each. */
    private final class LightList extends ObjectSelectionList<LightList.Line> {
        LightList(int listWidth, int listHeight, int y) {
            super(AnnunciatorBoxListScreen.this.minecraft, listWidth, listHeight, y, BoxListLayout.ROW);
        }

        void fill(List<AnnunciatorBoxRows.Row> lights) {
            clearEntries();
            for (AnnunciatorBoxRows.Row light : new ArrayList<>(lights)) {
                addEntry(new Line(light));
            }
        }

        @Override
        public int getRowWidth() {
            return BoxListLayout.rowWidth(width);
        }

        private final class Line extends ObjectSelectionList.Entry<Line> {
            private AnnunciatorBoxRows.Row row;

            Line(AnnunciatorBoxRows.Row row) {
                this.row = row;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight,
                               int mouseX, int mouseY, boolean hovering, float partial) {
                try {
                    AnnunciatorBoxRows.Row light = row;
                    AnnunciatorData data = light.data();
                    boolean wired = light.wiring().wired();
                    int middleX = left + BoxListLayout.middleX();
                    int middleWidth = BoxListLayout.middleWidth(rowWidth);
                    int sideX = left + BoxListLayout.sideX(rowWidth);
                    int first = top + 1;
                    int second = top + 10;
                    // line 1: number, lens colour, legend, state
                    graphics.drawString(font, font.plainSubstrByWidth(light.number(), BoxListLayout.NUMBER_WIDTH - 2), left + 2, first,
                            wired ? 0xFFFFFF : 0xA0A0A0);
                    int swatch = left + BoxListLayout.swatchX();
                    graphics.fill(swatch - 1, first - 1, swatch + BoxListLayout.SWATCH + 1, first + BoxListLayout.SWATCH + 1, 0xFF000000);
                    graphics.fill(swatch, first, swatch + BoxListLayout.SWATCH, first + BoxListLayout.SWATCH, 0xFF000000 | data.color);
                    String legend = light.legend();
                    graphics.drawString(font, legend.isEmpty() ? clip(Component.translatable("screen.bwr.box_list.no_legend"), middleWidth)
                            : font.plainSubstrByWidth(legend, middleWidth), middleX, first, legend.isEmpty() ? 0x808080 : wired ? 0xFFFFFF : 0xA0A0A0);
                    AnnunciatorBoxList.Show show = light.show();
                    graphics.drawString(font, clip(stateText(show), BoxListLayout.SIDE_WIDTH), sideX, first, stateColour(show));
                    // line 2: address, source, trigger
                    boolean addressed = data.address >= AnnunciatorData.MIN_ADDRESS;
                    graphics.drawString(font, addressed ? font.plainSubstrByWidth(data.addressText(), BoxListLayout.NUMBER_WIDTH - 2)
                            : clip(Component.translatable("screen.bwr.box_list.none"), BoxListLayout.NUMBER_WIDTH - 2), left + 2, second,
                            addressed ? 0x9FE0FF : 0x808080);
                    // the source, and after it the alarm sound when it is not the panel alarm and there is room for it whole
                    String source = sourceText(light).getString();
                    String sound = soundNote(data);
                    if (!sound.isEmpty() && font.width(source + "  " + sound) <= middleWidth) {
                        source = source + "  " + sound;
                    }
                    graphics.drawString(font, font.plainSubstrByWidth(source, middleWidth), middleX, second, wired ? 0xB0D0B0 : 0x808080);
                    graphics.drawString(font, clip(Component.translatable("screen.bwr.box_list.trigger." + data.condition,
                            AnnunciatorTrigger.format(data.setpoint)), BoxListLayout.SIDE_WIDTH), sideX, second, 0xB0B0B0);
                } catch (RuntimeException failure) {
                    // one light that cannot be drawn leaves its row blank
                }
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                try {
                    PanelClientHooks.openConfig(row.owner(), row.id());
                } catch (RuntimeException failure) {
                    LogUtils.getLogger().warn("The annunciator box list could not open a light", failure);
                }
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(row.number() + " " + row.legend());
            }
        }
    }
}
