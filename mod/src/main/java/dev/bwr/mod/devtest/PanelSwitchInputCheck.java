package dev.bwr.mod.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelBus;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.client.RenderCache;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.model.FlagState;
import dev.bwr.mod.panel.model.Poses;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The switch input stages of the panel client check: what a PLAYER does to a breaker control switch (the GE target pistol,
 * the Westinghouse W-2 pistols, the classic look), through the game's own key loop and the mod's real client handlers
 * ({@code PanelClientOperate}), never by calling a server method. The use key is driven through {@link KeyMapping} between
 * client ticks exactly as the window's key callback would, so the game raises the click, repeats it while the key is down
 * and the mod sends the packets.
 *
 * <p>Every sequence (a quick click pressed and released in one tick or a tick apart, a hold, a slide off the switch with the
 * key down, double clicks, the other side while one is held, a mash, a scroll flick, the same with sneak down) is run on
 * each switch of a layout. After each one the handle must be back at NORMAL within a few ticks on the server (the
 * component's outputs and its data), on the client (its copy of the outputs and the angle the renderer last posed the
 * handle at) and the target flag and the breaker command must be what the operation left: the flag shows the last
 * operation (colour read back from the renderer), and the breaker, a model of a MOMENTARY plant sink listening to the
 * switch's real {@link PanelBus} events, stays CLOSED after CLOSE and OPEN after OPEN (the spring's return to NORMAL must
 * not trip or close it). The layouts cover a vertical wall facing north and one facing east, a flat desk, a 45 degree
 * benchboard and a 15 degree console. The last stage photographs the GE and the Westinghouse switch at NORMAL, held and
 * back at NORMAL (1920 x 1080).
 *
 * <p>Alt and Shift: the switch itself has no modifier action. Alt+click only matters with a component item in hand (the size
 * picker) and Shift+click only with a component item, the wrench or the linker (see {@code PanelClientInput}); both are
 * bypassed for a bare hand, so what is exercised here is sneak (Shift) held down, which is the only modifier a bare hand can
 * have; Alt cannot be injected (the game reads it from the window).
 */
final class PanelSwitchInputCheck {
    private PanelSwitchInputCheck() {
    }

    static final String PREFIX = "switch-input-";
    static final String[] NAMES = {"switch-input-vertical-north", "switch-input-vertical-east", "switch-input-desk",
            "switch-input-bench45", "switch-input-console15", "switch-input-closeups"};
    /** Client ticks the stages may need in all; added to the whole client check's time limit. */
    static final int TICK_BUDGET = 16_000;

    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    /** How long after the last input the handle may take to be at NORMAL on the server and the client (ticks). */
    private static final int SETTLE = 14;
    private static final int SPRING_AFTER_SCROLL = 20;
    /** How long the handle may stay on a control the crosshair has left, key still down (ticks). */
    private static final int LEAVE = 8;

    // ---- layouts

    private record Look(String name, int w, int h, String style, String variant) {
    }

    private static final Look GE_8X12 = new Look("ge-8x12", 8, 12, PanelStyles.GE, "pistol");
    private static final Look GE_12X16 = new Look("ge-12x16", 12, 16, PanelStyles.GE, "pistol");
    private static final Look GE_1X2 = new Look("ge-real-1x2", 1, 2, PanelStyles.GE, "pistol");
    private static final Look GE_2X3 = new Look("ge-real-2x3", 2, 3, PanelStyles.GE, "pistol");
    private static final Look W2_8X12 = new Look("w2-pistol-8x12", 8, 12, PanelStyles.WESTINGHOUSE, "pistol");
    private static final Look W2_2X3 = new Look("w2-pistol-real-2x3", 2, 3, PanelStyles.WESTINGHOUSE, "pistol");
    private static final Look W2_HD_12X16 = new Look("w2-heavy-12x16", 12, 16, PanelStyles.WESTINGHOUSE, "pistol_hd");
    private static final Look W2_HD_2X3 = new Look("w2-heavy-real-2x3", 2, 3, PanelStyles.WESTINGHOUSE, "pistol_hd");
    private static final Look CLASSIC_8X12 = new Look("classic-8x12", 8, 12, PanelStyles.CLASSIC, null);
    private static final Look CLASSIC_2X3 = new Look("classic-real-2x3", 2, 3, PanelStyles.CLASSIC, null);

    /**
     * @param cv    row of the block (cells down) the camera looks at on the middle seam of the first row
     * @param up    how far in front of the face (along its normal) the camera stands, and {@code back} how far it is moved
     *              toward the operator on top of that
     */
    private record Layout(String name, PanelType type, Direction facing, Supplier<? extends Block> block, int baseX, int y,
                          int columns, int rows, double cv, double up, double back, Look[] looks, boolean closeups) {
    }

