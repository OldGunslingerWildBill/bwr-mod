package dev.bwr.core;

import dev.bwr.core.fuel.CoreLoading;
import dev.bwr.core.fuel.FuelAssembly;
import dev.bwr.core.fuel.FuelType;
import dev.bwr.core.fuel.FuelTypeSpec;
import dev.bwr.core.harness.TransientHarness;
import dev.bwr.core.instrument.AveragePowerRangeMonitor;
import dev.bwr.core.instrument.IntermediateRangeMonitor;
import dev.bwr.core.instrument.NeutronDetector;
import dev.bwr.core.instrument.PeriodMeter;
import dev.bwr.core.instrument.SourceRangeMonitor;
import dev.bwr.core.kinetics.DelayedNeutronData;
import dev.bwr.core.kinetics.PointKinetics;
import dev.bwr.core.kinetics.ReactivityBalance;
import dev.bwr.core.kinetics.RodWorth;
import dev.bwr.core.poison.Xenon;
import dev.bwr.core.thermal.DecayHeat;
import dev.bwr.core.thermal.FuelThermal;
import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.core.thermal.VoidModel;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The assembled core: one tick at a time, and the properties that must hold
 * across the whole model rather than inside any one component.
 *
 * <p>Power here is <b>emergent</b>. Nothing in these tests commands a burn rate,
 * because there is nowhere to command one: the only handles are rod position and
 * recirculation flow, and the neutronics decides what they are worth.
 */
public final class ReactorCoreTickTest {

    private ReactorCoreTickTest() {
    }

    private static TransientHarness.PlantOperator operatorFor(ReactorCore core, boolean rodControl) {
        TransientHarness.PlantOperator operator = new TransientHarness.PlantOperator(core);
        operator.setPressureRegulatorEnabled(true);
        operator.setLevelControlEnabled(true);
        operator.setRodControlEnabled(rodControl);
        return operator;
    }

    /**
     * A core initialised at rated power and left alone must stay at rated power.
     * The initialisation puts void, fuel temperature, xenon, decay heat and the
     * rod pattern all at their equilibria for that point simultaneously, and if
     * any one of them disagrees with the others the plant walks away from the
     * operating point over the next few minutes.
     */
    public static void test01_ratedCoreHoldsItsOperatingPoint() {
        ReactorCore core = TransientHarness.ratedCore();
        TransientHarness.PlantOperator operator = operatorFor(core, false);

        double initialTotal = core.getTotalPowerFractionOfRated();
        double worstDeviation = 0.0;
        for (int i = 0; i < 6000; i++) { // 300 s
            operator.tick();
            core.step();
            worstDeviation = Math.max(worstDeviation,
                    Math.abs(core.getTotalPowerFractionOfRated() - 1.0));
            Check.finiteAndPositive(core.getNeutronPowerFraction(), "fission power during the hold");
            Check.finiteAndPositive(core.getPressurePsig() + PhysicalConstants.ATMOSPHERIC_PSI,
                    "dome pressure during the hold");
        }

        Check.note("300 s at rated: total %.4f -> %.4f, worst deviation %.4f",
                initialTotal, core.getTotalPowerFractionOfRated(), worstDeviation);
        Check.note("holding %.1f psig, %.2f degC, void %.4f, fuel %.0f degC, level %.1f in",
                core.getPressurePsig(), core.getCoolantTemperatureC(), core.getVoidFraction(),
                core.getFuelTemperatureC(), core.getIndicatedLevelIn());
        Check.relative(1.0, core.getTotalPowerFractionOfRated(), 0.05, "total power after 300 s");
        Check.lessThan(0.10, worstDeviation, "worst excursion from rated over 300 s");
        Check.absolute(PhysicalConstants.RATED_DOME_PRESSURE_PSIG, core.getPressurePsig(), 15.0,
                "dome pressure held by the regulator");
        Check.inRange(60.0, 125.0, core.getAveragePowerRangePercent(0), "APRM indication at rated");
    }

