package dev.bwr.mod.signal;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * A CC-independent view of one plant device: what it reports and what it accepts.
 *
 * <p>Providers only read, or execute an explicit command; they never act on their own. Reads return empty
 * when the device cannot report (not formed, removed). Commands go through the same code path as the
 * device's ComputerCraft setter and return {@code false} when the device refuses (no power, interlock,
 * bad value, unknown key).
 *
 * <p>All methods must be called on the server thread.
 */
public interface SignalProvider {
    List<SignalSpec> signals();
    /** Booleans read as 0/1. Empty means "no signal". */
    OptionalDouble read(String key);
    default Optional<String> readText(String key) { return Optional.empty(); }
    List<CommandSpec> commands();
    /** @return false if the command was refused. */
    boolean command(String key, double value);
}
