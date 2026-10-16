package dev.bwr.mod.panel.mimic;

import java.util.Locale;

/** What the mimic tape does when it is used on a panel face. */
public enum MimicMode {
    /** Adds a point to the path being drawn. */
    DRAW,
    /** Removes the path under the cursor. */
    ERASE,
    /** Opens the config screen of the path under the cursor. */
    EDIT;

    public static MimicMode byId(int id) {
        MimicMode[] all = values();
        return id >= 0 && id < all.length ? all[id] : DRAW;
    }

    /** The mode {@code steps} places on (wrapping), for the sneak-scroll switch. */
    public MimicMode step(int steps) {
        MimicMode[] all = values();
        return all[Math.floorMod(ordinal() + steps, all.length)];
    }

    public String translationKey() {
        return "item.bwr.mimic_tape.mode." + name().toLowerCase(Locale.ROOT);
    }
}
