package dev.bwr.mod.signal;

import dev.bwr.mod.signal.adapters.SignalAdapters;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Entry point of the signal layer. Adapters are registered by block-entity class; {@link #at} resolves the
 * device at a position (a part of a multiblock resolves to its controller, exactly as the CC peripheral
 * lookup does) and returns a provider for it.
 *
 * <p>This package never imports ComputerCraft, so panels work without CC installed. It never loads a
 * chunk, and nothing in it acts unless a caller issues an explicit command.
 */
public final class SignalProviders {
    private SignalProviders() {}

    private static final Map<Class<?>, Function<BlockEntity, SignalProvider>> ADAPTERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Function<BlockEntity, SignalProvider>> RESOLVED = new ConcurrentHashMap<>();
    private static final Function<BlockEntity, SignalProvider> NONE = be -> null;
    private static volatile boolean builtinsRegistered;

    /** Register an adapter for a block-entity class (subclasses match unless they have their own adapter). */
    public static <T extends BlockEntity> void register(Class<T> type, Function<? super T, SignalProvider> adapter) {
        ADAPTERS.put(type, be -> adapter.apply(type.cast(be)));
        RESOLVED.clear();
    }

    /** The provider for the device at {@code pos}, or null when there is none or its chunk is not loaded. */
    public static SignalProvider at(Level level, BlockPos pos) {
        if (level == null || level.isClientSide() || pos == null || !level.isLoaded(pos)) return null;
        return of(DeviceResolver.resolve(level, pos));
    }

    /** The provider for a known block entity, or null if no adapter covers it. */
    public static SignalProvider of(BlockEntity be) {
        if (be == null || be.isRemoved()) return null;
        ensureBuiltins();
        return RESOLVED.computeIfAbsent(be.getClass(), SignalProviders::lookup).apply(be);
    }

    private static Function<BlockEntity, SignalProvider> lookup(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Function<BlockEntity, SignalProvider> adapter = ADAPTERS.get(c);
            if (adapter != null) return adapter;
        }
        return NONE;
    }

    private static void ensureBuiltins() {
        if (builtinsRegistered) return;
        synchronized (SignalProviders.class) {
            if (builtinsRegistered) return;
            builtinsRegistered = true;
            SignalAdapters.registerAll();
        }
    }
}
