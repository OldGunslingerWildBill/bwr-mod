package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;

/**
 * The sizes the older showcases lay their boards out with. A new item of five indicator types starts at a different
 * size since the alpha.60 size table (pushbutton 4x4, lamp 3x3, annunciator window 8x6, annunciator control module 32x8,
 * RPS trip 12x6), but these boards were laid out on a grid of the previous defaults (2x2, 2x2, 2x2, 4x2, 4x2), so they keep
 * placing those, which still place (they are retired from the picker, not removed). The new sizes have their own board,
 * {@link PanelIndicatorSizesShowcase}. Every other type is placed at the size a new item starts at.
 */
final class ShowcaseSizes {
    private ShowcaseSizes() {
    }

    static Footprint layoutSize(ComponentType type) {
        if (type == ComponentTypes.PUSHBUTTON || type == ComponentTypes.LAMP || type == ComponentTypes.ANNUNCIATOR) {
            return Footprint.of(2, 2);
        }
        if (type == ComponentTypes.ANNUNCIATOR_CONTROL || type == ComponentTypes.RPS_TRIP) {
            return Footprint.of(4, 2);
        }
        return type.footprints().get(type.realDefaultIndex());
    }
}
