package dev.bwr.mod.gui;

import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.mod.reactor.CavitySeal;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

/**
 * What the INFO tab says about the cavity seal and the well: the survey's verdict (present, or absent and where the wall is too
 * far), the water over the flange and the water leaving the open vessel. Read-only; it commands nothing.
 */
public record CavityInfo(boolean known, boolean present, Component seal, boolean wellUsable, double wellWaterM3, double wellCapacityM3,
                         double riseM, double spillKgPerS, boolean spillsToDrywell) {
    public static final CavityInfo EMPTY = new CavityInfo(false, false, Component.empty(), false, 0, 0, 0, 0, false);

    public static CavityInfo capture(ReactorControllerBlockEntity be) {
        if (be == null || !be.isFormed() || be.core() == null || be.cavity() == null) {
            return EMPTY;
        }
        var c = be.cavity();
        var vessel = be.core().getPressureVessel();
        double capacity = c.profile() == null ? 0.0 : c.profile().toWell().capacityM3();
        double rise = Math.max(0.0, vessel.getCollapsedLevelIn() - PressureVessel.FLANGE_LEVEL_IN) * PressureVessel.METRES_PER_INCH;
        return new CavityInfo(true, c.present(), c.describe(), c.wellUsable(), vessel.getWellWaterM3(), capacity, rise,
                vessel.getWellSpillKgPerS(), !c.wellUsable());
    }

    public void write(FriendlyByteBuf b, HolderLookup.Provider registries) {
        b.writeBoolean(known);
        if (!known) {
            return;
        }
        b.writeBoolean(present);
        b.writeUtf(Component.Serializer.toJson(seal, registries), 8192);
        b.writeBoolean(wellUsable);
        b.writeDouble(wellWaterM3);
        b.writeDouble(wellCapacityM3);
        b.writeDouble(riseM);
        b.writeDouble(spillKgPerS);
        b.writeBoolean(spillsToDrywell);
    }

    public static CavityInfo read(FriendlyByteBuf b, HolderLookup.Provider registries) {
        if (!b.readBoolean()) {
            return EMPTY;
        }
        boolean present = b.readBoolean();
        Component seal = Component.Serializer.fromJson(b.readUtf(8192), registries);
        return new CavityInfo(true, present, seal == null ? Component.empty() : seal, b.readBoolean(), b.readDouble(), b.readDouble(),
                b.readDouble(), b.readDouble(), b.readBoolean());
    }
}
