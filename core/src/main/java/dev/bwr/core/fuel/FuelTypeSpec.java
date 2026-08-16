package dev.bwr.core.fuel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The serialised form of a {@link FuelType} — the contract a datapack entry
 * writes against. SPEC 2.1.
 *
 * <h2>Why this is here and not in the mod</h2>
 * SPEC 2.1 says fuel types are JSON entries rather than hardcoded classes, and
 * the mod is where JSON meets Minecraft. But the <i>meaning</i> of those entries
 * is nuclear data: which parameters exist, what units they are in, which are
 * required, what happens when one is missing, and which values are physically
 * impossible. That is physics, so it lives in {@code :core} where it can be
 * tested with nothing but a JDK, and the mod's
 * {@code dev.bwr.mod.fuel.FuelTypeCodec} is a thin transport adapter over it.
 * There is exactly one definition of what a fuel entry means and it is this
 * file.
 *
 * <h2>The contract</h2>
 * A fuel entry is a <b>flat JSON object of numbers</b>. The name is not in it:
 * a datapack entry is named by its file, {@code data/<namespace>/fuel_type/<name>.json},
 * which is what lets a pack override a shipped fuel by writing a file at the
 * same path.
 *
 * <pre>
 * {
 *   "k_inf_base":             1.22,
 *   "beta":                   0.006502,
 *   "prompt_lifetime_seconds": 4.0e-5,
 *   "depletion_rate":         6.5e-6,
 *   "doppler_coeff_per_c":    -1.6e-5,
 *   "gad_content":            0.02,
 *   "heat_per_fission_mev":   202.5,
 *
 *   "nominal_enrichment":     0.035,   // optional
 *   "conversion_gain_k_inf":  0.0      // optional
 * }
 * </pre>
 *
 * The seven required keys are exactly the seven parameters of the SPEC 2.1
 * table. They are required rather than defaulted because every one of them is a
 * measured property of a real material: a fuel entry that forgets {@code beta}
 * and silently inherits U-235's 0.006502 would hand a plutonium fuel three
 * times the control margin it should have, which is the single most important
 * thing this model gets right. Better to reject the entry and say so.
 *
 * <p>Unknown keys are rejected for the same reason. {@code "beta_eff": 0.0021}
 * is a typo, not a fuel; accepting it silently would mean the pack author's
 * plutonium quietly behaves like uranium. The error names the offending key and
 * lists the valid ones.
 *
 * <h2>Graceful degradation is the caller's job</h2>
 * Every failure here is an {@link IllegalArgumentException} carrying a message
 * that names the fuel and the field. The datapack loader catches it, logs it and
 * skips that one entry — a malformed fuel must never take the datapack load down
 * with it.
 */
public final class FuelTypeSpec {

    private FuelTypeSpec() {
    }

    // ---------------------------------------------------------------
    // Field names. These are the datapack keys; nothing else may spell them.
    // ---------------------------------------------------------------

    /** Infinite multiplication factor of fresh fuel at {@link #FIELD_NOMINAL_ENRICHMENT}. */
    public static final String FIELD_K_INF_BASE = "k_inf_base";

    /** Total delayed neutron fraction of the fissioning isotope. Real physics; see below. */
    public static final String FIELD_BETA = "beta";

    /** Prompt neutron lifetime, seconds. SPEC calls this Lambda. */
    public static final String FIELD_PROMPT_LIFETIME_SECONDS = "prompt_lifetime_seconds";

    /** k-infinity lost per MWd/tonne of exposure. The cycle-length knob. */
    public static final String FIELD_DEPLETION_RATE = "depletion_rate";

    /** Fuel temperature coefficient, dk/k per degree C. Must be negative. */
    public static final String FIELD_DOPPLER_COEFF_PER_C = "doppler_coeff_per_c";

    /** Initial gadolinia loading, weight fraction. Burnable poison. */
    public static final String FIELD_GAD_CONTENT = "gad_content";

    /** Recoverable energy per fission, MeV. */
    public static final String FIELD_HEAT_PER_FISSION_MEV = "heat_per_fission_mev";

    /** Optional. Fissile weight fraction at which {@link #FIELD_K_INF_BASE} was quoted. */
    public static final String FIELD_NOMINAL_ENRICHMENT = "nominal_enrichment";

    /** Optional. k-infinity eventually gained from breeding fissile in. Thorium's field. */
    public static final String FIELD_CONVERSION_GAIN_K_INF = "conversion_gain_k_inf";

    /** The seven SPEC 2.1 parameters, in table order. All must be present. */
    public static final List<String> REQUIRED_FIELDS = List.of(
            FIELD_K_INF_BASE,
            FIELD_BETA,
            FIELD_PROMPT_LIFETIME_SECONDS,
            FIELD_DEPLETION_RATE,
            FIELD_DOPPLER_COEFF_PER_C,
            FIELD_GAD_CONTENT,
            FIELD_HEAT_PER_FISSION_MEV);

