package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * CC:Tweaked peripheral for a main steam isolation valve.
 *
 * <p>The MSIV documented "commanded from redstone or Lua" from the day it was
 * written, but no peripheral was ever registered for it, so
 * {@code setComputerControlled} had no callers, the persisted
 * {@code ComputerControlled} NBT key could only ever be written as false, and
 * {@code peripheral.find("bwr_msiv")} found nothing. Redstone worked; Lua did
 * not, and could not. That is what this class fixes.
 *
 * <h2>The transient is emergent, and nothing here scripts it</h2>
 * Closing an MSIV isolates the vessel. Steam generation continues, removal
 * stops, pressure climbs, voids collapse, moderation increases and power surges
 * — which is why real BWRs scram on MSIV closure rather than waiting for a flux
 * trip. Nothing in this mod writes that scram, and nothing here forms an opinion
 * about whether closing the valve is a reasonable thing to be doing.
 *
 * <p>The valve strokes over {@link #getStrokeSeconds()} rather than snapping
 * shut, and {@code TurbineSteamOutletBlockEntity} throttles its delivered steam
 * by {@link #getPosition()}, so how fast it closes is what sets how sharp the
 * transient is. A program that wants to know whether the line is actually
 * isolated reads the position, not the demand.
 *
 * <h2>Everything here runs on the server thread</h2>
 * Every method that touches the valve is {@code @LuaFunction(mainThread = true)}
 * and must stay that way: the actuators reach {@code BlockEntity.setChanged()}
 * and {@code Level.setBlock}, both of which are server-thread work.
 *
 * <p>This class references CC:Tweaked types directly, so it must only ever be
 * loaded when CC:Tweaked is present. {@link BwrPeripheralSupport} is the guard.
 */
public class MainSteamIsolationValvePeripheral implements IPeripheral {

    private final MainSteamIsolationValveBlockEntity be;

    public MainSteamIsolationValvePeripheral(MainSteamIsolationValveBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "bwr_msiv";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof MainSteamIsolationValvePeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    // --- Actuators ------------------------------------------------------

    /** Command the valve open. Unconditional. */
    @LuaFunction(mainThread = true)
    public final void open() {
        command(true);
    }

    /** Command the valve shut. Also unconditional; the mod never does this. */
    @LuaFunction(mainThread = true)
    public final void close() {
        command(false);
    }

    /** Command open or shut in one call, for a program driving this from a boolean. */
    @LuaFunction(mainThread = true)
    public final void setOpen(boolean open) {
        command(open);
    }

    /**
     * Hand the valve back to redstone. A redstone signal closes the valve, which
     * matches real fail-closed isolation, so a valve released while the line is
     * energised will start shutting on the next neighbour update.
     */
    @LuaFunction(mainThread = true)
    public final void releaseControl() {
        be.setComputerControlled(false);
    }

    /**
     * Move the demand and keep the block state in step, so the model and
     * anything reading the block state agree with the block entity.
     *
     * <p>{@code Block.UPDATE_CLIENTS} rather than {@code UPDATE_ALL}: a full
     * neighbour update here would re-enter this valve's own
     * {@code neighborChanged}, which is the code path that owns the demand when
     * the valve is <i>not</i> computer controlled.
     */
    private void command(boolean open) {
        be.setComputerControlled(true);
        be.setDemandOpen(open);
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        BlockState state = level.getBlockState(be.getBlockPos());
        if (state.hasProperty(MainSteamIsolationValveBlock.OPEN)
                && state.getValue(MainSteamIsolationValveBlock.OPEN) != open) {
            level.setBlock(be.getBlockPos(),
                    state.setValue(MainSteamIsolationValveBlock.OPEN, open),
                    Block.UPDATE_CLIENTS);
        }
    }

    // --- Measurements ---------------------------------------------------

    /** What the valve has been told to do. True is open. */
    @LuaFunction(mainThread = true)
    public final boolean isDemandOpen() {
        return be.isDemandOpen();
    }

    /**
     * How far open the valve actually is, 1.0 open to 0.0 shut. The number that
     * governs the steam line — the demand is only where it is heading.
     */
    @LuaFunction(mainThread = true)
    public final double getPosition() {
        return be.getPosition();
    }

    /** Full stroke time, seconds. A constant of the hardware. */
    @LuaFunction
    public final double getStrokeSeconds() {
        return MainSteamIsolationValveBlockEntity.STROKE_SECONDS;
    }

    @LuaFunction(mainThread = true)
    public final boolean isComputerControlled() {
        return be.isComputerControlled();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new HashMap<>();
        m.put("demandOpen", be.isDemandOpen());
        m.put("position", be.getPosition());
        m.put("strokeSeconds", MainSteamIsolationValveBlockEntity.STROKE_SECONDS);
        m.put("computerControlled", be.isComputerControlled());
        return m;
    }
}
