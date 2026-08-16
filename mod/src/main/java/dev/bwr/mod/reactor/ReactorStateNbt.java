package dev.bwr.mod.reactor;

import dev.bwr.core.ReactorState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Translates {@link ReactorState} to and from NBT.
 *
 * <p>This class exists so that {@code :core} never learns what NBT is. The
 * physics module is plain Java with no Minecraft on its classpath — that is
 * enforced by the build, not by convention — so the conversion has to live on
 * this side of the boundary.
 *
 * <p>Every component of the snapshot is written. {@code SPEC.md} section 11
 * requires freeze-and-resume: a reactor unloaded mid-transient must resume
 * mid-transient, which means precursor concentrations, decay heat group
 * inventories, peak clad and peak fuel temperature, oxidation fraction, both of
 * the void model's lagged states, all five frozen outputs of the nodal solve —
 * effective beta, prompt lifetime, the per-rod flux weights, the fuel excess
 * reactivity and the Doppler coefficient — the scram latch and the IRM range
 * switches all have to survive. Dropping any one of them
 * quietly resets a piece of an accident in progress.
 *
 * <p>{@link ReactorState} now has exactly one constructor, the canonical one,
 * and the compatibility overload that used to shadow it is gone. That overload
 * defaulted {@code scramActive} to false, so binding to it made a saved scram
 * come back cleared: the drives resumed unlatched at whatever notch they had
 * reached, and on a de-energised CRD bus — the ATWS case, where the scram path
 * is the only thing that could still move a rod — they stopped there for good.
 * A save must not cancel a scram. <b>When a component is added to the record,
 * the compile error that appears here is the point:</b> add the key on both
 * sides, and do not reintroduce a shorter constructor to make it go away.
 *
 * <p>Keys are read with the plain getters, so a tag written before a component
 * existed reads back the type's zero — {@code 0.0}, or an empty array. Each new
 * key below documents what that default means for a legacy save; in every case
 * it is the behaviour that save already had, never a silently invented value.
 */
public final class ReactorStateNbt {

    private ReactorStateNbt() {
    }

    /**
     * NBT has int, long and byte arrays but no double array, so double arrays
     * travel as a list of doubles. Precision is preserved exactly.
     */
    public static void putDoubles(CompoundTag t, String key, double[] values) {
        ListTag list = new ListTag();
        for (double v : values) {
            list.add(DoubleTag.valueOf(v));
        }
        t.put(key, list);
    }

