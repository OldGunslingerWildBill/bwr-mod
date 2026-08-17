package dev.bwr.mod.reactor;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of a structure validation attempt.
 *
 * <p>Failures carry the coordinate that caused them. {@code SPEC.md} section 3.3
 * is explicit that a missing control rod drive must name the exact position —
 * error messages are how a player learns the geometry, so a bare "invalid
 * multiblock" is a bug in this class, not a terse convenience.
 *
 * <p>Distinguishes hard failures, which stop the multiblock forming, from
 * degradations, which do not. An incomplete core spray ring is degraded rather
 * than invalid ({@code SPEC.md} section 9.4): partial coverage is a far more
 * interesting failure mode than binary valid/invalid, and it means damage to
 * one ring segment has graded consequences.
 */
public final class ValidationResult {

    /** One thing that is wrong, and where. */
    public record Problem(BlockPos where, String message) {
        @Override
        public String toString() {
            return where == null
                    ? message
                    : message + " at " + where.getX() + ", " + where.getY() + ", " + where.getZ();
        }
    }

    private final List<Problem> failures = new ArrayList<>();
    private final List<Problem> degradations = new ArrayList<>();

    /**
     * A hard failure: the multiblock will not form.
     *
     * <p>The position is <b>copied</b>, and that copy is load-bearing.
     * {@link ReactorStructure}'s scans walk the vessel with a single
     * {@link BlockPos.MutableBlockPos} cursor rather than allocating a position
     * per block — a 21x21x21 interior is nine thousand of them per sweep, several
     * times a minute — and a {@link Problem} outlives the loop that produced it:
     * the controller holds the whole result in {@code lastValidation} and prints
     * it whenever a player right-clicks. Storing the cursor itself would make
     * every problem in the list report whichever block the walk happened to
     * finish on. {@link BlockPos#immutable()} returns {@code this} for a position
     * that is already immutable, so this costs nothing for callers that hand over
     * a real one.
     */
    public ValidationResult fail(BlockPos where, String message) {
        failures.add(new Problem(where == null ? null : where.immutable(), message));
        return this;
    }

    public ValidationResult fail(String message) {
        return fail(null, message);
    }

    /**
     * A degradation: the multiblock forms, but something works less well. The
     * position is copied for the reason {@link #fail(BlockPos, String)} gives.
     */
    public ValidationResult degrade(BlockPos where, String message) {
        degradations.add(new Problem(where == null ? null : where.immutable(), message));
        return this;
    }

    public ValidationResult degrade(String message) {
        return degrade(null, message);
    }

    public boolean isValid() {
        return failures.isEmpty();
    }

    public boolean isDegraded() {
        return !degradations.isEmpty();
    }

    public List<Problem> failures() {
        return Collections.unmodifiableList(failures);
    }

    public List<Problem> degradations() {
        return Collections.unmodifiableList(degradations);
    }

    /** First failure, for the one-line status the controller shows. */
    public String firstFailure() {
        return failures.isEmpty() ? null : failures.get(0).toString();
    }

    /** Every problem as human-readable lines, failures first. */
    public List<String> messages() {
        List<String> out = new ArrayList<>(failures.size() + degradations.size());
        for (Problem p : failures) {
            out.add("FAULT: " + p);
        }
        for (Problem p : degradations) {
            out.add("DEGRADED: " + p);
        }
        return out;
    }

    /** Cap on how many problems of one kind are worth reporting to a player. */
    public static final int REPORT_LIMIT = 8;

    /** True once enough failures have been found that more would not help. */
    public boolean hasEnoughFailures() {
        return failures.size() >= REPORT_LIMIT;
    }
}
