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
 *   vessel (recirculation outlet and/or bottom drain port) -> pump -> regenerative exchanger (hot side)
 *     -> non-regenerative exchanger -> [ filter-demineraliser -> regenerative exchanger (cold side) -> feedwater port ]
 *                                      [ reject: a Condensate Storage Tank or a condenser, through any valves ]
 * </pre>
 *
 * A hop whose pipe ends at anything but the expected machine face is a crossed line and makes the circuit invalid; a
 * valve in a hop is a throttle (its opening scales the flow, a closed one stops it). Nothing here changes the world.
 */
public final class RwcuCircuit {
    public record End(BlockPos pos, Direction face, BlockState state, double opening) {}

    public boolean valid;
    public String status = "Awaiting pipe survey";
    public ReactorControllerBlockEntity reactor;
    public final List<RpvWaterInjectionPortBlockEntity> suctionPorts = new ArrayList<>();
    public final List<Double> suctionOpenings = new ArrayList<>();
    public RwcuRegenBlockEntity regen;
    public RwcuCoolerBlockEntity cooler;
    public RwcuDeminBlockEntity demin;
    public BlockPos returnPort;
    public final List<End> rejectEnds = new ArrayList<>();
    /** Smallest opening between the pump's discharge and the cooler (the suction ports' own openings are in {@link #suctionOpenings}). */
    public double mainBeyondSuction;
    /** Smallest opening on the way from the cooler through the demineraliser and regenerator to the vessel. */
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
        // 2. Pump discharge -> regenerative exchanger hot side.
        var discharge = ends(level, pumpPos, pump.faceOf(pumpState, RwcuUnitBlock.Role.PUMP_DISCHARGE));
        var hotIn = only(discharge, RwcuRegenBlock.class, RwcuUnitBlock.Role.HOT_IN);
        if (hotIn == null) {
            c.status = "Pump discharge must reach only the regenerative exchanger hot inlet";
            return c;
        }
        double main = hotIn.opening();
        c.regen = unit(level, hotIn, RwcuRegenBlockEntity.class);
        if (c.regen == null) { c.status = "Regenerative exchanger is unloaded"; return c; }
        // 3. Regenerative hot outlet -> cooler process inlet.
        var hotOut = ends(level, hotIn.pos(), ((RwcuUnitBlock) hotIn.state().getBlock()).faceOf(hotIn.state(), RwcuUnitBlock.Role.HOT_OUT));
        var coolerIn = only(hotOut, RwcuCoolerBlock.class, RwcuUnitBlock.Role.PROCESS_IN);
        if (coolerIn == null) {
            c.status = "Regenerative hot outlet must reach only the cooler process inlet";
            return c;
        }
        main = Math.min(main, coolerIn.opening());
        c.cooler = unit(level, coolerIn, RwcuCoolerBlockEntity.class);
        if (c.cooler == null) { c.status = "Cooler is unloaded"; return c; }
        // 4. Cooler process outlet -> demineraliser, and optionally the reject path.
        var processOut = ends(level, coolerIn.pos(), ((RwcuUnitBlock) coolerIn.state().getBlock()).faceOf(coolerIn.state(), RwcuUnitBlock.Role.PROCESS_OUT));
        if (processOut == null) { c.status = "Cooler outlet survey is truncated"; return c; }
        End bedIn = null;
        for (var end : processOut) {
            if (end.state().getBlock() instanceof RwcuDeminBlock demin && demin.roleAt(end.state(), end.face()) == RwcuUnitBlock.Role.BED_IN) {
                if (bedIn != null) { c.status = "Two demineraliser inlets on the cooler outlet"; return c; }
                bedIn = end;
            } else if (end.state().getBlock() instanceof dev.bwr.mod.eccs.CondensateStorageTankBlock
                    || end.state().getBlock() instanceof dev.bwr.mod.condenser.CondenserBlock) {
                c.rejectEnds.add(end);
                c.rejectOpening = Math.max(c.rejectOpening, end.opening());
            } else {
                c.status = "Cooler outlet line is crossed: it may reach only the demineraliser and a reject sink (tank or condenser)";
                return c;
            }
        }
        if (bedIn == null) { c.status = "Cooler outlet must reach the demineraliser inlet"; return c; }
        double bed = bedIn.opening();
        c.demin = unit(level, bedIn, RwcuDeminBlockEntity.class);
        if (c.demin == null) { c.status = "Demineraliser is unloaded"; return c; }
        // 5. Demineraliser outlet -> regenerative cold inlet.
        var bedOut = ends(level, bedIn.pos(), ((RwcuUnitBlock) bedIn.state().getBlock()).faceOf(bedIn.state(), RwcuUnitBlock.Role.BED_OUT));
        var coldIn = only(bedOut, RwcuRegenBlock.class, RwcuUnitBlock.Role.COLD_IN);
        if (coldIn == null || !coldIn.pos().equals(hotIn.pos())) {
            c.status = "Demineraliser outlet must reach the same regenerative exchanger's cold inlet";
            return c;
        }
        bed = Math.min(bed, coldIn.opening());
        // 6. Regenerative cold outlet -> a feedwater (water injection) port of the same vessel.
        var coldOut = ends(level, coldIn.pos(), ((RwcuUnitBlock) coldIn.state().getBlock()).faceOf(coldIn.state(), RwcuUnitBlock.Role.COLD_OUT));
        if (coldOut == null || coldOut.isEmpty()) { c.status = "Cold outlet has no feedwater port"; return c; }
        double back = 0;
        for (var end : coldOut) {
            if (!end.state().is(BwrBlocks.RPV_WATER_INJECTION_PORT.get())) {
                c.status = "Cold outlet may reach only feedwater (water injection) ports";
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
