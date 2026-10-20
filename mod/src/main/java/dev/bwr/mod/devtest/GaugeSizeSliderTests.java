package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.client.ConfigModel;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ConfigField;
import dev.bwr.mod.panel.component.ConfigSchema;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.GaugeScale;
import dev.bwr.mod.panel.model.Legends;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The wrench's Text size and Tick size sliders on the gauges ({@link GaugeScale}): saved per component only when not
 * 100 %, so old saves load and save unchanged; out of range values clamped by the server; and the renderers honour them
 * (text and ticks scale by the factor, text only as far as it fits).
 *
 * <p>The world fixture is at 97_500 / 97_500 (grep before reusing); the rest is pure.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class GaugeSizeSliderTests {
    private GaugeSizeSliderTests() {
    }

    static final int ORIGIN_X = 97_500;
    static final int ORIGIN_Z = 97_500;
    private static final String[] GAUGES = {"dial_gauge", "edgewise_gauge_vertical", "edgewise_gauge_horizontal", "bar_gauge"};

    private static ComponentType type(String path) {
        return ComponentTypes.get(dev.bwr.mod.BwrMod.id(path));
    }

    private static ConfigField field(ComponentType type, String key) {
        for (ConfigField field : type.configSchema()) {
            if (field.key().equals(key)) {
                return field;
            }
        }
        return null;
    }

    @GameTest(template = "empty")
    public static void sliderSettingsSaveOnlyWhenChangedAndRoundTrip(GameTestHelper helper) {
        for (String path : GAUGES) {
            ComponentType type = type(path);
            for (String key : new String[] {GaugeScale.TEXT_SIZE, GaugeScale.TICK_SIZE}) {
                ConfigField field = field(type, key);
                helper.assertTrue(field != null && field.kind() == ConfigField.Kind.SLIDER && field.min() == 50 && field.max() == 200
                        && field.defaultValue() == 100 && field.step() == GaugeScale.STEP_PERCENT, path + " has a 50..200 % slider " + key);
            }
            GaugeData fresh = (GaugeData) type.createData();
            CompoundTag tag = fresh.toTag();
            helper.assertTrue(fresh.textSize == 100 && fresh.tickSize == 100 && !tag.contains(GaugeScale.TEXT_SIZE)
                    && !tag.contains(GaugeScale.TICK_SIZE), path + ": 100 % is not saved: " + tag);
            // A save from before the sliders (no keys) loads at 100 % and saves the same keys again.
            GaugeData old = (GaugeData) type.createData();
            old.textSize = 170;
            old.load(tag);
            helper.assertTrue(old.textSize == 100 && old.tickSize == 100 && old.toTag().equals(tag), path + ": an old save loads unchanged");
            fresh.textSize = 160;
            fresh.tickSize = 60;
            CompoundTag changed = fresh.toTag();
            helper.assertTrue(changed.getInt(GaugeScale.TEXT_SIZE) == 160 && changed.getInt(GaugeScale.TICK_SIZE) == 60,
                    path + ": a changed size is saved: " + changed);
            GaugeData loaded = (GaugeData) type.createData();
            loaded.load(changed);
            helper.assertTrue(loaded.textSize == 160 && loaded.tickSize == 60 && loaded.toTag().equals(changed),
                    path + ": load(save()) keeps the sizes");
            helper.assertTrue(Math.abs(loaded.textFactor() - 1.6f) < 1.0e-6f && Math.abs(loaded.tickFactor() - 0.6f) < 1.0e-6f,
                    path + ": the factors");
            // A save edited by hand beyond the range loads clamped.
            changed.putInt(GaugeScale.TEXT_SIZE, 999);
            changed.putInt(GaugeScale.TICK_SIZE, -5);
            loaded.load(changed);
            helper.assertTrue(loaded.textSize == 200 && loaded.tickSize == 50, path + ": a save out of range loads clamped");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sliderEditsAreValidatedLikeEveryOtherField(GameTestHelper helper) {
        ComponentType type = ComponentTypes.DIAL_GAUGE;
        GaugeData data = (GaugeData) type.createData();
        CompoundTag incoming = new CompoundTag();
        incoming.putInt(GaugeScale.TEXT_SIZE, 500);
        incoming.putDouble(GaugeScale.TICK_SIZE, -40.0);
        CompoundTag merged = ConfigSchema.merge(type.configSchema(), data.toTag(), incoming);
        helper.assertTrue(merged.getInt(GaugeScale.TEXT_SIZE) == 200 && merged.getInt(GaugeScale.TICK_SIZE) == 50,
                "out of range values are clamped: " + merged);
        incoming = new CompoundTag();
        incoming.putString(GaugeScale.TEXT_SIZE, "huge");
        merged = ConfigSchema.merge(type.configSchema(), data.toTag(), incoming);
        helper.assertTrue(!merged.contains(GaugeScale.TEXT_SIZE), "a value of the wrong type is refused: " + merged);
        // The screen's slider: its ends, its steps, and a missing value reads as the default.
        ConfigField text = field(type, GaugeScale.TEXT_SIZE);
        helper.assertTrue(ConfigModel.sliderValue(text, 0.0) == 50 && ConfigModel.sliderValue(text, 1.0) == 200
                && ConfigModel.sliderValue(text, 0.5) == 125 && ConfigModel.sliderValue(text, 0.34) == 100
                && ConfigModel.sliderValue(text, 0.2) % GaugeScale.STEP_PERCENT == 0, "slider positions map to 50..200 in steps");
        helper.assertTrue(Math.abs(ConfigModel.sliderPosition(text, 100) - 1.0 / 3.0) < 1.0e-9
                && ConfigModel.sliderValue(text, ConfigModel.sliderPosition(text, 160)) == 160, "and back");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theWrenchSetsTheSizesAndTheServerClampsThem(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "sizes", s -> {
            PanelComponent gauge = s.place(ComponentTypes.EDGEWISE_GAUGE_VERTICAL, 2, 0, Footprint.of(6, 16));
            s.wrench();
            CompoundTag config = new CompoundTag();
            config.putInt(GaugeScale.TEXT_SIZE, 160);
            config.putInt(GaugeScale.TICK_SIZE, 1000);
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, gauge.id(), edit), "the wrench edit was applied");
            GaugeData data = (GaugeData) gauge.data();
            helper.assertTrue(data.textSize == 160 && data.tickSize == 200, "set through the wrench, the tick size clamped: "
                    + data.textSize + " " + data.tickSize);
            CompoundTag saved = gauge.save();
            PanelComponent reloaded = PanelComponent.load(saved);
            helper.assertTrue(((GaugeData) reloaded.data()).textSize == 160 && reloaded.save().equals(saved),
                    "the sizes survive a save and reload");
            // Back to 100 %: nothing is saved for them again.
            config = new CompoundTag();
            config.putInt(GaugeScale.TEXT_SIZE, 100);
            config.putInt(GaugeScale.TICK_SIZE, 100);
            edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(s.player, s.origin, gauge.id(), edit), "the second edit was applied");
            CompoundTag tag = gauge.data().toTag();
            helper.assertTrue(!tag.contains(GaugeScale.TEXT_SIZE) && !tag.contains(GaugeScale.TICK_SIZE), "100 % saves nothing: " + tag);
        });
    }

    /** Text caps of every piece of text, ticks' {max thickness, max length}, for a probe at a size. */
    private static float[] ticks(GaugeScaleGuardTests.Shot shot) {
        float thick = 0.0f;
        float length = 0.0f;
        for (float[] m : shot.marks()) {
            thick = Math.max(thick, m[0]);
            length = Math.max(length, m[1]);
        }
        return new float[] {thick, length};
    }

    @GameTest(template = "empty")
    public static void theRenderersHonourTextSizeAndTickSize(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        GaugeScaleGuardTests.withModels(() -> {
            for (GaugeScaleGuardTests.Probe probe : GaugeScaleGuardTests.PROBES) {
                if (!(probe.type().createData() instanceof GaugeData)) {
                    continue;
                }
                List<Footprint> sizes = new ArrayList<>(List.of(probe.type().footprints().get(0)));
                sizes.add(probe.extraSizes().isEmpty() ? probe.type().footprints().get(probe.type().footprints().size() - 1)
                        : probe.extraSizes().get(0));
                for (Footprint size : sizes) {
                    String where = probe + " " + size + ": ";
                    GaugeScaleGuardTests.Shot base = GaugeScaleGuardTests.shot(probe, size, 100, 100);
                    // Each slider on its own: longer ticks also push the bar gauge's numbers aside, shrinking them to fit.
                    GaugeScaleGuardTests.Shot small = GaugeScaleGuardTests.shot(probe, size, 60, 100);
                    GaugeScaleGuardTests.Shot large = GaugeScaleGuardTests.shot(probe, size, 160, 100);
                    GaugeScaleGuardTests.Shot thin = GaugeScaleGuardTests.shot(probe, size, 100, 60);
                    GaugeScaleGuardTests.Shot thick = GaugeScaleGuardTests.shot(probe, size, 100, 160);
                    for (int i = 0; i < base.text().size(); i++) {
                        Legends.Item item = base.text().get(i);
                        float down = small.text().get(i).capCells() / item.capCells();
                        float up = large.text().get(i).capCells() / item.capCells();
                        if (Math.abs(down - 0.6f) > 0.01f) {
                            failures.add(where + "'" + item.text() + "' at 60 % is " + down + " of its size");
                        }
                        if (up < 1.0f - 1.0e-4f || up > 1.6f + 1.0e-4f) {
                            failures.add(where + "'" + item.text() + "' at 160 % is " + up + " of its size");
                        }
                    }
                    // Some text grows (the gauge has room for more), the rest is held by its fit.
                    boolean grew = false;
                    for (int i = 0; i < base.text().size(); i++) {
                        grew |= large.text().get(i).capCells() > base.text().get(i).capCells() * 1.05f;
                    }
                    if (!base.text().isEmpty() && !grew) {
                        failures.add(where + "no text grows at 160 %");
                    }
                    float[] b = ticks(base);
                    float[] d = ticks(thin);
                    float[] u = ticks(thick);
                    if (Math.abs(d[0] / b[0] - 0.6f) > 0.01f || Math.abs(d[1] / b[1] - 0.6f) > 0.01f) {
                        failures.add(where + "ticks at 60 % are " + d[0] / b[0] + " as thick and " + d[1] / b[1] + " as long");
                    }
                    if (u[0] / b[0] < 1.1f || u[1] / b[1] < 1.0f - 1.0e-4f || u[0] / b[0] > 1.6f + 1.0e-4f || u[1] / b[1] > 1.6f + 1.0e-4f) {
                        failures.add(where + "ticks at 160 % are " + u[0] / b[0] + " as thick and " + u[1] / b[1] + " as long");
                    }
                }
            }
        });
        GaugeScaleGuardTests.assertNone(helper, failures, "sliders");
        helper.succeed();
    }
}
