package dev.bwr.mod.panel.component;

import dev.bwr.mod.signal.PowerUnits;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * The gauge setting {@code Power units} (Raw, W, kW, MW; kJ and MJ for stored energy) and, for an FE rate whose time
 * base is not said, {@code Per tick / Per second}. Picking a unit is more than a setting: the server sets the binding
 * scale and the gauge Units text to match (see {@code PowerUnitsOps}); this object only remembers the choice. Both
 * values are saved only when they are not the default, so an old save loads and re-saves unchanged.
 */
public final class PowerUnitsState {
    public static final String UNITS_KEY = "PowerUnits";
    public static final String RATE_KEY = "PowerRate";

    public String units = PowerUnits.RAW;
    public String rate = PowerUnits.PER_TICK;

    /** The two config fields a gauge-like type appends to its schema. */
    public static List<ConfigField> fields() {
        return List.of(ConfigField.choice(UNITS_KEY, "component.bwr.field.power_units", PowerUnits.OPTIONS),
                ConfigField.choice(RATE_KEY, "component.bwr.field.power_rate", PowerUnits.RATES));
    }

    public void save(CompoundTag tag) {
        if (!PowerUnits.RAW.equals(units)) {
            tag.putString(UNITS_KEY, units);
        }
        if (!PowerUnits.PER_TICK.equals(rate)) {
            tag.putString(RATE_KEY, rate);
        }
    }

    public void load(CompoundTag tag) {
        String loaded = tag.contains(UNITS_KEY, Tag.TAG_STRING) ? tag.getString(UNITS_KEY) : PowerUnits.RAW;
        units = PowerUnits.OPTIONS.contains(loaded) ? loaded : PowerUnits.RAW;
        String time = tag.contains(RATE_KEY, Tag.TAG_STRING) ? tag.getString(RATE_KEY) : PowerUnits.PER_TICK;
        rate = PowerUnits.RATES.contains(time) ? time : PowerUnits.PER_TICK;
    }

    public boolean perSecond() {
        return PowerUnits.PER_SECOND.equals(rate);
    }
}
