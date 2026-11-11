package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.AnnunciatorLogic;
import dev.bwr.mod.panel.component.AnnunciatorType;
import dev.bwr.mod.panel.component.InputSource;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.PanelComponent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Annunciator windows by their light address (the optional {@link AnnunciatorData#address} of a window, 0 to 10000):
 * what the {@code bwr_panel} peripheral's {@code setAlarm}, {@code setValue}, {@code getAlarm} and
 * {@code annunciators} do. It is here, and uses no CC:Tweaked type, so that it loads and can be tested without that mod;
 * a refusal is an {@link IllegalArgumentException} whose message is the Lua error.
 *
 * <p>A group is the panel group of the block asked; only its loaded blocks are seen. Several windows may share an
 * address: they are then driven together, and read as the most urgent of them.
 */
public final class AnnunciatorAddresses {
    public static final String OFF = "off";
    public static final String ALARM = "alarm";
    public static final String ACKNOWLEDGED = "acknowledged";

    /** One window with the block that owns it. */
    public record Window(PanelBlockEntity owner, PanelComponent component, AnnunciatorData data) {
    }

    private AnnunciatorAddresses() {
    }

    /** Every annunciator window of the group of {@code block}, in the group's canonical order. */
    public static List<Window> windows(PanelBlockEntity block) {
        List<Window> windows = new ArrayList<>();
        if (block == null || block.getLevel() == null) {
            return windows;
        }
        PanelGroup group = block.group();
        if (group == null) {
            for (PanelComponent component : block.owned()) {
                add(windows, block, component);
            }
            windows.sort((a, b) -> Integer.compare(a.component.id(), b.component.id()));
            return windows;
        }
        for (PanelGroup.Entry entry : group.components(block.getLevel())) {
            add(windows, entry.owner(), entry.component());
        }
        return windows;
    }

    private static void add(List<Window> windows, PanelBlockEntity owner, PanelComponent component) {
        if (component.type() instanceof AnnunciatorType && component.data() instanceof AnnunciatorData data) {
            windows.add(new Window(owner, component, data));
        }
    }

    /** The windows at {@code address}; an address out of range or that no window has is refused. */
    public static List<Window> at(PanelBlockEntity block, int address) {
        if (address < AnnunciatorData.MIN_ADDRESS || address > AnnunciatorData.MAX_ADDRESS) {
            throw new IllegalArgumentException("address must be " + AnnunciatorData.MIN_ADDRESS + " to " + AnnunciatorData.MAX_ADDRESS);
        }
        List<Window> found = new ArrayList<>();
        for (Window window : windows(block)) {
            if (window.data.address == address) {
                found.add(window);
            }
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException("No annunciator light at address " + AnnunciatorData.formatAddress(address));
        }
        return found;
    }

    /**
     * Drives every window at {@code address} with {@code value}, as {@code set(label, value)} does for one. A window
     * whose input has no source yet is given the CC source first; one wired to anything else is refused by name, and
     * then no window is changed.
     *
     * @return how many windows were driven
     */
    public static int drive(PanelBlockEntity block, int address, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be a finite number");
        }
        List<Window> found = at(block, address);
        for (Window window : found) {
            InputSource source = window.component.source(0);
            if (!(source instanceof CcSource) && source != InputSources.NONE
                    && !(source != null && InputSources.KIND_NONE.equals(source.kind()))) {
                throw new IllegalArgumentException("Light " + AnnunciatorData.formatAddress(address) + " (" + window.component.label()
                        + ") is wired to " + describe(source) + ", not to CC");
            }
        }
        Set<PanelBlockEntity> rewired = new LinkedHashSet<>();
        for (Window window : found) {
            if (!(window.component.source(0) instanceof CcSource)) {
                window.component.setSource(0, new CcSource());
                window.owner.resample(window.component, true);
                rewired.add(window.owner);
            }
            PanelWiring.setCc(window.owner, window.component, 0, value);
        }
        for (PanelBlockEntity owner : rewired) {
            owner.updateRedstoneOutput();
            owner.syncFull();
        }
        return found.size();
    }

    private static String describe(InputSource source) {
        if (source == null) {
            return "an unknown source";
        }
        if (source instanceof PlantSource plant) {
            return "the plant signal " + plant.key();
        }
        return "a " + source.kind() + " source";
    }

    /**
     * What the light at {@code address} shows: {@link #OFF}, {@link #ALARM} (a new alarm, flashing) or
     * {@link #ACKNOWLEDGED} (steady). Of several windows with the address, the most urgent.
     */
    public static String state(PanelBlockEntity block, int address) {
        AnnunciatorLogic.State worst = AnnunciatorLogic.State.NORMAL;
        for (Window window : at(block, address)) {
            AnnunciatorLogic.State state = window.data.logic.state();
            if (state == AnnunciatorLogic.State.ALARM || worst == AnnunciatorLogic.State.NORMAL) {
                worst = state;
            }
        }
        return word(worst);
    }

    /** The word for a state: the three {@link AnnunciatorLogic} has. */
    public static String word(AnnunciatorLogic.State state) {
        return switch (state) {
            case NORMAL -> OFF;
            case ALARM -> ALARM;
            case ACKED -> ACKNOWLEDGED;
        };
    }

    /**
     * Every window of the group: {@code address} (absent when the window has none), {@code label}, {@code legend} (its
     * lines that are not empty), {@code color} (RGB), {@code group} and {@code state}.
     */
    public static List<Map<String, Object>> describe(PanelBlockEntity block) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Window window : windows(block)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (window.data.address >= AnnunciatorData.MIN_ADDRESS) {
                entry.put("address", window.data.address);
            }
            entry.put("label", window.component.label());
            entry.put("legend", List.of(window.data.legend()));
            entry.put("color", window.data.color);
            entry.put("group", window.data.group);
            entry.put("state", word(window.data.logic.state()));
            result.add(entry);
        }
        return result;
    }
}
