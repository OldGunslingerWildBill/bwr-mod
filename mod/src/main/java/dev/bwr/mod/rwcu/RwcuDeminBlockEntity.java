package dev.bwr.mod.rwcu;

import dev.bwr.core.chemistry.FilterDemineralizer;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.reactor.ReactorStateNbt;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * The filter-demineraliser: the resin bed ({@link FilterDemineralizer}) and its radiation. The pump pushes water through
 * it; the bed fills with what it removes. What it holds is a gamma source (the survey meter reads it) and, replaced, it
 * comes out as {@link SpentResinItem}. No ticker: the pump's tick steps it.
 */
public class RwcuDeminBlockEntity extends BlockEntity implements RwcuUnit {
    /** What the vessel wall and the water in it let through of the captured crud's gamma. */
    public static final double TRANSMISSION = 0.05;
    private final FilterDemineralizer bed = new FilterDemineralizer();
    private long processedTick = Long.MIN_VALUE;
    private double flowKgPerS, inletC;
    private double emittedWatts = -1;

    public RwcuDeminBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.RWCU_DEMIN.get(), pos, state);
    }

    public FilterDemineralizer bed() { return bed; }
    public boolean hasBed() { return bed.bedPresent(); }
    public boolean overTemperature() { return level != null && level.getGameTime() - processedTick <= 2 && bed.overTemperature(); }
    public boolean flowing() { return level != null && level.getGameTime() - processedTick <= 2 && flowKgPerS > 0; }

    public FilterDemineralizer.Result process(double flowKgPerS, double inletC, double ionGPerKg, double crudGPerKg, double bqPerG, double dt) {
        this.flowKgPerS = flowKgPerS;
        this.inletC = inletC;
        processedTick = level == null ? 0 : level.getGameTime();
        var result = bed.process(flowKgPerS, inletC, ionGPerKg, crudGPerKg, bqPerG, dt);
        refreshRadiation(false);
        setChanged();
        return result;
    }

    /** The pump is not moving water through it. */
    public void idle() { flowKgPerS = 0; }

    /** Puts a fresh bed in: the old one comes out as spent resin. A bed that has taken nothing is left alone. */
    public void changeBed(Player player, ItemStack resin) {
        if (bed.bedPresent() && bed.ionLoadG() <= 0 && bed.crudLoadG() <= 0 && bed.damage() <= 0) {
            player.displayClientMessage(Component.literal("The resin bed is still fresh."), true);
            return;
        }
        if (bed.bedPresent()) {
            double[] spent = bed.spentBed();
            ItemStack out = SpentResinItem.make(spent[2], spent[0], spent[1], 1);
            if (!player.getInventory().add(out)) player.drop(out, false);
        }
        bed.replaceBed();
        if (!player.getAbilities().instabuild) resin.shrink(1);
        refreshRadiation(true);
        setChanged();
        player.displayClientMessage(Component.literal("Resin bed replaced."), true);
    }

    /** Re-rates the gamma source when it has moved by more than a few per cent (never per tick). */
    public void refreshRadiation(boolean force) {
        if (level == null || level.isClientSide()) return;
        double w = bed.gammaWatts();
        if (!force && emittedWatts >= 0 && Math.abs(w - emittedWatts) <= 0.03 * Math.max(w, emittedWatts) && !(w > 0 && emittedWatts <= 0)) return;
        emittedWatts = w;
        RadiationSources.emit(level, this, Vec3.atCenterOf(worldPosition), w, TRANSMISSION);
    }

    @Override public void onLoad() {
        super.onLoad();
        refreshRadiation(true);
    }

    @Override public void setRemoved() {
        if (level != null) RadiationSources.clear(level, this);
        super.setRemoved();
    }

    @Override public void onChunkUnloaded() {
        if (level != null) RadiationSources.clear(level, this);
        super.onChunkUnloaded();
    }

    private static String n(double v, int d) { return String.format(Locale.ROOT, "%." + d + "f", v); }

    @Override public Readout readout() {
        boolean hot = overTemperature();
        boolean on = flowing();
        int headStatus = !bed.bedPresent() ? 3 : hot ? 3 : bed.exhaustion() >= 1.0 ? 3 : bed.exhaustion() > 0.8 ? 2 : on ? 1 : 0;
        String head = !bed.bedPresent() ? "No resin bed" : hot ? "Resin over 140 F: being destroyed"
                : bed.exhaustion() >= 1.0 ? "Resin exhausted: replace the bed" : on ? "Cleaning" : "Idle";
        return new Readout(head, headStatus, List.of(
                new Line("Resin used", n(100 * bed.exhaustion(), 1), "%", bed.exhaustion() > 0.8 ? 2 : 0),
                new Line("Ionic impurity held", n(bed.ionLoadG(), 2), "g of " + (int) FilterDemineralizer.ION_CAPACITY_G, 0),
                new Line("Crud held", n(bed.crudLoadG(), 2), "g of " + (int) FilterDemineralizer.CRUD_CAPACITY_G, 0),
                new Line("Heat damage", n(100 * bed.damage(), 1), "%", bed.damage() > 0 ? 3 : 0),
                new Line("Water through the bed", on ? n(flowKgPerS, 1) : "-", "kg/s", 0),
                new Line("Water at the bed", on ? n(inletC, 1) : "-", "C", hot ? 3 : 0),
                new Line("Removal efficiency", on ? n(100 * bed.lastEfficiency(), 0) : "-", "%", 0),
                new Line("Radioactivity held", String.format(Locale.ROOT, "%.2e", bed.activityBq()), "Bq", bed.activityBq() > 0 ? 2 : 0),
                new Line("Gamma power", n(bed.gammaWatts(), 3), "W", 0)));
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // A fresh bed is the default and writes nothing.
        double[] a = bed.toArray();
        if (a[0] < 0.5 || a[1] > 0 || a[2] > 0 || a[3] > 0 || a[4] > 0) ReactorStateNbt.putDoubles(tag, "Bed", a);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Bed")) bed.fromArray(ReactorStateNbt.getDoubles(tag, "Bed"));
        else bed.replaceBed();
    }
}
