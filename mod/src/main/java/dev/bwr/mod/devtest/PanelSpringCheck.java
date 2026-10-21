package dev.bwr.mod.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.client.RenderCache;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.registry.BwrBlocks;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The breaker spring stage of the panel client check: a player-like client operates control switches (every style and
 * size, spring on and off) through the real client handlers, and what the handle does is read back on the client.
 * A real breaker control switch springs back to NORMAL after CLOSE or OPEN, while the target flag keeps the last
 * operation; the stage asserts the handle is at NORMAL a short time after
 * <ul>
 *   <li>a held click (the use key goes down, the game repeats it, then it is let go),</li>
 *   <li>a quick tap (the key goes down and up at once),</li>
 *   <li>a scroll flick, and an absolute SET sent over the network,</li>
 *   <li>a PRESS that is never released (a client that vanished): the server's fail-safe lets go,</li>
 * </ul>
 * on the server's value, on the client's copy of it and on the angle the renderer drew, and that a switch without the
 * spring stays where it was put. The use key is driven through {@link KeyMapping} so the game's own key loop raises the
 * click, the repeat and the release; only the scroll event is posted by hand (the game raises it from the mouse).
 *
 * <p>Plan: a floor at y 125 around x 5600, z 5100, and a wall of 4 x 3 vertical panels with the first block at
 * x 5600, y 128, z 5100, the columns running west, seen from the north.
 */
final class PanelSpringCheck {
    private PanelSpringCheck() {
    }

    static final String NAME = "breaker-spring";
    private static final BlockPos CORNER = new BlockPos(5600, 128, 5100);
    private static final int COLUMNS = 4;
    private static final int ROWS = 3;
    /** How long the client may take to show a handle at NORMAL after the spring let go (ticks). */
    private static final int SETTLE = 14;

    private record Rig(String name, BlockPos owner, int id, boolean spring) {
    }

    private record Step(String name, int ticks, Consumer<Minecraft> action) {
    }

    private static final List<Rig> RIGS = new ArrayList<>();
    private static final List<Step> STEPS = new ArrayList<>();
    private static int index;
    private static int countdown;
    private static boolean builtSteps;
    /** The server's output 0 of each rig, sampled on the server thread every client tick. */
    private static volatile double[] serverOut = new double[0];
    /** The extremes the client's output 0 of the rig under test showed since the phase began. */
    private static double lo, hi;
    private static int current;
    /** Where the rig under test ought to be at rest (spring: NORMAL, otherwise wherever it was put). */
    private static int position;

    static Vec3 camera() {
        return new Vec3(CORNER.getX() - 1.0, 126.0, CORNER.getZ() - 2.8);
    }

    static Vec3 target() {
        return new Vec3(CORNER.getX() - 1.0, 127.6, CORNER.getZ());
    }

    // ---- building (server)

    /** {@code cx} is the left cell in the block, or -1 for centred; a switch that starts at cell 10 spans into the next block. */
    private record Look(String name, int w, int h, int cx, String style, String variant, boolean spring) {
    }

    private static final Look[] LOOKS = {
            new Look("ge-8x12", 8, 12, -1, PanelStyles.GE, "pistol", true),
            new Look("ge-12x16", 12, 16, -1, PanelStyles.GE, "pistol", true),
            new Look("ge-real-1x2", 1, 2, -1, PanelStyles.GE, "pistol", true),
            new Look("ge-real-2x3", 2, 3, -1, PanelStyles.GE, "pistol", true),
            new Look("w2-pistol-8x12", 8, 12, -1, PanelStyles.WESTINGHOUSE, "pistol", true),
            new Look("w2-heavy-12x16", 12, 16, -1, PanelStyles.WESTINGHOUSE, "pistol_hd", true),
            new Look("classic-8x12", 8, 12, -1, PanelStyles.CLASSIC, null, true),
            new Look("ge-8x12-no-spring", 8, 12, -1, PanelStyles.GE, "pistol", false),
            new Look("ge-12x12-spanning-two-blocks", 12, 12, 10, PanelStyles.GE, "pistol", true)};

