package dev.bwr.core.isotope;

import dev.bwr.core.Check;
import dev.bwr.core.grid.EnergyUnits;

/** RTG arithmetic: capsule power from mass and specific power, half-life decay, the efficiency and the FE figure. */
public final class RadioisotopePowerTest {

    private RadioisotopePowerTest() {
    }

    /** 250 g of Sr-90 with Y-90 at 0.92 W/g is 230 W thermal: the Beta-M's source (0.255 kg, 230 W). */
    public static void test01_capsulePowerIsMassTimesSpecificPower() {
        double w = RadioisotopePower.thermalWatts(250.0, 0.92, 28.9, 0.0);
        Check.exactly(230.0, w, "250 g of Sr-90 at 0.92 W/g, fresh");
        Check.exactly(0.0, RadioisotopePower.thermalWatts(0.0, 0.92, 28.9, 0.0), "no mass, no power");
        Check.exactly(0.0, RadioisotopePower.thermalWatts(250.0, 0.0, 28.9, 0.0), "no specific power, no power");
        Check.exactly(6 * 230.0, 6 * RadioisotopePower.thermalWatts(250.0, 0.92, 28.9, 0.0), "six capsules add");
        Check.note("250 g Sr-90 = %.1f W thermal", w);
    }

    /** After one half-life half remains; after two, a quarter; a session is a rounding error. */
    public static void test02_decayFollowsTheHalfLife() {
        double year = RadioisotopePower.SECONDS_PER_YEAR;
        Check.relative(0.5, RadioisotopePower.decayFactor(28.9, 28.9 * year), 1e-12, "one half-life");
        Check.relative(0.25, RadioisotopePower.decayFactor(28.9, 57.8 * year), 1e-12, "two half-lives");
        Check.relative(0.5, RadioisotopePower.decayFactor(432.6, 432.6 * year), 1e-12, "americium after its half-life");
        Check.exactly(1.0, RadioisotopePower.decayFactor(28.9, 0.0), "no time, no decay");
        Check.exactly(1.0, RadioisotopePower.decayFactor(0.0, 1e9), "an unset half-life never decays");
        double afterHundredHours = RadioisotopePower.decayFactor(28.9, 100 * 3600.0);
        Check.isTrue(afterHundredHours > 0.9997 && afterHundredHours < 1.0, "100 hours costs under 0.03 %%: %.6f", afterHundredHours);
        Check.relative(115.0, RadioisotopePower.thermalWatts(250.0, 0.92, 28.9, 28.9 * year), 1e-12, "thermal power halves");
        Check.note("Sr-90 after 100 h: %.6f", afterHundredHours);
    }

    /** 5 % of 230 W thermal is 11.5 W, which is 0.858 FE/t on the mod's one conversion, 13.4 W per FE/t. */
    public static void test03_efficiencyAndTheFeFigure() {
        double electric = RadioisotopePower.electricWatts(230.0, RadioisotopePower.THERMOCOUPLE_EFFICIENCY);
        Check.relative(11.5, electric, 1e-12, "electric watts");
        double fe = RadioisotopePower.fePerTick(electric, 1.0);
        Check.relative(11.5 / 13.4, fe, 1e-12, "FE per tick");
        Check.relative(2.0 * fe, RadioisotopePower.fePerTick(electric, 2.0), 1e-12, "the output multiplier scales and nothing else does");
        Check.exactly(0.0, RadioisotopePower.fePerTick(0.0, 1.0), "no power, no FE");
        // The existing conversion: 75 kW is 5,598 FE/t (ceil), as the turbine auxiliaries use it.
        Check.exactly(5598, (int) Math.ceil(75_000.0 / EnergyUnits.WATTS_PER_FE_PER_TICK), "75 kW in FE/t");
        Check.relative(75_000.0 / 13.4, RadioisotopePower.fePerTick(75_000.0, 1.0), 1e-12, "the same conversion");
        Check.note("Sr-90 capsule: %.3f W electric = %.4f FE/t", electric, fe);
    }

    /** Photon power and the dose behind it: caesium-137's 0.29 W/g reads about 0.26 Sv/h per gram at 1 m. */
    public static void test04_gammaAndDose() {
        double w = RadioisotopePower.gammaWatts(1.0, 0.29, 1.0);
        double msv = RadioisotopePower.doseRateMsvPerH(w, 1.0, 1.0);
        Check.isTrue(msv > 200.0 && msv < 300.0, "1 g of Cs-137 reads 0.2 to 0.3 Sv/h at 1 m: %.1f mSv/h", msv);
        Check.exactly(0.0, RadioisotopePower.gammaWatts(100.0, 0.29, 0.0), "fully shielded");
        Check.relative(0.5 * w, RadioisotopePower.gammaWatts(1.0, 0.29, 0.5), 1e-12, "transmission scales");
        Check.note("Cs-137: %.1f mSv/h per gram at 1 m", msv);
    }
}
