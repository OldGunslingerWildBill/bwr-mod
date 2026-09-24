package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.eccs.AdsControllerBlockEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * CC:Tweaked peripheral for the Automatic Depressurisation System.
 *
 * <p>The real system's automation is precisely what this mod does not ship:
 * there is no level signal, no confirmatory pressure permissive and no timer
 * here. {@link #start()} opens the valves because a program said so.
 *
 * <p>The decision it exists to make expensive: opening these valves is the only
 * way to get the low pressure systems to deliver anything, and it costs pool
 * heat, vessel inventory and — once the pressure is gone — the turbine-driven
 * systems that were keeping the core covered in the first place.
 *
 * <h2>Everything here runs on the server thread</h2>
 * Every method is {@code @LuaFunction(mainThread = true)}, and it must stay that
 * way. CC runs a plain {@code @LuaFunction} on the computer thread, and every
 * actuator below ends in {@code BlockEntity.setChanged()}, which in 1.21.1 calls
 * {@code Level.blockEntityChanged} (into {@code ServerChunkCache.getChunk},
 * which sees the wrong thread and blocks the caller on the server's main-thread
 * task queue) and then {@code updateNeighbourForOutputSignal}, which dispatches
 * {@code neighborChanged} to adjacent comparators — arbitrary block update logic
 * running concurrently with the tick that is already running block updates.
 */
public class AdsPeripheral implements IPeripheral {

    private final AdsControllerBlockEntity be;

    public AdsPeripheral(AdsControllerBlockEntity be) {
        this.be = be;
    }

    @LuaFunction(mainThread=true) public int getDivision(){return be.getDivision();}
    @LuaFunction(mainThread=true) public void setDivision(int division) throws dan200.computercraft.api.lua.LuaException {
        if(division<0||division>4)throw new dan200.computercraft.api.lua.LuaException("Division must be 0 through 4");
        be.setDivision(division);
    }
    @Override
    public String getType() {
        return "bwr_ads";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof AdsPeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    // --- Actuators ------------------------------------------------------

    /** Open the valve bank. Unconditional, immediate, and irreversible in practice. */
    @LuaFunction(mainThread = true)
    public final void start() {
        be.setComputerControlled(true);
        be.setOpen(true);
    }

    /** Shut the bank again. The vessel will not re-pressurise quickly. */
    @LuaFunction(mainThread = true)
    public final void stop() {
        be.setComputerControlled(true);
        be.setOpen(false);
    }

    /**
     * What fraction of the available valve bank to hold open, 0 to 1. A partial
     * blowdown is a slower blowdown, which spends the suppression pool more
     * gently and keeps the turbine drives alive longer.
     *
     * <p>Non-finite is refused. {@code setValveDemandFraction} silently ignores
     * a NaN, so without this a control loop with a divide-by-zero in it would
     * see its demand not change and have nothing to debug against.
     */
    @LuaFunction(mainThread = true)
    public final void setFlow(double fraction) throws LuaException {
        if (!Double.isFinite(fraction)) {
            throw new LuaException("valve demand fraction must be a finite number, got " + fraction);
        }
        be.setComputerControlled(true);
        be.setValveDemandFraction(fraction);
    }

    /** Hand control back to redstone. */
    @LuaFunction(mainThread = true)
    public final void releaseControl() {
        be.setComputerControlled(false);
    }

    // --- Measurements ---------------------------------------------------

    @LuaFunction(mainThread = true)
    public final boolean isOpen() {
        return be.isOpen();
    }

    /** Relief valves this controller can use: present, and discharging underwater. */
    @LuaFunction(mainThread = true)
    public final int getValveCount() {
        return be.getValveCount();
    }

    @LuaFunction(mainThread = true)
    public final int getOpenValveCount() {
        return be.getHeldOpenCount();
    }

    @LuaFunction(mainThread = true)
    public final double getValveDemand() {
        return be.getValveDemandFraction();
    }

    /** Steam leaving the vessel through the bank right now, kg/s. */
    @LuaFunction(mainThread = true)
    public final double getBlowdownFlow() {
        return be.getBlowdownKgPerS();
    }

    /**
     * Pneumatic charge in the nitrogen bottles, 0 to 1.
     *
     * <p>Hardware, in the same sense as the scram accumulators: a full charge
     * holds the whole bank open for about half an hour with no electrical
     * supply, and then the valves shut themselves. A compressor with power
     * outruns the leak.
     */
    @LuaFunction(mainThread = true)
    public final double getNitrogenCharge() {
        return be.getNitrogenCharge();
    }

    @LuaFunction(mainThread = true)
    public final int getEnergy() {
        return be.energy().getEnergyStored();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("division",be.getDivision());
        m.put("open", be.isOpen());
        m.put("valves", be.getValveCount());
        m.put("valvesOpen", be.getHeldOpenCount());
        m.put("valveDemand", be.getValveDemandFraction());
        m.put("blowdownFlow", be.getBlowdownKgPerS());
        m.put("nitrogen", be.getNitrogenCharge());
        m.put("energy", be.energy().getEnergyStored());
        m.put("reactorFound", be.hasReactor());
        return m;
    }
}
