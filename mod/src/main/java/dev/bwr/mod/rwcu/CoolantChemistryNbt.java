package dev.bwr.mod.rwcu;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.chemistry.CoolantChemistry;
import dev.bwr.mod.reactor.ReactorStateNbt;
import net.minecraft.nbt.CompoundTag;

/**
 * Persists the coolant chemistry beside {@code ReactorState}, like the boundary damage and the severe accident record. It
 * is written only when it differs from a clean plant, so a save with a clean coolant has no key and an old save, which
 * never had one, loads clean.
 */
public final class CoolantChemistryNbt {
    private CoolantChemistryNbt() {}

    public static final String KEY = "Chemistry";

    /** The record, or null when the coolant is clean and nothing needs saving. */
    public static CompoundTag write(ReactorCore core) {
        if (core == null || !core.getChemistry().differsFromClean(core.getLiquidMassKg())) return null;
        CompoundTag t = new CompoundTag();
        ReactorStateNbt.putDoubles(t, "chemistry", core.getChemistry().toArray());
        if (core.getChemistry().getCondenserInleakageGPerS() != CoolantChemistry.CONDENSER_INLEAKAGE_G_PER_S)
            t.putDouble("condenserInleakage", core.getChemistry().getCondenserInleakageGPerS());
        return t;
    }

    public static void read(CompoundTag t, ReactorCore core) {
        if (t == null || core == null) return;
        double[] a = ReactorStateNbt.getDoubles(t, "chemistry");
        if (a.length >= CoolantChemistry.SNAPSHOT_LENGTH) core.getChemistry().fromArray(a);
        if (t.contains("condenserInleakage")) core.getChemistry().setCondenserInleakageGPerS(t.getDouble("condenserInleakage"));
    }
}
