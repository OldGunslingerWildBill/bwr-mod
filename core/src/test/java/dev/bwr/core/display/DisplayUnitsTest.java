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

    /** Pressure and power are never converted, in any mode, so the numbers the screens print do not move. */
    public static void test09_pressureAndPowerNumbersNeverChange() {
        for (Mode mode : Mode.values()) {
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

    private static void expectLabel(Mode mode, Quantity quantity, String expected) {
        String actual = DisplayUnits.unit(mode, quantity);
        Check.isTrue(expected.equals(actual), mode + " " + quantity + " label should be '" + expected + "', got '" + actual + "'");
    }
}
