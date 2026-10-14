package dev.bwr.mod.panel.component;

/**
 * Implemented by the {@link ComponentData} of components that show settable static text (nameplates, segment
 * displays, character LCDs), so CC's {@code setText(label, text)} can set it without knowing the type.
 */
public interface PanelTextTarget {
    /**
     * Replaces the displayed text, sanitised and truncated to the component's own limit.
     *
     * @return whether the stored text changed
     */
    boolean setText(String text);

    /** Names of the further text fields {@link #setField} takes, for CC; none by default. */
    default java.util.List<String> textFields() {
        return java.util.List.of();
    }

    /**
     * Replaces one named text field of the component (a second nameplate line, a corner field, a tag, a display
     * caption), sanitised and truncated like {@link #setText}.
     *
     * @return whether the stored text changed; false also for a name that is not one of {@link #textFields}
     */
    default boolean setField(String field, String text) {
        return false;
    }
}
