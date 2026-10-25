package dev.bwr.mod.rwcu;

import java.util.List;

/** What a passive skid unit shows: one headline and a list of readings, for the status screen and Computer readouts. */
public interface RwcuUnit {
    /** Status 0 none, 1 normal, 2 caution, 3 alarm. */
    record Line(String label, String value, String unit, int status) {}
    record Readout(String headline, int headlineStatus, List<Line> lines) {}
    Readout readout();
}
