package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.component.ComponentData;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.GaugeData;
import dev.bwr.mod.panel.component.GraphicDisplayData;
import dev.bwr.mod.panel.component.LcdData;
import dev.bwr.mod.panel.component.MimicLineData;
import dev.bwr.mod.panel.component.MimicSymbolData;
import dev.bwr.mod.panel.component.NameplateData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.SegmentDisplayData;
import dev.bwr.mod.panel.component.StripChartData;
import dev.bwr.mod.panel.display.LcdFont;
import dev.bwr.mod.panel.display.MimicShape;
import dev.bwr.mod.panel.display.PanelFormat;
import dev.bwr.mod.panel.display.SevenSegment;
import dev.bwr.mod.panel.display.SixteenSegment;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Control panel gauges, displays and mimic (spec 2.1, 2.2): the pure format and
 * character-map pieces, placement of every new type at every footprint,
 * save/reload and network round trips of each (including graphic display
 * content and strip-chart history), input values driving needle and segment
 * state, the no-signal look, and the display Java API with its sync patches.
 *
 * <p>Same fixture rules as {@link PanelFoundationTests}: each test builds at its own far-away fixture, force-loads
 * the chunks it needs, empties the volume first (a persistent world run twice starts clean) and releases everything
 * in every outcome.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelDisplayTests {
    private PanelDisplayTests() {
    }

    private static final Direction FACING = Direction.NORTH;

    private static final class Stage {
        final ServerLevel level;
        final BlockPos origin;
        final FakePlayer player;

        private Stage(GameTestHelper helper, int slot) {
            level = helper.getLevel();
            origin = new BlockPos(48_900 + slot * 64, 110, 48_900);
            hold(true);
            player = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes(("panel-display-test-" + slot).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "panel-display-" + slot));
        }

        /** Runs {@code body} on a clean, force-loaded fixture two ticks later; releases the chunks in every outcome. */
        static void run(GameTestHelper helper, int slot, Consumer<Stage> body) {
            Stage s = new Stage(helper, slot);
            helper.runAfterDelay(2, () -> {
                try {
                    s.clear();
                    s.player.setGameMode(GameType.SURVIVAL);
                    s.player.getInventory().clearContent();
                    s.player.setShiftKeyDown(false);
                    s.player.setPos(s.origin.getX() - 1.5, s.origin.getY() - 1.0, s.origin.getZ() - 2.5);
                    body.accept(s);
                } finally {
                    s.release();
                }
                helper.succeed();
            });
        }

        private void hold(boolean force) {
            for (int x = (origin.getX() - 40) >> 4; x <= (origin.getX() + 40) >> 4; x++) {
                for (int z = (origin.getZ() - 16) >> 4; z <= (origin.getZ() + 16) >> 4; z++) {
                    level.setChunkForced(x, z, force);
                    if (force) {
                        level.getChunk(x, z);
                    }
                }
            }
        }

        private void clear() {
            for (int x = -24; x <= 4; x++) {
                for (int y = -12; y <= 12; y++) {
                    for (int z = -6; z <= 6; z++) {
                        BlockPos pos = origin.offset(x, y, z);
                        if (!level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(60, 24, 24))) {
                item.discard();
            }
        }

        void release() {
            clear();
            hold(false);
        }

        BlockPos at(int column, int row) {
            return origin.relative(FACING.getCounterClockWise(), column).below(row);
        }

        void wall(int columns, int rows) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < columns; c++) {
                    level.setBlock(at(c, r), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                            .setValue(PanelBlock.FACING, FACING), 3);
                }
            }
        }

        PanelBlockEntity be(BlockPos pos) {
            return (PanelBlockEntity) level.getBlockEntity(pos);
        }

        PanelGroup.Result place(Item item, int variant, BlockPos pos, int cx, int cy) {
            ItemStack stack = new ItemStack(item);
            if (variant > 0) {
                stack.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), variant);
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            return PanelOps.place(player, InteractionHand.MAIN_HAND, pos, cx, cy);
        }

        void wrench() {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
        }

        /** Configures with the wrench: config fields and constant input sources (NaN = leave the input empty). */
        boolean configure(BlockPos pos, int id, CompoundTag config, double... inputs) {
            wrench();
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            ListTag list = new ListTag();
            for (double value : inputs) {
                CompoundTag source = new CompoundTag();
                if (Double.isNaN(value)) {
                    source.putString("Kind", "none");
                } else {
                    source.putString("Kind", "constant");
                    source.putDouble("Value", value);
                }
                list.add(source);
            }
            if (inputs.length > 0) {
                edit.put("Inputs", list);
            }
            return PanelOps.configure(player, pos, id, edit);
        }
    }

    private static Item itemOf(ComponentType type) {
        return type.item();
    }

    /** The registered ids of this class's types, in a stable order. */
    private static List<ComponentType> newTypes() {
        return List.of(ComponentTypes.DIAL_GAUGE, ComponentTypes.EDGEWISE_GAUGE_VERTICAL,
                ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, ComponentTypes.STRIP_CHART,
                ComponentTypes.SEVEN_SEGMENT_DISPLAY, ComponentTypes.SIXTEEN_SEGMENT_DISPLAY,
                ComponentTypes.CHARACTER_LCD, ComponentTypes.GRAPHIC_DISPLAY,
                ComponentTypes.MIMIC_LINE, ComponentTypes.MIMIC_SYMBOL);
    }

    private static CompoundTag config(Object... keyValues) {
        CompoundTag tag = new CompoundTag();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = (String) keyValues[i];
            Object value = keyValues[i + 1];
            if (value instanceof Integer n) {
                tag.putInt(key, n);
            } else if (value instanceof Double d) {
                tag.putDouble(key, d);
            } else {
                tag.putString(key, (String) value);
            }
        }
        return tag;
    }

    // ---- pure pieces

    @GameTest(template = "empty")
    public static void formatStringsHandleWidthPrecisionPaddingOverflowAndNoSignal(GameTestHelper helper) {
        double[] values = {42.345, 7, Double.NaN, 123456.0, -3.5, 0.0};
        java.util.function.IntToDoubleFunction in = i -> i < values.length ? values[i] : Double.NaN;
        check(helper, "PWR {0:5.1f}%", in, "PWR  42.3%");
        check(helper, "{0:04d}", in, "0042");
        check(helper, "{1}", in, "7");
        check(helper, "{1:03d}|{1:>3d}|{1:<3d}|", in, "007|  7|7  |");
        check(helper, "{4:+.1f} {4:06.1f} {4:d}", in, "-3.5 -003.5 -3");
        check(helper, "{5:+.1f} {5:.0f} {5}", in, "+0.0 0 0");
        check(helper, "{0}", in, "42.345");
        check(helper, "{0:.2}", in, "42.35");
        check(helper, "{3:3d}", in, "OFL");
        check(helper, "{3:5d}", in, "  OFL");
        check(helper, "{3:<5d}", in, "OFL  ");
        check(helper, "{3:2d}", in, "--");
        check(helper, "{0:2.0f}", in, "42");
        check(helper, "{0:1.0f}", in, "-");
        check(helper, "{2}", in, "----");
        check(helper, "{2:6.1f}", in, "------");
        check(helper, "{9}", in, "----");
        check(helper, "{0:x} {3:X}", in, "2a 1E240");
        check(helper, "{{{1}}}", in, "{7}");
        check(helper, "no fields", in, "no fields");
        check(helper, "{", in, "{");
        check(helper, "{x} {0:q} {16}", in, "{x} {0:q} {16}");
        check(helper, "}", in, "}");
        check(helper, "", in, "");
        helper.assertTrue(PanelFormat.formatNumber(Double.POSITIVE_INFINITY, "5d").equals("  OFL"), "infinity overflows");
        helper.assertTrue(PanelFormat.formatNumber(1.0e20, "").equals(PanelFormat.formatNumber(1.0e30, "")),
                "huge values overflow the same way");
        helper.assertTrue(PanelFormat.formatNumber(-0.0001, ".2f").equals("0.00"), "negative zero prints as zero");
        helper.assertTrue(PanelFormat.formatNumber(1.0, "999d") == null || PanelFormat.formatNumber(1.0, "9999d") == null,
                "absurd widths are refused");
        helper.assertTrue(PanelFormat.hasFields("a{0}b") && !PanelFormat.hasFields("a{{0}}b") && !PanelFormat.hasFields("ab"),
                "hasFields");
        helper.succeed();
    }

    private static void check(GameTestHelper helper, String pattern, java.util.function.IntToDoubleFunction values,
                              String expected) {
        String actual = PanelFormat.format(pattern, values);
        helper.assertTrue(expected.equals(actual), "format '" + pattern + "' gave '" + actual + "', wanted '" + expected + "'");
    }

    @GameTest(template = "empty")
    public static void sevenSegmentMapShowsOnlyWhatItCan(GameTestHelper helper) {
        for (char c = '0'; c <= '9'; c++) {
            helper.assertTrue(SevenSegment.mask(c) != 0, "digit " + c);
        }
        helper.assertTrue(SevenSegment.mask('8') == 0x7F && SevenSegment.mask('1') == (SevenSegment.B | SevenSegment.C),
                "8 lights all seven and 1 only b and c");
        for (char c : "AbCdEFGHIJLnoPqrStUy-_".toCharArray()) {
            helper.assertTrue(SevenSegment.mask(c) != 0 && SevenSegment.canShow(c), "letter " + c);
            helper.assertTrue(SevenSegment.mask(c) == SevenSegment.mask(Character.toUpperCase(c))
                    && SevenSegment.mask(c) == SevenSegment.mask(Character.toLowerCase(c)), "either case " + c);
        }
        for (char c : "KMVWXZ@#?%/ ~".toCharArray()) {
            helper.assertTrue(SevenSegment.mask(c) == 0, "'" + c + "' renders blank");
        }
        helper.assertTrue(SevenSegment.mask('é') == 0 && SevenSegment.mask('\u0000') == 0, "non-ASCII renders blank");
        helper.assertTrue(SevenSegment.mask('S') == SevenSegment.mask('5') && SevenSegment.mask('-') == SevenSegment.G
                && SevenSegment.mask('_') == SevenSegment.D, "S is 5, dash is g, underscore is d");

        int[] layout = SevenSegment.layout("12.5", 4, true);
        helper.assertTrue(layout.length == 4 && layout[0] == 0 && layout[1] == (SevenSegment.mask('1'))
                && layout[2] == (SevenSegment.mask('2') | SevenSegment.DP) && layout[3] == SevenSegment.mask('5'),
                "right aligned, the point joins the digit before it");
        layout = SevenSegment.layout("1.2", 4, false);
        helper.assertTrue(layout[0] == (SevenSegment.mask('1') | SevenSegment.DP) && layout[1] == SevenSegment.mask('2')
                && layout[2] == 0, "left aligned");
        layout = SevenSegment.layout(".5", 3, false);
        helper.assertTrue(layout[0] == SevenSegment.DP && layout[1] == SevenSegment.mask('5'), "a leading point takes its own digit");
        layout = SevenSegment.layout("123456", 3, false);
        helper.assertTrue(layout.length == 3 && layout[2] == SevenSegment.mask('3'), "too long text is cut at the right");
        layout = SevenSegment.layout("123.", 3, false);
        helper.assertTrue(layout[2] == (SevenSegment.mask('3') | SevenSegment.DP), "a point after the last digit still lights");
        helper.assertTrue(SevenSegment.layout("1..", 3, false)[1] == SevenSegment.DP, "a second point takes a digit");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sixteenSegmentMapCoversPrintableAscii(GameTestHelper helper) {
        for (char c = 33; c <= 126; c++) {
            helper.assertTrue(SixteenSegment.mask(c) != 0 && SixteenSegment.canShow(c), "printable '" + c + "' has a glyph");
            helper.assertTrue(SixteenSegment.mask(c) >> SixteenSegment.SEGMENTS == 0, "glyph '" + c + "' stays in 16 bits");
        }
        helper.assertTrue(SixteenSegment.mask(' ') == 0 && SixteenSegment.mask('é') == 0 && SixteenSegment.mask('\n') == 0,
                "space and non-printable are blank");
        for (char c = 'A'; c <= 'Z'; c++) {
            helper.assertTrue(SixteenSegment.mask(c) == SixteenSegment.mask(Character.toLowerCase(c)), "lowercase " + c);
        }
        // Letters and digits should be told apart except for the O/0 pair.
        for (char a : "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()) {
            for (char b : "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()) {
                if (a < b && !(a == '0' && b == 'O') && !(a == 'O' && b == '0') && !(a == '5' && b == 'S')
                        && !(a == 'S' && b == '5')) {
                    helper.assertTrue(SixteenSegment.mask(a) != SixteenSegment.mask(b), a + " and " + b + " share a glyph");
                }
            }
        }
        int[] layout = SixteenSegment.layout("A.B", 3, false);
        helper.assertTrue(layout[0] == (SixteenSegment.mask('A') | SixteenSegment.DP) && layout[1] == SixteenSegment.mask('B')
                && layout[2] == 0, "decimal point on the 16 segment display");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void lcdFontAndMimicShapesAreWellFormed(GameTestHelper helper) {
        helper.assertTrue(LcdFont.GLYPHS == 95, "ASCII 32..126: " + LcdFont.GLYPHS);
        helper.assertTrue(!LcdFont.pixel(' ', 2, 3) && LcdFont.pixel('A', 0, 6) && LcdFont.pixel('A', 2, 0)
                && !LcdFont.pixel('A', 2, 6), "the A glyph is an A");
        helper.assertTrue(LcdFont.column('é', 0) == LcdFont.column('?', 0), "unknown characters draw as ?");
        for (char c = 32; c <= 126; c++) {
            for (int col = 0; col < LcdFont.WIDTH; col++) {
                helper.assertTrue(LcdFont.column(c, col) >= 0 && LcdFont.column(c, col) < 128, "glyph '" + c + "' is 7 rows tall");
            }
        }
        helper.assertTrue(MimicShape.arms("straight", 0, 8, 1) == (MimicShape.LEFT | MimicShape.RIGHT), "wide straight");
        helper.assertTrue(MimicShape.arms("straight", 0, 1, 8) == (MimicShape.UP | MimicShape.DOWN), "tall straight");
        helper.assertTrue(MimicShape.arms("straight", 0, 3, 3) == (MimicShape.LEFT | MimicShape.RIGHT)
                && MimicShape.arms("straight", 1, 3, 3) == (MimicShape.UP | MimicShape.DOWN), "square straight uses rotation");
        for (int r = 0; r < 4; r++) {
            helper.assertTrue(Integer.bitCount(MimicShape.arms("corner", r, 3, 3)) == 2, "corner has two arms");
            helper.assertTrue(Integer.bitCount(MimicShape.arms("tee", r, 3, 3)) == 3, "tee has three arms");
        }
        helper.assertTrue(MimicShape.arms("tee", 0, 3, 3) == (MimicShape.LEFT | MimicShape.RIGHT | MimicShape.DOWN), "tee 0 opens down");
        helper.assertTrue(MimicShape.arms("corner", 7, 3, 3) == MimicShape.arms("corner", 3, 3, 3), "rotation wraps");
        helper.assertTrue(MimicShape.thickness(4, 2, 8) == 2 && MimicShape.thickness(0, 5, 5) == 1, "thickness is clamped");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void stripChartHistoryIsBoundedThinnedAndRoundTrips(GameTestHelper helper) {
        StripChartData data = new StripChartData();
        FakeHost host = new FakeHost(6, 6);
        data.attach(host);
        data.pens = 2;
        data.spanSeconds = 60;
        long span = data.spanTicks();
        helper.assertTrue(data.points(0) == 0, "starts empty");
        // Far more changes than the capacity, a few ticks apart: stays bounded and inside the window.
        for (int i = 0; i < 5000; i++) {
            data.push(0, 1000L + i * 3L, i);
        }
        helper.assertTrue(data.points(0) <= StripChartData.CAPACITY, "bounded: " + data.points(0));
        long newest = data.time(0, data.points(0) - 1);
        long now = 1000L + 4999 * 3L;
        helper.assertTrue(newest > now - 10 && data.value(0, data.points(0) - 1) == 4999f, "newest point kept");
        helper.assertTrue(data.points(0) >= 2 && data.time(0, 1) > now - span - 10, "at most one point older than the window");
        // Unchanged values add nothing; changes inside one slot overwrite the last point.
        int before = data.points(1);
        data.push(1, 5000, 10f);
        data.push(1, 5000, 10f);
        helper.assertTrue(data.points(1) == before + 1, "a repeated value is not a change");
        data.push(1, 5001, 11f);
        data.push(1, 5001, 12f);
        data.push(1, 5001, 13f);
        helper.assertTrue(data.points(1) == before + 2 && data.value(1, data.points(1) - 1) == 13f,
                "changes inside one time slot overwrite: " + data.points(1));
        data.push(1, 5002, Float.NaN);
        helper.assertTrue(Float.isNaN(data.value(1, data.points(1) - 1)), "no signal is recorded as a gap");
        // Persistence: ages survive a reload and rebase onto the new clock.
        host.time = 20_000;
        CompoundTag saved = data.toTag();
        StripChartData loaded = new StripChartData();
        loaded.load(saved);
        helper.assertTrue(loaded.toTag().equals(saved), "save is a fixed point of load");
        loaded.rebase(40_000);
        long age = 20_000 - data.time(0, data.points(0) - 1);
        helper.assertTrue(40_000 - loaded.time(0, loaded.points(0) - 1) == age, "ages rebase onto the new clock");
        helper.assertTrue(loaded.points(0) == data.points(0) && loaded.value(0, 5) == data.value(0, 5), "values survive");
        helper.assertTrue(loaded.pens == 2 && loaded.spanSeconds == 60, "config survives");
        // Hostile data cannot overflow the buffers.
        CompoundTag hostile = new CompoundTag();
        hostile.putIntArray("Pen1Ages", new int[StripChartData.CAPACITY * 3]);
        hostile.putIntArray("Pen1Values", new int[StripChartData.CAPACITY * 3]);
        hostile.putInt("Pens", 99);
        hostile.putInt("Span", -5);
        StripChartData bounded = new StripChartData();
        bounded.load(hostile);
        helper.assertTrue(bounded.points(0) == StripChartData.CAPACITY && bounded.pens == StripChartData.MAX_PENS
                && bounded.spanSeconds == StripChartData.MIN_SPAN_SECONDS, "load clamps everything");
        helper.succeed();
    }

    /** A host with a settable clock for data-level tests. */
    private static final class FakeHost implements ComponentData.Host {
        long time;
        final int w;
        final int h;

        FakeHost(int w, int h) {
            this.w = w;
            this.h = h;
        }

        @Override
        public long gameTime() {
            return time;
        }

        @Override
        public int cellsWide() {
            return w;
        }

        @Override
        public int cellsHigh() {
            return h;
        }

        @Override
        public void touch() {
        }

        @Override
        public void contentChanged() {
        }
    }

    @GameTest(template = "empty")
    public static void graphicDisplayPatchesSyncOnlyWhatChangedAndStayBounded(GameTestHelper helper) {
        GraphicDisplayData server = new GraphicDisplayData();
        server.attach(new FakeHost(64, 64));
        GraphicDisplayData client = new GraphicDisplayData();
        client.attach(new FakeHost(64, 64));
        helper.assertTrue(server.width() == 128 && server.height() == 128, "4x4 blocks are 128 x 128 px");
        helper.assertTrue(server.getPixel(5, 5) == GraphicDisplayData.BACKGROUND && server.getPixel(200, 0) == -1, "pixel access");
        helper.assertTrue(server.drainPatch() == null, "nothing changed, nothing to send");

        helper.assertTrue(server.setPixel(3, 4, 2) && !server.setPixel(3, 4, 2), "set pixel reports changes");
        helper.assertTrue(!server.setPixel(-1, 0, 2) && !server.setPixel(0, 0, 16) && !server.setPixel(0, 0, -1), "refuses bad input");
        byte[] one = server.drainPatch();
        helper.assertTrue(one != null && one.length < 200, "one pixel sends one tile, not the frame: " + (one == null ? -1 : one.length));
        helper.assertTrue(server.drainPatch() == null, "a patch is drained once");
        client.applyPatch(one);
        helper.assertTrue(client.getPixel(3, 4) == 2 && client.getPixel(4, 4) == GraphicDisplayData.BACKGROUND, "client got the pixel");

        helper.assertTrue(server.fill(100, 100, 20, 20, 7), "fill");
        helper.assertTrue(server.drawText(10, 10, "HI", 1), "text");
        helper.assertTrue(server.blit(60, 60, List.of("0F.1", "..22", "zz")), "blit");
        helper.assertTrue(server.getPixel(60, 60) == 0 && server.getPixel(61, 60) == 15 && server.getPixel(62, 60) == GraphicDisplayData.BACKGROUND
                && server.getPixel(63, 60) == 1 && server.getPixel(62, 61) == 2, "blit hex with transparency");
        helper.assertTrue(server.setPalette(3, 0x123456) && !server.setPalette(3, 0x123456) && !server.setPalette(16, 1), "palette");
        byte[] several = server.drainPatch();
        helper.assertTrue(several != null && several.length < 2000, "a few tiles: " + several.length);
        client.applyPatch(several);
        helper.assertTrue(sameContent(server, client), "client matches after the second patch");
        helper.assertTrue(client.getPalette(3) == 0x123456, "palette synced");

        helper.assertTrue(server.clear(4), "clear");
        byte[] full = server.drainPatch();
        helper.assertTrue(full != null && full.length <= ComponentData.MAX_PATCH && full.length > 8000,
                "a full frame is the whole 8 KiB: " + (full == null ? -1 : full.length));
        client.applyPatch(full);
        helper.assertTrue(sameContent(server, client) && client.getPixel(127, 127) == 4, "full frame applied");

        // Palette-only change.
        helper.assertTrue(server.setPalette(0, 0xABCDEF), "palette only");
        byte[] palette = server.drainPatch();
        helper.assertTrue(palette != null && palette.length < 20, "palette-only patch is tiny: " + palette.length);
        client.applyPatch(palette);
        helper.assertTrue(client.getPalette(0) == 0xABCDEF, "palette-only applied");

        // Malformed patches change nothing and do not throw.
        GraphicDisplayData untouched = new GraphicDisplayData();
        untouched.attach(new FakeHost(64, 64));
        untouched.applyPatch(null);
        untouched.applyPatch(new byte[0]);
        untouched.applyPatch(new byte[] {1});
        untouched.applyPatch(new byte[] {0, -1, -1, -1, -1, -1, -1, -1, -1});
        untouched.applyPatch(new byte[] {2, -1, -1, 1});
        helper.assertTrue(untouched.getPixel(0, 0) == GraphicDisplayData.BACKGROUND, "malformed patches are ignored");

        // Save round trip, including content.
        CompoundTag saved = server.toTag();
        GraphicDisplayData reloaded = new GraphicDisplayData();
        reloaded.load(saved);
        helper.assertTrue(reloaded.toTag().equals(saved) && sameContent(server, reloaded), "graphic data save is a fixed point of load");
        helper.assertTrue(saved.getByteArray("Pixels").length == 128 * 128 / 2, "framebuffer saves as nibbles");

        // Small displays.
        GraphicDisplayData small = new GraphicDisplayData();
        small.attach(new FakeHost(16, 16));
        helper.assertTrue(small.width() == 32 && small.height() == 32, "1 block is 32 px");
        small.fill(0, 0, 1000, 1000, 9);
        helper.assertTrue(small.getPixel(31, 31) == 9 && small.getPixel(32, 31) == -1, "drawing clips to the display");
        helper.succeed();
    }

    private static boolean sameContent(GraphicDisplayData a, GraphicDisplayData b) {
        if (a.width() != b.width() || a.height() != b.height()) {
            return false;
        }
        for (int y = 0; y < a.height(); y++) {
            for (int x = 0; x < a.width(); x++) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) {
                    return false;
                }
            }
        }
        for (int i = 0; i < GraphicDisplayData.PALETTE_SIZE; i++) {
            if (a.getPalette(i) != b.getPalette(i)) {
                return false;
            }
        }
        return true;
    }

    @GameTest(template = "empty")
    public static void contentPayloadCodecRoundTripsAndBoundsSize(GameTestHelper helper) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        byte[] bytes = {1, 2, 3, -4};
        PanelPayloads.Content.STREAM_CODEC.encode(buffer, new PanelPayloads.Content(new BlockPos(5, 6, 7), 9, bytes));
        var decoded = PanelPayloads.Content.STREAM_CODEC.decode(buffer);
        helper.assertTrue(decoded.id() == 9 && decoded.pos().equals(new BlockPos(5, 6, 7))
                && java.util.Arrays.equals(decoded.bytes(), bytes), "content round trip");
        var huge = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        huge.writeBlockPos(BlockPos.ZERO);
        huge.writeVarInt(1);
        huge.writeVarInt(ComponentData.MAX_PATCH + 1);
        boolean rejected = false;
        try {
            PanelPayloads.Content.STREAM_CODEC.decode(huge);
        } catch (RuntimeException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "an oversized content packet must be refused");
        helper.succeed();
    }

    // ---- placement

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void everyNewTypePlacesAtEveryFootprint(GameTestHelper helper) {
        Stage.run(helper, 0, s -> {
            s.wall(4, 4);
            BlockPos anchor = s.at(0, 0);
            PanelBlockEntity be = s.be(anchor);
            int placements = 0;
            for (ComponentType type : newTypes()) {
                helper.assertTrue(type.item() instanceof dev.bwr.mod.panel.component.ComponentItem, type.id() + " has an item");
                List<Footprint> footprints = type.footprints();
                for (int variant = 0; variant < footprints.size(); variant++) {
                    Footprint footprint = footprints.get(variant);
                    var result = s.place(itemOf(type), variant, anchor, 0, 0);
                    helper.assertTrue(result == PanelGroup.Result.OK, type.id() + " " + footprint + ": " + result);
                    PanelComponent component = be.owned().get(be.owned().size() - 1);
                    helper.assertTrue(component.type() == type && component.w() == footprint.w() && component.h() == footprint.h(),
                            type.id() + " footprint " + footprint + " recorded as " + component.w() + "x" + component.h());
                    helper.assertTrue(component.inputCount() == type.inputPorts(component.data()).size()
                            && component.outputCount() == type.outputPorts(component.data()).size(), type.id() + " ports");
                    // A second one on the same spot collides.
                    if (variant == 0) {
                        helper.assertTrue(s.place(itemOf(type), variant, anchor, 0, 0) != PanelGroup.Result.OK,
                                type.id() + " collides with itself");
                    }
                    s.wrench();
                    helper.assertTrue(PanelOps.remove(s.player, anchor, component.id()), type.id() + " " + footprint + " removes");
                    helper.assertTrue(be.owned().isEmpty(), "wall is empty again");
                    placements++;
                }
            }
            helper.assertTrue(placements >= 80, "placed every footprint of every type: " + placements);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void largeDisplaysStraddleAndReserveEveryBlock(GameTestHelper helper) {
        Stage.run(helper, 1, s -> {
            s.wall(4, 4);
            // 64 x 64 graphic display across the whole 4 x 4 wall from the top-left cell.
            helper.assertTrue(s.place(itemOf(ComponentTypes.GRAPHIC_DISPLAY), 3, s.at(0, 0), 0, 0) == PanelGroup.Result.OK,
                    "graphic display 4x4 blocks");
            PanelBlockEntity owner = s.be(s.at(0, 0));
            PanelComponent display = owner.owned().get(0);
            helper.assertTrue(display.w() == 64 && display.h() == 64, "64 x 64 cells");
            helper.assertTrue(((GraphicDisplayData) display.data()).width() == 128, "128 px wide");
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 4; r++) {
                    PanelBlockEntity block = s.be(s.at(c, r));
                    helper.assertTrue(block.claimAt(8, 8) != null && block.claimAt(15, 15) != null
                            && block.claimAt(8, 8).owner().equals(s.at(0, 0)), "block " + c + "," + r + " is reserved");
                }
            }
            helper.assertTrue(s.place(itemOf(ComponentTypes.MIMIC_LINE), 0, s.at(3, 3), 15, 15) != PanelGroup.Result.OK,
                    "nothing fits on the display");
            // A footprint that does not fit the wall is refused.
            s.wrench();
            PanelOps.remove(s.player, s.at(0, 0), display.id());
            helper.assertTrue(s.place(itemOf(ComponentTypes.GRAPHIC_DISPLAY), 3, s.at(0, 0), 1, 0) != PanelGroup.Result.OK,
                    "a 64 cell display cannot start at cell 1 of a 4 block wall");
        });
    }

    // ---- save / reload

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void everyNewTypeSurvivesSaveReloadAndNetworkSync(GameTestHelper helper) {
        Stage.run(helper, 2, s -> {
            s.wall(4, 4);
            BlockPos anchor = s.at(0, 0);
            List<PanelComponent> placed = new ArrayList<>();
            // type, block column, block row, cell x, cell y (default footprints; the graphic display straddles four blocks)
            Object[][] layout = {
                    {ComponentTypes.DIAL_GAUGE, 0, 0, 0, 0},
                    {ComponentTypes.EDGEWISE_GAUGE_VERTICAL, 1, 0, 0, 0},
                    {ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, 1, 3, 12, 5},
                    {ComponentTypes.STRIP_CHART, 0, 0, 0, 15},
                    {ComponentTypes.SEVEN_SEGMENT_DISPLAY, 0, 2, 0, 11},
                    {ComponentTypes.SIXTEEN_SEGMENT_DISPLAY, 1, 2, 8, 11},
                    {ComponentTypes.CHARACTER_LCD, 0, 2, 0, 1},
                    {ComponentTypes.MIMIC_LINE, 0, 3, 0, 5},
                    {ComponentTypes.MIMIC_SYMBOL, 1, 3, 4, 5},
                    {ComponentTypes.GRAPHIC_DISPLAY, 2, 0, 0, 0},
            };
            for (Object[] slot : layout) {
                ComponentType type = (ComponentType) slot[0];
                var result = s.place(itemOf(type), 0, s.at((Integer) slot[1], (Integer) slot[2]), (Integer) slot[3], (Integer) slot[4]);
                helper.assertTrue(result == PanelGroup.Result.OK, type.id() + " placement: " + result);
            }
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 4; r++) {
                    placed.addAll(s.be(s.at(c, r)).owned());
                }
            }
            helper.assertTrue(placed.size() == newTypes().size(), "all ten placed: " + placed.size());

            PanelBlockEntity base = s.be(anchor);
            // Configure each type and push live values.
            for (PanelComponent component : placed) {
                BlockPos where = ownerOf(s, component);
                ComponentType type = component.type();
                int id = component.id();
                if (type == ComponentTypes.DIAL_GAUGE) {
                    helper.assertTrue(s.configure(where, id, config("Min", -50.0, "Max", 150.0, "Units", "MPa", "Damping", 1.5,
                            "Band1Low", 100.0, "Band1High", 120.0, "Band1Color", 0x00FF00), 75.0), "dial");
                } else if (type == ComponentTypes.EDGEWISE_GAUGE_VERTICAL || type == ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL) {
                    helper.assertTrue(s.configure(where, id, config("Min", 10.0, "Max", 20.0, "Units", "bar",
                            "Band2Low", 18.0, "Band2High", 20.0), 12.5, 17.0), "edgewise");
                } else if (type == ComponentTypes.STRIP_CHART) {
                    helper.assertTrue(s.configure(where, id, config("Pens", 3, "Span", 90, "Min", 0.0, "Max", 10.0,
                            "Pen2Color", 0x112233), 3.0, 5.0, 7.0), "strip chart");
                } else if (type == ComponentTypes.SEVEN_SEGMENT_DISPLAY) {
                    helper.assertTrue(s.configure(where, id, config("Format", "PWR {0:5.1f}%", "Color", 0x20E020,
                            "Align", "left"), 42.34), "7 seg");
                } else if (type == ComponentTypes.SIXTEEN_SEGMENT_DISPLAY) {
                    helper.assertTrue(s.configure(where, id, config("Format", "Tag {1}", "Color", 0xFF4040), Double.NaN, 8), "16 seg");
                } else if (type == ComponentTypes.CHARACTER_LCD) {
                    helper.assertTrue(s.configure(where, id, config("Format", "{0:4d}|T={1:.1f}", "Backlight", 0x336699,
                            "Ink", 0xEEEEEE), 17, 1.25), "lcd");
                } else if (type == ComponentTypes.GRAPHIC_DISPLAY) {
                    helper.assertTrue(s.configure(where, id, config("Bezel", 0x445566)), "graphic");
                    GraphicDisplayData data = (GraphicDisplayData) component.data();
                    data.clear(15);
                    data.fill(4, 4, 30, 12, 3);
                    data.drawText(6, 20, "BWR", 0);
                    data.setPalette(5, 0xFEDCBA);
                    data.setPixel(63, 63, 8);
                } else if (type == ComponentTypes.MIMIC_LINE) {
                    helper.assertTrue(s.configure(where, id, config("Shape", "tee", "Rotation", 2, "Thickness", 2, "Color", 0x00AAFF), 1), "line");
                } else if (type == ComponentTypes.MIMIC_SYMBOL) {
                    helper.assertTrue(s.configure(where, id, config("Symbol", "pump", "OnColor", 0xAA0000, "OffColor", 0x00AA00), 0), "symbol");
                }
            }
            // Strip chart history through the real input path.
            PanelComponent chart = find(placed, ComponentTypes.STRIP_CHART);
            PanelBlockEntity chartOwner = s.be(ownerOf(s, chart));
            chartOwner.setInput(chart, 0, 4.0);
            chartOwner.setInput(chart, 1, 6.0);
            helper.assertTrue(((StripChartData) chart.data()).points(0) >= 2 && ((StripChartData) chart.data()).points(2) >= 1,
                    "the chart recorded its pens");
            // CC-style content on the LCD, then reload.
            PanelComponent lcd = find(placed, ComponentTypes.CHARACTER_LCD);
            LcdData lcdData = (LcdData) lcd.data();
            helper.assertTrue(lcdData.write(1, 2, "ready") && lcdData.format.isEmpty(), "write takes over from the format");
            s.configure(ownerOf(s, lcd), lcd.id(), config("Backlight", 0x336699));

            var registries = s.level.registryAccess();
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 4; r++) {
                    PanelBlockEntity block = s.be(s.at(c, r));
                    if (block.ownedCount() + block.foreignClaims().size() == 0) {
                        continue;
                    }
                    CompoundTag saved = block.saveWithoutMetadata(registries);
                    PanelBlockEntity reloaded = new PanelBlockEntity(block.getBlockPos(), block.getBlockState());
                    reloaded.loadWithComponents(saved, registries);
                    helper.assertTrue(reloaded.savePanel(false).equals(block.savePanel(false)),
                            "world save of " + c + "," + r + " is a fixed point of load");
                    PanelBlockEntity client = new PanelBlockEntity(block.getBlockPos(), block.getBlockState());
                    client.applyNetworkState(block.savePanel(true));
                    helper.assertTrue(client.savePanel(false).equals(block.savePanel(false)), "network state matches the saved form");
                    for (PanelComponent original : block.owned()) {
                        PanelComponent copy = reloaded.component(original.id());
                        PanelComponent remote = client.component(original.id());
                        helper.assertTrue(copy.type() == original.type() && copy.label().equals(original.label())
                                && copy.w() == original.w() && copy.h() == original.h(), original.label() + " identity");
                        helper.assertTrue(copy.data().toTag().equals(original.data().toTag()), original.label() + " data");
                        for (int i = 0; i < original.inputCount(); i++) {
                            helper.assertTrue(remote.input(i) == original.input(i)
                                    || Double.isNaN(remote.input(i)) && Double.isNaN(original.input(i)),
                                    original.label() + " client sees input " + i);
                        }
                    }
                }
            }

            // Spot checks on the reloaded values.
            PanelBlockEntity reloadedBase = new PanelBlockEntity(base.getBlockPos(), base.getBlockState());
            reloadedBase.loadWithComponents(base.saveWithoutMetadata(registries), registries);
            for (PanelComponent component : reloadedBase.owned()) {
                ComponentType type = component.type();
                if (type == ComponentTypes.DIAL_GAUGE) {
                    GaugeData data = (GaugeData) component.data();
                    helper.assertTrue(data.min == -50.0 && data.max == 150.0 && data.units.equals("MPa") && data.damping == 1.5
                            && data.bandLow[0] == 100.0 && data.bandColor[0] == 0x00FF00, "dial config");
                } else if (type == ComponentTypes.SEVEN_SEGMENT_DISPLAY) {
                    SegmentDisplayData data = (SegmentDisplayData) component.data();
                    helper.assertTrue(data.format.equals("PWR {0:5.1f}%") && data.color == 0x20E020 && data.align.equals("left"),
                            "7 seg config");
                }
            }
        });
    }

    private static PanelComponent find(List<PanelComponent> components, ComponentType type) {
        for (PanelComponent component : components) {
            if (component.type() == type) {
                return component;
            }
        }
        throw new AssertionError("no " + type.id());
    }

    /** The block position that owns {@code component}, found by searching the wall. */
    private static BlockPos ownerOf(Stage s, PanelComponent component) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                PanelBlockEntity block = s.be(s.at(c, r));
                if (block != null && block.owned().contains(component)) {
                    return s.at(c, r);
                }
            }
        }
        throw new AssertionError("component " + component.label() + " is not on the wall");
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void graphicDisplayContentSurvivesWorldSaveAndTheNetwork(GameTestHelper helper) {
        Stage.run(helper, 3, s -> {
            s.wall(2, 2);
            helper.assertTrue(s.place(itemOf(ComponentTypes.GRAPHIC_DISPLAY), 4, s.at(0, 0), 0, 0) == PanelGroup.Result.OK,
                    "graphic display 32x16 cells");
            PanelBlockEntity owner = s.be(s.at(0, 0));
            PanelComponent display = owner.owned().get(0);
            GraphicDisplayData data = (GraphicDisplayData) display.data();
            helper.assertTrue(data.width() == 64 && data.height() == 32, "2 x 1 blocks is 64 x 32 px");
            data.clear(7);
            data.fill(10, 5, 20, 8, 1);
            data.drawText(2, 20, "OK", 2);
            data.setPalette(1, 0x00FF7F);

            var registries = s.level.registryAccess();
            PanelBlockEntity reloaded = new PanelBlockEntity(owner.getBlockPos(), owner.getBlockState());
            reloaded.loadWithComponents(owner.saveWithoutMetadata(registries), registries);
            GraphicDisplayData copy = (GraphicDisplayData) reloaded.component(display.id()).data();
            helper.assertTrue(sameContent(data, copy), "pixels and palette survive a world save");

            // A client that already has the empty display receives the drawing as patches.
            PanelBlockEntity before = new PanelBlockEntity(owner.getBlockPos(), owner.getBlockState());
            before.applyNetworkState(owner.savePanel(true));
            GraphicDisplayData remote = (GraphicDisplayData) before.component(display.id()).data();
            helper.assertTrue(sameContent(data, remote), "the full state carries the content");
            data.drainPatch();
            data.setPixel(0, 0, 11);
            data.fill(30, 20, 5, 5, 12);
            byte[] patch = data.drainPatch();
            helper.assertTrue(patch != null && patch.length < 600, "small edits send small patches");
            before.applyContent(display.id(), patch);
            helper.assertTrue(sameContent(data, remote), "applying the patch converges");
        });
    }

    // ---- input values drive the picture

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void inputValuesDriveNeedleSegmentsAndPens(GameTestHelper helper) {
        Stage.run(helper, 4, s -> {
            // The components are 12 to 48 cells across, so they are split over two blocks (ids are per block).
            s.wall(3, 2);
            BlockPos pos = s.at(0, 0);
            BlockPos lower = s.at(0, 1);
            PanelBlockEntity be = s.be(pos);
            PanelBlockEntity lowerBe = s.be(lower);
            helper.assertTrue(s.place(itemOf(ComponentTypes.DIAL_GAUGE), 0, pos, 0, 0) == PanelGroup.Result.OK, "dial");
            helper.assertTrue(s.place(itemOf(ComponentTypes.SEVEN_SEGMENT_DISPLAY), 0, pos, 12, 0) == PanelGroup.Result.OK, "7 seg");
            helper.assertTrue(s.place(itemOf(ComponentTypes.CHARACTER_LCD), 0, lower, 0, 0) == PanelGroup.Result.OK, "lcd");
            helper.assertTrue(s.place(itemOf(ComponentTypes.SIXTEEN_SEGMENT_DISPLAY), 6, lower, 0, 8) == PanelGroup.Result.OK, "16 seg");
            PanelComponent dial = be.component(1);
            PanelComponent seven = be.component(2);
            PanelComponent sixteen = lowerBe.component(2);
            PanelComponent lcd = lowerBe.component(1);
            GaugeData gauge = (GaugeData) dial.data();
            s.configure(pos, 1, config("Min", 0.0, "Max", 200.0));
            helper.assertTrue(gauge.fraction(dial.input(0)) == 0.0 && GaugeData.offScale(dial.input(0)), "no signal pegs low and flags off scale");
            be.setInput(dial, 0, 50.0);
            helper.assertTrue(gauge.fraction(dial.input(0)) == 0.25 && !GaugeData.offScale(dial.input(0)), "50 of 200 is a quarter");
            be.setInput(dial, 0, 500.0);
            helper.assertTrue(gauge.fraction(dial.input(0)) == 1.0, "over range pegs high");
            be.setInput(dial, 0, -5.0);
            helper.assertTrue(gauge.fraction(dial.input(0)) == 0.0 && !GaugeData.offScale(dial.input(0)), "under range pegs low without the flag");
            s.configure(pos, 1, config("Min", 5.0, "Max", 5.0));
            helper.assertTrue(gauge.span() == 1.0 && !Double.isNaN(gauge.fraction(7.0)), "a degenerate range cannot divide by zero");

            SegmentDisplayData seg = (SegmentDisplayData) seven.data();
            s.configure(pos, 2, config("Format", "P{0:4.1f}", "Align", "left"));
            helper.assertTrue(seg.digits() == 4 && seg.shown(seven).equals("P----"), "no signal shows dashes: " + seg.shown(seven));
            int[] masks = seg.masks(seven);
            helper.assertTrue(masks.length == 4 && masks[0] == SevenSegment.mask('P') && masks[1] == SevenSegment.G
                    && masks[3] == SevenSegment.G, "dashes are the g segment");
            be.setInput(seven, 0, 12.34);
            helper.assertTrue(seg.shown(seven).equals("P12.3"), "value formatted: " + seg.shown(seven));
            masks = seg.masks(seven);
            helper.assertTrue(masks[1] == SevenSegment.mask('1') && masks[2] == (SevenSegment.mask('2') | SevenSegment.DP)
                    && masks[3] == SevenSegment.mask('3'), "digits and decimal point follow the input");
            be.setInput(seven, 0, 123456.0);
            helper.assertTrue(seg.shown(seven).equals("P OFL"), "overflow shows OFL in the field width: " + seg.shown(seven));

            SegmentDisplayData alpha = (SegmentDisplayData) sixteen.data();
            s.configure(lower, 2, config("Format", "{0:d}-{1}"), 5, Double.NaN);
            helper.assertTrue(alpha.shown(sixteen).equals("5-----"), "unwired input shows dashes: " + alpha.shown(sixteen));
            helper.assertTrue(alpha.digits() == 8 && alpha.masks(sixteen).length == 8, "eight characters");

            LcdData data = (LcdData) lcd.data();
            s.configure(lower, 1, config("Format", "N={0:3d}|{1:5.2f}"), 7, 3.14159);
            String[] rows = data.shownRows(lcd);
            helper.assertTrue(data.rows() == 2 && data.cols() == 16 && rows[0].equals("N=  7") && rows[1].equals(" 3.14"),
                    "lcd rows: '" + rows[0] + "' '" + rows[1] + "'");
            lowerBe.setInput(lcd, 0, 999);
            helper.assertTrue(data.shownRows(lcd)[0].equals("N=999"), "lcd follows its input");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void stripChartRecordsInputChangesThroughTheRealPath(GameTestHelper helper) {
        Stage.run(helper, 5, s -> {
            s.wall(1, 1);
            BlockPos pos = s.at(0, 0);
            PanelBlockEntity be = s.be(pos);
            helper.assertTrue(s.place(itemOf(ComponentTypes.STRIP_CHART), 0, pos, 0, 0) == PanelGroup.Result.OK, "chart");
            PanelComponent chart = be.component(1);
            StripChartData data = (StripChartData) chart.data();
            helper.assertTrue(chart.inputCount() == 1, "one pen, one input");
            s.configure(pos, 1, config("Pens", 4, "Span", 30));
            helper.assertTrue(chart.inputCount() == 4, "four pens, four inputs: " + chart.inputCount());
            helper.assertTrue(data.points(0) == 0, "nothing recorded without a change");
            be.setInput(chart, 0, 1.0);
            be.setInput(chart, 3, 9.0);
            helper.assertTrue(data.points(0) == 1 && data.points(3) == 1 && data.points(1) == 0, "only pens that changed record");
            helper.assertTrue(data.value(3, 0) == 9.0f, "recorded value");
            be.setInput(chart, 0, Double.NaN);
            helper.assertTrue(Float.isNaN(data.value(0, data.points(0) - 1)), "no signal is a gap in the trace");
            s.configure(pos, 1, config("Pens", 1));
            helper.assertTrue(chart.inputCount() == 1 && data.fraction(Double.NaN) == 0.0, "back to one pen");
        });
    }

    // ---- no-signal look and mimic

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void noSignalLooksAreDefinedForEveryNewType(GameTestHelper helper) {
        Stage.run(helper, 6, s -> {
            s.wall(1, 1);
            BlockPos pos = s.at(0, 0);
            PanelBlockEntity be = s.be(pos);
            helper.assertTrue(s.place(itemOf(ComponentTypes.MIMIC_SYMBOL), 0, pos, 0, 0) == PanelGroup.Result.OK, "symbol");
            helper.assertTrue(s.place(itemOf(ComponentTypes.MIMIC_LINE), 2, pos, 0, 8) == PanelGroup.Result.OK, "line");
            helper.assertTrue(s.place(itemOf(ComponentTypes.EDGEWISE_GAUGE_VERTICAL), 0, pos, 10, 0) == PanelGroup.Result.OK, "edgewise");
            PanelComponent symbol = be.component(1);
            PanelComponent line = be.component(2);
            PanelComponent meter = be.component(3);
            MimicSymbolData data = (MimicSymbolData) symbol.data();
            helper.assertTrue(data.colorFor(Double.NaN) == MimicSymbolData.NO_SIGNAL, "no signal is gray");
            helper.assertTrue(data.colorFor(0.0) == data.offColor && data.colorFor(3.0) == data.onColor
                    && data.offColor != data.onColor, "zero and non-zero use the two colours");
            helper.assertTrue(line.source(0) == dev.bwr.mod.panel.component.InputSources.NONE && Double.isNaN(line.input(0)),
                    "an unbound mimic line stays coloured (the model only dims bound lines)");
            helper.assertTrue(GaugeData.offScale(meter.input(0)) && Double.isNaN(meter.input(1)),
                    "an edgewise meter without signal is off scale with no demand marker");
            ((GaugeData) meter.data()).max = 10;
            be.setInput(meter, 1, 5.0);
            helper.assertTrue(((GaugeData) meter.data()).fraction(meter.input(1)) == 0.0 + 0.5, "demand marker position");
            // Config validation of the enums on the mimic pieces.
            s.configure(pos, 1, config("Symbol", "teapot"));
            helper.assertTrue(data.symbol.equals(MimicSymbolData.VALVE), "unknown symbol keeps the default");
            s.configure(pos, 1, config("Symbol", "breaker"));
            helper.assertTrue(data.symbol.equals(MimicSymbolData.BREAKER), "breaker");
            s.configure(pos, 2, config("Shape", "corner", "Thickness", 99, "Rotation", 7));
            MimicLineData lineData = (MimicLineData) line.data();
            helper.assertTrue(lineData.shape.equals("corner") && lineData.thickness == MimicLineData.MAX_THICKNESS
                    && lineData.rotation == 3, "mimic line config is clamped");
        });
    }

    // ---- display Java API

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void displayApiSetsTextWritesLcdAndSendsSmallPatches(GameTestHelper helper) {
        Stage.run(helper, 7, s -> {
            s.wall(3, 2);
            BlockPos pos = s.at(0, 0);
            PanelBlockEntity be = s.be(pos);
            helper.assertTrue(s.place(itemOf(ComponentTypes.NAMEPLATE), 0, pos, 0, 0) == PanelGroup.Result.OK, "nameplate");
            helper.assertTrue(s.place(itemOf(ComponentTypes.SEVEN_SEGMENT_DISPLAY), 0, pos, 0, 4) == PanelGroup.Result.OK, "7 seg");
            helper.assertTrue(s.place(itemOf(ComponentTypes.CHARACTER_LCD), 0, pos, 0, 12) == PanelGroup.Result.OK, "lcd");
            PanelComponent plate = be.component(1);
            PanelComponent seven = be.component(2);
            PanelComponent lcd = be.component(3);
            PanelBlockEntity client = new PanelBlockEntity(pos, be.getBlockState());
            s.configure(pos, 2, config("Format", "{0}"), 3);
            client.applyNetworkState(be.savePanel(true));

            SegmentDisplayData seg = (SegmentDisplayData) seven.data();
            helper.assertTrue(seg.setText("A b-5é z.") && seg.format.isEmpty() && seg.text.equals("A b-5é z."), "setText takes over");
            helper.assertTrue(!seg.setText("A b-5é z."), "same text is not a change");
            int[] masks = seg.masks(seven);
            helper.assertTrue(seg.digits() == 4 && masks.length == 4, "four digits");
            byte[] patch = seg.drainPatch();
            helper.assertTrue(patch != null && patch.length < 200 && seg.drainPatch() == null, "one small patch");
            client.applyContent(2, patch);
            SegmentDisplayData remoteSeg = (SegmentDisplayData) client.component(2).data();
            helper.assertTrue(remoteSeg.text.equals(seg.text) && remoteSeg.format.isEmpty(), "client shows the same text");
            helper.assertTrue(java.util.Arrays.equals(remoteSeg.masks(client.component(2)), masks), "and the same segments");
            helper.assertTrue(seg.setText("x".repeat(500)) && seg.text.length() == SegmentDisplayData.MAX_TEXT, "text is bounded");

            LcdData data = (LcdData) lcd.data();
            helper.assertTrue(data.rows() == 2 && data.cols() == 16, "default lcd is 2 x 16");
            helper.assertTrue(data.write(0, 0, "Hello") && data.write(1, 12, "WORLD!!!") && !data.write(2, 0, "x")
                    && !data.write(0, 16, "x") && !data.write(-1, 0, "x"), "write clips to the display");
            helper.assertTrue(data.line(0).equals("Hello") && data.line(1).equals(" ".repeat(12) + "WORL"), "clipped at the edge: '" + data.line(1) + "'");
            helper.assertTrue(data.write(0, -2, "abcdefg") && data.line(0).equals("cdefg"), "negative columns clip at the left: " + data.line(0));
            helper.assertTrue(data.write(0, 1, "é\n") && data.line(0).startsWith("c??") , "unprintable becomes ?: " + data.line(0));
            byte[] lcdPatch = data.drainPatch();
            helper.assertTrue(lcdPatch != null && lcdPatch.length < 400, "lcd patch is small");
            client.applyContent(3, lcdPatch);
            LcdData remoteLcd = (LcdData) client.component(3).data();
            helper.assertTrue(remoteLcd.line(0).equals(data.line(0)) && remoteLcd.line(1).equals(data.line(1)), "client lcd matches");
            helper.assertTrue(data.setText("one\ntwo\nthree") && data.line(0).equals("one") && data.line(1).equals("two"),
                    "setText splits rows and drops the extra");
            helper.assertTrue(data.clear() && data.line(0).isEmpty() && !data.clear(), "clear");
            s.configure(pos, 3, config("Format", "static"));
            helper.assertTrue(data.shownRows(lcd)[0].equals("static"), "a static format is just text");
            helper.assertTrue(data.write(0, 0, "A") && data.format.isEmpty(), "the program's write replaces the format");

            NameplateData plateData = (NameplateData) plate.data();
            helper.assertTrue(plateData.setText("MAIN STEAM") && plateData.text.equals("MAIN STEAM") && !plateData.setText("MAIN STEAM"),
                    "nameplate setText");
            byte[] platePatch = plateData.drainPatch();
            helper.assertTrue(platePatch != null, "nameplate patch");
            client.applyContent(1, platePatch);
            helper.assertTrue(((NameplateData) client.component(1).data()).text.equals("MAIN STEAM"), "client nameplate updated");
            helper.assertTrue(plateData.setText("y".repeat(99)) && plateData.text.length() == NameplateData.MAX_TEXT, "nameplate text bounded");
        });
    }
}
