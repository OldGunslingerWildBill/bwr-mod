package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.steam.SafetyReliefValveBlock;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * CC:Tweaked peripheral for one safety/relief valve.
 *
 * <h2>This valve does not open by itself, and now Lua can actually open it</h2>
 * {@code SafetyReliefValveBlockEntity} has documented "called from redstone or
 * from Lua" since it was written, but no peripheral was ever registered for it,
 * so {@code setComputerControlled} had zero callers anywhere in the mod and the
 * {@code !isComputerControlled()} guard in {@code SafetyReliefValveBlock
 * .neighborChanged} was a condition that could never be false. Redstone worked;
 * Lua did not, and could not. That is what this class fixes.
 *
 * <p>The consequence worth restating: <b>there is no automatic overpressure
 * relief anywhere in this plant.</b> Nothing in the mod lifts one of these on
 * pressure — {@code PhysicalConstants.SRV_SPRING_SETTINGS_PSIG} records what the
 * real valves are rated at and that is as far as the mod goes. Deciding when to
 * open a valve, and on what, is the whole of the player's relief logic.
 *
 * <h2>Claiming control</h2>
 * {@link #open()} and {@link #close()} claim computer control first, exactly as
 * {@code AdsPeripheral} and {@code EccsPumpPeripheral} do. Without the claim the
 * block owns the position: any neighbour update reaching a Lua-opened valve
 * would read {@code hasNeighborSignal() == false} and slam it shut.
 * {@link #releaseControl()} hands it back to redstone.
 *
 * <p>An ADS controller within range still commands the valves in its bank every
 * tick regardless of this flag — it is the bank controller and it does not
 * consult {@code isComputerControlled()}. So a valve inside an ADS bank answers
 * the ADS, and a program that wants to work that valve directly should either
 * be outside a bank or command the bank through {@code bwr_ads}.
 *
 * <h2>Everything here runs on the server thread</h2>
 * Every method is {@code @LuaFunction(mainThread = true)} and must stay that
 * way: the actuators reach {@code BlockEntity.setChanged()} and
 * {@code Level.setBlock}, and {@link #isDischargeSubmerged()} walks the level
 * looking for water. All three are server-thread work.
 *
 * <p>This class references CC:Tweaked types directly, so it must only ever be
 * loaded when CC:Tweaked is present. {@link BwrPeripheralSupport} is the guard.
 */
public class SafetyReliefValvePeripheral implements IPeripheral {

    private final SafetyReliefValveBlockEntity be;

    public SafetyReliefValvePeripheral(SafetyReliefValveBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "bwr_safety_relief_valve";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof SafetyReliefValvePeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    // --- Actuators ------------------------------------------------------

    /** Lift the valve. Unconditional: nothing is checked, and nothing checks it. */
    @LuaFunction(mainThread = true)
    public final void open() {
        command(true);
    }

    /** Reseat it. Equally unconditional. */
    @LuaFunction(mainThread = true)
    public final void close() {
        command(false);
    }

    /** Open or close in one call, for a program driving this from a boolean. */
    @LuaFunction(mainThread = true)
    public final void setOpen(boolean open) {
        command(open);
    }

    /**
     * Hand the valve back to redstone. The valve keeps its present position
     * until the next neighbour update reaches it, which is the same thing a
     * hardware selector switch does.
     */
    @LuaFunction(mainThread = true)
    public final void releaseControl() {
        be.setComputerControlled(false);
    }

    /**
     * Move the valve and keep its block state in step so the model, and anything
     * reading the block state, agree with the block entity.
     *
     * <p>{@code Block.UPDATE_CLIENTS} rather than {@code UPDATE_ALL} for the same
     * reason {@code AdsControllerBlockEntity.setValve} uses it: a neighbour
     * update here would re-enter the valve's own {@code neighborChanged} and the
     * ADS controller's validation on every stroke.
     */
    private void command(boolean open) {
        be.setComputerControlled(true);
        be.setOpen(open);
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        BlockState state = level.getBlockState(be.getBlockPos());
        if (state.hasProperty(SafetyReliefValveBlock.OPEN)
                && state.getValue(SafetyReliefValveBlock.OPEN) != open) {
            level.setBlock(be.getBlockPos(), state.setValue(SafetyReliefValveBlock.OPEN, open),
                    Block.UPDATE_CLIENTS);
        }
    }

    // --- Measurements ---------------------------------------------------

    @LuaFunction(mainThread = true)
    public final boolean isOpen() {
        return be.isOpen();
    }

    @LuaFunction(mainThread = true)
    public final boolean isComputerControlled() {
        return be.isComputerControlled();
    }

    /**
     * Whether this valve's discharge is underwater, as last surveyed.
     *
     * <p>A valve venting to atmosphere passes nothing in this model and
     * suppresses nothing in a real one. That is plumbing validation, not a
     * permissive: the valve still opens when told, it simply has nowhere to
     * discharge to.
     *
     * <p>The cached answer, deliberately. The survey walks 24 blocks downward
     * and marks the chunk dirty; the suppression pool controller, the ADS
     * controller and the block's own neighbour handling all refresh it, and
     * re-running it on every poll of a Lua loop would be twenty scans a second
     * for a fact that only changes when somebody moves water.
     */
    @LuaFunction(mainThread = true)
    public final boolean isDischargeSubmerged() {
        return be.isDischargeSubmerged();
    }

    /**
     * Steam this valve passed, kg/s, the last time anything computed it.
     *
     * <p>Computed by whichever controller is metering the valve — the
     * suppression pool for its heat balance, the ADS controller for the relief
     * channel. A valve with neither in range is not being metered by anything
     * and reads zero regardless of whether it is open, which is also the honest
     * answer: nothing is accounting for its steam.
     */
    @LuaFunction(mainThread = true)
    public final double getFlow() {
        return be.getLastFlowKgPerS();
    }

    /** Flow one fully open valve passes at rated dome pressure, kg/s. Nameplate. */
    @LuaFunction
    public final double getCapacity() {
        return SafetyReliefValveBlockEntity.CAPACITY_KG_PER_S;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("open", be.isOpen());
        m.put("computerControlled", be.isComputerControlled());
        m.put("dischargeSubmerged", be.isDischargeSubmerged());
        m.put("flow", be.getLastFlowKgPerS());
        m.put("capacity", SafetyReliefValveBlockEntity.CAPACITY_KG_PER_S);
        return m;
    }
}
