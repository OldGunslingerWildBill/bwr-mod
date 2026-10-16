package dev.bwr.mod.panel.component;

import java.util.Locale;

/**
 * The keys on the face of a recorder. Each is its own part of the bezel model (see {@code DX1000-RECORDER-SPEC.md}
 * section 7): the part name is {@code key_<name>} ({@code key_soft1}, {@code key_disp}), and the DX2000's key-panel
 * cover has a tab ({@code cover_tab}) that opens and closes it.
 */
public enum RecorderKey {
    SOFT1, SOFT2, SOFT3, SOFT4, SOFT5, SOFT6, SOFT7,
    START, STOP, USER, FUNC, ESC, MENU, FAV, DISP, UP, DOWN, LEFT, RIGHT,
    COVER_TAB;

    /** The name of this key's part in the bezel model. */
    public String part() {
        return this == COVER_TAB ? "cover_tab" : "key_" + name().toLowerCase(Locale.ROOT);
    }

    public boolean isSoft() {
        return ordinal() <= SOFT7.ordinal();
    }

    /** 1 to 7 for a soft key, 0 for any other key. */
    public int softNumber() {
        return isSoft() ? ordinal() + 1 : 0;
    }

    public static RecorderKey ofPart(String part) {
        for (RecorderKey key : values()) {
            if (key.part().equals(part)) {
                return key;
            }
        }
        return null;
    }

    /** The key of an {@link RecorderActions} press code (see there), or null. */
    public static RecorderKey byOrdinal(int ordinal) {
        RecorderKey[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : null;
    }
}
