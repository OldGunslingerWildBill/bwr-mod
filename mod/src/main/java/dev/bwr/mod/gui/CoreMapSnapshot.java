package dev.bwr.mod.gui;

import dev.bwr.core.ReactorCore;
import dev.bwr.core.fuel.CoreLoading;
import dev.bwr.core.fuel.FuelAssembly;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The core map: one entry per core slot, carrying what is loaded there and how
 * much power it is making.
 *
 * <p>This is the data behind both overlays of {@code SPEC.md} section 10. The
 * operating overlay reads {@link #relativeFlux}, the refuelling overlay reads
 * {@link #burnupMwdPerTonne} and the fuel identity, and they are the same
 * numbers off the same solve — which is the point of building one grid widget
 * and two overlays rather than two grids.
 *
 * <h2>Where the flux comes from</h2>
 * {@link CoreLoading#powerWeights()}. On a formed reactor that is not the
 * analytic radial stand-in: {@code ReactorCore} installs
 * {@code NodalFluxSolver} as the loading's weight source at construction and
 * re-solves it once a second, so these weights already carry rod shadowing,
 * burnup and the void distribution. Reading through {@code powerWeights()}
 * rather than reaching into the solver is the seam: if the nodal solve is
 * replaced or extended — per-axial-node flux is the obvious next step, and
 * {@code FluxSolution.flux(position, node)} already exposes it — this map picks
 * up the change without a line of GUI code moving. An axial overlay would add a
 * second array here and a third overlay mode on the widget.
 */
public final class CoreMapSnapshot {

    /** Burnup wire resolution, MWd/t per unit. Keeps a 1.6 MWd/t core inside a short. */
    private static final double BURNUP_QUANTUM = 50.0;

    public int latticeWidth;
    public int coreSlotCount;

    /** Lattice square for each core slot. */
    public int[] latticeIndex = new int[0];

    /** Index into {@link #fuelTypeNames}, or -1 for an empty position. */
    public int[] fuelTypeIndex = new int[0];

    public String[] fuelTypeNames = new String[0];
    public double[] burnupMwdPerTonne = new double[0];
    public double[] enrichmentWeightFraction = new double[0];
    public double[] kInf = new double[0];

    /** Power weight normalised so the hottest bundle in the core is 1.0. */
    public double[] relativeFlux = new double[0];

    /** Power weight of the hottest bundle, as a fraction of core power. */
    public double peakWeight;

    public double coreThermalMW;

    /** Thermal power of one slot, MW. */
    public double assemblyThermalMW(int slot) {
        return relativeFlux[slot] * peakWeight * coreThermalMW;
    }

    /** Radial peaking factor: hottest bundle over the core average. */
    public double peakingFactor() {
        int loaded = 0;
        for (int i = 0; i < coreSlotCount; i++) {
            if (fuelTypeIndex[i] >= 0) {
                loaded++;
            }
        }
        return loaded == 0 ? 0.0 : peakWeight * loaded;
    }

    public boolean isOccupied(int slot) {
        return slot >= 0 && slot < coreSlotCount && fuelTypeIndex[slot] >= 0;
    }

    public String fuelTypeName(int slot) {
        int index = fuelTypeIndex[slot];
        return index < 0 || index >= fuelTypeNames.length ? "empty" : fuelTypeNames[index];
    }

    // -----------------------------------------------------------------
    // Wire format
    // -----------------------------------------------------------------

    /**
     * Write the map for a core.
     *
     * @param assemblyCount positions this vessel has, from the multiblock
     */
    public static void write(FriendlyByteBuf buf, ReactorCore core, int assemblyCount) {
        CoreLoading loading = core.getCoreLoading();
        int width = loading.latticeWidth();
        int[] positions = CoreLattice.corePositions(width, assemblyCount);
        double[] weights = loading.powerWeights();

        double peak = 0.0;
        for (int position : positions) {
            peak = Math.max(peak, weights[position]);
        }

        // Fuel names are interned per packet, because a core is normally one or
        // two fuel types across several hundred bundles.
        Map<String, Integer> names = new LinkedHashMap<>();
        for (int position : positions) {
            FuelAssembly assembly = loading.assemblyAt(position);
            if (assembly != null) {
                names.computeIfAbsent(assembly.fuelType().name(), k -> names.size());
            }
        }

        buf.writeVarInt(width);
        buf.writeVarInt(positions.length);
        buf.writeDouble(peak);
        buf.writeDouble(core.getThermalPowerMW());
        buf.writeVarInt(names.size());
        for (String name : names.keySet()) {
            buf.writeUtf(name, 64);
        }

        for (int position : positions) {
            FuelAssembly assembly = loading.assemblyAt(position);
            if (assembly == null) {
                buf.writeByte(0);
                continue;
            }
            buf.writeByte(1 + names.get(assembly.fuelType().name()));
            buf.writeShort(clampShort(assembly.burnupMwdPerTonne() / BURNUP_QUANTUM));
            buf.writeShort(clampShort(assembly.enrichmentWeightFraction() * 10000.0));
            buf.writeShort(clampShort(assembly.kInf() * 10000.0));
            double relative = peak > 0.0 ? weights[position] / peak : 0.0;
            buf.writeByte((int) Math.round(relative * 255.0));
        }
    }

    public static CoreMapSnapshot read(FriendlyByteBuf buf) {
        CoreMapSnapshot map = new CoreMapSnapshot();
        map.latticeWidth = buf.readVarInt();
        map.coreSlotCount = buf.readVarInt();
        map.peakWeight = buf.readDouble();
        map.coreThermalMW = buf.readDouble();

        int nameCount = buf.readVarInt();
        map.fuelTypeNames = new String[nameCount];
        for (int i = 0; i < nameCount; i++) {
            map.fuelTypeNames[i] = buf.readUtf(64);
        }

        int n = map.coreSlotCount;
        map.latticeIndex = CoreLattice.corePositions(map.latticeWidth, n);
        map.fuelTypeIndex = new int[n];
        map.burnupMwdPerTonne = new double[n];
        map.enrichmentWeightFraction = new double[n];
        map.kInf = new double[n];
        map.relativeFlux = new double[n];

        for (int i = 0; i < n; i++) {
            // Unsigned, because writeByte writes the low eight bits and the
            // index it carries is 1 + a position in the per-packet name table.
            // Reading it back signed meant the 128th distinct fuel type in one
            // core and everything after it came out negative, which isOccupied
            // reads as an empty slot — a loaded bundle drawn as a hole in the
            // map. The byte on the wire is unchanged, so this is a decode fix,
            // not a format change. The ceiling is 255 fuel types in one core;
            // above that the name table would need a varint.
            int type = buf.readUnsignedByte();
            if (type == 0) {
                map.fuelTypeIndex[i] = -1;
                continue;
            }
            map.fuelTypeIndex[i] = type - 1;
            map.burnupMwdPerTonne[i] = buf.readShort() * BURNUP_QUANTUM;
            map.enrichmentWeightFraction[i] = buf.readShort() / 10000.0;
            map.kInf[i] = buf.readShort() / 10000.0;
            map.relativeFlux[i] = buf.readUnsignedByte() / 255.0;
        }
        return map;
    }

    /** Distinct fuel types present, for a legend. */
    public List<String> loadedFuelTypes() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < coreSlotCount; i++) {
            String name = fuelTypeIndex[i] < 0 ? null : fuelTypeName(i);
            if (name != null && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    private static short clampShort(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
    }
}
