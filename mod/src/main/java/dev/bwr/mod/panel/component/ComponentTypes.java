package dev.bwr.mod.panel.component;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The component type registry. Types register in the static initialiser of this
 * class (one line each), which runs the first time anything asks for a type, in
 * practice from {@code BwrMod}'s constructor via {@link #bootstrap()}. The
 * registry is append-only and is read on both sides, so ids must be stable:
 * they are saved in worlds.
 */
public final class ComponentTypes {
    private ComponentTypes() {
    }

    private static final Map<ResourceLocation, ComponentType> TYPES = new LinkedHashMap<>();

    // ---- the catalogue: add new types here -------------------------------------
    public static final ComponentType NAMEPLATE = register(new NameplateType());
    public static final ComponentType LAMP = register(new LampType());
    // inputs and indicators (spec 2.1, first two tables)
    public static final ComponentType PUSHBUTTON = register(new PushbuttonType());
    public static final ComponentType TOGGLE_SWITCH = register(new ToggleSwitchType());
    public static final ComponentType ROTARY_SELECTOR = register(new RotarySelectorType());
    public static final ComponentType CONTROL_SWITCH = register(new ControlSwitchType());
    public static final ComponentType KEY_SWITCH = register(new KeySwitchType());
    public static final ComponentType SETPOINT_KNOB = register(new SetpointKnobType());
    public static final ComponentType THUMBWHEEL = register(new ThumbwheelType());
    public static final ComponentType ANNUNCIATOR = register(new AnnunciatorType());
    public static final ComponentType BAR_GRAPH = register(new BarGraphType());
    // ----------------------------------------------------------------------------

    /** Forces class initialisation. */
    public static void bootstrap() {
    }

    private static synchronized <T extends ComponentType> T register(T type) {
        if (TYPES.putIfAbsent(type.id(), type) != null) {
            throw new IllegalStateException("duplicate component type " + type.id());
        }
        return type;
    }

    /** Types that exist only while a GameTest class is loaded: findable by id, but not in the catalogue. */
    private static final Map<ResourceLocation, ComponentType> TEST_ONLY = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Registers a type for tests only. It is found by {@link #get} (so saves, packets and {@code ComponentItem}
     * work) but is not part of {@link #all()}, so it needs no item, recipe or lang entry and never reaches a
     * player's world.
     */
    public static void registerTestOnly(ComponentType type) {
        TEST_ONLY.put(type.id(), type);
    }

    /** The type with this id, or null when unknown (for example an add-on that was removed). */
    public static ComponentType get(ResourceLocation id) {
        ComponentType type = TYPES.get(id);
        return type != null ? type : TEST_ONLY.get(id);
    }

    public static Collection<ComponentType> all() {
        return Collections.unmodifiableCollection(TYPES.values());
    }
}