    /**
     * <b>The manoeuvre that makes a BWR a BWR.</b> Cut recirculation flow with
     * every rod standing still and power falls on its own: less flow means more
     * void, void reactivity is negative, and the neutronics settles somewhere
     * lower. Nothing commanded it.
     */
    public static void test02_flowAloneMovesPowerWithRodsStationary() {
        ReactorCore core = TransientHarness.ratedCore();
        TransientHarness.PlantOperator operator = operatorFor(core, false);
        TransientHarness.runSeconds(core, operator, 30.0, null);

        int[] rodsBefore = core.getRodNotchIndices();
        double powerBefore = core.getTotalPowerFractionOfRated();
        double voidBefore = core.getVoidFraction();
        double fuelBefore = core.getFuelTemperatureC();

        core.setRecirculationFlowFraction(0.70);
        TransientHarness.runSeconds(core, operator, 300.0, null);

        double powerAfter = core.getTotalPowerFractionOfRated();
        double voidAfter = core.getVoidFraction();
        Check.note("flow 1.00 -> %.2f: power %.4f -> %.4f, void %.4f -> %.4f, "
                        + "rho_void %+.5f, fuel %.0f -> %.0f degC",
                core.getCoreFlowFraction(), powerBefore, powerAfter, voidBefore, voidAfter,
                core.getReactivityBreakdown().voidDkOverK(), fuelBefore, core.getFuelTemperatureC());

        Check.arraysExactly(rodsBefore, core.getRodNotchIndices(), "rod pattern during a flow change");
        Check.lessThan(powerBefore, powerAfter, "power must fall when flow is cut");
        Check.greaterThan(voidBefore, voidAfter, "void must rise when flow is cut");
        Check.greaterThan(0.3 * powerBefore, powerAfter, "the plant must not shut itself down on flow alone");
        Check.lessThan(0.0, core.getReactivityBreakdown().voidDkOverK(), "void reactivity must be negative");

        // And back. The same handle in the other direction returns the power.
        core.setRecirculationFlowFraction(1.0);
        TransientHarness.runSeconds(core, operator, 300.0, null);
        Check.note("flow restored: power %.4f, void %.4f", core.getTotalPowerFractionOfRated(),
                core.getVoidFraction());
        Check.arraysExactly(rodsBefore, core.getRodNotchIndices(), "rod pattern after the flow change");
        Check.relative(powerBefore, core.getTotalPowerFractionOfRated(), 0.10,
                "power after flow is restored");
    }

    /**
     * Isolating the steam outlet at power must raise pressure, collapse void, and
     * hand back <i>positive</i> reactivity — the emergent pressurisation
     * transient of {@code SPEC.md} section 6.3. Nothing in the model knows that
     * closing a valve should raise power; four separate models each doing their
     * own job produce it.
     */
    public static void test03_isolationCollapsesVoidAndAddsReactivity() {
        ReactorCore core = TransientHarness.ratedCore();
        TransientHarness.PlantOperator operator = operatorFor(core, false);
        TransientHarness.runSeconds(core, operator, 20.0, null);

        double pressureBefore = core.getPressurePsig();
        double voidBefore = core.getVoidFraction();
        double voidReactivityBefore = core.getReactivityBreakdown().voidDkOverK();
        double powerBefore = core.getNeutronPowerFraction();

        operator.setPressureRegulatorEnabled(false);
        core.setTurbineSteamFlowKgPerS(0.0);
        core.setBypassSteamFlowKgPerS(0.0);
        core.setReliefSteamFlowKgPerS(0.0);
        TransientHarness.runSeconds(core, operator, 20.0, null);

        Check.note("20 s after isolation: %.0f -> %.0f psig, void %.4f -> %.4f, "
                        + "rho_void %+.5f -> %+.5f, fission %.4f -> %.4f",
                pressureBefore, core.getPressurePsig(), voidBefore, core.getVoidFraction(),
                voidReactivityBefore, core.getReactivityBreakdown().voidDkOverK(),
                powerBefore, core.getNeutronPowerFraction());

        Check.greaterThan(pressureBefore + 20.0, core.getPressurePsig(), "pressure after isolation");
        Check.lessThan(voidBefore, core.getVoidFraction(), "void must collapse as pressure rises");
        Check.greaterThan(voidReactivityBefore, core.getReactivityBreakdown().voidDkOverK(),
                "collapsing void must hand back positive reactivity");
        Check.greaterThan(powerBefore, core.getNeutronPowerFraction(),
                "power must rise on the void collapse");
        Check.finiteAndPositive(core.getNeutronPowerFraction(), "fission power during the pressurisation");

        // Nothing opened a relief valve. SRVs are player-actuated, and the model
        // will let the vessel run past its design pressure rather than decide for
        // the player that it should not.
        Check.exactly(0.0, core.getReliefSteamFlowKgPerS(), "no relief valve may open by itself");
    }

