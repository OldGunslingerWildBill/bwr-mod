package dev.bwr.mod.panel.component;

import java.util.List;

/**
 * The make of a paperless recorder: one {@code paperless_recorder} component type, one data class and one setup
 * screen, with the style picking the limits, the screen texture and the bezel model (spec 5b).
 *
 * <table>
 *   <caption>Limits</caption>
 *   <tr><th>Style</th><th>Channels</th><th>Groups</th><th>Per group</th><th>Texture</th></tr>
 *   <tr><td>DX1000</td><td>2, 4, 6 or 12</td><td>10</td><td>6</td><td>320 x 240</td></tr>
 *   <tr><td>DX2000</td><td>4, 8, 10, 20, 30, 40 or 48</td><td>36</td><td>10</td><td>640 x 480</td></tr>
 * </table>
 * The channel counts are the real models' (DX1002 to DX1012; DX2004 to DX2048).
 */
public enum RecorderStyle {
    DX1000("yokogawa_dx1000", new int[] {2, 4, 6, 12}, 12, 10, 6, 320, 240, 5),
    DX2000("yokogawa_dx2000", new int[] {4, 8, 10, 20, 30, 40, 48}, 10, 36, 10, 640, 480, 7);

    public static final String KEY = "RecorderStyle";

    private final String id;
    private final int[] channelOptions;
    private final int defaultChannels;
    private final int groups;
    private final int perGroup;
    private final int textureWidth;
    private final int textureHeight;
    private final int softKeys;

    RecorderStyle(String id, int[] channelOptions, int defaultChannels, int groups, int perGroup, int textureWidth,
                  int textureHeight, int softKeys) {
        this.id = id;
        this.channelOptions = channelOptions;
        this.defaultChannels = defaultChannels;
        this.groups = groups;
        this.perGroup = perGroup;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.softKeys = softKeys;
    }

    /** The style id as saved and as the bezel model's file name ({@code yokogawa_dx1000}). */
    public String id() {
        return id;
    }

    /** Channel counts the make is built with, smallest first. */
    public int[] channelOptions() {
        return channelOptions.clone();
    }

    public int maxChannels() {
        return channelOptions[channelOptions.length - 1];
    }

    public int defaultChannels() {
        return defaultChannels;
    }

    public int groups() {
        return groups;
    }

    public int perGroup() {
        return perGroup;
    }

    public int textureWidth() {
        return textureWidth;
    }

    public int textureHeight() {
        return textureHeight;
    }

    /** Pixels of this style's screen per pixel of the DX1000's: the fonts and strokes scale with it. */
    public int scale() {
        return textureWidth / 320;
    }

    public int softKeys() {
        return softKeys;
    }

    /** Whether the front has a hinged key-panel cover (DX2000). */
    public boolean hasCover() {
        return this == DX2000;
    }

    /** The nearest channel count the make is built with that is not below {@code wanted}, or the largest. */
    public int snapChannels(int wanted) {
        for (int option : channelOptions) {
            if (option >= wanted) {
                return option;
            }
        }
        return maxChannels();
    }

    /** The style saved as {@code id}; the DX1000 for anything else, so a bad save still loads. */
    public static RecorderStyle byId(String id) {
        for (RecorderStyle style : values()) {
            if (style.id.equals(id)) {
                return style;
            }
        }
        return DX1000;
    }

    public static List<String> ids() {
        return List.of(DX1000.id, DX2000.id);
    }

    /**
     * Which style a freshly placed recorder of {@code footprint} is: from 32 cells (2 x 2 blocks) a DX2000, which is always
     * twice a DX1000 of the same setting (DX1000 12, 16, 20, 24 cells; DX2000 24, 32, 40, 48), smaller a DX1000. The one size
     * both makes have, 24 cells, is a DX1000; a DX2000 of 24 cells is a DX1000 of that size switched in the setup screen.
     */
    public static RecorderStyle forFootprint(Footprint footprint) {
        return footprint != null && footprint.w() >= 32 && footprint.h() >= 32 ? DX2000 : DX1000;
    }
}
