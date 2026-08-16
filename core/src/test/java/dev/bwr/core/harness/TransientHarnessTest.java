package dev.bwr.core.harness;

import dev.bwr.core.Check;
import dev.bwr.core.PhysicalConstants;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The standalone transient harness has to actually run. {@code SPEC.md} section
 * 0 and the README both make the standalone phase a hard requirement — a
 * believable startup, load change and scram plotted in plain Java before a line
 * of NeoForge — so a scenario that throws, stalls or emits NaN is a broken
 * deliverable, not a cosmetic problem.
 *
 * <p>These tests run each scenario into a buffer and check the CSV: that rows
 * come out, that no field is NaN or infinite, and that the emergent behaviour
 * the scenario exists to demonstrate is present in the numbers.
 */
public final class TransientHarnessTest {

    private TransientHarnessTest() {
    }

    /** Runs one scenario into a buffer and returns the CSV it wrote. */
    private static String run(java.util.function.Consumer<PrintStream> scenario) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            scenario.accept(out);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** Data rows only: comments, blanks and the header line dropped. */
    private static List<String[]> dataRows(String csv) {
        List<String[]> rows = new ArrayList<>();
        boolean headerSeen = false;
        for (String line : csv.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (!headerSeen) {
                headerSeen = true;
                continue;
            }
            rows.add(trimmed.split(","));
        }
        return rows;
    }

    private static void assertNoNonFiniteFields(String csv, String scenario) {
        for (String[] row : dataRows(csv)) {
            for (String field : row) {
                if (field.contains("NaN") || field.contains("Infinity")) {
                    Check.fail("%s scenario emitted a non-finite field: %s", scenario, field);
                }
            }
        }
    }

    private static double column(String[] row, int index) {
        return Double.parseDouble(row[index]);
    }

