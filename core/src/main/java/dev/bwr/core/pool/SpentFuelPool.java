package dev.bwr.core.pool;

/**
 * The water and the fuel of a spent fuel pool: a heat balance, a level and, when the water is lost, the fuel
 * heating up in air. Pure Java, no Minecraft; the block entity feeds it the decay power of each stored assembly
 * ({@link dev.bwr.core.thermal.AssemblyDecayHeat}) and applies what it reports.
 *
 * <h2>Water</h2>
 * One well-mixed body of water at one temperature, {@code massKg} of it, in a vertical-sided pool of
 * {@code footprintCells} square metres (a metre is a block, a block of water is {@value #KG_PER_CELL} kg), so the
 * level is {@code mass / (1000 * footprint)} metres above the floor. Each step of {@code dt} seconds:
 *
 * <pre>
 *   heat in    = decay power of the assemblies the water covers
 *   evaporation = A (p_w - p_a) (0.089 + 0.0782 V) / Y           kg/s   (below 100 C)
 *   surface    = h_s A (T - T_ambient)        walls = U_w A_wet (T - T_ambient)
 *   cooling    = whatever the cooling loop removed this tick (removeHeatMJ)
 * </pre>
 *
 * The evaporation line is the ASHRAE indoor-pool correlation (HVAC Applications, "Natatoriums"; {@code A} pool
 * area m2, {@code p} in kPa, {@code V} air speed m/s, {@code Y} latent heat kJ/kg); {@code p_w} is the saturation
 * pressure at the water temperature from the Antoine equation (NIST constants for water, 1 to 100 C), {@code p_a}
 * that of 25 C air at 50 % relative humidity. The evaporated mass leaves the pool, carrying its latent heat.
 * The convective/radiative surface coefficient (8 W/m2 K) and the wall coefficient (1.5 W/m2 K: about 1.5 m of
 * lined concrete) are game-calibrated round numbers, not vendor data.
 *
 * <h2>Boiling</h2>
 * Above 100 C the water would be steam: the excess energy is converted to steam at 2257 kJ/kg, the temperature
 * is held at 100 C and that mass leaves the pool. With no cooling and a large heat load the level therefore falls
 * at {@code (decay power - losses) / 2257 kJ/kg}, which is the rate a test can check by hand.
 *
 * <h2>Fuel out of the water</h2>
 * An assembly whose rack top is above the level is uncovered. It stops giving its heat to the water and heats as a
 * lump of {@value #FUEL_HEAT_CAPACITY_KJ_PER_K} kJ/K that loses heat to the air by conduction (20 W/K) and
 * radiation (0.4 m2 of effective area at emissivity 0.7: a rack is a poor view of the room), so an old assembly
 * settles at a few hundred degrees and a fresh one runs away. At 565 C the cladding is taken to burst (a gap
 * release) and at 900 C the zirconium ignites and adds 100 kW per assembly until 50 kg of it is used up (NUREG-1738
 * is the source of the two temperatures; the powers and masses are game scale). Water covering an assembly again
 * quenches it. None of this is validated; it is a plausible shape for the accident, not a code.
 *
 * <p>No protection logic lives here: nothing starts cooling or makeup, nothing trips. The pool boils and uncovers
 * its fuel exactly as far as the player lets it.
 */
public final class SpentFuelPool {

    /** Assemblies in one rack block. */
    public static final int ASSEMBLIES_PER_RACK = 4;

    /** Water in one cell (one cubic metre), kilograms. */
    public static final double KG_PER_CELL = 1000.0;

    /** Specific heat of liquid water used for the pool, kJ/(kg K): the same as the RHR exchanger. */
    public static final double CP_KJ_PER_KG_K = 4.184;

    /** Boiling point at the pool surface (atmospheric pressure), C. */
    public static final double BOIL_C = 100.0;

    /** Latent heat of vaporisation at 100 C, kJ/kg. */
    public static final double LATENT_BOIL_KJ_PER_KG = 2257.0;

