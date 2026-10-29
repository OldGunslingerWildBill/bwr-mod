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
            case CONDITIONER -> "bwr_lube_oil_conditioner";
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
    /** The conditioner's switch (the Lube Oil Conditioner has no other control; the rest of its face is readings). */
    @LuaFunction(mainThread = true) public void setRunning(boolean on) throws LuaException {
        need(TurbineAuxBlock.Kind.CONDITIONER, "Only the Lube Oil Conditioner can be started or stopped");
        skid.setConditioner(on);
    }
    @LuaFunction(mainThread = true) public void start() throws LuaException { setRunning(true); }
    @LuaFunction(mainThread = true) public void stop() throws LuaException { setRunning(false); }

    /** The duplex filter's transfer valve: "A" or "B" puts that side in service (the other can then be opened for a change). */
    @LuaFunction(mainThread = true) public void setSide(String side) throws LuaException {
        need(TurbineAuxBlock.Kind.OIL_FILTER, "Only the Oil Filter Housing has a transfer valve");
        if (side == null || !(side.equalsIgnoreCase("A") || side.equalsIgnoreCase("B"))) throw new LuaException("The side is \"A\" or \"B\"");
        skid.selectSide(side.equalsIgnoreCase("B"));
    }
    @LuaFunction(mainThread = true) public void transfer() throws LuaException {
        need(TurbineAuxBlock.Kind.OIL_FILTER, "Only the Oil Filter Housing has a transfer valve");
        skid.selectSide(!skid.sideBInService());
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
        m.put("filterGrade", dev.bwr.core.turbine.TurbineAux.GRADE_NAMES[filter.grade()]);
        if (Double.isFinite(filter.flowCapacity())) m.put("filterFlowCapacity", filter.flowCapacity());
        // The duplex filter (TURBINE-AUX.md): the side in service, and each side's own readings (the flat ones above are the side in service).
        m.put("filterSelectedSide", filter.sideBInService() ? "B" : "A");
        for (int side = 0; side < 2; side++) {
            var s = new LinkedHashMap<String, Object>();
            s.put("cartridge", filter.hasCartridge(side)); s.put("life", filter.filterLife(side)); s.put("dpPsi", filter.filterDpPsi(side));
            s.put("grade", dev.bwr.core.turbine.TurbineAux.GRADE_NAMES[filter.grade(side)]); if (Double.isFinite(filter.flowCapacity(side))) s.put("flowCapacity", filter.flowCapacity(side));
            s.put("bypass", filter.filterBypass(side)); s.put("changeDue", filter.filterChangeDue(side)); s.put("inService", (side == 1) == filter.sideBInService());
            m.put(side == 0 ? "sideA" : "sideB", s);
        }
    }

    /** The bearing oil flow of the train a tank serves and its three flags (TURBINE-AUX.md, Bearing oil flow). */
    private static void flowFields(Map<String, Object> m, dev.bwr.mod.power.TurbineAuxTrain.Readout a) {
        m.put("oilFlow", a.oilFlow); m.put("oilFlowGood", a.oilFlowGood); m.put("oilFlowLowAlarm", a.oilFlowLowAlarm); m.put("oilFlowVeryLowAlarm", a.oilFlowVeryLowAlarm);
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
                m.put("dischargePiped", skid.dischargePiped());
                m.put("oilCleanliness", skid.cleanliness()); m.put("flowLimited", skid.flowLimited());
                if (Double.isFinite(skid.filterCapacity())) m.put("filterFlowLimit", skid.filterCapacity());
                if (module != null && module.aux().lubeGoverned) flowFields(m, module.aux());
                var conditioner = skid.conditioner();
                m.put("conditioner", conditioner != null);
                if (conditioner != null) { m.put("conditionerOn", conditioner.conditionerOn()); m.put("conditionerRunning", conditioner.conditionerRunning()); }
                m.put("filterHousing", filter != null);
                if (filter != null) filterFields(m, filter);
            }
            case OIL_FILTER -> {
                var tank = skid.tank();
                m.put("attached", tank != null);
                m.put("onSupplyLine", skid.onSupplyLine());
                filterFields(m, skid);
                if (tank != null) {
                    m.put("oilCleanliness", tank.cleanliness());
                    var served = tank.module();
                    if (served != null && served.aux().lubeGoverned) flowFields(m, served.aux());
                }
                m.put("filterChangeDue", skid.filterChangeDue());
                double hours = skid.filterHoursLeft();
                if (Double.isFinite(hours)) m.put("filterHoursLeft", hours); // absent when nothing flows through it
            }
            case CONDITIONER -> {
                var tank = skid.tank();
                m.put("attached", tank != null);
                m.put("on", skid.conditionerOn()); m.put("running", skid.conditionerRunning()); m.put("supplyFE", skid.acFe());
                m.put("degradationFactor", dev.bwr.core.turbine.TurbineAux.conditionerFactor(skid.conditionerRunning()));
            }
            case SHAFT_PUMP -> {
                m.put("delivery", module == null ? 0.0 : dev.bwr.core.turbine.TurbineAux.shaftPumpFlow(module.rpm));
                m.put("suctionPiped", skid.suctionPiped());
                m.put("dischargePiped", skid.dischargePiped());
            }
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