    /**
     * Doppler must fight the excursion. Fuel temperature rising has to produce a
     * negative reactivity contribution, or nothing terminates a power rise.
     */
    public static void test04_dopplerOpposesAPowerRise() {
        ReactorCore core = TransientHarness.ratedCore();
        TransientHarness.PlantOperator operator = operatorFor(core, false);
        TransientHarness.runSeconds(core, operator, 20.0, null);

        double fuelC = core.getFuelTemperatureC();
        double dopplerAtRated = core.getReactivityBreakdown().dopplerDkOverK();
        Check.note("fuel %.0f degC, coolant %.1f degC, rho_doppler %+.5f dk/k",
                fuelC, core.getCoolantTemperatureC(), dopplerAtRated);
        Check.greaterThan(core.getCoolantTemperatureC(), fuelC, "fuel must be hotter than the coolant");
        Check.lessThan(0.0, dopplerAtRated, "Doppler reactivity at rated power");

        core.scram();
        TransientHarness.runSeconds(core, operator, 120.0, null);
        Check.note("after the scram: fuel %.0f degC, rho_doppler %+.5f dk/k",
                core.getFuelTemperatureC(), core.getReactivityBreakdown().dopplerDkOverK());
        Check.greaterThan(dopplerAtRated, core.getReactivityBreakdown().dopplerDkOverK(),
                "Doppler must relax back as the fuel cools");
    }

    /**
     * The model is deterministic. Two cores built and driven identically must
     * produce bit-identical state — there is no randomness anywhere in the
     * physics, and a stuck rod is a consequence of neglect rather than a dice
     * roll.
     */
    public static void test05_steppingIsDeterministic() {
        ReactorCore first = TransientHarness.ratedCore();
        ReactorCore second = TransientHarness.ratedCore();
        for (int i = 0; i < 400; i++) {
            first.setRecirculationFlowFraction(0.7 + 0.3 * Math.sin(i * 0.01));
            second.setRecirculationFlowFraction(0.7 + 0.3 * Math.sin(i * 0.01));
            if (i == 200) {
                first.scram();
                second.scram();
            }
            first.step();
            second.step();
        }
        ReactorStateRoundTripTest.assertStatesIdentical(first.toState(), second.toState(),
                "two identically driven cores");
    }

    /**
     * A power change must move the fuel through the instrument stack with no gap:
     * the source range monitor covers the bottom, the intermediate range monitors
     * take over before it rolls off, and the average power range monitors come on
     * scale before the intermediate range runs out. If the bands did not overlap
     * there would be a flux at which nothing reads true, which is a startup with
     * the lights off.
     */
    public static void test06_instrumentRangesOverlap() {
        SourceRangeMonitor srm = new SourceRangeMonitor("SRM", new CoreConfig());
        IntermediateRangeMonitor irmLowest =
                new IntermediateRangeMonitor("IRM", IntermediateRangeMonitor.LOWEST_RANGE);
        IntermediateRangeMonitor irmHighest =
                new IntermediateRangeMonitor("IRM", IntermediateRangeMonitor.HIGHEST_RANGE);
        AveragePowerRangeMonitor aprm = new AveragePowerRangeMonitor("APRM");

        Check.note("SRM covers %.3E to %.3E of rated (rollover at the top)",
                srm.getRangeMinimumNeutronPowerFraction(), srm.getRangeMaximumNeutronPowerFraction());
        Check.note("IRM covers %.3E to %.3E across its ten ranges",
                irmLowest.getRangeMinimumNeutronPowerFraction(),
                irmHighest.getRangeMaximumNeutronPowerFraction());
        Check.note("APRM covers %.3E to %.3E", aprm.getRangeMinimumNeutronPowerFraction(),
                aprm.getRangeMaximumNeutronPowerFraction());

        Check.lessThan(srm.getRangeMaximumNeutronPowerFraction(),
                irmLowest.getRangeMinimumNeutronPowerFraction(),
                "the IRM must come on scale before the SRM rolls over");
        Check.lessThan(irmHighest.getRangeMaximumNeutronPowerFraction(),
                aprm.getRangeMinimumNeutronPowerFraction(),
                "the APRM must come on scale before the IRM runs out");
    }