    /** Latent heat used for evaporation at pool temperatures, kJ/kg (ASHRAE uses the value at the water temperature). */
    public static final double LATENT_EVAP_KJ_PER_KG = 2400.0;

    public static final double AMBIENT_C = SuppressionPool.AMBIENT_TEMPERATURE_C;

    /** Vapour pressure of the hall air (25 C at 50 % relative humidity), kPa. */
    public static final double AIR_VAPOUR_PRESSURE_KPA = 1.58;

    /** Still hall air over the pool, m/s. */
    public static final double AIR_SPEED_M_PER_S = 0.1;

    public static final double SURFACE_W_PER_M2_K = 8.0;
    public static final double WALL_W_PER_M2_K = 1.5;

    /** Heat capacity of an assembly with its channel and structure (about 600 kg at 0.3 kJ/kg K). */
    public static final double FUEL_HEAT_CAPACITY_KJ_PER_K = 180.0;
    public static final double FUEL_AIR_W_PER_K = 20.0;
    public static final double FUEL_RADIATING_AREA_M2 = 0.4;
    public static final double FUEL_EMISSIVITY = 0.7;
    private static final double STEFAN_BOLTZMANN = 5.670374e-8;

    public static final double CLADDING_BURST_C = 565.0;
    public static final double ZIRCONIUM_IGNITION_C = 900.0;
    public static final double OXIDATION_POWER_W = 100_000.0;
    /** 50 kg of zirconium at about 12 MJ/kg. */
    public static final double ZIRCONIUM_ENERGY_J = 6.0e8;
    /** The fuel is not allowed past this (it would be melting); keeps the numbers finite. */
    private static final double FUEL_CEILING_C = 2200.0;

    /** Hazard added per second by one igniting assembly of full weight, in the units of the contamination index. */
    public static final double IGNITED_RELEASE_PER_S = 1.0e-5;
    /** Hazard added once when an assembly's cladding bursts. */
    public static final double CLADDING_BURST_RELEASE = 5.0e-4;

    /** The cooling pump loses suction below this fraction of the pool's capacity. */
    public static final double SUCTION_FLOOR_FRACTION = 0.05;

    /** One storage position: its geometry, what is in it and what has happened to it. */
    public static final class Slot {
        /** Top of the rack this position is in, metres above the pool floor. */
        public final double topM;
        public boolean occupied;
        /** Decay power, W, set by the owner before each step. */
        public double decayW;
        /** Weight of this assembly's fission-product inventory in the release (0..1). */
        public double releaseWeight = 1.0;
        public double fuelC = AMBIENT_C;
        public boolean damaged;
        public boolean ignited;
        /** Fraction of the zirconium not yet burned. */
        public double zirconiumLeft = 1.0;
        /** Set by the last step: the water covers this position. */
        public boolean covered = true;

        public Slot(double topM) {
            this.topM = topM;
        }
    }

    /** What one step did, for the owner. */
    public static final class Report {
        public double hazardAdded;
        public int newlyDamaged;
        public int newlyIgnited;
    }

    private int footprintCells = 1;
    private int heightCells = 1;
    private double massKg;
    private double temperatureC = 30.0;

    private double lastCoveredDecayW;
    private double lastUncoveredDecayW;
    private double lastEvaporationKgPerS;
    private double lastBoilKgPerS;
    private double lastEvaporationLossW;
    private double lastSurfaceLossW;
    private double lastWallLossW;
    private double lastCoolingW;
    private double pendingCoolingJ;
    private double cumulativeBoiledKg;
    private double cumulativeEvaporatedKg;

    // ---------------------------------------------------------------
    // Geometry and inventory
    // ---------------------------------------------------------------

    /** Set the pool's size: the interior footprint in square metres and the interior depth in metres. */
    public void configure(int footprintCells, int heightCells) {
        this.footprintCells = Math.max(1, footprintCells);
        this.heightCells = Math.max(1, heightCells);
        this.massKg = Math.min(massKg, capacityKg());
    }

    public int footprintCells() {
        return footprintCells;
    }

    public int heightCells() {
        return heightCells;
    }