    private static final Layout[] LAYOUTS = {
            new Layout("vertical-north", PanelType.VERTICAL, Direction.NORTH, BwrBlocks.CONTROL_PANEL_VERTICAL, 6400, 129,
                    4, 2, 16, 2.4, 0.0, new Look[] {GE_8X12, GE_12X16, GE_1X2, GE_2X3, W2_8X12, W2_HD_12X16, CLASSIC_8X12}, false),
            new Layout("vertical-east", PanelType.VERTICAL, Direction.EAST, BwrBlocks.CONTROL_PANEL_VERTICAL, 6440, 129,
                    3, 1, 8, 2.0, 0.0, new Look[] {GE_8X12, W2_8X12, W2_HD_12X16}, false),
            new Layout("desk", PanelType.DESK, Direction.NORTH, BwrBlocks.CONTROL_PANEL_DESK, 6480, 126,
                    4, 1, PanelGeometry.DESK_ROWS / 2.0, 1.0, 1.0, new Look[] {GE_2X3, W2_2X3, W2_HD_2X3, CLASSIC_2X3}, false),
            new Layout("bench45", PanelType.BENCHBOARD, Direction.NORTH, BwrBlocks.CONTROL_PANEL_BENCHBOARD, 6520, 126,
                    4, 1, 8, 1.8, 0.0, new Look[] {GE_8X12, W2_8X12, W2_HD_12X16, GE_2X3}, false),
            new Layout("console15", PanelType.CONSOLE, Direction.NORTH, BwrBlocks.CONTROL_PANEL_CONSOLE, 6560, 126,
                    4, 1, 8, 1.6, 0.3, new Look[] {GE_8X12, W2_8X12, W2_HD_12X16, GE_2X3}, false),
            new Layout("closeups", PanelType.VERTICAL, Direction.NORTH, BwrBlocks.CONTROL_PANEL_VERTICAL, 6600, 129,
                    2, 1, 8, 1.6, 0.0, new Look[] {GE_8X12, W2_8X12}, true)};

    private static final int Z = 6400;

    private static BlockPos blockOf(Layout layout, int column, int row) {
        Direction right = layout.facing().getCounterClockWise();
        return new BlockPos(layout.baseX(), layout.y(), Z).relative(right, column).below(row);
    }

    private static PanelGeometry.Frame frame(Layout layout) {
        return PanelGeometry.frame(layout.type(), layout.facing());
    }

    /** The point on the face the camera looks at: the seam between the middle two columns of the first row. */
    static Vec3 target(int stage) {
        Layout layout = LAYOUTS[stage];
        BlockPos pos = blockOf(layout, layout.columns() / 2 - 1, 0);
        PanelGeometry.Frame frame = frame(layout);
        double cu = layout.columns() % 2 == 0 ? 16.0 : 8.0;
        if (layout.columns() % 2 == 1) {
            pos = blockOf(layout, layout.columns() / 2, 0);
        }
        return new Vec3(pos.getX() + frame.x(cu, layout.cv(), 0.0), pos.getY() + frame.y(cu, layout.cv(), 0.0),
                pos.getZ() + frame.z(cu, layout.cv(), 0.0));
    }

    /** The camera's eye position minus the eye height (where the player's feet are). */
    static Vec3 camera(int stage) {
        Layout layout = LAYOUTS[stage];
        PanelGeometry.Frame frame = frame(layout);
        Vec3 target = target(stage);
        Vec3 eye = target.add(frame.nx * layout.up() + layout.facing().getStepX() * layout.back(),
                frame.ny * layout.up(), frame.nz * layout.up() + layout.facing().getStepZ() * layout.back());
        return eye.subtract(0.0, 1.62, 0.0);
    }

    // ---- building (server)

    private record Rig(Layout layout, Look look, BlockPos pos, int id) {
        String name() {
            return layout.name() + "/" + look.name();
        }
    }

    private static final List<List<Rig>> RIGS = new ArrayList<>();
    private static final boolean[] BUILT = new boolean[LAYOUTS.length];
    private static final Set<Long> WATCHED = ConcurrentHashMap.newKeySet();
    private static final List<Event> EVENTS = new CopyOnWriteArrayList<>();
    private static boolean listening;

    private record Event(long pos, int id, int index, double value) {
    }

