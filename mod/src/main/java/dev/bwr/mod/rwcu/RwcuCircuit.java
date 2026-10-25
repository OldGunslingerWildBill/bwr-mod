package dev.bwr.mod.rwcu;

import dev.bwr.mod.piping.PipeTopology;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.world.LoadedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The RWCU circuit as the pipes make it, found from the pump outward through {@code PipeTopology} (loaded chunks only,
 * the same cached surveys every other machine uses):
 *
 * <pre>
 *   vessel (recirculation outlet and/or bottom drain port) -> pump -> [ regenerative exchanger (hot side) ] -> [ non-regenerative exchanger ]
 *     -> stainless steel nanofilter -> [ regenerative exchanger (cold side) ] -> feedwater port
 *   a reject sink (Condensate Storage Tank or condenser) may hang off the line, through any valves, ahead of the nanofilter
 * </pre>
 *
 * The two exchangers are optional (square brackets): without them the loop runs at reactor temperature and costs the vessel
 * less heat. A regenerative exchanger is used on both sides or not at all. A hop whose pipe ends at anything but the expected
 * machine face is a crossed line and makes the circuit invalid; a valve in a hop is a throttle (its opening scales the flow,
 * a closed one stops it). Nothing here changes the world.
 */
public final class RwcuCircuit {
    public record End(BlockPos pos, Direction face, BlockState state, double opening) {}

    public boolean valid;
    public String status = "Awaiting pipe survey";
    public ReactorControllerBlockEntity reactor;
    public final List<RpvWaterInjectionPortBlockEntity> suctionPorts = new ArrayList<>();
    public final List<Double> suctionOpenings = new ArrayList<>();
    /** Null when the loop has no regenerative exchanger. */
    public RwcuRegenBlockEntity regen;
    /** Null when the loop has no non-regenerative exchanger. */
    public RwcuCoolerBlockEntity cooler;
    /** The stainless steel nanofilter (the unit that was the filter-demineraliser). */
    public RwcuDeminBlockEntity demin;
    public BlockPos returnPort;
    public final List<End> rejectEnds = new ArrayList<>();
    /** Smallest opening between the pump's discharge and the reject tee (the suction ports' own openings are in {@link #suctionOpenings}). */
    public double mainBeyondSuction;
    /** Smallest opening on the way from the reject tee through the nanofilter (and regenerator) to the vessel. */
    public double beddedOpening;
    /** Largest opening of a reject path (0 when there is none). */
    public double rejectOpening;

    private static List<End> ends(Level level, BlockPos pos, Direction face) {
        var graph = PipeTopology.get(level, pos, face, false, false);
        if (graph.truncated()) return null;
        List<End> out = new ArrayList<>();
        for (var route : PipeTopology.routes(level, graph)) {
            var node = route.node();
            if (node.kind() != PipeTopology.Kind.END || node.state() == null) continue;
            out.add(new End(node.pos(), node.face(), node.state(), route.opening()));
        }
        return out;
    }

    private static End only(List<End> ends, Class<? extends RwcuUnitBlock> type, RwcuUnitBlock.Role role) {
        if (ends == null || ends.size() != 1) return null;
        var e = ends.get(0);
        if (e.state().getBlock() instanceof RwcuUnitBlock unit && type.isInstance(unit) && unit.roleAt(e.state(), e.face()) == role) return e;
        return null;
    }

    /** Whether {@code end} is the next machine the process line may reach, given what it has already passed. */
    private static boolean nextMachine(RwcuCircuit c, End end) {
        if (!(end.state().getBlock() instanceof RwcuUnitBlock block)) return false;
        var role = block.roleAt(end.state(), end.face());
        if (block instanceof RwcuRegenBlock) return role == RwcuUnitBlock.Role.HOT_IN && c.regen == null && c.cooler == null;
        if (block instanceof RwcuCoolerBlock) return role == RwcuUnitBlock.Role.PROCESS_IN && c.cooler == null;
        return block instanceof RwcuDeminBlock && role == RwcuUnitBlock.Role.BED_IN;
    }