    public double capacityKg() {
        return KG_PER_CELL * footprintCells * heightCells;
    }

    public double massKg() {
        return massKg;
    }

    public double temperatureC() {
        return temperatureC;
    }

    /** Water level, metres above the pool floor. */
    public double levelM() {
        return massKg / (KG_PER_CELL * footprintCells);
    }

    public double levelFraction() {
        return massKg / capacityKg();
    }

    /** Restores a saved state: water that is not there is not hot. */
    public void restore(double massKg, double temperatureC) {
        this.massKg = Double.isFinite(massKg) ? Math.max(0.0, Math.min(massKg, capacityKg())) : 0.0;
        this.temperatureC = Double.isFinite(temperatureC) ? Math.max(0.0, Math.min(BOIL_C, temperatureC)) : 30.0;
    }

    /** Water delivered through a makeup port; mixes by energy. Returns the mass accepted (the pool has a rim). */
    public double receiveWaterKg(double kg, double inletC) {
        if (!Double.isFinite(kg) || !Double.isFinite(inletC) || kg <= 0.0) {
            return 0.0;
        }
        double accepted = Math.min(kg, Math.max(0.0, capacityKg() - massKg));
        if (accepted <= 0.0) {
            return 0.0;
        }
        double clamped = Math.max(0.0, Math.min(BOIL_C, inletC));
        temperatureC = massKg <= 0.0 ? clamped : (massKg * temperatureC + accepted * clamped) / (massKg + accepted);
        massKg += accepted;
        return accepted;
    }

    /** Room for makeup water, kg. */
    public double spaceKg() {
        return Math.max(0.0, capacityKg() - massKg);
    }

    /** Water the cooling pump can draw before it loses suction, kg. */
    public double availableSuctionKg() {
        return Math.max(0.0, massKg - SUCTION_FLOOR_FRACTION * capacityKg());
    }

    /**
     * Takes heat out through the cooling loop, never below the cold side's temperature. The heat is removed at once
     * from the water; the step reports it as the cooling of that tick.
     */
    public double removeHeatMJ(double requestedMJ, double sinkC) {
        if (!Double.isFinite(requestedMJ) || !Double.isFinite(sinkC) || requestedMJ <= 0.0 || massKg <= 0.0) {
            return 0.0;
        }
        double available = Math.max(0.0, temperatureC - sinkC) * massKg * CP_KJ_PER_KG_K / 1000.0;
        double removed = Math.min(requestedMJ, available);
        temperatureC -= removed * 1000.0 / (massKg * CP_KJ_PER_KG_K);
        pendingCoolingJ += removed * 1.0e6;
        return removed;
    }

    // ---------------------------------------------------------------
    // Physics
    // ---------------------------------------------------------------

    /** Saturation pressure of water at {@code celsius}, kPa: Antoine, NIST constants, valid 1 to 100 C. */
    public static double saturationPressureKpa(double celsius) {
        double t = Math.max(1.0, Math.min(BOIL_C, celsius));
        double mmHg = Math.pow(10.0, 8.07131 - 1730.63 / (233.426 + t));
        return mmHg * 0.133322;
    }

    /** Evaporation rate of an open surface of {@code areaM2} at {@code celsius}, kg/s. */
    public static double evaporationKgPerS(double areaM2, double celsius) {
        double dp = saturationPressureKpa(celsius) - AIR_VAPOUR_PRESSURE_KPA;
        if (dp <= 0.0 || areaM2 <= 0.0) {
            return 0.0;
        }
        return areaM2 * dp * (0.089 + 0.0782 * AIR_SPEED_M_PER_S) / LATENT_EVAP_KJ_PER_KG;
    }

    /** Wetted floor and wall area at the current level, m2 (square pool of the footprint's side). */
    public double wettedAreaM2() {
        double side = Math.sqrt(footprintCells);
        return footprintCells + 4.0 * side * Math.min(levelM(), heightCells);
    }

