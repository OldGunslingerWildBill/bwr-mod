package dev.bwr.mod.panel.component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The front-key logic of a paperless recorder as one player sees it: the display selection menu opened by DISP/ENTER,
 * the FUNC soft-key strip (AlarmACK, Message, Free message, START, STOP and Next) and the message sub-strip. Pure
 * and Minecraft free so it is tested without a client; the client keeps one per recorder it is looking at and never
 * saves it (the menu state is client only). A press returns what, if anything, to ask the server for
 * ({@link RecorderActions}) and what to open on this client.
 *
 * <ul>
 *   <li>DISP/ENTER opens the menu; up and down move the cursor, DISP/ENTER chooses, ESC closes it.</li>
 *   <li>Outside the menu left and right change the group, up and down step trend, digital and bar.</li>
 *   <li>FUNC opens the soft-key strip; the soft keys then mean what the strip labels say. The strip's last soft
 *       keys page it (DX1000: soft 5 is Next; DX2000: soft 6 is Previous, soft 7 Next).</li>
 *   <li>MENU opens the setup screen; USER and the favourite key do nothing but click.</li>
 *   <li>The DX2000's key-panel cover hides every key but the soft keys while it is closed: the cover tab is the only
 *       other key that answers.</li>
 * </ul>
 */
public final class RecorderUi {
    public enum Mode { NONE, MENU, FUNC, MESSAGE }

    /** What a press opens on this client. */
    public enum Effect { NONE, OPEN_SETUP, OPEN_FREE_MESSAGE }

    /**
     * @param handled whether the key did anything (a key hidden by the cover does not)
     * @param action  a {@link RecorderActions} code to send to the server, or 0
     */
    public record Result(boolean handled, int action, Effect effect) {
        static final Result IGNORED = new Result(false, RecorderActions.NONE, Effect.NONE);
        static final Result LOCAL = new Result(true, RecorderActions.NONE, Effect.NONE);

        static Result action(int code) {
            return new Result(true, code, Effect.NONE);
        }
    }

    /** One line of the display selection menu. */
    public record MenuItem(String label, int action) {
    }

    /** The FUNC strip's items, in order. */
    public static final String[] FUNC_ITEMS = {"ALARM ACK", "MESSAGE", "FREE MSG", "START", "STOP"};

    public Mode mode = Mode.NONE;
    public int cursor;
    public int page;
    /** The last key pressed and the game tick of it (for the click feedback), set by the client. */
    public RecorderKey lastKey;
    public long lastKeyTick = Long.MIN_VALUE;
    private int version;

    /** Bumped by every press that changed what the screen shows; the renderer repaints when it differs. */
    public int version() {
        return version;
    }