    /** Builds the fixture of a stage the first time its stage begins (server thread). */
    static void prepare(ServerLevel level, int stage) {
        while (RIGS.size() < LAYOUTS.length) {
            RIGS.add(new ArrayList<>());
        }
        if (!listening) {
            listening = true;
            PanelBus.addOutputListener((l, owner, component, index, value) -> {
                if (WATCHED.contains(owner.getBlockPos().asLong())) {
                    EVENTS.add(new Event(owner.getBlockPos().asLong(), component.id(), index, value));
                }
            });
        }
        if (BUILT[stage]) {
            return;
        }
        BUILT[stage] = true;
        Layout layout = LAYOUTS[stage];
        for (int x = layout.baseX() - 8; x <= layout.baseX() + 8; x++) {
            for (int z = Z - 8; z <= Z + 8; z++) {
                level.setBlock(new BlockPos(x, 125, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 126; y <= 134; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (int row = 0; row < layout.rows(); row++) {
            for (int column = 0; column < layout.columns(); column++) {
                level.setBlock(blockOf(layout, column, row), layout.block().get().defaultBlockState()
                        .setValue(PanelBlock.FACING, layout.facing()), 3);
            }
        }
        int gridRows = layout.type() == PanelType.DESK ? PanelGeometry.DESK_ROWS : PanelGeometry.GRID;
        List<Rig> rigs = RIGS.get(stage);
        for (int i = 0; i < layout.looks().length; i++) {
            Look look = layout.looks()[i];
            BlockPos pos = blockOf(layout, i % layout.columns(), i / layout.columns());
            PanelComponent component = PanelOps.placeDirect(level, pos, (PanelGeometry.GRID - look.w()) / 2, (gridRows - look.h()) / 2,
                    ComponentTypes.CONTROL_SWITCH, Footprint.of(look.w(), look.h()));
            if (component == null) {
                throw new AssertionError("Switch input check: placement refused " + layout.name() + "/" + look.name());
            }
            ControlSwitchData data = (ControlSwitchData) component.data();
            // A freshly placed breaker switch must spring back: this is the default the owner gets from the item.
            if (!data.spring) {
                throw new AssertionError("Switch input check: a freshly placed control switch does not default to spring return");
            }
            PanelModelShowcase.look(component, look.style(),
                    look.variant() != null ? look.variant() : PanelStyles.defaultVariant(ControlSwitchData.TYPE, look.style()));
            rigs.add(new Rig(layout, look, pos, component.id()));
            WATCHED.add(pos.asLong());
        }
        for (int row = 0; row < layout.rows(); row++) {
            for (int column = 0; column < layout.columns(); column++) {
                if (level.getBlockEntity(blockOf(layout, column, row)) instanceof PanelBlockEntity be) {
                    be.syncFull();
                }
            }
        }
    }

    // ---- the engine (client)

    @FunctionalInterface
    private interface Act {
        void run(Minecraft mc, Rig rig);
    }

    private record Timed(int at, Act act) {
    }

    /**
     * @param expect   the operation the sequence leaves behind: -1 CLOSE, +1 OPEN, 0 none (the flag and the breaker keep
     *                 whatever they had)
     * @param settle   ticks after the last act the handle may take to be at NORMAL
     * @param sneak    Shift held down during the sequence
     * @param finish   run once the handle is at NORMAL again, aimed at the switch (pictures)
     */
    private record Seq(String name, int expect, int settle, boolean sneak, List<Timed> acts, Act finish) {
    }

    private static final class Job {
        final Rig rig;
        final Seq seq;

        Job(Rig rig, Seq seq) {
            this.rig = rig;
            this.seq = seq;
        }
    }

    /** What the server's copy of the switch under test looked like a tick ago (taken on the server thread). */
    private record Snap(Rig rig, double out0, double out1, double out2, int position, int lastOperated, boolean spring) {
    }

    private enum Phase {
        START, RUN, WAIT, LOOK, GAP, RECOVER
    }

    private static volatile Snap snap;
    private static final List<Job> JOBS = new ArrayList<>();
    private static int jobIndex;
    private static int builtFor = -1;
    private static Phase phase = Phase.START;
    private static int seqTick;
    private static int waitTicks;
    private static int neutralRun;
    private static int firstNeutral;
    private static int latency;
    private static int lookTicks;
    private static int gap;
    private static int eventsFrom;
    private static Vec3 feet;
    private static int worst;
    private static int jobsDone;
    private static int[] restPicture, heldPicture;
    /** The operation each switch was last left at (flag) and what its breaker was last commanded to, by rig. */
    private static final java.util.Map<Rig, int[]> STATE = new java.util.HashMap<>();

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError("Switch input check: " + message);
        }
    }

    private static PanelComponent client(Minecraft mc, Rig rig) {
        PanelComponent component = mc.level.getBlockEntity(rig.pos()) instanceof PanelBlockEntity be ? be.component(rig.id()) : null;
        require(component != null, "the client has no switch " + rig.name());
        return component;
    }

    private static String describe(Minecraft mc, Rig rig) {
        PanelComponent component = client(mc, rig);
        Snap s = snap;
        return "client output " + component.output(0) + "/" + component.output(1) + "/" + component.output(2) + ", server output "
                + (s != null && s.rig() == rig ? s.out0() + "/" + s.out1() + "/" + s.out2() + " position " + s.position() : "unknown")
                + ", drawn angle " + RenderCache.of(component).drawnAngle() + ", lastOperated "
                + ((ControlSwitchData) component.data()).lastOperated;
    }

    // -- the player's hand

    private static void down(Minecraft mc) {
        InputConstants.Key key = mc.options.keyUse.getKey();
        KeyMapping.click(key);
        KeyMapping.set(key, true);
    }

    private static void up(Minecraft mc) {
        KeyMapping.set(mc.options.keyUse.getKey(), false);
    }

    /** The key goes down and up between two ticks (a very quick click). */
    private static void flick(Minecraft mc) {
        InputConstants.Key key = mc.options.keyUse.getKey();
        KeyMapping.click(key);
        KeyMapping.set(key, true);
        KeyMapping.set(key, false);
    }

    private static void look(Minecraft mc, Vec3 target) {
        Vec3 d = target.subtract(mc.player.getEyePosition(1.0f));
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        turn(mc, yaw, pitch);
    }

    private static void turn(Minecraft mc, float yaw, float pitch) {
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.yHeadRot = yaw;
        mc.gameRenderer.pick(1.0f);
    }

    /** The point of the block's face at cell (cu, cv) of the block the switch sits on. */
    private static Vec3 faceAt(Minecraft mc, Rig rig, double cu, double cv) {
        PanelBlockEntity owner = (PanelBlockEntity) mc.level.getBlockEntity(rig.pos());
        PanelGeometry.Frame frame = PanelGeometry.frame(owner.panelType(), owner.facing());
        BlockPos pos = owner.getBlockPos();
        return new Vec3(pos.getX() + frame.x(cu, cv, 0.0), pos.getY() + frame.y(cu, cv, 0.0), pos.getZ() + frame.z(cu, cv, 0.0));
    }

    /** The crosshair onto the switch at {@code fx} of its width (0 left edge, 1 right edge), half way down. */
    private static Act aim(double fx) {
        return (mc, rig) -> {
            PanelComponent component = client(mc, rig);
            look(mc, faceAt(mc, rig, component.x() + fx * component.w(), component.y() + 0.5 * component.h()));
            require(mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(rig.pos()),
                    rig.name() + ": the crosshair is not on the switch's panel block: " + mc.hitResult);
        };
    }

    /** The crosshair onto a bare spot of the same panel (the top left corner of the block's face). */
    private static Act aimBare() {
        return (mc, rig) -> {
            look(mc, faceAt(mc, rig, 0.4, 0.4));
            // On a slope the corner of the block's face may be the next block's step: any panel block will do, it is not the switch.
            require(mc.hitResult instanceof BlockHitResult hit && mc.level.getBlockEntity(hit.getBlockPos()) instanceof PanelBlockEntity,
                    rig.name() + ": the crosshair is not on the panel's bare corner: " + mc.hitResult);
        };
    }

    /** The crosshair off the panel altogether (up into the sky). */
    private static Act aimAway() {
        return (mc, rig) -> {
            turn(mc, mc.player.getYRot(), -70.0f);
            require(!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK,
                    rig.name() + ": the crosshair is still on a block: " + mc.hitResult);
        };
    }

    private static Act press() {
        return (mc, rig) -> down(mc);
    }

    private static Act release() {
        return (mc, rig) -> up(mc);
    }

    private static Act click() {
        return (mc, rig) -> flick(mc);
    }

    private static Act scroll(double delta) {
        return (mc, rig) -> {
            var event = new InputEvent.MouseScrollingEvent(0.0, delta, false, false, false, 0.0, 0.0);
            NeoForge.EVENT_BUS.post(event);
            require(event.isCanceled(), rig.name() + ": the scroll over the switch was not taken by the panel's handler");
        };
    }

    /**
     * A click's two packets delivered in the wrong order: the RELEASE (stamped later) first, then the PRESS (stamped
     * earlier), in the same tick, as the real packets a client would send. The server must drop the stale PRESS.
     */
    private static Act reordered(double side) {
        return (mc, rig) -> {
            int pressStamp = dev.bwr.mod.panel.client.PanelClientOperate.nextStamp();
            int releaseStamp = dev.bwr.mod.panel.client.PanelClientOperate.nextStamp();
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new dev.bwr.mod.panel.net.PanelPayloads.Operate(
                    rig.pos(), rig.id(), dev.bwr.mod.panel.component.OperateAction.RELEASE.ordinal(), 0.0, releaseStamp));
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new dev.bwr.mod.panel.net.PanelPayloads.Operate(
                    rig.pos(), rig.id(), dev.bwr.mod.panel.component.OperateAction.PRESS.ordinal(), side, pressStamp));
        };
    }

