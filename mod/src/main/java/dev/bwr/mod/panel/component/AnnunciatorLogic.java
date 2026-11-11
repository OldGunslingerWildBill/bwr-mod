package dev.bwr.mod.panel.component;

/**
 * The annunciator state machine (ISA-18.1 sequence A), with no Minecraft types
 * so it can be tested directly.
 *
 * <pre>
 *   NORMAL  --condition-->  ALARM (fast flash)  --acknowledge-->  ACKED (steady)
 *   ACKED   --condition clears-->  NORMAL (off)          [automatic reset]
 * </pre>
 * <ul>
 *   <li>An alarm that clears before it is acknowledged keeps flashing until it
 *       is acknowledged; acknowledging it then turns it off at once.</li>
 *   <li>With {@code manualReset} an acknowledged alarm whose condition has
 *       cleared stays steady until RESET.</li>
 *   <li>RESET only affects an acknowledged alarm whose condition has cleared;
 *       it never silences an alarm nobody has acknowledged.</li>
 *   <li>No signal ({@code NaN}) holds the current state.</li>
 *   <li>The horn: a window is {@linkplain #audible() audible} from the moment it enters a new alarm until it is
 *       acknowledged or its group is silenced (SILENCE stops the horn and nothing else: the window keeps flashing).
 *       Only an unacknowledged alarm is ever audible, so a window that is off or steady never sounds.</li>
 * </ul>
 */
public final class AnnunciatorLogic {
    public enum State { NORMAL, ALARM, ACKED }

    public enum Display { OFF, FLASH, STEADY }

    public static final String ABOVE = "above";
    public static final String BELOW = "below";
    public static final String EQUALS = "equals";

    private State state = State.NORMAL;
    private boolean condition;
    private boolean manualReset;
    private boolean audible;

    public State state() {
        return state;
    }

    /** Whether the alarm condition was true at the last update. */
    public boolean condition() {
        return condition;
    }

    public boolean manualReset() {
        return manualReset;
    }

    public void setManualReset(boolean manualReset) {
        this.manualReset = manualReset;
    }

    /** Whether this window is sounding the horn: a new alarm nobody has acknowledged or silenced yet. */
    public boolean audible() {
        return audible && state == State.ALARM;
    }

    /** What the window shows. */
    public Display display() {
        return switch (state) {
            case NORMAL -> Display.OFF;
            case ALARM -> Display.FLASH;
            case ACKED -> Display.STEADY;
        };
    }

    /**
     * Evaluates an alarm condition. Anything unknown, or a signal that is not a number, is false.
     *
     * @param mode {@link #ABOVE} (input &gt; setpoint), {@link #BELOW} (input &lt; setpoint) or
     *             {@link #EQUALS} (input == setpoint)
     */
    public static boolean evaluate(String mode, double input, double setpoint) {
        if (Double.isNaN(input) || Double.isNaN(setpoint)) {
            return false;
        }
        return switch (mode) {
            case ABOVE -> input > setpoint;
            case BELOW -> input < setpoint;
            case EQUALS -> Math.abs(input - setpoint) <= 1.0e-9 * Math.max(1.0, Math.abs(setpoint));
            default -> false;
        };
    }

    /** The condition changed (or was re-evaluated). Returns whether the state changed. */
    public boolean update(boolean now) {
        State before = state;
        condition = now;
        if (now && state == State.NORMAL) {
            state = State.ALARM;
            audible = true;
        } else if (!now && state == State.ACKED && !manualReset) {
            state = State.NORMAL;
        }
        if (state != State.ALARM) {
            audible = false;
        }
        return state != before;
    }

    /** ACK pressed. Returns whether the state changed. Acknowledging also stops the horn for this window. */
    public boolean acknowledge() {
        if (state != State.ALARM) {
            return false;
        }
        state = condition || manualReset ? State.ACKED : State.NORMAL;
        audible = false;
        return true;
    }

    /** SILENCE pressed: stops the horn for this window and leaves the alarm as it is. Returns whether it was sounding. */
    public boolean silence() {
        boolean was = audible();
        audible = false;
        return was;
    }

    /** RESET pressed. Returns whether the state changed. */
    public boolean reset() {
        if (state == State.ACKED && !condition) {
            state = State.NORMAL;
            return true;
        }
        return false;
    }

    // ---- persistence (plain ints so this class needs no NBT)

    public int stateId() {
        return state.ordinal();
    }

    /** A state saved before the horn existed: nothing is audible, so an old save loads silent. */
    public void restore(int stateId, boolean condition) {
        restore(stateId, condition, false);
    }

    public void restore(int stateId, boolean condition, boolean audible) {
        State[] all = State.values();
        this.state = stateId >= 0 && stateId < all.length ? all[stateId] : State.NORMAL;
        this.condition = condition;
        this.audible = audible && state == State.ALARM;
    }
}
