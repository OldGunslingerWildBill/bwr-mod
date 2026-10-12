package dev.bwr.mod.signal;

/**
 * What a signal or command value means.
 *
 * <ul>
 *   <li>{@link #NUMBER}: a continuous measurement or demand in the signal's unit.</li>
 *   <li>{@link #BOOLEAN}: 0 or 1.</li>
 *   <li>{@link #LEVEL}: a discrete whole-number step (a rod notch, a damage stage, a lineup index).</li>
 *   <li>{@link #PULSE}: a command that fires once when sent (scram, reset); the value is ignored.</li>
 *   <li>{@link #TEXT}: read through {@link SignalProvider#readText}; {@code read} is empty.</li>
 * </ul>
 */
public enum Kind { NUMBER, BOOLEAN, LEVEL, PULSE, TEXT }
