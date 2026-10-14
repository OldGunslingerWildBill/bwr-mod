package dev.bwr.mod.panel.model;

import dev.bwr.mod.panel.component.ComponentData;
import dev.bwr.mod.panel.component.LampData;
import dev.bwr.mod.panel.component.PushbuttonData;
import dev.bwr.mod.panel.component.RotarySelectorData;
import dev.bwr.mod.panel.component.SetpointKnobData;

/**
 * Which colour a model part is recoloured with, or none. Models keep their own colours except where the
 * modeller tagged a part with a tint (the cap of a pushbutton, a lamp lens): such a part takes the colour the
 * player configured on the component, and the tint's palette colour while they have not chosen one. A pointer
 * takes the variant's pointer colour (the red pointer of a model that has no red twin). Flags and lamp glow are
 * decided per frame by the renderer.
 */
public final class Recolour {
    private Recolour() {
    }

    /** No recolouring: keep the colours the model was authored with. */
    public static final int KEEP = -1;

    /**
     * The colour {@code part} is drawn with on a component whose data is {@code data}, or {@link #KEEP}.
     *
     * @param needleRgb the pointer colour the style table forces, or {@link #KEEP}
     */
    public static int baseFor(BakedPanelModel.Part part, ComponentData data, int needleRgb) {
        if (part.role == BakedPanelModel.Role.NEEDLE && needleRgb >= 0) {
            return needleRgb;
        }
        if (part.tint != null && part.role != BakedPanelModel.Role.FLAG) {
            if (part.role == BakedPanelModel.Role.LAMP && "white".equalsIgnoreCase(part.tint) && data instanceof LampData lamp) {
                // A lens modelled neutral (the dome indicating lights) is the lamp's colour whatever it is, the default
                // red included: there is no "model default" colour for it to stand for, and left alone it reads white.
                return lamp.color;
            }
            int configured = configuredColour(data);
            return configured >= 0 ? configured : Tints.rgb(part.tint, KEEP);
        }
        return KEEP;
    }

    /**
     * The colour the player chose on the component, or {@link #KEEP} when its type has none or the colour is
     * still the default: the default cap, lens or knob colour stands for "whatever this model is by default" (a
     * black flush button, a green extended one, a red mushroom), so only a deliberate change recolours a model.
     */
    public static int configuredColour(ComponentData data) {
        if (data instanceof LampData lamp) {
            return chosen(lamp.color, LampData.DEFAULT_COLOR);
        }
        if (data instanceof PushbuttonData button) {
            return chosen(button.color, PushbuttonData.DEFAULT_COLOR);
        }
        if (data instanceof SetpointKnobData knob) {
            return chosen(knob.color, SetpointKnobData.DEFAULT_COLOR);
        }
        if (data instanceof RotarySelectorData selector) {
            return chosen(selector.color, RotarySelectorData.DEFAULT_COLOR);
        }
        return KEEP;
    }

    private static int chosen(int color, int defaultColor) {
        return color == defaultColor ? KEEP : color;
    }
}
