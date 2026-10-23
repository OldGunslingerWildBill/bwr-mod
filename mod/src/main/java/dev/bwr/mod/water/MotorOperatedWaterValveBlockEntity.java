package dev.bwr.mod.water;

import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The motor of a Motor-Operated Water Valve: a demanded opening (0 to 1), the stroke towards it, the FE it draws, and the
 * player's chosen fail position.
 *
 * <p>No ticker. Like the containment penetration's actuator, time is advanced only when the valve is asked something (its
 * position is read by a pipe route, a panel or a computer, or it is commanded or fed), from the game ticks that passed since it
 * was last asked, one tick at a time. A valve nobody asks about does not need to move, and one in an unloaded chunk neither
 * moves nor draws power.
 *
 * <h2>Hardware</h2>
 * <ul>
 *   <li>Full stroke {@value #STROKE_TICKS} ticks (20 s) either way, every direction, at {@value #STROKE_FE_PER_TICK} FE per tick while
 *   the motor drives it: {@value #FULL_STROKE_FE} FE for a full stroke, which is also the size of the buffer.</li>
 *   <li>It holds its position with no power and no draw. That is the default, FAIL AS-IS.</li>
 *   <li>FAIL OPEN and FAIL CLOSED are a spring and a latch: while the valve stands away from its fail position the latch draws
 *   {@value #HOLD_FE_PER_TICK} FE per tick, and when the buffer cannot pay it (or pay a stroke) the spring takes the valve to
 *   the fail position at the normal stroke rate, using no FE. The spring also helps a stroke that is towards the fail position,
 *   free. It is the player's chosen hardware behaviour, like a spring on an air-operated valve; nothing here decides to operate
 *   it and no signal of the plant moves it.</li>
 * </ul>
 */
public final class MotorOperatedWaterValveBlockEntity extends BlockEntity {
    public static final int ENERGY_CAPACITY_FE = 20_000;
    public static final int STROKE_TICKS = 400;
    public static final int STROKE_FE_PER_TICK = 50;
    public static final int HOLD_FE_PER_TICK = 20;
    public static final int FULL_STROKE_FE = STROKE_TICKS * STROKE_FE_PER_TICK;
    private static final double STROKE_PER_TICK = 1.0 / STROKE_TICKS;
    /** Past this many catch-up ticks nothing more can change (a stroke, then the buffer running dry on the latch). */
    private static final int MAX_CATCH_UP_TICKS = STROKE_TICKS + ENERGY_CAPACITY_FE / HOLD_FE_PER_TICK + 20;

    /** What the valve does when it has no power. */
    public enum FailMode {
        AS_IS("as_is", "FAIL AS-IS", 0.0 / 0.0),
        OPEN("open", "FAIL OPEN", 1.0),
        CLOSED("closed", "FAIL CLOSED", 0.0);

        private final String key;
        private final String label;
        private final double position;

        FailMode(String key, String label, double position) {
            this.key = key;
            this.label = label;
            this.position = position;
        }

        public String key() { return key; }
        public String label() { return label; }
        /** The position the spring takes it to, or NaN for none. */
        public double failPosition() { return position; }
        public FailMode next() { return values()[(ordinal() + 1) % values().length]; }

        public static FailMode byLevel(int level) {
            return level >= 0 && level < values().length ? values()[level] : null;
        }

        public static FailMode byKey(String text) {
            if (text == null) return null;
            String t = text.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            if (t.equals("asis")) t = "as_is";
            if (t.equals("fail_open")) t = "open";
            if (t.equals("fail_closed") || t.equals("close")) t = "closed";
            if (t.equals("fail_as_is") || t.equals("none")) t = "as_is";
            for (FailMode m : values()) if (m.key.equals(t)) return m;
            return null;
        }
    }

    private int energyFe;
    private double target;
    private double position;
    private FailMode failMode = FailMode.AS_IS;
    /** The last redstone strength (0 to 15) this valve acted on; only a change of it is a command. */
    private int redstone;
    private long observedTick = -1;
    private long flowTick = Long.MIN_VALUE;
    private double flowKg;

    private final IEnergyStorage energy = new IEnergyStorage() {
        @Override public int receiveEnergy(int amount, boolean simulate) {
            advance();
            int accepted = Math.min(Math.max(0, amount), ENERGY_CAPACITY_FE - energyFe);
            if (!simulate && accepted > 0) { energyFe += accepted; setChanged(); }
            return accepted;
        }
        @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { advance(); return energyFe; }
        @Override public int getMaxEnergyStored() { return ENERGY_CAPACITY_FE; }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return true; }
    };

    public MotorOperatedWaterValveBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.MOTOR_OPERATED_WATER_VALVE.get(), pos, state);
    }

    public IEnergyStorage energy() { return energy; }

    /** The demanded opening, 0 to 1. */
    public double target() { return target; }

    /** The actual opening, 0 to 1, brought up to date. */
    public double position() { advance(); return position; }

    public FailMode failMode() { return failMode; }

    public void setFailMode(FailMode mode) {
        if (mode == null || mode == failMode) return;
        advance();
        failMode = mode;
        setChanged();
    }

    /** Demands an opening. NaN and infinities are refused (false); others are clamped to 0..1 and kept to 0.1 %. */
    public boolean setTarget(double fraction) {
        if (!Double.isFinite(fraction)) return false;
        advance();
        double t = Math.round(Math.max(0.0, Math.min(1.0, fraction)) * 1000.0) / 1000.0;
        if (t != target) { target = t; setChanged(); }
        return true;
    }

    /** Redstone: a change of the signal strength demands strength / 15 of an opening; no change is no command. */
    public void redstone(int strength) {
        int s = Math.max(0, Math.min(15, strength));
        if (s == redstone) return;
        redstone = s;
        setTarget(s / 15.0);
        setChanged();
    }

    public boolean isPowered() { advance(); return energyFe >= STROKE_FE_PER_TICK; }
    public boolean isMoving() { advance(); return position != target; }
    public boolean isOpen() { return position() >= 0.999; }
    public boolean isClosed() { return position() <= 0.001; }

    // ---- the stroke

    private void advance() {
        if (level == null || level.isClientSide()) return;
        long now = level.getGameTime();
        if (observedTick < 0 || now < observedTick) { observedTick = now; return; }
        long elapsed = now - observedTick;
        if (elapsed <= 0) return;
        observedTick = now;
        int beforeEnergy = energyFe;
        double beforePosition = position;
        long steps = Math.min(elapsed, MAX_CATCH_UP_TICKS);
        for (long i = 0; i < steps; i++) {
            if (!step()) break;
        }
        if (energyFe != beforeEnergy || position != beforePosition) setChanged();
    }

    /** One tick of the motor and the spring. False when nothing more can change. */
    private boolean step() {
        double fp = failMode.failPosition();
        boolean spring = !Double.isNaN(fp);
        double wanted = target - position;
        if (wanted != 0.0) {
            boolean towardFail = spring && Math.signum(fp - position) == Math.signum(wanted);
            if (towardFail) {
                position = moveToward(position, target);
                return true;
            }
            if (energyFe >= STROKE_FE_PER_TICK) {
                energyFe -= STROKE_FE_PER_TICK;
                position = moveToward(position, target);
                return true;
            }
            if (spring && position != fp) {
                position = moveToward(position, fp);
                return true;
            }
            return false;
        }
        if (spring && position != fp) {
            if (energyFe >= HOLD_FE_PER_TICK) {
                energyFe -= HOLD_FE_PER_TICK;
                return true;
            }
            position = moveToward(position, fp);
            return true;
        }
        return false;
    }

    private static double moveToward(double from, double to) {
        double next = from < to ? Math.min(to, from + STROKE_PER_TICK) : Math.max(to, from - STROKE_PER_TICK);
        // 400 additions of 1/400 do not sum to exactly 1: a position within rounding of the destination is the destination
        return Math.abs(next - to) < 1.0e-9 ? to : next;
    }

    // ---- flow metering (the water that passed the valve on the line that last used it)

    /** Records water that passed this tick, kg. */
    public void record(double kg) {
        if (level == null || !(kg > 0) || !Double.isFinite(kg)) return;
        long now = level.getGameTime();
        if (now != flowTick) { flowTick = now; flowKg = 0; }
        flowKg += kg;
    }

    /** The water that passed on the latest metered tick, kept one tick so the order of readers cannot flicker it, kg/s. */
    public double flowKgPerS() {
        if (level == null || flowTick == Long.MIN_VALUE) return 0;
        long age = level.getGameTime() - flowTick;
        return age >= 0 && age <= 1 ? flowKg * 20.0 : 0;
    }

    // ---- rating

    /** The pipe's rating (the same nameplate as the high-pressure water pipe), psi. */
    public static final double RATING_PSI = 2500.0;
    /** Pressure above which a line connected to the reactor wears and can fail, valve and pipe alike, psig. */
    public static double wearAbovePsig() { return dev.bwr.core.boundary.BoundaryStress.DESIGN_PRESSURE_PSIG; }

    // ---- what the screen and the computers say

    public List<String> statusLines() { return BwrText.english(statusComponents()); }

    public List<Component> statusComponents() {
        List<Component> lines = new ArrayList<>();
        lines.add(BwrText.tr("status.bwr.water_valve.title", "Motor-operated water valve"));
        lines.add(ratingComponent());
        lines.add(BwrText.tr("status.bwr.water_valve.position", "Position %s%% open (demand %s%%) | %s FE | %s",
                String.format(Locale.ROOT, "%.1f", position() * 100.0), String.format(Locale.ROOT, "%.1f", target * 100.0),
                String.format(Locale.ROOT, "%d", energyFe), failMode.label()));
        if (!isPowered() && position != target && failMode == FailMode.AS_IS) {
            lines.add(BwrText.tr("status.bwr.water_valve.no_power", "No power: the motor cannot move it (it holds where it is)."));
        }
        return lines;
    }

    public static Component ratingComponent() {
        return BwrText.tr("status.bwr.water_valve.rating",
                "Rated as the water pipe: design pressure %s psi; on a line from the reactor it wears and can fail above %s psig, as the pipe does.",
                String.format(Locale.ROOT, "%,.0f", RATING_PSI), String.format(Locale.ROOT, "%,.0f", wearAbovePsig()));
    }

    // ---- saves (a key only when it is not the default)

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        advance();
        if (energyFe != 0) tag.putInt("EnergyFe", energyFe);
        if (target != 0.0) tag.putDouble("Target", target);
        if (position != 0.0) tag.putDouble("Position", position);
        if (failMode != FailMode.AS_IS) tag.putString("FailMode", failMode.key());
        if (redstone != 0) tag.putInt("Redstone", redstone);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energyFe = Math.clamp(tag.getInt("EnergyFe"), 0, ENERGY_CAPACITY_FE);
        target = finite(tag.getDouble("Target"));
        position = finite(tag.getDouble("Position"));
        FailMode mode = FailMode.byKey(tag.getString("FailMode"));
        failMode = mode == null ? FailMode.AS_IS : mode;
        redstone = Math.clamp(tag.getInt("Redstone"), 0, 15);
        observedTick = -1;
        flowTick = Long.MIN_VALUE;
        flowKg = 0;
    }

    private static double finite(double x) { return Double.isFinite(x) ? Math.max(0.0, Math.min(1.0, x)) : 0.0; }
}
