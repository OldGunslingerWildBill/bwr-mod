package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.furniture.AssemblyBlock;
import dev.bwr.mod.furniture.DeskBase;
import dev.bwr.mod.furniture.DeskMonitor;
import dev.bwr.mod.panel.DuplexCell;
import dev.bwr.mod.panel.DuplexPanelBlock;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelFinish;
import dev.bwr.mod.panel.PanelFinishes;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.client.PanelFinishColors;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.trim.PanelEndCapBlock;
import dev.bwr.mod.panel.trim.PanelTrimBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Disposable real-client showroom of the control-room furniture (ROOM-FURNITURE.md): a small control room with an existing
 * benchboard run under vertical boards and a black mimic band on the west wall, operator desks with monitors facing it and a
 * supervisor desk behind them, relay cabinets along the east wall and in a back to back pair, and a cream mimic band with a
 * 2 x 2 and a 3 x 2 wall display on the north wall. It checks that every furniture state has a baked model with real
 * textures, then photographs the room from several places into {@code furniture-check/}. Does not run in ordinary play.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class FurnitureClientCheck {
    private static final int X0 = 5393;   // the room, walls included, fills one chunk (5392..5407) so one light section answers
    private static final int Z0 = 5393;
    private static final int FLOOR = 119;
    private static final int Y = FLOOR + 1;

    private static final String[] BASE_NAMES = {"room-overview-ne", "room-overview-sw", "desks-front", "desks-close",
            "cabinets-front", "wall-displays", "back-to-back-pair", "item-icons"};
    /** The stages after the original eight: the same room in every finish (PanelFinish), two pictures each, then the item icons. */
    private static final int FINISH_FIRST = BASE_NAMES.length;
    private static final PanelFinish[] FINISHES = PanelFinish.values();
    private static final String[] NAMES = names();

    private static String[] names() {
        String[] all = new String[BASE_NAMES.length + FINISHES.length * 2 + 1];
        System.arraycopy(BASE_NAMES, 0, all, 0, BASE_NAMES.length);
        for (int i = 0; i < FINISHES.length; i++) {
            all[FINISH_FIRST + i * 2] = "finish-" + FINISHES[i].getSerializedName() + "-room";
            all[FINISH_FIRST + i * 2 + 1] = "finish-" + FINISHES[i].getSerializedName() + "-trim";
        }
        all[all.length - 1] = "finish-icons";
        return all;
    }
    /** Eye position then target, in room coordinates (x, y above the floor, z): the camera stands where the eye is. */
    private static final double[][] VIEWS = views();
    private static final int[] FOV = fovs();

    private static double[][] views() {
        double[][] base = {
                {12.2, 3.8, 3.4, 2.0, 1.4, 7.0},
                {1.0, 3.7, 10.8, 9.0, 1.9, 2.0},
                {8.5, 2.5, 6.8, 3.6, 0.9, 3.5},
                {6.2, 2.0, 5.0, 3.5, 1.0, 3.2},
                {8.5, 2.6, 3.8, 13.5, 2.1, 3.4},
                {8.0, 3.0, 6.6, 8.5, 3.0, 0.5},
                {12.4, 2.4, 11.2, 10.0, 1.4, 8.6},
                {8.5, 2.6, 3.8, 13.5, 2.1, 3.4},
        };
        double[][] all = new double[NAMES.length][];
        System.arraycopy(base, 0, all, 0, base.length);
        for (int i = FINISH_FIRST; i < NAMES.length; i++) {
            // each finish: the room from the north-east corner (the west wall run, the desks), then the south wall run with its trim
            all[i] = (i - FINISH_FIRST) % 2 == 0 ? new double[] {12.6, 3.6, 2.2, 2.0, 1.2, 7.0}
                    : new double[] {10.8, 2.7, 3.2, 5.6, 1.6, 10.6};
        }
        all[NAMES.length - 1] = base[7];
        return all;
    }

    private static int[] fovs() {
        int[] all = new int[NAMES.length];
        int[] base = {75, 75, 70, 60, 60, 70, 65, 60};
        System.arraycopy(base, 0, all, 0, base.length);
        for (int i = FINISH_FIRST; i < NAMES.length; i++) {
            all[i] = 72;
        }
        return all;
    }

    private static boolean started;
    private static boolean requested;
    private static boolean built;
    private static volatile Throwable buildFailure;
    private static volatile Vec3 wantedCamera;
    private static volatile float wantedYaw;
    private static volatile float wantedPitch;
    private static int age;
    private static int joined;
    private static int stage;
    private static int shown;

    private FurnitureClientCheck() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.furnitureClientCheck")) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (buildFailure != null) {
            throw new IllegalStateException("Furniture showroom build failed", buildFailure);
        }
        if (++age > 4000) {
            throw new IllegalStateException("Furniture client check timed out at stage " + stage);
        }
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.renderDistance().set(8);
            mc.options.guiScale().set(2);
            mc.options.gamma().set(1.0);
            mc.createWorldOpenFlows().createFreshLevel("bwr-furniture-check-" + System.currentTimeMillis(),
                    new LevelSettings("Furniture check", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) {
            return;
        }
        if (!requested) {
            requested = true;
            shown = 0;
            wantedCamera = null;
            mc.setScreen(null);
            mc.options.hideGui = true;
            mc.options.fov().set(FOV[stage]);
            int selected = stage;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                if (player == null) {
                    throw new IllegalStateException("Furniture check player missing");
                }
                var level = player.serverLevel();
                if (!built) {
                    try {
                        build(level);
                        built = true;
                    } catch (Throwable failure) {
                        buildFailure = failure;
                        return;
                    }
                }
                if (selected >= FINISH_FIRST && selected < NAMES.length - 1) {
                    try {
                        finishStage(level, selected - FINISH_FIRST);
                    } catch (Throwable failure) {
                        buildFailure = failure;
                        return;
                    }
                }
                double[] v = VIEWS[selected];
                Vec3 eye = new Vec3(X0 + v[0], Y + v[1], Z0 + v[2]);
                Vec3 target = new Vec3(X0 + v[3], Y + v[4], Z0 + v[5]);
                Vec3 delta = target.subtract(eye);
                float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
                float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
                Vec3 feet = eye.subtract(0, player.getEyeHeight(), 0);
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                level.setDayTime(6000);
                level.setWeatherParameters(6000, 0, false, false);
                wantedYaw = yaw;
                wantedPitch = pitch;
                player.connection.teleport(feet.x, feet.y, feet.z, yaw, pitch);
                wantedCamera = feet;
            });
        }
        Vec3 wanted = wantedCamera;
        if (wanted == null) {
            return;
        }
        mc.player.getAbilities().flying = true;
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setPos(wanted.x, wanted.y, wanted.z);
        mc.player.setYRot(wantedYaw);
        mc.player.setXRot(wantedPitch);
        mc.player.yRotO = wantedYaw;
        mc.player.xRotO = wantedPitch;
        // the first picture waits longer: the room's chunks are meshed while the player arrives
        shown++;
        if (shown == 40 && NAMES[stage].equals("item-icons")) {
            mc.setScreen(new IconScreen());
        }
        if (shown == 40 && NAMES[stage].equals("finish-icons")) {
            mc.setScreen(new FinishIconScreen());
        }
        if (shown < (stage == 0 ? 220 : 90)) {
            return;
        }
        if (stage == 0) {
            verifyModels(mc);
        }
        if (stage == FINISH_FIRST) {
            verifyFinishModels(mc);
        }
        if (stage == 0) {
            var server = mc.getSingleplayerServer();
            server.execute(() -> LogUtils.getLogger().info("FURNITURE CLIENT CHECK server light: block {} sky {}",
                    server.overworld().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at(8, 1, 5)),
                    server.overworld().getBrightness(net.minecraft.world.level.LightLayer.SKY, at(8, 1, 5))));
        }
        {
            BlockPos probe = at(8, 1, 5);
            LogUtils.getLogger().info("FURNITURE CLIENT CHECK light at {}: block {} sky {} (stage {})", probe,
                    mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, probe),
                    mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, probe), NAMES[stage]);
        }
        try {
            var directory = mc.gameDirectory.toPath().resolve("furniture-check");
            Files.createDirectories(directory);
            try (var shot = CheckShots.saveKeeping(mc, directory.resolve(NAMES[stage] + ".png"))) {
                if (isMostlyOneColour(shot)) {
                    throw new AssertionError("Furniture screenshot " + NAMES[stage] + " is a flat colour: nothing rendered");
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Furniture screenshot failed", ex);
        }
        LogUtils.getLogger().info("FURNITURE CLIENT CHECK: {}", NAMES[stage]);
        requested = false;
        if (++stage == NAMES.length) {
            LogUtils.getLogger().info("FURNITURE CLIENT CHECK PASS: {} pictures, every furniture state has a baked model with its textures, "
                    + "and the room in {} finishes", NAMES.length, FINISHES.length);
            mc.stop();
        }
    }

    /** The furniture items as the inventory draws them, at six times the size. */
    private static final class IconScreen extends net.minecraft.client.gui.screens.Screen {
        IconScreen() {
            super(net.minecraft.network.chat.Component.empty());
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xFF202830);
            Block[] blocks = {BwrBlocks.RELAY_CABINET.get(), BwrBlocks.RELAY_CABINET_PLAIN.get(), BwrBlocks.OPERATOR_DESK.get(),
                    BwrBlocks.OPERATOR_DESK_ROUND.get(), BwrBlocks.OPERATOR_DESK_CORNER.get(), BwrBlocks.SUPERVISOR_DESK.get(),
                    BwrBlocks.MIMIC_BAND_CREAM.get(), BwrBlocks.MIMIC_BAND_BLACK.get(), BwrBlocks.WALL_DISPLAY.get(),
                    BwrBlocks.WALL_DISPLAY_WIDE.get(), BwrBlocks.CONTROL_STAND.get(), BwrBlocks.CONTROL_PANEL_BENCHBOARD.get()};
            float scale = 5.0f;
            int cell = 16 * (int) scale + 24;
            for (int i = 0; i < blocks.length; i++) {
                int col = i % 4;
                int row = i / 4;
                graphics.pose().pushPose();
                graphics.pose().translate(width / 2.0f - 2 * cell + col * cell + 12, 20 + row * (cell - 6), 0);
                graphics.pose().scale(scale, scale, 1.0f);
                graphics.renderItem(new ItemStack(blocks[i]), 0, 0);
                graphics.pose().popPose();
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    private static boolean isMostlyOneColour(com.mojang.blaze3d.platform.NativeImage shot) {
        int first = shot.getPixelRGBA(shot.getWidth() / 2, shot.getHeight() / 2);
        int same = 0;
        int total = 0;
        for (int y = 0; y < shot.getHeight(); y += 8) {
            for (int x = 0; x < shot.getWidth(); x += 8) {
                total++;
                if (shot.getPixelRGBA(x, y) == first) {
                    same++;
                }
            }
        }
        return same > total * 0.97;
    }

    /** Every state of every furniture block must bake to quads, none of them with the missing-texture sprite. */
    private static void verifyModels(Minecraft mc) {
        var renderer = mc.getBlockRenderer();
        var missing = mc.getModelManager().getMissingModel();
        List<Block> blocks = List.of(BwrBlocks.RELAY_CABINET.get(), BwrBlocks.RELAY_CABINET_PLAIN.get(), BwrBlocks.OPERATOR_DESK.get(),
                BwrBlocks.OPERATOR_DESK_ROUND.get(), BwrBlocks.OPERATOR_DESK_CORNER.get(), BwrBlocks.SUPERVISOR_DESK.get(),
                BwrBlocks.MIMIC_BAND_CREAM.get(), BwrBlocks.MIMIC_BAND_BLACK.get(), BwrBlocks.WALL_DISPLAY.get(),
                BwrBlocks.WALL_DISPLAY_WIDE.get(), BwrBlocks.CONTROL_STAND.get());
        int states = 0;
        int quads = 0;
        List<String> wrong = new ArrayList<>();
        for (Block block : blocks) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                var model = renderer.getBlockModel(state);
                int n = model.getQuads(state, null, RandomSource.create(1L), ModelData.EMPTY, null).size();
                for (Direction side : Direction.values()) {
                    n += model.getQuads(state, side, RandomSource.create(1L), ModelData.EMPTY, null).size();
                }
                states++;
                quads += n;
                if (model == missing || n < 3) {
                    wrong.add(state + " -> " + n + " quads" + (model == missing ? " (missing model)" : ""));
                    continue;
                }
                for (var quad : model.getQuads(state, null, RandomSource.create(1L), ModelData.EMPTY, null)) {
                    if (quad.getSprite().contents().name().getPath().contains("missingno")) {
                        wrong.add(state + " draws the missing texture");
                        break;
                    }
                }
            }
        }
        if (!wrong.isEmpty()) {
            throw new AssertionError("Furniture states without a proper baked model: " + wrong.subList(0, Math.min(8, wrong.size())));
        }
        LogUtils.getLogger().info("FURNITURE CLIENT CHECK models: {} states, {} quads, none missing", states, quads);
    }

    // ---- the room (server side)

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(X0 + x, Y + y, Z0 + z);
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 3);
    }

    private static void furnish(ServerLevel level, BlockPos pos, Block block, Direction facing, DeskMonitor monitor) {
        BlockState state = block.defaultBlockState().setValue(AssemblyBlock.FACING, facing);
        if (monitor != null) {
            state = state.setValue(DeskBase.MONITOR, monitor);
        }
        level.setBlock(pos, state, 3);
        block.setPlacedBy(level, pos, state, null, ItemStack.EMPTY);
    }

    private static void build(ServerLevel level) {
        for (int cx = (X0 - 24) >> 4; cx <= (X0 + 24) >> 4; cx++) {
            for (int cz = (Z0 - 24) >> 4; cz <= (Z0 + 24) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        // shell: floor, walls, ceiling with a grid of flush lanterns and hidden light blocks under them; interior 14 wide (x 0..13),
        // 12 deep (z 0..11) and 5 high
        for (int x = -1; x <= 14; x++) {
            for (int z = -1; z <= 12; z++) {
                set(level, new BlockPos(X0 + x, FLOOR, Z0 + z), Blocks.GRAY_CONCRETE.defaultBlockState());
                boolean lit = x >= 1 && x <= 13 && z >= 1 && z <= 10 && x % 3 == 1 && z % 3 == 1;
                set(level, new BlockPos(X0 + x, Y + 5, Z0 + z),
                        (lit ? Blocks.SEA_LANTERN : Blocks.LIGHT_GRAY_CONCRETE).defaultBlockState());
                for (int y = 0; y < 5; y++) {
                    boolean wall = x == -1 || x == 14 || z == -1 || z == 12;
                    BlockState state = wall ? Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    if (lit && y == 3) {
                        state = Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 15);
                    }
                    set(level, new BlockPos(X0 + x, Y + y, Z0 + z), state);
                }
            }
        }
        // west wall: the existing benchboard run with vertical boards above it and a black mimic band over those
        List<BlockPos> benches = new ArrayList<>();
        List<BlockPos> verticals = new ArrayList<>();
        for (int z = 1; z <= 7; z++) {
            BlockPos bench = at(0, 0, z);
            level.setBlock(bench, BwrBlocks.CONTROL_PANEL_BENCHBOARD.get().defaultBlockState().setValue(PanelBlock.FACING, Direction.EAST), 3);
            benches.add(bench);
            for (int y = 1; y <= 2; y++) {
                BlockPos vertical = at(0, y, z);
                level.setBlock(vertical, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, Direction.EAST), 3);
                verticals.add(vertical);
            }
            furnish(level, at(0, 3, z), BwrBlocks.MIMIC_BAND_BLACK.get(), Direction.EAST, null);
        }
        decoratePanels(level, benches, verticals);
        // west wall, further along: a cream mimic wall two bands high
        for (int z = 8; z <= 11; z++) {
            for (int y = 1; y <= 2; y++) {
                furnish(level, at(0, y, z), BwrBlocks.MIMIC_BAND_CREAM.get(), Direction.EAST, null);
            }
        }
        // operator desks facing the boards: a corner desk, two straight desks with a monitor and a CRT, a round end desk
        furnish(level, at(3, 0, 2), BwrBlocks.OPERATOR_DESK_CORNER.get(), Direction.EAST, DeskMonitor.CRT);
        furnish(level, at(3, 0, 3), BwrBlocks.OPERATOR_DESK.get(), Direction.EAST, DeskMonitor.FLAT);
        furnish(level, at(3, 0, 4), BwrBlocks.OPERATOR_DESK.get(), Direction.EAST, DeskMonitor.CRT);
        furnish(level, at(3, 0, 5), BwrBlocks.OPERATOR_DESK_ROUND.get(), Direction.EAST, DeskMonitor.FLAT);
        // the shift supervisor's desk, behind the operators and facing the same way
        furnish(level, at(7, 0, 3), BwrBlocks.SUPERVISOR_DESK.get(), Direction.EAST, DeskMonitor.FLAT);
        level.setBlock(at(7, 0, 4), level.getBlockState(at(7, 0, 4)).setValue(DeskBase.MONITOR, DeskMonitor.CRT), 3);
        // relay cabinets along the east wall, fronts to the room
        for (int z = 1; z <= 7; z++) {
            furnish(level, at(13, 0, z), BwrBlocks.RELAY_CABINET.get(), Direction.WEST, null);
        }
        // two rows of plain-back cabinets standing back to back, a two block aisle in front of the second row
        for (int x = 9; x <= 11; x++) {
            furnish(level, at(x, 0, 8), BwrBlocks.RELAY_CABINET_PLAIN.get(), Direction.NORTH, null);
            furnish(level, at(x, 0, 9), BwrBlocks.RELAY_CABINET_PLAIN.get(), Direction.SOUTH, null);
        }
        // one standard cabinet in the open, to show its service door at the back
        furnish(level, at(12, 0, 9), BwrBlocks.RELAY_CABINET.get(), Direction.NORTH, null);
        // two control stands in front of the cream mimic wall
        furnish(level, at(2, 0, 9), BwrBlocks.CONTROL_STAND.get(), Direction.EAST, null);
        furnish(level, at(2, 0, 10), BwrBlocks.CONTROL_STAND.get(), Direction.EAST, null);
        // north wall: a cream mimic band, a 2 x 2 and a 3 x 2 display above it
        for (int x = 4; x <= 12; x++) {
            furnish(level, at(x, 1, 0), BwrBlocks.MIMIC_BAND_CREAM.get(), Direction.SOUTH, null);
        }
        furnish(level, at(7, 2, 0), BwrBlocks.WALL_DISPLAY.get(), Direction.SOUTH, null);
        furnish(level, at(12, 2, 0), BwrBlocks.WALL_DISPLAY_WIDE.get(), Direction.SOUTH, null);
        // blocks set in one go inside chunks that have only just loaded: ask the light engine to look at every one again
        var lights = level.getChunkSource().getLightEngine();
        for (int x = -1; x <= 14; x++) {
            for (int z = -1; z <= 12; z++) {
                for (int y = -1; y <= 6; y++) {
                    lights.checkBlock(new BlockPos(X0 + x, Y + y, Z0 + z));
                }
            }
        }
    }

    // ---- a few components, so the existing board reads as a board and not as bare paint

    private static PanelComponent put(ServerLevel level, BlockPos anchor, ComponentType type, int x, int y, int w, int h,
                                      String style, String variant, double value) {
        PanelComponent component = PanelOps.placeDirect(level, anchor, x, y, type, Footprint.of(w, h));
        if (component == null) {
            throw new AssertionError("Showroom panel placement refused " + type.id() + " at " + anchor);
        }
        PanelModelShowcase.look(component, style, variant);
        component.touch();
        if (component.inputCount() > 0) {
            component.setSource(0, InputSources.constant(value));
            ((PanelBlockEntity) level.getBlockEntity(anchor)).resample(component, true);
        }
        return component;
    }

    private static void decoratePanels(ServerLevel level, List<BlockPos> benches, List<BlockPos> verticals) {
        String ge = PanelStyles.GE;
        for (int i = 0; i < benches.size(); i++) {
            BlockPos bench = benches.get(i);
            if (i % 3 == 0) {
                put(level, bench, ComponentTypes.CONTROL_SWITCH, 2, 0, 12, 16, ge, "pistol", 0);
            } else if (i % 3 == 1) {
                put(level, bench, ComponentTypes.PUSHBUTTON, 0, 4, 8, 8, ge, "flush", 0);
                put(level, bench, ComponentTypes.PUSHBUTTON, 8, 4, 8, 8, ge, "mushroom", 0);
            } else {
                for (int k = 0; k < 3; k++) {
                    put(level, bench, ComponentTypes.LAMP, 1 + k * 5, 6, 4, 4, ge, k % 2 == 0 ? "round" : "square", k == 1 ? 0 : 1);
                }
            }
        }
        for (int i = 0; i < verticals.size(); i++) {
            BlockPos vertical = verticals.get(i);
            put(level, vertical, ComponentTypes.DIAL_GAUGE, 0, 0, 16, 16, ge, i % 3 == 2 ? "red_pointer" : "standard", 30 + (i * 37) % 60);
        }
    }

    // ---- the same room in every finish

    private static boolean extrasBuilt;

    /** One stage of the finishes: the whole room takes the finish (every panel, trim and furniture block), after the extras exist. */
    private static void finishStage(ServerLevel level, int index) {
        if (!extrasBuilt) {
            extrasBuilt = true;
            buildFinishExtras(level);
        }
        PanelFinish finish = FINISHES[index / 2];
        int changed = 0;
        for (int x = -1; x <= 14; x++) {
            for (int z = -1; z <= 12; z++) {
                for (int y = 0; y < 5; y++) {
                    BlockPos pos = at(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (PanelFinishes.hasFinish(state) && state.getValue(PanelFinish.FINISH) != finish) {
                        level.setBlock(pos, state.setValue(PanelFinish.FINISH, finish), Block.UPDATE_CLIENTS);
                        changed++;
                    }
                }
            }
        }
        LogUtils.getLogger().info("FURNITURE CLIENT CHECK finish {}: {} blocks changed", finish.getSerializedName(), changed);
    }

    /**
     * What the original room has none of: a run along the south wall of a cabinet plinth, two vertical boards, an annunciator
     * strip, canopy and vent above, with an end cap at each end, and one duplex column in the middle of the floor.
     */
    private static void buildFinishExtras(ServerLevel level) {
        Direction north = Direction.NORTH;
        for (int x = 4; x <= 8; x++) {
            level.setBlock(at(x, 0, 11), BwrBlocks.CONTROL_PANEL_PLINTH_BASE.get().defaultBlockState().setValue(PanelTrimBlock.FACING, north), 3);
            for (int y = 1; y <= 2; y++) {
                level.setBlock(at(x, y, 11), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, north), 3);
            }
            Block header = x % 2 == 0 ? BwrBlocks.CONTROL_PANEL_HEADER_STRIP.get() : BwrBlocks.CONTROL_PANEL_HEADER_CANOPY.get();
            level.setBlock(at(x, 3, 11), header.defaultBlockState().setValue(PanelTrimBlock.FACING, north), 3);
            level.setBlock(at(x, 4, 11), BwrBlocks.CONTROL_PANEL_HEADER_VENT.get().defaultBlockState().setValue(PanelTrimBlock.FACING, north), 3);
        }
        for (int y = 0; y <= 2; y++) {
            Block cap = y == 0 ? BwrBlocks.CONTROL_PANEL_END_CAP_BENCH.get() : BwrBlocks.CONTROL_PANEL_END_CAP_FLAT.get();
            level.setBlock(at(3, y, 11), cap.defaultBlockState().setValue(PanelTrimBlock.FACING, north)
                    .setValue(PanelEndCapBlock.SIDE, PanelEndCapBlock.Side.LEFT), 3);
            level.setBlock(at(9, y, 11), cap.defaultBlockState().setValue(PanelTrimBlock.FACING, north)
                    .setValue(PanelEndCapBlock.SIDE, PanelEndCapBlock.Side.RIGHT), 3);
        }
        level.setBlock(at(10, 0, 11), BwrBlocks.CONTROL_PANEL_CORNER_VERTICAL_OUTSIDE.get().defaultBlockState()
                .setValue(PanelTrimBlock.FACING, north), 3);
        // a duplex column on the floor, bench at the front
        BlockState bench = BwrBlocks.CONTROL_PANEL_DUPLEX.get().defaultBlockState().setValue(PanelBlock.FACING, north)
                .setValue(DuplexPanelBlock.CELL, DuplexCell.BENCH);
        BlockPos origin = at(5, 0, 6);
        level.setBlock(origin, bench, 3);
        BwrBlocks.CONTROL_PANEL_DUPLEX.get().setPlacedBy(level, origin, bench, null, ItemStack.EMPTY);
        var lights = level.getChunkSource().getLightEngine();
        for (int x = 2; x <= 11; x++) {
            for (int z = 5; z <= 12; z++) {
                for (int y = 0; y <= 5; y++) {
                    lights.checkBlock(at(x, y, z));
                }
            }
        }
    }

    /** Every finished block, in every finish, bakes to quads with real textures, and the painted quads are tinted. */
    private static void verifyFinishModels(Minecraft mc) {
        var renderer = mc.getBlockRenderer();
        var missing = mc.getModelManager().getMissingModel();
        int states = 0;
        int tinted = 0;
        List<String> wrong = new ArrayList<>();
        for (Block block : PanelFinishColors.finishBlocks()) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                var model = renderer.getBlockModel(state);
                states++;
                if (model == missing) {
                    wrong.add(state + " has no model");
                    continue;
                }
                boolean anyTint = false;
                for (var quad : model.getQuads(state, null, RandomSource.create(1L), ModelData.EMPTY, null)) {
                    if (quad.getSprite().contents().name().getPath().contains("missingno")) {
                        wrong.add(state + " draws the missing texture");
                        break;
                    }
                    anyTint |= quad.isTinted();
                }
                if (anyTint) {
                    tinted++;
                }
            }
        }
        if (!wrong.isEmpty()) {
            throw new AssertionError("Finished blocks without a proper baked model: " + wrong.subList(0, Math.min(8, wrong.size())));
        }
        if (tinted < states / 2) {
            throw new AssertionError("Only " + tinted + " of " + states + " finished states have tinted quads");
        }
        LogUtils.getLogger().info("FURNITURE CLIENT CHECK finish models: {} states, {} with tinted quads, none missing", states, tinted);
    }

    /** One row of items per finish, at five times the size: the item colour handler follows the item's finish. */
    private static final class FinishIconScreen extends net.minecraft.client.gui.screens.Screen {
        FinishIconScreen() {
            super(net.minecraft.network.chat.Component.empty());
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xFF202830);
            Block[] blocks = {BwrBlocks.CONTROL_PANEL_VERTICAL.get(), BwrBlocks.CONTROL_PANEL_BENCHBOARD.get(),
                    BwrBlocks.CONTROL_PANEL_CONSOLE.get(), BwrBlocks.CONTROL_PANEL_DUPLEX.get(), BwrBlocks.CONTROL_PANEL_DESK.get(),
                    BwrBlocks.CONTROL_PANEL_DESK_WIDE.get(), BwrBlocks.CONTROL_PANEL_END_CAP_FLAT.get(),
                    BwrBlocks.CONTROL_PANEL_HEADER_STRIP.get(), BwrBlocks.CONTROL_PANEL_PLINTH_BASE.get(), BwrBlocks.RELAY_CABINET.get(),
                    BwrBlocks.OPERATOR_DESK.get(), BwrBlocks.SUPERVISOR_DESK.get(), BwrBlocks.WALL_DISPLAY.get(), BwrBlocks.MIMIC_BAND_CREAM.get()};
            float scale = 3.2f;
            int cell = (int) (16 * scale) + 6;
            for (int row = 0; row < FINISHES.length; row++) {
                for (int i = 0; i < blocks.length; i++) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(width / 2.0f - blocks.length * cell / 2.0f + i * cell, 10 + row * (cell + 4), 0);
                    graphics.pose().scale(scale, scale, 1.0f);
                    graphics.renderItem(PanelFinish.withFinish(new ItemStack(blocks[i]), FINISHES[row]), 0, 0);
                    graphics.pose().popPose();
                }
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