    /**
     * Advance {@code dtSeconds}. The caller has set {@link Slot#decayW} and {@link Slot#occupied} on each slot.
     */
    public Report step(double dtSeconds, Slot[] slots) {
        Report report = new Report();
        lastCoveredDecayW = 0.0;
        lastUncoveredDecayW = 0.0;
        lastEvaporationKgPerS = 0.0;
        lastBoilKgPerS = 0.0;
        lastEvaporationLossW = 0.0;
        lastSurfaceLossW = 0.0;
        lastWallLossW = 0.0;
        lastCoolingW = pendingCoolingJ / Math.max(1.0e-9, dtSeconds);
        pendingCoolingJ = 0.0;
        if (!(dtSeconds > 0.0) || !Double.isFinite(dtSeconds)) {
            return report;
        }
        double level = levelM();
        double coveredW = 0.0;
        for (Slot slot : slots) {
            if (!slot.occupied) {
                slot.covered = true;
                continue;
            }
            slot.covered = massKg > 0.0 && level >= slot.topM - 1.0e-9;
            if (slot.covered) {
                coveredW += slot.decayW;
                // A quench: the water is there again, the fuel is the water's temperature.
                slot.fuelC = temperatureC;
                slot.ignited = false;
            } else {
                lastUncoveredDecayW += slot.decayW;
                heatInAir(slot, dtSeconds, report);
            }
        }
        lastCoveredDecayW = coveredW;
        if (massKg <= 0.0) {
            return report;
        }

        double area = footprintCells;
        double energyJ = coveredW * dtSeconds;
        if (temperatureC < BOIL_C) {
            double evap = Math.min(massKg, evaporationKgPerS(area, temperatureC) * dtSeconds);
            lastEvaporationKgPerS = evap / dtSeconds;
            lastEvaporationLossW = evap * LATENT_EVAP_KJ_PER_KG * 1000.0 / dtSeconds;
            energyJ -= evap * LATENT_EVAP_KJ_PER_KG * 1000.0;
            massKg -= evap;
            cumulativeEvaporatedKg += evap;
        }
        lastSurfaceLossW = SURFACE_W_PER_M2_K * area * Math.max(-20.0, temperatureC - AMBIENT_C);
        lastWallLossW = WALL_W_PER_M2_K * wettedAreaM2() * Math.max(-20.0, temperatureC - AMBIENT_C);
        energyJ -= (lastSurfaceLossW + lastWallLossW) * dtSeconds;
        if (massKg <= 0.0) {
            massKg = 0.0;
            return report;
        }
        temperatureC += energyJ / (massKg * CP_KJ_PER_KG_K * 1000.0);
        if (temperatureC > BOIL_C) {
            double excessJ = (temperatureC - BOIL_C) * massKg * CP_KJ_PER_KG_K * 1000.0;
            double boiled = Math.min(massKg, excessJ / (LATENT_BOIL_KJ_PER_KG * 1000.0));
            massKg -= boiled;
            temperatureC = BOIL_C;
            lastBoilKgPerS = boiled / dtSeconds;
            cumulativeBoiledKg += boiled;
        }
        if (temperatureC < 0.0) {
            temperatureC = 0.0;
        }
        // covered fuel sits at the temperature of the water around it, as the step ended
        for (Slot slot : slots) {
            if (slot.occupied && slot.covered) {
                slot.fuelC = temperatureC;
            }
        }
        return report;
    }

