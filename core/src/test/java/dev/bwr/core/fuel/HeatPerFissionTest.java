package dev.bwr.core.fuel;

import dev.bwr.core.Check;
import dev.bwr.core.CoreConfig;
import dev.bwr.core.ReactorCore;

/**
 * {@code heat_per_fission_mev} reaches thermal output. SPEC 2.1.
 *
 * <h2>The defect these are the regression for</h2>
 * {@code heat_per_fission_mev} is one of the seven fields
 * {@link FuelTypeSpec#REQUIRED_FIELDS} refuses a datapack entry for omitting, it
 * is validated on load, it is carried through {@link FuelType} and
 * {@link FuelAssembly} and flux-weighted by {@link CoreLoading} — and then
 * nothing multiplied by it. {@link CoreLoading#heatPerFissionScaleFactor()} was
 * written, documented and never called, so a pack author could set the field to
 * any value at all and produce a bit-identical reactor. That is the worst shape a
 * defect takes here: a parameter that looks supported all the way down and is
 * inert at the bottom.
 *
 * <h2>What "wired" has to mean</h2>
 * Point kinetics is scale free. Its {@code n} is a normalised fission
 * <i>rate</i>, and the only thing that ties a rate to a power is the energy each
 * fission releases, so the scale factor belongs at the single point the rate
 * becomes a power and nowhere else. These tests pin down both halves of that:
 * everything downstream of the multiplication moves with it — heat, steam, fuel
 * temperature, burnup — and everything upstream of it does not. The neutron
 * instruments in particular measure flux and must go on reading flux, which is
 * why an APRM on a high-energy core reads low against a heat balance instead of
 * being quietly corrected.
 */
public final class HeatPerFissionTest {

    private HeatPerFissionTest() {
    }

    /**
     * A fuel identical to LEU in every neutronic respect and releasing exactly
     * twice the energy per fission.
     *
     * <p>Twice, rather than a realistic few per cent, because the whole question
     * is whether a number reaches a place: a factor of two separates "wired" from
     * "not wired" by 100% on every downstream quantity, where plutonium's real
     * 4.4% could hide inside the tolerance of a drifting core. Identical
     * neutronics means the two cores below differ in this one property and in
     * nothing else.
     */
    private static final FuelType DOUBLE_ENERGY_LEU = new FuelType(
            "test_double_energy",
            FuelType.LEU.kInfBase(),
            FuelType.LEU.beta(),
            FuelType.LEU.promptLifetimeSeconds(),
            FuelType.LEU.depletionKInfPerMwdPerTonne(),
            FuelType.LEU.dopplerCoeffPerC(),
            FuelType.LEU.gadContentWeightFraction(),
            2.0 * CoreLoading.EMPTY_CORE_HEAT_PER_FISSION_MEV,
            FuelType.LEU.nominalEnrichmentWeightFraction(),
            FuelType.LEU.conversionGainKInf());

    /**
     * Every shipped fuel's scale factor, and the two that must be exactly 1.
     *
     * <p>Both uranium fuels quote U-235's 202.5 MeV, which is the figure the
     * plant's rated thermal power is quoted against, so their factor is
     * {@code x / x} — exactly 1.0 in binary floating point, not approximately.
     * That is what makes this change invisible to every default loading and to
     * every other test in the suite, and it is worth asserting rather than
     * assuming: a factor that came out 0.9999999999999999 for LEU would move the
     * whole shipped calibration by a rounding error a nobody could find.
     */
    public static void test01_shippedUraniumFuelsScaleByExactlyOne() {
        for (FuelType type : FuelType.presets()) {
            CoreLoading loading = uniformCore(type);
            double expected = type.heatPerFissionMeV() / CoreLoading.EMPTY_CORE_HEAT_PER_FISSION_MEV;
            Check.exactly(expected, loading.heatPerFissionScaleFactor(),
                    "heat per fission scale factor for " + type.name());
            Check.note("%-10s %6.1f MeV/fission -> thermal power x %.6f",
                    type.name(), type.heatPerFissionMeV(), loading.heatPerFissionScaleFactor());
        }

        Check.exactly(1.0, uniformCore(FuelType.LEU).heatPerFissionScaleFactor(),
                "LEU is the reference fuel and must scale by exactly one");
        Check.exactly(1.0, uniformCore(FuelType.HEU).heatPerFissionScaleFactor(),
                "HEU is the same isotope and must scale by exactly one");

        // An empty lattice has no fuel to average, so it falls back to the
        // reference figure and also lands on exactly 1 — which matters because a
        // player mid-refuel with the head off must not see the plant's rating
        // move underneath them.
        CoreLoading empty = new CoreLoading(5);
        Check.exactly(1.0, empty.heatPerFissionScaleFactor(),
                "an empty core scales by exactly one");
    }

