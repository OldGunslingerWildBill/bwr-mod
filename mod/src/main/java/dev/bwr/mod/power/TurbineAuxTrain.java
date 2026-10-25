package dev.bwr.mod.power;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.eccs.EccsPower;

import java.util.ArrayList;
import java.util.List;

/**
 * The turbine auxiliaries' share of one shaft-train tick: the lube oil system and the bearings, the rotor temperature, bow and
 * vibration, the turning gear, and the generator winding and its cooling (TURBINE-AUX.md). Called by {@link PowerTrain#tick} after
 * the shaft step, once per train per tick; the physics lives in {@code dev.bwr.core.turbine.TurbineAux}.
 *
 * <h2>Which trains it governs</h2>
 * A system is governed when the train has opted into it, and a train that never did runs exactly as before this existed:
 * <ul>
 *   <li>oil and bearings: a Main Oil Tank or Shaft-Driven Oil Pump is attached, or a module was placed new by a player;</li>
 *   <li>rotor bow: a generator's turning gear is switched on, or a module was placed new by a player;</li>
 *   <li>winding temperature: a Generator Cooling Skid is attached, or a module was placed new by a player.</li>
 * </ul>
 * "Placed new" is the server option {@code turbineAuxRequired} (default on) applied when a player places a module from an item that
 * does not carry an older module's condition; see {@link PowerModuleBlockEntity#auxRequired()}. Nothing here switches anything on or
 * off, trips or protects: every reading is a fact, and every pump, gear and valve is the player's.
 */
public final class TurbineAuxTrain {
    private TurbineAuxTrain() {}

    public static final double DT = 0.05;
    public static final int GEAR_FE_PER_TICK = EccsPower.fePerTickFromWatts(TurbineAux.GEAR_MOTOR_W);
    public static final int GEAR_CAPACITY_FE = 200_000;

    /** What a section reports about the train's auxiliaries; written every tick by {@link #step}. All zero and ungoverned for a legacy train. */
    public static final class Readout {
        public boolean lubeGoverned, bowGoverned, genGoverned;
        public boolean oilTank, shaftPump, acRunning, dcRunning;
        public double oilPsig, oilFilm, oilC = Double.NaN, oilLevel = Double.NaN, pumpFlow;
        public double vibration;
        public boolean gearOn, gearRolling;
        public boolean coolingSkid, statorRunning;
        public double statorFlow, h2Psig = Double.NaN, coolantC = Double.NaN;
        public boolean oilPressureAlarm, oilLevelAlarm, oilTempAlarm, bearingAlarm, vibrationAlarm, windingAlarm, h2Alarm;
        void clear() {
            lubeGoverned = bowGoverned = genGoverned = oilTank = shaftPump = acRunning = dcRunning = gearOn = gearRolling = coolingSkid = statorRunning = false;
            oilPsig = oilFilm = pumpFlow = vibration = statorFlow = 0;
            oilC = oilLevel = h2Psig = coolantC = Double.NaN;
            oilPressureAlarm = oilLevelAlarm = oilTempAlarm = bearingAlarm = vibrationAlarm = windingAlarm = h2Alarm = false;
        }
        public boolean anyAlarm() {
            return oilPressureAlarm || oilLevelAlarm || oilTempAlarm || bearingAlarm || vibrationAlarm || windingAlarm || h2Alarm;
        }
    }

    private static final Readout LEGACY = new Readout();

