package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.power.TurbineAuxBlock;
import dev.bwr.mod.power.TurbineAuxBlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/** CC face of a turbine auxiliary skid: the readings, and the pump switches that are the player's. */
public final class TurbineAuxPeripheral implements IPeripheral {
    private final TurbineAuxBlockEntity skid;

    public TurbineAuxPeripheral(TurbineAuxBlockEntity skid) { this.skid = skid; }

    @Override public String getType() {
        return switch (skid.kind()) {
            case LUBE_OIL -> "bwr_main_oil_tank";
            case SHAFT_PUMP -> "bwr_shaft_oil_pump";
            case GEN_COOLING -> "bwr_generator_cooling_skid";
            case OIL_FILTER -> "bwr_oil_filter";
        };
    }
    @Override public boolean equals(IPeripheral other) { return other instanceof TurbineAuxPeripheral p && p.skid == skid; }
    @Override public Object getTarget() { return skid; }

    private void need(TurbineAuxBlock.Kind kind, String what) throws LuaException {
        if (skid.kind() != kind) throw new LuaException(what);
    }

    @LuaFunction(mainThread = true) public void setAcPump(boolean on) throws LuaException {
        need(TurbineAuxBlock.Kind.LUBE_OIL, "Only the Main Oil Tank has an AC pump");
        skid.setAcPump(on);
    }
    @LuaFunction(mainThread = true) public void setDcPump(boolean on) throws LuaException {
        need(TurbineAuxBlock.Kind.LUBE_OIL, "Only the Main Oil Tank has a DC pump");
        skid.setDcPump(on);
    }
    @LuaFunction(mainThread = true) public void setStatorPumps(boolean on) throws LuaException {
        need(TurbineAuxBlock.Kind.GEN_COOLING, "Only the Generator Cooling Skid has stator pumps");
        skid.setStatorPumps(on);
    }

    /** What the oil filter housing reads, the same names on the housing and on the tank it is against. */
    private static void filterFields(Map<String, Object> m, TurbineAuxBlockEntity filter) {
        m.put("filterCartridge", filter.hasCartridge());
        m.put("filterLife", filter.filterLife());
        m.put("filterDpPsi", filter.filterDpPsi());
        m.put("filterBypass", filter.filterBypass());
    }

    @LuaFunction(mainThread = true) public Map<String, Object> getStatus() {
        var m = new LinkedHashMap<String, Object>();
        var module = skid.module();
        m.put("attached", module != null);
        switch (skid.kind()) {
            case LUBE_OIL -> {
                m.put("acPump", skid.acPumpOn()); m.put("dcPump", skid.dcPumpOn());
                m.put("acRunning", skid.acRunning()); m.put("dcRunning", skid.dcRunning());
                m.put("oilLevel", skid.oilFraction()); m.put("oilLitres", skid.oilLitres()); m.put("oilTempC", skid.oilC());
                m.put("pressurePsig", skid.psig()); m.put("oilFilm", skid.film()); m.put("coolerKW", skid.coolerKw());
                m.put("coldWaterKg", skid.coldKg()); m.put("hotWaterKg", skid.hotKg()); m.put("acFE", skid.acFe()); m.put("dcFE", skid.dcFe());
                // The oil filter housing against this tank (TURBINE-AUX.md); the other fields are present only with one.
                var filter = skid.oilFilter();
                m.put("filterHousing", filter != null);
                if (filter != null) filterFields(m, filter);
            }
            case OIL_FILTER -> {
                var tank = skid.tank();
                m.put("attached", tank != null);
                filterFields(m, skid);
                m.put("filterChangeDue", skid.filterChangeDue());
                double hours = skid.filterHoursLeft();
                if (Double.isFinite(hours)) m.put("filterHoursLeft", hours); // absent when nothing flows through it
            }
            case SHAFT_PUMP -> m.put("delivery", module == null ? 0.0 : dev.bwr.core.turbine.TurbineAux.shaftPumpFlow(module.rpm));
            case GEN_COOLING -> {
                m.put("statorPumps", skid.statorPumpsOn()); m.put("statorRunning", skid.statorRunning()); m.put("statorFlow", skid.statorFlow());
                m.put("h2PressurePsig", skid.h2Psig()); m.put("h2Kg", skid.h2Kg()); m.put("coolantTempC", skid.coolantC());
                m.put("windingTempC", module == null ? 0.0 : module.windingC); m.put("coolerKW", skid.coolerKw());
                m.put("coldWaterKg", skid.coldKg()); m.put("hotWaterKg", skid.hotKg()); m.put("supplyFE", skid.acFe());
            }
        }
        return m;
    }
}
