package dev.bwr.mod.panel.model;

import dev.bwr.mod.panel.component.AnnunciatorLogic;

import java.util.ArrayList;
import java.util.List;

/**
 * The list of one annunciator box, as pure arithmetic (no Minecraft types, so it is tested directly): which windows
 * are the box of a given window, in reading order with the numbers the box prints for them ({@link AnnunciatorBox#layout}),
 * what a light shows, and the filters and counts of the box list screen.
 */
public final class AnnunciatorBoxList {
    /** The most lights one list holds; a box with more shows its first ones in reading order. */
    public static final int MAX_ROWS = 512;

    /** What a window's input is wired to, as far as the list sorts and counts it. */
    public enum Wiring {
        NONE, PLANT, COMPUTER, REDSTONE, COMPONENT, CONSTANT, OTHER;

        public boolean wired() {
            return this != NONE;
        }
    }

    /** What a light shows: the three states of the alarm sequence, and "no signal" for one that is off with no input value. */
    public enum Show {
        OFF, ALARM, ACKNOWLEDGED, NO_SIGNAL;

        /** Lit: a new alarm (flashing) or an acknowledged one (steady). */
        public boolean lit() {
            return this == ALARM || this == ACKNOWLEDGED;
        }
    }

    /** The filters of the list. */
    public enum Filter {
        ALL, NOT_WIRED, IN_ALARM, COMPUTER
    }

    /**
     * One light of a box.
     *
     * @param index  the window's index in the array given to {@link #lights}
     * @param row    its row in the box, from 0 (one row per alarm, so a split tile is two rows)
     * @param column its column, from 0
     */
    public record Light(int index, int row, int column) {
        /** The light's number as the box prints it in its margins: row, a dash, column, both from 1. */
        public String number() {
            return (row + 1) + "-" + (column + 1);
        }
    }

    /** The counts in the list's header. */
    public record Counts(int lights, int wired, int notWired, int inAlarm) {
    }

    private AnnunciatorBoxList() {
    }

    /**
     * The lights of the box that window {@code asked} is in, in reading order (top row first, left to right), at most
     * {@code cap} of them. Windows that are no part of that box are left out. Never throws: an index out of range gives
     * an empty list, and a layout that cannot be worked out gives the asked window alone.
     *
     * @param windows the annunciator windows of one panel group
     */
    public static List<Light> lights(AnnunciatorBox.Rect[] windows, int asked, int cap) {
        List<Light> lights = new ArrayList<>();
        if (windows == null || asked < 0 || asked >= windows.length || cap <= 0) {
            return lights;
        }
        AnnunciatorBox.Tile[] tiles;
        try {
            tiles = AnnunciatorBox.layout(windows);
        } catch (RuntimeException e) {
            tiles = null;
        }
        if (tiles == null || tiles.length != windows.length || tiles[asked] == null) {
            lights.add(new Light(asked, 0, 0));
            return lights;
        }
        int box = tiles[asked].box();
        for (int i = 0; i < windows.length; i++) {
            if (tiles[i] != null && tiles[i].box() == box && windows[i] != null) {
                lights.add(new Light(i, tiles[i].row(), tiles[i].column()));
            }
        }
        lights.sort((a, b) -> {
            AnnunciatorBox.Rect ra = windows[a.index()];
            AnnunciatorBox.Rect rb = windows[b.index()];
            if (ra.y() != rb.y()) {
                return Integer.compare(ra.y(), rb.y());
            }
            if (ra.x() != rb.x()) {
                return Integer.compare(ra.x(), rb.x());
            }
            return Integer.compare(a.index(), b.index());
        });
        return lights.size() > cap ? new ArrayList<>(lights.subList(0, cap)) : lights;
    }

    /** What a light shows for its alarm state; an off light whose input has no value shows "no signal". */
    public static Show show(AnnunciatorLogic.State state, boolean signal) {
        if (state == AnnunciatorLogic.State.ALARM) {
            return Show.ALARM;
        }
        if (state == AnnunciatorLogic.State.ACKED) {
            return Show.ACKNOWLEDGED;
        }
        return signal ? Show.OFF : Show.NO_SIGNAL;
    }

    /** Whether a light passes a filter. "In alarm" is every lit light, flashing or acknowledged. */
    public static boolean matches(Filter filter, Wiring wiring, Show show) {
        if (filter == null || wiring == null || show == null) {
            return filter == null || filter == Filter.ALL;
        }
        return switch (filter) {
            case ALL -> true;
            case NOT_WIRED -> !wiring.wired();
            case IN_ALARM -> show.lit();
            case COMPUTER -> wiring == Wiring.COMPUTER;
        };
    }

    /** The header counts of lights given as two lists of the same length. */
    public static Counts counts(List<Wiring> wirings, List<Show> shows) {
        int lights = Math.min(wirings.size(), shows.size());
        int wired = 0;
        int alarm = 0;
        for (int i = 0; i < lights; i++) {
            if (wirings.get(i) != null && wirings.get(i).wired()) {
                wired++;
            }
            if (shows.get(i) != null && shows.get(i).lit()) {
                alarm++;
            }
        }
        return new Counts(lights, wired, lights - wired, alarm);
    }

    /** A legend's lines on one line: the lines that are not empty, joined by a blank. */
    public static String legend(String... lines) {
        StringBuilder joined = new StringBuilder();
        if (lines != null) {
            for (String line : lines) {
                if (line != null && !line.isBlank()) {
                    if (joined.length() > 0) {
                        joined.append(' ');
                    }
                    joined.append(line.strip());
                }
            }
        }
        return joined.toString();
    }
}
