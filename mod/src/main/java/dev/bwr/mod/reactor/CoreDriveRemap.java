package dev.bwr.mod.reactor;

import dev.bwr.core.ReactorState;
import java.util.*;

/** Keeps surviving physical drives' state when RIP footprints change compact rod numbering. */
public final class CoreDriveRemap {
    public static int[] demands(int[] values,int[] before,int[] after) {
        if(values==null)return null;
        if(values.length!=before.length)throw new IllegalArgumentException("Saved drive demands do not match their mounting coordinates");
        int[] out=new int[after.length];var map=index(before);
        for(int i=0;i<after.length;i++){Integer from=map.get(after[i]);if(from!=null)out[i]=values[from];}
        return out;
    }
    private static Map<Integer,Integer> index(int[] cells) {
        var map=new HashMap<Integer,Integer>();
        for(int i=0;i<cells.length;i++)if(cells[i]<0||cells[i]>=441||map.put(cells[i],i)!=null)
            throw new IllegalArgumentException("Invalid saved drive coordinate");
        return map;
    }
    public static ReactorState state(ReactorState s,int[] before,int[] after) {
        if(s.rodNotches().length!=before.length)throw new IllegalArgumentException("Saved rods do not match their mounting coordinates");
        var map=index(before);int[] notches=demands(s.rodNotches(),before,after);
        double[] charge=new double[after.length],position=new double[after.length];
        var oldCharge=s.accumulatorCharge();var oldPosition=s.rodPositionsNotches();
        if(oldCharge.length!=before.length)throw new IllegalArgumentException("Saved accumulators do not match their mounting coordinates");
        for(int i=0;i<after.length;i++) {
            Integer old=map.get(after[i]);if(old==null)continue; // New drives start inserted and uncharged.
            charge[i]=oldCharge[old];position[i]=oldPosition.length==before.length?oldPosition[old]:notches[i];
        }
        return new ReactorState(s.neutronPower(),s.precursors(),s.sourceStrength(),s.decayHeatFraction(),s.decayGroups(),
            s.fuelTempC(),s.cladTempC(),s.peakCladTempC(),s.peakFuelTempC(),s.oxidationFraction(),s.hydrogenKg(),s.protectiveOxideArealKgPerM2(),
            s.coolantTempC(),s.pressurePsig(),s.voidFraction(),s.coreInletEnthalpyKJPerKg(),s.coreFlowFraction(),s.waterLevelIn(),
            s.xenon(),s.iodine(),s.boronPpm(),s.burnupMwdPerTonne(),notches,charge,s.reactivityTotal(),s.betaEff(),s.promptLifetime(),
            new double[0],s.fuelExcessReactivityDkOverK(),s.dopplerCoefficientPerCAtAnchor(),s.elapsedSeconds(),s.scramActive(),
            s.intermediateRangeMonitorRanges(),position);
    }
    private CoreDriveRemap() {}
}
