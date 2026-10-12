package dev.bwr.mod.signal;

import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Declarative description of a device type's signals and commands. Build one per block-entity class (a
 * static constant in the adapter), then {@link #bind} it to a live block entity to get a provider.
 *
 * <p>A reader that returns NaN reads as "no signal" (not formed, no data). Infinity passes through exactly
 * as the CC peripheral reports it.
 */
public final class Schema<T extends BlockEntity> {
    /** A command body. Returns false to refuse. */
    @FunctionalInterface public interface Command<T> { boolean run(T be, double value); }
    /** A command body for a dynamically keyed command (e.g. per-rod). */
    @FunctionalInterface public interface KeyedCommand<T> { boolean run(T be, String key, double value); }

    private final Map<String, SignalSpec> specs = new LinkedHashMap<>();
    private final Map<String, ToDoubleFunction<T>> readers = new LinkedHashMap<>();
    private final Map<String, Function<T, String>> textReaders = new LinkedHashMap<>();
    private final Map<String, CommandSpec> commandSpecs = new LinkedHashMap<>();
    private final Map<String, Command<T>> commandBodies = new LinkedHashMap<>();
    private Function<T, List<SignalSpec>> extraSignals;
    private BiFunction<T, String, OptionalDouble> extraReader;
    private Function<T, List<CommandSpec>> extraCommands;
    private KeyedCommand<T> extraCommand;

    public Schema<T> number(String key, String label, String unit, double min, double max, ToDoubleFunction<T> reader) {
        return add(new SignalSpec(key, label, unit, min, max, Kind.NUMBER), reader);
    }

    public Schema<T> level(String key, String label, String unit, double min, double max, ToDoubleFunction<T> reader) {
        return add(new SignalSpec(key, label, unit, min, max, Kind.LEVEL), reader);
    }

    public Schema<T> bool(String key, String label, Predicate<T> reader) {
        return add(new SignalSpec(key, label, "", 0, 1, Kind.BOOLEAN), be -> reader.test(be) ? 1.0 : 0.0);
    }

    /** A boolean that can also be unavailable: the reader returns 1, 0 or NaN. */
    public Schema<T> boolOrNone(String key, String label, ToDoubleFunction<T> reader) {
        return add(new SignalSpec(key, label, "", 0, 1, Kind.BOOLEAN), reader);
    }

    public Schema<T> text(String key, String label, Function<T, String> reader) {
        SignalSpec spec = new SignalSpec(key, label, "", 0, 0, Kind.TEXT);
        specs.put(key, spec);
        textReaders.put(key, reader);
        return this;
    }

    public Schema<T> command(String key, String label, Kind kind, double min, double max, Command<T> body) {
        commandSpecs.put(key, new CommandSpec(key, label, kind, min, max));
        commandBodies.put(key, body);
        return this;
    }

    public Schema<T> pulse(String key, String label, Command<T> body) {
        return command(key, label, Kind.PULSE, 0, 1, body);
    }

    public Schema<T> toggle(String key, String label, Command<T> body) {
        return command(key, label, Kind.BOOLEAN, 0, 1, body);
    }

    /** Signals whose keys depend on the live device (per-rod data). */
    public Schema<T> dynamicSignals(Function<T, List<SignalSpec>> list, BiFunction<T, String, OptionalDouble> reader) {
        extraSignals = list;
        extraReader = reader;
        return this;
    }

    public Schema<T> dynamicCommands(Function<T, List<CommandSpec>> list, KeyedCommand<T> body) {
        extraCommands = list;
        extraCommand = body;
        return this;
    }

    private Schema<T> add(SignalSpec spec, ToDoubleFunction<T> reader) {
        specs.put(spec.key(), spec);
        readers.put(spec.key(), reader);
        return this;
    }

    public SignalProvider bind(T be) {
        return new Bound(be);
    }

    private final class Bound implements SignalProvider {
        private final T be;
        private final List<SignalSpec> fixedSignals = List.copyOf(specs.values());
        private final List<CommandSpec> fixedCommands = List.copyOf(commandSpecs.values());

        Bound(T be) { this.be = be; }

        private boolean live() { return !be.isRemoved(); }

        @Override public List<SignalSpec> signals() {
            if (extraSignals == null || !live()) return fixedSignals;
            List<SignalSpec> all = new ArrayList<>(fixedSignals);
            all.addAll(extraSignals.apply(be));
            return List.copyOf(all);
        }

        @Override public OptionalDouble read(String key) {
            if (!live()) return OptionalDouble.empty();
            ToDoubleFunction<T> reader = readers.get(key);
            if (reader != null) {
                double value = reader.applyAsDouble(be);
                return Double.isNaN(value) ? OptionalDouble.empty() : OptionalDouble.of(value);
            }
            return extraReader == null ? OptionalDouble.empty() : extraReader.apply(be, key);
        }

        @Override public Optional<String> readText(String key) {
            Function<T, String> reader = textReaders.get(key);
            if (reader == null || !live()) return Optional.empty();
            return Optional.ofNullable(reader.apply(be));
        }

        @Override public List<CommandSpec> commands() {
            if (extraCommands == null || !live()) return fixedCommands;
            List<CommandSpec> all = new ArrayList<>(fixedCommands);
            all.addAll(extraCommands.apply(be));
            return List.copyOf(all);
        }

        @Override public boolean command(String key, double value) {
            if (!live() || Double.isNaN(value)) return false;
            CommandSpec spec = commandSpecs.get(key);
            if (spec == null) return extraCommand != null && extraCommand.run(be, key, value);
            if (spec.kind() != Kind.PULSE && spec.kind() != Kind.BOOLEAN
                    && (!Double.isFinite(value) || value < spec.min() - 1e-9 || value > spec.max() + 1e-9)) return false;
            return commandBodies.get(key).run(be, value);
        }
    }
}
