package dev.bwr.core.display;

import dev.bwr.core.Check;
import dev.bwr.core.display.DisplayUnits.Mode;
import dev.bwr.core.display.DisplayUnits.Quantity;

/**
 * The display-only unit conversions, against the exact factors: F = C x 9/5 + 32, 1 lb = 0.45359237 kg,
 * 1 ft = 0.3048 m, 1 US gal = 3.785411784 L. METRIC must be the identity for every quantity, because it is the
 * default and is exactly what the screens have always shown.
 */
public final class DisplayUnitsTest {

    private DisplayUnitsTest() {
    }

    /** Temperature: Celsius to Fahrenheit by the exact formula; METRIC unchanged. */
    public static void test01_temperatureConversionIsExact() {
        Check.absolute(32.0, DisplayUnits.convert(Mode.IMPERIAL, Quantity.TEMPERATURE, 0.0), 1e-12, "0 C in F");
        Check.absolute(212.0, DisplayUnits.convert(Mode.PLANT, Quantity.TEMPERATURE, 100.0), 1e-12, "100 C in F");
        Check.absolute(549.32, DisplayUnits.convert(Mode.IMPERIAL, Quantity.TEMPERATURE, 287.4), 1e-9, "287.4 C in F");
        Check.absolute(-40.0, DisplayUnits.convert(Mode.IMPERIAL, Quantity.TEMPERATURE, -40.0), 1e-12, "-40 is -40 both ways");
        Check.absolute(287.4, DisplayUnits.convert(Mode.METRIC, Quantity.TEMPERATURE, 287.4), 0.0, "METRIC keeps C");
    }

    /** Mass flow: kg/s to lb/s, and to Mlb/hr for the plant. */
    public static void test02_massFlowConversionIsExact() {
        double lbPerSecond = 1.0 / 0.45359237;
        Check.absolute(lbPerSecond, DisplayUnits.convert(Mode.IMPERIAL, Quantity.MASS_FLOW, 1.0), 1e-9, "1 kg/s in lb/s");
        // 1 kg/s = 3600 kg/h = 3600 / 0.45359237 lb/h, and one million of those is Mlb/hr.
        double mlbPerHour = 3600.0 / 0.45359237 / 1.0e6;
        Check.absolute(mlbPerHour, DisplayUnits.convert(Mode.PLANT, Quantity.MASS_FLOW, 1.0), 1e-12, "1 kg/s in Mlb/hr");
        Check.absolute(1000.0, DisplayUnits.convert(Mode.METRIC, Quantity.MASS_FLOW, 1000.0), 0.0, "METRIC keeps kg/s");
    }

    /** Level: the mod's inches to feet (imperial) and to inches (plant, METRIC). */
    public static void test03_levelConversionIsExact() {
        Check.absolute(1.0, DisplayUnits.convert(Mode.IMPERIAL, Quantity.LEVEL, 12.0), 1e-12, "12 in in ft");
        Check.absolute(12.0, DisplayUnits.convert(Mode.PLANT, Quantity.LEVEL, 12.0), 0.0, "PLANT keeps inches");
        Check.absolute(12.0, DisplayUnits.convert(Mode.METRIC, Quantity.LEVEL, 12.0), 0.0, "METRIC keeps inches");
    }

    /** Length: metres to feet (imperial) and to inches (plant); METRIC unchanged. */
    public static void test04_lengthConversionIsExact() {
        Check.absolute(1.0 / 0.3048, DisplayUnits.convert(Mode.IMPERIAL, Quantity.LENGTH, 1.0), 1e-9, "1 m in ft");
        Check.absolute(1.0 / 0.0254, DisplayUnits.convert(Mode.PLANT, Quantity.LENGTH, 1.0), 1e-9, "1 m in inches");
        Check.absolute(3.5, DisplayUnits.convert(Mode.METRIC, Quantity.LENGTH, 3.5), 0.0, "METRIC keeps m");
    }

    /** Volume: cubic metres to US gallons (imperial and plant); METRIC unchanged. */
    public static void test05_volumeConversionIsExact() {
        Check.absolute(1.0 / 0.003785411784, DisplayUnits.convert(Mode.IMPERIAL, Quantity.VOLUME, 1.0), 1e-9, "1 m3 in gal");
        Check.absolute(1.0 / 0.003785411784, DisplayUnits.convert(Mode.PLANT, Quantity.VOLUME, 1.0), 1e-9, "1 m3 in gal (plant)");
        Check.absolute(2.5, DisplayUnits.convert(Mode.METRIC, Quantity.VOLUME, 2.5), 0.0, "METRIC keeps m3");
    }

