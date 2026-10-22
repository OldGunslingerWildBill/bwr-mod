package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Synchroscope ({@code bwr:synchroscope}): a round meter whose pointer shows the phase angle between the generator and the
 * grid, and two synchronising lamps. 12 o'clock is in phase; the pointer is clockwise (FAST) when the generator leads, and
 * anticlockwise (SLOW) when it lags; a pointer that turns steadily is slip.
 *
 * <h3>Inputs</h3>
 * <ul>
 *   <li>{@code phase}: the phase angle in degrees, -180 to +180 (the grid tie's {@code phase_degrees} signal). The
 *       pointer follows it by the short way round.</li>
 *   <li>{@code slip}: the slip frequency in Hz, positive when the generator is faster (the grid tie's {@code slip_hz}).
 *       With no {@code phase} signal the pointer turns by itself at the slip rate (a full turn a second per hertz): on the
 *       real instrument the pointer is the integrated slip. With both, the pointer dead reckons with the slip between phase
 *       samples and is pulled to the phase.</li>
 * </ul>
 * With neither, the pointer is parked at 12 o'clock behind a red flag. The two lamps are the synchronising lamps wired across the
 * open breaker (the dark lamp method): both follow the same curve, dark in phase and brightest at 180 degrees out of phase,
 * with a continuous brightness in between that pulses at the slip frequency (see {@link SyncMath#darkLamp}); both are dark when
 * there is no signal or the breaker is closed.
 *
 * <p>No outputs; nothing to operate. It displays two measurements and decides nothing: whether to close a breaker is the
 * player's call.
 */
public final class SynchroscopeType extends ComponentType {
    public static final String ID_PATH = "synchroscope";
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(12, 16), Footprint.of(15, 20), Footprint.of(20, 26),
            // The real tail: twice the size first (a new item starts there), then the exact real size. A 6 in round
            // synchroscope with a strip of two lamps under it is 3 x 4; the 4.3 in one is 2 x 3.
            Footprint.of(6, 8), Footprint.of(3, 4), Footprint.of(4, 6), Footprint.of(2, 3));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.bool("Reverse", "component.bwr.field.sync_reverse"),
            ConfigField.color("Lamp1Color", "component.bwr.field.sync_lamp_1"),
            ConfigField.color("Lamp2Color", "component.bwr.field.sync_lamp_2"));
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("phase"), PortSpec.of("slip"));
    public static final int IN_PHASE = 0;
    public static final int IN_SLIP = 1;

    SynchroscopeType() {
        super(BwrMod.id(ID_PATH));
    }

    @Override
    public int realSizes() {
        return 4;
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new SynchroscopeData();
    }

    @Override
    public List<ConfigField> configSchema() {
        return SCHEMA;
    }

    @Override
    public List<PortSpec> inputPorts(ComponentData data) {
        return INPUTS;
    }

    @Override
    public String labelPrefix() {
        return "SY";
    }
}
