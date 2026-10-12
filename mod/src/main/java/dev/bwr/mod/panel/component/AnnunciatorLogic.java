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
        } else if (!now && state == State.ACKED && !manualReset) {
            state = State.NORMAL;
        }
        return state != before;
    }

    /** ACK pressed. Returns whether the state changed. */
    public boolean acknowledge() {
        if (state != State.ALARM) {
            return false;
        }
        state = condition || manualReset ? State.ACKED : State.NORMAL;
        return true;
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

    public void restore(int stateId, boolean condition) {
        State[] all = State.values();
        this.state = stateId >= 0 && stateId < all.length ? all[stateId] : State.NORMAL;
        this.condition = condition;
    }
}