    /** One tick. {@code train} is every section; {@code active} the ones this call advances (a half-joined train splits). */
    public static void step(List<PowerModuleBlockEntity> train, List<PowerModuleBlockEntity> active, long now) {
        boolean required = false;
        var lube = new ArrayList<TurbineAuxBlockEntity>();
        var shaft = new ArrayList<TurbineAuxBlockEntity>();
        boolean gearOn = false;
        for (var m : train) {
            required |= m.auxRequired();
            gearOn |= m.block().generator() && m.turningGear();
            for (var a : m.auxEntities()) {
                if (a.kind() == TurbineAuxBlock.Kind.LUBE_OIL) lube.add(a);
                else if (a.kind() == TurbineAuxBlock.Kind.SHAFT_PUMP) shaft.add(a);
            }
        }
        double rpm = active.isEmpty() ? 0 : active.get(0).rpm;
        // The rotor soaks up the steam's heat whenever there is any; whether a bow can follow is the governed part.
        for (var m : active) {
            if (m.block().generator()) continue;
            m.advanceRotor(m.flowKgPerS > 0.5 && m.inletC > 0 ? m.inletC : Double.NaN);
        }
        boolean lubeGov = required || !lube.isEmpty() || !shaft.isEmpty();
        boolean bowGov = required || gearOn;
        boolean genGov = required || train.stream().anyMatch(m -> m.block().generator() && !m.genCoolingSkids().isEmpty());

        if (!lubeGov && !bowGov && !genGov) {
            for (var m : active) { m.setAux(LEGACY); m.vibration = 0; m.bearingC = TurbineAux.AMBIENT_C; }
            return;
        }

        // The turning gear: engaged and fed, on a shaft at or under its overrun speed, it rolls the train at its own speed.
        boolean rolling = false;
        for (var g : train) {
            if (g.block().generator() && g.turningGear() && rpm <= TurbineAux.GEAR_MAX_RPM && g.drawGear()) rolling = true;
        }
        if (rolling) {
            rpm = TurbineAux.gearSpeed(rpm);
            for (var m : active) m.rpm = rpm;
        }

        var r = new Readout();
        r.lubeGoverned = lubeGov;
        r.bowGoverned = bowGov;
        r.genGoverned = genGov;
        r.gearOn = gearOn;
        r.gearRolling = rolling;
        double bearingFilm = 1;

        if (lubeGov) {
            // A train joined half-way through a tick is stepped twice; its tanks are advanced only the first time.
            boolean fresh = lube.isEmpty();
            for (var t : lube) fresh |= t.beginStep(now);
            double flow = 0, suction = 0, psig, oilSum = 0, minLevel = 1;
            if (fresh) {
                for (var t : lube) {
                    flow += t.oilPumpFlow();
                    suction += t.suction();
                }
                double shaftFlow = !shaft.isEmpty() && !lube.isEmpty() ? TurbineAux.shaftPumpFlow(rpm) * suction / lube.size() : 0;
                flow += shaftFlow;
                psig = TurbineAux.oilPsig(flow);
                bearingFilm = TurbineAux.film(flow, rpm);
                double heat = TurbineAux.oilHeatKw(rpm, bearingFilm);
                for (var t : lube) t.oilThermal(heat / lube.size(), psig, bearingFilm);
            } else {
                psig = lube.get(0).psig();
                bearingFilm = lube.get(0).film();
                flow = lube.stream().mapToDouble(TurbineAuxBlockEntity::pumpFlow).sum();
            }
            for (var t : lube) {
                oilSum += t.oilC();
                minLevel = Math.min(minLevel, t.oilFraction());
                r.acRunning |= t.acRunning();
                r.dcRunning |= t.dcRunning();
            }
            r.oilTank = !lube.isEmpty();
            r.shaftPump = !shaft.isEmpty();
            r.pumpFlow = flow;
            r.oilPsig = psig;
            r.oilFilm = bearingFilm;
            r.oilC = lube.isEmpty() ? TurbineAux.AMBIENT_C : oilSum / lube.size();
            r.oilLevel = lube.isEmpty() ? Double.NaN : minLevel;
            r.oilPressureAlarm = rpm >= TurbineAux.STANDSTILL_RPM && psig < TurbineAux.OIL_PRESSURE_ALARM_PSIG;
            r.oilLevelAlarm = lube.isEmpty() || minLevel < TurbineAux.OIL_LEVEL_ALARM;
            r.oilTempAlarm = r.oilC > TurbineAux.OIL_ALARM_C;
        }

        double maxVibration = 0;
        for (var m : active) {
            if (lubeGov) {
                double target = TurbineAux.bearingTargetC(bearingFilm, Double.isNaN(r.oilC) ? TurbineAux.AMBIENT_C : r.oilC, rpm);
                m.bearingC = TurbineAux.bearingStepC(m.bearingC, target, DT);
                m.addShaftDamage(TurbineAux.bearingDamage(m.bearingC, DT));
            } else {
                m.bearingC = TurbineAux.AMBIENT_C;
            }
            if (bowGov && !m.block().generator()) {
                m.advanceBow(rpm, rolling);
                m.vibration = TurbineAux.vibrationMmPerS(m.bow(), rpm);
                m.addShaftDamage(TurbineAux.vibrationDamage(m.vibration, DT));
                maxVibration = Math.max(maxVibration, m.vibration);
            } else if (!bowGov) {
                m.vibration = 0;
            }
        }
        for (var m : active) if (m.block().generator()) m.vibration = bowGov ? maxVibration : 0;
        r.vibration = maxVibration;
        r.vibrationAlarm = maxVibration > TurbineAux.VIBRATION_ALARM_MM_S;
        for (var m : active) r.bearingAlarm |= lubeGov && m.bearingC > TurbineAux.BEARING_ALARM_C;

        // Per generator: its winding, and the skids set against it.
        for (var m : active) {
            var out = new Readout();
            copy(r, out);
            if (m.block().generator() && genGov) generator(m, rpm, now, out);
            m.setAux(out);
        }
    }

