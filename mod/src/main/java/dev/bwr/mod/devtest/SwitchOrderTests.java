package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ControlSwitchData;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The server ends at NORMAL whatever order the press and release of a click reach it in. A client stamps the presses and
 * releases of a hold in the order the player made them ({@code Operate.seq}); a PRESS that reaches the server after the
 * RELEASE that ended its hold is stale and must not latch the switch at CLOSE (it would stay there until the fail-safe,
 * 1.5 s, with the plant commanded) and a RELEASE older than the PRESS now held must not let go of it. The order is forced
 * here by delivering the packets to the real server entry point in the wrong order, never by timing.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class SwitchOrderTests {
    private SwitchOrderTests() {
    }

    private static final int ORIGIN_X = 335_000;
    private static final int ORIGIN_Z = 96_700;

    private static boolean op(FakePlayer player, HardwareStage s, PanelComponent sw, OperateAction action, double value, int seq) {
        return PanelOps.operate(player, s.origin, sw.id(), action, value, seq);
    }

    private static String state(PanelComponent sw) {
        return "output " + sw.output(0) + ", open " + sw.output(1) + ", close " + sw.output(2) + ", lastOperated "
                + ((ControlSwitchData) sw.data()).lastOperated;
    }

    private static void expect(GameTestHelper helper, PanelComponent sw, int position, int flag, String when) {
        helper.assertTrue(sw.output(0) == position && sw.output(1) == (position > 0 ? 1.0 : 0.0) && sw.output(2) == (position < 0 ? 1.0 : 0.0)
                        && ((ControlSwitchData) sw.data()).lastOperated == flag,
                when + ": expected the handle at " + position + " and the flag on " + flag + ", got " + state(sw));
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aReleaseThatArrivesBeforeItsPressLeavesTheSwitchAtNormal(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "switch-order", s -> {
            PanelComponent sw = s.place(ComponentTypes.CONTROL_SWITCH, 0, 0, Footprint.of(8, 12));
            FakePlayer p = s.player;
            // The click's PRESS (stamp 1) and RELEASE (stamp 2) in the order they were made: held, then let go.
            helper.assertTrue(op(p, s, sw, OperateAction.PRESS, -1.0, 1), "PRESS refused");
            expect(helper, sw, -1, -1, "pressed");
            helper.assertTrue(op(p, s, sw, OperateAction.RELEASE, 0.0, 2), "RELEASE refused");
            expect(helper, sw, 0, -1, "released in order");

            // The same click with the packets swapped: RELEASE (stamp 4) first, the stale PRESS (stamp 3) after it.
            op(p, s, sw, OperateAction.RELEASE, 0.0, 4);
            op(p, s, sw, OperateAction.PRESS, 1.0, 3);
            expect(helper, sw, 0, -1, "a PRESS that arrived after its own RELEASE must not latch (and must not fire OPEN)");
            // ...including the keep-alive repeats of that hold that were still on the wire.
            op(p, s, sw, OperateAction.PRESS, 1.0, 3);
            op(p, s, sw, OperateAction.PRESS, 1.0, 4);
            expect(helper, sw, 0, -1, "late repeats of a finished hold");

            // The next click is a new hold and works: its stamps are higher.
            helper.assertTrue(op(p, s, sw, OperateAction.PRESS, 1.0, 5), "the next PRESS was refused");
            expect(helper, sw, 1, 1, "a new click after a stale one");
            // A RELEASE of the older hold (stamp 2) reaching the server now must not let go of the new one.
            op(p, s, sw, OperateAction.RELEASE, 0.0, 2);
            expect(helper, sw, 1, 1, "a RELEASE older than the PRESS now held");
            op(p, s, sw, OperateAction.RELEASE, 0.0, 6);
            expect(helper, sw, 0, 1, "the new hold let go in order");

            // A PRESS and its RELEASE in the same tick, in order and swapped, both end at NORMAL.
            op(p, s, sw, OperateAction.PRESS, -1.0, 7);
            op(p, s, sw, OperateAction.RELEASE, 0.0, 8);
            expect(helper, sw, 0, -1, "PRESS and RELEASE in one tick");
            op(p, s, sw, OperateAction.RELEASE, 0.0, 10);
            op(p, s, sw, OperateAction.PRESS, 1.0, 9);
            expect(helper, sw, 0, -1, "RELEASE and PRESS swapped in one tick");

            // Another player's presses are not judged against this player's stamps (each client counts for itself).
            FakePlayer other = FakePlayerFactory.get(s.level, new GameProfile(
                    UUID.nameUUIDFromBytes("switch-order-other".getBytes(StandardCharsets.UTF_8)), "switch-order-other"));
            other.setGameMode(GameType.SURVIVAL);
            other.setPos(s.origin.getX() + 0.5, s.origin.getY() - 1.0, s.origin.getZ() - 1.5);
            helper.assertTrue(op(other, s, sw, OperateAction.PRESS, 1.0, 1), "another player's PRESS was refused");
            expect(helper, sw, 1, 1, "another player's low stamp");
            op(other, s, sw, OperateAction.RELEASE, 0.0, 2);
            expect(helper, sw, 0, 1, "another player let go");

            // An unstamped packet (a program, an older client) is never judged: PRESS after RELEASE still presses.
            op(p, s, sw, OperateAction.RELEASE, 0.0, 0);
            op(p, s, sw, OperateAction.PRESS, -1.0, 0);
            expect(helper, sw, -1, -1, "unstamped press");
            op(p, s, sw, OperateAction.RELEASE, 0.0, 0);
            expect(helper, sw, 0, -1, "unstamped release");
        });
    }
}
