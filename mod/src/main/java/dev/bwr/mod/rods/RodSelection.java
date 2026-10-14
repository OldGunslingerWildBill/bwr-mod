package dev.bwr.mod.rods;

import dev.bwr.core.ReactorCore;

/**
 * The reactor's selected rod, the operator state behind a rod select matrix and the rod motion pushbuttons of a
 * benchboard. Pure Java; the reactor controller owns one and persists {@link #selected()}.
 *
 * <p>Selecting a rod never moves anything. A step changes the one standing demand of the selected rod, in
 * {@link ControlRodDriveNetwork} (the only authority over rod demand), so CRD power and water are honoured exactly
 * as they are for {@code rod_<n>_position} and the CC {@code setRodPosition}: the drive indexes toward the new
 * demand only while it can, and a drive that cannot simply keeps the demand standing.
 *
 * <p>The continuous controls are two held flags. While one is set (and the other is not) the selected rod is
 * stepped one more notch each time it has reached its demand, which is the normal rod speed; a drive that cannot
 * move therefore leads its demand by one notch at most, never runs it to the end of the stroke. The flags are
 * transient: they are never saved, so a reload cannot resume a motion nobody is holding.
 *
 * <p>Nothing here decides anything. There is no rod block, no sequence rule and no limit beyond the end of the
 * stroke; whether a rod should move is for the player to decide.
 */
public final class RodSelection {
    private int selected;
    private boolean insertHeld;
    private boolean withdrawHeld;

    /** The selected rod, 1-based; 0 is none. */
    public int selected() {
        return selected;
    }

    public boolean insertHeld() {
        return insertHeld;
    }

    public boolean withdrawHeld() {
        return withdrawHeld;
    }

    /**
     * Selects a rod (1-based) or clears the selection (0). A held continuous control is released: it belonged to
     * the previous rod.
     *
     * @return false when {@code rod} is not 0 and not a rod of a core with {@code rodCount} rods
     */
    public boolean select(int rod, int rodCount) {
        if (rod < 0 || rod > rodCount) {
            return false;
        }
        selected = rod;
        insertHeld = false;
        withdrawHeld = false;
        return true;
    }

    /** Restores a saved selection (old saves have none); anything that is not a rod of this core means none. */
    public void restore(int rod, int rodCount) {
        selected = rod >= 1 && rod <= rodCount ? rod : 0;
        insertHeld = false;
        withdrawHeld = false;
    }

    /** Forgets everything: the reactor is not formed any more. */
    public void clear() {
        selected = 0;
        insertHeld = false;
        withdrawHeld = false;
    }

    /**
     * Steps the selected rod's demand one notch: {@code +1} withdraws, {@code -1} inserts.
     *
     * @return false when no rod is selected or the selection does not fit the network
     */
    public boolean step(ControlRodDriveNetwork rods, int direction) {
        if (rods == null || selected < 1 || selected > rods.getControlRodCount() || direction == 0) {
            return false;
        }
        if (direction > 0) {
            rods.withdraw(selected - 1, 1);
        } else {
            rods.insert(selected - 1, 1);
        }
        return true;
    }

    /**
     * Holds or releases a continuous control. Releasing always succeeds; holding needs a selected rod.
     *
     * @param direction {@code +1} withdraw, {@code -1} insert
     */
    public boolean hold(int direction, boolean held, int rodCount) {
        if (direction == 0) {
            return false;
        }
        if (held && (selected < 1 || selected > rodCount)) {
            return false;
        }
        if (direction > 0) {
            withdrawHeld = held;
        } else {
            insertHeld = held;
        }
        return true;
    }

    /**
     * Once per controller tick, before the drive network pushes demands into the core: keeps a held control
     * stepping at the normal rod speed.
     *
     * @return whether a step was made
     */
    public boolean tick(ControlRodDriveNetwork rods, ReactorCore core) {
        int direction = (withdrawHeld ? 1 : 0) - (insertHeld ? 1 : 0);
        if (direction == 0 || rods == null || core == null || selected < 1 || selected > rods.getControlRodCount()
                || core.isScramActive()) {
            return false;
        }
        int rod = selected - 1;
        if (core.getRodPositionNotches(rod) != rods.getCommandedNotchIndex(rod)) {
            return false; // still travelling, or the drive cannot move: do not lead the demand any further
        }
        return step(rods, direction);
    }
}
