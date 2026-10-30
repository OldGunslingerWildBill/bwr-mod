package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ConfigSchema;
import dev.bwr.mod.panel.component.CoreDisplayData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.FullCoreDisplayData;
import dev.bwr.mod.panel.component.NameplateData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelTextTarget;
import dev.bwr.mod.panel.component.RodSelectMatrixData;
import dev.bwr.mod.panel.fcd.FcdSnapshot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The label settings of spec item 9: the caption, the edge number mode and the coordinate legend toggle of the core
 * displays (and the caption and toggle of the matrix), and the fuller nameplate (three lines, two corner fields, a
 * tag, a dot and a stripe). Config round trips and bounds, saves from before the fields, CC setters through
 * {@link PanelTextTarget}, and the content patch that carries a CC change to clients.
 *
 * <p>Fixture origin 83_900 / 83_900 for the one test that needs a panel wall (see {@link FcdTestSupport}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class FcdLabelTests {
    static final int ORIGIN = 83_900;

    // ---- core display labels

    @GameTest(template = "empty", batch = "fcd")
    public static void displayLabelSettingsDefaultsBoundsAndOldSaves(GameTestHelper h) {
        FullCoreDisplayData data = new FullCoreDisplayData();
        h.assertTrue(data.caption.isEmpty() && data.coordLegends && data.edgeNumbers.equals("auto"), "defaults");
        h.assertTrue(!data.edgeNumbersOn(), "auto is off for Columbia");
        data.style = FullCoreDisplayData.BROWNS_FERRY;
        h.assertTrue(data.edgeNumbersOn(), "auto is on for Browns Ferry");
        data.edgeNumbers = "off";
        h.assertTrue(!data.edgeNumbersOn(), "off is off");
        data.style = FullCoreDisplayData.COLUMBIA;
        data.edgeNumbers = "on";
        h.assertTrue(data.edgeNumbersOn(), "on is on for Columbia");
        // The wrench edit is validated against the schema: text clamps, the mode must be one of its options.
        CompoundTag edit = new CompoundTag();
        edit.putString("Caption", "x".repeat(80));
        edit.putString("EdgeNumbers", "bogus");
        edit.putBoolean("CoordLegends", false);
        CompoundTag merged = ConfigSchema.merge(ComponentTypes.FULL_CORE_DISPLAY.configSchema(), data.toTag(), edit);
        FullCoreDisplayData edited = new FullCoreDisplayData();
        edited.load(merged);
        h.assertTrue(edited.caption.length() == CoreDisplayData.MAX_CAPTION && edited.edgeNumbers.equals("on") && !edited.coordLegends,
                "caption bounded, bad mode ignored, toggle taken");
        // It saves and loads as a fixed point.
        FullCoreDisplayData again = new FullCoreDisplayData();
        again.load(edited.toTag());
        h.assertTrue(again.toTag().equals(edited.toTag()), "a fixed point");
        // A save from before the fields: nothing there, the defaults.
        CompoundTag old = new CompoundTag();
        old.putInt("TileCells", 4);
        old.putString(FullCoreDisplayData.KEY_STYLE, "browns_ferry");
        FullCoreDisplayData loaded = new FullCoreDisplayData();
        loaded.load(old);
        h.assertTrue(loaded.tileCells == 4 && loaded.caption.isEmpty() && loaded.coordLegends && loaded.edgeNumbers.equals("auto")
                && loaded.edgeNumbersOn(), "an old save loads with the defaults");
        // The matrix has a caption and the legend toggle, and no edge numbers.
        RodSelectMatrixData matrix = new RodSelectMatrixData();
        CompoundTag matrixEdit = new CompoundTag();
        matrixEdit.putString("Caption", "ROD SELECT");
        matrixEdit.putBoolean("CoordLegends", false);
        matrixEdit.putString("EdgeNumbers", "on");
        matrix.load(ConfigSchema.merge(ComponentTypes.ROD_SELECT_MATRIX.configSchema(), matrix.toTag(), matrixEdit));
        h.assertTrue(matrix.caption.equals("ROD SELECT") && !matrix.coordLegends && !matrix.toTag().contains("EdgeNumbers"),
                "matrix: caption and toggle only");
        h.succeed();
    }

    @GameTest(template = "empty", batch = "fcd")
    public static void captionFromCcTravelsInTheNextPatchWithTheSnapshot(GameTestHelper h) {
        FullCoreDisplayData server = new FullCoreDisplayData();
        FullCoreDisplayData client = new FullCoreDisplayData();
        h.assertTrue(server instanceof PanelTextTarget && server.textFields().equals(java.util.List.of("caption")), "a text target");
        h.assertTrue(server.setText("FULL CORE DISPLAY") && server.caption.equals("FULL CORE DISPLAY"), "setText sets the caption");
        h.assertTrue(!server.setText("FULL CORE DISPLAY") && !server.setField("nonsense", "x"), "same text and unknown fields change nothing");
        byte[] patch = server.drainPatch();
        h.assertTrue(patch != null && patch.length <= dev.bwr.mod.panel.component.ComponentData.MAX_PATCH, "one bounded patch");
        client.applyPatch(patch);
        h.assertTrue(client.caption.equals("FULL CORE DISPLAY"), "the caption arrived");
        h.assertTrue(server.drainPatch() == null, "taken once");
        // A caption and a snapshot change together make one patch.
        FcdSnapshot snap = FcdSnapshot.dark(FcdSnapshot.referenceCells());
        h.assertTrue(server.setText("CORE"), "caption again");
        byte[] both = server.drainPatch();
        client.applyPatch(both);
        h.assertTrue(client.caption.equals("CORE") && client.snapshot().rodCount() == 0, "caption without a snapshot change");
        // Malformed caption patches are ignored.
        client.applyPatch(new byte[] {0x7F, 0, 99, 1});
        client.applyPatch(new byte[] {0x7F});
        h.assertTrue(client.caption.equals("CORE"), "bad patches change nothing");
        h.assertTrue(snap.rodCount() == 185, "reference snapshot");
        h.succeed();
    }

    // ---- nameplate

    @GameTest(template = "empty", batch = "fcd")
    public static void nameplateFieldsSaveLoadBoundsAndOldSaves(GameTestHelper h) {
        NameplateData data = new NameplateData();
        data.text = "SRV A";
        data.text2 = "RELIEF VALVE";
        data.text3 = "MSL B";
        data.cornerLeft = "16";
        data.cornerRight = "1101 PSIG";
        data.tag = "PAM";
        data.tagColor = 0xC0261E;
        data.dot = true;
        data.dotColor = 0x30C060;
        data.stripe = true;
        data.stripeColor = 0xE02020;
        CompoundTag saved = data.toTag();
        NameplateData loaded = new NameplateData();
        loaded.load(saved);
        h.assertTrue(loaded.toTag().equals(saved) && loaded.text3.equals("MSL B") && loaded.cornerRight.equals("1101 PSIG")
                && loaded.tag.equals("PAM") && loaded.dot && loaded.stripe && loaded.stripeColor == 0xE02020, "a fixed point");
        // An old plate: one line, no other key. It loads into line 1, re-saves as it was.
        CompoundTag old = new CompoundTag();
        old.putString("Text", "MAIN STEAM");
        old.putInt("Plate", 0x112233);
        old.putInt("Ink", 0xEEEEEE);
        NameplateData oldData = new NameplateData();
        oldData.load(old);
        h.assertTrue(oldData.text.equals("MAIN STEAM") && oldData.text2.isEmpty() && oldData.text3.isEmpty() && oldData.cornerLeft.isEmpty()
                && oldData.cornerRight.isEmpty() && oldData.tag.isEmpty() && !oldData.dot && !oldData.stripe && oldData.plate == 0x112233,
                "old save: line 1 only, everything else off");
        h.assertTrue(oldData.toTag().equals(old), "an old plate re-saves byte for byte");
        // Bounds: lines 32, corners 16, tag 8, colours masked, junk ignored.
        CompoundTag junk = new CompoundTag();
        junk.putString("Text", "a".repeat(99));
        junk.putString("Text2", "b".repeat(99));
        junk.putString("CornerRight", "c".repeat(99));
        junk.putString("Tag", "d".repeat(99));
        junk.putInt("StripeColor", -1);
        junk.putString("Dot", "yes");
        NameplateData bounded = new NameplateData();
        bounded.load(junk);
        h.assertTrue(bounded.text.length() == NameplateData.MAX_TEXT && bounded.text2.length() == NameplateData.MAX_TEXT
                && bounded.cornerRight.length() == NameplateData.MAX_CORNER && bounded.tag.length() == NameplateData.MAX_TAG
                && bounded.stripeColor == 0xFFFFFF && !bounded.dot, "bounded on load");
        // The wrench edit is validated field by field.
        CompoundTag edit = new CompoundTag();
        edit.putString("Text3", "z".repeat(60));
        edit.putBoolean("Stripe", true);
        edit.putInt("TagColor", 0x123456);
        edit.putString("Unknown", "ignored");
        NameplateData edited = new NameplateData();
        edited.load(ConfigSchema.merge(ComponentTypes.NAMEPLATE.configSchema(), edited.toTag(), edit));
        h.assertTrue(edited.text3.length() == NameplateData.MAX_TEXT && edited.stripe && edited.tagColor == 0x123456
                && !edited.toTag().contains("Unknown"), "config edit validated");
        h.assertTrue(ComponentTypes.NAMEPLATE.footprints().get(0).equals(Footprint.of(16, 4)), "the default size is unchanged");
        h.succeed();
    }

    @GameTest(template = "empty", batch = "fcd")
    public static void nameplateCcFieldsAndPatchRoundTrip(GameTestHelper h) {
        NameplateData server = new NameplateData();
        h.assertTrue(server.textFields().equals(NameplateData.FIELDS), "fields listed for CC");
        h.assertTrue(server.setText("SRV A") && server.text.equals("SRV A"), "setText is line 1");
        h.assertTrue(server.drainPatch() != null && server.drainPatch() == null, "one patch for it");
        h.assertTrue(server.setField("line2", "RELIEF") && server.setField("line3", "MSL B") && server.setField("cornerLeft", "16")
                && server.setField("cornerRight", "1101 PSIG") && server.setField("tag", "PAM"), "every field settable");
        h.assertTrue(!server.setField("line2", "RELIEF") && !server.setField("stripe", "x") && !server.setField("tagColor", "1"),
                "same text and non-text fields change nothing");
        h.assertTrue(server.setField("tag", "e".repeat(40)) && server.tag.length() == NameplateData.MAX_TAG, "truncated like setText");
        byte[] patch = server.drainPatch();
        h.assertTrue(patch != null && server.drainPatch() == null, "the changes go in one patch");
        NameplateData client = new NameplateData();
        client.applyPatch(patch);
        h.assertTrue(client.toTag().equals(server.toTag()) && client.text2.equals("RELIEF") && client.cornerRight.equals("1101 PSIG"),
                "the patch carries every field");
        client.applyPatch(new byte[] {1, 2, 3});
        h.assertTrue(client.toTag().equals(server.toTag()), "a malformed patch is ignored");
        h.succeed();
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = FcdTestSupport.WAIT_TICKS + 100)
    public static void placedNameplateQueuesOnePatchForACcFieldChange(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 0, 9, 9, run -> {
            run.wall(4, 4);
            PanelBlockEntity block = run.panel(0, 0);
            PanelComponent plate = PanelOps.placeDirect(run.level, run.wallAt(0, 0), 0, 0, ComponentTypes.NAMEPLATE, Footprint.of(32, 12));
            h.assertTrue(plate != null, "a 32 x 12 plate fits");
            NameplateData data = (NameplateData) plate.data();
            h.assertTrue(!block.contentPending(), "nothing queued");
            h.assertTrue(data.setField("cornerRight", "1101 PSIG"), "set");
            h.assertTrue(block.contentPending(), "the change is queued for clients");
            NameplateData client = new NameplateData();
            client.applyPatch(data.drainPatch());
            h.assertTrue(client.cornerRight.equals("1101 PSIG"), "and arrives");
            PanelComponent copy = PanelComponent.load(plate.save());
            h.assertTrue(((NameplateData) copy.data()).cornerRight.equals("1101 PSIG") && copy.w() == 32 && copy.h() == 12, "saved with the plate");
        });
    }
}
