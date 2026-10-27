package dev.bwr.mod.reactor;

import dev.bwr.mod.eccs.CondensateStorageTankBlock;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.piping.PipeTopology;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A fill line into the reactor well (the mirror of {@link CavityDrainPortBlockEntity}). The formed reactor steps it once a tick from its
 * own tick (this block entity has no ticker); between steps it only remembers what the last step found. Nothing of it is saved.
 *
 * <h2>Two ways in</h2>
 * <ul>
 *   <li><b>A pump.</b> A feed, condensate, RHR or core spray pump whose discharge is piped to the flange delivers into this reactor exactly as
 *   it would through a vessel injection nozzle (the pump's own law, its suction, its power; the water joins the vessel's inventory through the
 *   pump's report). The port only says which reactor that is ({@link #pumpReactor()}), and only while it stands in that reactor's usable
 *   well with the head off.</li>
 *   <li><b>Gravity from a tank.</b> A Condensate Storage Tank on the line, through a Motor-Operated Water Valve, whose outlet flange (its block
 *   centre) stands higher than the water at the port, fills the well by the drain port's law ({@link CavityDrainFlow}): {@code Q = Cd A rho
 *   sqrt(2 g H) x opening}, H the flange's height less the water's (the well's surface, or the port's sill when the water is under it). What
 *   leaves the tank enters the vessel's inventory to the kilogram; the vessel's model gives it its temperature, as it does the canal's water.</li>
 * </ul>
 * Nothing is automatic: the valve moves only when the player moves it, and a pump runs only when the player runs it.
 */
public final class CavityFillPortBlockEntity extends BlockEntity {

    /** What the last step found. The ordinal is what the INFO tab is sent. */
    public enum State {
        NO_REACTOR("no_reactor", "Not in the well of a formed reactor."),
        NO_WELL("no_well", "The reactor has no usable well (no cavity seal, or the wall opens below the flange)."),
        NOT_IN_WELL("not_in_well", "The wet face does not touch the well's water. Place it in the well wall or floor, flange out."),
        BELOW_SEAL("below_seal", "Below the cavity seal row: a fill port must stand at or above it."),
        HEAD_ON("head_on", "The vessel head is on: lift it off before flooding the well."),
        PUMP_LINE("pump_line", "Ready for a pump: water a pump sends down this line goes into the well. No tank on the line to fill it by gravity."),
        NO_VALVE("no_valve", "No motor-operated water valve on the line to the tank."),
        VALVE_CLOSED("valve_closed", "The valve on the line is closed."),
        NO_HEAD("no_head", "The tank's outlet is not above the water at the port: a tank fills the well by gravity only from above it. Use a pump, or a higher tank."),
        TANK_EMPTY("tank_empty", "The Condensate Storage Tank is empty."),
        FILLING("filling", "Filling the well from the Condensate Storage Tank by gravity.");

        private final String key;
        private final String english;

        State(String key, String english) {
            this.key = key;
            this.english = english;
        }

        public String key() { return key; }

        public Component text() {
            return BwrText.tr("status.bwr.cavity_fill.state." + key, english);
        }

        /** True when the port stands where it can put water into the well (a pump may use it). */
        public boolean placed() {
            return ordinal() > HEAD_ON.ordinal();
        }

        public static State byOrdinal(int i) {
            return i >= 0 && i < values().length ? values()[i] : NO_REACTOR;
        }
    }

    private State state = State.NO_REACTOR;
    private double flowKgPerS;
    private double headM;
    private double opening;
    private int valves;
    @Nullable
    private BlockPos source;
    @Nullable
    private BlockPos reactorPos;
    private long steppedAt = Long.MIN_VALUE;
    private double carryKg;
    private double totalKg;

    public CavityFillPortBlockEntity(BlockPos pos, BlockState blockState) {
        super(BwrBlockEntities.CAVITY_FILL_PORT.get(), pos, blockState);
    }

    @Override public void onLoad() {
        super.onLoad();
        CavityDrains.add(level, worldPosition);
    }