    /**
     * <b>The design rule, enforced by reflection.</b> The mod provides hardware
     * and physics; the player provides control logic. So nothing anywhere in the
     * physics may decide whether a number is acceptable — no trip setpoints, no
     * permissives, no protection system, no auto-actuation. {@code scram()} is
     * allowed because it is a bare actuator with no condition attached.
     *
     * <p>This is a real test and not a comment because the rule is easy to break
     * by accident and impossible to spot in review once the codebase is large.
     *
     * <h2>What the scan reaches, and what it used to miss</h2>
     * <b>Every named member type</b>, not just top-level classes. Discovery used
     * to drop any binary name containing {@code '$'}, justified as excluding
     * anonymous and local classes — but {@code '$'} is also in the binary name of
     * every named member type, and core/ has nine of them. All nine were
     * invisible, {@code TransientHarness.RodSequencer} among them: a rod
     * withdrawal sequencer is where a real BWR keeps its rod worth minimiser
     * interlock, so the one class most likely to grow a permissive was the one
     * class the interlock guard could not see. Anonymous and local types are now
     * excluded by asking the loaded class, which is what the comment always
     * claimed was happening.
     *
     * <p><b>Fields as well as methods</b>, because a setpoint is far more likely
     * to arrive as a constant than as a method — and the identifier splitter
     * handles {@code SCREAMING_SNAKE_CASE}, without which field scanning finds
     * nothing at all: a camel-case-only splitter turns
     * {@code PRESSURE_SETPOINT_PSIG} into twenty-one single letters.
     *
     * <p>Three members are exempt, each named and justified individually and
     * each required to still exist, so that the exemption list cannot rot into a
     * silent licence.
     */
    public static void test07_noProtectionLogicAnywhereInThePhysics() {
        Class<?>[] physics = {
                ReactorCore.class, ReactorState.class, CoreConfig.class, PhysicalConstants.class,
                PointKinetics.class, DelayedNeutronData.class, ReactivityBalance.class, RodWorth.class,
                VoidModel.class, PressureVessel.class, FuelThermal.class, DecayHeat.class,
                Saturation.class, Xenon.class,
                CoreLoading.class, FuelAssembly.class, FuelType.class, FuelTypeSpec.class,
                NeutronDetector.class, SourceRangeMonitor.class, IntermediateRangeMonitor.class,
                AveragePowerRangeMonitor.class, PeriodMeter.class,
        };
        // The list above is a floor, not the scan set. A hardcoded list silently
        // stops covering whatever is written next — boundary/, eccs/, nodal/ and
        // harness/ were all invisible to this test until discovery was added.
        // Everything compiled under dev.bwr.core is scanned; the list only
        // pins the classes whose absence from the scan would be worst.
        List<Class<?>> discovered = discoverCoreClasses();
        for (Class<?> required : physics) {
            Check.isTrue(discovered.contains(required),
                    "classpath discovery missed %s — the scan is not covering the physics",
                    required.getName());
        }

        // `discovered.size() >= physics.length` used to stand here, and it is
        // implied by the 23 assertions above: a list containing 23 distinct
        // elements has size >= 23, so that line could never be the one that
        // fired. Discovery could regress from 46 classes to exactly the 23
        // hardcoded ones and it would still have passed — which is precisely the
        // 23-of-37 hole this discovery was added to close.
        //
        // Two guards that can fail instead. First: every package that has
        // compiled classes in it must show up in what discovery returned. This
        // is walked separately, listing directories rather than class files, so
        // a discovery that stopped recursing into boundary/, eccs/, nodal/,
        // pool/ or harness/ fails here however many classes it still finds in
        // the packages it does reach.
        Set<String> expectedPackages = corePackagesOnDisk();
        Set<String> scannedPackages = new TreeSet<>();
        for (Class<?> type : discovered) {
            scannedPackages.add(type.getPackageName());
        }
        List<String> missedPackages = new ArrayList<>(expectedPackages);
        missedPackages.removeAll(scannedPackages);
        Check.isTrue(missedPackages.isEmpty(),
                "classpath discovery did not reach these packages, which have compiled classes "
                        + "in them: %s", missedPackages);

        // Second: an absolute floor well clear of the hardcoded list, so that
        // losing a whole area of the model inside a package that survives is
        // still a failure. 46 at the time of writing: 37 top-level types and 9
        // named member types.
        final int minimumDiscoveredClasses = 40;
        Check.isTrue(discovered.size() >= minimumDiscoveredClasses,
                "discovery found %d classes across %d packages; fewer than %d means the scan has "
                        + "shrunk, not that the model has", discovered.size(),
                scannedPackages.size(), minimumDiscoveredClasses);

        // Words that turn a measurement into a judgement.
        List<String> bannedWords = List.of(
                "high", "low", "trip", "trips", "tripped", "permissive", "permissives",
                "setpoint", "setpoints", "interlock", "interlocks", "alarm", "alarms",
                "unsafe", "acceptable", "violation");
        List<String> bannedNames = List.of(
                "shouldscram", "checktrips", "autoscram", "needsscram", "issafe", "isunsafe",
                "ishighpressure", "islowlevel", "checklimits", "enforcelimits",
                "protectionsystem", "safetysystem", "mustscram", "scramifneeded",
                "triponhighpressure", "autostart");
        // Deliberately not banned: "protective", as in the protective oxide layer
        // on the cladding. That is a description of a physical film of zirconia,
        // not a protection system, and the distinction is the whole point.

        List<String> offences = new ArrayList<>();
        int scannedMethods = 0;
        int scannedFields = 0;
        for (Class<?> type : discovered) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                scannedMethods++;
                inspect(type, "method", method.getName(), bannedWords, bannedNames, offences);
            }
            // Fields too. A setpoint is far more likely to arrive as a constant
            // than as a method, and scanning only methods meant every
            // PRESSURE_SETPOINT-shaped field in the tree was invisible.
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                scannedFields++;
                inspect(type, "field", field.getName(), bannedWords, bannedNames, offences);
            }
        }

        // The exemptions, named one at a time and justified one at a time. Each
        // is asserted to still exist below, so an exemption for something that
        // has been renamed or deleted cannot sit here quietly widening.
        Map<String, String> exemptions = new LinkedHashMap<>();
        exemptions.put("dev.bwr.core.PhysicalConstants field RECIRC_LOW_SPEED_FRACTION",
                "the recirculation pumps are two-speed hardware and 'low speed' is the name of a "
                        + "detent, not a judgement about a number");
        exemptions.put("dev.bwr.core.harness.TransientHarness$PlantOperator "
                        + "field PRESSURE_SETPOINT_PSIG",
                "PlantOperator is the stand-in for the player's Lua, written against the same "
                        + "public getters the peripheral exposes; a setpoint is what control logic "
                        + "is made of and this one is on the player's side of the line");
        exemptions.put("dev.bwr.core.harness.TransientHarness$PlantOperator field LEVEL_SETPOINT_IN",
                "same: the harness operator's own level setpoint, not the plant's");
        List<String> unusedExemptions = new ArrayList<>(exemptions.keySet());
        unusedExemptions.removeAll(offences);
        offences.removeAll(exemptions.keySet());

        Check.note("scanned %d methods and %d fields across %d classes in %d packages "
                        + "(%d named explicitly, the rest found by classpath discovery; "
                        + "%d of them are named member types)",
                scannedMethods, scannedFields, discovered.size(), scannedPackages.size(),
                physics.length, countMemberTypes(discovered));
        for (Map.Entry<String, String> exemption : exemptions.entrySet()) {
            Check.note("EXEMPT %s — %s", exemption.getKey(), exemption.getValue());
        }
        for (String offence : offences) {
            Check.note("OFFENCE %s", offence);
        }
        Check.isTrue(offences.isEmpty(),
                "the physics must expose measurements and actuators, never judgements: %s", offences);
        Check.isTrue(unusedExemptions.isEmpty(),
                "these exemptions no longer match anything the scan found, so they are permitting "
                        + "nothing and hiding whatever is written next under the same name: %s",
                unusedExemptions);

        // The nested types the '$' filter used to drop are really in the scan
        // now. RodSequencer above all: a rod withdrawal sequencer is where a
        // real BWR keeps its rod worth minimiser interlock, and it was invisible
        // to the one guard that forbids interlocks.
        for (Class<?> mustBeScanned : new Class<?>[]{
                TransientHarness.PlantOperator.class, TransientHarness.RodSequencer.class,
                ReactivityBalance.Breakdown.class, NeutronDetector.DetectorStatus.class}) {
            Check.isTrue(discovered.contains(mustBeScanned),
                    "%s is a named member type and must be scanned — filtering on '$' in the "
                            + "binary name drops every one of them, not just anonymous classes",
                    mustBeScanned.getName());
        }

        // And the actuator that is allowed exists, with no arguments and no
        // conditions to satisfy before calling it.
        try {
            Method scram = ReactorCore.class.getMethod("scram");
            Check.exactly(0, scram.getParameterCount(), "scram() must take no arguments");
            Check.isTrue(scram.getReturnType() == void.class,
                    "scram() must return nothing — it is an actuator, not a request");
        } catch (NoSuchMethodException e) {
            Check.fail("ReactorCore.scram() must exist as a bare actuator");
        }
    }

    /** Record one member's name against the banned lists. */
    private static void inspect(Class<?> type, String kind, String name,
                                List<String> bannedWords, List<String> bannedNames,
                                List<String> offences) {
        String flat = name.toLowerCase(Locale.ROOT).replace("_", "");
        String where = type.getName() + " " + kind + " " + name;
        for (String banned : bannedNames) {
            if (flat.contains(banned)) {
                offences.add(where);
                return;
            }
        }
        for (String word : splitIdentifier(name)) {
            if (bannedWords.contains(word)) {
                offences.add(where);
                return;
            }
        }
    }

    private static int countMemberTypes(List<Class<?>> classes) {
        int count = 0;
        for (Class<?> type : classes) {
            if (type.getEnclosingClass() != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Every package under {@code dev/bwr/core} that has compiled classes in it,
     * walked from the same code source {@link #discoverCoreClasses} walks but
     * listing directories rather than class files — so the two cannot fail in
     * the same way at the same time.
     */
    private static Set<String> corePackagesOnDisk() {
        Set<String> packages = new TreeSet<>();
        Path root = codeSourceRoot();
        if (!Files.isDirectory(root)) {
            // A jar. Derive the package set from the entry names instead.
            try (JarFile jar = new JarFile(root.toFile())) {
                for (JarEntry entry : Collections.list(jar.entries())) {
                    String name = entry.getName();
                    if (name.startsWith("dev/bwr/core/") && name.endsWith(".class")) {
                        packages.add(name.substring(0, name.lastIndexOf('/')).replace('/', '.'));
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + root, e);
            }
            return packages;
        }
        Path packageRoot = root.resolve("dev").resolve("bwr").resolve("core");
        try (Stream<Path> walk = Files.walk(packageRoot)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> packages.add(root.relativize(p.getParent()).toString()
                            .replace(File.separatorChar, '.')));
        } catch (IOException e) {
            throw new IllegalStateException("cannot walk " + packageRoot, e);
        }
        return packages;
    }

    private static Path codeSourceRoot() {
        URL location = ReactorCore.class.getProtectionDomain().getCodeSource().getLocation();
        try {
            return Paths.get(location.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot locate the compiled core classes", e);
        }
    }

    /**
     * Every production class under {@code dev.bwr.core}, found by walking the
     * code source that {@link ReactorCore} itself was loaded from.
     *
     * <p>This exists so the design rule cannot be escaped by writing a new
     * class. It works both from a directory of {@code .class} files (Gradle,
     * and the plain-javac workflow in BUILD-STATUS.md) and from a jar.
     * Test classes are excluded: a test may legitimately mention a banned word
     * in a method name describing what it forbids.
     */
    private static List<Class<?>> discoverCoreClasses() {
        List<String> names = new ArrayList<>();
        Path root = codeSourceRoot();

        if (Files.isDirectory(root)) {
            Path packageRoot = root.resolve("dev").resolve("bwr").resolve("core");
            try (Stream<Path> walk = Files.walk(packageRoot)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                        .forEach(p -> names.add(root.relativize(p).toString()
                                .replace(File.separatorChar, '/')));
            } catch (IOException e) {
                throw new IllegalStateException("cannot walk " + packageRoot, e);
            }
        } else {
            try (JarFile jar = new JarFile(root.toFile())) {
                for (JarEntry entry : Collections.list(jar.entries())) {
                    if (entry.getName().startsWith("dev/bwr/core/")
                            && entry.getName().endsWith(".class")) {
                        names.add(entry.getName());
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + root, e);
            }
        }

        List<Class<?>> classes = new ArrayList<>();
        for (String entry : names) {
            String className = entry.substring(0, entry.length() - ".class".length())
                    .replace('/', '.');
            // Test classes may legitimately name what they forbid. Checked on
            // the OUTERMOST name so that a member type of a test class goes with
            // its enclosing class, and so that a production member type is not
            // dropped for the shape of its own simple name.
            String topLevel = className.contains("$")
                    ? className.substring(0, className.indexOf('$')) : className;
            if (topLevel.endsWith("Test") || topLevel.endsWith("Tests")
                    || topLevel.endsWith("Check")) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(className, false, ReactorCoreTickTest.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("discovered but could not load " + className, e);
            }
            // Only anonymous, local and compiler-generated types are skipped,
            // and they are identified by asking the class rather than by looking
            // for a '$' in its binary name. Every NAMED member type has a '$' in
            // it too, so the old filter dropped all nine of them — including
            // TransientHarness.RodSequencer, which is a rod withdrawal sequencer
            // and therefore exactly where an interlock would appear.
            if (type.isAnonymousClass() || type.isLocalClass() || type.isSynthetic()) {
                continue;
            }
            classes.add(type);
        }
        classes.sort(Comparator.comparing(Class::getName));
        return classes;
    }

    /**
     * Split an identifier into lowercase words, handling both {@code camelCase}
     * and {@code SCREAMING_SNAKE_CASE}.
     *
     * <p>The snake case half matters: the camel-case-only splitter this replaces
     * turned {@code PRESSURE_SETPOINT_PSIG} into twenty-one single letters,
     * because every character is uppercase and it broke on every one. Field
     * scanning without this finds nothing at all.
     */
    private static List<String> splitIdentifier(String name) {
        List<String> words = new ArrayList<>();
        for (String chunk : name.split("_")) {
            if (chunk.isEmpty()) {
                continue;
            }
            if (chunk.equals(chunk.toUpperCase(Locale.ROOT))) {
                words.add(chunk.toLowerCase(Locale.ROOT));
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                if (Character.isUpperCase(c) && current.length() > 0) {
                    words.add(current.toString().toLowerCase(Locale.ROOT));
                    current.setLength(0);
                }
                if (Character.isLetterOrDigit(c)) {
                    current.append(c);
                }
            }
            if (current.length() > 0) {
                words.add(current.toString().toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    /**
     * Rod position is twenty-five discrete notches, read out as 00 to 48 in steps
     * of two. Not a percentage: a BWR drive is a notched hydraulic collet and its
     * position is one of twenty-five things.
     */
    public static void test08_rodPositionIsTwentyFiveDiscreteNotches() {
        Check.exactly(25, PhysicalConstants.ROD_NOTCH_POSITIONS, "notch positions");
        Check.exactly(0, RodWorth.NOTCH_INDEX_FULLY_INSERTED, "fully inserted notch index");
        Check.exactly(24, RodWorth.NOTCH_INDEX_FULLY_WITHDRAWN, "fully withdrawn notch index");
        for (int index = 0; index <= 24; index++) {
            Check.exactly(2 * index, RodWorth.notchLabel(index), "Full Core Display label for notch " + index);
            Check.exactly(index, RodWorth.notchIndexFromLabel(2 * index), "notch index from label");
        }

        ReactorCore core = TransientHarness.shutdownCore();
        boolean rejected = false;
        try {
            core.setRodNotchDemand(0, 25);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        Check.isTrue(rejected, "a notch beyond the top of travel must be rejected");

        // One drive per rod, never a ganged bank: moving one rod moves one rod.
        core.setRodNotchDemand(3, 10);
        TransientHarness.runSeconds(core, null, 40.0, null);
        Check.exactly(10, core.getRodNotchIndex(3), "the commanded rod moved");
        Check.exactly(0, core.getRodNotchIndex(4), "its neighbour did not");
    }
}
