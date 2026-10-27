package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.spentfuel.NewFuelElevatorBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CC:Tweaked peripheral {@code bwr_new_fuel_elevator}: what the elevator is doing and the one thing a program can ask of it besides loading
 * (which is items: a hopper, a pipe or the inventory peripheral's {@code pushItems} into the hatch). Lowering starts by itself for what was
 * put in the hatch; raising an assembly out of the pool is {@code raise()}, the program's own choice. Every method runs on the server thread.
 */
public class NewFuelElevatorPeripheral implements IPeripheral {
    private final NewFuelElevatorBlockEntity be;

    public NewFuelElevatorPeripheral(NewFuelElevatorBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "bwr_new_fuel_elevator";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof NewFuelElevatorPeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    private static Map<String, Object> result(@Nullable NewFuelElevatorBlockEntity.Refusal why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", why == null);
        m.put("reason", why == null ? "" : why.why().name().toLowerCase(java.util.Locale.ROOT));
        m.put("message", why == null ? "" : why.english());
        return m;
    }

    /** Whether the elevator stands on the rim of a formed spent fuel pool. */
    @LuaFunction(mainThread = true)
    public final boolean isLinked() {
        return be.link() != null;
    }

    /**
     * {@code linked}, {@code problem}, {@code state} ("idle", "waiting", "lowering", "raising", "unlinked"), {@code leg}, {@code progress}, {@code secondsLeft},
     * {@code queued}, {@code landing}, {@code landingDecayHeatKW}, {@code inFlightDecayHeatKW}, {@code freeCells}, {@code cells},
     * {@code waterOverTransferM}, {@code minCoverM}, {@code poolLevelM}, {@code coolestDecayHeatKW} (-1 when the pool holds none), {@code lastRefusal}.
     */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        var s = be.status();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("linked", s.linked());
        m.put("problem", s.problem());
        m.put("state", s.state());
        m.put("leg", s.leg());
        m.put("progress", s.progress());
        m.put("secondsLeft", s.secondsLeft());
        m.put("queued", s.queued());
        m.put("landing", s.landing());
        m.put("landingDecayHeatKW", s.landingDecayKw());
        m.put("inFlightDecayHeatKW", s.inFlightDecayKw());
        m.put("freeCells", s.freeCells());
        m.put("cells", s.cells());
        m.put("waterOverTransferM", s.waterOverTransferM());
        m.put("minCoverM", s.minCoverM());
        m.put("poolLevelM", s.levelM());
        m.put("coolestDecayHeatKW", s.coolestKw());
        m.put("lastRefusal", s.lastRefusal());
        return m;
    }

    /** Raises the assembly that has cooled longest to the hatch. Returns {@code {ok, reason, message}}. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> raise() {
        return result(be.raise(null));
    }

    /** Raises the assembly in cell {@code cell} (rack * 4 + cell, counting from 0, racks in the order {@code bwr_spent_fuel_pool.getRacks()} lists them). */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> raiseCell(int cell) {
        var link = be.link();
        if (link == null) {
            return result(be.raise(null));
        }
        var pool = link.pool();
        int rack = cell / 4;
        if (cell < 0 || rack >= pool.rackCount()) {
            return result(be.raise(new SpentFuelPoolBlockEntity.CellRef(pool.getBlockPos(), 0)));
        }
        return result(be.raise(new SpentFuelPoolBlockEntity.CellRef(pool.rackPos(rack), cell % 4)));
    }

    /** Tries again to lower an assembly that was blocked in the hatch. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> retry() {
        return result(be.retry());
    }
}