    @Override public void setRemoved() {
        CavityDrains.remove(level, worldPosition);
        super.setRemoved();
    }

    @Override public void onChunkUnloaded() {
        CavityDrains.remove(level, worldPosition);
        super.onChunkUnloaded();
    }

    public State state() { return state; }
    /** Water put into the well by gravity on the last step, kg/s (a pump's delivery is the pump's own, on the reactor's injection). */
    public double flowKgPerS() { return flowKgPerS; }
    public double headM() { return headM; }
    public double lineOpening() { return opening; }
    public int valveCount() { return valves; }
    @Nullable public BlockPos sourcePos() { return source; }
    /** Water this port has passed by gravity since it loaded, kg. */
    public double totalKg() { return totalKg; }

    /** The wet cell: behind the flange. */
    public BlockPos wetCell() {
        return worldPosition.relative(getBlockState().getValue(CavityFillPortBlock.FACING).getOpposite());
    }

    /**
     * The reactor a pump discharging into this port delivers to: the one that stepped it on this tick or the last, while the port stands in
     * its usable well with the head off. Null otherwise (the pump then has nowhere to send its water, as with an unconnected discharge).
     */
    @Nullable
    public ReactorControllerBlockEntity pumpReactor() {
        if (level == null || reactorPos == null || !state.placed() || level.getGameTime() - steppedAt > 2) {
            return null;
        }
        return dev.bwr.mod.world.LoadedWorld.blockEntity(level, reactorPos) instanceof ReactorControllerBlockEntity r && !r.isRemoved()
                && r.isFormed() && r.vesselState() == VesselState.REFUELING ? r : null;
    }

    private void idle(State why) {
        state = why;
        flowKgPerS = 0;
        headM = 0;
        opening = 0;
        valves = 0;
        source = null;
        carryKg = 0;
    }

    /** Why a port at this place cannot reach the well of {@code reactor}, or null when it can. Shared by the drain-port rules. */
    @Nullable
    static State placement(ReactorControllerBlockEntity reactor, BlockPos port, Direction facing) {
        if (reactor.core() == null || !reactor.isFormed()) {
            return State.NO_REACTOR;
        }
        var cavity = reactor.cavity();
        var envelope = reactor.vesselEnvelope();
        if (cavity == null || envelope == null) {
            return State.NO_REACTOR;
        }
        var visual = cavity.visual();
        if (!cavity.present() || !cavity.wellUsable() || cavity.profile() == null || visual == null || visual.well() == null) {
            return State.NO_WELL;
        }
        BlockPos wet = port.relative(facing.getOpposite());
        if (facing == Direction.UP || !visual.well().cell(wet.getY(), wet.getX(), wet.getZ())) {
            return State.NOT_IN_WELL;
        }
        if (port.getY() < cavity.sealRow()) {
            return State.BELOW_SEAL;
        }
        return null;
    }

