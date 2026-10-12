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
    // -- gauges, displays and mimic (spec 2.1, 2.2) --
    public static final ComponentType DIAL_GAUGE = register(new DialGaugeType());
    public static final ComponentType EDGEWISE_GAUGE_VERTICAL = register(new EdgewiseGaugeType(true));
    public static final ComponentType EDGEWISE_GAUGE_HORIZONTAL = register(new EdgewiseGaugeType(false));
    public static final ComponentType STRIP_CHART = register(new StripChartType());
    public static final ComponentType SEVEN_SEGMENT_DISPLAY = register(new SegmentDisplayType(false));
    public static final ComponentType SIXTEEN_SEGMENT_DISPLAY = register(new SegmentDisplayType(true));
    public static final ComponentType CHARACTER_LCD = register(new LcdType());
    public static final ComponentType GRAPHIC_DISPLAY = register(new GraphicDisplayType());
    public static final ComponentType MIMIC_LINE = register(new MimicLineType());
    public static final ComponentType MIMIC_SYMBOL = register(new MimicSymbolType());
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

    /** The type with this id, or null when unknown (for example an add-on that was removed). */
    public static ComponentType get(ResourceLocation id) {
        return TYPES.get(id);
    }

    public static Collection<ComponentType> all() {
        return Collections.unmodifiableCollection(TYPES.values());
    }
}
