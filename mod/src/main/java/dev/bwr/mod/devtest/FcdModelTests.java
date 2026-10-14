package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.component.PushbuttonData;
import dev.bwr.mod.panel.component.RotarySelectorData;
import dev.bwr.mod.panel.fcd.FcdLayout;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.FcdModels;
import dev.bwr.mod.panel.model.PanelModelParser;
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
}
