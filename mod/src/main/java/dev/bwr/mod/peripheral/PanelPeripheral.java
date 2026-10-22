package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelBus;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelDrawTarget;
import dev.bwr.mod.panel.component.PanelTextTarget;
import dev.bwr.mod.panel.component.PlantComputerData;
import dev.bwr.mod.panel.plant.PlantApi;
import dev.bwr.mod.panel.plant.PlantState;
import dev.bwr.mod.panel.plant.PlantTouch;
import dev.bwr.mod.panel.component.PortSpec;
import dev.bwr.mod.panel.wiring.CcSource;
import dev.bwr.mod.panel.wiring.PanelWiring;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@code bwr_panel}: the whole panel group, seen from any of its blocks (spec section 8).
 *
 * <p>Lua API (every call runs on the server thread; a label is unique within the group):
 * <ul>
 *   <li>{@code list()}, {@code get(label)}: what is on the panel and its live values;</li>
 *   <li>{@code getInputs()}: the current output of every control (switches, knobs) by label and port;</li>
 *   <li>{@code set(label, value)} / {@code setPort(label, port, value)}: the value of a CC-sourced input;</li>
 *   <li>{@code setText(label, text)}: the text of a nameplate or text display;</li>
 *   <li>drawing, for components whose data implements {@link PanelDrawTarget}: {@code getSize, clear,
 *       setPixel, fill, drawText, blit, setPalette, write};</li>
 *   <li>the plant computer calls ({@code plantList}, {@code plantInfo}, {@code plantClear}, {@code plantClearPage},
 *       {@code plantSetPage}, {@code plantTitle}, {@code plantSet}, {@code plantValue}, {@code plantPush}, {@code plantRemove},
 *       {@code plantFlush}), for components of type {@code bwr:plant_computer}: see {@link PlantApi} and PLANT-COMPUTER.md.
 *       Every panel peripheral also answers to the type {@value #PLANT_TYPE}, so {@code peripheral.find("bwr_plant_computer")}
 *       finds a panel that has one (call {@code plantList()} to see which);</li>
 *   <li>event {@code plant_computer_touch(name, label, page, key)} when a player touches a soft key of a plant computer page
 *       (read only: it only raises this event);</li>
 *   <li>event {@code bwr_panel_input(name, label, value, port)} whenever a player operates a control.
 *       {@code name} is the peripheral name the computer sees for the panel (the side, or the modem name such as
 *       {@code bwr_panel_3}), like {@code monitor_touch}'s first argument. A computer receives at most one event
 *       per output change and group; when it is attached to the group through several blocks, the name is the
 *       one attached first (and the next one if that detaches).</li>
 * </ul>
 * The calls go through {@code LivePeripheral}, which converts Lua arguments to {@code int}, {@code double},
 * {@code boolean}, {@code String} and {@code String[]} only, so the API is built from those.
 */
public final class PanelPeripheral implements IPeripheral {
    public static final String TYPE = "bwr_panel";
    public static final String EVENT = "bwr_panel_input";
    /** The type a panel peripheral also answers to, and the event a touched soft key raises. */
    public static final String PLANT_TYPE = "bwr_plant_computer";
    public static final String TOUCH_EVENT = "plant_computer_touch";

    private record Attachment(Level level, BlockPos pos, IComputerAccess computer, int computerId, String name) {
    }

    private static final List<Attachment> ATTACHED = new CopyOnWriteArrayList<>();
    private static boolean installed;

    private final PanelBlockEntity block;

    public PanelPeripheral(PanelBlockEntity block) {
        this.block = block;
    }

    // ---- attachment and the input event

    /** Starts routing {@code bwr_panel_input} events. Idempotent; called when CC support is set up. */
    public static synchronized void install() {
        if (!installed) {
            installed = true;
            PanelBus.addOutputListener(PanelPeripheral::onOutput);
            PlantTouch.addListener(PanelPeripheral::onTouch);
        }
    }

    static void attached(Level level, BlockPos pos, IComputerAccess computer) {
        ATTACHED.add(new Attachment(level, pos.immutable(), computer, computer.getID(), computer.getAttachmentName()));
    }

    static void detached(IComputerAccess computer) {
        ATTACHED.removeIf(attachment -> attachment.computer == computer);
    }

    /** How many computers are attached to panel blocks (tests). */
    public static int attachedCount() {
        return ATTACHED.size();
    }

    /** Test hook: attaches a computer to the panel peripheral at {@code pos} as a modem would. */
    public static void attachForTest(Level level, BlockPos pos, IComputerAccess computer) {
        attached(level, pos, computer);
    }

    public static void detachForTest(IComputerAccess computer) {
        detached(computer);
    }

    private static void onOutput(ServerLevel level, PanelBlockEntity owner, PanelComponent component, int index,
                                 double value) {
        if (ATTACHED.isEmpty() || component.type() == null) {
            return;
        }
        PanelGroup group = owner.group();
        if (group == null) {
            return;
        }
        List<PortSpec> ports = component.type().outputPorts(component.data());
        String port = index >= 0 && index < ports.size() ? ports.get(index).key() : Integer.toString(index);
        // One event per computer (CC gives every attachment its own IComputerAccess, so key on the computer id),
        // however many blocks of the group it is attached to: the first attachment wins.
        java.util.Set<Integer> notified = new java.util.HashSet<>();
        for (Attachment attachment : ATTACHED) {
            if (attachment.level == level && group.contains(attachment.pos) && notified.add(attachment.computerId)) {
                try {
                    attachment.computer.queueEvent(EVENT, attachment.name, component.label(), value, port);
                } catch (RuntimeException e) {
                    ATTACHED.remove(attachment); // the computer detached without telling us
                }
            }
        }
    }

    /** A soft key was touched: one event per attached computer of the panel group, like {@code bwr_panel_input}. */
    private static void onTouch(ServerLevel level, PanelBlockEntity owner, PanelComponent component, int page, int key) {
        PanelGroup group = owner == null ? null : owner.group();
        if (ATTACHED.isEmpty() || group == null) {
            return;
        }
        java.util.Set<Integer> notified = new java.util.HashSet<>();
        for (Attachment attachment : ATTACHED) {
            if (attachment.level == level && group.contains(attachment.pos) && notified.add(attachment.computerId)) {
                try {
                    attachment.computer.queueEvent(TOUCH_EVENT, attachment.name, component.label(), page, key);
                } catch (RuntimeException e) {
                    ATTACHED.remove(attachment);
                }
            }
        }
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public java.util.Set<String> getAdditionalTypes() {
        return java.util.Set.of(PLANT_TYPE);
    }

    @Override
    public boolean equals(IPeripheral other) {
        return other instanceof PanelPeripheral p && p.block == block;
    }

    // ---- lookup

    private record Found(PanelBlockEntity owner, PanelComponent component) {
    }

    /**
     * Labels are unique within a group (duplicates from a merge are renamed when the group forms), but if two
     * components ever share one, it is never dropped: the first in canonical order (owner position, then id) keeps
     * the plain label and the others are listed as {@code label#2}, {@code label#3}, in order.
     */
    private Map<String, Found> keyed() {
        Map<String, Found> keyed = new LinkedHashMap<>();
        PanelGroup group = block.group();
        if (group == null || block.getLevel() == null) {
            return keyed;
        }
        for (PanelGroup.Entry entry : group.components(block.getLevel())) {
            String base = entry.component().label();
            String key = base;
            for (int n = 2; keyed.containsKey(key); n++) {
                key = base + "#" + n;
            }
            keyed.put(key, new Found(entry.owner(), entry.component()));
        }
        return keyed;
    }

    private Found find(String label) throws LuaException {
        Found found = keyed().get(label);
        if (found != null && found.component.type() != null) {
            return found;
        }
        throw new LuaException("No component labelled " + label);
    }

    // ---- reading

    /** Every component in the group by label: {@code {type, width, height}}. */
    @LuaFunction(mainThread = true)
    public Map<String, Object> list() {
        Map<String, Object> labels = new LinkedHashMap<>();
        for (Map.Entry<String, Found> keyed : keyed().entrySet()) {
            PanelComponent component = keyed.getValue().component;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", component.type() == null ? "unknown" : component.type().id().toString());
            entry.put("width", component.w());
            entry.put("height", component.h());
            labels.put(keyed.getKey(), entry);
        }
        return labels;
    }

    /** One component: its type, footprint and live input/output values (a port with no signal is absent). */
    @LuaFunction(mainThread = true)
    public Map<String, Object> get(String label) throws LuaException {
        return describe(find(label).component);
    }

    /**
     * The current output of every control on the panel: {@code {label = {port = value}}}. Components without
     * outputs (lamps, nameplates) are not listed.
     */
    @LuaFunction(mainThread = true)
    public Map<String, Object> getInputs() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Found> keyed : keyed().entrySet()) {
            PanelComponent component = keyed.getValue().component;
            if (component.type() == null || component.outputCount() == 0) {
                continue;
            }
            Map<String, Object> ports = new LinkedHashMap<>();
            List<PortSpec> outputPorts = component.type().outputPorts(component.data());
            for (int i = 0; i < outputPorts.size(); i++) {
                ports.put(outputPorts.get(i).key(), component.output(i));
            }
            result.put(keyed.getKey(), ports);
        }
        return result;
    }

    private static Map<String, Object> describe(PanelComponent component) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", component.type().id().toString());
        result.put("label", component.label());
        result.put("width", component.w());
        result.put("height", component.h());
        Map<String, Object> inputs = new LinkedHashMap<>();
        List<PortSpec> inputPorts = component.type().inputPorts(component.data());
        for (int i = 0; i < inputPorts.size(); i++) {
            double value = component.input(i);
            if (!Double.isNaN(value)) {
                inputs.put(inputPorts.get(i).key(), value);
            }
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        List<PortSpec> outputPorts = component.type().outputPorts(component.data());
        for (int i = 0; i < outputPorts.size(); i++) {
            outputs.put(outputPorts.get(i).key(), component.output(i));
        }
        result.put("inputs", inputs);
        result.put("outputs", outputs);
        return result;
    }

    // ---- writing

    /**
     * Sets the first input of a component, which must be wired to CC in the linker's binding screen. Returns
     * true when the value was accepted (not necessarily changed).
     */
    @LuaFunction(mainThread = true)
    public boolean set(String label, double value) throws LuaException {
        Found found = find(label);
        List<PortSpec> ports = found.component.type().inputPorts(found.component.data());
        if (ports.isEmpty()) {
            throw new LuaException(label + " has no inputs");
        }
        return setInput(found, 0, ports.get(0).key(), value);
    }

    /** Sets the named input ({@code get(label).inputs} keys, or the port key from the screen) of a component. */
    @LuaFunction(mainThread = true)
    public boolean setPort(String label, String port, double value) throws LuaException {
        Found found = find(label);
        List<PortSpec> ports = found.component.type().inputPorts(found.component.data());
        for (int i = 0; i < ports.size(); i++) {
            if (ports.get(i).key().equals(port)) {
                return setInput(found, i, port, value);
            }
        }
        throw new LuaException(label + " has no input " + port);
    }

    private static boolean setInput(Found found, int input, String port, double value) throws LuaException {
        if (!Double.isFinite(value)) {
            throw new LuaException("value must be a finite number");
        }
        if (!(found.component.source(input) instanceof CcSource)) {
            throw new LuaException("Input " + port + " of " + found.component.label() + " is not wired to CC");
        }
        return PanelWiring.setCc(found.owner, found.component, input, value);
    }

    /** Sets the text of a nameplate or text display. Returns whether it changed. */
    @LuaFunction(mainThread = true)
    public boolean setText(String label, String text) throws LuaException {
        Found found = find(label);
        if (!(found.component.data() instanceof PanelTextTarget target)) {
            throw new LuaException(label + " has no text");
        }
        boolean changed = target.setText(text);
        if (changed) {
            found.owner.displayChanged(found.component);
        }
        return changed;
    }

    /**
     * Sets one named text field of a component whose text has several: a nameplate's {@code line1} to {@code line3},
     * {@code cornerLeft}, {@code cornerRight} and {@code tag}, or a core display's {@code caption}. Returns whether it
     * changed; an unknown field is an error that lists the component's fields.
     */
    @LuaFunction(mainThread = true)
    public boolean setTextField(String label, String field, String text) throws LuaException {
        Found found = find(label);
        if (!(found.component.data() instanceof PanelTextTarget target) || target.textFields().isEmpty()) {
            throw new LuaException(label + " has no named text fields");
        }
        if (!target.textFields().contains(field)) {
            throw new LuaException(label + " has no text field " + field + "; its fields are " + String.join(", ", target.textFields()));
        }
        boolean changed = target.setField(field, text);
        if (changed) {
            found.owner.displayChanged(found.component);
        }
        return changed;
    }

    // ---- drawing: thin wrappers over PanelDrawTarget

    private record Surface(Found found, PanelDrawTarget target) {
    }

    private Surface surface(String label) throws LuaException {
        Found found = find(label);
        if (found.component.data() instanceof PanelDrawTarget target) {
            return new Surface(found, target);
        }
        throw new LuaException(label + " is not a drawable display");
    }

    private static boolean changed(Surface surface, boolean result) {
        if (result) {
            surface.found.owner.displayChanged(surface.found.component);
        }
        return result;
    }

    /** Width and height: pixels for a graphic display, characters and rows for a character LCD. */
    @LuaFunction(mainThread = true)
    public Object[] getSize(String label) throws LuaException {
        PanelDrawTarget target = surface(label).target;
        return new Object[] {target.drawWidth(), target.drawHeight()};
    }

    /** Clears to a palette colour (a character LCD clears its text and ignores the colour). */
    @LuaFunction(mainThread = true)
    public boolean clear(String label, int color) throws LuaException {
        Surface surface = surface(label);
        return changed(surface, surface.target.clear(color));
    }

    @LuaFunction(mainThread = true)
    public boolean setPixel(String label, int x, int y, int color) throws LuaException {
        Surface surface = surface(label);
        return changed(surface, surface.target.setPixel(x, y, color));
    }

    @LuaFunction(mainThread = true)
    public boolean fill(String label, int x, int y, int width, int height, int color) throws LuaException {
        Surface surface = surface(label);
        return changed(surface, surface.target.fill(x, y, width, height, color));
    }

    @LuaFunction(mainThread = true)
    public boolean drawText(String label, int x, int y, String text, int color) throws LuaException {
        Surface surface = surface(label);
        return changed(surface, surface.target.drawText(x, y, text, color));
    }

    /** Rows of hex digits, one palette index per digit, from the top-left corner. */
    @LuaFunction(mainThread = true)
    public boolean blit(String label, String[] rows) throws LuaException {
        Surface surface = surface(label);
        return changed(surface, surface.target.blit(rows));
    }

    @LuaFunction(mainThread = true)
    public boolean setPalette(String label, int index, int rgb) throws LuaException {
        Surface surface = surface(label);
        return changed(surface, surface.target.setPalette(index, rgb));
    }

    /** Character LCD: writes text at a 0-based row and column. */
    @LuaFunction(mainThread = true)
    public boolean write(String label, int row, int column, String text) throws LuaException {
        Surface surface = surface(label);
        return changed(surface, surface.target.write(row, column, text));
    }

    // ---- the plant computer (PlantApi): pages, elements, trends

    private PlantComputerData plant(String label) throws LuaException {
        Found found = find(label);
        if (found.component.data() instanceof PlantComputerData data) {
            return data;
        }
        throw new LuaException(label + " is not a plant computer");
    }

    private interface PlantCall<T> {
        T run() throws PlantState.Rejected;
    }

    private static <T> T guarded(PlantCall<T> call) throws LuaException {
        try {
            return call.run();
        } catch (PlantState.Rejected e) {
            throw new LuaException(e.getMessage());
        }
    }

    /** The plant computers of the panel group by label: {@code {label = {cols, rows, mode}}}. */
    @LuaFunction(mainThread = true)
    public Map<String, Object> plantList() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Found> keyed : keyed().entrySet()) {
            if (keyed.getValue().component.data() instanceof PlantComputerData data) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("cols", data.layout().cols());
                entry.put("rows", data.layout().rows());
                entry.put("mode", data.mode);
                result.put(keyed.getKey(), entry);
            }
        }
        return result;
    }

    /** Grid size, mode, page shown, counts and the limits of one plant computer. */
    @LuaFunction(mainThread = true)
    public Map<String, Object> plantInfo(String label) throws LuaException {
        return PlantApi.info(plant(label));
    }

    /** Clears every page and shows page 1. Returns whether anything changed. */
    @LuaFunction(mainThread = true)
    public boolean plantClear(String label) throws LuaException {
        PlantComputerData data = plant(label);
        return guarded(() -> PlantApi.clear(data));
    }

    @LuaFunction(mainThread = true)
    public boolean plantClearPage(String label, int page) throws LuaException {
        PlantComputerData data = plant(label);
        return guarded(() -> PlantApi.clearPage(data, page));
    }

    /** Shows page {@code page} (1 to 8). */
    @LuaFunction(mainThread = true)
    public boolean plantSetPage(String label, int page) throws LuaException {
        PlantComputerData data = plant(label);
        return guarded(() -> PlantApi.setPage(data, page));
    }

    @LuaFunction(mainThread = true)
    public boolean plantTitle(String label, int page, String title) throws LuaException {
        PlantComputerData data = plant(label);
        return guarded(() -> PlantApi.title(data, page, title));
    }

    /** Defines or redefines an element from a table, e.g. {@code {type="field", col=2, row=3, text="POWER", unit="%"}}. */
    @LuaFunction(mainThread = true)
    public boolean plantSet(String label, int page, String id, Map<?, ?> spec) throws LuaException {
        PlantComputerData data = plant(label);
        return guarded(() -> PlantApi.set(data, page, id, spec));
    }

    /** Updates a field or bar number (nil: no data), a tile state ("ok", "warn", "alarm", "off") or a text. */
    @LuaFunction(mainThread = true)
    public boolean plantValue(String label, int page, String id, Object value) throws LuaException {
        PlantComputerData data = plant(label);
        return guarded(() -> PlantApi.value(data, page, id, value));
    }

    /** Adds a point to a trend. */
    @LuaFunction(mainThread = true)
    public boolean plantPush(String label, int page, String id, double value) throws LuaException {
        PlantComputerData data = plant(label);
        return guarded(() -> PlantApi.push(data, page, id, value));
    }

    @LuaFunction(mainThread = true)
    public boolean plantRemove(String label, int page, String id) throws LuaException {
        PlantComputerData data = plant(label);
        return guarded(() -> PlantApi.remove(data, page, id));
    }

    /** Sends what changed with the next tick instead of the usual five a second. Returns whether it was asked for. */
    @LuaFunction(mainThread = true)
    public boolean plantFlush(String label) throws LuaException {
        return plant(label).flush();
    }
}
