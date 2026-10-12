package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelLinkerItem;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.PanelSync;
import dev.bwr.mod.panel.component.ComponentData;
import dev.bwr.mod.panel.component.GraphicDisplayData;
import dev.bwr.mod.panel.component.LcdData;
import dev.bwr.mod.panel.component.SegmentDisplayData;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSource;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.OutputSink;
import dev.bwr.mod.panel.component.OutputSinks;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelDrawTarget;
import dev.bwr.mod.panel.component.PanelTextTarget;
import dev.bwr.mod.panel.component.PortSpec;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.CcSource;
import dev.bwr.mod.panel.wiring.ComponentSource;
import dev.bwr.mod.panel.wiring.PanelSampler;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.panel.wiring.RedstoneSink;
import dev.bwr.mod.panel.wiring.RedstoneSource;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.panel.wiring.WiringOps.BindKind;
import dev.bwr.mod.panel.wiring.WiringOps.Result;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The control panel wiring layer: plant sources and commands, component-to-component wiring, redstone in and
 * out, the linker's validation, saves, and (when CC is present) the {@code bwr_panel} peripheral.
 *
 * <p>The wiring needs input components, and the catalogue of this branch has none, so the tests use
 * {@link TestIoType}, registered with {@link ComponentTypes#registerTestOnly}: it is found by id for saves and
 * placement but is not in the catalogue, so it needs no item, recipe or lang entry.
 *
 * <p>Every test builds at its own far-away fixture, force-loads the chunks, empties the volume first (so a
 * second run on a persistent world starts clean) and releases the chunks in every outcome, pass or fail.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelWiringTests {
    private PanelWiringTests() {
    }

    private static final Direction FACING = Direction.NORTH;
    /** Redstone and the device sit behind and beside the panel; the player stands in front. */
    private static final Direction BACK = FACING.getOpposite();

    // ---- the test-only input component

    /** Two inputs (a, b) and two outputs (x, y); PRESS/RELEASE/SET drive x, TURN drives y. Also a draw target. */
    public static final class TestIoType extends ComponentType {
        public static final ResourceLocation ID = BwrMod.id("test_io");
        private static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(1, 1));
        private static final List<PortSpec> INPUTS = List.of(PortSpec.of("a"), PortSpec.of("b"));
        private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("x"), PortSpec.of("y"));

        TestIoType() {
            super(ID);
        }

        @Override
        public List<Footprint> footprints() {
            return FOOTPRINTS;
        }

        @Override
        public ComponentData createData() {
            return new TestIoData();
        }

        @Override
        public List<PortSpec> inputPorts(ComponentData data) {
            return INPUTS;
        }

        @Override
        public List<PortSpec> outputPorts(ComponentData data) {
            return OUTPUTS;
        }

        @Override
        public String labelPrefix() {
            return "TIO";
        }

        @Override
        public boolean operate(OperateContext context, OperateAction action, double value) {
            switch (action) {
                case PRESS -> context.setOutput(0, 1);
                case RELEASE -> context.setOutput(0, 0);
                case SET -> context.setOutput(0, value);
                case TURN -> context.setOutput(1, value);
            }
            return true;
        }
    }

    /** Text target and a tiny 8 x 4 draw target recording what it was asked. */
    public static final class TestIoData extends ComponentData implements PanelTextTarget, PanelDrawTarget {
        String text = "";
        final int[] pixels = new int[8 * 4];
        int clearedTo = -1;
        String[] blit;
        String written = "";
        int paletteIndex = -1;
        int paletteRgb;
        String drawn = "";

        @Override
        public boolean setText(String value) {
            boolean changed = !text.equals(value);
            text = value;
            return changed;
        }

        @Override
        public int drawWidth() {
            return 8;
        }

        @Override
        public int drawHeight() {
            return 4;
        }

        @Override
        public boolean clear(int color) {
            clearedTo = color;
            java.util.Arrays.fill(pixels, color);
            return true;
        }

        @Override
        public boolean setPixel(int x, int y, int color) {
            if (x < 0 || y < 0 || x >= 8 || y >= 4) {
                return false;
            }
            pixels[y * 8 + x] = color;
            return true;
        }

        @Override
        public boolean fill(int x, int y, int width, int height, int color) {
            boolean any = false;
            for (int j = y; j < y + height; j++) {
                for (int i = x; i < x + width; i++) {
                    any |= setPixel(i, j, color);
                }
            }
            return any;
        }

        @Override
        public boolean drawText(int x, int y, String value, int color) {
            drawn = x + "," + y + ":" + value + ":" + color;
            return true;
        }

        @Override
        public boolean blit(String[] rows) {
            blit = rows;
            return true;
        }

        @Override
        public boolean setPalette(int index, int rgb) {
            paletteIndex = index;
            paletteRgb = rgb;
            return true;
        }

        @Override
        public boolean write(int row, int column, String value) {
            written = row + "," + column + ":" + value;
            return true;
        }

        @Override
        public void save(CompoundTag tag) {
            tag.putString("Text", text);
        }

        @Override
        public void load(CompoundTag tag) {
            text = tag.getString("Text");
        }
    }

    private static final ComponentType TEST_IO = new TestIoType();

    static {
        ComponentTypes.registerTestOnly(TEST_IO);
    }

    // ---- fixture

    /** A cleared, force-loaded stage with a survival fake player in front of the panels, run in timed steps. */
    private static final class Stage {
        final GameTestHelper helper;
        final ServerLevel level;
        /** Top-left block of the panel wall. Columns run toward -X (the right of a north-facing panel). */
        final BlockPos origin;
        final FakePlayer player;
        /** Where devices stand: well inside the force-loaded area and 8 blocks below the wall. */
        final BlockPos devices;

        private final List<Runnable> steps = new ArrayList<>();
        private final List<Integer> delays = new ArrayList<>();

        private Stage(GameTestHelper helper, int slot) {
            this.helper = helper;
            level = helper.getLevel();
            origin = new BlockPos(46_900 + slot * 64, 110, 46_900);
            devices = origin.offset(-12, -8, 0);
            hold(true);
            player = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes(("panel-wiring-test-" + slot).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "panel-wiring-" + slot));
        }

        /**
         * Runs {@code body} on a clean fixture two ticks after the chunks are forced (an earlier run's entities
         * are not accessible before that), then each {@link #then} step in order. The chunks are released and
         * the fixture emptied in every outcome.
         */
        static void run(GameTestHelper helper, int slot, java.util.function.Consumer<Stage> body) {
            Stage s = new Stage(helper, slot);
            helper.runAfterDelay(2, () -> {
                try {
                    s.clear();
                    s.player.setGameMode(GameType.SURVIVAL);
                    s.player.getInventory().clearContent();
                    s.player.setShiftKeyDown(false);
                    s.player.setPos(s.origin.getX() - 1.5, s.origin.getY() - 1.0, s.origin.getZ() - 2.5);
                    body.accept(s);
                } catch (RuntimeException | Error e) {
                    s.release();
                    throw e;
                }
                s.next();
            });
        }

        /** A step to run {@code delay} ticks after the previous one. */
        void then(int delay, Runnable step) {
            delays.add(delay);
            steps.add(step);
        }

        private void next() {
            if (steps.isEmpty()) {
                release();
                helper.succeed();
                return;
            }
            int delay = delays.remove(0);
            Runnable step = steps.remove(0);
            helper.runAfterDelay(delay, () -> {
                try {
                    step.run();
                } catch (RuntimeException | Error e) {
                    release();
                    throw e;
                }
                next();
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
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(24, 12, 12))) {
                item.discard();
            }
        }

        void release() {
            clear();
            hold(false);
        }

        BlockPos col(int column) {
            return origin.relative(FACING.getCounterClockWise(), column);
        }

        BlockPos at(int column, int row) {
            return col(column).below(row);
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

        PanelComponent io(BlockPos block, int cx, int cy) {
            PanelComponent component = PanelOps.placeDirect(level, block, cx, cy, TEST_IO, Footprint.of(1, 1));
            helper.assertTrue(component != null, "test_io did not fit at " + cx + "," + cy);
            return component;
        }

        MainSteamIsolationValveBlockEntity msiv(BlockPos root) {
            var block = BwrBlocks.MSIV.get();
            var state = block.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
            level.setBlock(root, state, 3);
            block.setPlacedBy(level, root, state, null, new ItemStack(block));
            var valve = (MainSteamIsolationValveBlockEntity) level.getBlockEntity(root);
            valve.setDemandOpen(false);
            valve.tickValve(5);
            return valve;
        }

        void removeMsiv(BlockPos root) {
            level.removeBlock(root.above(2), false);
            level.removeBlock(root.above(), false);
            level.removeBlock(root, false);
        }

        /** Puts a linker in the player's main hand, set to {@code device} (or unset). */
        ItemStack linker(BlockPos device) {
            ItemStack stack = new ItemStack(BwrItems.PANEL_LINKER.get());
            if (device != null) {
                stack.set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(level.dimension(), device));
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            return stack;
        }

        void wrench() {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
        }

        Result bind(PanelBlockEntity block, PanelComponent component, boolean output, int port, BindKind kind,
                    BlockPos device, String key, double scale) {
            return WiringOps.bind(player, new PanelPayloads.Bind(block.getBlockPos(), component.id(), output, port,
                    kind.ordinal(), device == null ? BlockPos.ZERO : device, key == null ? "" : key, BlockPos.ZERO, 0, 0,
                    scale));
        }

        /** Binds an output to a plant command with an explicit sink mode and fixed value. */
        Result bindSink(PanelBlockEntity block, PanelComponent component, int port, String key, PlantSink.Mode mode,
                        double fixed, double scale) {
            return WiringOps.bind(player, new PanelPayloads.Bind(block.getBlockPos(), component.id(), true, port,
                    BindKind.PLANT.ordinal(), devices, key, BlockPos.ZERO, 0, 0, scale, mode.ordinal(), fixed));
        }

        /** Applies config fields with the wrench (a Config compound). */
        void configure(PanelBlockEntity block, PanelComponent component, CompoundTag config) {
            wrench();
            CompoundTag edit = new CompoundTag();
            edit.put("Config", config);
            helper.assertTrue(PanelOps.configure(player, block.getBlockPos(), component.id(), edit),
                    "configure refused for " + component.label());
        }

        Result bindComponent(PanelBlockEntity block, PanelComponent component, int port, BlockPos refOwner, int refId,
                             int refOutput) {
            return WiringOps.bind(player, new PanelPayloads.Bind(block.getBlockPos(), component.id(), false, port,
                    BindKind.COMPONENT.ordinal(), BlockPos.ZERO, "", refOwner, refId, refOutput, 1.0));
        }

        void operate(PanelBlockEntity block, PanelComponent component, OperateAction action, double value) {
            helper.assertTrue(PanelOps.operate(player, block.getBlockPos(), component.id(), action, value),
                    "operate refused for " + component.label());
        }
    }

    /** Long enough for the sampler to have run at least once at any sampleTicks setting the tests could meet. */
    private static int sampleWait() {
        return dev.bwr.mod.panel.PanelConfig.sampleTicks() + 3;
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, message);
    }

    // ---- pure pieces

    @GameTest(template = "empty")
    public static void wiringPayloadsRoundTripAndBoundSizes(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess();
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
        var bind = new PanelPayloads.Bind(new BlockPos(1, 2, 3), 7, true, 1, BindKind.PLANT.ordinal(),
                new BlockPos(-50, 64, 9), "dome_pressure", new BlockPos(4, 5, 6), 9, 2, 0.01);
        PanelPayloads.Bind.STREAM_CODEC.encode(buffer, bind);
        check(helper, PanelPayloads.Bind.STREAM_CODEC.decode(buffer).equals(bind), "bind round trip");

        var screen = new PanelPayloads.LinkerScreen(new BlockPos(1, 2, 3), 7, PanelPayloads.LINK_OK, new BlockPos(5, 6, 7),
                "block.bwr.msiv", List.of(new PanelPayloads.SignalEntry("position", "Valve position", "fraction open")),
                List.of(new PanelPayloads.CommandEntry("open", "Open / close", false),
                        new PanelPayloads.CommandEntry("scram", "Scram", true)));
        var out = new RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
        PanelPayloads.LinkerScreen.STREAM_CODEC.encode(out, screen);
        check(helper, PanelPayloads.LinkerScreen.STREAM_CODEC.decode(out).equals(screen), "linker screen round trip");

        var open = new RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
        PanelPayloads.LinkerOpen.STREAM_CODEC.encode(open, new PanelPayloads.LinkerOpen(new BlockPos(9, 9, 9), 4));
        check(helper, PanelPayloads.LinkerOpen.STREAM_CODEC.decode(open).id() == 4, "linker open round trip");

        var huge = new RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
        huge.writeBlockPos(BlockPos.ZERO);
        huge.writeVarInt(0);
        huge.writeVarInt(0);
        huge.writeBlockPos(BlockPos.ZERO);
        huge.writeUtf("");
        huge.writeVarInt(PanelPayloads.MAX_LISTED + 1);
        boolean rejected = false;
        try {
            PanelPayloads.LinkerScreen.STREAM_CODEC.decode(huge);
        } catch (RuntimeException expected) {
            rejected = true;
        }
        check(helper, rejected, "an oversized signal list must be refused");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sourcesAndSinksSurviveAMalformedOrFutureTag(GameTestHelper helper) {
        CompoundTag future = new CompoundTag();
        future.putString("Kind", "plant");
        future.putString("Device", "somewhere");
        check(helper, InputSources.save(InputSources.load(future)).equals(future),
                "a plant source without a position is kept verbatim");
        CompoundTag sink = new CompoundTag();
        sink.putString("Kind", "teleport");
        sink.putInt("Where", 5);
        check(helper, OutputSinks.save(OutputSinks.load(sink)).equals(sink), "an unknown sink kind is kept verbatim");
        CompoundTag brokenSink = new CompoundTag();
        brokenSink.putString("Kind", "plant");
        check(helper, OutputSinks.save(OutputSinks.load(brokenSink)).equals(brokenSink), "a plant sink without a device is kept");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sourcesNeverLoadAChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos far = new BlockPos(9_000_000, 100, 9_000_000);
        check(helper, !level.hasChunkAt(far), "fixture: the far chunk starts unloaded");
        var context = new InputSource.SampleContext(level, null, null, 0);
        check(helper, Double.isNaN(new PlantSource(far, "dome_pressure").sample(context)), "unloaded device reads no signal");
        new PlantSink(far, "open", 1).fire(new OutputSink.FireContext(level, null, null, 0), 1);
        check(helper, !level.hasChunkAt(far), "neither the source nor the sink loaded the chunk");
        helper.succeed();
    }

    // ---- plant source

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void plantSourceReadsADeviceAndGoesNaNWhenItIsRemoved(GameTestHelper helper) {
        Stage.run(helper, 0, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent io = s.io(block.getBlockPos(), 2, 2);
            var valve = s.msiv(s.devices);
            s.linker(s.devices);

            check(helper, s.bind(block, io, false, 0, BindKind.PLANT, s.devices, "demand_open", 1) == Result.OK,
                    "binding a plant signal");
            check(helper, io.source(0) instanceof PlantSource plant && plant.device().equals(s.devices)
                    && plant.key().equals("demand_open"), "the source stores the absolute position and key");
            check(helper, io.input(0) == 0.0, "bound at once, closed demand reads 0: " + io.input(0));
            check(helper, PanelSampler.isTracked(block), "a panel with a plant input is sampled");

            valve.setDemandOpen(true);
            s.then(sampleWait(), () -> {
                check(helper, io.input(0) == 1.0, "the sampler picked up the new value: " + io.input(0));
                s.removeMsiv(s.devices);
            });
            s.then(sampleWait(), () -> {
                check(helper, Double.isNaN(io.input(0)), "a removed device reads no signal: " + io.input(0));
                check(helper, PanelSampler.isTracked(block), "still sampled: the device may come back");
                s.msiv(s.devices).setDemandOpen(true);
            });
            s.then(sampleWait(), () -> check(helper, io.input(0) == 1.0, "a rebuilt device is read again: " + io.input(0)));
            s.then(1, () -> {
                check(helper, s.bind(block, io, false, 0, BindKind.NONE, null, null, 1) == Result.OK, "unbinding");
                check(helper, !PanelSampler.isTracked(block), "a panel with no live input is not sampled");
                check(helper, Double.isNaN(io.input(0)), "an unbound input reads no signal");
            });
        });
    }

    // ---- plant command

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void plantCommandOpensAPoweredMsivAndRecordsTheDemandWhenUnpowered(GameTestHelper helper) {
        Stage.run(helper, 1, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent io = s.io(block.getBlockPos(), 2, 2);
            var valve = s.msiv(s.devices);
            s.linker(s.devices);

            check(helper, s.bind(block, io, true, 0, BindKind.PLANT, s.devices, "open", 1) == Result.OK, "binding the command");
            check(helper, !valve.isComputerControlled() && !valve.isDemandOpen(),
                    "binding a command does not act: nothing happens until a player operates the control");
            PanelBlockEntity copy = (PanelBlockEntity) BlockEntity.loadStatic(block.getBlockPos(), block.getBlockState(),
                    block.saveWithFullMetadata(s.level.registryAccess()), s.level.registryAccess());
            copy.setLevel(s.level);
            copy.loadPanel(block.savePanel(false));
            check(helper, !valve.isComputerControlled(), "loading a panel does not fire its sinks");

            // Unpowered: like CC's open(), the demand is recorded and the valve opens when power returns.
            s.operate(block, io, OperateAction.SET, 1);
            check(helper, valve.isDemandOpen() && valve.isComputerControlled(),
                    "an unpowered MSIV records the open demand like CC's open()");
            for (int i = 0; i < 40; i++) {
                valve.tickValve(.05);
            }
            check(helper, valve.getPosition() == 0.0, "no power, no movement: " + valve.getPosition());
            valve.energy().receiveEnergy(20_000, false);
            for (int i = 0; i < 90; i++) {
                valve.tickValve(.05);
            }
            check(helper, valve.getPosition() == 1.0, "power returned, the recorded demand opened it: " + valve.getPosition());

            // Powered: a change of the control closes it and opens it again.
            s.operate(block, io, OperateAction.SET, 0);
            check(helper, !valve.isDemandOpen(), "the control closed it");
            s.operate(block, io, OperateAction.PRESS, 0);
            check(helper, valve.isDemandOpen(), "a press (output 1) opens a powered valve");
            // A non-operating fan-out target, scaled: the same output also reaches a second command.
            check(helper, s.bind(block, io, true, 0, BindKind.PLANT, s.devices, "open", 1) == Result.OK
                    && io.sinks(0).size() == 2, "outputs fan out to several sinks");
            check(helper, s.bind(block, io, true, 0, BindKind.NONE, null, null, 1) == Result.OK && io.sinks(0).isEmpty(),
                    "clearing removes every sink");
            s.removeMsiv(s.devices);
        });
    }

    // ---- component to component

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void componentToComponentWiring(GameTestHelper helper) {
        Stage.run(helper, 2, s -> {
            s.wall(2, 1);
            PanelBlockEntity left = s.be(s.at(0, 0));
            PanelBlockEntity right = s.be(s.at(1, 0));
            PanelComponent control = s.io(left.getBlockPos(), 1, 1);
            PanelComponent shown = s.io(right.getBlockPos(), 3, 3);
            s.linker(null);

            check(helper, s.bindComponent(right, shown, 0, left.getBlockPos(), control.id(), 0) == Result.OK,
                    "binding across two blocks of one group");
            check(helper, shown.source(0) instanceof ComponentSource, "stored as a component source");
            check(helper, Double.isNaN(shown.input(0)) == false && shown.input(0) == 0.0,
                    "reads the control's current output at once: " + shown.input(0));
            check(helper, !PanelSampler.isTracked(right), "component sources are pushed, not polled");

            s.operate(left, control, OperateAction.SET, 7);
            check(helper, shown.input(0) == 7.0, "operating the control updates the reader: " + shown.input(0));
            control.setLabel("RENAMED");
            s.operate(left, control, OperateAction.SET, 3);
            check(helper, shown.input(0) == 3.0, "the reference survives a rename: " + shown.input(0));
            s.operate(left, control, OperateAction.TURN, 9);
            check(helper, shown.input(0) == 3.0, "the other output does not reach it: " + shown.input(0));

            // Refusals.
            check(helper, s.bindComponent(left, control, 0, left.getBlockPos(), control.id(), 0) == Result.NO_SUCH_COMPONENT,
                    "a component cannot read itself");
            check(helper, s.bindComponent(right, shown, 1, left.getBlockPos(), control.id(), 5) == Result.BAD_PORT,
                    "no such output");
            check(helper, s.bindComponent(right, shown, 1, left.getBlockPos(), 999, 0) == Result.NO_SUCH_COMPONENT,
                    "no such component");
            BlockPos elsewhere = s.at(0, 4);
            s.level.setBlock(elsewhere, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, FACING), 3);
            check(helper, s.bindComponent(right, shown, 1, elsewhere, control.id(), 0) == Result.NOT_IN_GROUP,
                    "a block outside the group is refused");

            // Removing the source component turns the reader to no signal.
            s.wrench();
            check(helper, PanelOps.remove(s.player, left.getBlockPos(), control.id()), "wrench removal");
            check(helper, Double.isNaN(shown.input(0)), "a removed source reads no signal: " + shown.input(0));
        });
    }

    // ---- redstone

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void redstoneInAndOut(GameTestHelper helper) {
        Stage.run(helper, 3, s -> {
            s.wall(1, 1);
            BlockPos pos = s.at(0, 0);
            PanelBlockEntity block = s.be(pos);
            PanelComponent io = s.io(pos, 2, 2);
            s.linker(null);
            BlockPos back = pos.relative(BACK);

            // Input: strength at the back face, picked up by the sampler (no neighbour update) ...
            check(helper, s.bind(block, io, false, 0, BindKind.REDSTONE, null, null, 1) == Result.OK, "binding redstone in");
            check(helper, io.source(0) instanceof RedstoneSource && PanelSampler.isTracked(block), "sampled while it has redstone input");
            check(helper, io.input(0) == 0.0, "no power yet: " + io.input(0));
            s.level.setBlock(back, Blocks.REDSTONE_BLOCK.defaultBlockState(), 2);
            s.then(sampleWait(), () -> {
                check(helper, io.input(0) == 15.0, "a redstone block behind reads 15: " + io.input(0));
                check(helper, s.level.getSignal(pos, FACING) == 0, "reading input is not emitting output");
                s.level.setBlock(back, Blocks.AIR.defaultBlockState(), 3);
                check(helper, io.input(0) == 0.0, "a neighbour update reads at once: " + io.input(0));
                // ... other faces do not count.
                s.level.setBlock(pos.relative(Direction.EAST), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                check(helper, io.input(0) == 0.0, "only the back face is read: " + io.input(0));
                s.level.setBlock(pos.relative(Direction.EAST), Blocks.AIR.defaultBlockState(), 3);

                // Output: a control with a redstone sink drives a lamp behind the panel.
                check(helper, s.bind(block, io, true, 0, BindKind.REDSTONE, null, null, 15) == Result.OK,
                        "binding redstone out");
                s.level.setBlock(back, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
                check(helper, !s.level.getBlockState(back).getValue(RedstoneLampBlock.LIT), "lamp starts dark");
                s.operate(block, io, OperateAction.SET, 1);
                check(helper, s.level.getSignal(pos, FACING) == 15, "emits 1 x 15 from the back face: " + s.level.getSignal(pos, FACING));
                check(helper, s.level.getSignal(pos, Direction.EAST) == 0 && s.level.getSignal(pos, Direction.WEST) == 0
                        && s.level.getSignal(pos, BACK) == 0, "nothing is emitted from the other faces");
                check(helper, s.level.getBlockState(back).getValue(RedstoneLampBlock.LIT), "the lamp behind lit at once");
                s.operate(block, io, OperateAction.SET, 0);
                check(helper, s.level.getSignal(pos, FACING) == 0, "operated back to zero");
            });
            s.then(8, () -> {
                check(helper, !s.level.getBlockState(back).getValue(RedstoneLampBlock.LIT), "the lamp went dark");
                s.operate(block, io, OperateAction.SET, 1);
                check(helper, s.level.getSignal(pos, FACING) == 15, "lit again");
                s.wrench();
                check(helper, PanelOps.remove(s.player, block.getBlockPos(), io.id()), "removing the control");
                check(helper, s.level.getSignal(pos, FACING) == 0, "a removed control stops emitting");
            });
        });
    }

    // ---- linker

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void linkerStoresDevicesAndRefusesBadBindings(GameTestHelper helper) {
        Stage.run(helper, 4, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent io = s.io(block.getBlockPos(), 2, 2);
            s.msiv(s.devices);

            // Storing: sneak-click a device; not a plain block, not without sneaking.
            ItemStack empty = s.linker(null);
            PanelLinkerItem item = (PanelLinkerItem) empty.getItem();
            s.level.setBlock(s.devices.east(3), Blocks.STONE.defaultBlockState(), 3);
            UseOnContext onStone = new UseOnContext(s.level, s.player, InteractionHand.MAIN_HAND, empty,
                    new BlockHitResult(Vec3.atCenterOf(s.devices.east(3)), Direction.UP, s.devices.east(3), false));
            UseOnContext onValve = new UseOnContext(s.level, s.player, InteractionHand.MAIN_HAND, empty,
                    new BlockHitResult(Vec3.atCenterOf(s.devices.above()), Direction.UP, s.devices.above(), false));
            item.useOn(onValve);
            check(helper, PanelLinkerItem.linked(empty) == null, "a plain click does not store");
            s.player.setShiftKeyDown(true);
            item.useOn(onStone);
            check(helper, PanelLinkerItem.linked(empty) == null, "stone reports no signals, nothing stored");
            item.useOn(onValve);
            GlobalPos stored = PanelLinkerItem.linked(empty);
            check(helper, stored != null && stored.pos().equals(s.devices.above()) && stored.dimension().equals(s.level.dimension()),
                    "sneak-click on any cell of a device stores its dimension and position: " + stored);
            s.player.setShiftKeyDown(false);

            // Refusals, in the order the server checks them.
            s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            check(helper, s.bind(block, io, false, 0, BindKind.REDSTONE, null, null, 1) == Result.NOT_HOLDING_LINKER,
                    "no linker, no binding");
            s.linker(null);
            check(helper, s.bind(block, io, false, 0, BindKind.PLANT, s.devices, "position", 1) == Result.NO_LINK,
                    "a linker with no device cannot bind a plant signal");
            check(helper, s.bind(block, io, false, 0, BindKind.REDSTONE, null, null, 1) == Result.OK,
                    "redstone needs no device");

            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_LINKER.get()));
            s.player.getMainHandItem().set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(Level.NETHER, s.devices));
            check(helper, s.bind(block, io, false, 0, BindKind.PLANT, s.devices, "position", 1) == Result.WRONG_DIMENSION,
                    "a device from another dimension is refused");

            s.linker(s.devices);
            check(helper, s.bind(block, io, false, 0, BindKind.PLANT, s.devices.east(), "position", 1) == Result.DEVICE_MISMATCH,
                    "the client cannot name a device other than the linker's");
            BlockPos beyond = s.origin.offset(-(dev.bwr.mod.panel.PanelConfig.linkRange() + 20), 0, 0);
            s.linker(beyond);
            check(helper, s.bind(block, io, false, 0, BindKind.PLANT, beyond, "position", 1) == Result.OUT_OF_RANGE,
                    "beyond the configured range (" + dev.bwr.mod.panel.PanelConfig.linkRange() + ") is refused");
            BlockPos empty2 = s.origin.offset(-20, 0, 0);
            s.linker(empty2);
            check(helper, s.bind(block, io, false, 0, BindKind.PLANT, empty2, "position", 1) == Result.NO_DEVICE,
                    "in range but nothing there");
            s.linker(s.devices);
            check(helper, s.bind(block, io, false, 0, BindKind.PLANT, s.devices, "no_such_signal", 1) == Result.NO_SUCH_KEY,
                    "a signal the device does not have");
            check(helper, s.bind(block, io, true, 0, BindKind.PLANT, s.devices, "position", 1) == Result.NO_SUCH_KEY,
                    "position is a signal, not a command");
            check(helper, s.bind(block, io, true, 0, BindKind.PLANT, s.devices, "open", 0) == Result.BAD_VALUE
                    && s.bind(block, io, true, 0, BindKind.PLANT, s.devices, "open", Double.NaN) == Result.BAD_VALUE,
                    "a zero or non-finite scale is refused");
            check(helper, s.bind(block, io, false, 5, BindKind.REDSTONE, null, null, 1) == Result.BAD_PORT, "no such input");
            check(helper, s.bind(block, io, true, -1, BindKind.NONE, null, null, 1) == Result.BAD_PORT, "no such output");
            check(helper, WiringOps.bind(s.player, new PanelPayloads.Bind(block.getBlockPos(), io.id(), false, 0, 99,
                    BlockPos.ZERO, "", BlockPos.ZERO, 0, 0, 1)) == Result.BAD_VALUE, "an unknown kind");
            check(helper, WiringOps.bind(s.player, new PanelPayloads.Bind(block.getBlockPos(), 999, false, 0,
                    BindKind.CC.ordinal(), BlockPos.ZERO, "", BlockPos.ZERO, 0, 0, 1)) == Result.NO_SUCH_COMPONENT,
                    "an unknown component");
            for (int i = 0; i < WiringOps.MAX_SINKS; i++) {
                check(helper, s.bind(block, io, true, 1, BindKind.PLANT, s.devices, "open", 1) == Result.OK, "sink " + i);
            }
            check(helper, s.bind(block, io, true, 1, BindKind.PLANT, s.devices, "open", 1) == Result.TOO_MANY,
                    "an output fans out to at most " + WiringOps.MAX_SINKS);

            // Reach.
            s.linker(s.devices);
            check(helper, s.bind(block, io, false, 1, BindKind.PLANT, s.devices, "position", 1) == Result.OK, "valid binding");
            s.player.setPos(s.origin.getX() + 300, s.origin.getY(), s.origin.getZ());
            check(helper, s.bind(block, io, false, 1, BindKind.NONE, null, null, 1) == Result.OUT_OF_REACH,
                    "a player out of reach binds nothing");
            check(helper, io.source(1) instanceof PlantSource, "and the earlier binding stands");
            check(helper, WiringOps.screenFor(s.player, block.getBlockPos(), io.id()) == null, "no screen for a distant player");
            s.player.setPos(s.origin.getX() - 1.5, s.origin.getY() - 1.0, s.origin.getZ() - 2.5);
            var screen = WiringOps.screenFor(s.player, block.getBlockPos(), io.id());
            check(helper, screen != null && screen.status() == PanelPayloads.LINK_OK && screen.device().equals(s.devices)
                            && screen.signals().stream().anyMatch(e -> e.key().equals("position"))
                            && screen.commands().stream().anyMatch(e -> e.key().equals("open") && !e.pulse()),
                    "the screen lists the linked device's signals and commands: " + screen);
            s.linker(beyond);
            var far = WiringOps.screenFor(s.player, block.getBlockPos(), io.id());
            check(helper, far != null && far.status() == PanelPayloads.LINK_OUT_OF_RANGE && far.signals().isEmpty(),
                    "an out-of-range device is reported, not listed");
            s.linker(null);
            check(helper, WiringOps.screenFor(s.player, block.getBlockPos(), io.id()).status() == PanelPayloads.LINK_NONE,
                    "no device linked is reported");
            s.removeMsiv(s.devices);
        });
    }

    // ---- saves

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void bindingsSaveAndReloadAsAbsolutePositions(GameTestHelper helper) {
        Stage.run(helper, 5, s -> {
            s.wall(2, 1);
            PanelBlockEntity left = s.be(s.at(0, 0));
            PanelBlockEntity right = s.be(s.at(1, 0));
            PanelComponent control = s.io(left.getBlockPos(), 1, 1);
            PanelComponent shown = s.io(right.getBlockPos(), 3, 3);
            var valve = s.msiv(s.devices);
            s.linker(s.devices);

            check(helper, s.bind(right, shown, false, 0, BindKind.PLANT, s.devices, "position", 1) == Result.OK, "plant in");
            check(helper, s.bindComponent(right, shown, 1, left.getBlockPos(), control.id(), 1) == Result.OK, "component in");
            check(helper, s.bind(left, control, false, 0, BindKind.REDSTONE, null, null, 1) == Result.OK, "redstone in");
            check(helper, s.bind(left, control, false, 1, BindKind.CC, null, null, 1) == Result.OK, "cc in (awaiting)");
            check(helper, s.bind(left, control, true, 0, BindKind.PLANT, s.devices, "open", 0.5) == Result.OK, "plant out");
            check(helper, s.bind(left, control, true, 0, BindKind.REDSTONE, null, null, 15) == Result.OK, "redstone out");
            check(helper, s.bind(left, control, true, 1, BindKind.PLANT, s.devices, "open", 1) == Result.OK, "second output");
            check(helper, ((CcSource) control.source(1)).awaiting() && Double.isNaN(control.input(1)),
                    "an unset CC input is awaiting and reads no signal");
            check(helper, dev.bwr.mod.panel.wiring.PanelWiring.setCc(left, control, 1, 12.5) && control.input(1) == 12.5,
                    "setting a CC input");
            // An unknown future sink and source stay put.
            CompoundTag futureSink = new CompoundTag();
            futureSink.putString("Kind", "teleport");
            futureSink.putInt("Where", 5);
            control.addSink(1, OutputSinks.load(futureSink));

            CompoundTag saved = left.savePanel(false);
            CompoundTag savedRight = right.savePanel(false);
            check(helper, PanelComponent.load(control.save()).save().equals(control.save()), "component round trip");

            // The block entity itself, through the same NBT the chunk uses.
            PanelBlockEntity copyLeft = copyOf(s, left);
            PanelBlockEntity copyRight = copyOf(s, right);
            check(helper, copyLeft.savePanel(false).equals(saved), "left block round trip:\n" + copyLeft.savePanel(false) + "\n" + saved);
            check(helper, copyRight.savePanel(false).equals(savedRight), "right block round trip");
            PanelComponent reloaded = copyLeft.component(control.id());
            check(helper, reloaded.source(1) instanceof CcSource cc && cc.value() == 12.5, "the CC value is saved");
            check(helper, reloaded.sinks(0).size() == 2 && reloaded.sinks(0).get(0) instanceof PlantSink plant
                    && plant.device().equals(s.devices) && plant.scale() == 0.5
                    && reloaded.sinks(0).get(1) instanceof RedstoneSink redstone && redstone.scale() == 15.0,
                    "sinks reload with their absolute device position and scale");
            check(helper, reloaded.sinks(1).size() == 2 && OutputSinks.save(reloaded.sinks(1).get(1)).equals(OutputSinks.save(
                    control.sinks(1).get(1))), "the unknown sink survived");
            PanelComponent reloadedShown = copyRight.component(shown.id());
            check(helper, reloadedShown.source(0) instanceof PlantSource p && p.device().equals(s.devices) && p.key().equals("position")
                            && reloadedShown.source(1) instanceof ComponentSource c && c.owner().equals(left.getBlockPos())
                            && c.componentId() == control.id() && c.output() == 1,
                    "sources reload with absolute positions");
            check(helper, !valve.isComputerControlled(), "none of this fired a sink");
            s.removeMsiv(s.devices);
        });
    }

    private static PanelBlockEntity copyOf(Stage s, PanelBlockEntity be) {
        PanelBlockEntity copy = (PanelBlockEntity) BlockEntity.loadStatic(be.getBlockPos(), be.getBlockState(),
                be.saveWithFullMetadata(s.level.registryAccess()), s.level.registryAccess());
        s.helper.assertTrue(copy != null, "block entity reload");
        return copy;
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void savingTheWrenchScreenKeepsLinkerBindings(GameTestHelper helper) {
        Stage.run(helper, 6, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent io = s.io(block.getBlockPos(), 2, 2);
            s.linker(null);
            check(helper, s.bind(block, io, false, 0, BindKind.REDSTONE, null, null, 1) == Result.OK, "redstone in");
            check(helper, s.bind(block, io, false, 1, BindKind.CC, null, null, 1) == Result.OK, "cc in");

            // What the wrench screen sends for linker-made inputs, plus a label change.
            CompoundTag edit = new CompoundTag();
            edit.putString("Label", "RENAMED");
            var inputs = new net.minecraft.nbt.ListTag();
            for (int i = 0; i < 2; i++) {
                CompoundTag keep = new CompoundTag();
                keep.putString("Kind", "keep");
                inputs.add(keep);
            }
            edit.put("Inputs", inputs);
            s.wrench();
            check(helper, PanelOps.configure(s.player, block.getBlockPos(), io.id(), edit), "configure accepted");
            check(helper, io.label().equals("RENAMED"), "the label changed");
            check(helper, io.source(0) instanceof RedstoneSource && io.source(1) instanceof CcSource,
                    "the wrench cannot unwire a linker binding through 'keep'");

            // And the generic path still cannot create linker kinds.
            CompoundTag forged = new CompoundTag();
            var forgedList = new net.minecraft.nbt.ListTag();
            CompoundTag plant = new CompoundTag();
            plant.putString("Kind", "plant");
            plant.putInt("X", 1);
            plant.putInt("Y", 2);
            plant.putInt("Z", 3);
            plant.putString("Key", "position");
            forgedList.add(plant);
            forged.put("Inputs", forgedList);
            check(helper, PanelOps.configure(s.player, block.getBlockPos(), io.id(), forged), "configure accepted");
            check(helper, io.source(0) instanceof RedstoneSource, "a forged plant source through configure is ignored");
        });
    }

    // ---- CC:Tweaked

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void ccPanelPeripheralSetsGetsAndRaisesInputEvents(GameTestHelper helper) {
        if (!BwrMod.isComputerCraftPresent()) {
            helper.succeed();
            return;
        }
        Stage.run(helper, 7, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent io = s.io(block.getBlockPos(), 2, 2);
            PanelComponent plate = PanelOps.placeDirect(s.level, block.getBlockPos(), 5, 5,
                    ComponentTypes.NAMEPLATE, Footprint.of(4, 1));
            s.linker(null);
            check(helper, s.bind(block, io, false, 0, BindKind.CC, null, null, 1) == Result.OK, "cc in");
            PanelCcChecks.run(helper, s.level, block, io, plate);
        });
    }

    // ---- momentary plant sinks

    @GameTest(template = "empty")
    public static void momentarySinkPayloadAndSaveRoundTripAndOldSavesFollow(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess();
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
        var bind = new PanelPayloads.Bind(new BlockPos(1, 2, 3), 7, true, 1, BindKind.PLANT.ordinal(),
                new BlockPos(-50, 64, 9), "open", BlockPos.ZERO, 0, 0, 1.0, PlantSink.Mode.MOMENTARY.ordinal(), 0.0);
        PanelPayloads.Bind.STREAM_CODEC.encode(buffer, bind);
        var decoded = PanelPayloads.Bind.STREAM_CODEC.decode(buffer);
        check(helper, decoded.equals(bind) && decoded.sinkMode() == 1, "momentary bind round trip");
        var legacy = new PanelPayloads.Bind(new BlockPos(1, 2, 3), 7, true, 1, BindKind.PLANT.ordinal(),
                BlockPos.ZERO, "open", BlockPos.ZERO, 0, 0, 2.0);
        check(helper, legacy.sinkMode() == PlantSink.Mode.FOLLOW.ordinal() && legacy.fixed() == 0.0,
                "the short constructor follows the output");
        var entry = new PanelPayloads.CommandEntry("open", "Open / close", false, dev.bwr.mod.signal.Kind.BOOLEAN.ordinal(), 0, 1);
        var screen = new PanelPayloads.LinkerScreen(BlockPos.ZERO, 1, PanelPayloads.LINK_OK, BlockPos.ZERO, "x",
                List.of(), List.of(entry));
        var out = new RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
        PanelPayloads.LinkerScreen.STREAM_CODEC.encode(out, screen);
        check(helper, PanelPayloads.LinkerScreen.STREAM_CODEC.decode(out).equals(screen), "command kind and range round trip");

        BlockPos device = new BlockPos(5, 6, 7);
        PlantSink momentary = new PlantSink(device, "open", 1.0, PlantSink.Mode.MOMENTARY, 0.0);
        CompoundTag saved = OutputSinks.save(momentary);
        check(helper, saved.getString("Mode").equals("momentary") && saved.getDouble("Fixed") == 0.0, "mode and fixed value are saved");
        OutputSink reloaded = OutputSinks.load(saved);
        check(helper, reloaded instanceof PlantSink p && p.mode() == PlantSink.Mode.MOMENTARY && p.fixed() == 0.0
                && p.device().equals(device) && OutputSinks.save(reloaded).equals(saved), "momentary sink reloads as a fixed point");
        PlantSink one = new PlantSink(device, "open", 1.0, PlantSink.Mode.MOMENTARY, 1.0);
        check(helper, OutputSinks.load(OutputSinks.save(one)) instanceof PlantSink p && p.fixed() == 1.0, "fixed 1 reloads");

        // A follow sink saves exactly as before (no Mode key), and a save from before the mode loads as follow.
        CompoundTag follow = OutputSinks.save(new PlantSink(device, "open", 0.5));
        check(helper, !follow.contains("Mode") && !follow.contains("Fixed"), "follow saves no mode keys");
        CompoundTag old = new CompoundTag();
        old.putString("Kind", "plant");
        old.putInt("X", 5);
        old.putInt("Y", 6);
        old.putInt("Z", 7);
        old.putString("Key", "open");
        old.putDouble("Scale", 0.25);
        OutputSink loaded = OutputSinks.load(old);
        check(helper, loaded instanceof PlantSink p && p.mode() == PlantSink.Mode.FOLLOW && p.scale() == 0.25
                && OutputSinks.save(loaded).equals(old), "an old save loads as FOLLOW and re-saves unchanged");

        // Malformed momentary data is kept verbatim and inert rather than guessed at.
        CompoundTag noFixed = old.copy();
        noFixed.putString("Mode", "momentary");
        check(helper, OutputSinks.save(OutputSinks.load(noFixed)).equals(noFixed) && !(OutputSinks.load(noFixed) instanceof PlantSink),
                "momentary without a fixed value is preserved");
        CompoundTag future = noFixed.copy();
        future.putString("Mode", "toggle");
        future.putDouble("Fixed", 1.0);
        check(helper, OutputSinks.save(OutputSinks.load(future)).equals(future) && !(OutputSinks.load(future) instanceof PlantSink),
                "an unknown future mode is preserved");
        CompoundTag nan = noFixed.copy();
        nan.putDouble("Fixed", Double.NaN);
        check(helper, !(OutputSinks.load(nan) instanceof PlantSink), "a non-finite fixed value is not trusted");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void momentarySinkFiresOnTheRiseOnlyAndFollowIsUnchanged(GameTestHelper helper) {
        Stage.run(helper, 10, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent io = s.io(block.getBlockPos(), 2, 2);
            var valve = s.msiv(s.devices);
            valve.energy().receiveEnergy(20_000, false);
            s.linker(s.devices);

            // Validation, server side.
            check(helper, s.bindSink(block, io, 0, "open", PlantSink.Mode.MOMENTARY, Double.NaN, 1) == Result.BAD_VALUE,
                    "a non-finite fixed value is refused");
            check(helper, s.bindSink(block, io, 0, "open", PlantSink.Mode.MOMENTARY, 2, 1) == Result.BAD_VALUE
                    && s.bindSink(block, io, 0, "open", PlantSink.Mode.MOMENTARY, -1, 1) == Result.BAD_VALUE,
                    "a fixed value outside the command's 0 to 1 range is refused");
            check(helper, WiringOps.bind(s.player, new PanelPayloads.Bind(block.getBlockPos(), io.id(), true, 0,
                    BindKind.PLANT.ordinal(), s.devices, "open", BlockPos.ZERO, 0, 0, 1, 9, 1)) == Result.BAD_VALUE,
                    "an unknown sink mode is refused");
            check(helper, s.bindSink(block, io, 0, "position", PlantSink.Mode.MOMENTARY, 1, 1) == Result.NO_SUCH_KEY,
                    "momentary still needs a real command");
            check(helper, io.sinks(0).isEmpty(), "refusals stored nothing");
            check(helper, s.bindSink(block, io, 0, "open", PlantSink.Mode.MOMENTARY, 1, 0) == Result.OK,
                    "a momentary sink ignores the scale, even an unusable one");
            check(helper, s.bindSink(block, io, 1, "open", PlantSink.Mode.MOMENTARY, 0, 1) == Result.OK, "second momentary sink");
            check(helper, ((PlantSink) io.sinks(0).get(0)).mode() == PlantSink.Mode.MOMENTARY
                    && ((PlantSink) io.sinks(0).get(0)).fixed() == 1.0, "stored with its mode and fixed value");
            check(helper, !valve.isComputerControlled() && !valve.isDemandOpen(), "binding does not act");

            // Rise sends the fixed value once, whatever the output value was.
            s.operate(block, io, OperateAction.SET, 3);
            check(helper, valve.isDemandOpen(), "output 0 to 3 sent the fixed value 1");
            // The fall does nothing: someone else closes the valve first, then the control returns to zero.
            valve.setDemandOpen(false);
            s.operate(block, io, OperateAction.SET, 0);
            check(helper, !valve.isDemandOpen(), "the return to zero sent nothing (a follow sink would have sent 0 as well)");
            s.operate(block, io, OperateAction.PRESS, 0);
            check(helper, valve.isDemandOpen(), "the next press sends 1 again");
            s.operate(block, io, OperateAction.RELEASE, 0);
            check(helper, valve.isDemandOpen(), "release leaves the valve open: it sealed in");
            // A second output with fixed 0 closes it, and its own fall does nothing.
            s.operate(block, io, OperateAction.TURN, 5);
            check(helper, !valve.isDemandOpen(), "a rise on the other output sent its fixed 0");
            valve.setDemandOpen(true);
            s.operate(block, io, OperateAction.TURN, 0);
            check(helper, valve.isDemandOpen(), "its fall sent nothing");

            // Saved and reloaded through the block entity: same mode, same fixed value, and loading fires nothing.
            valve.setDemandOpen(false);
            PanelBlockEntity copy = copyOf(s, block);
            PanelComponent reloaded = copy.component(io.id());
            check(helper, reloaded.sinks(0).get(0) instanceof PlantSink a && a.mode() == PlantSink.Mode.MOMENTARY && a.fixed() == 1.0
                            && reloaded.sinks(1).get(0) instanceof PlantSink b && b.mode() == PlantSink.Mode.MOMENTARY && b.fixed() == 0.0,
                    "mode and fixed value survive a block entity save");
            check(helper, copy.savePanel(false).equals(block.savePanel(false)), "the panel save is a fixed point");
            check(helper, !valve.isDemandOpen(), "loading fired nothing");

            // FOLLOW is unchanged: value x scale on every change, including the fall.
            check(helper, s.bind(block, io, true, 0, BindKind.NONE, null, null, 1) == Result.OK
                    && s.bind(block, io, true, 1, BindKind.NONE, null, null, 1) == Result.OK, "clearing");
            check(helper, s.bind(block, io, true, 0, BindKind.PLANT, s.devices, "open", 0.5) == Result.OK
                    && ((PlantSink) io.sinks(0).get(0)).mode() == PlantSink.Mode.FOLLOW, "an old-style request is FOLLOW");
            s.operate(block, io, OperateAction.SET, 1);
            check(helper, valve.isDemandOpen(), "follow: 1 x 0.5 opens");
            s.operate(block, io, OperateAction.SET, 0);
            check(helper, !valve.isDemandOpen(), "follow: the fall sends 0 and closes");
            s.removeMsiv(s.devices);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void controlRoomSwitchSealsTheMsivInAndLampsAndGaugesFollow(GameTestHelper helper) {
        Stage.run(helper, 8, s -> {
            s.wall(2, 2);
            PanelBlockEntity block = s.be(s.at(0, 0));
            BlockPos pos = block.getBlockPos();
            PanelComponent sw = PanelOps.placeDirect(s.level, pos, 0, 0, ComponentTypes.CONTROL_SWITCH, Footprint.of(3, 4));
            PanelComponent gauge = PanelOps.placeDirect(s.level, pos, 4, 0, ComponentTypes.EDGEWISE_GAUGE_VERTICAL, Footprint.of(2, 6));
            PanelComponent seven = PanelOps.placeDirect(s.level, pos, 7, 0, ComponentTypes.SEVEN_SEGMENT_DISPLAY, Footprint.of(8, 3));
            check(helper, sw != null && gauge != null && seven != null, "the real components fit on the wall");
            var valve = s.msiv(s.devices);
            valve.energy().receiveEnergy(20_000, false);
            s.linker(s.devices);

            check(helper, s.bindSink(block, sw, 1, "open", PlantSink.Mode.MOMENTARY, 1, 1) == Result.OK, "OPEN -> open = 1 momentary");
            check(helper, s.bindSink(block, sw, 2, "open", PlantSink.Mode.MOMENTARY, 0, 1) == Result.OK, "CLOSE -> open = 0 momentary");
            check(helper, s.bind(block, sw, false, 0, BindKind.PLANT, s.devices, "position", 1) == Result.OK, "red lamp <- position");
            check(helper, s.bind(block, gauge, false, 0, BindKind.PLANT, s.devices, "position", 1) == Result.OK, "gauge <- position");
            check(helper, s.bind(block, seven, false, 0, BindKind.PLANT, s.devices, "position", 1) == Result.OK, "display <- position");
            // The display shows the fraction open: the format is the only scaling the display API has.
            CompoundTag format = new CompoundTag();
            format.putString("Format", "{0:4.2f}");
            s.configure(block, seven, format);
            s.linker(s.devices);
            SegmentDisplayData digits = (SegmentDisplayData) seven.data();
            check(helper, !valve.isDemandOpen() && valve.getPosition() == 0.0, "the valve starts closed");

            // OPEN, then the spring return.
            s.operate(block, sw, OperateAction.SET, 1);
            check(helper, valve.isDemandOpen() && sw.output(1) == 1.0, "turning to OPEN commanded the valve open");
            s.operate(block, sw, OperateAction.RELEASE, 0);
            check(helper, sw.output(1) == 0.0 && sw.output(0) == 0.0, "the switch sprang back to NORMAL");
            check(helper, valve.isDemandOpen(), "and the demand stayed open: the valve is sealed in");
            for (int i = 0; i < 90; i++) {
                valve.tickValve(.05);
            }
            check(helper, valve.getPosition() == 1.0, "the valve reached full open: " + valve.getPosition());
            s.then(sampleWait(), () -> {
                check(helper, sw.input(0) > 0.99, "the red lamp input reads the position after sampling: " + sw.input(0));
                check(helper, gauge.input(0) > 0.99, "the edgewise gauge reads the position: " + gauge.input(0));
                check(helper, ((dev.bwr.mod.panel.component.GaugeData) gauge.data()).fraction(gauge.input(0)) > 0.0,
                        "and its needle fraction follows");
                check(helper, digits.shown(seven).equals("1.00"), "the display shows the open position: '" + digits.shown(seven) + "'");
                check(helper, valve.isDemandOpen(), "still open");

                // CLOSE, then the spring return.
                s.operate(block, sw, OperateAction.SET, -1);
                check(helper, !valve.isDemandOpen() && sw.output(2) == 1.0, "turning to CLOSE commanded the valve closed");
                s.operate(block, sw, OperateAction.RELEASE, 0);
                check(helper, !valve.isDemandOpen(), "and it stayed closed after the spring return");
                for (int i = 0; i < 90; i++) {
                    valve.tickValve(.05);
                }
                check(helper, valve.getPosition() == 0.0, "the valve closed: " + valve.getPosition());
            });
            s.then(sampleWait(), () -> {
                check(helper, sw.input(0) < 0.01 && gauge.input(0) < 0.01, "lamp and gauge follow it closed: " + sw.input(0));
                check(helper, digits.shown(seven).equals("0.00"), "the display shows closed: '" + digits.shown(seven) + "'");
                s.removeMsiv(s.devices);
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void lcdAndGraphicDisplayDrawBySmallPatchesNotFullResends(GameTestHelper helper) {
        Stage.run(helper, 9, s -> {
            s.wall(2, 2);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelBlockEntity lower = s.be(s.at(1, 1));
            PanelComponent lcd = PanelOps.placeDirect(s.level, block.getBlockPos(), 0, 0, ComponentTypes.CHARACTER_LCD, Footprint.of(16, 4));
            PanelComponent graphic = PanelOps.placeDirect(s.level, lower.getBlockPos(), 0, 0, ComponentTypes.GRAPHIC_DISPLAY, Footprint.of(16, 16));
            check(helper, lcd != null && graphic != null, "the displays fit");
            PanelComponent tio = s.io(s.at(1, 0), 8, 8);
            check(helper, lcd.data() instanceof PanelDrawTarget && graphic.data() instanceof PanelDrawTarget, "both are draw targets");
            check(helper, lcd.data().syncsByPatch() && graphic.data().syncsByPatch() && !tio.data().syncsByPatch(),
                    "the displays sync by patch; an ordinary component does not");

            PanelDrawTarget lcdTarget = (PanelDrawTarget) lcd.data();
            PanelDrawTarget gTarget = (PanelDrawTarget) graphic.data();
            check(helper, lcdTarget.drawWidth() == 16 && lcdTarget.drawHeight() == 2, "lcd is 16 x 2 characters");
            check(helper, gTarget.drawWidth() == 32 && gTarget.drawHeight() == 32, "a 16 x 16 cell graphic display is 32 x 32 px");

            // Each draw is followed by displayChanged, exactly as PanelPeripheral does.
            check(helper, lcdTarget.write(0, 1, "BWR"), "lcd write");
            block.displayChanged(lcd);
            check(helper, ((LcdData) lcd.data()).line(0).equals(" BWR"), "the text is on the row");
            check(helper, gTarget.setPixel(2, 3, 9), "setPixel");
            lower.displayChanged(graphic);
            check(helper, gTarget.blit(new String[] {"0123", "4567"}), "blit");
            lower.displayChanged(graphic);
            check(helper, gTarget.clear(1) && ((GraphicDisplayData) graphic.data()).getPixel(5, 5) == 1, "clear");
            lower.displayChanged(graphic);
            check(helper, block.contentPending() && lower.contentPending(), "content patches are queued for both blocks");
            check(helper, !PanelSync.displaySyncPending(block) && !PanelSync.displaySyncPending(lower),
                    "and neither block queued a full-state resend");
            check(helper, ((LcdData) lcd.data()).drainPatch() != null && ((GraphicDisplayData) graphic.data()).drainPatch() != null,
                    "each display holds a patch to send");

            // Contrast: a component that does not patch does get the full resend.
            s.be(s.at(1, 0)).displayChanged(tio);
            check(helper, PanelSync.displaySyncPending(s.be(s.at(1, 0))), "an ordinary component's change queues a full-state resend");

            if (BwrMod.isComputerCraftPresent()) {
                PanelCcChecks.drawDisplays(helper, block, lower, lcd, graphic);
            }
        });
    }
    // ---- plant input transform (displayed = raw x scale + offset)

    @GameTest(template = "empty")
    public static void inputTransformPayloadAndSaveRoundTripAndOldSavesAreByteIdentical(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess();
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
        var bind = new PanelPayloads.Bind(new BlockPos(1, 2, 3), 7, false, 0, BindKind.PLANT.ordinal(),
                new BlockPos(-50, 64, 9), "position", BlockPos.ZERO, 0, 0, 100.0, 0, 0.0, -2.5);
        PanelPayloads.Bind.STREAM_CODEC.encode(buffer, bind);
        var decoded = PanelPayloads.Bind.STREAM_CODEC.decode(buffer);
        check(helper, decoded.equals(bind) && decoded.scale() == 100.0 && decoded.offset() == -2.5, "bind with offset round trip");
        check(helper, buffer.readableBytes() == 0, "the codec consumed every byte");
        var legacy = new PanelPayloads.Bind(new BlockPos(1, 2, 3), 7, true, 1, BindKind.PLANT.ordinal(),
                BlockPos.ZERO, "open", BlockPos.ZERO, 0, 0, 2.0, PlantSink.Mode.MOMENTARY.ordinal(), 1.0);
        check(helper, legacy.offset() == 0.0 && new PanelPayloads.Bind(BlockPos.ZERO, 1, false, 0, 0, BlockPos.ZERO, "",
                BlockPos.ZERO, 0, 0, 1.0).offset() == 0.0, "the older constructors have no offset");

        // A source with the default transform saves exactly the old tag; an old tag re-saves byte-identical.
        CompoundTag old = new CompoundTag();
        old.putString("Kind", "plant");
        old.putInt("X", 5);
        old.putInt("Y", 6);
        old.putInt("Z", 7);
        old.putString("Key", "position");
        InputSource loaded = InputSources.load(old);
        check(helper, loaded instanceof PlantSource p && p.scale() == 1.0 && p.offset() == 0.0, "old tag loads as identity");
        check(helper, InputSources.save(loaded).equals(old), "old tag re-saves byte-identical");
        check(helper, InputSources.save(new PlantSource(new BlockPos(5, 6, 7), "position")).equals(old),
                "an identity source writes no Scale or Offset");

        InputSource scaled = new PlantSource(new BlockPos(5, 6, 7), "position", 100.0, -2.5);
        CompoundTag scaledTag = InputSources.save(scaled);
        check(helper, scaledTag.contains("Scale") && scaledTag.contains("Offset"), "a transform is saved");
        check(helper, InputSources.load(scaledTag) instanceof PlantSource q && q.scale() == 100.0 && q.offset() == -2.5
                && InputSources.save(InputSources.load(scaledTag)).equals(scaledTag), "transform round trip");
        CompoundTag scaleOnly = InputSources.save(new PlantSource(new BlockPos(5, 6, 7), "position", 100.0, 0.0));
        check(helper, scaleOnly.contains("Scale") && !scaleOnly.contains("Offset"), "only the non-default half is saved");

        CompoundTag zero = old.copy();
        zero.putDouble("Scale", 0.0);
        CompoundTag nan = old.copy();
        nan.putDouble("Offset", Double.NaN);
        CompoundTag text = old.copy();
        text.putString("Scale", "big");
        for (CompoundTag bad : List.of(zero, nan, text)) {
            check(helper, InputSources.save(InputSources.load(bad)).equals(bad), "a malformed transform is kept verbatim: " + bad);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void scaledPlantInputShowsPercentOffsetAppliesAndNaNStaysNaN(GameTestHelper helper) {
        Stage.run(helper, 11, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            BlockPos pos = block.getBlockPos();
            PanelComponent seven = PanelOps.placeDirect(s.level, pos, 0, 0, ComponentTypes.SEVEN_SEGMENT_DISPLAY, Footprint.of(8, 3));
            check(helper, seven != null, "the display fits");
            var valve = s.msiv(s.devices);
            valve.energy().receiveEnergy(20_000, false);
            s.linker(s.devices);
            SegmentDisplayData digits = (SegmentDisplayData) seven.data();

            check(helper, s.bind(block, seven, false, 0, BindKind.PLANT, s.devices, "position", 100) == Result.OK, "scale 100");
            CompoundTag format = new CompoundTag();
            format.putString("Format", "{0:3.0f}");
            s.configure(block, seven, format);
            s.linker(s.devices);

            // Refusals leave the binding as it was.
            PlantSource before = (PlantSource) seven.source(0);
            check(helper, s.bind(block, seven, false, 0, BindKind.PLANT, s.devices, "position", 0) == Result.BAD_VALUE,
                    "scale 0 refused");
            check(helper, s.bind(block, seven, false, 0, BindKind.PLANT, s.devices, "position", Double.NaN) == Result.BAD_VALUE
                    && s.bind(block, seven, false, 0, BindKind.PLANT, s.devices, "position", Double.POSITIVE_INFINITY) == Result.BAD_VALUE
                    && s.bind(block, seven, false, 0, BindKind.PLANT, s.devices, "position", 1.0e7) == Result.BAD_VALUE,
                    "a non-finite or absurd scale is refused");
            for (double offset : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 1.0e7}) {
                check(helper, WiringOps.bind(s.player, new PanelPayloads.Bind(pos, seven.id(), false, 0, BindKind.PLANT.ordinal(),
                        s.devices, "position", BlockPos.ZERO, 0, 0, 1.0, 0, 0.0, offset)) == Result.BAD_VALUE,
                        "offset " + offset + " refused");
            }
            check(helper, seven.source(0) == before, "refused binds changed nothing");

            valve.setDemandOpen(true);
            for (int i = 0; i < 90; i++) {
                valve.tickValve(.05);
            }
            check(helper, valve.getPosition() == 1.0, "the valve is fully open: " + valve.getPosition());
            s.then(sampleWait(), () -> {
                check(helper, seven.input(0) == 100.0, "open reads 100 after scale: " + seven.input(0));
                check(helper, digits.shown(seven).strip().equals("100"), "the display shows 100 when open: '" + digits.shown(seven) + "'");
                valve.setDemandOpen(false);
                for (int i = 0; i < 90; i++) {
                    valve.tickValve(.05);
                }
                check(helper, valve.getPosition() == 0.0, "the valve closed");
            });
            s.then(sampleWait(), () -> {
                check(helper, digits.shown(seven).strip().equals("0"), "and 0 when closed: '" + digits.shown(seven) + "'");
                // Offset: closed reads 0 x 100 - 50 = -50 at once; open reads +50 later.
                s.linker(s.devices);
                check(helper, WiringOps.bind(s.player, new PanelPayloads.Bind(pos, seven.id(), false, 0, BindKind.PLANT.ordinal(),
                        s.devices, "position", BlockPos.ZERO, 0, 0, 100.0, 0, 0.0, -50.0)) == Result.OK, "scale and offset");
                check(helper, seven.input(0) == -50.0, "offset applied at once: " + seven.input(0));
                valve.setDemandOpen(true);
                for (int i = 0; i < 90; i++) {
                    valve.tickValve(.05);
                }
            });
            s.then(sampleWait(), () -> {
                check(helper, seven.input(0) == 50.0, "open reads 1 x 100 - 50: " + seven.input(0));

                // Save and reload through the chunk NBT: the transform survives and the block re-saves identically.
                CompoundTag saved = block.savePanel(false);
                PanelBlockEntity copy = copyOf(s, block);
                check(helper, copy.savePanel(false).equals(saved), "block round trip");
                check(helper, copy.component(seven.id()).source(0) instanceof PlantSource p && p.scale() == 100.0
                        && p.offset() == -50.0 && p.device().equals(s.devices), "the transform is saved and reloaded");

                // Back to identity: nothing extra is saved, so this is exactly an old-style source.
                s.linker(s.devices);
                check(helper, s.bind(block, seven, false, 0, BindKind.PLANT, s.devices, "position", 1) == Result.OK, "identity");
                CompoundTag plain = InputSources.save(seven.source(0));
                check(helper, !plain.contains("Scale") && !plain.contains("Offset"), "identity saves no transform keys");
                check(helper, seven.input(0) == 1.0, "unscaled again: " + seven.input(0));
                s.bind(block, seven, false, 0, BindKind.PLANT, s.devices, "position", 100);
                s.removeMsiv(s.devices);
            });
            s.then(sampleWait(), () -> {
                check(helper, Double.isNaN(seven.input(0)), "NaN stays NaN through scale and offset: " + seven.input(0));
                check(helper, digits.shown(seven).strip().equals("---"), "the display shows dashes: '" + digits.shown(seven) + "'");
            });
        });
    }
}
