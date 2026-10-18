package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.signal.PowerUnits;
import dev.bwr.mod.signal.Quantity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Part 6: FE power units with automatic conversion. One FE is 2.5 J (server setting {@code feToJoules}): 100 FE/t is
 * 100 x 20 x 2.5 = 5000 W = 5 kW. Picking a unit sets the scale of the binding and the Units text of the gauge;
 * stored energy never offers W.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class NetworkingPowerTests {
    private NetworkingPowerTests() {
    }

    private static final double FE = 2.5;

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0e-9 * Math.max(1.0, Math.abs(b));
    }

    @GameTest(template = "empty")
    public static void feConversionValues(GameTestHelper helper) {
        // 100 FE/t reads 5.0 kW and 0.005 MW.
        double kw = PowerUnits.scale("FE/t", "fe_per_tick_now", PowerUnits.KILOWATT, false, FE);
        double mw = PowerUnits.scale("FE/t", "fe_per_tick_now", PowerUnits.MEGAWATT, false, FE);
        double w = PowerUnits.scale("FE/t", "fe_per_tick_now", PowerUnits.WATT, false, FE);
        helper.assertTrue(near(100 * kw, 5.0), "100 FE/t is 5.0 kW: " + 100 * kw);
        helper.assertTrue(near(100 * mw, 0.005), "100 FE/t is 0.005 MW: " + 100 * mw);
        helper.assertTrue(near(100 * w, 5000.0), "100 FE/t is 5000 W: " + 100 * w);
        // 2000 FE/s reads 5.0 kW.
        helper.assertTrue(near(2000 * PowerUnits.scale("FE/s", "x", PowerUnits.KILOWATT, false, FE), 5.0), "2000 FE/s is 5.0 kW");
        // The FE to joule ratio is a setting: with 1 J per FE the same 100 FE/t is 2.0 kW.
        double one = PowerUnits.scale("FE/t", "x", PowerUnits.KILOWATT, false, 1.0);
        helper.assertTrue(near(100 * one, 2.0) && !near(100 * one, 5.0), "feToJoules = 1 changes the result: " + 100 * one);
        // Between watt units.
        helper.assertTrue(near(PowerUnits.scale("MW", "x", PowerUnits.KILOWATT, false, FE), 1000.0)
                && near(PowerUnits.scale("MW", "x", PowerUnits.WATT, false, FE), 1.0e6)
                && near(PowerUnits.scale("kW", "x", PowerUnits.MEGAWATT, false, FE), 0.001), "watt units convert among themselves");
        // Raw never rescales.
        helper.assertTrue(PowerUnits.scale("FE/t", "x", PowerUnits.RAW, false, FE) == 1.0, "Raw is 1");
        // Stored energy: kJ and MJ at 2.5 J per FE, never W.
        helper.assertTrue(near(1000 * PowerUnits.scale("FE", "energy_fe", PowerUnits.KILOJOULE, false, FE), 2.5), "1000 FE is 2.5 kJ");
        helper.assertTrue(near(1.0e6 * PowerUnits.scale("FE", "energy_fe", PowerUnits.MEGAJOULE, false, FE), 2.5), "1e6 FE is 2.5 MJ");
        helper.assertTrue(!PowerUnits.offers(Quantity.ENERGY, PowerUnits.WATT) && !PowerUnits.offers(Quantity.ENERGY, PowerUnits.KILOWATT)
                && !PowerUnits.offers(Quantity.ENERGY, PowerUnits.MEGAWATT), "the ENERGY quantity never offers W, kW or MW");
        helper.assertTrue(PowerUnits.offeredFor(Quantity.ENERGY).equals(java.util.List.of("Raw", "kJ", "MJ")), "energy offers Raw, kJ, MJ");
        helper.assertTrue(PowerUnits.offeredFor(Quantity.POWER).equals(java.util.List.of("Raw", "W", "kW", "MW")), "power offers Raw, W, kW, MW");
        helper.assertTrue(PowerUnits.offeredFor(Quantity.LEVEL).equals(java.util.List.of("Raw")), "a level has only Raw");
        // An FE rate with an unsaid time base gets the selector (per tick by default).
        helper.assertTrue(PowerUnits.ambiguousRate("FE", "drain_rate") && !PowerUnits.ambiguousRate("FE/t", "drain_rate")
                && !PowerUnits.ambiguousRate("FE", "energy_fe"), "only a plain FE on a rate key is ambiguous");
        helper.assertTrue(Quantity.of("drain_rate", "FE", dev.bwr.mod.signal.Kind.NUMBER) == Quantity.POWER
                && Quantity.of("energy_fe", "FE", dev.bwr.mod.signal.Kind.NUMBER) == Quantity.ENERGY, "an ambiguous FE rate is power");
        helper.assertTrue(near(100 * PowerUnits.scale("FE", "drain_rate", PowerUnits.KILOWATT, false, FE), 5.0)
                && near(2000 * PowerUnits.scale("FE", "drain_rate", PowerUnits.KILOWATT, true, FE), 5.0),
                "per tick and per second give 5.0 kW for 100 per tick and 2000 per second");
        helper.assertTrue(PowerUnits.unitsText(PowerUnits.RAW, "FE/t").equals("FE/t") && PowerUnits.unitsText("kW", "FE/t").equals("kW"),
                "the Units text follows the pick");
        helper.succeed();
    }

    private static CompoundTag config(String key, String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString(key, value);
        return tag;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void pickingAUnitSetsTheScaleAndTheUnitsText(GameTestHelper helper) {
        NetStage.run(helper, 11, s -> {
            s.wall(3, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelBlockEntity second = s.be(s.at(1, 0));
            PanelBlockEntity third = s.be(s.at(2, 0));
            PanelComponent power = s.gauge(block.getBlockPos(), 1, 1);
            PanelComponent energy = s.gauge(second.getBlockPos(), 1, 1);
            PanelComponent position = s.gauge(third.getBlockPos(), 1, 1);
            BlockPos meterPos = s.devices;
            s.meter(meterPos);
            s.msiv(s.devices.west(6));
            BlockPos valve = s.devices.west(6);

            // An old binding: the default transform saves no scale and no power units; the gauge saves none either.
            s.wrench(meterPos);
            s.wire(s.plantIn(block, power, 0, meterPos, "fe_per_tick_now", 1, 0));
            CompoundTag oldSource = power.save().getList("Inputs", 10).getCompound(0);
            s.check(!oldSource.contains("Scale") && !oldSource.contains("Offset"), "a default binding saves no transform: " + oldSource);
            s.check(!power.data().toTag().contains("PowerUnits") && !power.data().toTag().contains("PowerRate"),
                    "a gauge on Raw saves no power setting");
            s.check(PanelComponent.load(power.save()).save().equals(power.save()), "and round-trips unchanged");

            // FE/t to kW, MW, W and back; the Units text follows.
            s.configure(block, power, config("PowerUnits", "kW"));
            PlantSource kw = (PlantSource) power.source(0);
            s.check(near(kw.scale(), 0.05) && near(100 * kw.scale(), 5.0), "kW: 100 FE/t reads 5.0, scale " + kw.scale());
            s.check(((GaugeData) power.data()).units.equals("kW") && ((GaugeData) power.data()).power.units.equals("kW"),
                    "the Units text reads kW");
            s.configure(block, power, config("PowerUnits", "MW"));
            s.check(near(100 * ((PlantSource) power.source(0)).scale(), 0.005) && ((GaugeData) power.data()).units.equals("MW"),
                    "MW: 100 FE/t reads 0.005");
            s.configure(block, power, config("PowerUnits", "Raw"));
            s.check(((PlantSource) power.source(0)).scale() == 1.0 && ((GaugeData) power.data()).units.equals("FE/t"),
                    "Raw restores the scale and the unit text");
            s.check(!power.save().getList("Inputs", 10).getCompound(0).contains("Scale"), "and the binding saves no scale again");

            // The ratio is a server setting.
            double before = PanelConfig.FE_TO_JOULES.get();
            try {
                PanelConfig.FE_TO_JOULES.set(1.0);
                s.configure(block, power, config("PowerUnits", "kW"));
                s.check(near(100 * ((PlantSource) power.source(0)).scale(), 2.0), "with feToJoules = 1, 100 FE/t reads 2.0 kW");
            } finally {
                PanelConfig.FE_TO_JOULES.set(before);
            }
            s.configure(block, power, config("PowerUnits", "Raw"));

            // Fit range: Min and Max from the signal range, converted the same way (clamped to the gauge limit).
            s.configure(block, power, config("PowerUnits", "kW"));
            CompoundTag edit = new CompoundTag();
            edit.put("Config", new CompoundTag());
            edit.putBoolean("FitRange", true);
            s.check(dev.bwr.mod.panel.PanelOps.configure(s.player, block.getBlockPos(), power.id(), edit), "fit range accepted");
            GaugeData data = (GaugeData) power.data();
            s.check(data.min == 0.0 && data.max == GaugeData.LIMIT, "Min and Max came from the signal range: " + data.min + " to " + data.max);
            s.check(((PlantSource) power.source(0)).scale() > 0.0, "the units were not touched by fitting");

            // Stored energy: kJ and MJ, never W.
            s.wrench(valve);
            s.wire(s.plantIn(second, energy, 0, valve, "energy_fe", 1, 0));
            s.configure(second, energy, config("PowerUnits", "kW"));
            s.check(((PlantSource) energy.source(0)).scale() == 1.0 && ((GaugeData) energy.data()).power.units.equals("Raw"),
                    "kW is refused for stored energy; nothing changed");
            s.configure(second, energy, config("PowerUnits", "kJ"));
            s.check(near(1000 * ((PlantSource) energy.source(0)).scale(), 2.5) && ((GaugeData) energy.data()).units.equals("kJ"),
                    "kJ is accepted: 1000 FE is 2.5 kJ");

            // A unit picked before wiring applies to the binding made afterwards (default transform only).
            s.configure(third, position, config("PowerUnits", "MW"));
            s.wrench(meterPos);
            s.wire(s.plantIn(third, position, 0, meterPos, "fe_per_tick_now", 1, 0));
            s.check(near(100 * ((PlantSource) position.source(0)).scale(), 0.005), "a later bind follows the picked unit");
            s.wire(s.plantIn(third, position, 0, meterPos, "fe_per_tick_now", 3, 0));
            s.check(((PlantSource) position.source(0)).scale() == 3.0, "a custom scale is never overridden");
            s.level.removeBlock(meterPos, false);
            s.removeMsiv(valve);
        });
    }
}
