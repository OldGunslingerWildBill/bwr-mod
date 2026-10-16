package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelWrenchItem;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PaperlessRecorderData;
import dev.bwr.mod.panel.component.PaperlessRecorderData.AlarmCfg;
import dev.bwr.mod.panel.component.PaperlessRecorderData.Channel;
import dev.bwr.mod.panel.component.PaperlessRecorderData.Group;
import dev.bwr.mod.panel.component.RecorderAlarmLogic;
import dev.bwr.mod.panel.component.RecorderStyle;
import dev.bwr.mod.panel.net.PanelPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The recorder's own setup screen, opened by its MENU key or the wrench. A recorder has up to 48 channels with four
 * alarms each, far more than the generic wrench screen (which does not scroll) can hold, so it has tabs: Channels
 * (a paged list; click one to edit it), Alarms (for a channel), Groups, Display and Messages. Edits are made on a
 * working copy of the recorder's settings; Done sends the settings that differ from what the recorder has, as one
 * ordinary {@code Configure} payload, and the server validates every key with {@code ConfigSchema.merge} as it does
 * for any component. The server only accepts it from a player holding the panel wrench, and the screen says so.
 */
public final class RecorderSetupScreen extends Screen {
    private enum Tab { CHANNELS, ALARMS, GROUPS, DISPLAY, MESSAGES }

    private static final int PAGE = 8;
    private static final int[] COLORS = {0xE0E0E0, 0xFF5050};

    private final BlockPos owner;
    private final int componentId;
    private final PaperlessRecorderData working = new PaperlessRecorderData();
    private final CompoundTag original;
    private Tab tab = Tab.CHANNELS;
    private int channel;
    private int page;
    private int group;
    private int left;
    private int top;
    private int uiWidth;
    private Button done;
    private final List<Object[]> labels = new ArrayList<>();

    public RecorderSetupScreen(PanelBlockEntity owner, PanelComponent component) {
        super(Component.translatable("screen.bwr.recorder.title"));
        this.owner = owner.getBlockPos();
        this.componentId = component.id();
        working.load(component.data().toTag());
        this.original = working.denseConfigTag();
    }

    /** The settings of the working copy that differ from the recorder's, as the {@code Config} of a configure packet. */
    public CompoundTag changes() {
        CompoundTag now = working.denseConfigTag();
        CompoundTag diff = new CompoundTag();
        for (String key : now.getAllKeys()) {
            Tag value = now.get(key);
            if (!value.equals(original.get(key))) {
                diff.put(key, value.copy());
            }
        }
        return diff;
    }

    /** Sends {@link #changes()}; returns whether there was anything to send. */
    public boolean send() {
        CompoundTag diff = changes();
        if (diff.isEmpty()) {
            return false;
        }
        CompoundTag edit = new CompoundTag();
        edit.put("Config", diff);
        PacketDistributor.sendToServer(new PanelPayloads.Configure(owner, componentId, edit));
        return true;
    }

    /** The working copy (checks edit it the way the widgets do). */
    public PaperlessRecorderData working() {
        return working;
    }

    public void showTab(String name) {
        tab = Tab.valueOf(name.toUpperCase(Locale.ROOT));
        rebuildWidgets();
    }

    public void selectChannel(int index) {
        channel = Math.max(0, Math.min(working.channels - 1, index));
        page = channel / PAGE;
        rebuildWidgets();
    }

    public void selectGroup(int index) {
        group = Math.max(0, Math.min(working.style.groups() - 1, index));
        rebuildWidgets();
    }

    // ---- widgets