    /** Mass: kilograms to pounds (imperial and plant); METRIC unchanged. */
    public static void test06_massConversionIsExact() {
        Check.absolute(1.0 / 0.45359237, DisplayUnits.convert(Mode.IMPERIAL, Quantity.MASS, 1.0), 1e-9, "1 kg in lb");
        Check.absolute(1.0 / 0.45359237, DisplayUnits.convert(Mode.PLANT, Quantity.MASS, 1.0), 1e-9, "1 kg in lb (plant)");
        Check.absolute(7.0, DisplayUnits.convert(Mode.METRIC, Quantity.MASS, 7.0), 0.0, "METRIC keeps kg");
    }

    /** Water flow: kg/s over density gives cubic metres per second, then US gallons per minute. */
    public static void test07_waterFlowIsVolumeFlowInGallonsPerMinute() {
        // 1000 kg/s of water at 1000 kg/m3 is 1 m3/s = 60 m3/min = 60 / 3.785411784e-3 gal/min.
        double gpm = 60.0 / 0.003785411784;
        Check.absolute(gpm, DisplayUnits.waterFlow(Mode.IMPERIAL, 1000.0, 1000.0), 1e-6, "1 m3/s in gpm");
        Check.absolute(gpm, DisplayUnits.waterFlow(Mode.PLANT, 1000.0, 1000.0), 1e-6, "1 m3/s in gpm (plant)");
        // Denser water at the same mass flow is a smaller volume flow.
        Check.absolute(gpm / 2.0, DisplayUnits.waterFlow(Mode.IMPERIAL, 1000.0, 2000.0), 1e-6, "density halves the volume");
        Check.absolute(1000.0, DisplayUnits.waterFlow(Mode.METRIC, 1000.0, 740.0), 0.0, "METRIC keeps kg/s");
    }