    /**
     * Fields a fuel entry may omit, and what it gets if it does. Both describe a
     * conventional non-breeding fuel quoted at LEU enrichment, so an entry that
     * mentions neither is a perfectly ordinary fuel rather than a broken one.
     */
    public static final List<String> OPTIONAL_FIELDS = List.of(
            FIELD_NOMINAL_ENRICHMENT,
            FIELD_CONVERSION_GAIN_K_INF);

    /** Every key a fuel entry may carry, required first. */
    public static List<String> fieldNames() {
        List<String> all = new ArrayList<>(REQUIRED_FIELDS);
        all.addAll(OPTIONAL_FIELDS);
        return Collections.unmodifiableList(all);
    }

    /** The value an absent optional field takes. */
    public static double defaultValueOf(String field) {
        switch (field) {
            case FIELD_NOMINAL_ENRICHMENT:
                return FuelType.DEFAULT_NOMINAL_ENRICHMENT_WEIGHT_FRACTION;
            case FIELD_CONVERSION_GAIN_K_INF:
                return 0.0;
            default:
                throw new IllegalArgumentException(field + " is not an optional field");
        }
    }

    // ---------------------------------------------------------------
    // Map form — what the mod's Codec transports
    // ---------------------------------------------------------------

    /**
     * The fields of a fuel type, in canonical order. Optional fields are always
     * written, so an encoded entry is a complete worked example a pack author
     * can copy and edit.
     */
    public static Map<String, Double> encode(FuelType type) {
        if (type == null) {
            throw new IllegalArgumentException("fuel type must not be null");
        }
        Map<String, Double> fields = new LinkedHashMap<>();
        fields.put(FIELD_K_INF_BASE, type.kInfBase());
        fields.put(FIELD_BETA, type.beta());
        fields.put(FIELD_PROMPT_LIFETIME_SECONDS, type.promptLifetimeSeconds());
        fields.put(FIELD_DEPLETION_RATE, type.depletionKInfPerMwdPerTonne());
        fields.put(FIELD_DOPPLER_COEFF_PER_C, type.dopplerCoeffPerC());
        fields.put(FIELD_GAD_CONTENT, type.gadContentWeightFraction());
        fields.put(FIELD_HEAT_PER_FISSION_MEV, type.heatPerFissionMeV());
        fields.put(FIELD_NOMINAL_ENRICHMENT, type.nominalEnrichmentWeightFraction());
        fields.put(FIELD_CONVERSION_GAIN_K_INF, type.conversionGainKInf());
        return fields;
    }

