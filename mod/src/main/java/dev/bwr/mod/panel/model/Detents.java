package dev.bwr.mod.panel.model;

/**
 * Handle angles of a rotary selector's detents. Selector handles are modelled pointing at 12 o'clock, and the
 * legend of position k (from 0) sits at the label anchor {@code pos(k+1)}, so the handle of position k turns to
 * the bearing of that anchor as seen from the handle's pivot, whatever the family's numbering: GE puts pos1 at 9
 * o'clock and counts clockwise (position k at (k-2) x 45 degrees), Westinghouse starts at 7:30 with pos4 at 12.
 * A model without an anchor for every position falls back to {@link Poses#detentAngle}, symmetric about the
 * modelled pose, so a handle never points at nothing.
 */
public final class Detents {
    private Detents() {
    }

    /** Angles (clockwise degrees from the modelled pose) for positions 0..count-1; never null. */
    public static float[] angles(BakedPanelModel model, int count) {
        int n = Math.max(1, count);
        float[] angles = new float[n];
        BakedPanelModel.Part handle = handle(model);
        boolean derived = handle != null;
        boolean outer = outerPair(model, n);
        for (int i = 0; i < n && derived; i++) {
            BakedPanelModel.Label anchor = model.label("pos" + (outer ? (i == 0 ? 1 : 3) : i + 1));
            if (anchor == null) {
                derived = false;
                break;
            }
            angles[i] = wrap(bearing(anchor.x - handle.pivotX, anchor.y - handle.pivotY));
        }
        if (!derived) {
            for (int i = 0; i < n; i++) {
                angles[i] = Poses.detentAngle(i, n);
            }
        }
        return angles;
    }

    /**
     * Whether a selector of {@code count} positions uses the outer two legends of {@code model}: the model has
     * exactly three (the oiltight selectors' OFF, AUTO, OPEN), so a two position one turns between its left and its
     * right legend and not to the middle one.
     */
    public static boolean outerPair(BakedPanelModel model, int count) {
        return count == 2 && model.label("pos3") != null && model.label("pos4") == null;
    }

    /** The part that turns about the shaft: the first part called {@code handle}, else the first pivoted part. */
    static BakedPanelModel.Part handle(BakedPanelModel model) {
        BakedPanelModel.Part named = model.part("handle");
        if (named != null && named.hasPivot) {
            return named;
        }
        for (int i = 0; i < model.partCount(); i++) {
            if (model.part(i).hasPivot) {
                return model.part(i);
            }
        }
        return null;
    }

    /** Degrees clockwise from straight up for an offset (x right, y down). */
    static float bearing(float dx, float dy) {
        return (float) Math.toDegrees(Math.atan2(dx, -dy));
    }

    /** {@code degrees} brought into (-180, 180]. */
    static float wrap(float degrees) {
        float d = degrees % 360.0f;
        if (d > 180.0f) {
            d -= 360.0f;
        } else if (d <= -180.0f) {
            d += 360.0f;
        }
        return d;
    }
}