    private static Act both(Act first, Act second) {
        return (mc, rig) -> {
            first.run(mc, rig);
            second.run(mc, rig);
        };
    }

    // -- assertions

    /** The handle is at {@code position} on the server and the client (and posed there on the screen). */
    private static Act holds(int position, String when) {
        return (mc, rig) -> {
            PanelComponent component = client(mc, rig);
            Snap s = snap;
            boolean serverOk = s != null && s.rig() == rig && s.out0() == position && s.position() == position;
            boolean clientOk = component.output(0) == position
                    && component.output(1) == (position > 0 ? 1.0 : 0.0) && component.output(2) == (position < 0 ? 1.0 : 0.0);
            require(serverOk && clientOk, rig.name() + " " + when + ": expected the handle at " + position + " but " + describe(mc, rig));
            float drawn = RenderCache.of(component).drawnAngle();
            require(Float.isNaN(drawn) || drawn == Poses.controlSwitchAngle(position), rig.name() + " " + when
                    + ": the handle is at " + position + " but drawn at " + drawn + " degrees; " + describe(mc, rig));
        };
    }

    /** The handle is at NORMAL now (the crosshair may be anywhere, so only the outputs are read). */
    private static Act springsBack(String when) {
        return (mc, rig) -> {
            PanelComponent component = client(mc, rig);
            Snap s = snap;
            require(s != null && s.rig() == rig && s.out0() == 0 && s.position() == 0 && component.output(0) == 0
                    && component.output(1) == 0 && component.output(2) == 0,
                    rig.name() + " " + when + ": the handle did not spring back to NORMAL; " + describe(mc, rig));
        };
    }