    @Override
    protected void init() {
        labels.clear();
        uiWidth = Math.min(400, width - 12);
        left = (width - uiWidth) / 2;
        top = Math.max(4, (this.height - 236) / 2);
        Tab[] tabs = Tab.values();
        int tabWidth = uiWidth / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            Button button = Button.builder(Component.translatable("screen.bwr.recorder.tab." + t.name().toLowerCase(Locale.ROOT)), b -> {
                tab = t;
                rebuildWidgets();
            }).bounds(left + i * tabWidth, top + 14, tabWidth - 2, 18).build();
            button.active = t != tab;
            addRenderableWidget(button);
        }
        switch (tab) {
            case CHANNELS -> channelsTab();
            case ALARMS -> alarmsTab();
            case GROUPS -> groupsTab();
            case DISPLAY -> displayTab();
            case MESSAGES -> messagesTab();
        }
        boolean wrench = Minecraft.getInstance().player != null && (Minecraft.getInstance().player.getMainHandItem().getItem() instanceof PanelWrenchItem
                || Minecraft.getInstance().player.getOffhandItem().getItem() instanceof PanelWrenchItem);
        int y = top + 244;
        done = addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
            send();
            onClose();
        }).bounds(left, y, uiWidth / 2 - 2, 20).build());
        done.active = wrench;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + uiWidth / 2 + 2, y, uiWidth / 2 - 2, 20).build());
        if (!wrench) {
            labels.add(new Object[] {left + 2, top + 229, Component.translatable("screen.bwr.recorder.need_wrench"), 0xFFB070});
        }
    }

    private EditBox box(int x, int y, int w, String value, int maxLength, Predicate<String> valid, Consumer<String> apply) {
        EditBox box = new EditBox(font, x, y, w, 16, Component.empty());
        box.setMaxLength(maxLength);
        box.setValue(value);
        box.setResponder(text -> {
            boolean ok = valid.test(text);
            box.setTextColor(COLORS[ok ? 0 : 1]);
            if (ok) {
                apply.accept(text);
            }
        });
        addRenderableWidget(box);
        return box;
    }

    private EditBox text(int x, int y, int w, String value, int maxLength, Consumer<String> apply) {
        return box(x, y, w, value, maxLength, s -> true, apply);
    }

    private static boolean isNumber(String s) {
        try {
            return Double.isFinite(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isInt(String s, int lo, int hi) {
        try {
            int v = Integer.parseInt(s.trim());
            return v >= lo && v <= hi;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String num(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e9 ? Long.toString((long) v) : Double.toString(v);
    }

    private EditBox number(int x, int y, int w, double value, double lo, double hi, Consumer<Double> apply) {
        return box(x, y, w, num(value), 14, s -> isNumber(s), s -> apply.accept(Math.max(lo, Math.min(hi, Double.parseDouble(s.trim())))));
    }

    private EditBox integer(int x, int y, int w, int value, int lo, int hi, Consumer<Integer> apply) {
        return box(x, y, w, Integer.toString(value), 6, s -> isInt(s, lo, hi), s -> apply.accept(Integer.parseInt(s.trim())));
    }

    private EditBox color(int x, int y, int w, int value, Consumer<Integer> apply) {
        return box(x, y, w, String.format(Locale.ROOT, "#%06X", value & 0xFFFFFF), 7, s -> ConfigModel.parseColor(s) != null,
                s -> apply.accept(ConfigModel.parseColor(s)));
    }

    private void label(int x, int y, String key, Object... args) {
        labels.add(new Object[] {x, y, Component.translatable(key, args), 0xCCCCCC});
    }

    private <T> CycleButton<T> cycle(int x, int y, int w, List<T> values, T current, java.util.function.Function<T, Component> name,
                                     Consumer<T> apply) {
        CycleButton<T> button = CycleButton.<T>builder(name).withValues(values).withInitialValue(current).displayOnlyValue()
                .create(x, y, w, 16, Component.empty(), (b, v) -> {
                    apply.accept(v);
                });
        addRenderableWidget(button);
        return button;
    }

    private static Component literal(String s) {
        return Component.literal(s);
    }

    // ---- channels

    private void channelsTab() {
        int listW = 124;
        int y0 = top + 46;
        int pages = (working.channels + PAGE - 1) / PAGE;
        for (int i = 0; i < PAGE; i++) {
            int index = page * PAGE + i;
            if (index >= working.channels) {
                break;
            }
            Channel c = working.channel(index);
            String name = String.format(Locale.ROOT, "%02d %s", index + 1, c.tag.isEmpty() ? "" : c.tag);
            Button button = Button.builder(literal(name), b -> selectChannel0(index)).bounds(left, y0 + i * 20, listW, 18).build();
            button.active = index != channel;
            addRenderableWidget(button);
        }
        Button up = Button.builder(literal("<"), b -> {
            page = Math.max(0, page - 1);
            rebuildWidgets();
        }).bounds(left, y0 + PAGE * 20 + 2, 58, 16).build();
        up.active = page > 0;
        addRenderableWidget(up);
        Button down = Button.builder(literal(">"), b -> {
            page = Math.min(pages - 1, page + 1);
            rebuildWidgets();
        }).bounds(left + 62, y0 + PAGE * 20 + 2, 62, 16).build();
        down.active = page < pages - 1;
        addRenderableWidget(down);
        Channel c = working.channel(channel);
        int x = left + listW + 8;
        int w = uiWidth - listW - 8;
        int fx = x + w / 2 - 10;
        int fw = w - (fx - x);
        labels.add(new Object[] {x, y0 - 12, Component.translatable("screen.bwr.recorder.channel", String.format(Locale.ROOT, "%02d", channel + 1)), 0xFFFFFF});
        int y = y0;
        label(x, y + 4, "component.bwr.field.rec_ch_on");
        cycle(fx, y, fw, List.of(true, false), c.on, v -> Component.translatable(v ? "options.on" : "options.off"), v -> c.on = v);
        y += 18;
        label(x, y + 4, "component.bwr.field.rec_ch_tag");
        text(fx, y, fw, c.tag, PaperlessRecorderData.TAG_MAX, s -> {
            c.tag = s;
        });
        y += 18;
        label(x, y + 4, "component.bwr.field.rec_ch_comment");
        text(fx, y, fw, c.comment, PaperlessRecorderData.COMMENT_MAX, s -> c.comment = s);
        y += 18;
        label(x, y + 4, "component.bwr.field.rec_ch_unit");
        text(fx, y, fw, c.unit, PaperlessRecorderData.UNIT_MAX, s -> c.unit = s);
        y += 18;
        label(x, y + 4, "component.bwr.field.rec_ch_lo");
        number(fx, y, fw, c.lo, -PaperlessRecorderData.LIMIT, PaperlessRecorderData.LIMIT, v -> c.lo = v);
        y += 18;
        label(x, y + 4, "component.bwr.field.rec_ch_hi");
        number(fx, y, fw, c.hi, -PaperlessRecorderData.LIMIT, PaperlessRecorderData.LIMIT, v -> c.hi = v);
        y += 18;
        label(x, y + 4, "component.bwr.field.rec_ch_dec");
        cycle(fx, y, fw, List.of(0, 1, 2, 3, 4), c.dec, v -> literal(Integer.toString(v)), v -> c.dec = v);
        y += 18;
        label(x, y + 4, "component.bwr.field.rec_ch_color");
        color(fx, y, fw, c.color, v -> c.color = v);
        y += 18;
        label(x, y + 4, "component.bwr.field.rec_ch_zone_lo");
        number(fx, y, fw, c.zoneLo, 0, 100, v -> c.zoneLo = v);
        y += 18;
        label(x, y + 4, "component.bwr.field.rec_ch_zone_hi");
        number(fx, y, fw, c.zoneHi, 0, 100, v -> c.zoneHi = v);
    }

    private void selectChannel0(int index) {
        channel = index;
        rebuildWidgets();
    }

    // ---- alarms

    private void alarmsTab() {
        int y0 = top + 38;
        Button prev = Button.builder(literal("<"), b -> {
            channel = Math.max(0, channel - 1);
            rebuildWidgets();
        }).bounds(left, y0, 24, 16).build();
        prev.active = channel > 0;
        addRenderableWidget(prev);
        Button next = Button.builder(literal(">"), b -> {
            channel = Math.min(working.channels - 1, channel + 1);
            rebuildWidgets();
        }).bounds(left + 28, y0, 24, 16).build();
        next.active = channel < working.channels - 1;
        addRenderableWidget(next);
        Channel c = working.channel(channel);
        labels.add(new Object[] {left + 58, y0 + 4, Component.translatable("screen.bwr.recorder.channel", String.format(Locale.ROOT, "%02d %s", channel + 1, c.tag)), 0xFFFFFF});
        int cx = left + 20;
        int[] colX = {cx, cx + 52, cx + 114, cx + 164, cx + 208, cx + 242, cx + 304};
        int[] colW = {48, 58, 46, 40, 30, 58, 36};
        String[] heads = {"al_type", "al_value", "al_hyst", "al_time", "al_level", "al_color", "al_hidden"};
        for (int i = 0; i < heads.length; i++) {
            labels.add(new Object[] {colX[i], y0 + 24, Component.translatable("screen.bwr.recorder.head." + heads[i]), 0xAAAAAA});
        }
        for (int a = 0; a < PaperlessRecorderData.ALARMS; a++) {
            AlarmCfg al = c.alarms[a];
            int y = y0 + 38 + a * 24;
            labels.add(new Object[] {left, y + 4, literal("A" + (a + 1)), 0xCCCCCC});
            cycle(colX[0], y, colW[0], RecorderAlarmLogic.TYPE_OPTIONS, al.type, v -> literal(v.equals("off") ? "-" : v), v -> al.type = v);
            number(colX[1], y, colW[1], al.value, -PaperlessRecorderData.LIMIT, PaperlessRecorderData.LIMIT, v -> al.value = v);
            number(colX[2], y, colW[2], al.hyst, 0, PaperlessRecorderData.LIMIT, v -> al.hyst = v);
            integer(colX[3], y, colW[3], al.time, 0, 3600, v -> al.time = v);
            cycle(colX[4], y, colW[4], List.of(1, 2, 3, 4), al.level, v -> literal(Integer.toString(v)), v -> al.level = v);
            color(colX[5], y, colW[5] + 18, al.color, v -> al.color = v);
            cycle(colX[6] + 18, y, colW[6], List.of(false, true), al.hidden, v -> Component.translatable(v ? "options.on" : "options.off"), v -> al.hidden = v);
        }
        labels.add(new Object[] {left, y0 + 140, Component.translatable("screen.bwr.recorder.alarm_help"), 0x909090});
    }

    // ---- groups

    private void groupsTab() {
        int y0 = top + 38;
        Button prev = Button.builder(literal("<"), b -> selectGroup(group - 1)).bounds(left, y0, 24, 16).build();
        prev.active = group > 0;
        addRenderableWidget(prev);
        Button next = Button.builder(literal(">"), b -> selectGroup(group + 1)).bounds(left + 28, y0, 24, 16).build();
        next.active = group < working.style.groups() - 1;
        addRenderableWidget(next);
        Group g = working.group(group);
        labels.add(new Object[] {left + 58, y0 + 4, Component.translatable("screen.bwr.recorder.group", group + 1, working.style.groups()), 0xFFFFFF});
        int y = y0 + 22;
        label(left, y + 4, "component.bwr.field.rec_g_name");
        text(left + 90, y, uiWidth - 90, g.name, PaperlessRecorderData.GROUP_NAME_MAX, s -> g.name = s);
        y += 22;
        label(left, y + 4, "component.bwr.field.rec_g_slot");
        int perRow = 5;
        int slotW = (uiWidth - 90) / perRow - 2;
        for (int s = 0; s < working.style.perGroup(); s++) {
            int slot = s;
            integer(left + 90 + (s % perRow) * (slotW + 2), y + (s / perRow) * 20, slotW, g.slots[s], 0, PaperlessRecorderData.MAX_CHANNELS,
                    v -> g.slots[slot] = v);
        }
        y += (working.style.perGroup() + perRow - 1) / perRow * 20 + 4;
        for (int t = 0; t < PaperlessRecorderData.TRIP_LINES; t++) {
            int line = t;
            labels.add(new Object[] {left, y + 4, Component.translatable("screen.bwr.recorder.trip", t + 1), 0xCCCCCC});
            cycle(left + 60, y, 40, List.of(false, true), g.tripOn[t], v -> Component.translatable(v ? "options.on" : "options.off"), v -> g.tripOn[line] = v);
            number(left + 104, y, 56, g.tripPos[t], 0, 100, v -> g.tripPos[line] = v);
            color(left + 164, y, 64, g.tripColor[t], v -> g.tripColor[line] = v);
            cycle(left + 232, y, 36, List.of(1, 2, 3), g.tripWidth[t], v -> literal(Integer.toString(v)), v -> g.tripWidth[line] = v);
            y += 20;
        }
        labels.add(new Object[] {left, y + 2, Component.translatable("screen.bwr.recorder.trip_help"), 0x909090});
    }

    // ---- display

    private static final String[] INTERVAL_NAMES = {"5 s", "10 s", "15 s", "30 s", "1 min", "2 min", "5 min", "10 min", "15 min",
            "20 min", "30 min", "1 h", "2 h", "4 h", "10 h"};

    private void displayTab() {
        int y = top + 40;
        int col = uiWidth / 2;
        int x1 = left;
        int fw = col - 100;
        // left column
        label(x1, y + 4, "component.bwr.field.rec_style");
        cycle(x1 + 96, y, fw, RecorderStyle.ids(), working.style.id(), id -> literal(id.equals("yokogawa_dx2000") ? "DX2000" : "DX1000"), id -> {
            RecorderStyle next = RecorderStyle.byId(id);
            if (next != working.style) {
                working.style = next;
                working.channels = next.snapChannels(working.channels);
                for (int g = 0; g < PaperlessRecorderData.MAX_GROUPS; g++) {
                    for (int s = 0; s < PaperlessRecorderData.MAX_PER_GROUP; s++) {
                        working.group(g).slots[s] = PaperlessRecorderData.defaultSlot(next, g, s);
                    }
                }
                channel = Math.min(channel, working.channels - 1);
                group = Math.min(group, next.groups() - 1);
                rebuildWidgets();
            }
        });
        y += 20;
        label(x1, y + 4, "component.bwr.field.rec_channels");
        List<Integer> options = new ArrayList<>();
        for (int o : working.style.channelOptions()) {
            options.add(o);
        }
        cycle(x1 + 96, y, fw, options, working.channels, v -> literal(Integer.toString(v)), v -> {
            working.channels = v;
            channel = Math.min(channel, v - 1);
        });
        y += 20;
        label(x1, y + 4, "component.bwr.field.rec_interval");
        List<Integer> intervals = new ArrayList<>();
        for (int i = 0; i < PaperlessRecorderData.INTERVAL_SECONDS.length; i++) {
            intervals.add(i);
        }
        cycle(x1 + 96, y, fw, intervals, working.interval, v -> literal(INTERVAL_NAMES[v] + "/div"), v -> working.interval = v);
        y += 20;
        label(x1, y + 4, "component.bwr.field.rec_layout");
        cycle(x1 + 96, y, fw, PaperlessRecorderData.LAYOUTS, working.layout, v -> Component.translatable("screen.bwr.recorder.layout." + v), v -> working.layout = v);
        y += 20;
        label(x1, y + 4, "component.bwr.field.rec_grid");
        integer(x1 + 96, y, fw, working.grid, 4, 12, v -> working.grid = v);
        y += 20;
        label(x1, y + 4, "component.bwr.field.rec_line_width");
        cycle(x1 + 96, y, fw, List.of(1, 2, 3), working.lineWidth, v -> literal(Integer.toString(v)), v -> working.lineWidth = v);
        y += 20;
        label(x1, y + 4, "component.bwr.field.rec_background");
        cycle(x1 + 96, y, fw, PaperlessRecorderData.BACKGROUNDS, working.background, v -> Component.translatable("screen.bwr.recorder.background." + v), v -> working.background = v);
        // right column
        int x2 = left + col + 6;
        int fw2 = col - 108;
        y = top + 40;
        label(x2, y + 4, "component.bwr.field.rec_tag_mode");
        cycle(x2 + 100, y, fw2, PaperlessRecorderData.TAG_MODES, working.tagMode, v -> Component.translatable("screen.bwr.recorder.tag_mode." + v), v -> working.tagMode = v);
        y += 20;
        label(x2, y + 4, "component.bwr.field.rec_auto_switch");
        integer(x2 + 100, y, fw2, working.autoSwitch, 0, 60, v -> working.autoSwitch = v > 0 && v < 5 ? 5 : v);
        y += 20;
        label(x2, y + 4, "component.bwr.field.rec_real_clock");
        cycle(x2 + 100, y, fw2, List.of(false, true), working.realClock, v -> Component.translatable(v ? "screen.bwr.recorder.clock_real" : "screen.bwr.recorder.clock_game"), v -> working.realClock = v);
        y += 20;
        label(x2, y + 4, "component.bwr.field.rec_hold");
        cycle(x2 + 100, y, fw2, List.of(true, false), working.hold, v -> Component.translatable(v ? "options.on" : "options.off"), v -> working.hold = v);
        y += 20;
        label(x2, y + 4, "component.bwr.field.rec_ack_group");
        integer(x2 + 100, y, fw2, working.ackGroup, 1, 8, v -> working.ackGroup = v);
        labels.add(new Object[] {left, top + 40 + 6 * 20 + 14, Component.translatable("screen.bwr.recorder.display_help"), 0x909090});
    }

    // ---- messages

    private void messagesTab() {
        int y = top + 40;
        for (int i = 0; i < PaperlessRecorderData.PRESETS; i++) {
            int index = i;
            int x = left + (i % 2) * (uiWidth / 2);
            int row = i / 2;
            labels.add(new Object[] {x, y + row * 22 + 4, literal(Integer.toString(i + 1)), 0xCCCCCC});
            text(x + 14, y + row * 22, uiWidth / 2 - 20, working.presets[i], PaperlessRecorderData.MESSAGE_MAX, s -> working.presets[index] = s);
        }
    }

    // ---- drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        renderBackground(graphics, mouseX, mouseY, partial);
        graphics.fill(left - 6, top - 2, left + uiWidth + 6, top + 272, 0xC0101010);
        graphics.drawString(font, title, left, top + 3, 0xFFFFFF);
        for (Object[] label : labels) {
            graphics.drawString(font, (Component) label[2], (Integer) label[0], (Integer) label[1], (Integer) label[3]);
        }
        for (Renderable widget : renderables) {
            widget.render(graphics, mouseX, mouseY, partial);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
