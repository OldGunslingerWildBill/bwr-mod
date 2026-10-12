package dev.bwr.mod.panel.component;

/**
 * What a player is doing to an input component, as carried by the C2S operate
 * packet. The meaning of {@code value} depends on the action:
 * <ul>
 *   <li>{@link #PRESS} / {@link #RELEASE}: ignored</li>
 *   <li>{@link #TURN}: a relative step (scroll notches, +1 / -1)</li>
 *   <li>{@link #SET}: an absolute value (knob drag, thumbwheel entry)</li>
 * </ul>
 * The server never trusts {@code value}: it is finite-checked by the packet
 * handler and each type clamps it to its own range.
 */
public enum OperateAction {
    PRESS, RELEASE, TURN, SET;

    public static OperateAction byId(int id) {
        OperateAction[] all = values();
        return id >= 0 && id < all.length ? all[id] : null;
    }
}