    private static void generator(PowerModuleBlockEntity g, double rpm, long now, Readout out) {
        var skids = g.genCoolingSkids();
        double flow = 0, h2 = 0, coolantSum = 0;
        boolean ran = false;
        for (var s : skids) {
            if (s.beginStep(now)) s.statorStep();
            flow += s.statorFlow();
            h2 = Math.max(h2, s.h2Psig());
            coolantSum += s.coolantC();
            ran |= s.statorRunning();
        }
        flow = Math.min(1, flow);
        double coolant = skids.isEmpty() ? TurbineAux.AMBIENT_C : coolantSum / skids.size();
        double cooling = TurbineAux.coolingFraction(flow, h2);
        double load = g.electricMW / 1500.0;
        double toCoolant = TurbineAux.windingToCoolantKw(g.windingC, coolant, cooling);
        g.windingC = TurbineAux.windingStepC(g.windingC, TurbineAux.windingLossKw(load, rpm), toCoolant, DT);
        g.addShaftDamage(TurbineAux.windingDamage(g.windingC, DT));
        for (var s : skids) s.coolantStep(Math.max(0, toCoolant) / skids.size());
        out.coolingSkid = !skids.isEmpty();
        out.statorRunning = ran;
        out.statorFlow = flow;
        out.h2Psig = skids.isEmpty() ? 0 : h2;
        out.coolantC = coolant;
        out.windingAlarm = g.windingC > TurbineAux.WINDING_ALARM_C;
        out.h2Alarm = skids.isEmpty() || h2 < TurbineAux.H2_ALARM_PSIG;
    }

    private static void copy(Readout a, Readout b) {
        b.lubeGoverned = a.lubeGoverned; b.bowGoverned = a.bowGoverned; b.genGoverned = a.genGoverned;
        b.oilTank = a.oilTank; b.shaftPump = a.shaftPump; b.acRunning = a.acRunning; b.dcRunning = a.dcRunning;
        b.oilPsig = a.oilPsig; b.oilFilm = a.oilFilm; b.oilC = a.oilC; b.oilLevel = a.oilLevel; b.pumpFlow = a.pumpFlow;
        b.vibration = a.vibration; b.gearOn = a.gearOn; b.gearRolling = a.gearRolling;
        b.oilPressureAlarm = a.oilPressureAlarm; b.oilLevelAlarm = a.oilLevelAlarm; b.oilTempAlarm = a.oilTempAlarm;
        b.bearingAlarm = a.bearingAlarm; b.vibrationAlarm = a.vibrationAlarm;
    }
}
