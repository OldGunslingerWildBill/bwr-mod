package dev.bwr.mod.panel.trim;

/**
 * Every control-room trim block, one per registry name. Most are decoration: no block entity, no mounting face. The
 * header strip, the canopy and the two chamfer corners ({@link #mountType()}) are {@link MountableTrimBlock}s whose
 * angled face takes components. The geometry of each piece
 * (what the collision shape and the model are built from) is in {@link TrimGeometry}; the models are
 * {@code art/models/control_panel_trim/build_trim.py}'s.
 */
public enum TrimPiece {
    END_CAP_FLAT("control_panel_end_cap_flat"),
    END_CAP_BENCH("control_panel_end_cap_bench"),
    END_CAP_CONSOLE("control_panel_end_cap_console"),
    END_CAP_ROUND("control_panel_end_cap_round"),
    HEADER_STRIP("control_panel_header_strip"),
    HEADER_CANOPY("control_panel_header_canopy"),
    HEADER_VENT("control_panel_header_vent"),
    PLINTH_KICK("control_panel_plinth_kick"),
    PLINTH_APRON("control_panel_plinth_apron"),
    PLINTH_BASE("control_panel_plinth_base"),
    CORNER_VERTICAL_INSIDE("control_panel_corner_vertical_inside"),
    CORNER_VERTICAL_OUTSIDE("control_panel_corner_vertical_outside"),
    CORNER_VERTICAL_CHAMFER("control_panel_corner_vertical_chamfer"),
    CORNER_BENCH_INSIDE("control_panel_corner_bench_inside"),
    CORNER_BENCH_OUTSIDE("control_panel_corner_bench_outside"),
    CORNER_BENCH_CHAMFER("control_panel_corner_bench_chamfer");

    private final String registryName;

    TrimPiece(String registryName) {
        this.registryName = registryName;
    }

    public String registryName() {
        return registryName;
    }

    /**
     * The mounting style of a piece whose angled face takes components ({@link MountableTrimBlock}), or {@code null}
     * for the pieces that stay decoration.
     */
    public dev.bwr.mod.panel.PanelType mountType() {
        return switch (this) {
            case HEADER_STRIP, HEADER_CANOPY -> dev.bwr.mod.panel.PanelType.HEADER_TILT;
            case CORNER_VERTICAL_CHAMFER -> dev.bwr.mod.panel.PanelType.CHAMFER_VERTICAL;
            case CORNER_BENCH_CHAMFER -> dev.bwr.mod.panel.PanelType.CHAMFER_BENCH;
            default -> null;
        };
    }

    /** An end cap closes one open side of a run and so has a side; every other piece is symmetric. */
    public boolean isEndCap() {
        return this == END_CAP_FLAT || this == END_CAP_BENCH || this == END_CAP_CONSOLE || this == END_CAP_ROUND;
    }
}