    private static int columnIndex(String csv, String name) {
        for (String line : csv.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] header = trimmed.split(",");
            for (int i = 0; i < header.length; i++) {
                if (header[i].equals(name)) {
                    return i;
                }
            }
            Check.fail("no column named '%s' in header: %s", name, trimmed);
        }
        Check.fail("no header row found");
        return -1;
    }

    /**
     * Approach to critical: the count rate must climb hyperbolically from a
     * finite floor and 1/M must fall monotonically toward zero. That straight
     * line is the whole startup technique, and it exists only because the point
     * kinetics carries a source term.
     */
    public static void test01_approachToCriticalProducesAFalling1OverM() {
        String csv = run(TransientHarness::approachToCritical);
        assertNoNonFiniteFields(csv, "approach");

        int countRateColumn = columnIndex(csv, "srm_cps");
        int inverseMColumn = columnIndex(csv, "inverse_m");
        int reactivityColumn = columnIndex(csv, "rho_dkk");
        List<String[]> rows = dataRows(csv);

        Check.greaterThan(4, rows.size(), "rows in the approach trace");
        double firstCountRate = column(rows.get(0), countRateColumn);
        double lastCountRate = column(rows.get(rows.size() - 1), countRateColumn);
        double firstInverseM = column(rows.get(0), inverseMColumn);
        double lastInverseM = column(rows.get(rows.size() - 1), inverseMColumn);

        Check.note("approach: %d steps, SRM %.1f -> %.1f cps, 1/M %.3f -> %.3f, rho %+.6f -> %+.6f",
                rows.size(), firstCountRate, lastCountRate, firstInverseM, lastInverseM,
                column(rows.get(0), reactivityColumn),
                column(rows.get(rows.size() - 1), reactivityColumn));

        Check.absolute(1.0, firstInverseM, 1.0e-6, "1/M at the reference point");
        Check.finiteAndPositive(firstCountRate, "count rate at the start of the approach");
        Check.greaterThan(firstCountRate, lastCountRate, "count rate at the end of the approach");
        Check.lessThan(firstInverseM, lastInverseM, "1/M must fall as the core approaches critical");
        Check.lessThan(0.5, lastInverseM, "1/M must get well down the line");

        double previousInverseM = Double.MAX_VALUE;
        for (String[] row : rows) {
            double inverseM = column(row, inverseMColumn);
            Check.finiteAndPositive(inverseM, "1/M");
            Check.lessThan(previousInverseM + 1.0e-9, inverseM, "1/M must fall monotonically");
            previousInverseM = inverseM;
        }
    }

    /**
     * Startup and power ascension: twelve decades from a shutdown core to rated,
     * with every instrument handing over to the next without a gap and the plant
     * still on its operating point when it gets there.
     */
    public static void test02_startupReachesRatedPower() {
        String csv = run(TransientHarness::startupAndPowerAscension);
        assertNoNonFiniteFields(csv, "startup");

        int timeColumn = columnIndex(csv, "t_s");
        int totalColumn = columnIndex(csv, "total_percent");
        int pressureColumn = columnIndex(csv, "pressure_psig");
        int levelColumn = columnIndex(csv, "level_in");
        int flowColumn = columnIndex(csv, "flow_fraction");
        int aprmColumn = columnIndex(csv, "aprm_percent");
        int irmColumn = columnIndex(csv, "irm_div");
        int countRateColumn = columnIndex(csv, "srm_cps");
        List<String[]> rows = dataRows(csv);
        Check.greaterThan(10, rows.size(), "rows in the startup trace");

        String[] first = rows.get(0);
        String[] last = rows.get(rows.size() - 1);
        double peakTotal = 0.0;
        double lowestPressure = Double.MAX_VALUE;
        double peakCountRate = 0.0;
        for (String[] row : rows) {
            peakTotal = Math.max(peakTotal, column(row, totalColumn));
            lowestPressure = Math.min(lowestPressure, column(row, pressureColumn));
            peakCountRate = Math.max(peakCountRate, column(row, countRateColumn));
        }

        Check.note("startup: %d rows, %.0f s, total power %.4E%% -> %.2f%% of rated (peak %.2f%%)",
                rows.size(), column(last, timeColumn), column(first, totalColumn),
                column(last, totalColumn), peakTotal);
        Check.note("at the top: %.1f psig (lowest %.1f), level %.1f in, flow %.2f, "
                        + "APRM %.1f%%, IRM %.0f divisions, SRM %.3E cps (peaked at %.3E)",
                column(last, pressureColumn), lowestPressure, column(last, levelColumn),
                column(last, flowColumn), column(last, aprmColumn), column(last, irmColumn),
                column(last, countRateColumn), peakCountRate);

        Check.lessThan(1.0e-6, column(first, totalColumn), "the startup must begin from a shutdown core");
        Check.greaterThan(99.0, column(last, totalColumn), "total power at the end of the ascension");
        Check.lessThan(110.0, column(last, totalColumn), "total power at the end of the ascension");

        // The plant has to still be on its operating point when it arrives.
        Check.absolute(1025.0, column(last, pressureColumn), 15.0, "dome pressure at rated");
        Check.greaterThan(900.0, lowestPressure, "the ascension must not depressurise the vessel");
        Check.absolute(30.0, column(last, levelColumn), 5.0, "indicated level at rated");
        Check.absolute(1.0, column(last, flowColumn), 0.02, "recirculation flow at rated");
        Check.absolute(100.0, column(last, aprmColumn), 8.0, "APRM indication at rated");

        // And the instruments must have handed over: the SRM climbed to its
        // paralyzable peak and then rolled all the way back to nothing, while the
        // APRM took over. A channel reading 00.00 at rated power is correct here.
        Check.greaterThan(1.0e10, peakCountRate, "the SRM must climb to its paralyzable peak");
        Check.lessThan(1.0, column(last, countRateColumn),
                "the SRM must have rolled over to nothing by rated power");
    }

    /** Load change on recirculation flow alone, with every rod standing still. */
    public static void test03_flowLoadChangeMovesPowerWithoutRods() {
        String csv = run(TransientHarness::flowLoadChange);
        assertNoNonFiniteFields(csv, "flow");

        int flowColumn = columnIndex(csv, "flow_actual");
        int totalColumn = columnIndex(csv, "total_percent");
        int voidColumn = columnIndex(csv, "void");
        int notchColumn = columnIndex(csv, "rods_avg_notch");
        List<String[]> rows = dataRows(csv);
        Check.greaterThan(20, rows.size(), "rows in the flow trace");

        double startingNotch = column(rows.get(0), notchColumn);
        double lowestFlow = Double.MAX_VALUE;
        double powerAtLowestFlow = 0.0;
        double voidAtLowestFlow = 0.0;
        for (String[] row : rows) {
            Check.absolute(startingNotch, column(row, notchColumn), 1.0e-9,
                    "rods must not move during a flow load change");
            double flow = column(row, flowColumn);
            if (flow < lowestFlow) {
                lowestFlow = flow;
                powerAtLowestFlow = column(row, totalColumn);
                voidAtLowestFlow = column(row, voidColumn);
            }
        }
        double startingPower = column(rows.get(0), totalColumn);
        double startingVoid = column(rows.get(0), voidColumn);
        Check.note("flow: %.2f -> %.2f of rated, power %.2f%% -> %.2f%%, void %.4f -> %.4f, "
                        + "rods held at notch %.2f",
                column(rows.get(0), flowColumn), lowestFlow, startingPower, powerAtLowestFlow,
                startingVoid, voidAtLowestFlow, startingNotch);
        Check.lessThan(0.8, lowestFlow, "the scenario must actually cut flow");
        Check.lessThan(startingPower, powerAtLowestFlow, "power must fall when flow is cut");
        Check.greaterThan(startingVoid, voidAtLowestFlow, "void must rise when flow is cut");
    }

    /** Scram: prompt drop, delayed tail, and decay heat that ignores both. */
    public static void test04_scramTraceShowsThePromptDropAndTheTail() {
        String csv = run(TransientHarness::scramFromFullPower);
        assertNoNonFiniteFields(csv, "scram");

        int scramColumn = columnIndex(csv, "scram");
        int fissionColumn = columnIndex(csv, "n_fraction");
        int decayColumn = columnIndex(csv, "decay_fraction");
        int totalColumn = columnIndex(csv, "total_percent");
        List<String[]> rows = dataRows(csv);

        double fissionBefore = -1.0;
        double fissionMinimum = Double.MAX_VALUE;
        for (String[] row : rows) {
            boolean scrammed = column(row, scramColumn) > 0.5;
            double fission = column(row, fissionColumn);
            if (!scrammed) {
                fissionBefore = fission;
            } else {
                fissionMinimum = Math.min(fissionMinimum, fission);
            }
            Check.finiteAndPositive(fission, "fission power in the scram trace");
        }
        String[] last = rows.get(rows.size() - 1);
        Check.note("scram: fission %.4f before -> %.4E at the end, decay %.4f, total %.4f%%",
                fissionBefore, column(last, fissionColumn), column(last, decayColumn),
                column(last, totalColumn));

        Check.greaterThan(0.5, fissionBefore, "fission power before the scram");
        Check.lessThan(0.05 * fissionBefore, column(last, fissionColumn),
                "fission power at the end of the scram trace");
        Check.finiteAndPositive(fissionMinimum, "the scrammed core must never reach zero power");
        Check.greaterThan(0.005, column(last, decayColumn),
                "decay heat is still there ten minutes later");
    }

    /**
     * Pressurisation: isolate the steam outlet and watch void collapse hand back
     * positive reactivity with no scram, no relief valve and nothing in the model
     * deciding to intervene.
     */
    public static void test05_pressurisationCollapsesVoidAndRaisesPower() {
        String csv = run(TransientHarness::pressurisationTransient);
        assertNoNonFiniteFields(csv, "pressurisation");

        int isolatedColumn = columnIndex(csv, "isolated");
        int pressureColumn = columnIndex(csv, "pressure_psig");
        int voidColumn = columnIndex(csv, "void");
        int voidReactivityColumn = columnIndex(csv, "rho_void");
        int fissionColumn = columnIndex(csv, "n_fraction");
        List<String[]> rows = dataRows(csv);

        double pressureAtIsolation = -1.0;
        double voidAtIsolation = -1.0;
        double voidReactivityAtIsolation = 0.0;
        double fissionAtIsolation = -1.0;
        for (String[] row : rows) {
            if (column(row, isolatedColumn) < 0.5) {
                pressureAtIsolation = column(row, pressureColumn);
                voidAtIsolation = column(row, voidColumn);
                voidReactivityAtIsolation = column(row, voidReactivityColumn);
                fissionAtIsolation = column(row, fissionColumn);
            }
        }
        String[] last = rows.get(rows.size() - 1);
        Check.note("pressurisation: %.0f -> %.0f psig, void %.4f -> %.4f, rho_void %+.5f -> %+.5f, "
                        + "fission %.4f -> %.4f",
                pressureAtIsolation, column(last, pressureColumn), voidAtIsolation,
                column(last, voidColumn), voidReactivityAtIsolation,
                column(last, voidReactivityColumn), fissionAtIsolation,
                column(last, fissionColumn));

        Check.greaterThan(pressureAtIsolation, column(last, pressureColumn),
                "pressure must rise after isolation");
        Check.lessThan(voidAtIsolation, column(last, voidColumn),
                "void must collapse as pressure rises");
        Check.greaterThan(voidReactivityAtIsolation, column(last, voidReactivityColumn),
                "collapsing void must hand back positive reactivity");
        Check.greaterThan(fissionAtIsolation, column(last, fissionColumn),
                "power must rise on the void collapse");

        // The trace must stop before the vessel model's numerical pressure
        // ceiling. Rows sitting flat against a clamp are an artefact of the
        // guard, not reactor behaviour, and they teach the reader nothing.
        Check.lessThan(4900.0, column(last, pressureColumn),
                "the trace must stop short of the vessel model's numerical ceiling");
        Check.greaterThan(PhysicalConstants.VESSEL_CODE_LIMIT_PSIG, column(last, pressureColumn),
                "the transient must still run past the ASME code limit, since nothing stops it");
    }

    /** The scenario list and {@code --list} must stay in step with the switch. */
    public static void test06_everyAdvertisedScenarioExists() {
        Check.exactly(5, TransientHarness.SCENARIOS.length, "advertised scenario count");
        for (String name : TransientHarness.SCENARIOS) {
            Check.isTrue(name != null && !name.isBlank(), "scenario name must not be blank");
        }
        Check.note("scenarios: %s", String.join(", ", TransientHarness.SCENARIOS));
    }
}
