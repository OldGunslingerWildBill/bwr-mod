package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PanelInputType;
import dev.bwr.mod.panel.component.PanelStyles;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Breaker control switches spring back. A real breaker control switch returns to NORMAL after CLOSE or OPEN, while its
 * target flag keeps showing the last operation. Every style and size of the switch (the GE target pistol, the
 * Westinghouse pistols, the classic look, the real size 1 x 2 and 2 x 3 escutcheons) is driven through every input the
 * server has: a held click (PRESS, its keep-alive repeat, RELEASE), the vanilla right-click fallback, a scroll flick
 * (TURN), an absolute SET, and a PRESS that nobody ever releases (a client that vanished). With the spring on the handle
 * must be at NORMAL a short time after each, and the latched {@code lastOperated} (the flag) must stay on the last
 * operation; with the spring off the handle stays where it was put.
 *
 * <p>The real client path, with the game's own key loop, is the {@code breaker-spring} stage of the panel client check.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class BreakerSpringTests {
    private BreakerSpringTests() {
    }

    private static final BlockPos ORIGIN = new BlockPos(91_300, 110, 91_300);
    private static final int COLUMNS = 4;

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

    private static final class Rig {
        final Look look;
        final BlockPos pos;
        final PanelComponent component;
        /** Where a switch without the spring ought to be. */
        int plain;
        /** The last position that was not NORMAL (what the target flag shows). */
        int flag;

        Rig(Look look, BlockPos pos, PanelComponent component) {
            this.look = look;
            this.pos = pos;
            this.component = component;
        }

        ControlSwitchData data() {
            return (ControlSwitchData) component.data();
        }
    }

    private static void hold(ServerLevel level, boolean force) {
        for (int x = (ORIGIN.getX() - 16) >> 4; x <= (ORIGIN.getX() + 16) >> 4; x++) {
            for (int z = (ORIGIN.getZ() - 16) >> 4; z <= (ORIGIN.getZ() + 16) >> 4; z++) {
                level.setChunkForced(x, z, force);
                if (force) {
                    level.getChunk(x, z);
                }
            }
        }
    }

    private static void clear(ServerLevel level) {
        for (int x = -8; x <= 4; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -6; z <= 6; z++) {
                    BlockPos pos = ORIGIN.offset(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static void check(Rig rig, boolean ok, String message) {
        if (!ok) {
            throw new AssertionError("Breaker spring test, " + rig.look.name() + ": " + message + " (output "
                    + rig.component.output(0) + ", open " + rig.component.output(1) + ", close " + rig.component.output(2)
                    + ", lastOperated " + rig.data().lastOperated + ")");
        }
    }

    /** The handle is at {@code spring} (with the spring on) or {@code plain} (without), and the flag is on the last operation. */
    private static void expect(List<Rig> rigs, String when, int spring, int plain) {
        for (Rig rig : rigs) {
            int expected = rig.look.spring() ? spring : plain;
            rig.plain = plain;
            PanelComponent c = rig.component;
            check(rig, c.output(0) == expected && c.output(1) == (expected > 0 ? 1.0 : 0.0) && c.output(2) == (expected < 0 ? 1.0 : 0.0),
                    when + ": expected the handle at " + expected);
        }
    }

    private static void flag(List<Rig> rigs, String when, int spring, int plain) {
        for (Rig rig : rigs) {
            int expected = rig.look.spring() ? spring : plain;
            check(rig, rig.data().lastOperated == expected, when + ": the target flag should show " + expected);
        }
    }

    private static boolean operate(FakePlayer player, Rig rig, OperateAction action, double value) {
        return PanelOps.operate(player, rig.pos, rig.component.id(), action, value);
    }

    @GameTest(template = "empty", timeoutTicks = 700)
    public static void breakerSwitchSpringsBackToNormalWhateverMovedIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        hold(level, true);
        helper.runAfterDelay(2, () -> {
            try {
                clear(level);
                FakePlayer player = FakePlayerFactory.get(level, new GameProfile(
                        UUID.nameUUIDFromBytes("breaker-spring-test".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "breaker-spring-test"));
                player.setGameMode(GameType.SURVIVAL);
                player.setPos(ORIGIN.getX() - 1.0, ORIGIN.getY() - 1.0, ORIGIN.getZ() - 1.5);
                List<Rig> rigs = new ArrayList<>();
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < COLUMNS; col++) {
                        level.setBlock(ORIGIN.relative(Direction.WEST, col).below(row), BwrBlocks.CONTROL_PANEL_VERTICAL.get()
                                .defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH), 3);
                    }
                }
                for (int i = 0; i < LOOKS.length; i++) {
                    Look look = LOOKS[i];
                    BlockPos pos = ORIGIN.relative(Direction.WEST, i % COLUMNS).below(i / COLUMNS);
                    PanelComponent component = PanelOps.placeDirect(level, pos, look.cx() >= 0 ? look.cx() : (16 - look.w()) / 2, (16 - look.h()) / 2,
                            ComponentTypes.CONTROL_SWITCH, Footprint.of(look.w(), look.h()));
                    helper.assertTrue(component != null, look.name() + " did not fit");
                    ControlSwitchData data = (ControlSwitchData) component.data();
                    data.style = look.style();
                    data.variant = look.variant() != null ? look.variant() : PanelStyles.defaultVariant(ControlSwitchData.TYPE, look.style());
                    data.spring = look.spring();
                    rigs.add(new Rig(look, pos, component));
                }
                phases(helper, level, player, rigs);
            } catch (RuntimeException | Error e) {
                release(level);
                throw e;
            }
        });
    }

    private static void release(ServerLevel level) {
        clear(level);
        hold(level, false);
    }

    /** Runs {@code step} after {@code ticks}, releasing the fixture whatever happens. */
    private static void later(GameTestHelper helper, ServerLevel level, int ticks, Runnable step) {
        helper.runAfterDelay(ticks, () -> {
            try {
                step.run();
            } catch (RuntimeException | Error e) {
                release(level);
                throw e;
            }
        });
    }

    private static void phases(GameTestHelper helper, ServerLevel level, FakePlayer player, List<Rig> rigs) {
        // 1. A held click on CLOSE: PRESS, the keep-alive repeat while the key is down, RELEASE when it is let go.
        for (Rig rig : rigs) {
            helper.assertTrue(operate(player, rig, OperateAction.PRESS, -1.0), rig.look.name() + ": PRESS refused");
        }
        expect(rigs, "held on CLOSE", -1, -1);
        flag(rigs, "held on CLOSE", -1, -1);
        later(helper, level, 4, () -> {
            for (Rig rig : rigs) {
                operate(player, rig, OperateAction.PRESS, -1.0);
            }
            expect(rigs, "the repeat keeps it on CLOSE", -1, -1);
            for (Rig rig : rigs) {
                operate(player, rig, OperateAction.RELEASE, 0.0);
            }
            expect(rigs, "CLOSE let go", 0, -1);
            flag(rigs, "CLOSE let go", -1, -1);
            // 2. The vanilla right-click fallback on the OPEN third: a pulse, then the spring.
            for (Rig rig : rigs) {
                PanelComponent c = rig.component;
                double fx = c.x() + 0.85 * c.w();
                // A switch that spans two blocks is clicked in the second one (the next column, to the west).
                BlockPos clicked = fx >= 16 ? rig.pos.relative(Direction.WEST) : rig.pos;
                fx -= clicked.equals(rig.pos) ? 0 : 16;
                PanelBlockEntity be = (PanelBlockEntity) level.getBlockEntity(clicked);
                helper.assertTrue(PanelOps.forwardUse(player, be, (int) Math.floor(fx), c.y() + c.h() / 2, fx, c.y() + c.h() / 2.0)
                        .consumesAction(), rig.look.name() + ": the click was not consumed");
            }
            expect(rigs, "clicked on OPEN", 1, 1);
            flag(rigs, "clicked on OPEN", 1, 1);
            later(helper, level, PanelInputType.PULSE_TICKS + 3, () -> {
                expect(rigs, "after the vanilla click", 0, 1);
                flag(rigs, "after the vanilla click", 1, 1);
                // 3. A scroll flick down: CLOSE for a spring return switch (one step down from the plain one's OPEN).
                for (Rig rig : rigs) {
                    helper.assertTrue(operate(player, rig, OperateAction.TURN, -1.0), rig.look.name() + ": TURN refused");
                }
                expect(rigs, "scrolled", -1, 0);
                later(helper, level, PanelInputType.PULSE_TICKS + 3, () -> {
                    expect(rigs, "after the scroll", 0, 0);
                    flag(rigs, "after the scroll", -1, 1);
                    // 4. An absolute SET to OPEN: the fail-safe lets go of a spring return switch.
                    for (Rig rig : rigs) {
                        helper.assertTrue(operate(player, rig, OperateAction.SET, 1.0), rig.look.name() + ": SET refused");
                    }
                    expect(rigs, "set to OPEN", 1, 1);
                    flag(rigs, "set to OPEN", 1, 1);
                    later(helper, level, PanelInputType.HOLD_FAILSAFE_TICKS + 3, () -> {
                        expect(rigs, "after SET", 0, 1);
                        flag(rigs, "after SET", 1, 1);
                        // 5. A PRESS nobody releases (the client vanished): the fail-safe lets go.
                        for (Rig rig : rigs) {
                            operate(player, rig, OperateAction.PRESS, -1.0);
                        }
                        expect(rigs, "pressed and abandoned", -1, -1);
                        later(helper, level, PanelInputType.HOLD_FAILSAFE_TICKS + 3, () -> {
                            try {
                                expect(rigs, "after the abandoned PRESS", 0, -1);
                                flag(rigs, "after the abandoned PRESS", -1, -1);
                            } finally {
                                release(level);
                            }
                            helper.succeed();
                        });
                    });
                });
            });
        });
    }
}
