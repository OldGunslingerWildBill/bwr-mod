package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelPlayerSessions;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Found by the dedicated server check (C3, {@code MpServerCheck}): a held rod motion control (a pushbutton wired to
 * {@code selected_rod_withdraw_continuous}, follow mode) is let go by the panel's own fail-safe timer a second and a half
 * after the player's last press, so a player who drops off the network stops the rod. But the timer lives in
 * {@code PanelTimers}, which drops the entry of a panel whose chunk is no longer loaded, and the "held" flag lives in the
 * reactor controller, in another chunk that may well stay loaded. A player who disconnected while holding, with nobody else
 * near the panel, therefore left the continuous motion running for good: the release never reached the reactor. The
 * panel now lets go of its held outputs when its chunk unloads or the block is removed.
 *
 * <p>Fixture origin 85_900 / 85_900 for the reactor ({@link FcdTestSupport}), the panel 700 blocks east in a chunk of its own.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelHeldControlTests {
    static final int ORIGIN = 85_900;

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 900)
    public static void heldRodControlIsReleasedWhenItsPanelChunkUnloads(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos min = FcdTestSupport.slot(ORIGIN, 0);
        BlockPos panelAt = new BlockPos(ORIGIN + 700, 190, ORIGIN);
        int cx = panelAt.getX() >> 4, cz = panelAt.getZ() >> 4;
        FcdTestSupport.hold(level, min, 9, 9, true);
        FixtureChunks.force(level, cx, cz, true);
        h.runAfterDelay(2, () -> {
            try {
                ReactorControllerBlockEntity reactor = FcdTestSupport.formed(h, min, 9, 9);
                level.getChunk(cx, cz);
                level.setBlock(panelAt, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, Direction.NORTH), 3);
                PanelComponent button = PanelOps.placeDirect(level, panelAt, 1, 1, ComponentTypes.PUSHBUTTON, Footprint.of(6, 6));
                h.assertTrue(button != null, "the pushbutton did not fit");
                button.addSink(0, new PlantSink(reactor.getBlockPos(), "selected_rod_withdraw_continuous", 1.0));
                SignalProvider rp = SignalProviders.of(reactor);
                h.assertTrue(rp != null && rp.command("select_rod", 3), "select_rod 3 failed");
                PanelBlockEntity panel = (PanelBlockEntity) level.getBlockEntity(panelAt);
                // The player presses and holds: the client repeats PRESS, then the player drops off the network.
                ComponentType.OperateContext press = new ComponentType.OperateContext(level, null, panel, button);
                h.assertTrue(button.type().operate(press, OperateAction.PRESS, 1.0), "the press was refused");
                h.assertTrue(reactor.selectedRodHeld(1), "the held button did not hold the rod's continuous withdraw");
                FixtureChunks.force(level, cx, cz, false); // nobody is near the panel any more
                waitForUnload(h, level, reactor, panelAt, cx, cz, min, 0);
            } catch (RuntimeException | Error e) {
                cleanUp(level, panelAt, cx, cz, min);
                throw e;
            }
        });
    }

    private static void waitForUnload(GameTestHelper h, ServerLevel level, ReactorControllerBlockEntity reactor, BlockPos panelAt,
                                      int cx, int cz, BlockPos min, int waited) {
        h.runAfterDelay(5, () -> {
            try {
                if (level.hasChunk(cx, cz)) {
                    h.assertTrue(waited < 300, "the panel chunk did not unload in 300 ticks");
                    waitForUnload(h, level, reactor, panelAt, cx, cz, min, waited + 5);
                    return;
                }
                // The chunk is gone, the reactor's chunks are still loaded: nothing may keep withdrawing the rod.
                h.assertTrue(!reactor.selectedRodHeld(1), "the rod's continuous withdraw is still held after the panel that held it unloaded ("
                        + waited + " ticks after the last press, panel chunk gone)");
                int demand = reactor.rods().getCommandedNotchIndex(2);
                h.runAfterDelay(80, () -> {
                    try {
                        h.assertTrue(reactor.rods().getCommandedNotchIndex(2) == demand,
                                "the rod kept moving after its panel unloaded: demand " + demand + " -> " + reactor.rods().getCommandedNotchIndex(2));
                    } finally {
                        cleanUp(level, panelAt, cx, cz, min);
                    }
                    h.succeed();
                });
            } catch (RuntimeException | Error e) {
                cleanUp(level, panelAt, cx, cz, min);
                throw e;
            }
        });
    }

    private static void cleanUp(ServerLevel level, BlockPos panelAt, int cx, int cz, BlockPos min) {
        try {
            FixtureChunks.force(level, cx, cz, true);
            level.getChunk(cx, cz);
            level.setBlock(panelAt, Blocks.AIR.defaultBlockState(), 3);
        } finally {
            FixtureChunks.force(level, cx, cz, false);
            FcdTestSupport.release(level, min, 9, 9);
        }
    }

    // ---- a held control must be let go whenever its component or its wiring goes

    /** A formed reactor, a panel on its wall and a pushbutton on rod withdraw that is pressed and held. */
    private record Held(ServerLevel level, BlockPos panelAt, ReactorControllerBlockEntity reactor, PanelBlockEntity panel,
                        PanelComponent button, FakePlayer player) {
    }

    private static void held(GameTestHelper h, int slot, Consumer<Held> body) {
        ServerLevel level = h.getLevel();
        BlockPos min = FcdTestSupport.slot(ORIGIN, slot);
        FcdTestSupport.hold(level, min, 9, 9, true);
        h.runAfterDelay(2, () -> {
            try {
                ReactorControllerBlockEntity reactor = FcdTestSupport.formed(h, min, 9, 9);
                BlockPos panelAt = FcdTestSupport.wallOrigin(min);
                FcdTestSupport.clearWall(level, min);
                level.setBlock(panelAt, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, Direction.NORTH), 3);
                PanelComponent button = PanelOps.placeDirect(level, panelAt, 1, 1, ComponentTypes.PUSHBUTTON, Footprint.of(6, 6));
                h.assertTrue(button != null, "the pushbutton did not fit");
                button.addSink(0, new PlantSink(reactor.getBlockPos(), "selected_rod_withdraw_continuous", 1.0));
                SignalProvider rp = SignalProviders.of(reactor);
                h.assertTrue(rp != null && rp.command("select_rod", 3), "select_rod 3 failed");
                PanelBlockEntity panel = (PanelBlockEntity) level.getBlockEntity(panelAt);
                FakePlayer player = FakePlayerFactory.get(level, new GameProfile(
                        UUID.nameUUIDFromBytes(("held-control-" + slot).getBytes(StandardCharsets.UTF_8)), "held-" + slot));
                player.setGameMode(GameType.SURVIVAL);
                player.getInventory().clearContent();
                player.setPos(panelAt.getX() + 0.5, panelAt.getY() - 1.0, panelAt.getZ() - 1.5);
                press(level, panel, button);
                h.assertTrue(reactor.selectedRodHeld(1), "the held button did not hold the continuous withdraw of the rod");
                body.accept(new Held(level, panelAt, reactor, panel, button, player));
            } catch (RuntimeException | Error e) {
                FcdTestSupport.clearWall(level, min);
                FcdTestSupport.release(level, min, 9, 9);
                throw e;
            }
            FcdTestSupport.clearWall(level, min);
            FcdTestSupport.release(level, min, 9, 9);
            h.succeed();
        });
    }

    private static void press(ServerLevel level, PanelBlockEntity panel, PanelComponent button) {
        ComponentType.OperateContext context = new ComponentType.OperateContext(level, null, panel, button);
        if (!button.type().operate(context, OperateAction.PRESS, 1.0)) {
            throw new AssertionError("the press was refused");
        }
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 400)
    public static void heldRodControlIsReleasedWhenItsPanelBlockIsBroken(GameTestHelper h) {
        held(h, 1, t -> {
            t.level().setBlock(t.panelAt(), Blocks.AIR.defaultBlockState(), 3);
            h.assertTrue(!t.reactor().selectedRodHeld(1), "the continuous withdraw of the rod is still held after its panel block was broken");
        });
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 400)
    public static void heldRodControlIsReleasedWhenItsComponentIsWrenchedOff(GameTestHelper h) {
        held(h, 2, t -> {
            t.player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
            h.assertTrue(PanelOps.remove(t.player(), t.panelAt(), t.button().id()), "the wrench did not remove the pushbutton");
            h.assertTrue(t.panel().component(t.button().id()) == null, "the pushbutton is still on the panel");
            h.assertTrue(!t.reactor().selectedRodHeld(1), "the continuous withdraw of the rod is still held after its component was wrenched off");
        });
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = 400)
    public static void heldRodControlIsReleasedWhenItsOutputIsRewiredToNothing(GameTestHelper h) {
        held(h, 3, t -> {
            t.player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_LINKER.get()));
            // Removing the sink of a held output lets go first...
            var removeSink = new PanelPayloads.Bind(t.panelAt(), t.button().id(), true, 0, WiringOps.BindKind.REMOVE_SINK.ordinal(),
                    BlockPos.ZERO, "", BlockPos.ZERO, 0, 0, 1.0, 0, 0.0);
            h.assertTrue(WiringOps.bind(t.player(), removeSink) == WiringOps.Result.OK, "removing the sink was refused");
            h.assertTrue(!t.reactor().selectedRodHeld(1), "the continuous withdraw of the rod is still held after its sink was removed");
            // ...and so does clearing the output: wire it again, press again (the client repeats PRESS), then clear.
            t.button().addSink(0, new PlantSink(t.reactor().getBlockPos(), "selected_rod_withdraw_continuous", 1.0));
            press(t.level(), t.panel(), t.button());
            h.assertTrue(t.reactor().selectedRodHeld(1), "the pressed button did not hold the rod again");
            var none = new PanelPayloads.Bind(t.panelAt(), t.button().id(), true, 0, WiringOps.BindKind.NONE.ordinal(),
                    BlockPos.ZERO, "", BlockPos.ZERO, 0, 0, 1.0, 0, 0.0);
            h.assertTrue(WiringOps.bind(t.player(), none) == WiringOps.Result.OK, "clearing the output was refused");
            h.assertTrue(t.button().sinks(0).isEmpty(), "the output still has sinks");
            h.assertTrue(!t.reactor().selectedRodHeld(1), "the continuous withdraw of the rod is still held after its output was rewired to nothing");
        });
    }

    // ---- press and release stamps are per login session

    /**
     * The client counts its PRESS / RELEASE stamps from 1 on every game launch, but a panel whose chunk stays loaded
     * remembers the last stamps of each player. A player who relaunched found every holdable control dead until the new
     * counter passed the old one. Stamps are now tied to the login session of the player.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void stampsOfAnEarlierLoginSessionDoNotBlockANewPress(GameTestHelper helper) {
        HardwareStage.run(helper, 450_000, 450_000, "stamp-session", s -> {
            PanelComponent button = s.place(ComponentTypes.PUSHBUTTON, 1, 1, Footprint.of(6, 6));
            UUID who = s.player.getUUID();
            PanelPlayerSessions.begin(who);
            try {
                // A long first session: the last click of it was stamped 39 (press) and 40 (release).
                helper.assertTrue(PanelOps.operate(s.player, s.origin, button.id(), OperateAction.PRESS, 1.0, 39) && button.output(0) == 1.0,
                        "session 1: the press works");
                helper.assertTrue(PanelOps.operate(s.player, s.origin, button.id(), OperateAction.RELEASE, 0.0, 40) && button.output(0) == 0.0,
                        "session 1: the release works");
                // Within the session the anti-reorder rule still holds: a late copy of an older press is dropped.
                PanelOps.operate(s.player, s.origin, button.id(), OperateAction.PRESS, 1.0, 38);
                helper.assertTrue(button.output(0) == 0.0, "session 1: a press stamped before the last release is still ignored");
                // The player quits and relaunches (the chunk stays loaded): the new client starts at stamp 1.
                PanelPlayerSessions.end(who);
                PanelPlayerSessions.begin(who);
                helper.assertTrue(PanelOps.operate(s.player, s.origin, button.id(), OperateAction.PRESS, 1.0, 1) && button.output(0) == 1.0,
                        "session 2: the first press (stamp 1) is blocked by the stamps of session 1");
                helper.assertTrue(PanelOps.operate(s.player, s.origin, button.id(), OperateAction.RELEASE, 0.0, 2) && button.output(0) == 0.0,
                        "session 2: the release works");
                // The rule applies inside the new session as well.
                PanelOps.operate(s.player, s.origin, button.id(), OperateAction.PRESS, 1.0, 2);
                helper.assertTrue(button.output(0) == 0.0, "session 2: a press with the stamp of the last release is not ignored");
                helper.assertTrue(PanelOps.operate(s.player, s.origin, button.id(), OperateAction.PRESS, 1.0, 3) && button.output(0) == 1.0,
                        "session 2: a newer press does not work");
                PanelOps.operate(s.player, s.origin, button.id(), OperateAction.RELEASE, 0.0, 4);
            } finally {
                PanelPlayerSessions.end(who);
            }
        });
    }
}