    /** The display selection menu of a make. */
    public static List<MenuItem> menu(RecorderStyle style) {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("TREND", RecorderActions.screen(RecorderScreen.TREND)));
        items.add(new MenuItem("DIGITAL", RecorderActions.screen(RecorderScreen.DIGITAL)));
        items.add(new MenuItem("BAR", RecorderActions.screen(RecorderScreen.BAR)));
        items.add(new MenuItem("OVERVIEW", RecorderActions.screen(RecorderScreen.OVERVIEW)));
        items.add(new MenuItem("ALARM SUMMARY", RecorderActions.screen(RecorderScreen.ALARM_SUMMARY)));
        if (style == RecorderStyle.DX2000) {
            items.add(new MenuItem("CIRCULAR", RecorderActions.screen(RecorderScreen.CIRCULAR)));
            items.add(new MenuItem("4-PANEL MIX", RecorderActions.QUAD_BASE));
            items.add(new MenuItem("4-PANEL ALL TREND", RecorderActions.QUAD_BASE + 1));
            items.add(new MenuItem("4-PANEL ALL DIGITAL", RecorderActions.QUAD_BASE + 2));
            items.add(new MenuItem("4-PANEL ALL BAR", RecorderActions.QUAD_BASE + 3));
        }
        return items;
    }

    /** Index of the menu line for what the recorder shows now. */
    public static int menuIndexOf(PaperlessRecorderData data) {
        List<MenuItem> items = menu(data.style);
        int wanted = data.screen == RecorderScreen.FOUR_PANEL ? RecorderActions.QUAD_BASE + data.quad
                : RecorderActions.screen(data.screen);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).action() == wanted) {
                return i;
            }
        }
        return 0;
    }

    /** Items a soft-key strip holds on one page: all the soft keys but the paging ones. */
    public static int itemsPerPage(RecorderStyle style) {
        return style.softKeys() - (style == RecorderStyle.DX2000 ? 2 : 1);
    }

    private static int pages(int total, RecorderStyle style) {
        return Math.max(1, (total + itemsPerPage(style) - 1) / itemsPerPage(style));
    }

    /** What each soft key is labelled in the current mode (blank for none); the length is the make's soft key count. */
    public String[] softLabels(RecorderStyle style) {
        String[] labels = new String[style.softKeys()];
        Arrays.fill(labels, "");
        if (mode != Mode.FUNC && mode != Mode.MESSAGE) {
            return labels;
        }
        int per = itemsPerPage(style);
        int total = mode == Mode.FUNC ? FUNC_ITEMS.length : PaperlessRecorderData.PRESETS;
        for (int k = 0; k < per; k++) {
            int index = page * per + k;
            if (index < total) {
                labels[k] = mode == Mode.FUNC ? FUNC_ITEMS[index] : Integer.toString(index + 1);
            }
        }
        if (style == RecorderStyle.DX2000) {
            labels[5] = "PREV";
            labels[6] = "NEXT";
        } else {
            labels[4] = "NEXT";
        }
        return labels;
    }

    /** A key was pressed. */
    public Result press(RecorderKey key, PaperlessRecorderData data) {
        lastKey = key;
        RecorderStyle style = data.style;
        if (key == RecorderKey.COVER_TAB) {
            return style.hasCover() ? Result.action(RecorderActions.COVER_TOGGLE) : Result.IGNORED;
        }
        if (!key.isSoft() && style.hasCover() && !data.coverOpen) {
            return Result.IGNORED;
        }
        if (key == RecorderKey.MENU) {
            mode = Mode.NONE;
            version++;
            return new Result(true, RecorderActions.NONE, Effect.OPEN_SETUP);
        }
        return switch (mode) {
            case MENU -> pressInMenu(key, data);
            case FUNC -> pressInFunc(key, style);
            case MESSAGE -> pressInMessage(key, style);
            case NONE -> pressPlain(key, data);
        };
    }

    private Result pressPlain(RecorderKey key, PaperlessRecorderData data) {
        switch (key) {
            case DISP -> {
                mode = Mode.MENU;
                cursor = menuIndexOf(data);
                version++;
                return Result.LOCAL;
            }
            case FUNC -> {
                mode = Mode.FUNC;
                page = 0;
                version++;
                return Result.LOCAL;
            }
            case LEFT -> {
                return Result.action(RecorderActions.GROUP_PREV);
            }
            case RIGHT -> {
                return Result.action(RecorderActions.GROUP_NEXT);
            }
            case UP -> {
                return Result.action(RecorderActions.STEP_UP);
            }
            case DOWN -> {
                return Result.action(RecorderActions.STEP_DOWN);
            }
            case START -> {
                return Result.action(RecorderActions.START);
            }
            case STOP -> {
                return Result.action(RecorderActions.STOP);
            }
            default -> {
                return Result.LOCAL; // ESC, USER, the favourite key and soft keys with no label: a click and nothing else
            }
        }
    }

    private Result pressInMenu(RecorderKey key, PaperlessRecorderData data) {
        List<MenuItem> items = menu(data.style);
        switch (key) {
            case UP -> cursor = Math.floorMod(cursor - 1, items.size());
            case DOWN -> cursor = Math.floorMod(cursor + 1, items.size());
            case ESC -> mode = Mode.NONE;
            case DISP -> {
                mode = Mode.NONE;
                version++;
                return Result.action(items.get(Math.max(0, Math.min(items.size() - 1, cursor))).action());
            }
            default -> {
                return Result.LOCAL;
            }
        }
        version++;
        return Result.LOCAL;
    }

    private Result pressInFunc(RecorderKey key, RecorderStyle style) {
        if (key == RecorderKey.ESC || key == RecorderKey.FUNC) {
            mode = Mode.NONE;
            version++;
            return Result.LOCAL;
        }
        if (!key.isSoft()) {
            return Result.LOCAL;
        }
        int number = key.softNumber();
        int per = itemsPerPage(style);
        if (number <= per) {
            int index = page * per + number - 1;
            if (index >= FUNC_ITEMS.length) {
                return Result.LOCAL;
            }
            version++;
            switch (index) {
                case 0 -> {
                    mode = Mode.NONE;
                    return Result.action(RecorderActions.ACK);
                }
                case 1 -> {
                    mode = Mode.MESSAGE;
                    page = 0;
                    return Result.LOCAL;
                }
                case 2 -> {
                    mode = Mode.NONE;
                    return new Result(true, RecorderActions.NONE, Effect.OPEN_FREE_MESSAGE);
                }
                case 3 -> {
                    mode = Mode.NONE;
                    return Result.action(RecorderActions.START);
                }
                default -> {
                    mode = Mode.NONE;
                    return Result.action(RecorderActions.STOP);
                }
            }
        }
        paging(number, style, FUNC_ITEMS.length);
        return Result.LOCAL;
    }

    private Result pressInMessage(RecorderKey key, RecorderStyle style) {
        if (key == RecorderKey.ESC || key == RecorderKey.FUNC) {
            mode = key == RecorderKey.FUNC ? Mode.NONE : Mode.FUNC;
            page = 0;
            version++;
            return Result.LOCAL;
        }
        if (!key.isSoft()) {
            return Result.LOCAL;
        }
        int number = key.softNumber();
        int per = itemsPerPage(style);
        if (number <= per) {
            int message = page * per + number;
            if (message > PaperlessRecorderData.PRESETS) {
                return Result.LOCAL;
            }
            mode = Mode.NONE;
            version++;
            return Result.action(RecorderActions.MESSAGE_BASE + message);
        }
        paging(number, style, PaperlessRecorderData.PRESETS);
        return Result.LOCAL;
    }

    private void paging(int softNumber, RecorderStyle style, int total) {
        int count = pages(total, style);
        boolean next = style == RecorderStyle.DX2000 ? softNumber == 7 : softNumber == 5;
        boolean previous = style == RecorderStyle.DX2000 && softNumber == 6;
        if (next) {
            page = (page + 1) % count;
            version++;
        } else if (previous) {
            page = Math.floorMod(page - 1, count);
            version++;
        }
    }
}
