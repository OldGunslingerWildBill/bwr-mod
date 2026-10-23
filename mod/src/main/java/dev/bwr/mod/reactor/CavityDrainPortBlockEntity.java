package dev.bwr.mod.reactor;

import dev.bwr.mod.eccs.CondensateStorageTankBlock;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.piping.PipeTopology;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.text.BwrText;
import dev.bwr.mod.water.ThermalWater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A drain through the wall or floor of the reactor well. The formed reactor steps it once a tick, from its own tick (this block
 * entity has no ticker); between steps it only remembers what the last step found, for the INFO tab, the computers and the
 * right-click text. Nothing of it is saved: it is derived from the blocks and the water.
 *
 * <h2>The law</h2>
 * The wet cell is the one behind the flange (the port's FACING is out of the well); it must be a cell of the well's water,
 * and the port must stand at or above the cavity seal row. The <b>sill</b> is the floor of that cell. Water stands over it when
 * the well's water is more than the well holds with its surface at the sill (the plate's height at the lowest). The driving
 * head is the depth of water over the sill plus the sill's height less the height of the receiving tank's inlet flange
 * ({@link CavityDrainFlow}); the flow is that law times the opening of the narrowest valve on the line. A line with no
 * motor-operated water valve on it passes nothing, and neither does one that does not reach a Condensate Storage Tank with room.
 * The water taken leaves the vessel's inventory, never below the sill's level, and goes into the tank at the vessel's coolant
 * temperature (at most 100 C, the tank being open to the air).
 */
public final class CavityDrainPortBlockEntity extends BlockEntity {

    /** What the last step found. The ordinal is what the INFO tab is sent. */
    public enum State {
        NO_REACTOR("no_reactor", "Not in the well of a formed reactor."),
        NO_WELL("no_well", "The reactor has no usable well (no cavity seal, or the wall opens below the flange)."),
        NOT_IN_WELL("not_in_well", "The wet face does not touch the well's water. Place it in the well wall or floor, flange out."),
        BELOW_SEAL("below_seal", "Below the cavity seal row: a drain must stand at or above it."),
        NO_WATER("no_water", "No water over the flange."),
        AT_SILL("at_sill", "The water is down to the port's sill."),
        NO_RECEIVER("no_receiver", "The line does not reach a Condensate Storage Tank."),
        NO_VALVE("no_valve", "No motor-operated water valve on the line."),
        VALVE_CLOSED("valve_closed", "The valve on the line is closed."),
        NO_HEAD("no_head", "The tank's inlet is above the water: no head to drain."),
        RECEIVER_FULL("receiver_full", "The Condensate Storage Tank is full."),
        DRAINING("draining", "Draining to the Condensate Storage Tank.");

        private final String key;
        private final String english;

        State(String key, String english) {
            this.key = key;
            this.english = english;
        }

        public String key() { return key; }

        public Component text() {
            return BwrText.tr("status.bwr.cavity_drain.state." + key, english);
        }

        public static State byOrdinal(int i) {
            return i >= 0 && i < values().length ? values()[i] : NO_REACTOR;
        }
    }

    private State state = State.NO_REACTOR;
    private double flowKgPerS;
    private double depthM;
    private double headM;
    private double opening;
    private int valves;
    private BlockPos receiver;
    private double carryKg;
    private double totalKg;

