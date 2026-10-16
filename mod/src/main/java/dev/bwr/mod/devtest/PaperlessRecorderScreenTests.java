package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PaperlessRecorderData;
import dev.bwr.mod.panel.component.RecorderKey;
import dev.bwr.mod.panel.component.RecorderScreen;
import dev.bwr.mod.panel.component.RecorderStyle;
import dev.bwr.mod.panel.component.RecorderUi;
import dev.bwr.mod.panel.display.RecorderCanvas;
import dev.bwr.mod.panel.display.RecorderPainter;
import dev.bwr.mod.panel.model.RecorderLayout;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * The recorder's screens painted by {@link RecorderPainter} without a client: every screen of both makes, white and
 * black, with a scenario of a recirculation pump trip behind it. The pictures are written to
 * {@code run/turbineGameTest/recorder-screens/} so they can be looked at, and the tests assert what a picture must
 * have (not blank, the background colour where it should be, white and black differ, the blinking alarm mark
 * toggling, a screen paints in well under a millisecond a pixel budget). The real client check
 * ({@code RecorderClientCheck}) shows the same screens on the bezel in a world.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PaperlessRecorderScreenTests {
    private PaperlessRecorderScreenTests() {
    }

    private static final Path OUT = Path.of("recorder-screens");

    private static final long NOW = 100_000L;

    static PanelComponent scenario(RecorderStyle style, int channels, String background, int interval) {
        PanelComponent rec = new PanelComponent(1, ComponentTypes.PAPERLESS_RECORDER, 0, 0,
                style == RecorderStyle.DX2000 ? Footprint.of(32, 32) : Footprint.of(16, 16), "REC-1");
        PaperlessRecorderData data = (PaperlessRecorderData) rec.data();
        RecorderScenario.configure(data, channels, background, interval);
        long end = RecorderScenario.fill(data, channels, NOW);
        double[] v = new double[PaperlessRecorderData.MAX_CHANNELS];
        java.util.Random noise = new java.util.Random(5);
        for (int c = 0; c < channels; c++) {
            v[c] = RecorderScenario.value(c, 430, noise);
            rec.storeInput(c, v[c]);
        }
        data.sample(end, v);
        data.addMessage("PUMP A TRIP");
        return rec;
    }

    private static long clockTick(PanelComponent rec) {
        return NOW + 2;
    }

    static RecorderCanvas render(PanelComponent rec, RecorderUi ui, long tick) {
        PaperlessRecorderData data = (PaperlessRecorderData) rec.data();
        RecorderCanvas canvas = new RecorderCanvas(data.style.textureWidth(), data.style.textureHeight());
        RecorderPainter.paint(canvas, data, rec, ui, tick, 1_760_000_000L);
        return canvas;
    }

    static void write(String name, RecorderCanvas canvas) {
        try {
            Files.createDirectories(OUT);
            BufferedImage image = new BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_RGB);
            int[] px = canvas.pixels();
            for (int i = 0; i < px.length; i++) {
                image.setRGB(i % canvas.width, i / canvas.width, RecorderCanvas.rgb(px[i]));
            }
            javax.imageio.ImageIO.write(image, "png", new File(OUT.toFile(), name + ".png"));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static int distinct(RecorderCanvas canvas) {
        Set<Integer> colours = new HashSet<>();
        for (int p : canvas.pixels()) {
            colours.add(p);
        }
        return colours.size();
    }

    /**
     * A hash of what blinks: the alarm icon at the right of the status bar and the numeric section (alarm marks) at the bottom of
     * the trend. The clock and the time labels move with the game time the test passes, so they are left out.
     */
    private static int pictureHash(RecorderCanvas canvas) {
        int k = canvas.width / 320;
        int hash = 1;
        int[] px = canvas.pixels();
        for (int y = 0; y < canvas.height; y++) {
            boolean icon = y < 20 * k;
            boolean numeric = y >= 192 * k && y < 226 * k;
            if (!icon && !numeric) {
                continue;
            }
            for (int x = icon ? 290 * k : 0; x < canvas.width; x++) {
                hash = hash * 31 + px[y * canvas.width + x];
            }
        }
        return hash;
    }

    private static int count(RecorderCanvas canvas, int rgb) {
        int n = 0;
        int abgr = RecorderCanvas.abgr(rgb);
        for (int p : canvas.pixels()) {
            if (p == abgr) {
                n++;
            }
        }
        return n;
    }

    private static void show(PanelComponent rec, RecorderScreen screen, String name, long tick) {
        PaperlessRecorderData data = (PaperlessRecorderData) rec.data();
        data.setScreen(screen);
        write(name, render(rec, new RecorderUi(), tick));
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void everyScreenOfTheDx1000PaintsInWhiteAndBlack(GameTestHelper h) {
        for (String background : new String[] {"white", "black"}) {
            PanelComponent rec = scenario(RecorderStyle.DX1000, 12, background, 4);
            PaperlessRecorderData data = (PaperlessRecorderData) rec.data();
            long tick = clockTick(rec);
            // The pump trips: the level and speed alarms are on and wait for an acknowledgement.
            h.assertTrue(data.unacknowledgedCount() > 0, background + ": the scenario has alarms waiting: " + data.unacknowledgedCount());
            Set<Integer> hashes = new HashSet<>();
            for (RecorderScreen screen : new RecorderScreen[] {RecorderScreen.TREND, RecorderScreen.DIGITAL, RecorderScreen.BAR,
                    RecorderScreen.OVERVIEW, RecorderScreen.ALARM_SUMMARY}) {
                data.setScreen(screen);
                RecorderCanvas canvas = render(rec, new RecorderUi(), tick);
                write("dx1000-" + screen.id() + "-" + background, canvas);
                int bgColour = background.equals("white") ? 0xFFFFFF : 0x000000;
                h.assertTrue(distinct(canvas) >= 6, background + " " + screen + " has a picture: " + distinct(canvas) + " colours");
                if (screen != RecorderScreen.OVERVIEW) {
                    h.assertTrue(count(canvas, bgColour) > 2000, background + " " + screen + " has the " + background + " background");
                }
                hashes.add(java.util.Arrays.hashCode(canvas.pixels()));
            }
            h.assertTrue(hashes.size() == 5, background + ": five screens, five different pictures: " + hashes.size());
            // vertical and wide layouts
            data.setScreen(RecorderScreen.TREND);
            data.layout = "vertical";
            write("dx1000-trend-vertical-" + background, render(rec, new RecorderUi(), tick));
            data.layout = "wide";
            write("dx1000-trend-wide-" + background, render(rec, new RecorderUi(), tick));
            data.layout = "horizontal";
            // the menu, the FUNC strip, the alarm before and after ACK
            RecorderUi ui = new RecorderUi();
            ui.press(RecorderKey.DISP, data);
            write("dx1000-menu-" + background, render(rec, ui, tick));
            ui.press(RecorderKey.ESC, data);
            ui.press(RecorderKey.FUNC, data);
            write("dx1000-func-" + background, render(rec, ui, tick));
            ui.press(RecorderKey.ESC, data);
            RecorderCanvas flashOn = render(rec, ui, 100);   // blink phase on
            RecorderCanvas flashOff = render(rec, ui, 110);  // blink phase off
            h.assertTrue(pictureHash(flashOn) != pictureHash(flashOff),
                    background + ": an unacknowledged alarm blinks");
            write("dx1000-trend-alarm-unack-" + background, flashOn);
            data.acknowledgeAll();
            RecorderCanvas ackOn = render(rec, ui, 100);
            RecorderCanvas ackOff = render(rec, ui, 110);
            write("dx1000-trend-alarm-acked-" + background, ackOn);
            h.assertTrue(pictureHash(ackOn) == pictureHash(ackOff),
                    background + ": an acknowledged alarm is steady");
            h.assertTrue(pictureHash(ackOn) != pictureHash(flashOff), "ACK steadies the picture: lit in the phase where the unacknowledged alarm is dark");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void everyScreenOfTheDx2000PaintsAtTwiceTheSize(GameTestHelper h) {
        for (String background : new String[] {"white", "black"}) {
            PanelComponent rec = scenario(RecorderStyle.DX2000, 48, background, 4);
            PaperlessRecorderData data = (PaperlessRecorderData) rec.data();
            data.group(0).name = "CONTAINMENT";
            long tick = clockTick(rec);
            Set<Integer> hashes = new HashSet<>();
            for (RecorderScreen screen : new RecorderScreen[] {RecorderScreen.TREND, RecorderScreen.DIGITAL, RecorderScreen.BAR,
                    RecorderScreen.OVERVIEW, RecorderScreen.ALARM_SUMMARY, RecorderScreen.CIRCULAR}) {
                data.setScreen(screen);
                RecorderCanvas canvas = render(rec, new RecorderUi(), tick);
                h.assertTrue(canvas.width == 640 && canvas.height == 480, "640 x 480");
                write("dx2000-" + screen.id() + "-" + background, canvas);
                h.assertTrue(distinct(canvas) >= 6, background + " " + screen + " has a picture");
                hashes.add(java.util.Arrays.hashCode(canvas.pixels()));
            }
            for (int quad = 0; quad < 4; quad++) {
                data.setQuad(quad);
                RecorderCanvas canvas = render(rec, new RecorderUi(), tick);
                write("dx2000-fourpanel-" + quad + "-" + background, canvas);
                hashes.add(java.util.Arrays.hashCode(canvas.pixels()));
            }
            h.assertTrue(hashes.size() == 10, background + ": ten different pictures: " + hashes.size());
            data.setScreen(RecorderScreen.TREND);
            RecorderUi ui = new RecorderUi();
            data.coverOpen = true;
            ui.press(RecorderKey.DISP, data);
            write("dx2000-menu-" + background, render(rec, ui, tick));
            ui.press(RecorderKey.ESC, data);
            ui.press(RecorderKey.FUNC, data);
            write("dx2000-func-" + background, render(rec, ui, tick));
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aScreenPaintsFastEnoughToRepaintOnEveryDot(GameTestHelper h) {
        PanelComponent small = scenario(RecorderStyle.DX1000, 12, "white", 0);
        PanelComponent big = scenario(RecorderStyle.DX2000, 48, "white", 0);
        ((PaperlessRecorderData) big.data()).setScreen(RecorderScreen.TREND);
        RecorderUi ui = new RecorderUi();
        for (int i = 0; i < 30; i++) {
            render(small, ui, 10_000 + i);
            render(big, ui, 10_000 + i);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            RecorderPainter.paint(new RecorderCanvas(320, 240), (PaperlessRecorderData) small.data(), small, ui, 20_000 + i, 0);
        }
        double dx1000 = (System.nanoTime() - t0) / 1.0e8;
        t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            RecorderPainter.paint(new RecorderCanvas(640, 480), (PaperlessRecorderData) big.data(), big, ui, 20_000 + i, 0);
        }
        double dx2000 = (System.nanoTime() - t0) / 1.0e8;
        System.out.printf("RECORDER PAINT COST: DX1000 trend %.3f ms, DX2000 trend %.3f ms per repaint%n", dx1000, dx2000);
        h.assertTrue(dx1000 < 8.0 && dx2000 < 20.0, "a repaint is a few milliseconds at most: " + dx1000 + " / " + dx2000);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theBezelLayoutCoversEveryKeyAndHitTestsThem(GameTestHelper h) {
        for (RecorderStyle style : RecorderStyle.values()) {
            RecorderLayout layout = RecorderLayout.of(style, null, style == RecorderStyle.DX2000 ? 32 : 16, style == RecorderStyle.DX2000 ? 32 : 16, 1.0f);
            for (RecorderKey key : RecorderKey.values()) {
                RecorderLayout.Box box = layout.key(key);
                if (key.softNumber() > style.softKeys() || key == RecorderKey.COVER_TAB && !style.hasCover()) {
                    h.assertTrue(box == null, style + " has no " + key);
                    continue;
                }
                h.assertTrue(box != null, style + " has the " + key + " key");
                RecorderKey hit = layout.keyAt(layout.cellX(box.centreX()), layout.cellY(box.centreY()), false);
                h.assertTrue(hit == key, style + ": the centre of " + key + " hits " + hit);
            }
            h.assertTrue(Math.abs((layout.screen.x1() - layout.screen.x0()) / (layout.screen.y1() - layout.screen.y0()) - 4.0 / 3.0) < 0.01,
                    style + " screen is 4:3");
            h.assertTrue(layout.keyAt(layout.cellX(layout.screen.centreX()), layout.cellY(layout.screen.centreY()), false) == null,
                    "the screen is not a key");
        }
        RecorderLayout dx2000 = RecorderLayout.of(RecorderStyle.DX2000, null, 32, 32, 1.0f);
        RecorderLayout.Box start = dx2000.key(RecorderKey.START);
        h.assertTrue(dx2000.keyAt(dx2000.cellX(start.centreX()), dx2000.cellY(start.centreY()), true) == null, "a closed cover swallows START");
        RecorderLayout.Box tab = dx2000.key(RecorderKey.COVER_TAB);
        h.assertTrue(dx2000.keyAt(dx2000.cellX(tab.centreX()), dx2000.cellY(tab.centreY()), true) == RecorderKey.COVER_TAB, "the tab answers first");
        RecorderLayout.Box disp = dx2000.key(RecorderKey.DISP);
        h.assertTrue(dx2000.keyAt(dx2000.cellX(disp.centreX()), dx2000.cellY(disp.centreY()), true) == RecorderKey.DISP, "DISP is not behind the cover");
        h.succeed();
    }
}
