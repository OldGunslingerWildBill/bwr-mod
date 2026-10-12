package dev.bwr.mod.panel.component;

/**
 * Names one input or output of a component for the wiring screens and CC.
 * Ports are positional: the Nth {@code PortSpec} is input/output index N.
 *
 * @param key      stable machine name ("state", "value", ...), used by CC
 * @param labelKey translation key for the screen
 */
public record PortSpec(String key, String labelKey) {
    public static PortSpec of(String key) {
        return new PortSpec(key, "component.bwr.port." + key);
    }
}
