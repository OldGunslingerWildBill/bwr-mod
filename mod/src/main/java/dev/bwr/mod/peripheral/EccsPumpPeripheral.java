package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.core.eccs.EccsDesign;
import dev.bwr.core.eccs.EccsPump;
import dev.bwr.mod.eccs.EccsPower;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.eccs.SuctionSource;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * CC:Tweaked peripheral for one emergency injection machine.
 *
 * <p>The peripheral type carries the machine's name — {@code bwr_rcic},
 * {@code bwr_hpci}, {@code bwr_hpcs}, {@code bwr_lpcs}, {@code bwr_rhr},
 * {@code bwr_slc} — so {@code peripheral.find("bwr_rcic")} picks out exactly
 * the system a program means, and a plant with four RHR loops enumerates all
 * four. Every machine answers the same API regardless.
 *
 * <h2>Actuators and measurements, and nothing in between</h2>
 * {@link #start()} and {@link #stop()} are bare. They do not look at level, at
 * pressure, at suction alignment or at whether the drive has any power, and
 * nothing in the mod ever calls them. Since {@code SPEC.md} section 9 ships no
 * automatic actuation at all, the entire emergency response of the plant is
 * whatever the player writes against this surface — which is why it publishes
 * more than looks necessary.
 *
 * <p>There is deliberately no {@code isInjectionPermitted()}. What there is
 * instead is {@link #isAboveShutoffHead()}, {@link #getAvailableFlow()} and
 * {@link #getDevelopedHead()}: three measurements of a pump curve against a
 * pressure gauge, from which a control program can work out for itself whether
 * water will arrive. The real plant's 500 psig injection permissive is a chosen
 * setpoint and belongs in Lua.
 *
 * <h2>Everything here runs on the server thread</h2>
 * Every method is {@code @LuaFunction(mainThread = true)}, and it must stay that
 * way. CC runs a plain {@code @LuaFunction} on the computer thread; the
 * actuators all end in {@code BlockEntity.setChanged()}, which walks the chunk
 * map and dispatches neighbour updates, and the readouts all sample
 * {@link EccsPump}'s plain double fields while the tick thread is inside
 * {@code pump.step(dt)}.
 */
public class EccsPumpPeripheral implements IPeripheral {

    private final EccsPumpBlockEntity be;

    public EccsPumpPeripheral(EccsPumpBlockEntity be) {
        this.be = be;
    }

    @LuaFunction(mainThread = true)
    public void setSpeed(double fraction) throws LuaException {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) throw new LuaException("Speed must be 0..1");
        be.setComputerControlled(true);
        be.setSpeedDemandFraction(fraction);
    }
    @LuaFunction(mainThread = true)
    public double getTargetSpeed() { return be.pump().getSpeedDemandFraction(); }

    @Override
    public String getType() {
        return "bwr_" + be.design().id();
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof EccsPumpPeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    private EccsPump pump() {
        return be.pump();
    }

    // =================================================================
    // Actuators
    // =================================================================

    /**
     * Start the machine. Unconditional. The pump spins up over its own startup
     * time — half a minute for RCIC — and then delivers whatever the physics
     * allows, which may well be nothing.
     */
    @LuaFunction(mainThread = true)
    public final void start() {
        be.setComputerControlled(true);
        be.setRunning(true);
    }

    /** Stop the machine. Also unconditional; it coasts down. */
    @LuaFunction(mainThread = true)
    public final void stop() {
        be.setComputerControlled(true);
        be.setRunning(false);
    }

    /**
     * Set the discharge throttle as a fraction of rated flow, 0 to 1. This is
     * the flow controller, not a level setpoint: the machine holds the flow you
     * ask for if its pump curve can reach it, and less if it cannot.
     *
     * <p>Non-finite is refused: {@code EccsPump.setFlowDemandFraction} drops a
     * NaN silently, so a control loop that divided by zero would sit at its old
     * demand with nothing anywhere to say why.
     */
    @LuaFunction(mainThread = true)
    public final void setFlow(double fraction) throws LuaException {
        if (!Double.isFinite(fraction)) {
            throw new LuaException("flow demand fraction must be a finite number, got " + fraction);
        }
        be.setComputerControlled(true);
        be.setFlowDemandFraction(fraction);
    }

    /**
     * Line the suction up on {@code "pool"} or {@code "tank"}. Nothing swaps it
     * for you when the tank runs dry — that is a script somebody has to write.
     *
     * <p>An unrecognised name is an error rather than a silent fall back to the
     * pool. {@code SuctionSource.byName} defaults, which is right for reading a
     * saved world and wrong for a Lua call: a program that typed
     * {@code "csv"} for the condensate tank would quietly keep drawing hot pool
     * water and nothing would ever tell it.
     */
    @LuaFunction(mainThread = true)
    public final void setSuctionSource(String source) throws LuaException {
        if(be.design()==dev.bwr.core.eccs.EccsDesign.SLC){
            if(!"boron_tank".equalsIgnoreCase(source))throw new LuaException("SLC requires its physical boron_tank suction line");
            return;
        }
        SuctionSource lineup = SuctionSource.byName(source);
        if (!lineup.getSerializedName().equalsIgnoreCase(source)
                && !lineup.name().equalsIgnoreCase(source)) {
            throw new LuaException("no such suction source \"" + source
                    + "\"; this machine can take suction on \"pool\" or \"tank\"");
        }
        be.setSuctionSource(lineup);
    }

    /**
     * Line an RHR loop up for {@code "injection"} or {@code "pool_cooling"}.
     * Ignored by the machines that have no second job. One loop cannot do both,
     * which is the choice between saving the core now and keeping the heat sink.
     *
     * <p>An unrecognised name is an error. It used to fall through to
     * {@code INJECTION}, so a typo in the lineup name silently took an RHR loop
     * off pool cooling and put it on the vessel — the exact swap this call
     * exists to make deliberate.
     */
    @LuaFunction(mainThread = true)
    public final void setMode(String mode) throws LuaException {
        if ("pool_cooling".equalsIgnoreCase(mode) || "poolcooling".equalsIgnoreCase(mode)) {
            be.setMode(EccsPumpBlockEntity.Mode.POOL_COOLING);
        } else if ("injection".equalsIgnoreCase(mode)) {
            be.setMode(EccsPumpBlockEntity.Mode.INJECTION);
        } else {
            throw new LuaException("no such lineup \"" + mode
                    + "\"; a loop is lined up for \"injection\" or \"pool_cooling\"");
        }
    }

    /** Hand control back to redstone. */
    @LuaFunction(mainThread = true)
    public final void releaseControl() {
        be.setComputerControlled(false);
    }

    // =================================================================
    // Measurements
    // =================================================================

    @LuaFunction(mainThread = true)
    public final boolean isRunning() {
        return be.isRunning();
    }

    /** Water actually reaching the vessel or the spargers, kg/s. */
    @LuaFunction(mainThread = true)
    public final double getFlow() {
        return be.getDeliveredFlowKgPerS();
    }

    /** Flow that was commanded, kg/s. */
    @LuaFunction(mainThread = true)
    public final double getDemandedFlow() {
        return pump().getDemandedFlowKgPerS();
    }

    /**
     * Flow this machine could deliver against the present vessel pressure at
     * full speed with an unlimited suction, kg/s. Zero means the vessel is
     * harder than the pump, not that the machine has failed.
     */
    @LuaFunction(mainThread = true)
    public final double getAvailableFlow() {
        return pump().getAvailableFlowKgPerS();
    }

    /** Rated flow off the nameplate, kg/s. */
    @LuaFunction(mainThread = true)
    public final double getRatedFlow() {
        return be.design().ratedFlowKgPerS();
    }

    /** Shaft speed, 0 to 1. Lags every command. */
    @LuaFunction(mainThread = true)
    public final double getSpeed() {
        return pump().getSpeedFraction();
    }

    /** Pressure the pump is pushing against, psi. */
    @LuaFunction(mainThread = true)
    public final double getDifferentialPressure() {
        return pump().differentialPressurePsi();
    }

    /** Head the pump can develop at its present speed, psi. */
    @LuaFunction(mainThread = true)
    public final double getDevelopedHead() {
        return pump().getDevelopedHeadPsi();
    }

    /** Head the pump develops at zero flow and full speed, psi. Nameplate. */
    @LuaFunction(mainThread = true)
    public final double getShutoffHead() {
        return be.design().maximumDischargePsi();
    }

    /**
     * Whether the vessel is presently harder than this pump.
     *
     * <p>A measurement, not a permissive: nothing consults it and nothing is
     * prevented by it. A pump in this condition runs and delivers nothing. This
     * is the reading a low pressure system uses to tell its control program
     * that the ADS has not finished its job.
     */
    @LuaFunction(mainThread = true)
    public final boolean isAboveShutoffHead() {
        return pump().isAboveShutoffHead();
    }

    /** True when the drive cannot turn the pump as fast as it wants to go. */
    @LuaFunction(mainThread = true)
    public final boolean isDriveLimited() {
        return pump().isDriveLimited();
    }

    /** {@code "pool"} or {@code "tank"}. */
    @LuaFunction(mainThread = true)
    public final String getSuctionSource() {
        return be.getSuctionSource().getSerializedName();
    }

    /** Temperature of the water being injected, degrees C. */
    @LuaFunction(mainThread = true)
    public final double getSuctionTemperature() {
        return pump().getSuctionTemperatureC();
    }

    /** Flow the pump made that the suction source could not supply, kg/s. */
    @LuaFunction(mainThread = true)
    public final double getSuctionShortfall() {
        return be.getSuctionShortfallKgPerS();
    }

    /** Steam the turbine drive is taking out of the vessel, kg/s. Zero on a motor. */
    @LuaFunction(mainThread = true)
    public final double getSteamConsumption() {
        return pump().getSteamDemandKgPerS();
    }

    /** {@code "STEAM_TURBINE"} or {@code "ELECTRIC_MOTOR"}. */
    @LuaFunction(mainThread = true)
    public final String getDrive() {
        return be.design().drive().name();
    }

    /** {@code "VESSEL_INJECTION"} or {@code "CORE_SPRAY"}. */
    @LuaFunction(mainThread = true)
    public final String getDelivery() {
        return be.design().delivery().name();
    }

    /** {@code "INJECTION"} or {@code "POOL_COOLING"}. */
    @LuaFunction(mainThread = true)
    public final String getMode() {
        return be.getMode().name();
    }

    /** Energy in the machine's buffer, FE. Always zero on a turbine drive. */
    @LuaFunction(mainThread = true)
    public final int getEnergy() {
        return be.energy().getEnergyStored();
    }

    /** Buffer size, FE. */
    @LuaFunction(mainThread = true)
    public final int getEnergyCapacity() {
        return be.energy().getMaxEnergyStored();
    }

    /** Draw at full load, FE per tick. Zero on a turbine drive — that is the point of one. */
    @LuaFunction(mainThread = true)
    public final int getRatedPowerDraw() {
        return EccsPower.fePerTickFromWatts(be.design().motorRatingWatts());
    }

    /** Boron this machine is adding, ppm per minute. Zero except on SLC. */
    @LuaFunction(mainThread = true)
    public final double getBoronRate() {
        return be.deliveredBoronPpmPerMinute();
    }

    /** Everything above, in one call. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        EccsDesign d = be.design();
        EccsPump p = pump();
        m.put("system", d.id());
        m.put("boronKgPerS",be.deliveredBoronKgPerS());
        m.put("drive", d.drive().name());
        m.put("delivery", d.delivery().name());
        m.put("running", be.isRunning());
        m.put("mode", be.getMode().name());
        m.put("speed", p.getSpeedFraction());
        m.put("flow", be.getDeliveredFlowKgPerS());
        m.put("demandedFlow", p.getDemandedFlowKgPerS());
        m.put("availableFlow", p.getAvailableFlowKgPerS());
        m.put("ratedFlow", d.ratedFlowKgPerS());
        m.put("differentialPressure", p.differentialPressurePsi());
        m.put("developedHead", p.getDevelopedHeadPsi());
        m.put("shutoffHead", d.maximumDischargePsi());
        m.put("aboveShutoffHead", p.isAboveShutoffHead());
        m.put("driveLimited", p.isDriveLimited());
        m.put("suction",d==dev.bwr.core.eccs.EccsDesign.SLC?"boron_tank":be.getSuctionSource().getSerializedName());
        m.put("suctionTemperature", p.getSuctionTemperatureC());
        m.put("suctionShortfall", be.getSuctionShortfallKgPerS());
        m.put("steamConsumption", p.getSteamDemandKgPerS());
        m.put("boronRate", be.deliveredBoronPpmPerMinute());
        m.put("energy", be.energy().getEnergyStored());
        m.put("energyCapacity", be.energy().getMaxEnergyStored());
        m.put("ratedPowerDraw", EccsPower.fePerTickFromWatts(d.motorRatingWatts()));
        m.put("reactorFound", be.hasReactor());
        m.put("poolFound", be.hasPool());
        m.put("tankFound", be.hasTank());
        return m;
    }
}
