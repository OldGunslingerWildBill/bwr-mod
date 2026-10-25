package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.core.isotope.LargeRtgStore;
import dev.bwr.core.isotope.RadioisotopePower;
import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.isotope.LargeRtgBlockEntity;
import dev.bwr.mod.isotope.RtgBlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The computer face of the small RTG ({@code bwr_rtg}) and the Large RTG ({@code bwr_large_rtg}): {@code getStatus()} and
 * {@code setHeatOnly(boolean)}, the heat-only switch (it stops the FE and nothing else; the thermal power is unchanged).
 */
public final class RtgPeripheral implements IPeripheral {

    private final RtgBlockEntity small;
    private final LargeRtgBlockEntity large;

    public RtgPeripheral(RtgBlockEntity be) {
        this.small = be;
        this.large = null;
    }

    public RtgPeripheral(LargeRtgBlockEntity be) {
        this.small = null;
        this.large = be;
    }

    @Override
    public String getType() {
        return small != null ? "bwr_rtg" : "bwr_large_rtg";
    }

    @Override
    public boolean equals(IPeripheral other) {
        return other instanceof RtgPeripheral p && p.small == small && p.large == large;
    }

    @LuaFunction(mainThread = true)
    public Map<String, Object> getStatus() {
        var m = new LinkedHashMap<String, Object>();
        if (small != null) {
            var r = small.readout();
            m.put("capsules", r.capsules());
            m.put("thermalW", r.thermalWatts());
            m.put("electricW", r.electricWatts());
            m.put("fePerTick", r.fePerTick());
            m.put("halfLifeYears", r.shortestHalfLifeYears());
            m.put("doseAt1mMsvPerH", r.doseAtOneMetreMsvPerH());
            m.put("bufferFE", small.bufferFe());
            m.put("efficiency", RadioisotopePower.THERMOCOUPLE_EFFICIENCY);
            m.put("outputMultiplier", GameplayConfig.rtgOutputMultiplier());
            m.put("heatOnly", small.heatOnly());
            return m;
        }
        LargeRtgStore.Output r = large.output();
        m.put("massKg", r.massGrams() / 1000.0);
        m.put("capacityKg", LargeRtgStore.CAPACITY_GRAMS / 1000.0);
        m.put("items", large.store().itemCount());
        m.put("thermalW", r.thermalWatts());
        m.put("electricW", r.electricWatts());
        m.put("fePerTick", r.fePerTick());
        m.put("halfLifeYears", r.shortestHalfLifeYears());
        m.put("doseAt1mMsvPerH", r.doseAtOneMetreMsvPerH());
        m.put("bareDoseAt1mMsvPerH", r.bareDoseAtOneMetreMsvPerH());
        m.put("bufferFE", large.bufferFe());
        m.put("efficiency", RadioisotopePower.THERMOCOUPLE_EFFICIENCY);
        m.put("outputMultiplier", GameplayConfig.rtgOutputMultiplier());
        m.put("heatOnly", large.heatOnly());
        var kinds = new LinkedHashMap<String, Object>();
        for (LargeRtgStore.Bucket b : large.store().buckets()) {
            kinds.merge(b.fuel().label().isEmpty() ? b.key() : b.fuel().label(), b.grams() / 1000.0, (a, c) -> (Double) a + (Double) c);
        }
        m.put("kgByIsotope", kinds);
        return m;
    }

    /** True turns the electricity off (the heat is unchanged); false turns it back on. Returns the new state. */
    @LuaFunction(mainThread = true)
    public boolean setHeatOnly(boolean heatOnly) {
        if (small != null) {
            small.setHeatOnly(heatOnly);
            return small.heatOnly();
        }
        large.setHeatOnly(heatOnly);
        return large.heatOnly();
    }
}
