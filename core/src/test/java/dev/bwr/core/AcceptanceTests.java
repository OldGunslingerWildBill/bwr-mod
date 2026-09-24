package dev.bwr.core;

import dev.bwr.core.pool.SuppressionPoolTest;

import dev.bwr.core.accident.DamageBookkeepingTest;
import dev.bwr.core.accident.QuenchSpikeRefloodTest;
import dev.bwr.core.accident.SevereAccidentEscalationTest;
import dev.bwr.core.boundary.BoundaryStressTest;
import dev.bwr.core.feedwater.FeedwaterPumpTest;
import dev.bwr.core.fuel.FuelTypeSpecTest;
import dev.bwr.core.fuel.HeatPerFissionTest;
import dev.bwr.core.fuel.MixedCoreTest;
import dev.bwr.core.harness.TransientHarnessTest;
import dev.bwr.core.instrument.AveragePowerRangePeriodTest;
import dev.bwr.core.instrument.SourceRangeMonitorTest;
import dev.bwr.core.kinetics.PointKineticsTest;
import dev.bwr.core.nodal.NodalFluxSolverTest;
import dev.bwr.core.thermal.SaturationTest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Runs the whole acceptance suite and exits non-zero if anything fails.
 *
 * <pre>
 *   java dev.bwr.core.AcceptanceTests             # everything
 *   java dev.bwr.core.AcceptanceTests kinetics    # classes whose name contains "kinetics"
 * </pre>
 *
 * <p>Every {@code public static void test*()} method on the listed classes is a
 * test. There is no framework: a test passes if it returns and fails if it
 * throws. Measured values recorded with {@link Check#note} are printed under
 * each test, because a physics test that reports only pass or fail hides
 * whether it passed for the right reason.
 *
 * <h2>What these tests are for</h2>
 * Each has an exact analytic answer, and each catches a specific way the model
 * could be silently wrong:
 * <ol>
 *   <li>Zero reactivity with equilibrium precursors holds power flat — catches
 *       sign errors and bad initialisation on the first step.</li>
 *   <li>A small insertion gives the prompt jump {@code beta/(beta-rho)} and then
 *       the inhour equation's dominant root.</li>
 *   <li>Exactly prompt critical ramps and stays finite.</li>
 *   <li>Super prompt critical engages the denominator guard, subdivides, and
 *       stays finite and positive.</li>
 *   <li>Scram from rated drops promptly, decays on the precursor tail, and never
 *       reaches zero.</li>
 *   <li>Subcritical with a source settles at {@code Lambda*S/|rho|} and the SRM
 *       reads a finite count rate.</li>
 *   <li>The SRM rolls over rather than pegging.</li>
 *   <li>The saturation correlation matches the steam tables.</li>
 *   <li>Persistence round-trips every field exactly.</li>
 * </ol>
 */
public final class AcceptanceTests {

    private AcceptanceTests() {
    }

    /** Every test class, in the order the runner executes them. */
    public static final Class<?>[] TEST_CLASSES = {
            FuelVarietyTest.class,
            CompactCoreLayoutTest.class,
            PowerTurbineTest.class,
            SurfaceCondenserTest.class,
            WaterSegmentTest.class,
            BoronSolutionTest.class,
            CoolingWaterUnitTest.class,
            RhrHeatExchangerTest.class,
            RecirculationSizingTest.class,
            ContinuousRodMotionTest.class,
            PumpSpeedControlTest.class,
            PointKineticsTest.class,
            SaturationTest.class,
            SourceRangeMonitorTest.class,
            AveragePowerRangePeriodTest.class,
            SubcriticalSourceTest.class,
            ColdStartTest.class,
            ReactorCoreScramTest.class,
            ReactorCoreTickTest.class,
            ReactorStateRoundTripTest.class,
            CriticalDefectRegressionTest.class,
            TransientHarnessTest.class,
            SuppressionPoolTest.class,
            BoundaryStressTest.class,
            SevereAccidentEscalationTest.class,
            QuenchSpikeRefloodTest.class,
            DamageBookkeepingTest.class,
            NodalFluxSolverTest.class,
            FuelTypeSpecTest.class,
            MixedCoreTest.class,
            HeatPerFissionTest.class,
            FeedwaterPumpTest.class,
            dev.bwr.core.eccs.PipedTurbineTest.class,
    };

    public static void main(String[] args) {
        // Run before filtering too: a targeted run must not hide an unregistered class.
        verifyRegistration();
        if (Arrays.asList(args).contains("--verify-registration")) {
            System.out.printf("Test registration verified: %d classes, %d methods%n",
                    TEST_CLASSES.length, Arrays.stream(TEST_CLASSES).mapToInt(c -> testMethods(c).size()).sum());
            return;
        }
        long startedAtNanos = System.nanoTime();
        List<String> failures = new ArrayList<>();
        int passed = 0;
        int run = 0;

        for (Class<?> testClass : TEST_CLASSES) {
            if (args.length > 0 && !matchesAny(testClass, args)) {
                continue;
            }
            System.out.println();
            System.out.println("== " + testClass.getName());

            for (Method method : testMethods(testClass)) {
                run++;
                Check.clearNotes();
                String name = testClass.getSimpleName() + "." + method.getName();
                long t0 = System.nanoTime();
                Throwable thrown = null;
                try {
                    method.invoke(null);
                } catch (InvocationTargetException e) {
                    thrown = e.getCause() == null ? e : e.getCause();
                } catch (Throwable t) {
                    thrown = t;
                }
                double millis = (System.nanoTime() - t0) / 1.0e6;

                if (thrown == null) {
                    passed++;
                    System.out.printf(Locale.ROOT, "  PASS  %-52s %8.1f ms%n", method.getName(), millis);
                } else {
                    System.out.printf(Locale.ROOT, "  FAIL  %-52s %8.1f ms%n", method.getName(), millis);
                    System.out.println("        " + describe(thrown));
                    if (!(thrown instanceof Check.Failure)) {
                        for (StackTraceElement frame : thrown.getStackTrace()) {
                            if (frame.getClassName().startsWith("dev.bwr.core")) {
                                System.out.println("        at " + frame);
                            }
                        }
                    }
                    failures.add(name + ": " + describe(thrown));
                }
                for (String note : Check.notes()) {
                    System.out.println("        . " + note);
                }
            }
        }

        double seconds = (System.nanoTime() - startedAtNanos) / 1.0e9;
        System.out.println();
        System.out.println("============================================================");
        System.out.printf(Locale.ROOT, "%d tests, %d passed, %d failed, %.1f s%n",
                run, passed, failures.size(), seconds);
        if (!failures.isEmpty()) {
            System.out.println("------------------------------------------------------------");
            for (String failure : failures) {
                System.out.println("FAILED  " + failure);
            }
        }
        System.out.println("============================================================");
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static boolean matchesAny(Class<?> testClass, String[] filters) {
        String name = testClass.getName().toLowerCase(Locale.ROOT);
        for (String filter : filters) {
            if (name.contains(filter.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<Method> testMethods(Class<?> testClass) {
        List<Method> methods = new ArrayList<>();
        for (Method method : testClass.getDeclaredMethods()) {
            if (method.getName().startsWith("test")
                    && Modifier.isStatic(method.getModifiers())
                    && Modifier.isPublic(method.getModifiers())
                    && method.getReturnType() == void.class
                    && method.getParameterCount() == 0) {
                methods.add(method);
            }
        }
        // Declared order is not specified by the JVM, so sort for a reproducible
        // transcript. Test method names carry a leading ordinal for readability.
        methods.sort(Comparator.comparing(Method::getName));
        return methods;
    }

    private static String describe(Throwable thrown) {
        String message = thrown.getMessage();
        if (thrown instanceof Check.Failure) {
            return message;
        }
        return thrown.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** For a caller that wants the class list without running anything. */
    public static List<Class<?>> testClasses() {
        return Arrays.asList(TEST_CLASSES);
    }

    /** Preserve the deliberate execution order, but fail if the compiled test tree disagrees. */
    public static void verifyRegistration() {
        Set<String> discovered = new TreeSet<>();
        try {
            Path location = Path.of(AcceptanceTests.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(location)) {
                try (var files = Files.walk(location)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList())
                        inspectClass(location.relativize(file).toString().replace('\\', '/'), discovered);
                }
            } else {
                try (var jar = new JarFile(location.toFile())) {
                    for (var entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList())
                        inspectClass(entry.getName(), discovered);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot verify acceptance test registration", e);
        }
        Set<String> registered = new TreeSet<>();
        for (Class<?> type : TEST_CLASSES) {
            if (!registered.add(type.getName())) throw new IllegalStateException("Duplicate test class: " + type.getName());
        }
        Set<String> missing = new TreeSet<>(discovered); missing.removeAll(registered);
        Set<String> empty = new TreeSet<>(registered); empty.removeAll(discovered);
        if (!missing.isEmpty() || !empty.isEmpty()) throw new IllegalStateException(
                "Acceptance test registration mismatch. Add to TEST_CLASSES: " + missing
                        + "; registered classes without test methods: " + empty);
    }

    private static void inspectClass(String path, Set<String> discovered) throws ClassNotFoundException {
        if (path.equals("module-info.class") || path.endsWith("package-info.class")) return;
        String name = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        Class<?> type = Class.forName(name, false, AcceptanceTests.class.getClassLoader());
        if (!testMethods(type).isEmpty()) discovered.add(name);
    }
}
