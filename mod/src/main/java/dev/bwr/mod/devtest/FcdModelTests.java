package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.component.PushbuttonData;
import dev.bwr.mod.panel.component.RotarySelectorData;
import dev.bwr.mod.panel.fcd.FcdLayout;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.FcdModels;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.client.QuadSink;
import dev.bwr.mod.panel.model.Detents;
import dev.bwr.mod.panel.model.Legends;
import dev.bwr.mod.panel.model.PanelModelParser;
import dev.bwr.mod.panel.model.PartDraw;
import dev.bwr.mod.panel.model.Placement;
import dev.bwr.mod.panel.model.Recolour;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * The models of the full core display family (spec item 6) as the game uses them: the style table rows of the SRV
 * switches, lights and the guarded yellow button, the fallback to the classic look when a model is missing, the
 * single mapping of LPRM levels to strip lamps, and, for every one of the ten model files that is present in the
 * jar, that it parses and has the parts, anchors and moving parts the drawing code relies on. The file checks pass
 * vacuously while the models are not merged; they are what catches a mismatched export when they arrive. Pure
 * data: these tests use no fixture area.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class FcdModelTests {
    @GameTest(template = "empty", batch = "fcd")
    public static void styleTableMapsTheSrvSwitchesLightsAndTheGuardedButton(GameTestHelper h) {
        try {
            var any = (java.util.function.Predicate<String>) id -> true;
            h.assertTrue(PanelStyles.styles("rotary_selector").contains(PanelStyles.OILTIGHT)
                    && PanelStyles.styles("rotary_selector").get(0).equals(PanelStyles.CLASSIC), "oiltight is a style of the selector");
            h.assertTrue(PanelStyles.defaultVariant("rotary_selector", PanelStyles.OILTIGHT).equals("knob"), "the knob is the default");
            h.assertTrue(PanelStyles.resolve("rotary_selector", PanelStyles.OILTIGHT, "knob", any).modelId().equals(FcdModels.SELECTOR_KNOB)
                    && PanelStyles.resolve("rotary_selector", PanelStyles.OILTIGHT, "lever", any).modelId().equals(FcdModels.SELECTOR_LEVER),
                    "knob and lever");
            h.assertTrue(PanelStyles.resolve("lamp", PanelStyles.GE, "dome_smooth", any).modelId().equals(FcdModels.DOME_SMOOTH)
                    && PanelStyles.resolve("lamp", PanelStyles.GE, "dome_ribbed", any).modelId().equals(FcdModels.DOME_RIBBED),
                    "smooth and ribbed lenses");
            h.assertTrue(PanelStyles.resolve("pushbutton", PanelStyles.GE, "guarded_yellow", any).modelId().equals(FcdModels.GUARDED_YELLOW),
                    "guarded yellow button");
            // The existing lights and buttons stay.
            h.assertTrue(PanelStyles.resolve("lamp", PanelStyles.GE, "round", any).modelId().equals("light_round")
                    && PanelStyles.resolve("pushbutton", PanelStyles.GE, "guarded", any).modelId().equals("pb_guarded"), "existing rows stay");
            // A switch to the oiltight style from a variant it does not have shows its default, never nothing.
            h.assertTrue(PanelStyles.resolve("rotary_selector", PanelStyles.OILTIGHT, "oval", any).modelId().equals(FcdModels.SELECTOR_KNOB),
                    "an unknown variant falls back to the style's default");
            // A missing model is the classic look, not a hole.
            h.assertTrue(PanelStyles.resolve("rotary_selector", PanelStyles.OILTIGHT, "knob", id -> false) == null
                    && PanelStyles.resolve("lamp", PanelStyles.GE, "dome_ribbed", id -> false) == null, "missing model: classic");
            // New components keep their defaults: the added rows are not defaults.
            PanelStyles.overrideShipped(id -> true);
            h.assertTrue(new RotarySelectorData().style.equals("ge") && new RotarySelectorData().variant.equals("oval")
                    && new LampData().variant.equals("round") && new PushbuttonData().variant.equals("flush"), "defaults unchanged");
        } finally {
            PanelStyles.overrideShipped(null);
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "fcd")
    public static void lprmLevelsMapToStripLampsInOnePlace(GameTestHelper h) {
        // lvl_a is the top lamp of the model and level D is the top detector of the real strip.
        h.assertTrue(FcdLayout.lprmLevelAtSlot(0) == 3 && FcdLayout.lprmLevelAtSlot(1) == 2 && FcdLayout.lprmLevelAtSlot(2) == 1
                && FcdLayout.lprmLevelAtSlot(3) == 0, "slot 0 (top) shows level D, slot 3 (bottom) level A");
        Set<Integer> seen = new HashSet<>();
        for (int slot = 0; slot < 4; slot++) {
            h.assertTrue(seen.add(FcdLayout.lprmLevelAtSlot(slot)), "a bijection");
        }
        h.succeed();
    }

    private static BakedPanelModel load(String id) throws IOException {
        String text;
        try (InputStream in = FcdModelTests.class.getResourceAsStream("/assets/bwr/models/panel/" + id + ".json")) {
            if (in == null) {
                return null;
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return PanelModelParser.parse(id, text);
    }

    @GameTest(template = "empty", batch = "fcd")
    public static void shippedFcdModelsHaveThePartsTheDrawingCodeUses(GameTestHelper h) throws IOException {
        h.assertTrue(FcdModels.ALL.size() == 10 && new HashSet<>(FcdModels.ALL).size() == 10, "ten distinct ids");
        for (String id : FcdModels.ALL) {
            BakedPanelModel model = load(id);
            if (model == null) {
                continue;
            }
            for (String part : FcdModels.partsDrawn(id)) {
                h.assertTrue(model.part(part) != null, id + " has the part " + part);
            }
            h.assertTrue(model.faceTotal() > 0 && model.footprintW > 0 && model.footprintH > 0, id + " has geometry and a footprint");
        }
        BakedPanelModel tile = load(FcdModels.TILE_COLUMBIA);
        if (tile != null) {
            h.assertTrue(tile.part("upper").role == BakedPanelModel.Role.LAMP && tile.part("lower").role == BakedPanelModel.Role.LAMP,
                    "the two halves of a tile are lamps");
            h.assertTrue(tile.label("upper") != null && tile.label("lower") != null && tile.faceTotal() <= 40, "tile anchors and budget");
        }
        BakedPanelModel button = load(FcdModels.ROD_SELECT_BUTTON);
        if (button != null) {
            h.assertTrue(button.part("cap").role == BakedPanelModel.Role.LAMP && button.part("cap").hasTravel && button.label("legend") != null,
                    "the cap is a lamp that travels");
        }
        BakedPanelModel strip = load(FcdModels.BF_LPRM_STRIP);
        if (strip != null) {
            h.assertTrue(strip.part("lvl_a").role == BakedPanelModel.Role.LAMP && strip.label("coord") != null, "strip lamps and coordinate anchor");
        }
        BakedPanelModel module = load(FcdModels.BF_ROD_MODULE);
        if (module != null) {
            h.assertTrue(module.label("digits") != null && module.part("select").role == BakedPanelModel.Role.LAMP, "module anchors");
        }
        BakedPanelModel guarded = load(FcdModels.GUARDED_YELLOW);
        if (guarded != null) {
            h.assertTrue(guarded.part("cap") != null && guarded.part("cap").hasTravel && guarded.part("guard").role == BakedPanelModel.Role.GLASS
                    && guarded.part("bezel") != null, "guarded button parts");
        }
        for (String id : new String[] {FcdModels.SELECTOR_KNOB, FcdModels.SELECTOR_LEVER}) {
            BakedPanelModel selector = load(id);
            if (selector != null) {
                BakedPanelModel.Part handle = selector.part("handle");
                h.assertTrue(handle != null && handle.hasPivot && handle.axisZ == 1.0f, id + " handle pivots on Z");
                h.assertTrue(selector.label("pos1") != null && selector.label("pos2") != null && selector.label("pos3") != null
                        && selector.label("caption") != null, id + " position and caption anchors");
            }
        }
        for (String id : new String[] {FcdModels.DOME_SMOOTH, FcdModels.DOME_RIBBED}) {
            BakedPanelModel light = load(id);
            if (light != null) {
                h.assertTrue(light.part("lens") != null && light.part("lens").role == BakedPanelModel.Role.LAMP && light.part("bezel") != null,
                        id + " lens and bezel");
            }
        }
        h.succeed();
    }

    /**
     * What the real-client check showed against the owner's SRV photos: the oiltight selectors turn to the bearings of
     * their legends (about 37.6 degrees either side), a two position one between the outer two legends, a dome lens
     * is the lamp's colour whatever it is (the default red included), and a lit dome is that colour edge to edge.
     */
    @GameTest(template = "empty", batch = "fcd")
    public static void srvSwitchesFollowThePhotographedLook(GameTestHelper h) throws IOException {
        for (String id : new String[] {FcdModels.SELECTOR_KNOB, FcdModels.SELECTOR_LEVER}) {
            BakedPanelModel selector = load(id);
            if (selector == null) {
                continue;
            }
            float[] three = Detents.angles(selector, 3);
            h.assertTrue(Math.abs(three[0] + 37.57f) < 0.1f && Math.abs(three[1]) < 0.01f && Math.abs(three[2] - 37.57f) < 0.1f,
                    id + " three detents at -37.6, 0, 37.6: " + java.util.Arrays.toString(three));
            float[] two = Detents.angles(selector, 2);
            h.assertTrue(Detents.outerPair(selector, 2) && !Detents.outerPair(selector, 3)
                    && Math.abs(two[0] + 37.57f) < 0.1f && Math.abs(two[1] - 37.57f) < 0.1f,
                    id + " two positions use the outer legends: " + java.util.Arrays.toString(two));
            PanelComponent component = new PanelComponent(1, ComponentTypes.ROTARY_SELECTOR, 0, 0, Footprint.of(12, 15), "RS-1");
            RotarySelectorData data = (RotarySelectorData) component.data();
            data.positions = 2;
            data.names = "OFF,OPEN";
            java.util.List<Legends.Item> items = Legends.items(selector, Placement.fit(selector, 12, 15, 1.0f), "rotary_selector", component);
            BakedPanelModel.Label right = selector.label("pos3");
            Legends.Item open = null;
            boolean middle = false;
            for (Legends.Item item : items) {
                middle |= item.text().equals("AUTO") || item.text().equals("TEST");
                if (item.text().equals("OPEN")) {
                    open = item;
                }
            }
            h.assertTrue(open != null && !middle && right != null, id + " a two position selector prints OFF and OPEN only");
            data.positions = 3;
            data.names = "OFF,AUTO,OPEN";
            boolean auto = false;
            for (Legends.Item item : Legends.items(selector, Placement.fit(selector, 12, 15, 1.0f), "rotary_selector", component)) {
                auto |= item.text().equals("AUTO");
            }
            h.assertTrue(auto, id + " three positions print the middle legend too");
        }
        BakedPanelModel dome = load(FcdModels.DOME_SMOOTH);
        if (dome != null) {
            PanelComponent lamp = new PanelComponent(2, ComponentTypes.LAMP, 0, 0, Footprint.of(8, 8), "L-1");
            LampData data = (LampData) lamp.data();
            BakedPanelModel.Part lens = dome.part("lens");
            h.assertTrue(Recolour.baseFor(lens, data, Recolour.KEEP) == LampData.DEFAULT_COLOR, "a default lamp: a lens of the default colour");
            data.color = 0x30D060;
            h.assertTrue(Recolour.baseFor(lens, data, Recolour.KEEP) == 0x30D060, "a green lamp: a green lens");
            int[] lowest = {Integer.MAX_VALUE};
            int[] highest = {0};
            QuadSink sink = new QuadSink() {
                @Override
                public void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2,
                                 float x3, float y3, float z3, int rgb, int normal, boolean emissive) {
                    int green = rgb >> 8 & 0xFF;
                    lowest[0] = Math.min(lowest[0], green);
                    highest[0] = Math.max(highest[0], green);
                }
            };
            PartDraw.emit(sink, lens, Placement.fit(dome, 8, 8, 1.0f), 0.0f, 0.0f, 0x30D060, 1.0f, true, new float[PartDraw.SCRATCH]);
            // The project owner's call (2026-10-15): a lit dome is the chosen colour across the whole lens, unshaded.
            h.assertTrue(lowest[0] == 0xD0 && highest[0] == 0xD0, "a lit dome: every face the chosen green 0xD0: "
                    + lowest[0] + ".." + highest[0]);
        }
        h.succeed();
    }
}
