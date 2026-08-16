package dev.bwr.core.fuel;

import dev.bwr.core.Check;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The datapack fuel contract: SPEC 2.1 says fuel types are JSON entries, not
 * hardcoded classes.
 *
 * <p>These tests exercise {@link FuelTypeSpec}, which is the whole definition of
 * what a fuel entry means. The mod's {@code FuelTypeCodec} is a
 * DataFixerUpper adapter that calls straight into it and adds no semantics of
 * its own, so what passes here is what a pack author gets in game.
 *
 * <p>The text a test feeds in is literally the text of a datapack file. Nothing
 * is stubbed: {@link FuelTypeSpec#fromJson} parses the same bytes Minecraft
 * would hand to Gson.
 */
public final class FuelTypeSpecTest {

    private FuelTypeSpecTest() {
    }

    /**
     * A fuel that exists nowhere in the Java. This is the acceptance criterion
     * for SPEC 2.1: a pack author writes a file and gets working nuclear data,
     * with no class, no registration and no enum entry anywhere.
     *
     * <p>The numbers are deliberately not any preset's. It is a hypothetical
     * high-enrichment americium-bearing bundle with a very small delayed neutron
     * fraction, and the point of the test is that the model takes it seriously:
     * beta 0.0013 comes back out as 0.0013 and the kinetics will run on it.
     */
    public static void test01_datapackDefinedFuelRoundTripsThroughTheCodec() {
        String name = "testpack:minor_actinide";
        String json = """
                {
                  "k_inf_base":              1.3725,
                  "beta":                    0.0013,
                  "prompt_lifetime_seconds": 1.75e-5,
                  "depletion_rate":          8.25e-6,
                  "doppler_coeff_per_c":     -2.35e-5,
                  "gad_content":             0.0125,
                  "heat_per_fission_mev":    215.25,
                  "nominal_enrichment":      0.145,
                  "conversion_gain_k_inf":   0.035
                }
                """;

        FuelType decoded = FuelTypeSpec.fromJson(name, json);

        Check.isTrue(name.equals(decoded.name()), "name comes from the file, got '%s'", decoded.name());
        Check.exactly(1.3725, decoded.kInfBase(), "k_inf_base");
        Check.exactly(0.0013, decoded.beta(), "beta");
        Check.exactly(1.75e-5, decoded.promptLifetimeSeconds(), "prompt_lifetime_seconds");
        Check.exactly(8.25e-6, decoded.depletionKInfPerMwdPerTonne(), "depletion_rate");
        Check.exactly(-2.35e-5, decoded.dopplerCoeffPerC(), "doppler_coeff_per_c");
        Check.exactly(0.0125, decoded.gadContentWeightFraction(), "gad_content");
        Check.exactly(215.25, decoded.heatPerFissionMeV(), "heat_per_fission_mev");
        Check.exactly(0.145, decoded.nominalEnrichmentWeightFraction(), "nominal_enrichment");
        Check.exactly(0.035, decoded.conversionGainKInf(), "conversion_gain_k_inf");
        Check.isTrue(decoded.isNetBreeder(), "a non-zero conversion gain makes it a breeder");

        // Encode -> text -> decode returns the identical object, bit for bit.
        String reEmitted = FuelTypeSpec.toJson(decoded);
        FuelType again = FuelTypeSpec.fromJson(name, reEmitted);
        Check.isTrue(decoded.equals(again),
                "round trip through JSON text changed the fuel:%n  %s%n  %s", decoded, again);

        // And through the map form, which is what the mod's Codec transports.
        Map<String, Double> fields = FuelTypeSpec.encode(decoded);
        Check.isTrue(decoded.equals(FuelTypeSpec.decode(name, fields)),
                "round trip through the field map changed the fuel");
        Check.exactly(FuelTypeSpec.fieldNames().size(), fields.size(), "encoded field count");

        // A fuel this twitchy is usable, and nothing about it is special-cased:
        // it makes a core with a third of LEU's control margin, which is the
        // only thing that makes it dangerous.
        FuelAssembly bundle = new FuelAssembly(decoded);
        Check.finiteAndPositive(bundle.kInf(), "k_inf of the datapack fuel");
        Check.note("datapack fuel '%s': k_inf %.4f, beta %.5f (%.2fx LEU), Lambda %.2e s",
                decoded.name(), bundle.kInf(), decoded.beta(),
                decoded.beta() / FuelType.LEU.beta(), decoded.promptLifetimeSeconds());
        Check.note("re-emitted entry:%n%s", reEmitted.stripTrailing());
    }

    /**
     * The two optional fields default to a conventional non-breeding fuel quoted
     * at LEU enrichment, so the minimal entry is exactly the seven parameters of
     * the SPEC 2.1 table and nothing else.
     */
    public static void test02_theMinimalEntryIsTheSevenSpecParameters() {
        String json = """
                {
                  "k_inf_base":              1.10,
                  "beta":                    0.0044,
                  "prompt_lifetime_seconds": 3.3e-5,
                  "depletion_rate":          4.0e-6,
                  "doppler_coeff_per_c":     -1.5e-5,
                  "gad_content":             0.0,
                  "heat_per_fission_mev":    200.0
                }
                """;
        FuelType minimal = FuelTypeSpec.fromJson("sparse", json);

        Check.exactly(7, FuelTypeSpec.REQUIRED_FIELDS.size(), "required field count");
        Check.exactly(FuelType.DEFAULT_NOMINAL_ENRICHMENT_WEIGHT_FRACTION,
                minimal.nominalEnrichmentWeightFraction(), "defaulted nominal_enrichment");
        Check.exactly(0.0, minimal.conversionGainKInf(), "defaulted conversion_gain_k_inf");
        Check.isFalse(minimal.isNetBreeder(), "a fuel that omits the breeding gain does not breed");
        Check.note("required %s", FuelTypeSpec.REQUIRED_FIELDS);
        Check.note("optional %s", FuelTypeSpec.OPTIONAL_FIELDS);
    }

    /**
     * Every way a hand-written entry goes wrong, and the message the pack author
     * gets. The loader turns each of these into one logged line and one skipped
     * file; none of them may be silently absorbed into a working fuel, because a
     * fuel that is quietly not what the author wrote is worse than no fuel.
     */
    public static void test03_malformedEntriesAreRejectedWithLegibleMessages() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("missing a required field",
                """
                { "k_inf_base": 1.2, "prompt_lifetime_seconds": 4.0e-5, "depletion_rate": 6.0e-6,
                  "doppler_coeff_per_c": -1.6e-5, "gad_content": 0.02, "heat_per_fission_mev": 202.5 }
                """);
        cases.put("a misspelled field",
                """
                { "k_inf_base": 1.2, "beta_eff": 0.0065, "beta": 0.0065,
                  "prompt_lifetime_seconds": 4.0e-5, "depletion_rate": 6.0e-6,
                  "doppler_coeff_per_c": -1.6e-5, "gad_content": 0.02, "heat_per_fission_mev": 202.5 }
                """);
        cases.put("a positive Doppler coefficient",
                """
                { "k_inf_base": 1.2, "beta": 0.0065, "prompt_lifetime_seconds": 4.0e-5,
                  "depletion_rate": 6.0e-6, "doppler_coeff_per_c": 1.6e-5,
                  "gad_content": 0.02, "heat_per_fission_mev": 202.5 }
                """);
        cases.put("beta outside (0,1)",
                """
                { "k_inf_base": 1.2, "beta": 1.5, "prompt_lifetime_seconds": 4.0e-5,
                  "depletion_rate": 6.0e-6, "doppler_coeff_per_c": -1.6e-5,
                  "gad_content": 0.02, "heat_per_fission_mev": 202.5 }
                """);
        cases.put("a zero prompt lifetime",
                """
                { "k_inf_base": 1.2, "beta": 0.0065, "prompt_lifetime_seconds": 0.0,
                  "depletion_rate": 6.0e-6, "doppler_coeff_per_c": -1.6e-5,
                  "gad_content": 0.02, "heat_per_fission_mev": 202.5 }
                """);
        cases.put("a string where a number goes",
                """
                { "k_inf_base": "lots", "beta": 0.0065, "prompt_lifetime_seconds": 4.0e-5,
                  "depletion_rate": 6.0e-6, "doppler_coeff_per_c": -1.6e-5,
                  "gad_content": 0.02, "heat_per_fission_mev": 202.5 }
                """);
        cases.put("truncated text",
                "{ \"k_inf_base\": 1.2, \"beta\": ");
        cases.put("not an object at all",
                "[1.2, 0.0065]");

        for (Map.Entry<String, String> entry : cases.entrySet()) {
            String message = null;
            try {
                FuelTypeSpec.fromJson("bad_fuel", entry.getValue());
            } catch (IllegalArgumentException e) {
                message = e.getMessage();
            }
            Check.isTrue(message != null, "%s was accepted; it must be rejected", entry.getKey());
            Check.isTrue(message.contains("bad_fuel"),
                    "the message for %s does not name the fuel: %s", entry.getKey(), message);
            Check.note("%-32s -> %s", entry.getKey(), message);
        }

        // NaN and infinity arrive as doubles rather than as text, so they are
        // checked through the map form the codec actually delivers.
        for (double poison : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
            Map<String, Double> fields = FuelTypeSpec.encode(FuelType.LEU);
            fields.put(FuelTypeSpec.FIELD_BETA, poison);
            String message = null;
            try {
                FuelTypeSpec.decode("poisoned", fields);
            } catch (IllegalArgumentException e) {
                message = e.getMessage();
            }
            Check.isTrue(message != null, "beta = %s was accepted", poison);
            Check.note("beta = %-9s -> %s", poison, message);
        }
    }

    /**
     * The shipped {@code data/bwr/fuel_type/} entries are these presets emitted
     * by {@link FuelTypeSpec#toJson}, so this is the test that says the files a
     * player can edit hold the same physics the Java falls back to.
     *
     * <p>It also pins the four delayed neutron fractions. Those are Keepin's
     * measured values, not balance numbers: the reason plutonium is frightening
     * is that 0.002099 is 32% of U-235's 0.006502, and if a later change ever
     * softens that, the emergent hazard SPEC 2.2 is built on quietly disappears.
     */
    public static void test04_shippedDefaultsRoundTripAndKeepTheirRealBetas() {
        Map<String, Double> measuredBeta = new LinkedHashMap<>();
        measuredBeta.put("leu", 0.006502);        // Keepin, U-235 thermal
        measuredBeta.put("heu", 0.006502);        // same isotope, same number
        measuredBeta.put("plutonium", 0.002099);  // Keepin, Pu-239 thermal
        measuredBeta.put("mox", 0.0035);          // blended U/Pu fission rate
        measuredBeta.put("thorium", 0.00266);     // U-233

        List<FuelType> presets = FuelType.presets();
        Check.exactly(measuredBeta.size(), presets.size(), "preset count");

        for (FuelType preset : presets) {
            Double expected = measuredBeta.get(preset.name());
            Check.isTrue(expected != null, "unexpected preset '%s'", preset.name());
            Check.exactly(expected, preset.beta(), "beta of " + preset.name());

            String json = FuelTypeSpec.toJson(preset);
            FuelType back = FuelTypeSpec.fromJson(preset.name(), json);
            Check.isTrue(preset.equals(back),
                    "%s does not survive its own datapack entry:%n  %s%n  %s",
                    preset.name(), preset, back);
            Check.note("%-10s beta %.6f (%.2fx LEU), k_inf base %.3f, %d bytes of JSON",
                    preset.name(), preset.beta(), preset.beta() / FuelType.LEU.beta(),
                    preset.kInfBase(), json.length());
        }

        double ratio = FuelType.PLUTONIUM.beta() / FuelType.LEU.beta();
        Check.inRange(0.30, 0.34, ratio, "Pu-239 beta as a fraction of U-235 beta");
        Check.note("plutonium has %.1f%% of LEU's control margin, from beta alone", 100.0 * ratio);
    }
}
