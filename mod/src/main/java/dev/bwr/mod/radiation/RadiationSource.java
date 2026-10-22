package dev.bwr.mod.radiation;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Something that makes radiation the survey meter and the area radiation monitor can read: a spent fuel pool, a
 * stack of irradiated assemblies, a hot sample. Register one with {@link RadiationSources#register}.
 *
 * <p>The unit is <b>mSv/h</b>, gamma, at the point asked about; {@code dev.bwr.core.radiation.DoseModel} has the
 * arithmetic for a point source behind shielding ({@code pointSourceMsvPerH}). Return zero when the source is not in
 * that level or is out of reach. It is called on the server thread, a few times a second per instrument at most, never
 * once per tick per block, so it may loop over its own sources but must not scan the world.
 */
@FunctionalInterface
public interface RadiationSource {
    /** The dose rate this source adds at {@code at} in {@code level}, mSv/h; zero, negative or NaN count as none. */
    double doseRateMsvPerHour(Level level, Vec3 at);
}