    static void build(ServerLevel level) {
        RIGS.clear();
        for (int x = CORNER.getX() - 12; x <= CORNER.getX() + 8; x++) {
            for (int z = CORNER.getZ() - 8; z <= CORNER.getZ() + 6; z++) {
                level.setBlock(new BlockPos(x, 125, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = 126; y <= 132; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                level.setBlock(CORNER.relative(Direction.WEST, col).below(row), BwrBlocks.CONTROL_PANEL_VERTICAL.get()
                        .defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH), 3);
            }
        }
        for (int i = 0; i < LOOKS.length; i++) {
            Look look = LOOKS[i];
            BlockPos pos = CORNER.relative(Direction.WEST, i % COLUMNS).below(i / COLUMNS);
            PanelComponent component = PanelOps.placeDirect(level, pos, look.cx() >= 0 ? look.cx() : (16 - look.w()) / 2, (16 - look.h()) / 2,
                    ComponentTypes.CONTROL_SWITCH, Footprint.of(look.w(), look.h()));
            if (component == null) {
                throw new AssertionError("Spring check: placement refused " + look.name());
            }
            PanelModelShowcase.look(component, look.style(),
                    look.variant() != null ? look.variant() : PanelStyles.defaultVariant(ControlSwitchData.TYPE, look.style()));
            ((ControlSwitchData) component.data()).spring = look.spring();
            RIGS.add(new Rig(look.name(), pos, component.id(), look.spring()));
        }
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                if (level.getBlockEntity(CORNER.relative(Direction.WEST, col).below(row)) instanceof PanelBlockEntity be) {
                    be.syncFull();
                }
            }
        }
    }

    // ---- the script (client)

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError("Breaker spring check: " + message);
        }
    }

    private static PanelComponent client(Minecraft mc, Rig rig) {
        PanelComponent component = mc.level.getBlockEntity(rig.owner()) instanceof PanelBlockEntity be ? be.component(rig.id()) : null;
        require(component != null, "the client has no switch " + rig.name());
        return component;
    }

    /** Turns the player's crosshair onto the switch at {@code fx} of its width (0 left edge, 1 right edge). */
    private static void aim(Minecraft mc, Rig rig, double fx) {
        PanelBlockEntity owner = (PanelBlockEntity) mc.level.getBlockEntity(rig.owner());
        PanelComponent component = client(mc, rig);
        double cu = component.x() + fx * component.w();
        double cv = component.y() + 0.5 * component.h();
        PanelGeometry.Frame frame = PanelGeometry.frame(owner.panelType(), owner.facing());
        BlockPos pos = owner.getBlockPos();
        Vec3 target = new Vec3(pos.getX() + frame.x(cu, cv, 0.0), pos.getY() + frame.y(cu, cv, 0.0), pos.getZ() + frame.z(cu, cv, 0.0));
        Vec3 d = target.subtract(mc.player.getEyePosition(1.0f));
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.yHeadRot = yaw;
        mc.gameRenderer.pick(1.0f);
        require(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                        && mc.level.getBlockEntity(hit.getBlockPos()) instanceof PanelBlockEntity,
                rig.name() + ": the crosshair is not on the panel: " + mc.hitResult);
    }

    private static void down(Minecraft mc) {
        InputConstants.Key key = mc.options.keyUse.getKey();
        KeyMapping.click(key);
        KeyMapping.set(key, true);
    }

    private static void up(Minecraft mc) {
        KeyMapping.set(mc.options.keyUse.getKey(), false);
        releasedAt = ticks;
    }

    /** Client ticks seen by the stage, and the one the use key was let go at (-1 when not waiting for the spring). */
    private static int ticks;
    private static int releasedAt = -1;
    private static boolean outputSeen;

    /** The frame the game last drew, as pixels (what is on the screen, whatever the caches did). */
    private static int[] frame(Minecraft mc) {
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int w = image.getWidth();
            int[] out = new int[w * image.getHeight()];
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < w; x++) {
                    out[y * w + x] = image.getPixelRGBA(x, y);
                }
            }
            return out;
        }
    }

    /** How many pixels differ clearly (by more than 40 of 255 in brightness) between two frames. */
    private static int differing(int[] a, int[] b) {
        require(a != null && b != null && a.length == b.length, "the two frames have different sizes");
        int count = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i] && Math.abs(luma(a[i]) - luma(b[i])) > 40) {
                count++;
            }
        }
        return count;
    }

    private static int luma(int abgr) {
        return ((abgr & 0xFF) * 3 + (abgr >> 8 & 0xFF) * 6 + (abgr >> 16 & 0xFF)) / 10;
    }

    /** The picture of the switch at rest, after a first operation (so the target flag already shows its colour). */
    private static int[] restPicture;
    private static int[] heldPicture;
    /** Fewest pixels that must change when the handle moves; at most this many may change when it springs back. */
    private static final int MOVED_AT_LEAST = 6;
    private static final int RESTORED_AT_MOST = 12;

    private static void shot(Minecraft mc, String name) {
        try {
            var directory = mc.gameDirectory.toPath().resolve("panel-client-check");
            java.nio.file.Files.createDirectories(directory);
            CheckShots.save(mc, directory.resolve("breaker-spring-" + name + ".png"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void begin() {
        lo = Double.MAX_VALUE;
        hi = -Double.MAX_VALUE;
    }

    private static void sample(Minecraft mc) {
        if (!builtSteps || current >= RIGS.size() || mc.level == null) {
            return;
        }
        ticks++;
        Rig rig = RIGS.get(current);
        if (mc.level.getBlockEntity(rig.owner()) instanceof PanelBlockEntity be && be.component(rig.id()) != null) {
            double v = be.component(rig.id()).output(0);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
            if (releasedAt >= 0 && v == 0.0) {
                if (!outputSeen) {
                    outputSeen = true;
                    LogUtils.getLogger().info("BREAKER SPRING latency {}: the client output was NORMAL {} ticks after the key was let go",
                            rig.name(), ticks - releasedAt);
                }
                float drawn = RenderCache.of(be.component(rig.id())).drawnAngle();
                require(Float.isNaN(drawn) || drawn == 0.0f || ticks - releasedAt < 60, rig.name()
                        + ": the output is at NORMAL but the handle is still drawn at " + drawn + " degrees "
                        + (ticks - releasedAt) + " ticks after the key was let go");
                if (Float.isNaN(drawn) || drawn == 0.0f) {
                    LogUtils.getLogger().info("BREAKER SPRING latency {}: the drawn handle was at NORMAL {} ticks after the key was let go",
                            rig.name(), ticks - releasedAt);
                    releasedAt = -1;
                    outputSeen = false;
                }
            }
        }
        var server = mc.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                double[] out = new double[RIGS.size()];
                ServerLevel level = server.overworld();
                for (int i = 0; i < out.length; i++) {
                    Rig r = RIGS.get(i);
                    out[i] = level.getBlockEntity(r.owner()) instanceof PanelBlockEntity be && be.component(r.id()) != null
                            ? be.component(r.id()).output(0) : Double.NaN;
                }
                serverOut = out;
            });
        }
    }

    private static String describe(Minecraft mc, Rig rig) {
        PanelComponent component = client(mc, rig);
        float drawn = RenderCache.of(component).drawnAngle();
        return "client output " + component.output(0) + ", server output "
                + (current < serverOut.length ? serverOut[current] : Double.NaN) + ", drawn handle angle " + drawn
                + ", spring " + ((ControlSwitchData) component.data()).spring;
    }

    /** The handle is at {@code expected} on the server, on the client and in the picture. */
    private static void expectAt(Minecraft mc, Rig rig, int expected, String when) {
        PanelComponent component = client(mc, rig);
        double server = current < serverOut.length ? serverOut[current] : Double.NaN;
        // The drawn angle is read once per rendered frame, so it may trail the output by a frame or two (more under a shader
        // pack): it is only required to be right when the frame has caught up, which the latency check in sample() enforces.
        require(component.output(0) == expected && server == expected,
                rig.name() + " " + when + ": expected the handle at " + expected + " but " + describe(mc, rig));
    }

    private static void expectMoved(Minecraft mc, Rig rig, int to, String when) {
        require(to < 0 ? lo <= -1.0 : hi >= 1.0, rig.name() + " " + when + ": the handle never reached " + to + " on the client (saw "
                + lo + " .. " + hi + "); " + describe(mc, rig));
    }

    private static void add(String name, int ticks, Consumer<Minecraft> action) {
        STEPS.add(new Step(name, ticks, action));
    }

    private static int rest(Rig rig, int put) {
        return rig.spring() ? ControlSwitchData.NORMAL : put;
    }

    private static void script() {
        builtSteps = true;
        for (int k = 0; k < RIGS.size(); k++) {
            final int which = k;
            final Rig rig = RIGS.get(k);
            add(rig.name() + ": begin", 2, mc -> {
                current = which;
                position = 0;
                begin();
                aim(mc, rig, 0.15);
            });
            // 1. Hold the use key on the CLOSE third: the game repeats the click while it is down.
            add(rig.name() + ": hold CLOSE", 24, mc -> {
                begin();
                aim(mc, rig, 0.15);
                down(mc);
            });
            add(rig.name() + ": still held at CLOSE", 1, mc -> {
                expectAt(mc, rig, -1, "held on the CLOSE side");
                up(mc);
                begin();
            });
            add(rig.name() + ": CLOSE let go", SETTLE, mc -> {
                position = rest(rig, -1);
                expectAt(mc, rig, position, "after CLOSE was let go");
            });
            // 1b. The same on the screen: with the flag already latched on CLOSE, the handle is seen to move and to come back.
            if (rig.spring()) {
                add(rig.name() + ": rest picture", 6, mc -> restPicture = frame(mc));
                add(rig.name() + ": picture hold CLOSE", 24, mc -> {
                    aim(mc, rig, 0.15);
                    down(mc);
                });
                add(rig.name() + ": picture held", SETTLE, mc -> {
                    heldPicture = frame(mc);
                    shot(mc, rig.name() + "-held");
                    up(mc);
                });
                add(rig.name() + ": picture let go", 1, mc -> {
                    if (RenderEnvironment.shaderPackInUse()) {
                        return; // a pack relights and anti-aliases every frame: the pictures are not comparable pixel for pixel
                    }
                    int moved = differing(restPicture, heldPicture);
                    int back = differing(restPicture, frame(mc));
                    shot(mc, rig.name() + "-after");
                    LogUtils.getLogger().info("BREAKER SPRING picture {}: {} pixels changed while held, {} after the spring", rig.name(), moved, back);
                    require(moved >= MOVED_AT_LEAST, rig.name() + ": the handle did not move on the screen when held on CLOSE (" + moved
                            + " pixels changed); " + describe(mc, rig));
                    require(back <= RESTORED_AT_MOST, rig.name() + ": the handle is not back at NORMAL on the screen after the spring ("
                            + back + " pixels differ from the rest picture); " + describe(mc, rig));
                });
            }
            // 2. A quick tap on the OPEN third.
            add(rig.name() + ": tap OPEN", 1, mc -> {
                begin();
                aim(mc, rig, 0.85);
                down(mc);
            });
            add(rig.name() + ": tap OPEN let go", SETTLE, mc -> {
                up(mc);
            });
            add(rig.name() + ": after the tap", 1, mc -> {
                position = rest(rig, 1);
                expectAt(mc, rig, position, "after a tap on OPEN");
            });
            // 3. A scroll flick: from NORMAL one notch down goes to CLOSE.
            add(rig.name() + ": scroll", 3, mc -> {
                begin();
                aim(mc, rig, 0.5);
                var event = new InputEvent.MouseScrollingEvent(0.0, -1.0, false, false, false, 0.0, 0.0);
                NeoForge.EVENT_BUS.post(event);
                require(event.isCanceled(), rig.name() + ": the scroll over the switch was not taken by the panel's handler");
            });
            add(rig.name() + ": scrolled", SETTLE, mc -> {
                if (rig.spring()) {
                    expectMoved(mc, rig, -1, "after a scroll");
                }
            });
            add(rig.name() + ": after the scroll", 1, mc -> {
                position = rest(rig, Math.max(-1, position - 1));
                expectAt(mc, rig, position, "after a scroll flick");
            });
            // 4. An absolute SET over the network.
            add(rig.name() + ": SET OPEN", 3, mc -> {
                begin();
                PacketDistributor.sendToServer(new PanelPayloads.Operate(rig.owner(), rig.id(), OperateAction.SET.ordinal(), 1.0));
            });
            add(rig.name() + ": SET sent", SETTLE + 30, mc -> {
                if (rig.spring()) {
                    expectMoved(mc, rig, 1, "after SET to OPEN");
                }
            });
            add(rig.name() + ": after SET", 1, mc -> {
                position = rest(rig, 1);
                expectAt(mc, rig, position, "after SET to OPEN");
            });
            // 5. A PRESS that is never released (a client that vanished): the server's fail-safe lets go.
            if (rig.spring()) {
                add(rig.name() + ": PRESS without release", 2, mc -> {
                    begin();
                    PacketDistributor.sendToServer(new PanelPayloads.Operate(rig.owner(), rig.id(), OperateAction.PRESS.ordinal(), -1.0));
                });
                add(rig.name() + ": held by a vanished client", 52, mc -> expectMoved(mc, rig, -1, "after a PRESS"));
                add(rig.name() + ": after the fail-safe", 1, mc -> expectAt(mc, rig, 0, "after a PRESS that was never released"));
            }
            add(rig.name() + ": done", 1, mc -> {
                LogUtils.getLogger().info("BREAKER SPRING PASS {}: spring={}, rest position {}", rig.name(), rig.spring(), position);
            });
        }
        variants();
        add("variants: done", 1, mc -> LogUtils.getLogger().info("BREAKER SPRING PASS variants"));
    }

    /** A hold of {@code ticks} on the CLOSE third of the first switch, then let go; it must be at CLOSE while held and at NORMAL after. */
    private static void cycle(String label, int ticks) {
        Rig rig = RIGS.get(0);
        add(label + ": hold", ticks, mc -> {
            begin();
            aim(mc, rig, 0.15);
            down(mc);
        });
        add(label + ": held", 1, mc -> {
            expectAt(mc, rig, -1, label + ", still held after " + ticks + " ticks");
            up(mc);
        });
        add(label + ": let go", SETTLE, mc -> expectAt(mc, rig, 0, label + ", after it was let go"));
    }

    private static void inHand(net.minecraft.world.item.ItemStack stack, InteractionHand hand) {
        add("hand " + hand + " " + stack.getItem(), 6, mc -> {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> server.getPlayerList().getPlayer(uuid).setItemInHand(hand, stack.copy()));
        });
    }

    /** The situations a real player is in beyond a bare hand and a steady click: all on the first switch. */
    private static void variants() {
        Rig rig = RIGS.get(0);
        add("variants: begin", 2, mc -> {
            current = 0;
            position = 0;
            aim(mc, rig, 0.15);
        });
        add("flick: the key goes down and up between two ticks", 1, mc -> {
            begin();
            aim(mc, rig, 0.15);
            InputConstants.Key key = mc.options.keyUse.getKey();
            KeyMapping.click(key);
            KeyMapping.set(key, true);
            KeyMapping.set(key, false);
        });
        add("flick: settle", SETTLE, mc -> expectAt(mc, rig, 0, "after a flick"));
        cycle("long hold, longer than the fail-safe", 46);
        // The crosshair moves from one held switch to another with the key still down: the first must let go at once.
        Rig other = RIGS.get(1);
        add("hand-over: hold the first switch", 6, mc -> {
            current = 0;
            begin();
            aim(mc, rig, 0.15);
            down(mc);
        });
        add("hand-over: move to the second switch", 8, mc -> {
            expectAt(mc, rig, -1, "held on CLOSE before the crosshair moved on");
            aim(mc, other, 0.15);
        });
        add("hand-over: check", 1, mc -> {
            expectAt(mc, rig, 0, "after the crosshair moved to another switch with the key still down (the first one must be let go)");
            current = 1;
        });
        add("hand-over: second held", SETTLE, mc -> {
            expectAt(mc, other, -1, "the switch the crosshair moved to is held on CLOSE");
            up(mc);
        });
        add("hand-over: both let go", SETTLE, mc -> {
            expectAt(mc, other, 0, "after the key was let go");
            current = 0;
        });
        inHand(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE), InteractionHand.MAIN_HAND);
        cycle("an apple in the main hand", 12);
        inHand(net.minecraft.world.item.ItemStack.EMPTY, InteractionHand.MAIN_HAND);
        inHand(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD), InteractionHand.OFF_HAND);
        cycle("a shield in the off hand", 12);
        inHand(net.minecraft.world.item.ItemStack.EMPTY, InteractionHand.OFF_HAND);
        add("sneak down", 4, mc -> KeyMapping.set(mc.options.keyShift.getKey(), true));
        cycle("sneaking", 12);
        add("sneak up", 2, mc -> KeyMapping.set(mc.options.keyShift.getKey(), false));
    }

    /** One tick of the stage (client thread). Returns true once every step has run. */
    static boolean tick(Minecraft mc) {
        if (!builtSteps) {
            script();
        }
        sample(mc);
        if (countdown > 0) {
            countdown--;
            return false;
        }
        if (index >= STEPS.size()) {
            return true;
        }
        Step step = STEPS.get(index++);
        step.action().accept(mc);
        countdown = step.ticks();
        if (index >= STEPS.size()) {
            LogUtils.getLogger().info("BREAKER SPRING CHECK PASS: {} switches, {} steps", RIGS.size(), STEPS.size());
        }
        return false;
    }
}
