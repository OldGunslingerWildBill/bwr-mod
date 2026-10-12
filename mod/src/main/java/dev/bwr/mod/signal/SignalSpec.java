package dev.bwr.mod.signal;

/** One readable value on a device. {@code key} is stable lowercase snake_case; see SIGNALS.md. */
public record SignalSpec(String key, String label, String unit, double min, double max, Kind kind) {}
