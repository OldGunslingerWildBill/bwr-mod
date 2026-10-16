package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.component.RecorderAlarmLogic;
import dev.bwr.mod.panel.component.RecorderAlarmLogic.Alarm;
import dev.bwr.mod.panel.component.RecorderAlarmLogic.Event;
import dev.bwr.mod.panel.component.RecorderAlarmLogic.State;
import dev.bwr.mod.panel.component.RecorderAlarmLogic.Type;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The paperless recorder's alarm state machine ({@link RecorderAlarmLogic}), run without a world as the spec's build
 * order asks: every alarm type, hysteresis, delay, rate of change, hold and non-hold indication, acknowledgement and
 * no signal (NaN) holding the state. Nothing here touches a level, so there is no fixture to clean up.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class RecorderAlarmLogicTests {
    private RecorderAlarmLogicTests() {
    }

    private static void is(GameTestHelper h, Object actual, Object expected, String what) {
        h.assertTrue(actual.equals(expected), what + ": expected " + expected + " but was " + actual);
    }

    @GameTest(template = "empty")
    public static void highAndLowAlarmsWithHysteresis(GameTestHelper h) {
        Alarm high = new Alarm();
        is(h, high.step(Type.HIGH, 5, 10, 2, 0, false, 0), Event.NONE, "below the limit");
        is(h, high.step(Type.HIGH, 10, 10, 2, 0, false, 2), Event.NONE, "exactly at the limit is not above it");
        is(h, high.step(Type.HIGH, 10.5, 10, 2, 0, false, 4), Event.ACTIVATED, "above the limit");
        is(h, high.state(), State.ACTIVE, "non-hold active");
        is(h, high.step(Type.HIGH, 8.5, 10, 2, 0, false, 6), Event.NONE, "inside the hysteresis band the alarm stays");
        is(h, high.step(Type.HIGH, 8.0, 10, 2, 0, false, 8), Event.RELEASED, "back by the full hysteresis (8 is not above 10 - 2)");
        is(h, high.state(), State.NORMAL, "released");
        is(h, high.step(Type.HIGH, 10.5, 10, 2, 0, false, 10), Event.ACTIVATED, "it can come on again");

        Alarm low = new Alarm();
        is(h, low.step(Type.LOW, 5, 3, 1, 0, false, 0), Event.NONE, "above the low limit");
        is(h, low.step(Type.LOW, 3, 3, 1, 0, false, 2), Event.NONE, "exactly at the limit is not below it");
        is(h, low.step(Type.LOW, 2.9, 3, 1, 0, false, 4), Event.ACTIVATED, "below the low limit");
        is(h, low.step(Type.LOW, 3.9, 3, 1, 0, false, 6), Event.NONE, "inside the hysteresis band");
        is(h, low.step(Type.LOW, 4.0, 3, 1, 0, false, 8), Event.RELEASED, "back above limit plus hysteresis");

        Alarm plain = new Alarm();
        is(h, plain.step(Type.HIGH, 11, 10, 0, 0, false, 0), Event.ACTIVATED, "no hysteresis: on");
        is(h, plain.step(Type.HIGH, 10, 10, 0, 0, false, 2), Event.RELEASED, "no hysteresis: off at the limit");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void delayedAlarmsNeedTheConditionForTheWholeDelay(GameTestHelper h) {
        Alarm delayed = new Alarm();
        // T: 3 s = 60 ticks.
        is(h, delayed.step(Type.DELAY_HIGH, 11, 10, 0, 3, false, 100), Event.NONE, "condition starts");
        is(h, delayed.step(Type.DELAY_HIGH, 11, 10, 0, 3, false, 159), Event.NONE, "59 ticks is not 60");
        is(h, delayed.step(Type.DELAY_HIGH, 11, 10, 0, 3, false, 160), Event.ACTIVATED, "60 ticks");
        is(h, delayed.step(Type.DELAY_HIGH, 9, 10, 0, 3, false, 162), Event.RELEASED, "releases at once");

        Alarm blip = new Alarm();
        blip.step(Type.DELAY_HIGH, 11, 10, 0, 3, false, 0);
        is(h, blip.step(Type.DELAY_HIGH, 9, 10, 0, 3, false, 40), Event.NONE, "dropped out before the delay");
        blip.step(Type.DELAY_HIGH, 11, 10, 0, 3, false, 50);
        is(h, blip.step(Type.DELAY_HIGH, 11, 10, 0, 3, false, 100), Event.NONE, "the delay restarted at 50, only 50 ticks ran");
        is(h, blip.step(Type.DELAY_HIGH, 11, 10, 0, 3, false, 110), Event.ACTIVATED, "60 ticks after the restart");

        Alarm low = new Alarm();
        low.step(Type.DELAY_LOW, 1, 5, 0, 1, false, 0);
        is(h, low.step(Type.DELAY_LOW, 1, 5, 0, 1, false, 19), Event.NONE, "delay low: 19 ticks");
        is(h, low.step(Type.DELAY_LOW, 1, 5, 0, 1, false, 20), Event.ACTIVATED, "delay low: 1 s");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void rateOfChangeAlarmsLookBackWholeScans(GameTestHelper h) {
        Alarm rise = new Alarm();
        // One scan is 20 ticks; the interval is 3 scans and the limit a rise of 5 over it.
        double[] values = {0, 0, 0, 0, 10};
        Event last = Event.NONE;
        for (int i = 0; i < values.length; i++) {
            last = rise.step(Type.RATE_RISE, values[i], 5, 0, 3, false, i * 20L);
            if (i < 4) {
                is(h, last, Event.NONE, "no history or no rise yet at scan " + i);
            }
        }
        is(h, last, Event.ACTIVATED, "a rise of 10 over 3 scans");
        // Flat from here: after three more scans the change over the interval is zero.
        is(h, rise.step(Type.RATE_RISE, 10, 5, 0, 3, false, 5 * 20L), Event.NONE, "still 10 above the value 3 scans back");
        rise.step(Type.RATE_RISE, 10, 5, 0, 3, false, 6 * 20L);
        is(h, rise.step(Type.RATE_RISE, 10, 5, 0, 3, false, 7 * 20L), Event.RELEASED, "the rise left the window");

        Alarm tooSoon = new Alarm();
        is(h, tooSoon.step(Type.RATE_RISE, 0, 5, 0, 3, false, 0), Event.NONE, "first scan");
        is(h, tooSoon.step(Type.RATE_RISE, 100, 5, 0, 3, false, 20), Event.NONE, "not three scans of history yet");

        Alarm fall = new Alarm();
        double[] down = {100, 100, 100, 100, 90};
        for (int i = 0; i < down.length; i++) {
            last = fall.step(Type.RATE_FALL, down[i], 5, 0, 3, false, i * 20L);
        }
        is(h, last, Event.ACTIVATED, "a fall of 10 over 3 scans");
        Alarm notFall = new Alarm();
        for (int i = 0; i < 5; i++) {
            last = notFall.step(Type.RATE_FALL, i * 10.0, 5, 0, 1, false, i * 20L);
        }
        is(h, last, Event.NONE, "a rising signal is not a fall");

        // Several sampler passes inside one scan do not move the history or the judgement: the sampler runs every
        // 2 ticks, a scan is 20. History is 0, 20, 40 at tick 40, and 40 - 0 is the first change above 30.
        Alarm sampled = new Alarm();
        long activatedAt = -1;
        for (long tick = 0; tick < 100; tick += 2) {
            if (sampled.step(Type.RATE_RISE, tick, 30, 0, 2, false, tick) == Event.ACTIVATED && activatedAt < 0) {
                activatedAt = tick;
            }
        }
        is(h, activatedAt, 40L, "rate judged at one-second scans, not per pass");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void holdIndicationFollowsTheDxSequence(GameTestHelper h) {
        // active, acknowledged while active, then cleared
        Alarm a = new Alarm();
        is(h, a.step(Type.HIGH, 11, 10, 0, 0, true, 0), Event.ACTIVATED, "on");
        is(h, a.state(), State.ACTIVE_UNACK, "hold: blinking red");
        is(h, a.acknowledge(), Event.ACKNOWLEDGED, "ACK");
        is(h, a.state(), State.ACTIVE_ACK, "acknowledged while active: steady red");
        is(h, a.acknowledge(), Event.NONE, "a second ACK does nothing");
        is(h, a.step(Type.HIGH, 5, 10, 0, 0, true, 2), Event.RELEASED, "clears");
        is(h, a.state(), State.NORMAL, "acknowledged and cleared: normal");

        // cleared before it was acknowledged
        Alarm b = new Alarm();
        b.step(Type.HIGH, 11, 10, 0, 0, true, 0);
        is(h, b.step(Type.HIGH, 5, 10, 0, 0, true, 2), Event.RELEASED, "clears unacknowledged");
        is(h, b.state(), State.CLEARED_UNACK, "hold, released but unacknowledged: blinking green");
        is(h, b.step(Type.HIGH, 5, 10, 0, 0, true, 4), Event.NONE, "stays blinking green");
        is(h, b.acknowledge(), Event.ACKNOWLEDGED, "ACK of a cleared alarm");
        is(h, b.state(), State.NORMAL, "and it is normal");

        // comes back while cleared-unacknowledged
        Alarm c = new Alarm();
        c.step(Type.HIGH, 11, 10, 0, 0, true, 0);
        c.step(Type.HIGH, 5, 10, 0, 0, true, 2);
        is(h, c.step(Type.HIGH, 12, 10, 0, 0, true, 4), Event.ACTIVATED, "again");
        is(h, c.state(), State.ACTIVE_UNACK, "needs acknowledging again");

        // acknowledging a quiet alarm does nothing
        is(h, new Alarm().acknowledge(), Event.NONE, "nothing to acknowledge");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void nonHoldAlarmIsExactlyTheCondition(GameTestHelper h) {
        Alarm a = new Alarm();
        a.step(Type.HIGH, 11, 10, 0, 0, false, 0);
        is(h, a.state(), State.ACTIVE, "active, never unacknowledged");
        is(h, a.acknowledge(), Event.NONE, "ACK means nothing without hold");
        is(h, a.state(), State.ACTIVE, "still active");
        a.step(Type.HIGH, 5, 10, 0, 0, false, 2);
        is(h, a.state(), State.NORMAL, "gone with the condition");

        // Switching hold off turns a flashing alarm into a plain one, switching it on makes an active one wait for ACK.
        Alarm held = new Alarm();
        held.step(Type.HIGH, 11, 10, 0, 0, true, 0);
        held.step(Type.HIGH, 11, 10, 0, 0, false, 2);
        is(h, held.state(), State.ACTIVE, "hold switched off");
        held.step(Type.HIGH, 11, 10, 0, 0, true, 4);
        is(h, held.state(), State.ACTIVE_UNACK, "hold switched on");
        Alarm cleared = new Alarm();
        cleared.step(Type.HIGH, 11, 10, 0, 0, true, 0);
        cleared.step(Type.HIGH, 5, 10, 0, 0, true, 2);
        cleared.step(Type.HIGH, 5, 10, 0, 0, false, 4);
        is(h, cleared.state(), State.NORMAL, "a cleared-unacknowledged alarm is normal without hold");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void noSignalHoldsEverything(GameTestHelper h) {
        Alarm a = new Alarm();
        a.step(Type.HIGH, 11, 10, 0, 0, true, 0);
        is(h, a.step(Type.HIGH, Double.NaN, 10, 0, 0, true, 2), Event.NONE, "NaN changes nothing");
        is(h, a.state(), State.ACTIVE_UNACK, "an active alarm stays active with no signal");
        a.acknowledge();
        a.step(Type.HIGH, Double.NaN, 10, 0, 0, true, 4);
        is(h, a.state(), State.ACTIVE_ACK, "and acknowledged");

        Alarm timer = new Alarm();
        timer.step(Type.DELAY_HIGH, 11, 10, 0, 2, false, 0);
        timer.step(Type.DELAY_HIGH, Double.NaN, 10, 0, 2, false, 20);
        is(h, timer.step(Type.DELAY_HIGH, 11, 10, 0, 2, false, 40), Event.ACTIVATED, "the delay that ran before the dropout counts");

        Alarm rate = new Alarm();
        for (int i = 0; i < 4; i++) {
            rate.step(Type.RATE_RISE, i * 10.0, 5, 0, 1, false, i * 20L);
        }
        is(h, rate.state(), State.ACTIVE, "rising");
        rate.step(Type.RATE_RISE, Double.NaN, 5, 0, 1, false, 80);
        is(h, rate.state(), State.ACTIVE, "no signal keeps it");
        is(h, a.step(null, 11, 10, 0, 0, true, 6), Event.NONE, "no type does nothing");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void clearAndRestoreAreIdentity(GameTestHelper h) {
        Alarm a = new Alarm();
        a.step(Type.HIGH, 11, 10, 0, 0, true, 0);
        int id = a.stateId();
        Alarm b = new Alarm();
        b.restore(id);
        is(h, b.state(), State.ACTIVE_UNACK, "restored");
        is(h, b.step(Type.HIGH, 5, 10, 0, 0, true, 2), Event.RELEASED, "a restored active alarm releases");
        is(h, b.state(), State.CLEARED_UNACK, "restored then cleared");
        h.assertTrue(a.clear(), "clear reports a change");
        h.assertTrue(!a.clear(), "a second clear reports none");
        is(h, a.state(), State.NORMAL, "cleared");
        Alarm junk = new Alarm();
        junk.restore(99);
        is(h, junk.state(), State.NORMAL, "a bad id restores as normal");
        is(h, Type.of("T"), Type.DELAY_HIGH, "T");
        is(h, Type.of("t"), Type.DELAY_LOW, "t");
        h.assertTrue(Type.of("off") == null && Type.of("x") == null, "off and junk are no type");
        h.assertTrue(RecorderAlarmLogic.TYPE_OPTIONS.size() == Type.values().length + 1, "every type is an option");
        h.succeed();
    }
}