    private static List<Timed> at(Object... steps) {
        List<Timed> list = new ArrayList<>();
        for (int i = 0; i < steps.length; i += 2) {
            list.add(new Timed((Integer) steps[i], (Act) steps[i + 1]));
        }
        return list;
    }

    private static Seq seq(String name, int expect, List<Timed> acts) {
        return new Seq(name, expect, SETTLE, false, acts, null);
    }

    private static final int LEFT = -1;
    private static final int RIGHT = 1;

    private static Act aimSide(int side) {
        return aim(side < 0 ? 0.15 : 0.85);
    }

    /** The sequences a player produces, run on every switch. */
    private static List<Seq> sequences() {
        List<Seq> all = new ArrayList<>();
        for (int side : new int[] {LEFT, RIGHT}) {
            String s = side < 0 ? "left" : "right";
            int op = side; // the left third is CLOSE (-1), the right third OPEN (+1)
            all.add(seq("quick-" + s + "-same-tick", op, at(0, both(aimSide(side), click()))));
            all.add(seq("quick-" + s + "-1-tick-apart", op, at(0, both(aimSide(side), press()), 1, release())));
            all.add(seq("hold-" + s + "-10-ticks", op, at(0, both(aimSide(side), press()), 9, holds(op, "held 10 ticks"), 10, release())));
        }
        all.add(seq("hold-left-40-ticks", LEFT, at(0, both(aimSide(LEFT), press()), 38, holds(-1, "held 40 ticks"), 40, release())));
        all.add(seq("click-centre-does-nothing", 0, at(0, both(aim(0.5), click()))));
        // The crosshair slides off with the key still down: the handle lets go when the hand does.
        all.add(seq("slide-off-same-tick-as-press", LEFT, at(0, both(aimSide(LEFT), press()), 1, aimAway(), 6, release())));
        all.add(new Seq("slide-off-to-bare-panel-held", LEFT, LEAVE + 3, false,
                at(0, both(aimSide(LEFT), press()), 3, aimBare(), 3 + LEAVE, springsBack("crosshair slid off the held switch onto bare panel"), 14, release()), null));
        all.add(new Seq("slide-off-to-sky-held", RIGHT, LEAVE + 3, false,
                at(0, both(aimSide(RIGHT), press()), 3, aimAway(), 3 + LEAVE, springsBack("crosshair slid off the held switch into the sky"), 14, release()), null));
        all.add(new Seq("slide-off-then-back-on", LEFT, SETTLE, false,
                at(0, both(aimSide(LEFT), press()), 3, aimAway(), 12, aimSide(LEFT), 14, release()), null));
        // Double clicks.
        all.add(seq("double-click-left-same-tick", LEFT, at(0, both(aimSide(LEFT), click()), 3, click())));
        all.add(seq("double-click-left-1-tick-apart", LEFT, at(0, both(aimSide(LEFT), press()), 1, release(), 2, press(), 3, release())));
        all.add(seq("double-click-left-then-right", RIGHT, at(0, both(aimSide(LEFT), click()), 3, both(aimSide(RIGHT), click()))));
        all.add(seq("double-click-right-then-left", LEFT, at(0, both(aimSide(RIGHT), click()), 3, both(aimSide(LEFT), click()))));
        all.add(seq("mash-five-clicks-alternating", LEFT, at(0, both(aimSide(LEFT), click()), 2, both(aimSide(RIGHT), click()),
                4, both(aimSide(LEFT), click()), 6, both(aimSide(RIGHT), click()), 8, both(aimSide(LEFT), click()))));
        // The packets of one click arriving in the wrong order (a RELEASE overtaking its PRESS): forced here, not left to load.
        all.add(new Seq("reordered-release-before-press-left", 0, SETTLE, false, at(0, reordered(-1.0), 4, springsBack("a stale PRESS (its RELEASE arrived first) latched the handle"), 8, springsBack("a stale PRESS latched the handle")), null));
        all.add(new Seq("reordered-release-before-press-right", 0, SETTLE, false, at(0, reordered(1.0), 4, springsBack("a stale PRESS (its RELEASE arrived first) latched the handle"), 8, springsBack("a stale PRESS latched the handle")), null));
        // The other side while one is held (one use key: the crosshair moves across with the key down).
        all.add(seq("other-side-while-held", RIGHT, at(0, both(aimSide(LEFT), press()), 3, aimSide(RIGHT), 10, holds(1, "moved to the other side while held"), 11, release())));
        // A scroll flick: one step, then the spring takes it back.
        all.add(new Seq("scroll-down-flick", LEFT, SPRING_AFTER_SCROLL, false, at(0, both(aim(0.5), scroll(-1.0))), null));
        all.add(new Seq("scroll-up-flick", RIGHT, SPRING_AFTER_SCROLL, false, at(0, both(aim(0.5), scroll(1.0))), null));
        // Sneak (Shift) down: the only modifier a bare hand can have.
        all.add(new Seq("sneak-quick-left", LEFT, SETTLE, true, at(0, both(aimSide(LEFT), click())), null));
        all.add(new Seq("sneak-hold-right-10-ticks", RIGHT, SETTLE, true, at(0, both(aimSide(RIGHT), press()), 9, holds(1, "sneaking, held 10 ticks"), 10, release()), null));
        return all;
    }