    /**
     * The default core is untouched, in the strongest sense available: the scale
     * factor is exactly one and the total power getter is bit-for-bit the sum of
     * the two components it always was.
     */
    public static void test02_theDefaultCoreIsBitIdentical() {
        ReactorCore core = new ReactorCore(new CoreConfig());
        core.initialiseAtTotalPowerFraction(1.0);

        Check.exactly(1.0, core.getHeatPerFissionScaleFactor(),
                "the shipped LEU core's heat per fission scale factor");
        Check.exactly(core.getKinetics().getNeutronPowerFraction(), core.getNeutronPowerFraction(),
                "fission power fraction on the reference fuel is the raw kinetics term");
        Check.exactly(core.getDecayHeat().getFractionOfRated(), core.getDecayHeatFraction(),
                "decay heat fraction on the reference fuel is the raw inventory term");
        Check.exactly(core.getNeutronPowerFraction() + core.getDecayHeatFraction(),
                core.getTotalPowerFractionOfRated(),
                "total power is still exactly the sum of its two published parts");
    }

    /**
     * <b>The wiring, end to end.</b> Two cores of neutronically identical fuel,
     * one releasing twice the energy per fission, both staged at rated
     * <i>thermal</i> power. The high-energy core must reach the same megawatts at
     * half the fission rate, and every thermal consumer must agree with it.
     *
     * <p>Each assertion below fails loudly if the multiplication is missing from
     * one particular consumer, which is the point of listing them separately
     * rather than checking one aggregate: an unwired vessel would be boiling
     * 970 kg/s instead of 1941, an unwired fuel node would sit 200 degC cooler,
     * and an unwired burnup would accrue half the exposure. None of those is
     * within any tolerance of the right answer.
     */
    public static void test03_aHigherEnergyFuelReachesRatedPowerAtHalfTheFissionRate() {
        ReactorCore reference = ratedCore(FuelType.LEU);
        ReactorCore doubled = ratedCore(DOUBLE_ENERGY_LEU);

        Check.exactly(1.0, reference.getHeatPerFissionScaleFactor(), "reference scale factor");
        Check.exactly(2.0, doubled.getHeatPerFissionScaleFactor(), "double-energy scale factor");

        // One tick each, so the vessel and the fuel nodes publish a figure that
        // belongs to the staged operating point rather than to a drift away from it.
        reference.step();
        doubled.step();

        Check.note("reference: %.1f MWth, fission rate %.4f, %.0f kg/s steam, fuel %.1f degC, "
                        + "APRM %.2f%%",
                reference.getThermalPowerMW(), reference.getKinetics().getNeutronPowerFraction(),
                reference.getSteamGenerationKgPerS(), reference.getFuelTemperatureC(),
                reference.getAveragePowerRangePercent(0));
        Check.note("double energy: %.1f MWth, fission rate %.4f, %.0f kg/s steam, fuel %.1f degC, "
                        + "APRM %.2f%%",
                doubled.getThermalPowerMW(), doubled.getKinetics().getNeutronPowerFraction(),
                doubled.getSteamGenerationKgPerS(), doubled.getFuelTemperatureC(),
                doubled.getAveragePowerRangePercent(0));

        // Thermal power: the same, because that is what both were asked for.
        Check.relative(1.0, reference.getTotalPowerFractionOfRated(), 0.02,
                "reference core staged at rated thermal power");
        Check.relative(1.0, doubled.getTotalPowerFractionOfRated(), 0.02,
                "double-energy core staged at rated thermal power");
        Check.relative(reference.getThermalPowerMW(), doubled.getThermalPowerMW(), 0.02,
                "two cores staged at the same thermal power must make the same megawatts");

        // Fission rate: half, because each fission is worth twice as much.
        Check.relative(0.5 * reference.getKinetics().getNeutronPowerFraction(),
                doubled.getKinetics().getNeutronPowerFraction(), 0.03,
                "the fission rate that produces rated power on double-energy fuel");

        // The vessel. An unwired scale factor halves this.
        Check.relative(reference.getSteamGenerationKgPerS(), doubled.getSteamGenerationKgPerS(), 0.02,
                "steam the vessel boils at rated thermal power");

        // The fuel and clad nodes, and the void that follows from them.
        Check.relative(reference.getFuelTemperatureC(), doubled.getFuelTemperatureC(), 0.02,
                "fuel temperature at rated thermal power");
        Check.relative(reference.getVoidFraction(), doubled.getVoidFraction(), 0.05,
                "core average void at rated thermal power");
    }

