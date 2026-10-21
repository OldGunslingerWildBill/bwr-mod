package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.client.ComponentModels;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PortSpec;
import dev.bwr.mod.panel.component.SyncMath;
import dev.bwr.mod.panel.component.SynchroscopeData;
import dev.bwr.mod.panel.component.SynchroscopeType;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The synchroscope ({@code bwr:synchroscope}): its two inputs, the arithmetic of the pointer and the two lamps
 * ({@link SyncMath}), the config round trip, what is saved, and that its printed text scales with it.
 *
 * <p>World fixture at 181_400 / 181_000 (see {@link HardwareStage}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class SynchroscopeTests {
    private SynchroscopeTests() {
    }

    static final int ORIGIN_X = 181_400;
    static final int ORIGIN_Z = 181_000;

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0e-9;
    }

    @GameTest(template = "empty")
    public static void synchroscopeIsRegisteredWithPhaseAndSlipInputsAndNoOutputs(GameTestHelper helper) {
        ComponentType type = ComponentTypes.SYNCHROSCOPE;
        helper.assertTrue(ComponentTypes.get(type.id()) == type && type.id().getPath().equals("synchroscope"), "registered as synchroscope");
        helper.assertTrue(BwrItems.SYNCHROSCOPE.get() instanceof ComponentItem item && item.type() == type, "its item places it");
        List<String> in = type.inputPorts(type.createData()).stream().map(PortSpec::key).toList();
        helper.assertTrue(in.equals(List.of("phase", "slip")) && type.outputPorts(type.createData()).isEmpty(), "inputs phase and slip, no outputs: " + in);
        helper.assertTrue(!type.isInteractive(), "an indicator: nothing to operate");
        List<Footprint> sizes = type.footprints();
        helper.assertTrue(type.realSizes() == 4 && sizes.get(type.realDefaultIndex()).equals(Footprint.of(6, 8))
                && sizes.contains(Footprint.of(3, 4)) && sizes.contains(Footprint.of(2, 3)),
                "a new item starts at twice the real size, 6 x 8, with the exact 3 x 4 and 2 x 3 listed: " + sizes);
        for (Footprint size : sizes) {
            helper.assertTrue(size.h() > size.w(), "a lamp strip fits under the dial at " + size);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pointerArithmeticWrapsAdvancesFollowsAndLightsTheLamps(GameTestHelper helper) {
        helper.assertTrue(near(SyncMath.wrap(190.0), -170.0) && near(SyncMath.wrap(-190.0), 170.0) && near(SyncMath.wrap(180.0), 180.0)
                && near(SyncMath.wrap(-180.0), 180.0) && near(SyncMath.wrap(720.0 + 15.0), 15.0), "wrap to -180 (exclusive) .. 180");
        helper.assertTrue(near(SyncMath.advance(0.0, 1.0, 0.25), 90.0), "1 Hz for a quarter second is a quarter turn clockwise");
        helper.assertTrue(near(SyncMath.advance(0.0, -0.5, 0.5), -90.0), "a slow generator turns the pointer back");
        helper.assertTrue(near(SyncMath.advance(170.0, 1.0, 0.1), SyncMath.wrap(206.0)), "and through 180");
        // Following a phase: the short way round, never the long one.
        double angle = SyncMath.follow(170.0, -170.0, 1000.0, 0.12);
        helper.assertTrue(Math.abs(SyncMath.wrap(angle - (-170.0))) < 1.0e-3, "converges to the target: " + angle);
        double step = SyncMath.follow(170.0, -170.0, 0.12, 0.12);
        helper.assertTrue(step > 170.0 || step < -170.0, "from 170 towards -170 it goes up through 180, not down through 0: " + step);
        helper.assertTrue(near(SyncMath.follow(10.0, 40.0, 0.5, 0.0), 40.0), "no lag snaps to the target");
        // The lamps: dark lamp dark in phase and full at 180, the bright lamp the other way round.
        helper.assertTrue(near(SyncMath.darkLamp(0.0), 0.0) && near(SyncMath.darkLamp(180.0), 1.0) && near(SyncMath.darkLamp(90.0), 0.5)
                && near(SyncMath.darkLamp(-90.0), 0.5), "dark lamp: 0, 0.5 at +-90, 1 at 180");
        for (double phase = -180.0; phase <= 180.0; phase += 15.0) {
            helper.assertTrue(near(SyncMath.darkLamp(phase) + SyncMath.brightLamp(phase), 1.0), "the lamps are complementary at " + phase);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void inputsAreBindableConfigRoundTripsAndNothingIsLatched(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "sync-config", s -> {
            PanelComponent scope = s.place(ComponentTypes.SYNCHROSCOPE, 0, 0, Footprint.of(3, 4));
            PanelBlockEntity panel = s.be();
            helper.assertTrue(Double.isNaN(scope.input(0)) && Double.isNaN(scope.input(1)), "no signal at the start");
            scope.setSource(SynchroscopeType.IN_PHASE, InputSources.constant(-35.0));
            scope.setSource(SynchroscopeType.IN_SLIP, InputSources.constant(0.25));
            panel.resample(scope, true);
            helper.assertTrue(scope.input(0) == -35.0 && scope.input(1) == 0.25, "both inputs follow their sources");
            panel.setInput(scope, SynchroscopeType.IN_PHASE, 120.0);
            helper.assertTrue(scope.input(0) == 120.0, "and take a pushed value");
            SynchroscopeData data = (SynchroscopeData) scope.data();
            helper.assertTrue(!data.toTag().contains("Reverse"), "a default instrument saves no Reverse key");
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putBoolean("Reverse", true);
            config.putInt("Lamp1Color", 0x30D060);
            config.putInt("Lamp2Color", 0x1FFFFFFF);
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, scope.id(), edit), "the wrench edit was applied");
            helper.assertTrue(data.reverse && data.lamp1Color == 0x30D060 && data.lamp2Color == 0xFFFFFF, "config applied, colour masked: " + data.toTag());
            CompoundTag saved = scope.save();
            PanelComponent reloaded = PanelComponent.load(saved);
            helper.assertTrue(reloaded.type() == ComponentTypes.SYNCHROSCOPE && reloaded.save().equals(saved)
                    && ((SynchroscopeData) reloaded.data()).reverse && reloaded.w() == 3 && reloaded.h() == 4, "reloads and re-saves unchanged");
            SynchroscopeData old = new SynchroscopeData();
            old.load(new CompoundTag());
            helper.assertTrue(!old.reverse && old.lamp1Color == SynchroscopeData.DEFAULT_LAMP_1, "an empty tag loads as the defaults");
        });
    }

    @GameTest(template = "empty")
    public static void printedTextScalesWithTheDialAndStaysOnIt(GameTestHelper helper) {
        PanelComponent small = new PanelComponent(1, ComponentTypes.SYNCHROSCOPE, 0, 0, Footprint.of(3, 4), "SY-1");
        PanelComponent big = new PanelComponent(2, ComponentTypes.SYNCHROSCOPE, 0, 0, Footprint.of(12, 16), "SY-2");
        var model = ComponentModels.get(ComponentTypes.SYNCHROSCOPE.id());
        List<Legends.Item> a = model.textLayout(small, 1.0f);
        List<Legends.Item> b = model.textLayout(big, 1.0f);
        helper.assertTrue(a.size() == 3 && b.size() == 3, "three legends: SYNC, SLOW, FAST");
        for (int i = 0; i < a.size(); i++) {
            float ratio = b.get(i).capCells() / a.get(i).capCells();
            helper.assertTrue(Math.abs(ratio - 4.0f) < 0.2f, "\"" + a.get(i).text() + "\" is four times as big on the four times bigger dial: " + ratio);
            Legends.Item x = a.get(i);
            helper.assertTrue(x.x() > 0 && x.x() < small.w() && x.y() - x.capCells() / 2 >= 0 && x.y() + x.capCells() / 2 <= small.w(),
                    "\"" + x.text() + "\" lies on the dial of the small instrument: " + x);
        }
        helper.succeed();
    }
}
