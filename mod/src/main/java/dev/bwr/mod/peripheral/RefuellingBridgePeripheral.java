package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.mod.refuelling.BridgeSite;
import dev.bwr.mod.refuelling.CanalGateBlock;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Loc;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity.Move;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CC:Tweaked peripheral for the refuelling bridge console: queue a move, read what the bridge is doing, stop it, resume
 * it, open and close the chute's gates, set the hoist-limit override. Nothing moves unless the program says so
 * ({@code queueMove} and friends); a halt waits for {@code resume()} or {@code abort()}. Every method runs on the
 * server thread.
 *
 * <p>Core positions are indices into the same list the refuelling map uses (0 is the middle of the core, counting
 * outward); rack cells are numbered {@code rack * 4 + cell} over the pool's racks as {@code getRacks()} lists them,
 * counting from 0. {@code queueMove} returns {@code ok} and, when it did not queue, the reason.
 */
public class RefuellingBridgePeripheral implements IPeripheral {

    private final RefuellingBridgeBlockEntity be;

    public RefuellingBridgePeripheral(RefuellingBridgeBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "bwr_refuelling_bridge";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof RefuellingBridgePeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    private static Map<String, Object> result(boolean ok, @Nullable Component why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        if (why != null) {
            m.put("message", BwrText.english(why));
        }
        return m;
    }

    private static Map<String, Object> point(@Nullable BridgeMotion.Point p) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (p != null) {
            m.put("x", p.x());
            m.put("y", p.y());
            m.put("z", p.z());
        }
        return m;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        BridgeSite site = be.refreshSite();
        m.put("ready", site != null && site.ok());
        if (site != null && !site.ok()) {
            m.put("problem", BwrText.english(site.problem));
        }
        m.put("phase", be.phase().name().toLowerCase(Locale.ROOT));
        m.put("busy", be.busy());
        m.put("halted", be.phase() == BridgeMotion.Phase.HALTED);
        m.put("progress", be.progress());
        m.put("secondsLeftInPhase", be.phaseTicksLeft() / 20.0);
        m.put("queued", be.queued());
        m.put("override", be.override());
        m.put("holding", !be.carried().isEmpty());
        m.put("carried", be.carriedSummary());
        m.put("carriedCoverM", be.carriedCoverM());
        m.put("grapple", point(be.tip()));
        if (be.haltMessage() != null) {
            m.put("haltReason", BwrText.english(be.haltMessage()));
        }
        m.put("chute", be.chute().state().name().toLowerCase(Locale.ROOT));
        if (site != null && site.ok()) {
            ReactorControllerBlockEntity r = site.reactor(be.getLevel());
            SpentFuelPoolBlockEntity p = site.pool(be.getLevel());
            m.put("reactorFound", r != null);
            m.put("poolFound", p != null);
            if (r != null) {
                m.put("cavityWaterM", BridgeSite.cavityWaterM(r));
                m.put("coreSlots", r.corePositions().length);
                m.put("coreReady", r.refuellingBlockedMessage() == null);
            }
            if (p != null) {
                m.put("poolWaterOverRacksM", BridgeSite.poolSurfaceY(p) - BridgeSite.rackSeatY(p.layout().racks().isEmpty()
                        ? new BlockPos(0, p.layout().min().getY() + 1, 0) : p.layout().racks().get(0)));
                m.put("rackCells", p.layout().racks().size() * 4);
            }
        }
        Move current = be.currentMove();
        if (current != null) {
            m.put("move", describe(current));
        }
        return m;
    }