    /**
     * The instruments are on the other side of the line, and stay there.
     *
     * <p>A fission chamber measures flux. The high-energy core above is making
     * rated megawatts at half the fission rate, so its APRMs must read about half
     * scale and be <i>right</i> to — an APRM is calibrated against a heat balance
     * by a gain the operator sets, and refuelling to a fuel with a different
     * energy release is exactly the occasion for that calibration to stop being
     * true. Correcting it here would delete the discrepancy along with the reason
     * the gain adjustment exists.
     */
    public static void test04_theNeutronInstrumentsGoOnMeasuringFluxNotHeat() {
        ReactorCore reference = ratedCore(FuelType.LEU);
        ReactorCore doubled = ratedCore(DOUBLE_ENERGY_LEU);
        reference.step();
        doubled.step();

        double gamma = doubled.getAveragePowerRangeMonitor(0).getGammaBackgroundPercent();
        double referenceFlux = reference.getAveragePowerRangePercent(0) - gamma;
        double doubledFlux = doubled.getAveragePowerRangePercent(0) - gamma;

        Check.note("APRM 1 above its gamma background: %.2f%% on the reference core, %.2f%% on a "
                        + "core making the same megawatts from half the fissions",
                referenceFlux, doubledFlux);
        Check.relative(0.5 * referenceFlux, doubledFlux, 0.03,
                "an APRM reads flux, so it reads half on a core making rated power at half rate");

        // And the discrepancy is explainable rather than hidden: the scale factor
        // is published, so a control program that finds its heat balance and its
        // chambers disagreeing by 2:1 can ask the plant what is loaded and close
        // the books. Indicated flux above background, times the scale factor,
        // plus decay heat, is the thermal power — which is the heat balance a
        // player would do by hand to set the APRM gain.
        double accountedPercent =
                doubledFlux * doubled.getHeatPerFissionScaleFactor()
                        + 100.0 * doubled.getDecayHeatFraction();
        Check.relative(100.0 * doubled.getTotalPowerFractionOfRated(), accountedPercent, 0.01,
                "indicated flux times the published scale factor accounts for the megawatts");
    }

