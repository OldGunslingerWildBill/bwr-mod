package dev.bwr.core.fuel;

/** Non-fuel cassettes. Exposure is equivalent seconds at rated local fission flux, not wall-clock age. */
public final class CoreInsert {
    public enum Kind {
        BORON_ABSORBER(2.0, 0, 0), HAFNIUM_ABSORBER(1.4, 0, 0),
        CALIFORNIUM_SOURCE(0.02, 2.0e-11, 0), AMERICIUM_BERYLLIUM_SOURCE(0.02, 8.0e-12, 0),
        // Multi-day gameplay batches, aligned with long fuel cycles rather than real isotope kinetics.
        ANTIMONY_BERYLLIUM_SOURCE(0.04, 1.2e-11, 48 * 3600.0),
        COBALT_TARGET(0.3, 0, 72 * 3600.0), TRITIUM_TARGET(0.5, 0, 168 * 3600.0),
        SILICON_TARGET(0.05, 0, 24 * 3600.0);

        public final double absorptionRatio, sourcePerSecond, exposureSeconds;
        Kind(double absorption, double source, double exposure) {
            absorptionRatio = absorption; sourcePerSecond = source; exposureSeconds = exposure;
        }
        public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
        public boolean target() { return this == COBALT_TARGET || this == TRITIUM_TARGET || this == SILICON_TARGET; }
    }

    private final Kind kind;
    private double exposureSeconds;
    public CoreInsert(Kind kind, double exposure) {
        this.kind = java.util.Objects.requireNonNull(kind);
        exposureSeconds = Double.isFinite(exposure) ? Math.max(0, Math.min(kind.exposureSeconds, exposure)) : 0;
    }
    public Kind kind() { return kind; }
    public double exposureSeconds() { return exposureSeconds; }
    public double progress() { return kind.exposureSeconds > 0 ? exposureSeconds / kind.exposureSeconds : 0; }
    public boolean complete() { return kind.target() && progress() >= 1; }
    public double sourcePerSecond() {
        return kind.sourcePerSecond * (kind == Kind.ANTIMONY_BERYLLIUM_SOURCE ? progress() : 1);
    }
    public void irradiate(double localFluxFraction, double seconds) {
        if (!Double.isFinite(localFluxFraction) || !Double.isFinite(seconds) || localFluxFraction <= 0 || seconds <= 0) return;
        exposureSeconds = Math.min(kind.exposureSeconds, exposureSeconds + localFluxFraction * seconds);
    }
}
