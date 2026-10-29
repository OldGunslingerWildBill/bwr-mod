package dev.bwr.mod.power;

import dev.bwr.mod.piping.OilLineNetwork;
import dev.bwr.mod.piping.OilLineNetwork.Role;
import dev.bwr.mod.world.LoadedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which oil reaches which section of a shaft train (TURBINE-AUX.md, Piping). Read once a tick by {@link TurbineAuxTrain#step}; it changes
 * nothing and keeps nothing (the surveys it asks for are kept by {@link OilLineNetwork}, redone only when a block along a run changes).
 *
 * <h2>The rule</h2>
 * Every pump has a discharge, and where it goes depends on how it is installed:
 * <ul>
 *   <li><b>Bolted on the train, discharge not piped (the integral header, as it always was).</b> A Main Oil Tank against a section, or
 *   the Shaft-Driven Pump on the generator's end, feeds <i>every</i> section of the train through the machine's own header, with no
 *   pipe. Nothing is needed and nothing can be cut: this is every old save and every placement with no pipe on it.</li>
 *   <li><b>Piped.</b> A pump whose discharge port has a Lube Oil Pipe run that joins the bearing supply of any section is on a header,
 *   and feeds <i>only</i> the sections its header reaches (through open valves), and only those whose drain runs back to the same tank's
 *   return inlet: oil out through the supply, back by the drain. Such a pump may stand anywhere, bolted on the train or in a basement
 *   lube oil room; a pump that is piped is not also on the integral header.</li>
 * </ul>
 * "Piped" is decided by the pipework alone (valves taken as open), so closing a valve starves the sections behind it and never makes
 * the pump feed the whole train instead. A section's oil is then the sum of the pumps that reach it, each counted once; a section that
 * none reaches has no oil (its film is 0: the bearing laws act as for no oil pressure).
 *
 * <p>The Shaft-Driven Pump takes its <i>suction</i> the same way: piped to the suction outlet of a tank, it draws from the tanks that run
 * reaches (through open valves, and nothing if the valve is shut); not piped, from the Main Oil Tanks bolted on its train, as it always did.
 * With neither it delivers nothing.
 */
final class OilCircuit {
    private OilCircuit() {}

    /** The pumps whose oil reaches one section, and what its two lines join. */
    static final class Feed {
        /** Tanks (AC and DC pumps) and shaft pumps that deliver to this section, each once. */
        final List<TurbineAuxBlockEntity> pumps = new ArrayList<>();
        /** The section's supply run joins a pump discharge / its drain run joins a tank return (the pipework, valves taken as open). */
        boolean supplyPiped, drainPiped;
        /** Something bolted on the train feeds it through the integral header. */
        boolean integral;
        boolean fed() { return !pumps.isEmpty(); }
    }

    /** One tick's reading of the whole train's oil system. */
    static final class Plan {
        /** True when nothing is piped and the train runs on the integral header alone: the oldest, simplest rule applies. */
        boolean legacy = true;
        /** Every tank that feeds or is fed by the train: those bolted on it first (in the order of its modules), then the ones its pipes reach. */
        final LinkedHashSet<TurbineAuxBlockEntity> tanks = new LinkedHashSet<>();
        final LinkedHashSet<TurbineAuxBlockEntity> shafts = new LinkedHashSet<>();
        final Map<PowerModuleBlockEntity, Feed> feeds = new IdentityHashMap<>();
        /** The tanks a shaft pump draws from, and (the piped case) the ones it reaches with its valves as they are. */
        final Map<TurbineAuxBlockEntity, List<TurbineAuxBlockEntity>> suction = new IdentityHashMap<>();
        final Set<TurbineAuxBlockEntity> piped = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        /** The tanks that feed the shaft pump's suction, empty with none. */
        List<TurbineAuxBlockEntity> suctionTanks(TurbineAuxBlockEntity shaft) { return suction.getOrDefault(shaft, List.of()); }
    }

    private static TurbineAuxBlockEntity skid(Level level, BlockPos pos, TurbineAuxBlock.Kind kind) {
        return LoadedWorld.blockEntity(level, pos) instanceof TurbineAuxBlockEntity t && !t.isRemoved() && t.kind() == kind ? t : null;
    }

    static OilLineNetwork.Survey supply(PowerModuleBlockEntity m) { return survey(m, true); }
    static OilLineNetwork.Survey drain(PowerModuleBlockEntity m) { return survey(m, false); }

    private static OilLineNetwork.Survey survey(PowerModuleBlockEntity m, boolean supply) {
        var level = m.getLevel();
        if (level == null) return OilLineNetwork.Survey.NONE;
        var state = m.getBlockState();
        var block = m.block();
        return OilLineNetwork.survey(level, block.oilCell(m.getBlockPos(), state, supply), block.oilFace(state, supply));
    }

    /** The plan for a train, from the tanks and shaft pumps bolted on it. */
    static Plan plan(List<PowerModuleBlockEntity> train) {
        var lube = new ArrayList<TurbineAuxBlockEntity>();
        var shaft = new ArrayList<TurbineAuxBlockEntity>();
        for (var m : train) {
            for (var a : m.auxEntities()) {
                if (a.kind() == TurbineAuxBlock.Kind.LUBE_OIL) lube.add(a);
                else if (a.kind() == TurbineAuxBlock.Kind.SHAFT_PUMP) shaft.add(a);
            }
        }
        return plan(train, lube, shaft);
    }

    static Plan plan(List<PowerModuleBlockEntity> train, List<TurbineAuxBlockEntity> lube, List<TurbineAuxBlockEntity> shaft) {
        var p = new Plan();
        p.tanks.addAll(lube);
        p.shafts.addAll(shaft);
        for (var t : lube) if (t.dischargePiped()) { p.piped.add(t); p.legacy = false; }
        for (var s : shaft) {
            if (s.dischargePiped()) { p.piped.add(s); p.legacy = false; }
            if (s.suctionPiped()) p.legacy = false;
        }
        if (train.isEmpty() || train.get(0).getLevel() == null) return p;
        var level = train.get(0).getLevel();
        // The pumps that the sections' supply runs lead back to, bolted on this train or not.
        for (var m : train) {
            for (var e : supply(m).topo) {
                TurbineAuxBlockEntity pump = e.role() == Role.TANK_DISCHARGE ? skid(level, e.pos(), TurbineAuxBlock.Kind.LUBE_OIL)
                        : e.role() == Role.PUMP_DISCHARGE ? skid(level, e.pos(), TurbineAuxBlock.Kind.SHAFT_PUMP) : null;
                if (pump == null) continue;
                p.legacy = false;
                p.piped.add(pump);
                if (pump.kind() == TurbineAuxBlock.Kind.LUBE_OIL) p.tanks.add(pump); else p.shafts.add(pump);
            }
        }
        // A shaft pump's suction: the tanks its pipe reaches, else the tanks bolted on its train.
        for (var s : p.shafts) {
            var sv = s.oilSurvey(Role.PUMP_SUCTION);
            if (sv.joins(Role.TANK_SUCTION)) {
                for (var e : sv.topo) {
                    var t = e.role() == Role.TANK_SUCTION ? skid(level, e.pos(), TurbineAuxBlock.Kind.LUBE_OIL) : null;
                    if (t != null) p.tanks.add(t);
                }
                var live = new ArrayList<TurbineAuxBlockEntity>();
                for (var e : sv.live) {
                    var t = e.role() == Role.TANK_SUCTION ? skid(level, e.pos(), TurbineAuxBlock.Kind.LUBE_OIL) : null;
                    if (t != null && !live.contains(t)) live.add(t);
                }
                p.suction.put(s, live);
            } else {
                p.suction.put(s, lube);
            }
        }
        if (p.legacy) return p;
        for (var m : train) {
            var f = new Feed();
            for (var t : lube) if (!p.piped.contains(t)) { f.pumps.add(t); f.integral = true; }
            for (var s : shaft) if (!p.piped.contains(s)) { f.pumps.add(s); f.integral = true; }
            var sup = supply(m);
            var dr = drain(m);
            f.supplyPiped = sup.joins(Role.TANK_DISCHARGE) || sup.joins(Role.PUMP_DISCHARGE);
            f.drainPiped = dr.joins(Role.TANK_RETURN);
            for (var e : sup.live) {
                TurbineAuxBlockEntity pump = e.role() == Role.TANK_DISCHARGE ? skid(level, e.pos(), TurbineAuxBlock.Kind.LUBE_OIL)
                        : e.role() == Role.PUMP_DISCHARGE ? skid(level, e.pos(), TurbineAuxBlock.Kind.SHAFT_PUMP) : null;
                if (pump == null || f.pumps.contains(pump) || !returns(pump, dr, p)) continue;
                f.pumps.add(pump);
            }
            p.feeds.put(m, f);
        }
        return p;
    }

    /** Whether the section's drain run leads back to the return inlet of the tank the pump draws from (oil out by the supply, back by the drain). */
    private static boolean returns(TurbineAuxBlockEntity pump, OilLineNetwork.Survey drain, Plan p) {
        if (pump.kind() == TurbineAuxBlock.Kind.LUBE_OIL) return drain.reaches(Role.TANK_RETURN, pump.getBlockPos());
        for (var t : p.suctionTanks(pump)) if (drain.reaches(Role.TANK_RETURN, t.getBlockPos())) return true;
        return false;
    }

    /** The tanks that serve a train, bolted on or reached by its pipes (the oil that burns when a bearing or a line fails). */
    static List<TurbineAuxBlockEntity> tanks(List<PowerModuleBlockEntity> train) {
        return List.copyOf(plan(train).tanks);
    }
}
