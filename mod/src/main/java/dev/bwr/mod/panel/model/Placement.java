package dev.bwr.mod.panel.model;

/**
 * How a model's inch-sized footprint is laid onto a component's cell rectangle: one uniform scale (cells per
 * inch) so the footprint fits entirely inside the rectangle, and an offset that centres it on the axis that has
 * room to spare. The same scale applies to elevation, so a model keeps its real proportions.
 */
public final class Placement {
    /** Cells per inch. */
    public final float scale;
    /** Cell position of the footprint's top-left corner within the component. */
    public final float offsetX;
    public final float offsetY;

    private Placement(float scale, float offsetX, float offsetY) {
        this.scale = scale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    /** Fits a {@code footprintW x footprintH} inch model into {@code cellsW x cellsH} cells. */
    public static Placement fit(float footprintW, float footprintH, float cellsW, float cellsH) {
        float scale = Math.min(cellsW / footprintW, cellsH / footprintH);
        return new Placement(scale, (cellsW - footprintW * scale) / 2.0f, (cellsH - footprintH * scale) / 2.0f);
    }

    public static Placement fit(BakedPanelModel model, float cellsW, float cellsH) {
        return fit(model.footprintW, model.footprintH, cellsW, cellsH);
    }

    /** Cell column of a model x (inches). */
    public float x(float inches) {
        return offsetX + inches * scale;
    }

    /** Cell row of a model y (inches). */
    public float y(float inches) {
        return offsetY + inches * scale;
    }

    /** Elevation (1/16 block) of a model z (inches). */
    public float z(float inches) {
        return inches * scale;
    }

    /** Length in cells of a model length in inches. */
    public float length(float inches) {
        return inches * scale;
    }
}