    private static Map<String, Object> describe(Move move) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", move.from().describe());
        m.put("to", move.to().describe());
        return m;
    }

    /** One table per rack of the pool: its cells (occupied, decay heat) in the order the cell numbers count. */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> getRacks() {
        List<Map<String, Object>> racks = new ArrayList<>();
        BridgeSite site = be.refreshSite();
        SpentFuelPoolBlockEntity pool = site != null && site.ok() ? site.pool(be.getLevel()) : null;
        if (pool == null) {
            return racks;
        }
        long now = be.getLevel().getGameTime();
        int index = 0;
        for (BlockPos rack : pool.layout().racks()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", index);
            m.put("x", rack.getX());
            m.put("y", rack.getY());
            m.put("z", rack.getZ());
            List<Map<String, Object>> cells = new ArrayList<>();
            for (int k = 0; k < 4; k++) {
                Map<String, Object> cell = new LinkedHashMap<>();
                var stack = pool.stackAt(rack, k);
                cell.put("cell", index * 4 + k);
                cell.put("occupied", !stack.isEmpty());
                if (!stack.isEmpty()) {
                    cell.put("decayHeatKW", dev.bwr.mod.fuel.SpentFuelHeat.decayWatts(stack, now) / 1000.0);
                }
                cells.add(cell);
            }
            m.put("cells", cells);
            racks.add(m);
            index++;
        }
        return racks;
    }

    @LuaFunction(mainThread = true)
    public final boolean isCoreOccupied(int coreIndex) throws LuaException {
        BridgeSite site = be.refreshSite();
        ReactorControllerBlockEntity r = site != null && site.ok() ? site.reactor(be.getLevel()) : null;
        if (r == null) {
            throw new LuaException("no reactor under the bridge");
        }
        int[] positions = r.corePositions();
        if (coreIndex < 0 || coreIndex >= positions.length) {
            throw new LuaException("core index out of range 0.." + (positions.length - 1));
        }
        return r.core().getCoreLoading().isOccupied(positions[coreIndex]);
    }

    private Move move(boolean toPool, int coreIndex, int cellIndex) throws LuaException {
        BridgeSite site = be.refreshSite();
        ReactorControllerBlockEntity r = site != null && site.ok() ? site.reactor(be.getLevel()) : null;
        SpentFuelPoolBlockEntity p = site != null && site.ok() ? site.pool(be.getLevel()) : null;
        if (r == null || p == null) {
            throw new LuaException("the bridge needs a reactor and a pool between its rails");
        }
        int[] positions = r.corePositions();
        var racks = p.layout().racks();
        if (coreIndex < 0 || coreIndex >= positions.length) {
            throw new LuaException("core index out of range 0.." + (positions.length - 1));
        }
        if (cellIndex < 0 || cellIndex / 4 >= racks.size()) {
            throw new LuaException("cell index out of range 0.." + (racks.size() * 4 - 1));
        }
        Loc core = Loc.core(positions[coreIndex]);
        Loc cell = Loc.rack(racks.get(cellIndex / 4), cellIndex % 4);
        return toPool ? new Move(core, cell) : new Move(cell, core);
    }

    /** Queue the assembly at core index {@code coreIndex} for the rack cell {@code cellIndex}. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> queueCoreToPool(int coreIndex, int cellIndex) throws LuaException {
        Component why = be.queue(move(true, coreIndex, cellIndex));
        return result(why == null, why);
    }

    /** Queue the assembly in rack cell {@code cellIndex} for core index {@code coreIndex}. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> queuePoolToCore(int cellIndex, int coreIndex) throws LuaException {
        Component why = be.queue(move(false, coreIndex, cellIndex));
        return result(why == null, why);
    }

    /** Queue setting down the assembly on the grapple (after a halt) in rack cell {@code cellIndex}. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> queueSetDown(int cellIndex) throws LuaException {
        BridgeSite site = be.refreshSite();
        SpentFuelPoolBlockEntity p = site != null && site.ok() ? site.pool(be.getLevel()) : null;
        if (p == null) {
            throw new LuaException("the bridge needs a pool between its rails");
        }
        var racks = p.layout().racks();
        if (cellIndex < 0 || cellIndex / 4 >= racks.size()) {
            throw new LuaException("cell index out of range 0.." + (racks.size() * 4 - 1));
        }
        Component why = be.queue(new Move(Loc.HELD, Loc.rack(racks.get(cellIndex / 4), cellIndex % 4)));
        return result(why == null, why);
    }

    @LuaFunction(mainThread = true)
    public final void abort() {
        be.abort();
    }

    @LuaFunction(mainThread = true)
    public final void resume() {
        be.resume();
    }

    @LuaFunction(mainThread = true)
    public final void setOverride(boolean on) {
        be.setOverride(on);
    }

    @LuaFunction(mainThread = true)
    public final boolean getOverride() {
        return be.override();
    }

    /** Opens or closes every gate of the cattle chute (the program's choice; nothing else moves a gate). Returns how many gates it found. */
    @LuaFunction(mainThread = true)
    public final int setGate(boolean open) {
        BridgeSite site = be.refreshSite();
        if (site == null || !site.ok()) {
            return 0;
        }
        int n = 0;
        for (BlockPos gate : be.chute().gates()) {
            var state = be.getLevel().getBlockState(gate);
            if (state.getBlock() instanceof CanalGateBlock) {
                be.getLevel().setBlock(gate, state.setValue(CanalGateBlock.OPEN, open), 3);
                n++;
            }
        }
        be.refreshSite();
        return n;
    }

    /** "absent", "open", "closed" (a gate is shut) or "drained". */
    @LuaFunction(mainThread = true)
    public final String getChute() {
        be.refreshSite();
        return be.chute().state().name().toLowerCase(Locale.ROOT);
    }
}
