package dev.bwr.mod.damage;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.core.accident.SevereAccident;
import dev.bwr.mod.reactor.ReactorStateNbt;
import net.minecraft.nbt.CompoundTag;

/**
 * Persists the severe accident progression and the containment atmosphere.
 *
 * <p>Like {@link BoundaryDamageNbt}, this sits beside {@code ReactorState}
 * rather than inside it: core damage, corium and containment gas are a record
 * of what happened to the plant, not part of its operating point. A save that
 * lost them would quietly un-melt a core.
 *
 * <p>Old saves have no key and load as a pristine core and an air-filled,
 * uninerted containment.
 */
public final class SevereAccidentNbt {

    private SevereAccidentNbt() {
    }

    /** Key this data is stored under in the controller's tag. */
    public static final String KEY = "SevereAccident";

    /**
     * Key of the corium pool's placement record, beside {@link #KEY}. Kept
     * separate because it belongs to the world, not the core: it is written
     * whether or not a core exists, and must survive a core being rebuilt or
     * reset, or the blocks already melted would be placed again. Absent in old
     * saves, which read as nothing placed yet.
     */
    public static final String CORIUM_KEY = "CoriumPool";

    public static void writeCorium(CompoundTag controllerTag, dev.bwr.mod.accident.CoriumPool pool) {
        CompoundTag t = new CompoundTag();
        pool.write(t);
        controllerTag.put(CORIUM_KEY, t);
    }

    public static void readCorium(CompoundTag controllerTag, dev.bwr.mod.accident.CoriumPool pool) {
        pool.read(controllerTag.contains(CORIUM_KEY, net.minecraft.nbt.Tag.TAG_COMPOUND)
                ? controllerTag.getCompound(CORIUM_KEY) : new CompoundTag());
    }

    public static CompoundTag write(ReactorCore core) {
        CompoundTag t = new CompoundTag();
        if (core == null) {
            return t;
        }
        ReactorStateNbt.putDoubles(t, "core", core.getSevereAccident().toArray());
        ReactorStateNbt.putDoubles(t, "containment", core.getContainment().toArray());
        return t;
    }

    public static void read(CompoundTag t, ReactorCore core) {
        if (t == null || core == null) {
            return;
        }
        double[] damage = ReactorStateNbt.getDoubles(t, "core");
        if (damage.length >= SevereAccident.SNAPSHOT_LENGTH) {
            core.getSevereAccident().fromArray(damage);
        }
        double[] gas = ReactorStateNbt.getDoubles(t, "containment");
        if (gas.length >= ContainmentAtmosphere.SNAPSHOT_LENGTH) {
            core.getContainment().fromArray(gas);
        }
    }
}