    public CavityDrainPortBlockEntity(BlockPos pos, BlockState blockState) {
        super(BwrBlockEntities.CAVITY_DRAIN_PORT.get(), pos, blockState);
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
    public double flowKgPerS() { return flowKgPerS; }
    public double depthM() { return depthM; }
    public double headM() { return headM; }
    /** The opening of the narrowest valve on the line the water takes, 0 to 1 (0 when there is no line). */
    public double lineOpening() { return opening; }
    public int valveCount() { return valves; }
    public BlockPos receiverPos() { return receiver; }
    /** Water this port has passed since it loaded, kg. */
    public double totalKg() { return totalKg; }

    /** The wet cell: behind the flange. */
    public BlockPos wetCell() {
        return worldPosition.relative(getBlockState().getValue(CavityDrainPortBlock.FACING).getOpposite());
    }

    private void idle(State why) {
        state = why;
        flowKgPerS = 0;
        depthM = 0;
        headM = 0;
        opening = 0;
        valves = 0;
        receiver = null;
        carryKg = 0;
    }

    /** One tick of this port for the reactor that owns the well it may stand in. */
    void step(ReactorControllerBlockEntity reactor, double dt) {
        var level = this.level;
        if (level == null || level.isClientSide() || reactor.core() == null || !reactor.isFormed()) {
            idle(State.NO_REACTOR);
            return;
        }
        var cavity = reactor.cavity();
        var envelope = reactor.vesselEnvelope();
        if (cavity == null || envelope == null) {
            idle(State.NO_REACTOR);
            return;
        }
        var visual = cavity.visual();
        if (!cavity.present() || !cavity.wellUsable() || cavity.profile() == null || visual == null || visual.well() == null) {
            idle(State.NO_WELL);
            return;
        }
        Direction facing = getBlockState().getValue(CavityDrainPortBlock.FACING);
        BlockPos wet = wetCell();
        if (facing == Direction.UP || !visual.well().cell(wet.getY(), wet.getX(), wet.getZ())) {
            idle(State.NOT_IN_WELL);
            return;
        }
        if (worldPosition.getY() < cavity.sealRow()) {
            idle(State.BELOW_SEAL);
            return;
        }
        var vessel = reactor.core().getPressureVessel();
        var profile = cavity.profile();
        double water = vessel.getWellWaterM3();
        double sill = Math.max(wet.getY() - envelope.min().getY(), profile.plateTop());
        double keep = profile.waterUpTo(sill);

        // The line: which valves, how far open, which tank. Read for the INFO tab whether or not there is water.
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
        depthM = Math.max(0.0, water > 0 ? reactor.wellSurfaceLocal() - sill : 0.0);
        headM = 0;
        opening = best == null ? 0 : best.opening();
        valves = best == null ? 0 : best.waterValves().size();
        receiver = best == null ? null : best.node().pos();
        if (!anyTank) { state = State.NO_RECEIVER; return; }
        if (!anyValved) { state = State.NO_VALVE; return; }
        if (best == null) { state = State.VALVE_CLOSED; return; }
        if (!(water > 0)) { state = State.NO_WATER; carryKg = 0; return; }
        double surplusKg = (water - keep) * dev.bwr.core.thermal.Saturation.liquidDensityKgPerM3(vessel.getPressurePsia());
        if (!(surplusKg >= 1.0) || !(depthM > 0)) { state = State.AT_SILL; carryKg = 0; return; }

        var tank = dev.bwr.mod.world.LoadedWorld.blockEntity(level, best.node().pos());
        if (!(tank instanceof CondensateStorageTankBlockEntity cst) || !cst.ready()) { state = State.NO_RECEIVER; receiver = null; return; }
        headM = depthM + (wet.getY() - (best.node().pos().getY() + 0.5));
        if (!(headM > 0)) { state = State.NO_HEAD; carryKg = 0; return; }

        double rate = CavityDrainFlow.kgPerS(headM, opening);
        double wanted = rate * dt + carryKg;
        int whole = (int) Math.min(Math.floor(wanted), Math.floor(surplusKg));
        if (whole <= 0) {
            carryKg = Math.min(wanted, surplusKg);
            state = State.DRAINING;
            flowKgPerS = rate;
            return;
        }
        double tankC = Math.min(100.0, reactor.core().getCoolantTemperatureC());
        IFluidHandler handler = cst.fluidHandler();
        int room = handler.fill(ThermalWater.atTemperature(whole, tankC), IFluidHandler.FluidAction.SIMULATE);
        if (room <= 0) { state = State.RECEIVER_FULL; carryKg = 0; return; }
        int moved = Math.min(whole, room);
        double taken = vessel.drainWellWaterKg(moved, keep);
        int sent = (int) Math.floor(taken + 1.0e-9);
        if (sent <= 0) { state = State.AT_SILL; carryKg = 0; return; }
        int accepted = handler.fill(ThermalWater.atTemperature(sent, tankC), IFluidHandler.FluidAction.EXECUTE);
        // Water never vanishes: whatever was taken and not accepted goes back into the vessel.
        double back = taken - accepted;
        if (back > 1.0e-9) {
            vessel.restoreLiquidMassKg(vessel.getLiquidMassKg() + back);
        }
        carryKg = moved == whole && accepted == whole ? Math.max(0.0, wanted - whole) : 0.0;
        flowKgPerS = accepted / dt;
        totalKg += accepted;
        state = moved < whole || accepted < sent ? State.RECEIVER_FULL : State.DRAINING;
        for (var valve : best.waterValves()) valve.record(accepted);
    }

    public List<Component> statusComponents() {
        List<Component> lines = new ArrayList<>();
        lines.add(BwrText.tr("status.bwr.cavity_drain.title", "Cavity drain port"));
        lines.add(state.text());
        if (state == State.DRAINING || state == State.RECEIVER_FULL || state == State.AT_SILL || state == State.NO_HEAD) {
            lines.add(BwrText.tr("status.bwr.cavity_drain.numbers", "%s kg/s | %s m over the sill | head %s m | valve %s%% open",
                    String.format(Locale.ROOT, "%.0f", flowKgPerS), String.format(Locale.ROOT, "%.2f", depthM),
                    String.format(Locale.ROOT, "%.2f", headM), String.format(Locale.ROOT, "%.0f", opening * 100.0)));
        }
        return lines;
    }

    public List<String> statusLines() { return BwrText.english(statusComponents()); }
}
