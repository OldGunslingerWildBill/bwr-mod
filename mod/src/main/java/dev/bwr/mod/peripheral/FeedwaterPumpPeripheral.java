package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.core.eccs.PumpDesign;
import dev.bwr.mod.eccs.EccsPower;
import dev.bwr.mod.eccs.PlantActuators;
import dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * CC:Tweaked peripheral for one reactor feed pump — {@code SPEC.md} section 15.
 *
 * <h2>This is where a player writes level control, and where the mod does not</h2>
 * Feedwater is the normal path that holds vessel inventory, and holding it is a
 * closed-loop control problem: read the level, compare it to where you want it,
 * move the demand. Every part of that is the player's to write. The mod supplies
 * a start command, a flow demand and a set of measurements, and it supplies no
 * level element, no steam flow element, no three-element controller, no
 * feed-pump trip and no runback. Nothing here looks at vessel level at all.
 *
 * <p>The measurements a control program needs are all here and they are all raw:
 * what the pump was told to do, what it is actually delivering, what its drive
 * can manage, and whether the suction is keeping up. Deciding what any of that
 * means is the program's job.
 *
 * <h2>The two machines read identically and behave differently</h2>
 * {@link #getDrive()} is the only call that distinguishes them, and a program
 * does not need it: a turbine-driven pump simply reports zero achievable speed
 * on a cold vessel and rising steam draw as it works, while a motor-driven pump
 * reports a power shortfall when the bus cannot carry it. Both are measurements
 * of hardware, and neither is the mod refusing to do something.
 *
 * <h2>Everything here runs on the server thread</h2>
 * Every method that touches block-entity or core state is
 * {@code @LuaFunction(mainThread = true)} and must stay that way; the remainder
 * return compile-time nameplate constants. The commands additionally go through
 * {@link PlantActuators}, which is what the redstone and GUI paths use, so one
 * guarantee covers every way in rather than one particular caller.
 *
 * <p>This class references CC:Tweaked types directly, so it must only ever be
 * loaded when CC:Tweaked is present. {@link BwrPeripheralSupport} is the guard.
 */
public class FeedwaterPumpPeripheral implements IPeripheral {

    private final FeedwaterPumpBlockEntity be;

    public FeedwaterPumpPeripheral(FeedwaterPumpBlockEntity be) {
        this.be = be;
    }

    /**
     * {@code bwr_motor_feed_pump} or {@code bwr_turbine_feed_pump}, so that
     * {@code peripheral.find} can pick out one kind of feed pump and not the
     * other — which matters, because they fail in completely different ways and
     * a program that lines up a blackout response wants the turbine ones.
     */
    @Override
    public String getType() {
        return "bwr_" + be.design().id();
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof FeedwaterPumpPeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    // --- Actuators ------------------------------------------------------

    /**
     * Start or stop the pump. Claims computer control, so redstone stops
     * fighting the program for the same command.
     */
    @LuaFunction(mainThread = true)
    public final void setRunning(boolean running) {
        PlantActuators.run(be, () -> {
            be.setComputerControlled(true);
            be.setRunning(running);
        });
    }

    /** Start the pump. */
    @LuaFunction(mainThread = true)
    public final void start() {
        setRunning(true);
    }

    /** Stop the pump. It coasts down over about six seconds; it does not stop dead. */
    @LuaFunction(mainThread = true)
    public final void stop() {
        setRunning(false);
    }

    /**
     * Command flow, as a fraction of this pump's rated capacity, 0 to 1.
     *
     * <p>Out of range clamps — the throttle has stops. Non-finite is refused by
     * name, because a control loop that divided by zero would otherwise put a
     * NaN demand into the pump and from there into the vessel's mass balance,
     * and every level and pressure reading downstream would become a dash with
     * nothing to say where it started. This rejects the <i>argument</i>; it
     * never makes a judgement about the plant.
     */
    @LuaFunction(mainThread = true)
    public final void setFlowDemand(double fraction) throws LuaException {
        if (!Double.isFinite(fraction)) {
            throw new LuaException("feedwater flow demand must be a finite number, got " + fraction);
        }
        PlantActuators.run(be, () -> {
            be.setComputerControlled(true);
            be.setFlowDemandFraction(fraction);
        });
    }

    /** Hand the pump back to its redstone signal. */
    @LuaFunction(mainThread = true)
    public final void releaseControl() {
        PlantActuators.run(be, () -> be.setComputerControlled(false));
    }

    // --- Measurements ---------------------------------------------------

    @LuaFunction(mainThread = true)
    public final boolean isRunning() {
        return be.isRunning();
    }

    /** Flow the pump has been told to deliver, as a fraction of rated. */
    @LuaFunction(mainThread = true)
    public final double getFlowDemand() {
        return be.getFlowDemandFraction();
    }

    /**
     * Water this pump is actually putting into the vessel, kg/s. Differs from
     * the demand for three separate reasons a program may want to tell apart:
     * the shaft is not up to speed, the vessel pressure is eating the pump's
     * head, or the suction cannot supply it.
     */
    @LuaFunction(mainThread = true)
    public final double getFlow() {
        return be.getDeliveredFlowKgPerS();
    }

    /** Shaft speed, 0 to 1. Lags the command, and coasts down when stopped. */
    @LuaFunction(mainThread = true)
    public final double getSpeed() {
        return be.pump().getSpeedFraction();
    }

    /** Fastest the drive could turn this pump right now against the present head, 0 to 1. */
    @LuaFunction(mainThread = true)
    public final double getAchievableSpeed() {
        return be.pump().getAchievableSpeedFraction();
    }

    /** Differential the pump is working against, psi. */
    @LuaFunction(mainThread = true)
    public final double getDifferentialPressure() {
        return be.pump().differentialPressurePsi();
    }

    /** Head the pump can develop at its current speed, psi. */
    @LuaFunction(mainThread = true)
    public final double getDevelopedHead() {
        return be.pump().getDevelopedHeadPsi();
    }

    /**
     * True when the vessel is above this pump's shutoff head, so no water is
     * entering however hard the shaft turns.
     *
     * <p>A measurement of a pump curve against a pressure, not a permissive.
     * Nothing refuses; the water simply stops arriving.
     */
    @LuaFunction(mainThread = true)
    public final boolean isAboveShutoffHead() {
        return be.pump().isAboveShutoffHead();
    }

    /**
     * Water the pump developed but could not be supplied with, kg/s. Non-zero
     * means the condensate return has stopped keeping up — the Mekanism turbine
     * is not giving water back fast enough, or the storage tank behind it is
     * running out.
     */
    @LuaFunction(mainThread = true)
    public final double getSuctionShortfall() {
        return be.getSuctionShortfallKgPerS();
    }

    /** Water in the pump's own suction buffer, kg. */
    @LuaFunction(mainThread = true)
    public final double getSuctionBuffer() {
        return be.getSuctionBufferKg();
    }

    /**
     * Steam the turbine drive is taking out of the vessel, kg/s. Zero on a
     * motor-driven pump. This steam leaves through the same path the main steam
     * does and condenses in the same place, so it is a pressure-control action
     * whether the operator meant it as one or not.
     */
    @LuaFunction(mainThread = true)
    public final double getDriveSteam() {
        return be.getSteamDrawKgPerS();
    }

    /** Electrical power the motor wants right now, watts. Zero on a turbine drive. */
    @LuaFunction(mainThread = true)
    public final double getPowerDemand() {
        return be.pump().getElectricalDemandWatts();
    }

    /** Energy in the motor's buffer, FE. */
    @LuaFunction(mainThread = true)
    public final double getEnergy() {
        return be.energy().getEnergyStored();
    }

    @LuaFunction(mainThread = true)
    public final double getEnergyCapacity() {
        return be.energy().getMaxEnergyStored();
    }

    @LuaFunction(mainThread = true)
    public final boolean isComputerControlled() {
        return be.isComputerControlled();
    }

    /**
     * True when this pump has a live reactor controller to deliver into. A pump
     * with none turns, draws power and puts nothing in a vessel.
     */
    @LuaFunction(mainThread = true)
    public final boolean isAttached() {
        return be.isAttached();
    }

    // --- Nameplate ------------------------------------------------------

    /** {@code ELECTRIC_MOTOR} or {@code STEAM_TURBINE}. */
    @LuaFunction
    public final String getDrive() {
        return be.design().drive().name();
    }

    /** Rated capacity of this pump, kg/s. */
    @LuaFunction
    public final double getRatedFlow() {
        return be.design().ratedFlowKgPerS();
    }

    /** Differential above which this pump delivers nothing, psi. */
    @LuaFunction
    public final double getShutoffHead() {
        return be.design().maximumDischargePsi();
    }

    /** Draw at full load, FE per tick. Zero on a turbine drive. */
    @LuaFunction
    public final double getRatedPowerDraw() {
        return EccsPower.fePerTickFromWatts(be.design().motorRatingWatts());
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("design", be.design().id());
        m.put("drive", be.design().drive().name());
        m.put("running", be.isRunning());
        m.put("flowDemand", be.getFlowDemandFraction());
        m.put("flow", be.getDeliveredFlowKgPerS());
        m.put("ratedFlow", be.design().ratedFlowKgPerS());
        m.put("speed", be.pump().getSpeedFraction());
        m.put("achievableSpeed", be.pump().getAchievableSpeedFraction());
        m.put("differentialPressure", be.pump().differentialPressurePsi());
        m.put("developedHead", be.pump().getDevelopedHeadPsi());
        m.put("aboveShutoffHead", be.pump().isAboveShutoffHead());
        m.put("suctionShortfall", be.getSuctionShortfallKgPerS());
        m.put("suctionBuffer", be.getSuctionBufferKg());
        m.put("driveSteam", be.getSteamDrawKgPerS());
        m.put("powerDemand", be.pump().getElectricalDemandWatts());
        m.put("energy", (double) be.energy().getEnergyStored());
        m.put("energyCapacity", (double) be.energy().getMaxEnergyStored());
        m.put("ratedPowerDraw",
                (double) EccsPower.fePerTickFromWatts(be.design().motorRatingWatts()));
        m.put("computerControlled", be.isComputerControlled());
        m.put("attached", be.isAttached());
        m.put("turbineDriven", be.design().drive() == PumpDesign.Drive.STEAM_TURBINE);
        return m;
    }
}
