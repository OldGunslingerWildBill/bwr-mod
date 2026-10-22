package dev.bwr.mod.radiation;

import com.mojang.logging.LogUtils;
import dev.bwr.core.radiation.DoseModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The extension point for radiation sources besides the reactor (which {@link RadiationField} reads from the existing
 * hazard model). Two ways in, both server thread only and both in mSv/h:
 *
 * <pre>
 *   // 1. One provider for a whole kind of source (a spent fuel pool block entity class, say), registered once:
 *   RadiationSources.register(ResourceLocation.fromNamespaceAndPath("bwr", "spent_fuel_pool"),
 *           (level, at) -> myPools.in(level).stream().mapToDouble(p -> p.doseRateAt(at)).sum());
 *
 *   // 2. A single point source that comes and goes with a block entity (set on load, clear on remove or unload):
 *   RadiationSources.emit(level, blockEntity, Vec3.atCenterOf(pos), gammaWatts, transmission);
 *   RadiationSources.clear(level, blockEntity);
 * </pre>
 *
 * A point source is gamma power in watts (see {@code DoseModel.INVENTORY_GAMMA_WATTS} for the scale of a whole core
 * inventory) and the fraction of it that gets through what is around it; the dose rate is
 * {@code DoseModel.pointSourceMsvPerH}. Passing the same owner again moves or re-rates its source.
 *
 * <p>Instruments sample a few times a second, so a source costs little; keep a provider's work proportional to its own
 * sources, not to the loaded world. A provider that throws is skipped and logged once.
 */
public final class RadiationSources {
    private RadiationSources() {
    }

    private record Point(Vec3 at, double gammaWatts, double transmission) {
    }

    private static final Map<ResourceLocation, RadiationSource> PROVIDERS = new LinkedHashMap<>();
    private static final Map<Level, Map<Object, Point>> POINTS = new WeakHashMap<>();
    private static final Set<ResourceLocation> COMPLAINED = new HashSet<>();

    /** Registers (or replaces) the provider with this id. */
    public static synchronized void register(ResourceLocation id, RadiationSource source) {
        PROVIDERS.put(id, source);
    }

    /** Removes the provider with this id. */
    public static synchronized void unregister(ResourceLocation id) {
        PROVIDERS.remove(id);
    }

    /** Sets the point source owned by {@code owner} in {@code level}: gamma watts and the share that gets out. */
    public static synchronized void emit(Level level, Object owner, Vec3 at, double gammaWatts, double transmission) {
        if (level == null || level.isClientSide() || owner == null) {
            return;
        }
        if (!(gammaWatts > 0.0) || !(transmission > 0.0)) {
            clear(level, owner);
            return;
        }
        POINTS.computeIfAbsent(level, ignored -> new LinkedHashMap<>()).put(owner, new Point(at, gammaWatts, transmission));
    }

    /** Removes the point source owned by {@code owner}. */
    public static synchronized void clear(Level level, Object owner) {
        Map<Object, Point> points = POINTS.get(level);
        if (points != null) {
            points.remove(owner);
        }
    }

    /** How many point sources are set in {@code level} (tests, diagnostics). */
    public static synchronized int pointCount(Level level) {
        Map<Object, Point> points = POINTS.get(level);
        return points == null ? 0 : points.size();
    }

    /** How many providers are registered. */
    public static synchronized int providerCount() {
        return PROVIDERS.size();
    }

    /** The sum of every registered provider and point source at {@code at}, mSv/h (zero when none). */
    public static synchronized double doseRateMsvPerHour(Level level, Vec3 at) {
        double total = 0.0;
        for (Map.Entry<ResourceLocation, RadiationSource> entry : PROVIDERS.entrySet()) {
            try {
                double d = entry.getValue().doseRateMsvPerHour(level, at);
                if (d > 0.0 && Double.isFinite(d)) {
                    total += d;
                }
            } catch (RuntimeException failure) {
                if (COMPLAINED.add(entry.getKey())) {
                    LogUtils.getLogger().warn("Radiation source {} failed and is skipped: {}", entry.getKey(), failure.toString());
                }
            }
        }
        Map<Object, Point> points = POINTS.get(level);
        if (points != null) {
            for (Point point : points.values()) {
                total += DoseModel.pointSourceMsvPerH(point.gammaWatts(), at.distanceTo(point.at()), point.transmission());
            }
        }
        return total;
    }
}
