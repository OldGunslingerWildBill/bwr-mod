package dev.bwr.mod.rwcu;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;

/** The regenerative exchanger: it holds no water; the pump reports what flowed through it this tick (readouts only). */
public class RwcuRegenBlockEntity extends BlockEntity implements RwcuUnit {
    private long reportedTick = Long.MIN_VALUE;
    private double hotInC, hotOutC, coldInC, coldOutC, dutyMW;

    public RwcuRegenBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.RWCU_REGEN.get(), pos, state);
    }

    /** Called by the pump each tick it runs. */
    public void report(double hotInC, double hotOutC, double coldInC, double coldOutC, double dutyMW) {
        this.hotInC = hotInC; this.hotOutC = hotOutC; this.coldInC = coldInC; this.coldOutC = coldOutC; this.dutyMW = dutyMW;
        reportedTick = level == null ? 0 : level.getGameTime();
    }

    public boolean active() { return level != null && level.getGameTime() - reportedTick <= 2 && dutyMW > 0; }
    public double hotInC() { return active() ? hotInC : 0; }
    public double hotOutC() { return active() ? hotOutC : 0; }
    public double coldInC() { return active() ? coldInC : 0; }
    public double coldOutC() { return active() ? coldOutC : 0; }
    /** Heat handed from the reactor water to the water going back, MW: what the exchanger recovers. */
    public double dutyMW() { return active() ? dutyMW : 0; }

    private static String n(double v, int d) { return String.format(Locale.ROOT, "%." + d + "f", v); }

    @Override public Readout readout() {
        boolean on = active();
        return new Readout(on ? "Recovering heat" : "No flow", on ? 1 : 0, List.of(
                new Line("Reactor water in (hot side)", on ? n(hotInC, 1) : "-", "C", 0),
                new Line("Reactor water out (onward)", on ? n(hotOutC, 1) : "-", "C", 0),
                new Line("Cleaned water in (from the nanofilter)", on ? n(coldInC, 1) : "-", "C", 0),
                new Line("Water back to feedwater", on ? n(coldOutC, 1) : "-", "C", on ? 1 : 0),
                new Line("Heat recovered", on ? n(dutyMW, 2) : "-", "MW", 0)));
    }
}