    public static RwcuCircuit resolve(Level level, BlockPos pumpPos, BlockState pumpState) {
        var c = new RwcuCircuit();
        if (!(pumpState.getBlock() instanceof RwcuPumpBlock pump) || !LoadedWorld.available(level, pumpPos)) {
            c.status = "Pump missing";
            return c;
        }
        // 1. Suction: only vessel ports that belong to one formed reactor.
        var suction = ends(level, pumpPos, pump.faceOf(pumpState, RwcuUnitBlock.Role.PUMP_SUCTION));
        if (suction == null || suction.isEmpty()) {
            c.status = "Suction has no vessel port: pipe it to a recirculation outlet or the bottom drain port";
            return c;
        }
        for (var end : suction) {
            boolean outlet = end.state().getBlock() instanceof RecirculationPortBlock port && port.isOutlet();
            boolean drain = end.state().getBlock() instanceof RpvBottomDrainPortBlock;
            if (!outlet && !drain) {
                c.status = "Suction line is crossed: it may only reach a recirculation outlet or the bottom drain port";
                return c;
            }
            if (!LoadedWorld.available(level, end.pos())) {
                c.status = "A chunk on the suction line is unloaded";
                return c;
            }
            if (!(LoadedWorld.blockEntity(level, end.pos()) instanceof RpvWaterInjectionPortBlockEntity be)) {
                c.status = "Suction vessel port has no entity";
                return c;
            }
            var owner = be.controller();
            if (owner == null || !owner.isFormed()) {
                c.status = "Suction port is not on a formed vessel";
                return c;
            }
            if (c.reactor != null && c.reactor != owner) {
                c.status = "Suction ports belong to two vessels";
                return c;
            }
            c.reactor = owner;
            c.suctionPorts.add(be);
            c.suctionOpenings.add(end.opening());
        }
        // 2. The process line from the pump's discharge to the nanofilter: an optional regenerative exchanger (hot side), an
        //    optional non-regenerative exchanger, then the nanofilter. A reject sink may hang off any hop.
        var cursor = ends(level, pumpPos, pump.faceOf(pumpState, RwcuUnitBlock.Role.PUMP_DISCHARGE));
        List<Double> hops = new ArrayList<>();
        int tee = -1;
        BlockPos regenPos = null;
        End bedIn = null;
        while (bedIn == null) {
            if (cursor == null) {
                c.status = "A line on the process side is too long or too branched to survey";
                return c;
            }
            End next = null;
            List<End> rejects = new ArrayList<>();
            for (var end : cursor) {
                if (nextMachine(c, end)) {
                    if (next != null) {
                        c.status = "The process line reaches two machines at once";
                        return c;
                    }
                    next = end;
                } else if (end.state().getBlock() instanceof dev.bwr.mod.eccs.CondensateStorageTankBlock
                        || end.state().getBlock() instanceof dev.bwr.mod.condenser.CondenserBlock) {
                    rejects.add(end);
                } else {
                    c.status = "Process line is crossed: it may reach only the regenerative exchanger, the cooler, the nanofilter (in that order) and a reject sink (tank or condenser)";
                    return c;
                }
            }
            if (next == null) {
                c.status = hops.isEmpty() ? "Pump discharge must reach the regenerative exchanger hot inlet, the cooler or the nanofilter inlet"
                        : "The process line must go on to the cooler or the nanofilter inlet";
                return c;
            }
            if (!rejects.isEmpty()) {
                if (tee >= 0) {
                    c.status = "Two reject tees on the process line";
                    return c;
                }
                tee = hops.size();
                c.rejectEnds.addAll(rejects);
                for (var r : rejects) c.rejectOpening = Math.max(c.rejectOpening, r.opening());
            }
            hops.add(next.opening());
            var block = (RwcuUnitBlock) next.state().getBlock();
            if (block instanceof RwcuRegenBlock) {
                c.regen = unit(level, next, RwcuRegenBlockEntity.class);
                if (c.regen == null) { c.status = "Regenerative exchanger is unloaded"; return c; }
                regenPos = next.pos();
                cursor = ends(level, next.pos(), block.faceOf(next.state(), RwcuUnitBlock.Role.HOT_OUT));
            } else if (block instanceof RwcuCoolerBlock) {
                c.cooler = unit(level, next, RwcuCoolerBlockEntity.class);
                if (c.cooler == null) { c.status = "Cooler is unloaded"; return c; }
                cursor = ends(level, next.pos(), block.faceOf(next.state(), RwcuUnitBlock.Role.PROCESS_OUT));
            } else {
                bedIn = next;
                c.demin = unit(level, next, RwcuDeminBlockEntity.class);
                if (c.demin == null) { c.status = "Nanofilter is unloaded"; return c; }
            }
        }
        if (tee < 0) tee = hops.size() - 1; // no reject: the tee is the hop into the nanofilter
        double main = 1.0, bed = 1.0;
        for (int i = 0; i < hops.size(); i++) {
            if (i < tee) main = Math.min(main, hops.get(i));
            else bed = Math.min(bed, hops.get(i));
        }
        // 3. Nanofilter outlet -> regenerative cold inlet (when the loop has a regenerator) or straight on to the feedwater port.
        var bedOut = ends(level, bedIn.pos(), ((RwcuUnitBlock) bedIn.state().getBlock()).faceOf(bedIn.state(), RwcuUnitBlock.Role.BED_OUT));
        List<End> coldOut;
        if (c.regen != null) {
            var coldIn = only(bedOut, RwcuRegenBlock.class, RwcuUnitBlock.Role.COLD_IN);
            if (coldIn == null || !coldIn.pos().equals(regenPos)) {
                c.status = "Nanofilter outlet must reach the same regenerative exchanger's cold inlet";
                return c;
            }
            bed = Math.min(bed, coldIn.opening());
            coldOut = ends(level, coldIn.pos(), ((RwcuUnitBlock) coldIn.state().getBlock()).faceOf(coldIn.state(), RwcuUnitBlock.Role.COLD_OUT));
        } else {
            coldOut = bedOut;
        }
        // 4. The return: a feedwater (water injection) port of the same vessel.
        if (coldOut == null || coldOut.isEmpty()) { c.status = "The return line has no feedwater port"; return c; }
        double back = 0;
        for (var end : coldOut) {
            if (!end.state().is(BwrBlocks.RPV_WATER_INJECTION_PORT.get())) {
                c.status = "The return line may reach only feedwater (water injection) ports";
                return c;
            }
            if (!(LoadedWorld.blockEntity(level, end.pos()) instanceof RpvWaterInjectionPortBlockEntity be) || be.controller() != c.reactor) {
                c.status = "Return port is not on the suction vessel";
                return c;
            }
            c.returnPort = end.pos();
            back = Math.max(back, end.opening());
        }
        bed = Math.min(bed, back);
        c.mainBeyondSuction = Math.max(0, Math.min(1, main));
        c.beddedOpening = Math.max(0, Math.min(1, bed));
        c.valid = true;
        c.status = "Circuit complete";
        return c;
    }

    private static <T> T unit(Level level, End end, Class<T> type) {
        if (!LoadedWorld.available(level, end.pos())) return null;
        var be = LoadedWorld.blockEntity(level, end.pos());
        return type.isInstance(be) ? type.cast(be) : null;
    }

    /** Whether the vessel's water stands above this suction port: a port draws nothing once the level falls to it. */
    public static boolean covered(ReactorControllerBlockEntity reactor, BlockPos port) {
        var core = reactor.core();
        var envelope = reactor.vesselEnvelope();
        if (core == null || envelope == null) return false;
        double elevation = VesselWaterGeometry.levelAtHeight(envelope, port.getY() + .5 - envelope.min().getY());
        return core.getCollapsedLevelIn() > elevation + 0.25;
    }
}
