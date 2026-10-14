package dev.bwr.mod.grid;

import dev.bwr.core.grid.*;
import java.util.*;

/** Shared read-only values for CC and panel adapters; unavailable numbers are omitted. */
public final class GridTelemetry {
    private GridTelemetry(){}
    public static Map<String,Object> tie(GridTieBlockEntity t){
        var m=new LinkedHashMap<String,Object>();var d=t.settings();var g=t.generator();
        m.put("demandMode",d.mode());m.put("fixedMW",d.fixedMW());m.put("remoteMW",d.remoteMW());m.put("demandMW",t.demandMW());m.put("demandMW1s",t.demandMW1s());
        m.put("curveMinMW",d.minimumMW());m.put("curveMaxMW",d.maximumMW());m.put("curvePeriodTicks",d.periodTicks());m.put("curvePhaseTicks",t.curvePhase());m.put("maximumMW",GridConfig.maximumMW());
        energy(m,t.ledger(),true);m.put("gridShortfallMW",Math.max(0,t.demandMW1s()-EnergyUnits.mw(t.ledger().averageFE(20))));
        m.put("breakerClosed",t.closed());m.put("bindingValid",g!=null);m.put("exportEnabled",t.exportEnabled());m.put("syncValid",t.syncValid());m.put("withinSyncWindow",t.withinWindow());
        m.put("boundGenerator",t.binding()==null?"":t.binding().dimension()+" "+t.binding().pos().toShortString()+" "+t.binding().id());
        if(g!=null){m.put("rpm",g.rpm);m.put("shaftFrequencyHz",g.rpm/30);}
        if(t.syncValid()){m.put("phaseDegrees",t.phase());m.put("slipHz",t.slip());}
        if(t.hasClose()){m.put("lastCloseAngleDegrees",t.lastAngle());m.put("lastCloseSlipHz",t.lastSlip());m.put("lastCloseFault",t.lastCloseFault());}
        m.put("aggregateValid",t.aggregateValid());m.put("selectedMeterCount",t.meters().size());m.put("validMeterCount",t.validMeterCount());
        if(t.aggregateValid()){m.put("observedBaseMW",t.observedBaseMW());m.put("requestedTotalMW",t.requestedTotalMW());m.put("mwError",t.mwError());}
        m.put("status",g==null?"source_unavailable":t.closed()?"closed":"open");return m;
    }
    public static Map<String,Object> meter(FeMeterBlockEntity t){
        var m=new LinkedHashMap<String,Object>();energy(m,t.ledger(),false);
        m.put("label",t.label());m.put("displayUnits",t.units());m.put("direction",t.direction().getName());
        m.put("fePerTickNow",t.ledger().lastTickFE());m.put("fePerTick1s",t.ledger().averageFE(20));m.put("fePerTick1min",t.ledger().averageFE(1200));return m;
    }
    public static List<Map<String,Object>> meters(GridTieBlockEntity t){
        var result=new ArrayList<Map<String,Object>>();
        for(var r:t.meters()){
            var m=new LinkedHashMap<String,Object>();var meter=t.meter(r);boolean valid=t.sampleValid(meter);
            m.put("id",r.id().toString());m.put("dimension",r.dimension());m.put("x",r.pos().getX());m.put("y",r.pos().getY());m.put("z",r.pos().getZ());
            m.put("label",meter==null?"":meter.label());m.put("valid",valid);
            if(valid){m.put("mw1s",EnergyUnits.mw(meter.ledger().averageFE(20)));m.put("mwh",EnergyUnits.mwh(meter.ledger().totalFE()));}result.add(m);
        }return result;
    }
    private static void energy(Map<String,Object> m,EnergyLedger e,boolean delivered){
        m.put(delivered?"deliveredMWNow":"mwNow",EnergyUnits.mw(e.lastTickFE()));m.put(delivered?"deliveredMW1s":"mw1s",EnergyUnits.mw(e.averageFE(20)));m.put(delivered?"deliveredMW1min":"mw1min",EnergyUnits.mw(e.averageFE(1200)));m.put(delivered?"deliveredMWh":"mwh",EnergyUnits.mwh(e.totalFE()));
        m.put("sampleTicks",e.sampleTicks());m.put("windowReady1s",e.sampleTicks()>=20);m.put("windowReady1min",e.sampleTicks()>=1200);m.put("counterSaturated",e.saturated());
    }
}
