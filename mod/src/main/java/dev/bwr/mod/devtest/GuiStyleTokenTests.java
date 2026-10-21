package dev.bwr.mod.devtest;

import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Shape;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The machine screens' design tokens ({@link BwrStyle}), checked as numbers: every text-on-background pair the screens
 * draw reaches 4.5:1, every graphic part 3:1, the spacing scale is a 4 px scale, the status vocabulary has a distinct
 * shape and word for each colour, and the type scale uses whole-number sizes.
 *
 * <p>Pure: no world and no client class ({@code BwrStyle} imports nothing from Minecraft), so it runs on the dedicated
 * test server. {@link #theOldPaletteWouldFailTheSameRule} is the control: the colours the screens used before the
 * redesign, run through the same predicate, are rejected, which shows the rule can fail.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class GuiStyleTokenTests {
    private GuiStyleTokenTests() {
    }

    /** The old screens' secondary text ({@code BwrScreen.TEXT_DIM}) and the surfaces it was drawn on. */
    private static final int OLD_TEXT_DIM = 0xFF7C848F;
    private static final int OLD_READOUT_WELL = 0xFF202832;
    private static final int OLD_INFO_WELL = 0xFF1C222A;

    private static void check(GameTestHelper helper, List<String> failures, String what) {
        helper.assertTrue(failures.isEmpty(), what + ": " + failures.size() + " failures, first:\n  "
                + String.join("\n  ", failures.subList(0, Math.min(8, failures.size()))));
    }

    @GameTest(template = "empty")
    public static void everyTextAndBackgroundPairReachesFourPointFiveToOne(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        List<BwrStyle.Pair> pairs = BwrStyle.textPairs();
        for (BwrStyle.Pair pair : pairs) {
            if (pair.contrast() < BwrStyle.MIN_CONTRAST) {
                failures.add(String.format("%s: %.2f:1 is below %.1f:1", pair.name(), pair.contrast(), BwrStyle.MIN_CONTRAST));
            }
        }
        helper.assertTrue(pairs.size() >= 50, "the pair table lost entries: " + pairs.size());
        check(helper, failures, "text contrast over " + pairs.size() + " pairs");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void graphicPartsReachThreeToOne(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        for (BwrStyle.Pair pair : BwrStyle.graphicPairs()) {
            if (pair.contrast() < BwrStyle.MIN_GRAPHIC_CONTRAST) {
                failures.add(String.format("%s: %.2f:1 is below %.1f:1", pair.name(), pair.contrast(), BwrStyle.MIN_GRAPHIC_CONTRAST));
            }
        }
        check(helper, failures, "graphic contrast");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theOldPaletteWouldFailTheSameRule(GameTestHelper helper) {
        helper.assertTrue(BwrStyle.contrast(OLD_TEXT_DIM, OLD_READOUT_WELL) < BwrStyle.MIN_CONTRAST,
                "old label colour on the readout well should fail, got " + BwrStyle.contrast(OLD_TEXT_DIM, OLD_READOUT_WELL));
        helper.assertTrue(BwrStyle.contrast(OLD_TEXT_DIM, OLD_INFO_WELL) < BwrStyle.MIN_CONTRAST,
                "old label colour on the info well should fail, got " + BwrStyle.contrast(OLD_TEXT_DIM, OLD_INFO_WELL));
        helper.assertTrue(BwrStyle.contrast(BwrStyle.TEXT_SECONDARY, BwrStyle.SURFACE) >= BwrStyle.MIN_CONTRAST,
                "the replacement must pass");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void contrastFunctionMatchesTheWcagDefinition(GameTestHelper helper) {
        helper.assertTrue(Math.abs(BwrStyle.contrast(0xFF000000, 0xFFFFFFFF) - 21.0) < 1.0e-9, "black on white is 21:1");
        helper.assertTrue(Math.abs(BwrStyle.contrast(0xFF123456, 0xFF123456) - 1.0) < 1.0e-9, "a colour on itself is 1:1");
        helper.assertTrue(Math.abs(BwrStyle.contrast(0xFF777777, 0xFFFFFFFF) - 4.478) < 0.01, "#777 on white is 4.48:1 (WCAG's own example)");
        helper.assertTrue(Math.abs(BwrStyle.contrast(0xFF123456, 0xFFFEDCBA) - BwrStyle.contrast(0xFFFEDCBA, 0xFF123456)) < 1.0e-12,
                "contrast is symmetric");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void spacingIsAFourPixelScale(GameTestHelper helper) {
        int[] scale = {BwrStyle.SP1, BwrStyle.SP2, BwrStyle.SP3, BwrStyle.SP4, BwrStyle.SP5};
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < scale.length; i++) {
            if (scale[i] % 4 != 0) {
                failures.add("SP" + (i + 1) + " = " + scale[i] + " is not a multiple of 4");
            }
            if (i > 0 && scale[i] <= scale[i - 1]) {
                failures.add("SP" + (i + 1) + " does not grow");
            }
        }
        int[] sizes = {BwrStyle.CONTROL, BwrStyle.TITLE_BAR, BwrStyle.TAB, BwrStyle.METER, BwrStyle.WINDOW_MARGIN, BwrStyle.ROW};
        String[] names = {"CONTROL", "TITLE_BAR", "TAB", "METER", "WINDOW_MARGIN", "ROW"};
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] % 4 != 0) {
                failures.add(names[i] + " = " + sizes[i] + " is not a multiple of 4");
            }
        }
        check(helper, failures, "spacing scale");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyStatusHasItsOwnShapeWordAndColour(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        Set<Shape> shapes = new HashSet<>();
        Set<String> words = new HashSet<>();
        Set<Integer> colours = new HashSet<>();
        for (Status status : Status.values()) {
            if (status == Status.NONE) {
                continue;
            }
            if (status.shape == Shape.NONE) {
                failures.add(status + " has no shape: it would be told apart by colour alone");
            }
            if (status.label.isBlank()) {
                failures.add(status + " has no word");
            }
            if (!shapes.add(status.shape)) {
                failures.add(status + " shares its shape with another status");
            }
            if (!words.add(status.label)) {
                failures.add(status + " shares its word with another status");
            }
            if (!colours.add(status.colour)) {
                failures.add(status + " shares its colour with another status");
            }
        }
        for (Status status : new Status[]{Status.NORMAL, Status.CAUTION, Status.ALARM, Status.INFO}) {
            helper.assertTrue(status != Status.NONE, "the four status colours of the brief exist");
        }
        check(helper, failures, "status vocabulary");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void typeScaleUsesWholeNumberSizesAndNamedSteps(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        int previous = 0;
        for (Type type : new Type[]{Type.CAPTION, Type.BODY, Type.HEADER, Type.TITLE, Type.DISPLAY}) {
            if (type.scale < 1) {
                failures.add(type + " has scale " + type.scale);
            }
            if (type.scale < previous) {
                failures.add(type + " is smaller than the step below it");
            }
            previous = type.scale;
        }
        helper.assertTrue(Type.DISPLAY.scale > Type.BODY.scale, "the display step is larger than body text");
        helper.assertTrue(Type.TITLE.bold && !Type.BODY.bold, "the title is the bold step");
        helper.assertTrue(Type.HEADER.upper && !Type.BODY.upper, "the section header is the upper-case step");
        check(helper, failures, "type scale");
        helper.succeed();
    }
}