    private static void heatInAir(Slot slot, double dt, Report report) {
        double powerW = slot.decayW + (slot.ignited ? OXIDATION_POWER_W : 0.0);
        double kelvin = slot.fuelC + 273.15;
        double ambientK = AMBIENT_C + 273.15;
        double lossW = FUEL_AIR_W_PER_K * (kelvin - ambientK)
                + STEFAN_BOLTZMANN * FUEL_EMISSIVITY * FUEL_RADIATING_AREA_M2
                * (kelvin * kelvin * kelvin * kelvin - ambientK * ambientK * ambientK * ambientK);
        slot.fuelC += (powerW - lossW) * dt / (FUEL_HEAT_CAPACITY_KJ_PER_K * 1000.0);
        if (slot.fuelC > FUEL_CEILING_C) {
            slot.fuelC = FUEL_CEILING_C;
        }
        if (slot.fuelC >= CLADDING_BURST_C && !slot.damaged) {
            slot.damaged = true;
            report.newlyDamaged++;
            report.hazardAdded += CLADDING_BURST_RELEASE * slot.releaseWeight;
        }
        if (!slot.ignited && slot.zirconiumLeft > 0.0 && slot.fuelC >= ZIRCONIUM_IGNITION_C) {
            slot.ignited = true;
            report.newlyIgnited++;
        }
        if (slot.ignited) {
            slot.zirconiumLeft -= OXIDATION_POWER_W * dt / ZIRCONIUM_ENERGY_J;
            report.hazardAdded += IGNITED_RELEASE_PER_S * slot.releaseWeight * dt;
            if (slot.zirconiumLeft <= 0.0) {
                slot.zirconiumLeft = 0.0;
                slot.ignited = false;
            }
        }
    }

    // ---------------------------------------------------------------
    // Readouts and estimates
    // ---------------------------------------------------------------

    public double coveredDecayW() {
        return lastCoveredDecayW;
    }

    public double uncoveredDecayW() {
        return lastUncoveredDecayW;
    }

    public double evaporationKgPerS() {
        return lastEvaporationKgPerS;
    }

    /** Steam leaving the pool by boiling, kg/s, over the last step. */
    public double boilKgPerS() {
        return lastBoilKgPerS;
    }

    public double evaporationLossW() {
        return lastEvaporationLossW;
    }

    /** Surface and wall losses together, W. */
    public double passiveLossW() {
        return lastSurfaceLossW + lastWallLossW;
    }

    /** What the cooling loop removed over the last step, W. */
    public double coolingW() {
        return lastCoolingW;
    }

    public double cumulativeBoiledKg() {
        return cumulativeBoiledKg;
    }

    public double cumulativeEvaporatedKg() {
        return cumulativeEvaporatedKg;
    }

    /** Net heating of the water over the last step, W (negative: cooling). */
    public double netHeatW() {
        return lastCoveredDecayW - lastEvaporationLossW - lastSurfaceLossW - lastWallLossW - lastCoolingW;
    }

    /**
     * Seconds until the water boils if the last step's decay power, cooling and losses stayed as they are; zero if
     * it is boiling, positive infinity if it is not heating. A projection, not a prediction: the decay power falls.
     */
    public double secondsToBoil() {
        if (massKg <= 0.0 || temperatureC >= BOIL_C) {
            return 0.0;
        }
        double net = netHeatW();
        if (!(net > 0.0)) {
            return Double.POSITIVE_INFINITY;
        }
        return massKg * CP_KJ_PER_KG_K * 1000.0 * (BOIL_C - temperatureC) / net;
    }

    /**
     * Seconds until the level reaches {@code topM} (the highest rack top holding fuel) by boil-off, from now, at the
     * current decay power and cooling: the time to boil plus the time to boil away the water above that line. Zero
     * if the fuel is already uncovered, infinity if the pool is not losing water.
     */
    public double secondsToUncover(double topM) {
        if (!(topM > 0.0)) {
            return Double.POSITIVE_INFINITY;
        }
        if (levelM() <= topM + 1.0e-9) {
            return 0.0;
        }
        double toBoil = secondsToBoil();
        if (Double.isInfinite(toBoil)) {
            return Double.POSITIVE_INFINITY;
        }
        double boilNetW = lastCoveredDecayW - lastCoolingW
                - (SURFACE_W_PER_M2_K * footprintCells + WALL_W_PER_M2_K * wettedAreaM2()) * (BOIL_C - AMBIENT_C);
        if (!(boilNetW > 0.0)) {
            return Double.POSITIVE_INFINITY;
        }
        double toLose = massKg - topM * KG_PER_CELL * footprintCells;
        return toBoil + toLose * LATENT_BOIL_KJ_PER_KG * 1000.0 / boilNetW;
    }
}
