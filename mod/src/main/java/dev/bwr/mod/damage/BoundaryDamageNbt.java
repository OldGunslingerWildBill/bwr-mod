package dev.bwr.mod.damage;

import dev.bwr.core.boundary.BoundaryStress;
import dev.bwr.core.boundary.PlantConfiguration;
import dev.bwr.mod.reactor.ReactorStateNbt;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * Persistence for the overpressure damage model — {@code SPEC.md} sections 7
 * and 11.
 *
 * <p>Exists for the same reason {@code ReactorStateNbt} does: {@code :core} is
 * plain Java with no Minecraft on its classpath, enforced by the build, so the
 * conversion has to live on this side of the boundary.
 *
 * <p><b>This has to be saved.</b> Accumulated stress is permanent damage and
 * the breaks are holes in the plant. A reactor that heals while its chunk is
 * unloaded is a reactor with no consequences, which would undo the only
 * automatic consequence the mod has. The snapshot is a fixed-length double
 * array, so it goes into one list tag and needs no per-field schema.
 */
public final class BoundaryDamageNbt {

    private BoundaryDamageNbt() {
    }

    /** Key this data is stored under in the controller's tag. */
    public static final String KEY = "Boundary";

    /** Write accumulated stress, breaks and exposure counters into a tag. */
    public static CompoundTag write(BoundaryStress boundary) {
        CompoundTag t = new CompoundTag();
        ReactorStateNbt.putDoubles(t, "s", boundary.toArray());
        return t;
    }

    /**
     * Restore into an existing model. A tag written by an older version with
     * fewer components is simply ignored rather than half-applied — losing the
     * damage record is better than restoring an inconsistent one, and it can
     * only happen across a schema change.
     */
    public static void read(CompoundTag t, BoundaryStress boundary) {
        if (t == null || boundary == null) {
            return;
        }
        double[] snapshot = ReactorStateNbt.getDoubles(t, "s");
        if (snapshot.length >= BoundaryStress.SNAPSHOT_LENGTH) {
            boundary.fromArray(snapshot);
        }
    }

    /**
     * Seed one reactor's failure draw from where it stands in the world.
     *
     * <p>The physics is otherwise bit-deterministic, and the failure draw is the
     * only random number in it. Seeding from the block position rather than the
     * clock keeps a single reactor reproducible from a given save while making
     * two reactors in the same world fail independently instead of in lockstep.
     */
    public static void seedFrom(BoundaryStress boundary, BlockPos pos) {
        if (boundary == null || pos == null) {
            return;
        }
        boundary.setRandomSeed(pos.asLong() * 0x9E3779B97F4A7C15L + BoundaryStress.DEFAULT_RANDOM_SEED);
    }

    /**
     * Which recirculation system a formed reactor is built with.
     *
     * <p>Today this is always external loops, because the reactor internal pump
     * block of {@code SPEC.md} section 4.4 does not exist yet — there is a
     * recirculation pump block and a jet pump block and nothing else. This
     * method is the single place that decision will be made when it does: the
     * damage model already handles both configurations and is tested on both,
     * so building the RIP block is all that remains for the upgrade to become
     * real in a world.
     */
    public static PlantConfiguration configurationFor(int reactorInternalPumpCount) {
        return reactorInternalPumpCount > 0
                ? PlantConfiguration.REACTOR_INTERNAL_PUMPS
                : PlantConfiguration.EXTERNAL_RECIRCULATION_LOOPS;
    }
}
