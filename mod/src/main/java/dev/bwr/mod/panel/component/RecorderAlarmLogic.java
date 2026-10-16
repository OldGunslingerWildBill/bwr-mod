package dev.bwr.mod.panel.component;

import java.util.List;

/**
 * The paperless recorder's alarm state machine, with no Minecraft types so it can be tested without a world. One
 * {@link Alarm} per alarm point (a channel has four); {@link PaperlessRecorderData} owns them and feeds them the
 * channel's value once per sampler pass.
 *
 * <h3>Types (DX1000 manual 1.2, 3.7)</h3>
 * <ul>
 *   <li>{@code H} high / {@code L} low: the value is above / below the limit.</li>
 *   <li>{@code T} delay high / {@code t} delay low: the same, but only once the condition has held for the delay
 *       (seconds). The delay restarts whenever the condition drops out.</li>
 *   <li>{@code R} rate of rise / {@code r} rate of fall: the change over {@code interval} scans (a scan is one second)
 *       is above the limit, the limit being a positive amount. Judged once per scan, and not defined until that much
 *       history exists.</li>
 * </ul>
 * Hysteresis (a positive amount) keeps an active alarm active until the value has come back that far inside the
 * limit. Difference alarms (h, l) need two channels and are not part of v1.
 *
 * <h3>Indication (manual 1-15)</h3>
 * Non-hold: {@link State#NORMAL} or {@link State#ACTIVE}, exactly the condition. Hold: an alarm that comes on is
 * {@link State#ACTIVE_UNACK} (flashing red) until acknowledged, then {@link State#ACTIVE_ACK} (steady red) while the
 * condition lasts and {@link State#NORMAL} after it. One that clears before it is acknowledged is
 * {@link State#CLEARED_UNACK} (flashing green) until acknowledged. A condition that returns while it is
 * cleared-unacknowledged makes it active and unacknowledged again.
 *
 * <p>A value that is not a number (no signal) holds everything: the state, the delay already run and the rate
 * history. This class only indicates; nothing here ever drives anything.
 */
public final class RecorderAlarmLogic {
    private RecorderAlarmLogic() {
    }

    /** Length of one scan of the rate-of-change alarms, in game ticks. */
    public static final int SCAN_TICKS = 20;
    /** Most scans a rate interval may span. */
    public static final int MAX_SCANS = 32;

    public enum Type {
        HIGH("H"), LOW("L"), DELAY_HIGH("T"), DELAY_LOW("t"), RATE_RISE("R"), RATE_FALL("r");

        private final String mark;

        Type(String mark) {
            this.mark = mark;
        }

        /** The one letter the DX prints for this alarm. */
        public String mark() {
            return mark;
        }

        public boolean delayed() {
            return this == DELAY_HIGH || this == DELAY_LOW;
        }

        public boolean rate() {
            return this == RATE_RISE || this == RATE_FALL;
        }

        /** The type printed as {@code mark}, or null (including for "off"). */
        public static Type of(String mark) {
            for (Type type : values()) {
                if (type.mark.equals(mark)) {
                    return type;
                }
            }
            return null;
        }
    }

    public enum State {
        NORMAL, ACTIVE, ACTIVE_UNACK, ACTIVE_ACK, CLEARED_UNACK;

        /** Whether the condition holds (or, for an unacknowledged cleared alarm, held). */
        public boolean condition() {
            return this == ACTIVE || this == ACTIVE_UNACK || this == ACTIVE_ACK;
        }

        /** Whether this alarm still wants acknowledging. */
        public boolean unacknowledged() {
            return this == ACTIVE_UNACK || this == CLEARED_UNACK;
        }
    }

    /** What the last {@link Alarm#step} or {@link Alarm#acknowledge} did, for the alarm log. */
    public enum Event {
        NONE, ACTIVATED, RELEASED, ACKNOWLEDGED
    }

    /** The types in the order a setup screen lists them, preceded by "off". */
    public static final List<String> TYPE_OPTIONS = List.of("off", "H", "L", "T", "t", "R", "r");

    /** One alarm point. */
    public static final class Alarm {
        private State state = State.NORMAL;
        /** The raw condition (with hysteresis) at the last update. */
        private boolean raw;
        /** Game tick the raw condition last turned on, or -1. */
        private long since = -1;
        private final float[] scans = new float[MAX_SCANS + 1];
        private int scanCount;
        private long nextScan = Long.MIN_VALUE;

        public State state() {
            return state;
        }

