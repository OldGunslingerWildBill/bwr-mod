package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.fuel.FuelAssemblies;
import dev.bwr.mod.fuel.SpentFuelHeat;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CC:Tweaked peripheral for a spent fuel pool: the same numbers as the panel, per rack and per assembly, and the
 * cooling pump's switch. Nothing starts by itself; {@code setCooling(true)} is the player's program saying so.
 * Every method runs on the server thread ({@code mainThread = true}), as the suppression pool's do.
 *
 * <p>Times are seconds and are {@code -1} when the event never happens at the present heat and cooling.
 */
public class SpentFuelPoolPeripheral implements IPeripheral {

    private final SpentFuelPoolBlockEntity be;

    public SpentFuelPoolPeripheral(SpentFuelPoolBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "bwr_spent_fuel_pool";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof SpentFuelPoolPeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    private static double seconds(double s) {
        return Double.isInfinite(s) || Double.isNaN(s) ? -1.0 : s;
    }

    @LuaFunction(mainThread = true)
    public final boolean isFormed() {
        return be.isFormed();
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        var v = be.summary();
        m.put("formed", v.formed());
        if (!v.formed()) {
            m.put("problem", be.problem() == null ? "" : dev.bwr.mod.text.BwrText.english(be.problem()));
            return m;
        }
        m.put("waterKg", v.massKg());
        m.put("capacityKg", v.capacityKg());
        m.put("levelM", v.levelM());
        m.put("levelFraction", v.capacityKg() > 0 ? v.massKg() / v.capacityKg() : 0.0);
        m.put("temperatureC", v.temperatureC());
        m.put("waterAboveFuelM", v.waterAboveFuelM());
        m.put("highestFuelTopM", v.highestRackTopM());
        m.put("assemblies", v.assemblies());
        m.put("cells", v.cells());
        m.put("racks", v.rackCount());
        m.put("decayHeatKW", v.decayW() / 1000.0);
        m.put("coveredDecayHeatKW", v.coveredDecayW() / 1000.0);
        m.put("uncoveredDecayHeatKW", v.uncoveredDecayW() / 1000.0);
        m.put("evaporationKgPerS", v.evaporationKgPerS());
        m.put("boilOffKgPerS", v.boilKgPerS());
        m.put("passiveLossKW", v.passiveLossW() / 1000.0);
        m.put("coolingKW", v.coolingW() / 1000.0);
        m.put("secondsToBoil", seconds(v.secondsToBoil()));
        m.put("secondsToUncover", seconds(v.secondsToUncover()));
        m.put("boiling", v.temperatureC() >= 99.9);
        m.put("fuelUncovered", v.uncoveredDecayW() > 0.0);
        m.put("coolingEnabled", v.coolingEnabled());
        m.put("coolingRunning", v.coolingRunning());
        m.put("energyFE", v.energyFE());
        m.put("coolingColdWaterKg", v.coolerColdKg());
        m.put("coolingWarmWaterKg", v.coolerHotKg());
        m.put("coolingColdWaterC", v.coolerColdC());
        m.put("coolingWarmWaterC", v.coolerHotC());
        m.put("damagedAssemblies", v.damaged());
        m.put("burningAssemblies", v.burning());
        m.put("releaseHazard", v.hazard());
        return m;
    }

    /** Racks {@code first} to {@code first + count - 1} as 0-based indices into the pool's racks, clamped; all of them when no range is given. */
    private int[] range(Optional<Integer> first, Optional<Integer> count) {
        long n = be.rackCount();
        long from = Math.min(n, Math.max(0L, (long) first.orElse(1) - 1L));
        long to = count.isPresent() ? Math.min(n, from + Math.max(0L, (long) count.get())) : n;
        return new int[] {(int) from, (int) Math.max(from, to)};
    }

    /**
     * One entry per rack: position, height, assemblies, decay heat, hottest fuel, uncovered and failed counts. With no arguments
     * every rack (up to 2,048); {@code getRacks(first, count)} gives racks {@code first} (from 1) to {@code first + count - 1}.
     */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> getRacks(Optional<Integer> first, Optional<Integer> count) {
        List<Map<String, Object>> list = new ArrayList<>();
        int[] r0 = range(first, count);
        int index = r0[0] + 1;
        for (var r : be.rackViews(r0[0], r0[1] - r0[0])) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", index++);
            m.put("x", r.pos().getX());
            m.put("y", r.pos().getY());
            m.put("z", r.pos().getZ());
            m.put("topM", r.topM());
            m.put("assemblies", r.assemblies());
            m.put("decayHeatKW", r.decayW() / 1000.0);
            m.put("hottestFuelC", r.hottestFuelC());
            m.put("uncovered", r.uncovered());
            m.put("damaged", r.damaged());
            m.put("burning", r.burning());
            list.add(m);
        }
        return list;
    }

    /**
     * One entry per stored assembly: where it is, what it is and how hot it is. With no arguments every assembly (up to 8,192);
     * {@code getAssemblies(first, count)} gives those in racks {@code first} (from 1) to {@code first + count - 1}.
     */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> getAssemblies(Optional<Integer> first, Optional<Integer> count) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (be.getLevel() == null) {
            return list;
        }
        long now = be.getLevel().getGameTime();
        int[] r0 = range(first, count);
        for (int rackIndex = r0[0]; rackIndex < r0[1]; rackIndex++) {
            BlockPos pos = be.rackPos(rackIndex);
            for (int cell = 0; cell < 4; cell++) {
                ItemStack stack = be.stackAt(pos, cell);
                if (stack.isEmpty()) {
                    continue;
                }
                var data = FuelAssemblies.dataOf(stack);
                var slot = be.slotAt(pos, cell);
                SpentFuelHeat.Curve curve = be.cellCurve(pos, cell);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rack", rackIndex + 1);
                m.put("cell", cell + 1);
                m.put("fuel", data.fuelTypeName());
                m.put("burnupMwdPerTonne", data.burnupMwdPerTonne());
                m.put("decayHeatKW", curve.watts(now) / 1000.0);
                m.put("coolingSeconds", curve.coolingSeconds(now));
                m.put("dischargeRecorded", curve.stamped());
                m.put("secondsUntilReprocessable", seconds(curve.secondsUntilReprocessable(now)));
                m.put("fuelC", slot == null ? 0.0 : slot.fuelC);
                m.put("covered", slot == null || slot.covered);
                m.put("damaged", slot != null && slot.damaged);
                m.put("burning", slot != null && slot.ignited);
                list.add(m);
            }
        }
        return list;
    }

    /** Switch the cooling pump. It runs only while it has power and cold water; nothing switches it by itself. */
    @LuaFunction(mainThread = true)
    public final void setCooling(boolean enabled) {
        be.setCoolingEnabled(enabled);
    }

    @LuaFunction(mainThread = true)
    public final boolean getCooling() {
        return be.coolingEnabled();
    }
}
