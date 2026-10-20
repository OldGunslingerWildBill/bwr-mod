package dev.bwr.mod.devtest;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.fml.ModList;

/**
 * The one place the client checks save their pictures (test harness only; nothing a player sees depends on it).
 *
 * <p>Minecraft renders into its own framebuffer with no multisampling, so a plain screenshot of small real-size panel parts
 * has stair-stepped edges and shimmering text. The picture is therefore SUPERSAMPLED: the same frame is rendered once more
 * into a main render target twice as wide and as tall (the window itself is never enlarged, so a 1080p monitor is enough),
 * read back, and reduced with a 2 x 2 box filter (area average of the four sub-pixels) to 1920 x 1080 before saving. The
 * saved size does not depend on the window (a decorated window cannot be 1080 high on a 1080p monitor; this one is 1061).
 * The extra frame is drawn by the game's own {@code GameRenderer.render}, like the vanilla panorama screenshot does it, with
 * the {@link Window} temporarily reporting the 3840 x 2160 framebuffer size and GUI scale, so the world, the GUI, scissor boxes
 * and line widths all stay consistent with each other.
 *
 * <p>{@code -Dbwr.noShotAA=true} (Gradle: {@code -PbwrNoShotAA}) turns it off and saves the plain framebuffer. Iris replaces
 * the render pipeline, so with Iris loaded the plain framebuffer (window size) is saved too.
 *
 * <p>The checks' PIXEL assertions keep reading the live framebuffer at window size: {@link #saveKeeping} returns that plain
 * capture (taken before the supersampled frame), and only the file on disk is the anti-aliased one.
 */
public final class CheckShots {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    /** Size of the saved pictures (the offscreen frame is twice this). */
    public static final int OUT_WIDTH = 1920, OUT_HEIGHT = 1080;
    private static boolean warned;
    private static long totalNanos, superNanos;
    private static int shots;

    private CheckShots() {}

    /** True when saved pictures are supersampled. */
    public static boolean antiAliasing() {
        return !Boolean.getBoolean("bwr.noShotAA") && !ModList.get().isLoaded("iris");
    }

    /** Saves the current frame to {@code file} and discards the live capture. */
    public static void save(Minecraft mc, Path file) throws IOException {
        try (NativeImage live = saveKeeping(mc, file)) {
            // nothing else to do: the file is on disk
        }
    }

    /**
     * Saves the current frame to {@code file} (supersampled unless switched off) and returns the plain live framebuffer
     * capture at window size, taken first, for pixel assertions. The caller closes it.
     */
    public static NativeImage saveKeeping(Minecraft mc, Path file) throws IOException {
        long start = System.nanoTime();
        NativeImage live = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        NativeImage saved = null;
        try {
            if (antiAliasing()) {
                long t = System.nanoTime();
                saved = supersampled(mc);
                superNanos += System.nanoTime() - t;
            }
            (saved != null ? saved : live).writeToFile(file);
            if (saved != null && Boolean.getBoolean("bwr.shotPlainCopy")) {
                String name = file.getFileName().toString();
                live.writeToFile(file.resolveSibling(name.replace(".png", "-plain.png")));
            }
        } finally {
            if (saved != null) saved.close();
        }
        totalNanos += System.nanoTime() - start;
        shots++;
        if (shots == 1 || shots % 10 == 0) LOG.info("CHECK SHOT live {}x{}, saved {}x{} {} ({} shots, {} ms each on average): {}", live.getWidth(),
                live.getHeight(), (saved != null ? saved : live).getWidth(), (saved != null ? saved : live).getHeight(),
                saved != null ? "2x2 supersampled" : "plain", shots, totalNanos / shots / 1_000_000, file.getFileName());
        if (shots == 1 || shots % 10 == 0) LOG.info("CHECK SHOT cost: {} ms per shot in all, of which {} ms the supersampled frame (render, readback, filter); the rest is the plain capture and the PNG write",
                totalNanos / shots / 1_000_000, superNanos / shots / 1_000_000);
        return live;
    }

    /**
     * Renders the frame at 3840 x 2160 and returns it reduced to 1920 x 1080, or null if that failed. The output size does not
     * depend on the window: a decorated window cannot be 1080 pixels high on a 1080p monitor (Windows caps it a little below,
     * 1061 here), but the offscreen frame can always be an exact 16:9 1080p picture.
     */
    private static NativeImage supersampled(Minecraft mc) {
        var main = mc.getMainRenderTarget();
        Window window = mc.getWindow();
        int liveWidth = window.getWidth(), liveHeight = window.getHeight();
        double guiScale = window.getGuiScale();
        int bigWidth = OUT_WIDTH * 2, bigHeight = OUT_HEIGHT * 2;
        NativeImage big = null;
        try {
            // The doubled GUI scale keeps the GUI the same size on the picture; it is derived from the live scale, so a check
            // that raised the scale (the size picker) is saved with that scale.
            window.setWidth(bigWidth);
            window.setHeight(bigHeight);
            window.setGuiScale(guiScale * 2);
            main.resize(bigWidth, bigHeight, Minecraft.ON_OSX);
            mc.gameRenderer.resize(bigWidth, bigHeight);
            if (main.width != bigWidth || main.height != bigHeight) {
                if (!warned) LOG.warn("CHECK SHOT: the GPU gave a {}x{} target instead of {}x{}; saving plain pictures",
                        main.width, main.height, bigWidth, bigHeight);
                warned = true;
                return null;
            }
            main.setClearColor(0f, 0f, 0f, 0f);
            main.clear(Minecraft.ON_OSX);
            main.bindWrite(true);
            mc.gameRenderer.render(mc.getTimer(), true);
            big = Screenshot.takeScreenshot(main);
            return halve(big);
        } finally {
            if (big != null) big.close();
            window.setWidth(liveWidth);
            window.setHeight(liveHeight);
            window.setGuiScale(guiScale);
            main.resize(liveWidth, liveHeight, Minecraft.ON_OSX);
            mc.gameRenderer.resize(liveWidth, liveHeight);
            main.bindWrite(true);
        }
    }

    /** 2 x 2 box filter: every output pixel is the plain average of four input pixels per colour channel. */
    private static NativeImage halve(NativeImage big) {
        int w = big.getWidth() / 2, h = big.getHeight() / 2;
        int[] in = big.getPixelsRGBA();
        int stride = big.getWidth();
        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            int row = 2 * y * stride;
            for (int x = 0; x < w; x++) {
                int a = in[row + 2 * x], b = in[row + 2 * x + 1], c = in[row + stride + 2 * x], d = in[row + stride + 2 * x + 1];
                int r = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF) + 2) >> 2;
                int g = ((a >> 8 & 0xFF) + (b >> 8 & 0xFF) + (c >> 8 & 0xFF) + (d >> 8 & 0xFF) + 2) >> 2;
                int bl = ((a >> 16 & 0xFF) + (b >> 16 & 0xFF) + (c >> 16 & 0xFF) + (d >> 16 & 0xFF) + 2) >> 2;
                out.setPixelRGBA(x, y, 0xFF000000 | bl << 16 | g << 8 | r);
            }
        }
        return out;
    }
}