        /** The rate over the last {@code interval} scans: the change the rate alarms judge, NaN until there is history. */
        public double rateOf(int interval, double input) {
            int n = Math.max(1, Math.min(MAX_SCANS, interval));
            if (scanCount <= n) {
                return Double.NaN;
            }
            return input - scans[scanCount - 1 - n];
        }

        /**
         * One update with the channel's current value.
         *
         * @param limit  the alarm value
         * @param hyst   hysteresis, at least 0
         * @param param  delay in seconds for T and t, scans for R and r, ignored for H and L
         * @param hold   hold indication (see the class comment)
         * @param now    game tick, never going backwards
         */
        public Event step(Type type, double input, double limit, double hyst, int param, boolean hold, long now) {
            if (Double.isNaN(input) || type == null) {
                return Event.NONE; // no signal holds the state
            }
            normalise(hold);
            boolean scanned = type.rate() && pushScan(input, now);
            double h = Math.max(0.0, hyst);
            boolean next;
            switch (type) {
                case HIGH, DELAY_HIGH -> next = raw ? input > limit - h : input > limit;
                case LOW, DELAY_LOW -> next = raw ? input < limit + h : input < limit;
                case RATE_RISE, RATE_FALL -> {
                    if (!scanned) {
                        next = raw; // the rate is judged once per scan, as the DX does, and holds in between
                    } else {
                        double rate = rateOf(param, input);
                        if (Double.isNaN(rate)) {
                            next = false;
                        } else {
                            double change = type == Type.RATE_RISE ? rate : -rate;
                            double amount = Math.abs(limit);
                            next = raw ? change > amount - h : change > amount;
                        }
                    }
                }
                default -> next = false;
            }
            raw = next;
            boolean effective = next;
            if (type.delayed()) {
                if (next) {
                    if (since < 0) {
                        since = now;
                    }
                    effective = now - since >= Math.max(0L, (long) param) * 20L;
                } else {
                    since = -1;
                }
            }
            return apply(effective, hold);
        }

        private Event apply(boolean effective, boolean hold) {
            boolean was = state.condition();
            if (effective && !was) {
                state = hold ? State.ACTIVE_UNACK : State.ACTIVE;
                return Event.ACTIVATED;
            }
            if (!effective && was) {
                state = hold && state == State.ACTIVE_UNACK ? State.CLEARED_UNACK : State.NORMAL;
                return Event.RELEASED;
            }
            return Event.NONE;
        }

        /** A change of the hold setting moves the state to the nearest one the new mode has. */
        private void normalise(boolean hold) {
            if (!hold) {
                if (state == State.ACTIVE_UNACK || state == State.ACTIVE_ACK) {
                    state = State.ACTIVE;
                } else if (state == State.CLEARED_UNACK) {
                    state = State.NORMAL;
                }
            } else if (state == State.ACTIVE) {
                state = State.ACTIVE_UNACK;
            }
        }

        /** Records a scan when one is due; returns whether it did. */
        private boolean pushScan(double input, long now) {
            if (now < nextScan && scanCount > 0) {
                return false;
            }
            if (scanCount == scans.length) {
                System.arraycopy(scans, 1, scans, 0, scans.length - 1);
                scanCount--;
            }
            scans[scanCount++] = (float) input;
            nextScan = now + SCAN_TICKS;
            return true;
        }

        /** ACK: an alarm wanting acknowledgement is released from flashing. */
        public Event acknowledge() {
            if (state == State.ACTIVE_UNACK) {
                state = State.ACTIVE_ACK;
                return Event.ACKNOWLEDGED;
            }
            if (state == State.CLEARED_UNACK) {
                state = State.NORMAL;
                return Event.ACKNOWLEDGED;
            }
            return Event.NONE;
        }

        /** Back to a quiet alarm (the alarm was switched off, hidden or retyped). Returns whether anything changed. */
        public boolean clear() {
            boolean changed = state != State.NORMAL;
            state = State.NORMAL;
            raw = false;
            since = -1;
            scanCount = 0;
            nextScan = Long.MIN_VALUE;
            return changed;
        }

        // ---- persistence (plain ints so this class needs no NBT)

        public int stateId() {
            return state.ordinal();
        }

        public void restore(int stateId) {
            State[] all = State.values();
            state = stateId >= 0 && stateId < all.length ? all[stateId] : State.NORMAL;
            raw = state.condition();
            since = -1;
            scanCount = 0;
            nextScan = Long.MIN_VALUE;
        }
    }
}