    /** One tick of this port for the reactor that owns the well it may stand in. */
    void step(ReactorControllerBlockEntity reactor, double dt) {
        var level = this.level;
        if (level == null || level.isClientSide()) {
            idle(State.NO_REACTOR);
            return;
        }
        Direction facing = getBlockState().getValue(CavityFillPortBlock.FACING);
        State bad = placement(reactor, worldPosition, facing);
        if (bad != null) {
            // a port of another reactor's well is not this one's to judge: keep what that reactor found
            if (reactorPos == null || reactorPos.equals(reactor.getBlockPos()) || level.getGameTime() - steppedAt > 2) {
                reactorPos = null;
                idle(bad);
            }
            return;
        }
        reactorPos = reactor.getBlockPos().immutable();
        steppedAt = level.getGameTime();
        if (reactor.vesselState() != VesselState.REFUELING) {
            idle(State.HEAD_ON);
            return;
        }
        var cavity = reactor.cavity();
        var envelope = reactor.vesselEnvelope();
        var vessel = reactor.core().getPressureVessel();
        var profile = cavity.profile();
        BlockPos wet = wetCell();
        double sill = Math.max(wet.getY() - envelope.min().getY(), profile.plateTop());
        double water = vessel.getWellWaterM3();
        double surface = water > 0 ? reactor.wellSurfaceLocal() : 0.0;
        double waterAtPortY = envelope.min().getY() + Math.max(sill, surface);

        // the line: which valves, how far open, which tank
        var graph = PipeTopology.get(level, worldPosition, facing, false, false);
        boolean anyTank = false;
        boolean anyValved = false;
        PipeTopology.Route best = null;
        if (!graph.truncated()) {
            for (PipeTopology.Route route : PipeTopology.routes(level, graph)) {
                var node = route.node();
                if (node.kind() != PipeTopology.Kind.END || node.state() == null
                        || !(node.state().getBlock() instanceof CondensateStorageTankBlock)
                        || !CondensateStorageTankBlock.acceptsWater(node.state(), node.face())) {
                    continue;
                }
                anyTank = true;
                if (!route.waterValves().isEmpty()) {
                    anyValved = true;
                    if (route.opening() > 0 && (best == null || route.opening() > best.opening())) {
                        best = route;
                    }
                }
            }
        }
        flowKgPerS = 0;
        headM = 0;
        opening = best == null ? 0 : best.opening();
        valves = best == null ? 0 : best.waterValves().size();
        source = best == null ? null : best.node().pos();
        if (!anyTank) { state = State.PUMP_LINE; carryKg = 0; return; }
        if (!anyValved) { state = State.NO_VALVE; carryKg = 0; return; }
        if (best == null) { state = State.VALVE_CLOSED; carryKg = 0; return; }
        var tank = dev.bwr.mod.world.LoadedWorld.blockEntity(level, best.node().pos());
        if (!(tank instanceof CondensateStorageTankBlockEntity cst) || !cst.ready()) { state = State.PUMP_LINE; source = null; carryKg = 0; return; }
        headM = best.node().pos().getY() + 0.5 - waterAtPortY;
        if (!(headM > 0)) { state = State.NO_HEAD; headM = Math.max(0.0, headM); carryKg = 0; return; }
        if (!(cst.availableWaterKg() >= 1.0)) { state = State.TANK_EMPTY; carryKg = 0; return; }

        double rate = CavityDrainFlow.kgPerS(headM, opening);
        double wanted = rate * dt + carryKg;
        int whole = (int) Math.floor(wanted);
        state = State.FILLING;
        if (whole <= 0) {
            carryKg = wanted;
            flowKgPerS = rate;
            return;
        }
        double drawn = cst.drawKg(whole);
        if (drawn > 0) {
            // what left the tank is in the vessel, to the kilogram
            vessel.restoreLiquidMassKg(vessel.getLiquidMassKg() + drawn);
            reactor.setChanged();
        }
        carryKg = drawn >= whole ? wanted - whole : 0.0;
        flowKgPerS = drawn / dt;
        totalKg += drawn;
        if (drawn < whole) {
            state = drawn > 0 ? State.FILLING : State.TANK_EMPTY;
        }
        for (var valve : best.waterValves()) valve.record(drawn);
    }

    public List<Component> statusComponents() {
        List<Component> lines = new ArrayList<>();
        lines.add(BwrText.tr("status.bwr.cavity_fill.title", "Cavity fill port"));
        lines.add(state.text());
        if (state == State.FILLING || state == State.NO_HEAD || state == State.TANK_EMPTY) {
            lines.add(BwrText.tr("status.bwr.cavity_fill.numbers", "%s kg/s | head %s m | valve %s%% open",
                    String.format(Locale.ROOT, "%.0f", flowKgPerS), String.format(Locale.ROOT, "%.2f", headM),
                    String.format(Locale.ROOT, "%.0f", opening * 100.0)));
        }
        return lines;
    }

    public List<String> statusLines() { return BwrText.english(statusComponents()); }
}
