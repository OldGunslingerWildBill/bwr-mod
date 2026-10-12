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
}