    /** Inverse of {@link #putDoubles}. Returns an empty array if the key is absent. */
    public static double[] getDoubles(CompoundTag t, String key) {
        ListTag list = t.getList(key, Tag.TAG_DOUBLE);
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.getDouble(i);
        }
        return out;
    }

    public static CompoundTag write(ReactorState s) {
        CompoundTag t = new CompoundTag();
        t.putDouble("n", s.neutronPower());
        putDoubles(t, "c", s.precursors());
        t.putDouble("src", s.sourceStrength());
        t.putDouble("decay", s.decayHeatFraction());
        putDoubles(t, "decayGroups", s.decayGroups());
        t.putDouble("fuelC", s.fuelTempC());
        t.putDouble("cladC", s.cladTempC());
        t.putDouble("peakCladC", s.peakCladTempC());
        // Monotonic damage, and the only input to the fraction-of-melt readout.
        // It cannot be reconstructed from the keys around it: peak fuel always
        // runs above peak clad, so the reconstruction it replaces — max(peak
        // clad, restored fuel temperature) — always lost, and every save walked
        // the recorded peak back down towards the coolant temperature.
        t.putDouble("peakFuelC", s.peakFuelTempC());
        t.putDouble("oxid", s.oxidationFraction());
        t.putDouble("h2", s.hydrogenKg());
        t.putDouble("oxideFilm", s.protectiveOxideArealKgPerM2());
        t.putDouble("coolantC", s.coolantTempC());
        t.putDouble("psig", s.pressurePsig());
        t.putDouble("void", s.voidFraction());
        // The void model's other lagged state. Written beside the void fraction
        // because the two are a pair: recomputing this one on load put it at the
        // equilibrium for the restored operating point, which is where a core
        // saved mid-transient is not, and the axial void profile that fell out
        // of it drove a different nodal flux shape on every reload.
        t.putDouble("inletH", s.coreInletEnthalpyKJPerKg());
        t.putDouble("flow", s.coreFlowFraction());
        t.putDouble("levelIn", s.waterLevelIn());
        t.putDouble("xe", s.xenon());
        t.putDouble("i", s.iodine());
        t.putDouble("boron", s.boronPpm());
        t.putDouble("burnup", s.burnupMwdPerTonne());
        t.putIntArray("notches", s.rodNotches());
        putDoubles(t, "accum", s.accumulatorCharge());
        t.putDouble("rho", s.reactivityTotal());
        t.putDouble("beta", s.betaEff());
        t.putDouble("lambda", s.promptLifetime());
        // The third frozen output of the nodal solve, written beside the beta and
        // prompt lifetime above because the three are one group. The rho on the
        // "rho" key was closed on exactly these weights, up to a second old; a
        // restore that re-solved for them closed it on the following second's and
        // the snapshot stopped reproducing itself by an ulp per reload.
        putDoubles(t, "rodFlux", s.rodFluxWeights());
        // The last two frozen aggregates, and the other two terms the recorded rho
        // was closed on. Both are fission-rate-weighted averages over the
        // assemblies, so both go stale with the flux weighting; recomputing them on
        // load moved rho by 5.6e-09 dk/k on a core with an ordinary burnup gradient
        // and by 5.0e-06 dk/k on a mixed-fuel one.
        t.putDouble("fuelExcess", s.fuelExcessReactivityDkOverK());
        t.putDouble("doppler", s.dopplerCoefficientPerCAtAnchor());
        t.putDouble("t", s.elapsedSeconds());
        // The scram latch is plant state, not a derived flag: it selects the
        // scram insertion path and it latches until resetScram(). Written in
        // record component order, like everything else here.
        t.putBoolean("scram", s.scramActive());
        // Range switch positions, not readings. Detector signals are deliberately
        // absent from the snapshot — a conditioning lag is a filter — but ranging
        // is manual by design, so nothing in the plant puts a switch back and
        // omitting these re-ranged the whole rack to detent 1 on every reload.
        t.putIntArray("irmRanges", s.intermediateRangeMonitorRanges());
        return t;
    }

    public static ReactorState read(CompoundTag t) {
        return new ReactorState(
                t.getDouble("n"),
                getDoubles(t, "c"),
                t.getDouble("src"),
                t.getDouble("decay"),
                getDoubles(t, "decayGroups"),
                t.getDouble("fuelC"),
                t.getDouble("cladC"),
                t.getDouble("peakCladC"),
                // Absent on a tag written before peak fuel was persisted, and
                // getDouble returns 0.0 for a missing key. ReactorCore.fromState
                // floors this at the peak clad temperature, so a legacy save
                // restores to exactly the value the old reconstruction gave it —
                // understated, but not newly wrong.
                t.getDouble("peakFuelC"),
                t.getDouble("oxid"),
                t.getDouble("h2"),
                t.getDouble("oxideFilm"),
                t.getDouble("coolantC"),
                t.getDouble("psig"),
                t.getDouble("void"),
                // Absent on a tag written before the inlet enthalpy was
                // persisted, and getDouble returns 0.0. Zero is not a reachable
                // enthalpy for water in a hot vessel, so ReactorCore.fromState
                // reads it as "this save records none" and falls back to the old
                // equilibrium reconstruction — exactly what that save used to
                // get, never a newly invented value.
                t.getDouble("inletH"),
                t.getDouble("flow"),
                t.getDouble("levelIn"),
                t.getDouble("xe"),
                t.getDouble("i"),
                t.getDouble("boron"),
                t.getDouble("burnup"),
                t.getIntArray("notches"),
                getDoubles(t, "accum"),
                t.getDouble("rho"),
                t.getDouble("beta"),
                t.getDouble("lambda"),
                // getDoubles returns an empty array for a missing key, and empty
                // means "this save records no weights". ReactorCore.fromState
                // reads that as a tag written before the component existed and
                // installs the weights of its own restore-time solve instead —
                // exactly what that save used to get. An array of the wrong length
                // (a multiblock resized between saves) takes the same path.
                getDoubles(t, "rodFlux"),
                // Absent on a tag written before the pair existed, and getDouble
                // returns 0.0 for both. The Doppler coefficient is the
                // discriminator: FuelType refuses a fuel whose coefficient is not
                // strictly negative, so zero cannot be a genuine persisted value
                // and ReactorCore.fromState reads it as "this save records
                // neither", recomputing both from the loaded fuel — exactly what
                // that save already got.
                t.getDouble("fuelExcess"),
                t.getDouble("doppler"),
                t.getDouble("t"),
                // Absent on a tag written before the latch was persisted, and
                // CompoundTag.getBoolean returns false for a missing key, which
                // is the right default: a save from that era genuinely has no
                // latch recorded and an un-scrammed reactor is the safe reading
                // of "we do not know".
                t.getBoolean("scram"),
                // getIntArray returns an empty array for a missing key, and an
                // empty array means "this save records no switch positions", on
                // which fromState leaves every switch where it is. That is the
                // only honest reading: inventing detent 1 for a channel the
                // record says nothing about is the auto-ranging this component
                // exists to stop.
                t.getIntArray("irmRanges")
        );
    }
}
