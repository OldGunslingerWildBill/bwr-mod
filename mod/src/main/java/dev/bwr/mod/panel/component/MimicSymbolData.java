package dev.bwr.mod.panel.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * Mimic symbol: valve, pump, tank, turbine or breaker, coloured by input 0
 * "state": non-zero is the "on" colour (US convention: red = open or running),
 * zero the "off" colour (green = closed or stopped), and no signal is gray.
 */
public final class MimicSymbolData extends ComponentData {
    public static final String VALVE = "valve";
    public static final String PUMP = "pump";
    public static final String TANK = "tank";
    public static final String TURBINE = "turbine";
    public static final String BREAKER = "breaker";
    public static final List<String> SYMBOLS = List.of(VALVE, PUMP, TANK, TURBINE, BREAKER);
    public static final int DEFAULT_ON = 0xD02020;
    public static final int DEFAULT_OFF = 0x28A028;
    public static final int NO_SIGNAL = 0x6E7B72;

    public String symbol = VALVE;
    public int onColor = DEFAULT_ON;
    public int offColor = DEFAULT_OFF;

    /** The fill colour for an input value. */
    public int colorFor(double input) {
        return Double.isNaN(input) ? NO_SIGNAL : input != 0.0 ? onColor : offColor;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putString("Symbol", symbol);
        tag.putInt("OnColor", onColor);
        tag.putInt("OffColor", offColor);
    }

    @Override
    public void load(CompoundTag tag) {
        String saved = tag.getString("Symbol");
        symbol = SYMBOLS.contains(saved) ? saved : VALVE;
        onColor = tag.contains("OnColor", Tag.TAG_ANY_NUMERIC) ? tag.getInt("OnColor") & 0xFFFFFF : DEFAULT_ON;
        offColor = tag.contains("OffColor", Tag.TAG_ANY_NUMERIC) ? tag.getInt("OffColor") & 0xFFFFFF : DEFAULT_OFF;
    }
}