    /**
     * Burnup is an energy, so it follows the scaled power.
     *
     * <p>MWd per tonne of heavy metal is megawatt-days: two cores at the same
     * thermal power accumulate the same exposure whatever their fission rates,
     * and the depletion coefficient every {@link FuelType} quotes is per MWd/t.
     * Left unwired, the high-energy core would deplete at half the rate while
     * making the same power, and its cycle would silently double.
     */
    public static void test05_burnupFollowsEnergyAndNotFissionRate() {
        ReactorCore core = ratedCore(DOUBLE_ENERGY_LEU);
        double tonnes = core.getCoreLoading().totalHeavyMetalTonnes();
        double tickSeconds = core.getConfig().tickSeconds;

        // Integrate the megawatts the core reports against the exposure it books.
        // Comparing two cores to each other would only have measured how far each
        // of them drifted over the run — this plant has no pressure or level
        // control unless a player writes one — whereas an energy balance against
        // the core's own published power is true whatever it does.
        double megawattSeconds = 0.0;
        for (int i = 0; i < 20 * 60; i++) { // 60 s
            core.step();
            megawattSeconds += core.getThermalPowerMW() * tickSeconds;
        }
        double expected = megawattSeconds / 86_400.0 / tonnes;

        Check.note("60 s on double-energy fuel: %.4f MWd/t booked against %.4f MWd/t of reported "
                        + "energy over %.1f tonnes; power ended at %.1f%% of rated on a fission "
                        + "rate of %.4f",
                core.getAverageBurnupMwdPerTonne(), expected, tonnes,
                100.0 * core.getTotalPowerFractionOfRated(),
                core.getKinetics().getNeutronPowerFraction());

        Check.greaterThan(0.0, core.getAverageBurnupMwdPerTonne(),
                "a core at power for a minute has accrued exposure");
        Check.relative(expected, core.getAverageBurnupMwdPerTonne(), 0.05,
                "exposure is megawatt-days, so it must match the megawatts actually reported");
        // The unwired answer is exactly half of this, which the tolerance above
        // could never absorb — stated separately so the failure says which of the
        // two things went wrong.
        Check.greaterThan(0.7 * expected, core.getAverageBurnupMwdPerTonne(),
                "burnup booked at the fission rate rather than the thermal power would be half");
    }

    /**
     * A high-energy core survives a save and a reload with its rating intact.
     *
     * <p>{@code ReactorState} has no component for the scale factor — adding one
     * would change a canonical record constructor the mod's NBT reader is compiled
     * against — so {@code fromState} recomputes it from the loaded fuel. For a
     * core of one fuel that recomputation is exact, because
     * {@code CoreLoading.weightedAverage} short-circuits a property identical in
     * every assembly and never consults the flux weights at all. This is the test
     * that says so, because the alternative failure is silent: a restored core
     * that forgot its fuel would come back making half the megawatts at the same
     * flux and the panel would show a plant that had lost half its power on a
     * chunk reload.
     */
    public static void test06_theScaleFactorSurvivesASaveAndReload() {
        ReactorCore original = ratedCore(DOUBLE_ENERGY_LEU);
        original.step();

        ReactorCore restored = new ReactorCore(new CoreConfig(),
                ReactorCore.defaultCoreLoading(new CoreConfig(), DOUBLE_ENERGY_LEU));
        restored.setBurnupEnabled(false);
        restored.fromState(original.toState());

        Check.exactly(2.0, restored.getHeatPerFissionScaleFactor(),
                "a restored core must come back on the fuel it was suspended on");
        Check.exactly(original.getThermalPowerMW(), restored.getThermalPowerMW(),
                "thermal power across a round trip on non-reference fuel");
        Check.note("round trip on double-energy fuel: %.3f MWth before, %.3f after",
                original.getThermalPowerMW(), restored.getThermalPowerMW());
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static CoreLoading uniformCore(FuelType type) {
        CoreLoading loading = new CoreLoading(5);
        for (int i = 0; i < loading.positionCount(); i++) {
            loading.load(i, new FuelAssembly(type));
        }
        return loading;
    }

    private static ReactorCore ratedCore(FuelType type) {
        CoreConfig config = new CoreConfig();
        ReactorCore core = new ReactorCore(config, ReactorCore.defaultCoreLoading(config, type));
        core.initialiseAtTotalPowerFraction(1.0);
        return core;
    }
}