    /**
     * Builds a fuel type from a datapack entry.
     *
     * @param name   registry name, taken from the entry's file name — never from
     *               inside the file
     * @param fields the flat object of numbers
     * @throws IllegalArgumentException naming the fuel and the field, for a
     *                                  missing required field, an unknown field,
     *                                  a non-finite value, or a value
     *                                  {@link FuelType} rejects as unphysical
     */
    public static FuelType decode(String name, Map<String, ? extends Number> fields) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("fuel type name must not be empty");
        }
        if (fields == null) {
            throw new IllegalArgumentException("fuel type '" + name + "': no fields at all");
        }

        TreeSet<String> unknown = new TreeSet<>(fields.keySet());
        fieldNames().forEach(unknown::remove);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("fuel type '" + name + "': unknown field(s) "
                    + unknown + "; valid fields are " + fieldNames());
        }

        double kInfBase = required(name, fields, FIELD_K_INF_BASE);
        double beta = required(name, fields, FIELD_BETA);
        double promptLifetime = required(name, fields, FIELD_PROMPT_LIFETIME_SECONDS);
        double depletion = required(name, fields, FIELD_DEPLETION_RATE);
        double doppler = required(name, fields, FIELD_DOPPLER_COEFF_PER_C);
        double gad = required(name, fields, FIELD_GAD_CONTENT);
        double heat = required(name, fields, FIELD_HEAT_PER_FISSION_MEV);
        double enrichment = optional(name, fields, FIELD_NOMINAL_ENRICHMENT);
        double conversion = optional(name, fields, FIELD_CONVERSION_GAIN_K_INF);

        // FuelType's compact constructor is the range check. Its messages
        // already name the fuel and the parameter, so they are not re-wrapped.
        return new FuelType(name, kInfBase, beta, promptLifetime, depletion,
                doppler, gad, heat, enrichment, conversion);
    }

    private static double required(String name, Map<String, ? extends Number> fields, String field) {
        Number value = fields.get(field);
        if (value == null) {
            throw new IllegalArgumentException("fuel type '" + name + "': missing required field '"
                    + field + "'. Required: " + REQUIRED_FIELDS);
        }
        return finite(name, field, value.doubleValue());
    }

    private static double optional(String name, Map<String, ? extends Number> fields, String field) {
        Number value = fields.get(field);
        return value == null ? defaultValueOf(field) : finite(name, field, value.doubleValue());
    }

    private static double finite(String name, String field, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("fuel type '" + name + "': field '" + field
                    + "' is " + value + ", which is not a number this model can use");
        }
        return value;
    }

    // ---------------------------------------------------------------
    // JSON text
    //
    // In game, JSON is Minecraft's business: the mod hands a parsed JsonElement
    // to a Codec that calls decode() above. The text form here exists so the
    // acceptance suite can round-trip a fuel through exactly the bytes a pack
    // author types, with no Minecraft on the classpath, and so the shipped
    // default entries can be generated from the presets rather than transcribed
    // by hand. It is a strict reader for the flat-object-of-numbers contract
    // and deliberately implements no more JSON than that.
    // ---------------------------------------------------------------

    /** The fuel type as the text of a datapack entry, one field per line. */
    public static String toJson(FuelType type) {
        Map<String, Double> fields = encode(type);
        int width = 0;
        for (String key : fields.keySet()) {
            width = Math.max(width, key.length());
        }
        StringBuilder out = new StringBuilder("{\n");
        int remaining = fields.size();
        for (Map.Entry<String, Double> entry : fields.entrySet()) {
            String quoted = "\"" + entry.getKey() + "\":";
            out.append("  ").append(quoted);
            for (int i = quoted.length(); i < width + 4; i++) {
                out.append(' ');
            }
            out.append(number(entry.getValue()));
            out.append(--remaining > 0 ? ",\n" : "\n");
        }
        return out.append("}\n").toString();
    }

    /**
     * Reads the text of a datapack entry. Same semantics as {@link #decode}; the
     * name still comes from outside the file.
     */
    public static FuelType fromJson(String name, String json) {
        return decode(name, parseFlatObject(name, json));
    }

    /**
     * Parses {@code { "key": number, ... }} and nothing else. Strings, booleans,
     * nulls, arrays and nested objects are all errors, because a fuel entry
     * containing one is a mistake worth reporting rather than something to
     * quietly ignore.
     */
    public static Map<String, Double> parseFlatObject(String where, String json) {
        if (json == null) {
            throw new IllegalArgumentException("fuel type '" + where + "': no text to parse");
        }
        Map<String, Double> fields = new LinkedHashMap<>();
        int i = skipSpace(json, 0);
        i = expect(where, json, i, '{');
        i = skipSpace(json, i);
        if (i < json.length() && json.charAt(i) == '}') {
            return fields;
        }
        while (true) {
            i = skipSpace(json, i);
            int keyStart = expect(where, json, i, '"');
            int keyEnd = json.indexOf('"', keyStart);
            if (keyEnd < 0) {
                throw syntax(where, json, keyStart, "unterminated field name");
            }
            String key = json.substring(keyStart, keyEnd);
            i = skipSpace(json, keyEnd + 1);
            i = expect(where, json, i, ':');
            i = skipSpace(json, i);

            int valueStart = i;
            while (i < json.length() && "+-.0123456789eE".indexOf(json.charAt(i)) >= 0) {
                i++;
            }
            if (i == valueStart) {
                throw syntax(where, json, i, "field '" + key
                        + "' must be a number; a fuel entry is a flat object of numbers");
            }
            String token = json.substring(valueStart, i);
            double value;
            try {
                value = Double.parseDouble(token);
            } catch (NumberFormatException e) {
                throw syntax(where, json, valueStart, "field '" + key + "' has '" + token
                        + "', which is not a number");
            }
            if (fields.put(key, value) != null) {
                throw syntax(where, json, valueStart, "field '" + key + "' appears twice");
            }

            i = skipSpace(json, i);
            if (i < json.length() && json.charAt(i) == ',') {
                i++;
                continue;
            }
            i = expect(where, json, i, '}');
            return fields;
        }
    }

    private static int skipSpace(String json, int i) {
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int expect(String where, String json, int i, char c) {
        if (i >= json.length() || json.charAt(i) != c) {
            throw syntax(where, json, i, "expected '" + c + "'");
        }
        return i + 1;
    }

    private static IllegalArgumentException syntax(String where, String json, int at, String what) {
        int line = 1;
        for (int i = 0; i < Math.min(at, json.length()); i++) {
            if (json.charAt(i) == '\n') {
                line++;
            }
        }
        return new IllegalArgumentException("fuel type '" + where + "': " + what
                + " at line " + line + " (offset " + at + ")");
    }

    /**
     * A double as JSON. {@link Double#toString} is the shortest text that reads
     * back to the identical double, which is what a round trip needs; its
     * uppercase exponent is legal JSON.
     */
    private static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1.0e7) {
            // Whole numbers print as 202.0 rather than 202, which reads better
            // next to 0.006502 and still parses to the same double.
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return Double.toString(value);
    }
}
