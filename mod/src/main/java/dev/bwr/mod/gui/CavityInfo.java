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
                         double riseM, double spillKgPerS, boolean spillsToDrywell, java.util.List<Drain> drains,
                         java.util.List<Check> flood, java.util.List<Fill> fills) {
    /** One line of the flood checklist ({@link dev.bwr.mod.reactor.FloodReadiness}): PASS, FAIL or INFO, and the reason. */
    public record Check(int mark, Component text) {
        public dev.bwr.mod.reactor.FloodReadiness.Mark markEnum() {
            var all = dev.bwr.mod.reactor.FloodReadiness.Mark.values();
            return mark >= 0 && mark < all.length ? all[mark] : dev.bwr.mod.reactor.FloodReadiness.Mark.INFO;
        }
    }

    /** One cavity fill port: where it is, what its line is doing, and what it passes by gravity. */
    public record Fill(int x, int y, int z, int state, double flowKgPerS, double headM, double opening) {
        public Component stateText() {
            return dev.bwr.mod.reactor.CavityFillPortBlockEntity.State.byOrdinal(state).text();
        }
    }

    /** The checklist lines and fill ports that fit on the screen. */
    public static final int MAX_CHECKS = 14;
    public static final int MAX_FILLS = 4;

    public double fillFlowKgPerS() {
        double sum = 0;
        for (Fill f : fills) {
            sum += f.flowKgPerS();
        }
        return sum;
    }

    /** One cavity drain port: where it is, what its line is doing, and what it passes. */
    public record Drain(int x, int y, int z, int state, double flowKgPerS, double depthM, double headM, double opening, int valves) {
        public Component stateText() {
            return dev.bwr.mod.reactor.CavityDrainPortBlockEntity.State.byOrdinal(state).text();
        }
    }

    public static final CavityInfo EMPTY = new CavityInfo(false, false, Component.empty(), false, 0, 0, 0, 0, false, java.util.List.of(),
            java.util.List.of(), java.util.List.of());

    /** The drain ports that fit on the screen. */
    public static final int MAX_DRAINS = 4;

    public double drainFlowKgPerS() {
        double sum = 0;
        for (Drain d : drains) {
            sum += d.flowKgPerS();
        }
        return sum;
    }

    public static CavityInfo capture(ReactorControllerBlockEntity be) {
        if (be == null || !be.isFormed() || be.core() == null || be.cavity() == null) {
            return EMPTY;
        }
        var c = be.cavity();
        var vessel = be.core().getPressureVessel();
        double capacity = c.profile() == null ? 0.0 : c.profile().toWell().capacityM3();
        double rise = Math.max(0.0, vessel.getCollapsedLevelIn() - PressureVessel.FLANGE_LEVEL_IN) * PressureVessel.METRES_PER_INCH;
        var drains = new java.util.ArrayList<Drain>();
        for (var port : be.cavityDrains()) {
            if (drains.size() >= MAX_DRAINS) {
                break;
            }
            var p = port.getBlockPos();
            drains.add(new Drain(p.getX(), p.getY(), p.getZ(), port.state().ordinal(), port.flowKgPerS(), port.depthM(), port.headM(),
                    port.lineOpening(), port.valveCount()));
        }
        var checks = new java.util.ArrayList<Check>();
        for (var item : dev.bwr.mod.reactor.FloodReadiness.of(be)) {
            if (checks.size() >= MAX_CHECKS) {
                break;
            }
            checks.add(new Check(item.mark().ordinal(), item.text()));
        }
        var fills = new java.util.ArrayList<Fill>();
        for (var port : be.cavityFills()) {
            if (fills.size() >= MAX_FILLS) {
                break;
            }
            var p = port.getBlockPos();
            fills.add(new Fill(p.getX(), p.getY(), p.getZ(), port.state().ordinal(), port.flowKgPerS(), port.headM(), port.lineOpening()));
        }
        return new CavityInfo(true, c.present(), c.describe(), c.wellUsable(), vessel.getWellWaterM3(), capacity, rise,
                vessel.getWellSpillKgPerS(), !c.wellUsable(), java.util.List.copyOf(drains), java.util.List.copyOf(checks), java.util.List.copyOf(fills));
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
        b.writeVarInt(drains.size());
        for (Drain d : drains) {
            b.writeInt(d.x());
            b.writeInt(d.y());
            b.writeInt(d.z());
            b.writeVarInt(d.state());
            b.writeDouble(d.flowKgPerS());
            b.writeDouble(d.depthM());
            b.writeDouble(d.headM());
            b.writeDouble(d.opening());
            b.writeVarInt(d.valves());
        }
        b.writeVarInt(flood.size());
        for (Check c : flood) {
            b.writeVarInt(c.mark());
            b.writeUtf(Component.Serializer.toJson(c.text(), registries), 8192);
        }
        b.writeVarInt(fills.size());
        for (Fill f : fills) {
            b.writeInt(f.x());
            b.writeInt(f.y());
            b.writeInt(f.z());
            b.writeVarInt(f.state());
            b.writeDouble(f.flowKgPerS());
            b.writeDouble(f.headM());
            b.writeDouble(f.opening());
        }
    }

    public static CavityInfo read(FriendlyByteBuf b, HolderLookup.Provider registries) {
        if (!b.readBoolean()) {
            return EMPTY;
        }
        boolean present = b.readBoolean();
        Component seal = Component.Serializer.fromJson(b.readUtf(8192), registries);
        boolean usable = b.readBoolean();
        double water = b.readDouble();
        double capacity = b.readDouble();
        double rise = b.readDouble();
        double spill = b.readDouble();
        boolean drywell = b.readBoolean();
        int n = Math.min(b.readVarInt(), MAX_DRAINS);
        var drains = new java.util.ArrayList<Drain>();
        for (int i = 0; i < n; i++) {
            drains.add(new Drain(b.readInt(), b.readInt(), b.readInt(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble(),
                    b.readDouble(), b.readVarInt()));
        }
        int nc = Math.min(b.readVarInt(), MAX_CHECKS);
        var checks = new java.util.ArrayList<Check>();
        for (int i = 0; i < nc; i++) {
            int mark = b.readVarInt();
            Component text = Component.Serializer.fromJson(b.readUtf(8192), registries);
            checks.add(new Check(mark, text == null ? Component.empty() : text));
        }
        int nf = Math.min(b.readVarInt(), MAX_FILLS);
        var fills = new java.util.ArrayList<Fill>();
        for (int i = 0; i < nf; i++) {
            fills.add(new Fill(b.readInt(), b.readInt(), b.readInt(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readDouble()));
        }
        return new CavityInfo(true, present, seal == null ? Component.empty() : seal, usable, water, capacity, rise, spill, drywell,
                java.util.List.copyOf(drains), java.util.List.copyOf(checks), java.util.List.copyOf(fills));
    }
}
