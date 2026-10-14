package dev.bwr.mod.signal.adapters;

import dev.bwr.mod.grid.*;
import dev.bwr.mod.signal.*;
import java.util.*;

final class GridSignals {
    private GridSignals(){}
    private static double numeric(Object n){return n instanceof Number v?v.doubleValue():n instanceof Boolean b?b?1:0:Double.NaN;}
    static final Schema<GridTieBlockEntity> TIE=tie();
    static final Schema<FeMeterBlockEntity> METER=meter();
    private static Schema<GridTieBlockEntity> tie(){
        var s=new Schema<GridTieBlockEntity>();
        s.number("demand_mode","demand mode","",0,1e12,b->numeric(GridTelemetry.tie(b).get("demandMode")));
        s.number("fixed_mw","fixed mw","MW",0,1e12,b->numeric(GridTelemetry.tie(b).get("fixedMW")));
        s.number("remote_mw","remote mw","MW",0,1e12,b->numeric(GridTelemetry.tie(b).get("remoteMW")));
        s.number("demand_mw","demand mw","MW",0,1e12,b->numeric(GridTelemetry.tie(b).get("demandMW")));
        s.number("demand_mw_1s","demand mw 1s","MW",0,1e12,b->numeric(GridTelemetry.tie(b).get("demandMW1s")));
        s.number("curve_min_mw","curve min mw","MW",0,1e12,b->numeric(GridTelemetry.tie(b).get("curveMinMW")));
        s.number("curve_max_mw","curve max mw","MW",0,1e12,b->numeric(GridTelemetry.tie(b).get("curveMaxMW")));
        s.number("curve_period_ticks","curve period ticks","ticks",0,1e12,b->numeric(GridTelemetry.tie(b).get("curvePeriodTicks")));
        s.number("curve_phase_ticks","curve phase ticks","ticks",0,1e12,b->numeric(GridTelemetry.tie(b).get("curvePhaseTicks")));
        s.number("maximum_mw","maximum mw","MW",0,1e12,b->numeric(GridTelemetry.tie(b).get("maximumMW")));
        s.number("delivered_mw_now","delivered mw now","MW",0,1e12,b->numeric(GridTelemetry.tie(b).get("deliveredMWNow")));
        s.number("delivered_mw_1s","delivered mw 1s","MW",0,1e12,b->numeric(GridTelemetry.tie(b).get("deliveredMW1s")));
        s.number("delivered_mw_1min","delivered mw 1min","MW",0,1e12,b->numeric(GridTelemetry.tie(b).get("deliveredMW1min")));
        s.number("delivered_mwh","delivered mwh","MWh",0,1e12,b->numeric(GridTelemetry.tie(b).get("deliveredMWh")));
        s.number("grid_shortfall_mw","grid shortfall mw","MW",0,1e12,b->numeric(GridTelemetry.tie(b).get("gridShortfallMW")));
        s.number("observed_base_mw","observed base mw","MW",0,1e12,b->numeric(GridTelemetry.tie(b).get("observedBaseMW")));
        s.number("requested_total_mw","requested total mw","MW",0,1e12,b->numeric(GridTelemetry.tie(b).get("requestedTotalMW")));
        s.number("mw_error","mw error","MW",-1e9,1e12,b->numeric(GridTelemetry.tie(b).get("mwError")));
        s.boolOrNone("aggregate_valid","aggregate valid",b->numeric(GridTelemetry.tie(b).get("aggregateValid")));
        s.number("selected_meter_count","selected meter count","",0,1e12,b->numeric(GridTelemetry.tie(b).get("selectedMeterCount")));
        s.number("valid_meter_count","valid meter count","",0,1e12,b->numeric(GridTelemetry.tie(b).get("validMeterCount")));
        s.boolOrNone("breaker_closed","breaker closed",b->numeric(GridTelemetry.tie(b).get("breakerClosed")));
        s.boolOrNone("export_enabled","export enabled",b->numeric(GridTelemetry.tie(b).get("exportEnabled")));
        s.boolOrNone("binding_valid","binding valid",b->numeric(GridTelemetry.tie(b).get("bindingValid")));
        s.text("bound_generator","bound generator",b->(String)GridTelemetry.tie(b).get("boundGenerator"));
        s.number("rpm","rpm","rpm",0,1e12,b->numeric(GridTelemetry.tie(b).get("rpm")));
        s.number("shaft_frequency_hz","shaft frequency hz","Hz",0,1e12,b->numeric(GridTelemetry.tie(b).get("shaftFrequencyHz")));
        s.number("slip_hz","slip hz","Hz",-1e9,1e12,b->numeric(GridTelemetry.tie(b).get("slipHz")));
        s.number("phase_degrees","phase degrees","degrees",-1e9,1e12,b->numeric(GridTelemetry.tie(b).get("phaseDegrees")));
        s.boolOrNone("sync_valid","sync valid",b->numeric(GridTelemetry.tie(b).get("syncValid")));
        s.boolOrNone("within_sync_window","within sync window",b->numeric(GridTelemetry.tie(b).get("withinSyncWindow")));
        s.number("last_close_angle_degrees","last close angle degrees","degrees",-1e9,1e12,b->numeric(GridTelemetry.tie(b).get("lastCloseAngleDegrees")));
        s.number("last_close_slip_hz","last close slip hz","Hz",-1e9,1e12,b->numeric(GridTelemetry.tie(b).get("lastCloseSlipHz")));
        s.boolOrNone("last_close_fault","last close fault",b->numeric(GridTelemetry.tie(b).get("lastCloseFault")));
        s.text("status","status",b->(String)GridTelemetry.tie(b).get("status"));
        s.number("sample_ticks","sample ticks","ticks",0,1e12,b->numeric(GridTelemetry.tie(b).get("sampleTicks")));
        s.boolOrNone("window_ready_1s","window ready 1s",b->numeric(GridTelemetry.tie(b).get("windowReady1s")));
        s.boolOrNone("window_ready_1min","window ready 1min",b->numeric(GridTelemetry.tie(b).get("windowReady1min")));
        s.boolOrNone("counter_saturated","counter saturated",b->numeric(GridTelemetry.tie(b).get("counterSaturated")));
        s.command("demand_mode","demand mode",Kind.NUMBER,0,2,(b,v)->DeviceCommands.grid(b,"demand_mode",v));
        s.command("fixed_mw","fixed mw",Kind.NUMBER,0,48000,(b,v)->DeviceCommands.grid(b,"fixed_mw",v));
        s.command("remote_mw","remote mw",Kind.NUMBER,0,48000,(b,v)->DeviceCommands.grid(b,"remote_mw",v));
        s.command("curve_min_mw","curve min mw",Kind.NUMBER,0,48000,(b,v)->DeviceCommands.grid(b,"curve_min_mw",v));
        s.command("curve_max_mw","curve max mw",Kind.NUMBER,0,48000,(b,v)->DeviceCommands.grid(b,"curve_max_mw",v));
        s.command("curve_period_ticks","curve period ticks",Kind.NUMBER,0,1728000,(b,v)->DeviceCommands.grid(b,"curve_period_ticks",v));
        s.pulse("close_breaker","close breaker",(b,v)->DeviceCommands.grid(b,"close_breaker",v));
        s.pulse("trip_breaker","trip breaker",(b,v)->DeviceCommands.grid(b,"trip_breaker",v));
        s.pulse("reset_curve","reset curve",(b,v)->DeviceCommands.grid(b,"reset_curve",v));
        s.dynamicSignals(b->{var all=new ArrayList<SignalSpec>();for(var r:b.meters()){
            String p="meter."+r.id()+".";all.add(new SignalSpec(p+"label","Meter label","",0,0,Kind.TEXT));all.add(new SignalSpec(p+"valid","Meter valid","",0,1,Kind.BOOLEAN));
            all.add(new SignalSpec(p+"mw_1s","Meter power","MW",0,1e12,Kind.NUMBER));all.add(new SignalSpec(p+"mwh","Meter total","MWh",0,1e12,Kind.NUMBER));}return all;
        },(b,key)->{double value=numeric(selected(b,key));return Double.isNaN(value)?OptionalDouble.empty():OptionalDouble.of(value);});
        s.dynamicText((b,key)->selected(b,key) instanceof String text?Optional.of(text):Optional.empty());
        return s;
    }
    private static Schema<FeMeterBlockEntity> meter(){
        var s=new Schema<FeMeterBlockEntity>();
        s.number("mw_now","mw now","MW",0,1e12,b->numeric(GridTelemetry.meter(b).get("mwNow")));
        s.number("mw_1s","mw 1s","MW",0,1e12,b->numeric(GridTelemetry.meter(b).get("mw1s")));
        s.number("mw_1min","mw 1min","MW",0,1e12,b->numeric(GridTelemetry.meter(b).get("mw1min")));
        s.number("fe_per_tick_now","fe per tick now","FE/t",0,1e12,b->numeric(GridTelemetry.meter(b).get("fePerTickNow")));
        s.number("fe_per_tick_1s","fe per tick 1s","FE/t",0,1e12,b->numeric(GridTelemetry.meter(b).get("fePerTick1s")));
        s.number("fe_per_tick_1min","fe per tick 1min","FE/t",0,1e12,b->numeric(GridTelemetry.meter(b).get("fePerTick1min")));
        s.number("mwh","mwh","MWh",0,1e12,b->numeric(GridTelemetry.meter(b).get("mwh")));
        s.text("label","label",b->(String)GridTelemetry.meter(b).get("label"));
        s.text("display_units","display units",b->(String)GridTelemetry.meter(b).get("displayUnits"));
        s.text("direction","direction",b->(String)GridTelemetry.meter(b).get("direction"));
        s.number("sample_ticks","sample ticks","ticks",0,1e12,b->numeric(GridTelemetry.meter(b).get("sampleTicks")));
        s.boolOrNone("window_ready_1s","window ready 1s",b->numeric(GridTelemetry.meter(b).get("windowReady1s")));
        s.boolOrNone("window_ready_1min","window ready 1min",b->numeric(GridTelemetry.meter(b).get("windowReady1min")));
        s.boolOrNone("counter_saturated","counter saturated",b->numeric(GridTelemetry.meter(b).get("counterSaturated")));
        s.command("direction","direction",Kind.NUMBER,0,5,(b,v)->DeviceCommands.meter(b,"direction",v));
        s.command("display_units","display units",Kind.NUMBER,0,1,(b,v)->DeviceCommands.meter(b,"display_units",v));
        return s;
    }
    private static Object selected(GridTieBlockEntity b,String key){
        for(var m:GridTelemetry.meters(b)){String p="meter."+m.get("id")+".";if(key.startsWith(p))return m.get(key.substring(p.length()).equals("mw_1s")?"mw1s":key.substring(p.length()));}return null;
    }
}
