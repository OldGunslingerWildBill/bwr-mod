package dev.bwr.mod.signal;

/** One command a device accepts. {@code key} is stable lowercase snake_case; see SIGNALS.md. */
public record CommandSpec(String key, String label, Kind kind, double min, double max) {}
