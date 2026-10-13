package dev.bwr.mod.panel.model;

/**
 * How a model's inch-sized footprint is laid onto a component's cell rectangle: one uniform scale (cells per
 * inch) so the footprint fits entirely inside the rectangle, and an offset that centres it on the axis that has
 * room to spare. The same scale applies to elevation, so a model keeps its real proportions.
 *
 * <h3>Sloped panels</h3>
 * A cell is one column wide but {@code rowAspect} columns long down the face of a benchboard (1.41) or console
 * (1.04), so a rectangle of cells is not the same shape in the world. The scale is therefore the same in real
 * lengths on all three axes: a model inch spans {@code scale} cells across, {@code scale / rowAspect} rows down
 * the face and {@code scale} units of elevation, and the footprint is fitted into the rectangle's real size
 * ({@code cellsW} columns by {@code cellsH * rowAspect} columns). On a vertical board {@code rowAspect} is 1 and
 * this is the plain uniform fit.
 */
public final class Placement {
    /** Cells across per inch, which is also the real length per inch in column widths, and 1/16 block of elevation per inch. */
    public final float scale;
    /** Rows down the face per inch: {@link #scale} divided by the row aspect. */
    public final float rowScale;
    /** Cell position of the footprint's top-left corner within the component. */
    public final float offsetX;
    public final float offsetY;

    private Placement(float scale, float rowScale, float offsetX, float offsetY) {
        this.scale = scale;
        this.rowScale = rowScale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    /**
     * Fits a {@code footprintW x footprintH} inch model into {@code cellsW x cellsH} cells on a face whose rows
     * are {@code rowAspect} times as long as its columns are wide.
     */
    public static Placement fit(float footprintW, float footprintH, float cellsW, float cellsH, float rowAspect) {
        float scale = Math.min(cellsW / footprintW, cellsH * rowAspect / footprintH);
        float rowScale = scale / rowAspect;
        return new Placement(scale, rowScale, (cellsW - footprintW * scale) / 2.0f, (cellsH - footprintH * rowScale) / 2.0f);
    }

    /** {@link #fit(float, float, float, float, float)} on a vertical board (square cells). */
    public static Placement fit(float footprintW, float footprintH, float cellsW, float cellsH) {
        return fit(footprintW, footprintH, cellsW, cellsH, 1.0f);
    }

    public static Placement fit(BakedPanelModel model, float cellsW, float cellsH, float rowAspect) {
        return fit(model.footprintW, model.footprintH, cellsW, cellsH, rowAspect);
    }

    public static Placement fit(BakedPanelModel model, float cellsW, float cellsH) {
        return fit(model, cellsW, cellsH, 1.0f);
    }

    /** Cell column of a model x (inches). */
    public float x(float inches) {
        return offsetX + inches * scale;
    }

    /** Cell row of a model y (inches). */
    public float y(float inches) {
        return offsetY + inches * rowScale;
    }

    /** Elevation (1/16 block) of a model z (inches). */
    public float z(float inches) {
        return inches * scale;
    }

    /** Length in column widths of a model length in inches (text heights; true on every panel type). */
    public float length(float inches) {
        return inches * scale;
    }
}
