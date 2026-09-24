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
 * <p><b>An ADS controller in range takes and gives back the same flag.</b>
 * {@code AdsControllerBlockEntity.setValve} writes {@code setComputerControlled}
 * itself, so the claim is shared rather than exclusive, and the interaction is
 * worth being precise about because it decides whether a program's command
 * survives:
 *
 * <ul>
 *   <li>An ADS that is <i>idle</i> only ever touches valves it is holding or has
 *       just released — {@code commandValves} takes the release branch on
 *       {@code held.remove(vp)} — so a valve this peripheral opened in an idle
 *       bank is left alone and the Lua command stands.</li>
 *   <li>An ADS that <i>fires</i> drives every valve it can see, so it will
 *       reopen or reseat a valve a program is working, and it does not consult
 *       {@code isComputerControlled()} before doing it.</li>
 *   <li>When the ADS releases a valve — the bank shuts, the valve leaves the
 *       bank, or the controller is broken — it clears the claim as well as the
 *       position, so a Lua claim made before that point is gone and the valve is
 *       answering redstone again.</li>
 * </ul>
 *
 * <p>So a program working a valve directly should either keep it outside an ADS
 * bank or command the bank through {@code bwr_ads}, and in either case should
 * read {@link #isComputerControlled()} back rather than assume the claim held.
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

    @LuaFunction(mainThread=true) public int getDivision(){return be.getDivision();}
    @LuaFunction(mainThread=true) public void setDivision(int division) throws dan200.computercraft.api.lua.LuaException {
        if(division<0||division>4)throw new dan200.computercraft.api.lua.LuaException("Division must be 0 through 4");
        be.setDivision(division);
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
     * Steam this valve is passing, kg/s.
     *
     * <p>Computed by whichever controller is metering the valve — the
     * suppression pool for its heat balance, the ADS controller for the relief
     * channel. Those two are the only callers of
     * {@code SafetyReliefValveBlockEntity.flowKgPerS} in the mod, so a valve with
     * neither in range is not metered at all and reads zero: nothing is
     * accounting for its steam.
     *
     * <p><b>A shut valve reads zero without waiting to be re-metered.</b> The
     * stored figure is only refreshed while something is metering the valve, so
     * a valve that was open and passing when its suppression pool was broken —
     * or when the chunk holding the pool controller unloaded — would otherwise go
     * on quoting 42 kg/s of relief that is not happening, for as long as nobody
     * rebuilt the pool. That is the most misleading thing a readout can tell a
     * program trying to work out where its steam went. Restating "a shut valve
     * passes nothing" is not a threshold and not a judgement: it is the first
     * line of {@code flowKgPerS} itself.
     */
    @LuaFunction(mainThread = true)
    public final double getFlow() {
        return be.isOpen() ? be.getLastFlowKgPerS() : 0.0;
    }

    /** Flow one fully open valve passes at rated dome pressure, kg/s. Nameplate. */
    @LuaFunction
    public final double getCapacity() {
        return SafetyReliefValveBlockEntity.CAPACITY_KG_PER_S;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("division", be.getDivision());
        m.put("open", be.isOpen());
        m.put("computerControlled", be.isComputerControlled());
        m.put("dischargeSubmerged", be.isDischargeSubmerged());
        // Same rule as getFlow(): a shut valve passes nothing, and the stored
        // figure is only refreshed while something is metering the valve.
        m.put("flow", be.isOpen() ? be.getLastFlowKgPerS() : 0.0);
        m.put("capacity", SafetyReliefValveBlockEntity.CAPACITY_KG_PER_S);
        return m;
    }
}