    /** The water flow needs a density, so the plain convert refuses it rather than guessing. */
    public static void test08_waterFlowRefusesPlainConvert() {
        boolean refused = false;
        try {
            DisplayUnits.convert(Mode.IMPERIAL, Quantity.WATER_FLOW, 100.0);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        Check.isTrue(refused, "convert(WATER_FLOW) must refuse: it needs a density");
    }

    /**
     * Pressure and power keep their numbers in the non-SI modes, so the numbers the screens print do not move. (SI
     * pressure is converted; it has its own tests below. This loop used to cover SI too; it was narrowed when SI came
     * in, because SI pressure is now converted by design.)
     */
    public static void test09_pressureAndPowerNumbersNeverChange() {
        for (Mode mode : new Mode[]{Mode.METRIC, Mode.IMPERIAL, Mode.PLANT}) {
            for (Quantity q : new Quantity[]{Quantity.PRESSURE_GAUGE, Quantity.PRESSURE_ABS, Quantity.PRESSURE_DIFF,
                    Quantity.POWER_THERMAL, Quantity.POWER_ELECTRIC}) {
                Check.absolute(1250.0, DisplayUnits.convert(mode, q, 1250.0), 0.0, mode + " " + q + " keeps its number");
            }
        }
    }

    /** The labels: METRIC is today's text; IMPERIAL and PLANT differ where the request says they do. */
    public static void test10_labelsPerMode() {
        expectLabel(Mode.METRIC, Quantity.TEMPERATURE, "C");
        expectLabel(Mode.IMPERIAL, Quantity.TEMPERATURE, "F");
        expectLabel(Mode.PLANT, Quantity.TEMPERATURE, "F");
        expectLabel(Mode.METRIC, Quantity.PRESSURE_GAUGE, "psig");
        expectLabel(Mode.IMPERIAL, Quantity.PRESSURE_GAUGE, "psig");
        expectLabel(Mode.PLANT, Quantity.PRESSURE_GAUGE, "psig");
        expectLabel(Mode.METRIC, Quantity.MASS_FLOW, "kg/s");
        expectLabel(Mode.IMPERIAL, Quantity.MASS_FLOW, "lb/s");
        expectLabel(Mode.PLANT, Quantity.MASS_FLOW, "Mlb/hr");
        expectLabel(Mode.METRIC, Quantity.WATER_FLOW, "kg/s");
        expectLabel(Mode.IMPERIAL, Quantity.WATER_FLOW, "gpm");
        expectLabel(Mode.PLANT, Quantity.WATER_FLOW, "gpm");
        expectLabel(Mode.METRIC, Quantity.LEVEL, "in");
        expectLabel(Mode.IMPERIAL, Quantity.LEVEL, "ft");
        expectLabel(Mode.PLANT, Quantity.LEVEL, "in");
        expectLabel(Mode.METRIC, Quantity.LENGTH, "m");
        expectLabel(Mode.IMPERIAL, Quantity.LENGTH, "ft");
        expectLabel(Mode.PLANT, Quantity.LENGTH, "in");
        expectLabel(Mode.METRIC, Quantity.VOLUME, "m3");
        expectLabel(Mode.IMPERIAL, Quantity.VOLUME, "gal");
        expectLabel(Mode.PLANT, Quantity.VOLUME, "gal");
        expectLabel(Mode.METRIC, Quantity.MASS, "kg");
        expectLabel(Mode.IMPERIAL, Quantity.MASS, "lb");
        expectLabel(Mode.PLANT, Quantity.MASS, "lb");
        expectLabel(Mode.METRIC, Quantity.POWER_THERMAL, "MWth");
        expectLabel(Mode.PLANT, Quantity.POWER_THERMAL, "MWt");
        expectLabel(Mode.METRIC, Quantity.POWER_ELECTRIC, "MW");
        expectLabel(Mode.PLANT, Quantity.POWER_ELECTRIC, "MWe");
    }

    /** The one-line text used in tooltips: a converted number with its label. */
    public static void test11_formatJoinsNumberAndLabel() {
        String text = DisplayUnits.format(Mode.PLANT, Quantity.TEMPERATURE, 1000.0, 0);
        Check.isTrue("1832 F".equals(text), "1000 C in plant mode reads '1832 F', got '" + text + "'");
        String metric = DisplayUnits.format(Mode.METRIC, Quantity.TEMPERATURE, 1000.0, 0);
        Check.isTrue("1000 C".equals(metric), "METRIC reads '1000 C', got '" + metric + "'");
    }

    /** SI temperature: degrees Celsius with the degree sign, and no conversion. */
    public static void test12_siTemperatureIsCelsiusWithDegreeSign() {
        Check.absolute(287.4, DisplayUnits.convert(Mode.SI, Quantity.TEMPERATURE, 287.4), 0.0, "SI keeps C");
        expectLabel(Mode.SI, Quantity.TEMPERATURE, "°C");
    }

    /** SI pressure: psi to kPa by the exact factor 6.894757, with the gauge and absolute labels. */
    public static void test13_siPressureIsKpaByExactFactor() {
        Check.absolute(6.894757, DisplayUnits.pressureKpa(1.0), 1e-12, "1 psi in kPa");
        Check.absolute(689.4757, DisplayUnits.pressureSi(Quantity.PRESSURE_GAUGE, 100.0), 1e-9, "100 psi in kPa");
        Check.absolute(101.3, DisplayUnits.pressureSi(Quantity.PRESSURE_ABS, 14.696), 0.05, "atmosphere in kPa(a)");
        expectSiUnit(Quantity.PRESSURE_GAUGE, 100.0, "kPa(g)");
        expectSiUnit(Quantity.PRESSURE_ABS, 14.696, "kPa(a)");
        expectSiUnit(Quantity.PRESSURE_VESSEL, 100.0, "kPa(g)");
        expectSiUnit(Quantity.PRESSURE_DIFF, 100.0, "kPa");
    }

    /** SI pressure switches to MPa just above 1,000 kPa, and not before. */
    public static void test14_siPressureSwitchesToMpaAboveOneThousandKpa() {
        // 145 psi is 999.74 kPa: still kPa. 146 psi is 1006.63 kPa: MPa.
        expectSiUnit(Quantity.PRESSURE_GAUGE, 145.0, "kPa(g)");
        Check.absolute(145.0 * 6.894757, DisplayUnits.pressureSi(Quantity.PRESSURE_GAUGE, 145.0), 1e-9, "145 psi in kPa");
        expectSiUnit(Quantity.PRESSURE_GAUGE, 146.0, "MPa(g)");
        Check.absolute(146.0 * 6.894757 / 1000.0, DisplayUnits.pressureSi(Quantity.PRESSURE_GAUGE, 146.0), 1e-12,
                "146 psi in MPa");
        // The rated dome pressure, 1025 psig, is 7.06662593 MPa(g).
        Check.absolute(1025.0 * 6.894757 / 1000.0, DisplayUnits.pressureSi(Quantity.PRESSURE_GAUGE, 1025.0), 1e-12,
                "1025 psig in MPa");
    }

    /** SI water level: inches to metres by the exact factor 0.0254; METRIC keeps inches. */
    public static void test15_siLevelIsMetresByExactFactor() {
        Check.absolute(0.0254, DisplayUnits.convert(Mode.SI, Quantity.LEVEL, 1.0), 1e-12, "1 inch in m");
        Check.absolute(0.3048, DisplayUnits.convert(Mode.SI, Quantity.LEVEL, 12.0), 1e-12, "12 inches in m (one foot)");
        Check.absolute(12.0, DisplayUnits.convert(Mode.METRIC, Quantity.LEVEL, 12.0), 0.0, "METRIC keeps inches");
        expectLabel(Mode.SI, Quantity.LEVEL, "m");
    }

    /** SI water flow: cubic metres per hour from kg/s over density. */
    public static void test16_siWaterFlowIsCubicMetresPerHour() {
        // 1000 kg/s of water at 1000 kg/m3 is 1 m3/s = 3600 m3/h; twice the density halves it.
        Check.absolute(3600.0, DisplayUnits.waterFlow(Mode.SI, 1000.0, 1000.0), 1e-9, "1 m3/s in m3/h");
        Check.absolute(1800.0, DisplayUnits.waterFlow(Mode.SI, 1000.0, 2000.0), 1e-9, "density doubles, flow halves");
        Check.absolute(1000.0, DisplayUnits.waterFlow(Mode.METRIC, 1000.0, 740.0), 0.0, "METRIC keeps kg/s");
        expectLabel(Mode.SI, Quantity.WATER_FLOW, "m³/h");
    }

    /** SI mass flow, length, volume, mass and power are the mod's own SI units, unchanged. */
    public static void test17_siSiUnitsAreUnchanged() {
        Check.absolute(1000.0, DisplayUnits.convert(Mode.SI, Quantity.MASS_FLOW, 1000.0), 0.0, "kg/s stays kg/s");
        Check.absolute(3.5, DisplayUnits.convert(Mode.SI, Quantity.LENGTH, 3.5), 0.0, "m stays m");
        Check.absolute(2.5, DisplayUnits.convert(Mode.SI, Quantity.VOLUME, 2.5), 0.0, "m3 stays m3");
        Check.absolute(7.0, DisplayUnits.convert(Mode.SI, Quantity.MASS, 7.0), 0.0, "kg stays kg");
        Check.absolute(1250.0, DisplayUnits.convert(Mode.SI, Quantity.POWER_THERMAL, 1250.0), 0.0, "MW stays MW");
        expectLabel(Mode.SI, Quantity.MASS_FLOW, "kg/s");
        expectLabel(Mode.SI, Quantity.LENGTH, "m");
        expectLabel(Mode.SI, Quantity.VOLUME, "m³");
        expectLabel(Mode.SI, Quantity.MASS, "kg");
        expectLabel(Mode.SI, Quantity.POWER_THERMAL, "MWth");
        expectLabel(Mode.SI, Quantity.POWER_ELECTRIC, "MW");
    }

    /** SI rate of pressure change: psi per second to kPa per second, with no MPa switch. */
    public static void test18_siPressureRateIsKpaPerSecond() {
        Check.absolute(6.894757, DisplayUnits.convert(Mode.SI, Quantity.PRESSURE_RATE, 1.0), 1e-12, "1 psi/s in kPa/s");
        expectLabel(Mode.SI, Quantity.PRESSURE_RATE, "kPa/s");
        Check.absolute(1.0, DisplayUnits.convert(Mode.METRIC, Quantity.PRESSURE_RATE, 1.0), 0.0, "METRIC keeps psi/s");
    }

    /** SI pressure has no single label, so the plain label and conversion calls refuse it. */
    public static void test19_siPressureNeedsItsValueAwareCalls() {
        boolean convertRefused = false;
        boolean unitRefused = false;
        try {
            DisplayUnits.convert(Mode.SI, Quantity.PRESSURE_GAUGE, 100.0);
        } catch (IllegalArgumentException expected) {
            convertRefused = true;
        }
        try {
            DisplayUnits.unit(Mode.SI, Quantity.PRESSURE_GAUGE);
        } catch (IllegalArgumentException expected) {
            unitRefused = true;
        }
        Check.isTrue(convertRefused, "convert(SI, PRESSURE_GAUGE) must refuse: use pressureSi()");
        Check.isTrue(unitRefused, "unit(SI, PRESSURE_GAUGE) must refuse: use pressureSiUnit()");
    }

    /** The pump's vessel pressure row: "psi" in METRIC as always, psig in the other modes. */
    public static void test20_vesselPressureLabelPerMode() {
        expectLabel(Mode.METRIC, Quantity.PRESSURE_VESSEL, "psi");
        expectLabel(Mode.IMPERIAL, Quantity.PRESSURE_VESSEL, "psig");
        expectLabel(Mode.PLANT, Quantity.PRESSURE_VESSEL, "psig");
    }

    private static void expectSiUnit(Quantity quantity, double psi, String expected) {
        String actual = DisplayUnits.pressureSiUnit(quantity, psi);
        Check.isTrue(expected.equals(actual), "SI " + quantity + " at " + psi + " psi should be '" + expected
                + "', got '" + actual + "'");
    }

    private static void expectLabel(Mode mode, Quantity quantity, String expected) {
        String actual = DisplayUnits.unit(mode, quantity);
        Check.isTrue(expected.equals(actual), mode + " " + quantity + " label should be '" + expected + "', got '" + actual + "'");
    }
}