    // ---- the pictures (the closeups stage)

    private static void shot(Minecraft mc, String name) {
        try {
            var directory = mc.gameDirectory.toPath().resolve("panel-client-check");
            java.nio.file.Files.createDirectories(directory);
            CheckShots.save(mc, directory.resolve("switch-input-" + name + ".png"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fewest pixels that must change when the handle moves; at most this many may differ once it has sprung back. */
    private static final int MOVED_AT_LEAST = 6;
    private static final int RESTORED_AT_MOST = 12;

    private static Act shotAct(String suffix) {
        return (mc, rig) -> shot(mc, rig.look().name().substring(0, 2) + suffix);
    }

    private static List<Seq> closeups() {
        List<Seq> all = new ArrayList<>();
        // Both switches in the picture, never operated (the flag is dark).
        all.add(new Seq("fresh", 0, SETTLE, false, at(0, aim(0.5), 6, shotAct("-0-fresh")), null));
        // A first operation, so the flag already shows its colour in the pictures that follow.
        all.add(new Seq("first-operation", LEFT, SETTLE, false, at(0, both(aimSide(LEFT), click())),
                (mc, rig) -> {
                    restPicture = PanelSpringCheck.frame(mc);
                    shot(mc, rig.look().name().substring(0, 2) + "-1-neutral");
                }));
        all.add(new Seq("held-close", LEFT, SETTLE, false,
                at(0, both(aimSide(LEFT), press()), 8, (Act) (mc, rig) -> {
                    heldPicture = PanelSpringCheck.frame(mc);
                    shot(mc, rig.look().name().substring(0, 2) + "-2-held-close");
                }, 9, release()),
                (mc, rig) -> {
                    int[] after = PanelSpringCheck.frame(mc);
                    shot(mc, rig.look().name().substring(0, 2) + "-3-after-close");
                    if (RenderEnvironment.shaderPackInUse()) {
                        return; // a pack relights and anti-aliases every frame: the pictures are not comparable pixel for pixel
                    }
                    int moved = PanelSpringCheck.differing(restPicture, heldPicture);
                    int back = PanelSpringCheck.differing(restPicture, after);
                    LOG.info("SWITCH INPUT picture {}: {} pixels changed while held on CLOSE, {} after the spring", rig.name(), moved, back);
                    require(moved >= MOVED_AT_LEAST, rig.name() + ": the handle did not move on the screen when held on CLOSE (" + moved + " pixels)");
                    require(back <= RESTORED_AT_MOST, rig.name() + ": the handle is not back at NORMAL on the screen (" + back
                            + " pixels differ from the neutral picture)");
                }));
        all.add(new Seq("held-open", RIGHT, SETTLE, false,
                at(0, both(aimSide(RIGHT), press()), 8, shotAct("-4-held-open"), 9, release()),
                (mc, rig) -> shot(mc, rig.look().name().substring(0, 2) + "-5-after-open")));
        return all;
    }

    // ---- running

    private static void begin(int stage) {
        builtFor = stage;
        arrivalWait = 0;
        JOBS.clear();
        jobIndex = 0;
        phase = Phase.START;
        feet = camera(stage);
        List<Rig> rigs = RIGS.get(stage);
        List<Seq> seqs = LAYOUTS[stage].closeups() ? closeups() : sequences();
        for (Rig rig : rigs) {
            STATE.put(rig, new int[] {0, 0});
            for (Seq s : seqs) {
                JOBS.add(new Job(rig, s));
            }
        }
        LOG.info("SWITCH INPUT {}: {} switches, {} sequences each", LAYOUTS[stage].name(), rigs.size(), seqs.size());
    }

    private static void holdPlayer(Minecraft mc) {
        if (mc.player.position().distanceToSqr(feet) > 0.0025) {
            mc.player.setPos(feet.x, feet.y, feet.z);
        }
        mc.player.setDeltaMovement(Vec3.ZERO);
    }

    private static void takeSnapshot(Minecraft mc, Rig rig) {
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            if (level.getBlockEntity(rig.pos()) instanceof PanelBlockEntity be && be.component(rig.id()) != null) {
                PanelComponent c = be.component(rig.id());
                ControlSwitchData data = (ControlSwitchData) c.data();
                snap = new Snap(rig, c.output(0), c.output(1), c.output(2), data.position, data.lastOperated, data.spring);
            }
        });
    }

    private static int arrivalWait;
    private static int startWait;

    /** Whether the client has every switch of the stage (the fixture's chunks have been sent); waits up to 600 ticks. */
    private static boolean arrived(Minecraft mc, int stage) {
        for (Rig rig : RIGS.get(stage)) {
            if (!(mc.level.getBlockEntity(rig.pos()) instanceof PanelBlockEntity be) || be.component(rig.id()) == null) {
                require(++arrivalWait < 600, "the client never received the switches of " + LAYOUTS[stage].name() + " (" + rig.name()
                        + " at " + rig.pos() + ", block entity " + mc.level.getBlockEntity(rig.pos()) + ", player at " + mc.player.position() + ")");
                return false;
            }
        }
        return true;
    }

    private static boolean neutral(Minecraft mc, Rig rig) {
        Snap s = snap;
        PanelComponent c = client(mc, rig);
        return s != null && s.rig() == rig && s.out0() == 0 && s.out1() == 0 && s.out2() == 0 && s.position() == 0
                && c.output(0) == 0 && c.output(1) == 0 && c.output(2) == 0;
    }

    /** -Dbwr.switchInputSurvey=true (Gradle -PbwrSwitchInputSurvey): a failing sequence is logged and the run goes on (for the before/after table). */
    private static final boolean SURVEY = Boolean.getBoolean("bwr.switchInputSurvey");
    private static int failures;
    private static int recoverTicks;

    /** One tick of a stage (client thread). Returns true once every sequence on every switch of the stage has passed. */
    static boolean tick(Minecraft mc, int stage) {
        if (!SURVEY) {
            return tickInner(mc, stage);
        }
        try {
            if (phase == Phase.RECOVER) {
                // After a failed sequence: let go of everything and wait for the handle to be at NORMAL (at most 60 ticks).
                up(mc);
                holdPlayer(mc);
                takeSnapshot(mc, JOBS.get(jobIndex).rig);
                if (++recoverTicks >= 60 || !(mc.level.getBlockEntity(JOBS.get(jobIndex).rig.pos()) instanceof PanelBlockEntity be0) || be0.component(JOBS.get(jobIndex).rig.id()) == null || neutral(mc, JOBS.get(jobIndex).rig)) {
                    jobIndex++;
                    phase = Phase.START;
                }
                return false;
            }
            return tickInner(mc, stage);
        } catch (AssertionError e) {
            failures++;
            Job job = JOBS.get(jobIndex);
            LOG.info("SWITCH INPUT FAIL {} {}: {}", job.rig.name(), job.seq.name(), e.getMessage());
            phase = Phase.RECOVER;
            recoverTicks = 0;
            return false;
        }
    }

    private static boolean tickInner(Minecraft mc, int stage) {
        if (builtFor != stage) {
            begin(stage);
        }
        if (jobIndex >= JOBS.size()) {
            up(mc);
            KeyMapping.set(mc.options.keyShift.getKey(), false);
            LOG.info("SWITCH INPUT CHECK {} {}: {} sequences run, {} failed, worst return to NORMAL {} ticks", failures == 0 ? "PASS" : "FAIL",
                    LAYOUTS[stage].name(), jobsDone, failures, worst);
            return true;
        }
        holdPlayer(mc);
        if (!arrived(mc, stage)) {
            return false; // the chunks of the fixture have not reached the client yet
        }
        Job job = JOBS.get(jobIndex);
        Rig rig = job.rig;
        Seq seq = job.seq;
        takeSnapshot(mc, rig);
        int[] state = STATE.get(rig);
        switch (phase) {
            case START -> {
                // Wait for a snapshot of this switch's server copy (taken above, answered a tick or so later), then it must be at NORMAL.
                if (snap == null || snap.rig() != rig) {
                    require(++startWait < 20, rig.name() + ": no server snapshot of the switch");
                    break;
                }
                startWait = 0;
                require(neutral(mc, rig), rig.name() + ": the handle is not at NORMAL before " + seq.name() + "; " + describe(mc, rig));
                seqTick = 0;
                eventsFrom = EVENTS.size();
                phase = Phase.RUN;
                KeyMapping.set(mc.options.keyShift.getKey(), seq.sneak());
                runActs(mc, job);
            }
            case RUN -> {
                seqTick++;
                runActs(mc, job);
            }
            case WAIT -> {
                waitTicks++;
                if (neutral(mc, rig)) {
                    if (neutralRun++ == 0) {
                        firstNeutral = waitTicks;
                    }
                } else {
                    neutralRun = 0;
                }
                // At NORMAL for two observations in a row, and not before every packet of the sequence can have been answered
                // (a click's PRESS is sent the tick after the act, and the server answers a tick or two later).
                if (neutralRun >= 2 && waitTicks >= 6) {
                    latency = firstNeutral - 1;
                    worst = Math.max(worst, latency);
                    phase = Phase.LOOK;
                    lookTicks = 0;
                    up(mc);
                } else {
                    require(waitTicks <= seq.settle() + 3, rig.name() + " " + seq.name() + ": the handle was not back at NORMAL "
                            + waitTicks + " ticks after the last input (allowed " + seq.settle() + "); " + describe(mc, rig));
                }
            }
            case LOOK -> {
                // The crosshair back on the switch so the renderer draws it, then the picture of the state is read.
                if (lookTicks == 0) {
                    aim(0.5).run(mc, rig);
                }
                if (++lookTicks >= 4) {
                    verify(mc, job, state);
                    phase = Phase.GAP;
                    gap = 0;
                }
            }
            case GAP -> {
                if (++gap >= 2) {
                    jobIndex++;
                    jobsDone++;
                    phase = Phase.START;
                }
            }
        }
        return false;
    }

    private static void runActs(Minecraft mc, Job job) {
        int last = 0;
        for (Timed timed : job.seq.acts()) {
            last = Math.max(last, timed.at());
            if (timed.at() == seqTick) {
                timed.act().run(mc, job.rig);
            }
        }
        if (seqTick >= last) {
            phase = Phase.WAIT;
            waitTicks = 0;
            neutralRun = 0;
        }
    }

    /** The handle is at NORMAL; now the flag, the breaker, and the render state must be what the operation left. */
    private static void verify(Minecraft mc, Job job, int[] state) {
        Rig rig = job.rig;
        Seq seq = job.seq;
        PanelComponent component = client(mc, rig);
        ControlSwitchData data = (ControlSwitchData) component.data();
        if (seq.expect() != 0) {
            state[0] = seq.expect();
        }
        int expected = state[0];
        Snap s = snap;
        require(s != null && s.rig() == rig && s.spring(), rig.name() + " " + seq.name() + ": the switch lost its spring");
        require(s.lastOperated() == expected, rig.name() + " " + seq.name() + ": the server's target flag latch is " + s.lastOperated()
                + " but the last operation was " + expected);
        require(data.lastOperated == expected, rig.name() + " " + seq.name() + ": the client's target flag latch is " + data.lastOperated
                + " but the last operation was " + expected);
        RenderCache cache = RenderCache.of(component);
        float drawn = cache.drawnAngle();
        require(Float.isNaN(drawn) || drawn == 0.0f, rig.name() + " " + seq.name() + ": the output is at NORMAL but the handle is drawn at "
                + drawn + " degrees; " + describe(mc, rig));
        if (cache.usesBaked()) {
            int wantedFlag = FlagState.colour(LampData.active(component.input(0)), LampData.active(component.input(1)), expected, true);
            require(cache.drawnFlagRgb() == wantedFlag, rig.name() + " " + seq.name() + ": the target flag is drawn "
                    + Integer.toHexString(cache.drawnFlagRgb()) + " but should show " + Integer.toHexString(wantedFlag)
                    + " (last operation " + expected + ")");
        }
        // The breaker: a MOMENTARY plant sink acts on a non-zero close/open output and ignores the return to zero.
        int breaker = state[1];
        long key = rig.pos().asLong();
        boolean returned = false;
        for (Event e : EVENTS.subList(eventsFrom, EVENTS.size())) {
            if (e.pos() != key || e.id() != rig.id()) {
                continue;
            }
            if (e.index() == 2 && e.value() >= 0.5) {
                breaker = -1;
            } else if (e.index() == 1 && e.value() >= 0.5) {
                breaker = 1;
            }
            returned |= e.value() == 0.0;
        }
        state[1] = breaker;
        require(breaker == expected, rig.name() + " " + seq.name() + ": the breaker would be " + (breaker < 0 ? "CLOSED" : breaker > 0 ? "OPEN" : "untouched")
                + " but the last operation was " + (expected < 0 ? "CLOSE" : expected > 0 ? "OPEN" : "none"));
        if (job.seq.finish() != null) {
            job.seq.finish().run(mc, rig);
        }
        LOG.info("SWITCH INPUT PASS {} {}: handle back at NORMAL {} ticks after the last input, flag {}, breaker {}{}", rig.name(), seq.name(),
                latency, expected < 0 ? "CLOSE" : expected > 0 ? "OPEN" : "none", breaker < 0 ? "CLOSED" : breaker > 0 ? "OPEN" : "untouched",
                returned ? "" : " (no return event)");
    }
}
