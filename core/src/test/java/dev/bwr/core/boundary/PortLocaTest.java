package dev.bwr.core.boundary;

import dev.bwr.core.*;
import dev.bwr.core.thermal.*;
import java.util.List;

public final class PortLocaTest {
    private static final double DT=.05;
    private static BoundaryOpening opening(double level, double fraction) {
        return new BoundaryOpening(BoundaryComponent.RECIRCULATION_LINE,level,
                fraction*BoundaryStress.RATED_CORE_FLOW_KG_PER_S,BoundaryStress.RATED_STEAM_FLOW_KG_PER_S);
    }
    private static PressureVessel vessel(double pressure, BoundaryOpening... openings) {
        var v=new PressureVessel(new CoreConfig());v.initialiseToNormalLevel();
        v.restorePressurePsig(pressure);v.setCollapsedLevelIn(8);
        v.setBoundaryOpenings(List.of(openings),v.getOccupiedVolumeM3());return v;
    }
    public static void test01_pressureAndPortSizeDriveFlow() {
        var lower=opening(-300,.2);var upper=opening(-100,.075);
        double slow=lower.liquidFlow(1,8,730),fast=lower.liquidFlow(1050,8,730);
        Check.greaterThan(slow*5,fast,"high-pressure break must be much faster than 1 psig");
        Check.greaterThan(upper.liquidFlow(1050,8,730)*2,fast,"lower inlet must lose coolant faster than upper outlet");
        Check.exactly(0,lower.liquidFlow(1050,-301,730),"cannot drain water below the port");
        Check.exactly(0,lower.steamFlow(0,-301),"no pressure-driven steam flow at atmospheric pressure");
        Check.greaterThan(0,lower.steamFlow(1050,-301),"uncovered water port must vent steam");
        Check.note("1 psig %.1f kg/s; 1050 psig %.1f kg/s",slow,fast);
    }
    public static void test02_submergedRuptureDepressurizesFlashesAndConservesMass() {
        var v=vessel(1050,opening(-300,.2));
        double start=v.getLiquidMassKg()+v.getSteamMassKg(),out=0,maxFlash=0;
        double volume=v.getOccupiedVolumeM3();
        for(int i=0;i<400;i++) {
            v.step(0,0,0,0,0,DT);
            out+=(v.getBoundaryLiquidFlowKgPerS()+v.getBoundarySteamFlowKgPerS())*DT;
            maxFlash=Math.max(maxFlash,v.getFlashingKgPerS());
        }
        Check.lessThan(1050,v.getPressurePsig(),"liquid break must depressurize even while submerged");
        Check.greaterThan(0,maxFlash,"depressurization must flash water into steam");
        Check.absolute(start-out,v.getLiquidMassKg()+v.getSteamMassKg(),.01,"blowdown mass balance");
        Check.absolute(volume,v.getOccupiedVolumeM3(),1e-8,"vessel volume cannot expand with the leak");
        Check.note("20 s: %.2f psig, level %.2f in, %.1f kg discharged, peak flash %.1f kg/s",v.getPressurePsig(),v.getCollapsedLevelIn(),out,maxFlash);
    }
    public static void test03_noArtificialLevelFloorAndNoSubmergedSteamVent() {
        var port=opening(-100,.075);var v=vessel(1050,port);
        v.setCollapsedLevelIn(-101);
        double before=v.getLiquidMassKg();v.step(50,0,0,0,0,DT);
        Check.exactly(0,v.getBoundaryLiquidFlowKgPerS(),"below nozzle: direct water drainage must stop");
        Check.greaterThan(0,v.getBoundarySteamFlowKgPerS(),"same opening becomes a steam vent");
        Check.lessThan(before,v.getLiquidMassKg(),"flashing/boiloff can lower inventory below nozzle elevation");
        Check.exactly(0,port.steamFlow(1050,8),"deeply submerged port does not directly drain steam dome");
    }
    public static void test04_feedwaterAndSteamRupturesVentExposedNozzles() {
        for(var c:List.of(BoundaryComponent.FEEDWATER_LINE,BoundaryComponent.MAIN_STEAM_LINE)) {
            var v=vessel(1050,new BoundaryOpening(c,30,200,1500));
            v.step(0,0,0,0,0,DT);
            Check.greaterThan(1000,v.getBoundarySteamFlowKgPerS(),"exposed "+c+" must vent steam rapidly");
            Check.lessThan(1050,v.getPressurePsig(),"steam rupture must depressurize");
            Check.exactly(0,v.getBoundaryLiquidFlowKgPerS(),"steam-space opening cannot drain submerged water directly");
        }
    }
    public static void test05_legacyBoundaryStateAndRuptureVolumeSurviveReload() {
        var b=new BoundaryStress();b.forceFailure(BoundaryComponent.RECIRCULATION_LINE,1050);
        var legacy=java.util.Arrays.copyOf(b.toArray(),BoundaryStress.LEGACY_SNAPSHOT_LENGTH);
        var loaded=new BoundaryStress();loaded.fromArray(legacy);
        Check.isTrue(loaded.isBroken(BoundaryComponent.RECIRCULATION_LINE),"old saves must retain failed piping");
        loaded.initialiseRuptureVolume(450);var next=new BoundaryStress();next.fromArray(loaded.toArray());
        Check.exactly(450,next.getRuptureVolumeM3(),"reload must retain blowdown geometry");
    }
    public static void test06_energyBalanceWithInflowAndMultipleBreaks() {
        var a=opening(-100,.075);var b=opening(-300,.2);var v=vessel(1050,a,b);
        double volume=v.getOccupiedVolumeM3();
        v.setFeedwaterFlowKgPerS(100);v.setFeedwaterEnthalpyKJPerKg(400);
        double m=v.getLiquidMassKg()+v.getSteamMassKg();
        double start=VesselBlowdown.at(1050,m,volume).energy();
        double hf=Saturation.liquidEnthalpyKJPerKg(v.getPressurePsia());
        v.step(0,0,0,0,0,.001);
        double nextMass=v.getLiquidMassKg()+v.getSteamMassKg();
        double end=VesselBlowdown.at(v.getPressurePsig(),nextMass,volume).energy();
        double out=v.getBoundaryLiquidFlowKgPerS()*.001;
        Check.absolute(m+.1-out,nextMass,1e-5,"parallel ruptures plus feedwater conserve mass");
        Check.absolute(start+40-out*hf,end,.01,"outlet enthalpy transport conserves internal energy");
    }
    public static void test07_upperBreakRetainsMoreInventoryThanLowerBreak() {
        var upper=vessel(1050,opening(-100,.075));
        var lower=vessel(1050,opening(-300,.2));
        for(int i=0;i<2400;i++) {
            upper.step(0,0,0,0,0,DT);lower.step(0,0,0,0,0,DT);
        }
        Check.lessThan(upper.getLiquidMassKg(),lower.getLiquidMassKg(),"lower break must lose more inventory");
        Check.lessThan(upper.getCollapsedLevelIn(),lower.getCollapsedLevelIn(),"lower port must allow a lower level");
        Check.exactly(0,upper.getBoundaryLiquidFlowKgPerS(),"upper break stops direct drainage at its elevation");
        Check.note("120 s: upper level %.1f in / %.0f kg; lower level %.1f in / %.0f kg",
                upper.getCollapsedLevelIn(),upper.getLiquidMassKg(),lower.getCollapsedLevelIn(),lower.getLiquidMassKg());
    }
    public static void test08_numericalVacuumFloorCannotCreateSteam() {
        var v=vessel(0,new BoundaryOpening(BoundaryComponent.MAIN_STEAM_LINE,60,0,2000));
        v.restoreLiquidMassKg(0);double mass=v.getSteamMassKg(),out=0;
        for(int i=0;i<200;i++) {
            v.step(0,0,1000,0,0,DT);out+=v.getSteamRemovalKgPerS()*DT;
            Check.isTrue(Double.isFinite(v.getPressurePsig())&&v.getLiquidMassKg()>=0,"dry blowdown state must remain finite");
        }
        Check.absolute(mass-out,v.getLiquidMassKg()+v.getSteamMassKg(),1e-5,"dry numerical floor must not manufacture steam");
    }
}
