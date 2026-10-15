package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
        level.setChunkForced(cx, cz, true);
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
                level.setChunkForced(cx, cz, false); // nobody is near the panel any more
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
            level.setChunkForced(cx, cz, true);
            level.getChunk(cx, cz);
            level.setBlock(panelAt, Blocks.AIR.defaultBlockState(), 3);
        } finally {
            level.setChunkForced(cx, cz, false);
            FcdTestSupport.release(level, min, 9, 9);
        }
    }
}
