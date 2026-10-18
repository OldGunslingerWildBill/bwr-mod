package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.BarGraphData;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.SegmentDisplayData;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.MeasuresCheck;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.panel.wiring.WiringOps.Result;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.signal.Kind;
import dev.bwr.mod.signal.Quantity;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.signal.SignalSpec;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Part 5: gauge compatibility. A gauge that {@code Measures} one quantity takes only signals of that quantity, however
 * it is wired; ANY (the default) behaves exactly as before; an old save that disagrees keeps working and says so.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class NetworkingMeasuresTests {
    private NetworkingMeasuresTests() {
    }

    private static CompoundTag measures(String quantity) {
        CompoundTag config = new CompoundTag();
        config.putString("Measures", quantity);
        return config;
    }

    private static Quantity q(String key, String unit, Kind kind) {
        return Quantity.of(key, unit, kind);
    }

    @GameTest(template = "empty")
    public static void quantityTableAndTheMeasuresDefault(GameTestHelper helper) {
        helper.assertTrue(q("level", "in", Kind.NUMBER) == Quantity.LEVEL && q("x", "m", Kind.NUMBER) == Quantity.LEVEL
                && q("x", "mB", Kind.NUMBER) == Quantity.LEVEL, "in, m and mB are LEVEL");
        helper.assertTrue(q("level", "fraction", Kind.NUMBER) == Quantity.LEVEL && q("level", "%", Kind.NUMBER) == Quantity.LEVEL
                && q("speed", "fraction", Kind.NUMBER) == Quantity.PERCENT && q("x", "% rated", Kind.NUMBER) == Quantity.PERCENT
                && q("x", "fraction of rated", Kind.NUMBER) == Quantity.PERCENT, "fraction and % are LEVEL only when the key says level");
        helper.assertTrue(q("p", "psig", Kind.NUMBER) == Quantity.PRESSURE && q("p", "inHg", Kind.NUMBER) == Quantity.PRESSURE
                && q("t", "degC", Kind.NUMBER) == Quantity.TEMPERATURE && q("t", "degC/h", Kind.NUMBER) == Quantity.RATE
                && q("f", "kg/s", Kind.NUMBER) == Quantity.FLOW && q("f", "gpm", Kind.NUMBER) == Quantity.FLOW, "pressure, temperature, rate, flow");
        helper.assertTrue(q("w", "MW", Kind.NUMBER) == Quantity.POWER && q("w", "kW", Kind.NUMBER) == Quantity.POWER
                && q("w", "FE/t", Kind.NUMBER) == Quantity.POWER && q("w", "FE/s", Kind.NUMBER) == Quantity.POWER
                && q("e", "FE", Kind.NUMBER) == Quantity.ENERGY && q("e", "MWh", Kind.NUMBER) == Quantity.ENERGY
                && q("e", "MJ", Kind.NUMBER) == Quantity.ENERGY, "power and energy");
        helper.assertTrue(q("pos", "fraction open", Kind.NUMBER) == Quantity.POSITION && q("r", "notch", Kind.LEVEL) == Quantity.POSITION
                && q("s", "rpm", Kind.NUMBER) == Quantity.SPEED && q("c", "ppm", Kind.NUMBER) == Quantity.CHEMISTRY
                && q("c", "ppm/min", Kind.NUMBER) == Quantity.CHEMISTRY && q("n", "rods", Kind.NUMBER) == Quantity.COUNT
                && q("b", "", Kind.BOOLEAN) == Quantity.BOOLEAN && q("t", "", Kind.TEXT) == Quantity.TEXT,
                "position, speed, chemistry, count, boolean and text");

        // The unit fix: the FE per tick signals of a generator say FE/t.
        BlockEntity generator = BwrBlocks.NUCLEAR_GENERATOR.get().newBlockEntity(BlockPos.ZERO,
                BwrBlocks.NUCLEAR_GENERATOR.get().defaultBlockState());
        SignalSpec generated = SignalProviders.of(generator).signals().stream().filter(sp -> sp.key().equals("generated_fe_tick"))
                .findFirst().orElseThrow();
        helper.assertTrue(generated.unit().equals("FE/t") && Quantity.of(generated) == Quantity.POWER,
                "generated_fe_tick is a per-tick rate: " + generated.unit());

        // ANY is the default of every gauge-like type, and a default saves nothing.
        for (var type : new dev.bwr.mod.panel.component.ComponentType[] {ComponentTypes.DIAL_GAUGE,
                ComponentTypes.EDGEWISE_GAUGE_VERTICAL, ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, ComponentTypes.BAR_GRAPH,
                ComponentTypes.SEVEN_SEGMENT_DISPLAY}) {
            var data = type.createData();
            helper.assertTrue(data.measures(0) == null, type.id() + " measures ANY by default");
            CompoundTag tag = data.toTag();
            helper.assertTrue(!tag.contains("Measures"), type.id() + " saves nothing for ANY: " + tag);
            helper.assertTrue(type.configSchema().stream().anyMatch(f -> f.key().equals("Measures")),
                    type.id() + " offers the Measures setting");
            tag.putString("Measures", "LEVEL");
            data.load(tag);
            helper.assertTrue(data.measures(0) == Quantity.LEVEL && data.toTag().getString("Measures").equals("LEVEL"),
                    type.id() + " saves and loads LEVEL");
            tag.putString("Measures", "ANY");
            data.load(tag);
            helper.assertTrue(data.measures(0) == null && !data.toTag().contains("Measures"), type.id() + " ANY saves nothing");
            tag.putString("Measures", "TEXT");
            data.load(tag);
            helper.assertTrue(data.measures(0) == null, type.id() + " an unusable value reads as ANY");
        }
        helper.assertTrue(new GaugeData("dial_gauge").measures == null && new BarGraphData().measures == null
                && new SegmentDisplayData(false).measures == null, "the field defaults are ANY");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aLevelGaugeTakesLevelAndRefusesPowerThroughTheRealBindPayload(GameTestHelper helper) {
        NetStage.run(helper, 7, s -> {
            s.wall(3, 1);
            PanelBlockEntity blockA = s.be(s.at(0, 0));
            PanelBlockEntity blockB = s.be(s.at(1, 0));
            PanelComponent levelGauge = s.gauge(blockA.getBlockPos(), 1, 1);
            PanelComponent anyGauge = s.gauge(blockB.getBlockPos(), 1, 1);
            BlockEntity pool = s.pool(s.devices);
            BlockPos meterPos = s.devices.west(4);
            BlockEntity meter = s.meter(meterPos);
            s.configure(blockA, levelGauge, measures("LEVEL"));
            s.check(levelGauge.data().measures(0) == Quantity.LEVEL, "fixture: the gauge was set to measure LEVEL");

            // The binding screen lists each signal with its quantity.
            s.wrench(s.devices);
            var screen = WiringOps.screenFor(s.player, blockA.getBlockPos(), levelGauge.id());
            s.check(screen != null && screen.signals().stream().anyMatch(e -> e.key().equals("level")
                    && e.quantity() == Quantity.LEVEL.ordinal()), "the screen carries the quantity of each signal: " + screen);

            // A LEVEL gauge bound to a level signal is accepted.
            s.wire(s.plantIn(blockA, levelGauge, 0, s.devices, "level", 100, 0));
            s.check(levelGauge.source(0) instanceof PlantSource a && a.key().equals("level"), "the level signal is accepted");

            // The same gauge bound to a power signal (MW) is refused, with the reason.
            s.wrench(meterPos);
            PanelPayloads.Bind power = s.plantIn(blockA, levelGauge, 0, meterPos, "mw_now", 1, 0);
            s.wire(power);
            s.check(levelGauge.source(0) instanceof PlantSource a && a.key().equals("level") && a.device().equals(s.devices),
                    "the power signal was not bound; the level binding stands: " + levelGauge.source(0));
            var outcome = s.outcome(power);
            s.check(outcome.result() == Result.MEASURES_MISMATCH, "the refusal is a measures mismatch: " + outcome.result());
            String reason = BwrText.english(outcome.detail());
            s.check(reason.equals("This gauge measures LEVEL; mw now is POWER"), "the reason is given: " + reason);
            // The server's own check covers every kind of power signal.
            s.check(s.outcome(s.plantIn(blockA, levelGauge, 0, meterPos, "fe_per_tick_now", 1, 0)).result()
                    == Result.MEASURES_MISMATCH, "an FE/t signal is power too");

            // An ANY gauge accepts both.
            s.wrench(s.devices);
            s.wire(s.plantIn(blockB, anyGauge, 0, s.devices, "level", 100, 0));
            s.check(anyGauge.source(0) instanceof PlantSource a && a.key().equals("level"), "an ANY gauge accepts the level signal");
            s.wrench(meterPos);
            s.wire(s.plantIn(blockB, anyGauge, 0, meterPos, "mw_now", 1, 0));
            s.check(anyGauge.source(0) instanceof PlantSource a && a.key().equals("mw_now"), "and the power signal");

            // Setting the gauge back to ANY lifts the restriction.
            s.configure(blockA, levelGauge, measures("ANY"));
            s.wrench(meterPos);
            s.wire(s.plantIn(blockA, levelGauge, 0, meterPos, "mw_now", 1, 0));
            s.check(levelGauge.source(0) instanceof PlantSource a && a.key().equals("mw_now"), "ANY takes the power signal again");
            s.check(pool != null && meter != null, "fixture devices exist");
            s.level.removeBlock(s.devices, false);
            s.level.removeBlock(meterPos, false);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void anOldMismatchedSaveKeepsWorkingAndWarns(GameTestHelper helper) {
        NetStage.run(helper, 8, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent gauge = s.gauge(block.getBlockPos(), 1, 1);
            BlockPos meterPos = s.devices;
            s.meter(meterPos);

            // Before the setting existed the gauge was wired to a power signal.
            s.wrench(meterPos);
            s.wire(s.plantIn(block, gauge, 0, meterPos, "mw_now", 1, 0));
            s.check(gauge.source(0) instanceof PlantSource, "fixture: wired while the gauge measured anything");
            CompoundTag before = gauge.save();
            s.check(MeasuresCheck.mismatch(s.level, gauge, 0) == -1, "no warning while it measures ANY");

            // The player (or an update) now sets it to LEVEL: the binding stays, and the warning appears.
            s.configure(block, gauge, measures("LEVEL"));
            s.check(gauge.source(0) instanceof PlantSource p && p.key().equals("mw_now"), "nothing unbinds on its own");
            s.check(MeasuresCheck.mismatch(s.level, gauge, 0) == Quantity.POWER.ordinal(), "the mismatch is reported: POWER");
            CompoundTag network = block.savePanel(true);
            PanelComponent client = PanelComponent.load(network.getList("Components", 10).getCompound(0));
            s.check(client.mismatch(0) == Quantity.POWER.ordinal(), "the client learns it with the full state");
            s.check(!block.savePanel(false).toString().contains("Mismatch"), "the warning is never saved to disk");
            // It loads and re-saves unchanged, and still samples.
            s.check(PanelComponent.load(gauge.save()).save().equals(gauge.save()), "the mismatched component round-trips");
            block.resample(gauge, true);
            s.check(!Double.isNaN(gauge.input(0)), "and it still reads its signal: " + gauge.input(0));
            s.check(!before.equals(gauge.save()), "fixture: the config did change the save");
            s.level.removeBlock(meterPos, false);
        });
    }
}
