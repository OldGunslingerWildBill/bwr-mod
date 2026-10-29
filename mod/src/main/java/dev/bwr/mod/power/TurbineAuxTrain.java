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
    /** The readings that raise an alarm flag. They are facts for a panel or a program; nothing acts on them. */
    public static final double ALARM_BEARING_C = 100, ALARM_OIL_C = 65, ALARM_OIL_LEVEL = 0.30, ALARM_OIL_PSIG = 15, ALARM_VIBRATION_MM_S = 7.1,
            ALARM_WINDING_C = 120, ALARM_H2_PSIG = 20;
    /** The filter's pressure drop at which a change is due, psi (an alarm reading, like the rest). */
    public static final double ALARM_FILTER_DP_PSI = TurbineAux.FILTER_DP_CHANGE_PSI;
    public static final int GEAR_FE_PER_TICK = EccsPower.fePerTickFromWatts(TurbineAux.GEAR_MOTOR_W);

    /** What a section reports about the train's auxiliaries; written every tick by {@link #step}. All zero and ungoverned for a legacy train. */
    public static final class Readout {
        public boolean lubeGoverned, bowGoverned, genGoverned;
        public boolean oilTank, shaftPump, acRunning, dcRunning;
        public double oilPsig, oilFilm, oilC = Double.NaN, oilLevel = Double.NaN, pumpFlow;
        public double vibration;
        public boolean gearOn, gearRolling, gearFed;
        public boolean coolingSkid, statorRunning;
        public double statorFlow, h2Psig = Double.NaN, coolantC = Double.NaN;
        public boolean oilPressureAlarm, oilLevelAlarm, oilTempAlarm, bearingAlarm, vibrationAlarm, windingAlarm, h2Alarm;
        /** The oil filter housing on the train's tank (TURBINE-AUX.md): none, and these read "no signal", on a tank without one. */
        public boolean filterHousing, filterBypass, filterAlarm;
        public double filterLife = Double.NaN, filterDpPsi = Double.NaN;
        /**
         * What reaches THIS section's bearings (TURBINE-AUX.md, Piping): {@code oilFed} some pump's oil reaches it (the integral header or its
         * piped supply and drain), {@code oilPiped} the train's oil is piped (the sections can differ), and for a piped section whether its
         * supply run joins a pump discharge and its drain run joins a tank return. All false on a train with no oil system.
         */
        public boolean oilFed, oilPiped, oilSupplyPiped, oilDrainPiped;
        void clear() {
            lubeGoverned = bowGoverned = genGoverned = oilTank = shaftPump = acRunning = dcRunning = gearOn = gearRolling = gearFed = coolingSkid = statorRunning = false;
            oilPsig = oilFilm = pumpFlow = vibration = statorFlow = 0;
            oilC = oilLevel = h2Psig = coolantC = Double.NaN;
            oilPressureAlarm = oilLevelAlarm = oilTempAlarm = bearingAlarm = vibrationAlarm = windingAlarm = h2Alarm = false;
            filterHousing = filterBypass = filterAlarm = false;
            filterLife = filterDpPsi = Double.NaN;
            oilFed = oilPiped = oilSupplyPiped = oilDrainPiped = false;
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
        // The oil system the train has: what is bolted on it and what its pipes reach (OilCircuit; with no pipe on it, just what is bolted on).
        var plan = OilCircuit.plan(train, lube, shaft);
        boolean lubeGov = required || !plan.tanks.isEmpty() || !plan.shafts.isEmpty();
        boolean bowGov = required || gearOn;
        boolean genGov = required || train.stream().anyMatch(m -> m.block().generator() && !m.genCoolingSkids().isEmpty());

        if (!lubeGov && !bowGov && !genGov) {
            for (var m : active) { m.setAux(LEGACY); m.vibration = 0; m.bearingC = TurbineAux.AMBIENT_C; }
            return;
        }

        // The turning gear: engaged and fed, on a shaft at or under its overrun speed, it rolls the train at its own speed.
        // Its motor is on the AC bus of the Main Oil Tank, with the AC oil pump.
        boolean rolling = false, gearFed = false;
        for (var g : train) {
            if (!g.block().generator() || !g.turningGear()) continue;
            boolean fed = false;
            for (var t : lube) if (!fed && t.acFe() >= GEAR_FE_PER_TICK) fed = true;
            gearFed |= fed;
            if (fed && rpm <= TurbineAux.GEAR_MAX_RPM) {
                for (var t : lube) if (t.drawAc(GEAR_FE_PER_TICK)) break;
                rolling = true;
            }
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
        r.gearFed = gearFed;
        double bearingFilm = 1;

        // One section's oil: the header pressure and film at its bearings, the oil reaching it, and what its lines join.
        var oil = new java.util.IdentityHashMap<PowerModuleBlockEntity, OilState>();
        if (lubeGov) {
            // The tanks that serve the train: those bolted on it, and (a piped train) the ones its pipes reach.
            List<TurbineAuxBlockEntity> tanks = plan.legacy ? lube : List.copyOf(plan.tanks);
            // A train joined half-way through a tick is stepped twice; its tanks are advanced only the first time.
            var stepped = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<TurbineAuxBlockEntity, Boolean>());
            for (var t : tanks) if (t.beginStep(now)) stepped.add(t);
            boolean fresh = tanks.isEmpty() || !stepped.isEmpty();
            double flow = 0, psig, oilSum = 0, minLevel = 1;
            if (plan.legacy) {
                // Nothing piped: every pump bolted on the train feeds every section through the integral header, exactly as it always did.
                double suction = 0;
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
                var shared = new OilState();
                shared.psig = psig;
                shared.film = bearingFilm;
                shared.flow = flow;
                shared.fed = !lube.isEmpty() || !shaft.isEmpty();
                for (var m : train) oil.put(m, shared);
            } else {
                psig = generalOil(plan, train, rpm, stepped, oil);
                bearingFilm = oil.values().stream().mapToDouble(o -> o.film).min().orElse(0);
                flow = oil.values().stream().mapToDouble(o -> o.flow).max().orElse(0);
            }
            for (var t : tanks) {
                oilSum += t.oilC();
                minLevel = Math.min(minLevel, t.oilFraction());
                r.acRunning |= t.acRunning();
                r.dcRunning |= t.dcRunning();
            }
            double trainOilC = tanks.isEmpty() ? TurbineAux.AMBIENT_C : oilSum / tanks.size();
            r.oilTank = !tanks.isEmpty();
            r.shaftPump = !(plan.legacy ? shaft : List.copyOf(plan.shafts)).isEmpty();
            r.pumpFlow = flow;
            r.oilPsig = psig;
            r.oilFilm = bearingFilm;
            r.oilC = trainOilC;
            r.oilLevel = tanks.isEmpty() ? Double.NaN : minLevel;
            r.oilPressureAlarm = rpm >= TurbineAux.STANDSTILL_RPM && psig < ALARM_OIL_PSIG;
            r.oilLevelAlarm = tanks.isEmpty() || minLevel < ALARM_OIL_LEVEL;
            r.oilTempAlarm = r.oilC > ALARM_OIL_C;
            r.oilPiped = !plan.legacy;
            // The oil filter housings on the tanks: the worst of them is what the train reports. A tank with none adds nothing.
            double life = 1, dp = 0;
            for (var t : tanks) {
                var f = t.oilFilter();
                if (f == null) continue;
                r.filterHousing = true;
                life = Math.min(life, f.filterLife());
                dp = Math.max(dp, f.filterDpPsi());
                r.filterBypass |= f.filterBypass();
                r.filterAlarm |= f.filterChangeDue();
            }
            if (r.filterHousing) { r.filterLife = life; r.filterDpPsi = dp; }
            // The oil each section's bearings sit in: what reaches them (the tanks of the pumps feeding it), room air when nothing does.
            for (var m : train) {
                var o = oil.get(m);
                if (plan.legacy) { o.oilC = trainOilC; continue; }
                var f = plan.feeds.get(m);
                var src = f == null ? List.<TurbineAuxBlockEntity>of() : tanksOf(plan, f);
                double sum = 0;
                for (var t : src) sum += t.oilC();
                o.oilC = src.isEmpty() ? TurbineAux.AMBIENT_C : sum / src.size();
            }
        }

        double maxVibration = 0;
        for (var m : active) {
            if (lubeGov) {
                var o = oil.get(m);
                double target = TurbineAux.bearingTargetC(o.film, Double.isNaN(o.oilC) ? TurbineAux.AMBIENT_C : o.oilC, rpm);
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
        dev.bwr.mod.rumble.RumbleSources.turbine(active, maxVibration, rpm, now);
        r.vibrationAlarm = maxVibration > ALARM_VIBRATION_MM_S;
        for (var m : active) r.bearingAlarm |= lubeGov && m.bearingC > ALARM_BEARING_C;
        // A bearing run dry past the ignition point lights the oil left in its housing, if the train's tanks hold any (TURBINE-MAINTENANCE.md).
        if (lubeGov && !plan.tanks.isEmpty() && !active.isEmpty() && active.get(0).getLevel() instanceof net.minecraft.server.level.ServerLevel server) {
            var burnable = List.copyOf(plan.tanks);
            for (var m : active) {
                if (!m.burning() && dev.bwr.core.turbine.TurbineFire.bearingIgnites(m.bearingC, rpm)) TurbineFires.bearing(server, m, burnable);
            }
        }

        // Per generator: its winding, and the skids set against it.
        for (var m : active) {
            var out = new Readout();
            copy(r, out);
            var o = oil.get(m);
            if (o != null) {
                out.oilPsig = o.psig;
                out.oilFilm = o.film;
                out.pumpFlow = o.flow;
                out.oilC = o.oilC;
                out.oilFed = o.fed;
                out.oilSupplyPiped = o.supply;
                out.oilDrainPiped = o.drain;
                out.oilPressureAlarm = rpm >= TurbineAux.STANDSTILL_RPM && o.psig < ALARM_OIL_PSIG;
            }
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
        out.windingAlarm = g.windingC > ALARM_WINDING_C;
        out.h2Alarm = skids.isEmpty() || h2 < ALARM_H2_PSIG;
    }

    private static void copy(Readout a, Readout b) {
        b.lubeGoverned = a.lubeGoverned; b.bowGoverned = a.bowGoverned; b.genGoverned = a.genGoverned;
        b.oilTank = a.oilTank; b.shaftPump = a.shaftPump; b.acRunning = a.acRunning; b.dcRunning = a.dcRunning;
        b.oilPsig = a.oilPsig; b.oilFilm = a.oilFilm; b.oilC = a.oilC; b.oilLevel = a.oilLevel; b.pumpFlow = a.pumpFlow;
        b.vibration = a.vibration; b.gearOn = a.gearOn; b.gearRolling = a.gearRolling; b.gearFed = a.gearFed;
        b.oilPressureAlarm = a.oilPressureAlarm; b.oilLevelAlarm = a.oilLevelAlarm; b.oilTempAlarm = a.oilTempAlarm;
        b.bearingAlarm = a.bearingAlarm; b.vibrationAlarm = a.vibrationAlarm;
        b.filterHousing = a.filterHousing; b.filterBypass = a.filterBypass; b.filterAlarm = a.filterAlarm;
        b.filterLife = a.filterLife; b.filterDpPsi = a.filterDpPsi;
        b.oilFed = a.oilFed; b.oilPiped = a.oilPiped; b.oilSupplyPiped = a.oilSupplyPiped; b.oilDrainPiped = a.oilDrainPiped;
    }

    /** What reaches one section's bearings this tick. */
    private static final class OilState {
        double psig, film, flow, oilC = Double.NaN;
        boolean fed, supply, drain;
    }

    /** The tanks the pumps feeding a section draw from (a tank pump's own tank, a shaft pump's suction tanks), each once. */
    private static List<TurbineAuxBlockEntity> tanksOf(OilCircuit.Plan plan, OilCircuit.Feed feed) {
        var out = new ArrayList<TurbineAuxBlockEntity>();
        for (var p : feed.pumps) {
            if (p.kind() == TurbineAuxBlock.Kind.LUBE_OIL) { if (!out.contains(p)) out.add(p); }
            else for (var t : plan.suctionTanks(p)) if (!out.contains(t)) out.add(t);
        }
        return out;
    }

    /** A shaft pump's delivery: its speed law times the suction its tanks give, nothing with no tank to draw from or a valve shut. */
    private static double shaftFlow(OilCircuit.Plan plan, TurbineAuxBlockEntity shaft, double rpm) {
        var from = plan.suctionTanks(shaft);
        if (from.isEmpty()) return 0;
        double suction = 0;
        for (var t : from) suction += t.suction();
        return TurbineAux.shaftPumpFlow(rpm) * suction / from.size();
    }

    /**
     * The oil of a piped train (OilCircuit): each section gets the sum of what the pumps that reach it deliver, so its header pressure and
     * oil film are its own; the bearing heat of every fed section goes to the tanks its oil comes from, and each tank takes the highest
     * pressure it supplies. A section no pump reaches has no pressure and no film. Fills {@code out} for every section of the train and
     * returns the highest header pressure.
     */
    private static double generalOil(OilCircuit.Plan plan, List<PowerModuleBlockEntity> train, double rpm, java.util.Set<TurbineAuxBlockEntity> stepped,
                                     java.util.Map<PowerModuleBlockEntity, OilState> out) {
        // A tank is advanced once a tick, by the first train that reaches it: the others read what it delivered.
        for (var t : plan.tanks) if (stepped.contains(t)) t.oilPumpFlow();
        double top = 0;
        for (var m : train) {
            var f = plan.feeds.get(m);
            var st = new OilState();
            double flow = 0;
            if (f != null) {
                for (var p : f.pumps) flow += p.kind() == TurbineAuxBlock.Kind.LUBE_OIL ? p.pumpFlow() : shaftFlow(plan, p, rpm);
                st.fed = f.fed();
                st.supply = f.supplyPiped;
                st.drain = f.drainPiped;
            }
            st.flow = flow;
            st.psig = TurbineAux.oilPsig(flow);
            st.film = TurbineAux.film(flow, rpm);
            top = Math.max(top, st.psig);
            out.put(m, st);
        }
        if (!stepped.isEmpty()) {
            var heat = new java.util.IdentityHashMap<TurbineAuxBlockEntity, double[]>();
            for (var t : plan.tanks) heat.put(t, new double[] {0, 0, 1, 0});
            for (var m : train) {
                var f = plan.feeds.get(m);
                if (f == null || !f.fed()) continue;
                var st = out.get(m);
                double h = TurbineAux.oilHeatKw(rpm, st.film) / train.size();
                var src = tanksOf(plan, f);
                for (var t : src) {
                    var a = heat.get(t);
                    if (a == null) continue;
                    a[0] += h / src.size();
                    a[1] = Math.max(a[1], st.psig);
                    a[2] = Math.min(a[2], st.film);
                    a[3] = 1;
                }
            }
            for (var t : plan.tanks) {
                if (!stepped.contains(t)) continue;
                var a = heat.get(t);
                t.oilThermal(a[0], a[3] > 0 ? a[1] : 0, a[3] > 0 ? a[2] : 0);
            }
        }
        return top;
    }

}
