package dev.bwr.mod.panel.wiring;

import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.signal.Quantity;
import dev.bwr.mod.signal.SignalSpec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Finds bindings that disagree with the component's {@code Measures} setting. Old saves can hold such a binding (a level
 * gauge wired to a power signal before the setting existed); it still loads and keeps working, and nothing unbinds it.
 * The server only tells the client, with the full state, so the wrench screen can show a warning.
 */
public final class MeasuresCheck {
    private MeasuresCheck() {
    }

    /** The quantity of the signal input {@code input} is line-linked to when it is not the quantity measured, else -1. */
    public static int mismatch(ServerLevel level, PanelComponent component, int input) {
        Quantity wanted = component.data().measures(input);
        if (wanted == null || !(component.source(input) instanceof PlantSource plant)) {
            return -1;
        }
        SignalSpec spec = WiringOps.specOf(level, plant.device(), plant.key());
        if (spec == null) {
            return -1;
        }
        Quantity actual = Quantity.of(spec);
        return actual == wanted ? -1 : actual.ordinal();
    }

    /** Adds a {@code Mismatch} list to a component's network form when any input disagrees. */
    public static void annotate(Level level, PanelComponent component, CompoundTag network) {
        if (!(level instanceof ServerLevel server) || component.type() == null) {
            return;
        }
        int[] found = new int[component.inputCount()];
        boolean any = false;
        for (int i = 0; i < found.length; i++) {
            found[i] = mismatch(server, component, i);
            any |= found[i] >= 0;
        }
        if (any) {
            network.putIntArray("Mismatch", found);
        }
        if (component.data().powerState() != null) {
            // Power units need to know what each input is wired to; only components that have the setting are annotated.
            int[] quantities = new int[found.length];
            net.minecraft.nbt.ListTag units = new net.minecraft.nbt.ListTag();
            boolean bound = false;
            for (int i = 0; i < quantities.length; i++) {
                SignalSpec spec = component.source(i) instanceof PlantSource plant
                        ? WiringOps.specOf(server, plant.device(), plant.key()) : null;
                quantities[i] = spec == null ? -1 : Quantity.of(spec).ordinal();
                units.add(net.minecraft.nbt.StringTag.valueOf(spec == null ? "" : spec.unit()));
                bound |= spec != null;
            }
            if (bound) {
                network.putIntArray("BoundQuantity", quantities);
                network.put("BoundUnit", units);
            }
        }
    }
}
