package dev.bwr.mod.radiation;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The state of an area radiation monitor: its high alarm setpoint (mSv/h) and its latest reading.
 *
 * <p>Nothing here ticks. {@link RadiationMonitors} refreshes every loaded monitor once a second (the lamp), and every
 * read of the dose rate (a panel's input sampler, a computer) recomputes it only if the last value is older than
 * {@link #MAX_AGE_TICKS}, so a monitor costs one {@link RadiationField} evaluation per five ticks however many panels
 * and computers look at it.
 *
 * <p>The alert setpoint is a tenth of the alarm setpoint, and the lamp is amber from alert, red from alarm. A lamp or an
 * output that is on stays on until the reading falls below 90 % of its setpoint, so a reading hovering at a setpoint
 * does not make it flicker. Saved: {@code HighSetpoint} only when it is not the default; old worlds have no monitors.
 */
public final class AreaRadiationMonitorBlockEntity extends BlockEntity {
    /** Default alarm setpoint, mSv/h (the usual "high radiation area" level). */
    public static final double DEFAULT_HIGH_MSV_PER_H = 1.0;
    public static final double MIN_HIGH_MSV_PER_H = 0.001;
    public static final double MAX_HIGH_MSV_PER_H = 1.0e6;
    /** The alert setpoint is the alarm setpoint divided by this. */
    public static final double ALERT_DIVISOR = 10.0;
    /** A lamp or output that is on drops out below this fraction of its setpoint. */
    public static final double HYSTERESIS = 0.9;
    /** A reading is reused for this many ticks. */
    public static final long MAX_AGE_TICKS = 5L;

    private double high = DEFAULT_HIGH_MSV_PER_H;
    private long sampledAt = Long.MIN_VALUE;
    private RadiationField.Reading reading = RadiationField.Reading.ZERO;

    public AreaRadiationMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.AREA_RADIATION_MONITOR.get(), pos, state);
    }

    // ---- setpoints

    public double highSetpoint() {
        return high;
    }

    public double alertSetpoint() {
        return high / ALERT_DIVISOR;
    }

    /** Sets the alarm setpoint, mSv/h; refused (false) when outside the range or not a number. */
    public boolean setHighSetpoint(double msvPerH) {
        if (!(msvPerH >= MIN_HIGH_MSV_PER_H) || msvPerH > MAX_HIGH_MSV_PER_H) {
            return false;
        }
        if (msvPerH != high) {
            high = msvPerH;
            setChanged();
        }
        return true;
    }

    // ---- readings

    /** The dose rate here, mSv/h, at most {@link #MAX_AGE_TICKS} old. Zero on a client or when unloaded. */
    public double doseRate() {
        return current().total();
    }

    /** The latest reading by source, mSv/h, at most {@link #MAX_AGE_TICKS} old. */
    public RadiationField.Reading current() {
        if (level == null || level.isClientSide()) {
            return RadiationField.Reading.ZERO;
        }
        long now = level.getGameTime();
        if (sampledAt == Long.MIN_VALUE || now - sampledAt >= MAX_AGE_TICKS || now < sampledAt) {
            reading = RadiationField.read(level, sensorPosition());
            sampledAt = now;
        }
        return reading;
    }

    /** Forces the next read to measure again (tests, and a setpoint or source that just changed). */
    public void invalidate() {
        sampledAt = Long.MIN_VALUE;
    }

    /** The detector sits in the middle of the block. */
    public Vec3 sensorPosition() {
        return Vec3.atCenterOf(worldPosition);
    }

    /** The lamp for a dose rate given the lamp now: 0 off, 1 alert (amber), 2 alarm (red). */
    public int lampFor(double rate, int lampNow) {
        if (rate >= high || lampNow == 2 && rate >= high * HYSTERESIS) {
            return 2;
        }
        double alert = alertSetpoint();
        if (rate >= alert || lampNow >= 1 && rate >= alert * HYSTERESIS) {
            return 1;
        }
        return 0;
    }

    /** The lamp the current reading calls for (what the lamp will show at the next refresh). */
    public int lamp() {
        return lampFor(doseRate(), getBlockState().getValue(AreaRadiationMonitorBlock.LAMP));
    }

    /** The boolean output "high radiation": at or above the alarm setpoint (with hysteresis). */
    public boolean isHigh() {
        return lamp() == 2;
    }

    /** The boolean output "radiation alert": at or above the alert setpoint (with hysteresis). */
    public boolean isAlert() {
        return lamp() >= 1;
    }

    /** Measures now and updates the lamp; the once-a-second job of {@link RadiationMonitors}. */
    public void refresh() {
        if (level == null || level.isClientSide() || isRemoved()) {
            return;
        }
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof AreaRadiationMonitorBlock)) {
            return;
        }
        int now = state.getValue(AreaRadiationMonitorBlock.LAMP);
        int next = lampFor(doseRate(), now);
        if (next != now) {
            level.setBlock(worldPosition, state.setValue(AreaRadiationMonitorBlock.LAMP, next), 3);
        }
    }

    // ---- registry and saving

    @Override
    public void onLoad() {
        super.onLoad();
        RadiationMonitors.add(this);
    }

    @Override
    public void setRemoved() {
        RadiationMonitors.remove(this);
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        RadiationMonitors.remove(this);
        super.onChunkUnloaded();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (high != DEFAULT_HIGH_MSV_PER_H) {
            tag.putDouble("HighSetpoint", high);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        high = DEFAULT_HIGH_MSV_PER_H;
        if (tag.contains("HighSetpoint", Tag.TAG_ANY_NUMERIC)) {
            double value = tag.getDouble("HighSetpoint");
            if (value >= MIN_HIGH_MSV_PER_H && value <= MAX_HIGH_MSV_PER_H) {
                high = value;
            }
        }
        sampledAt = Long.MIN_VALUE;
    }
}
