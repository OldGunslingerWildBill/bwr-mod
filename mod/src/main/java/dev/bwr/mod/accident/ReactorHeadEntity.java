package dev.bwr.mod.accident;

import com.mojang.logging.LogUtils;
import dev.bwr.core.accident.HeadMissile;
import dev.bwr.mod.containment.ContainmentStrength;
import dev.bwr.mod.registry.BwrEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import dev.bwr.mod.text.BwrText;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The vessel head thrown off by a head failure (see {@link HeadMissileLauncher} for the launch and
 * {@link HeadMissile} for the numbers). It is drawn with the vessel's own head model at the vessel's scale.
 *
 * <h2>Life</h2>
 * <ul>
 *   <li><b>Flying.</b> Ballistic under {@value #GRAVITY_M_PER_S2} m/s^2, one metre a block, with a small tumble. Nothing is
 *       simulated on the client: it follows the server's position. The flight is exactly reproducible: the entity
 *       saves its position, velocity and drift, so a head that is saved in the air resumes where it was and comes
 *       down in the same place.</li>
 *   <li><b>Landing.</b> When its underside meets a block it comes down with {@code 1/2 m v^2}, breaks the blocks under
 *       its footprint for that energy, a few rows at most and never more than {@link HeadMissile#MAX_CRATER_BLOCKS}
 *       blocks, through the same protection path the blasts use, and settles tilted a few degrees on whatever is left.
 *       A head that was contained does not look for ground: it comes back down onto the flange it left.</li>
 *   <li><b>Resting.</b> A static entity. It ticks nothing but its own base tick, is saved and reloaded in place, and
 *       can be removed by a player with a pickaxe (or in creative), which leaves scrap iron.</li>
 * </ul>
 *
 * <h2>Chunks</h2>
 * A head never loads a chunk. While falling, if any block column under it is in an unloaded chunk it holds still for
 * that tick; entities are not ticked in unloaded chunks at all, so a head whose landing place is unloaded simply waits
 * there, in the air or on the ground, until the chunk is loaded.
 */
public class ReactorHeadEntity extends Entity {
    public static final double GRAVITY_M_PER_S2 = HeadMissile.GRAVITY;
    /** Gravity per tick, blocks/tick^2. */
    public static final double GRAVITY_PER_TICK = HeadMissile.GRAVITY / (HeadMissile.TICKS_PER_SECOND * HeadMissile.TICKS_PER_SECOND);
    /** Damage dealt to any living thing the head hits in flight. */
    public static final float IMPACT_DAMAGE = 200.0f;
    /** Largest tumble of a head in the air, degrees. */
    public static final float TUMBLE_DEGREES = 9.0f;

    private static final EntityDataAccessor<Float> WIDTH = SynchedEntityData.defineId(ReactorHeadEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DEPTH = SynchedEntityData.defineId(ReactorHeadEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(ReactorHeadEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> RESTING = SynchedEntityData.defineId(ReactorHeadEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> TILT_X = SynchedEntityData.defineId(ReactorHeadEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> TILT_Z = SynchedEntityData.defineId(ReactorHeadEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Long> LAUNCHED = SynchedEntityData.defineId(ReactorHeadEntity.class, EntityDataSerializers.LONG);

    private double massKg = HeadMissile.REFERENCE_MASS_KG;
    /** Height the head comes down to when it was contained, or NaN when it looks for ground. */
    private double landY = Double.NaN;
    private double driftX, driftZ;
    /** Drift starts once the head's underside is above this. */
    private double clearBaseY = Double.NEGATIVE_INFINITY;

    public ReactorHeadEntity(EntityType<? extends ReactorHeadEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /** The dome height of a head on a vessel of the given outside size; the renderer draws the same number. */
    public static float headHeight(int width, int depth, int height) {
        return Math.min(height * 0.22f, Math.min(width, depth) * 0.18f);
    }

    /** Make a head of the given size, not yet in the world. */
    public static ReactorHeadEntity create(Level level, int width, int depth, int vesselHeight) {
        ReactorHeadEntity head = new ReactorHeadEntity(BwrEntities.REACTOR_HEAD.get(), level);
        head.entityData.set(WIDTH, (float) width);
        head.entityData.set(DEPTH, (float) depth);
        head.entityData.set(HEIGHT, headHeight(width, depth, vesselHeight));
        head.massKg = HeadMissile.massKg(width, depth);
        head.refreshDimensions();
        return head;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(WIDTH, 9.0f);
        builder.define(DEPTH, 9.0f);
        builder.define(HEIGHT, 1.6f);
        builder.define(RESTING, false);
        builder.define(TILT_X, 0.0f);
        builder.define(TILT_Z, 0.0f);
        builder.define(LAUNCHED, 0L);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (WIDTH.equals(key) || DEPTH.equals(key) || HEIGHT.equals(key)) refreshDimensions();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(Math.max(width(), depth()), height());
    }

    public float width() {
        return entityData.get(WIDTH);
    }

    public float depth() {
        return entityData.get(DEPTH);
    }

    public float height() {
        return entityData.get(HEIGHT);
    }

    public double massKg() {
        return massKg;
    }

    public boolean isResting() {
        return entityData.get(RESTING);
    }

    public float tiltXDegrees() {
        return entityData.get(TILT_X);
    }

    public float tiltZDegrees() {
        return entityData.get(TILT_Z);
    }

    public long launchedAt() {
        return entityData.get(LAUNCHED);
    }

    public double landY() {
        return landY;
    }

    public double driftX() {
        return driftX;
    }

    public double driftZ() {
        return driftZ;
    }

    public double clearBaseY() {
        return clearBaseY;
    }

    // ------------------------------------------------------------------
    // Launch
    // ------------------------------------------------------------------

    /** Set a head in flight: upward at {@code speedBlocksPerTick} with the given drift (blocks per tick) once clear. */
    void launch(double upBlocksPerTick, double driftXPerTick, double driftZPerTick, double clearBase, double land, long gameTime, float yaw) {
        this.driftX = driftXPerTick;
        this.driftZ = driftZPerTick;
        this.clearBaseY = clearBase;
        this.landY = land;
        entityData.set(LAUNCHED, gameTime);
        entityData.set(RESTING, false);
        setYRot(yaw);
        setDeltaMovement(0.0, upBlocksPerTick, 0.0);
    }

    /** Put a head straight down at rest where it is, tilted a little. */
    void restHere(ServerLevel level, double y) {
        setPos(getX(), y, getZ());
        settle(level, y);
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide || isResting() || !(level() instanceof ServerLevel server)) return;
        fly(server);
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    private void fly(ServerLevel level) {
        Vec3 v = getDeltaMovement();
        double y0 = getY();
        double vy = v.y - GRAVITY_PER_TICK;
        double ny = y0 + vy;
        double mx = y0 >= clearBaseY ? driftX : 0.0;
        double mz = y0 >= clearBaseY ? driftZ : 0.0;

        if (vy < 0.0) {
            if (!Double.isNaN(landY)) {
                if (ny <= landY) {
                    hit(level, landY, vy, true);
                    return;
                }
            } else {
                double nx = getX() + mx, nz = getZ() + mz;
                double support = support(level, nx, nz, y0, ny);
                if (Double.isNaN(support)) return; // a column under the head is in an unloaded chunk: hold still
                if (support != Double.NEGATIVE_INFINITY) {
                    setPos(nx, y0, nz);
                    hit(level, support, vy, false);
                    return;
                }
            }
        }
        AABB now = getBoundingBox();
        strike(level, now.minmax(now.move(mx, vy, mz)));
        setPos(getX() + mx, ny, getZ() + mz);
        setDeltaMovement(mx, vy, mz);
        setYRot(getYRot() + 1.4f);
        if (ny < level.getMinBuildHeight() - 64) discard();
    }

    private void strike(ServerLevel level, AABB swept) {
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, swept)) {
            victim.hurt(level.damageSources().fallingBlock(this), IMPACT_DAMAGE);
        }
    }

    /** One footprint column: the sample points under the head, as block columns (x, z). */
    private List<BlockPos> columns(double cx, double cz, int y) {
        return HeadMissileLauncher.footprint(cx, cz, Math.round(width()), Math.round(depth()), y);
    }

    /**
     * The highest top surface the head's underside meets going from {@code y0} to {@code ny}: NEGATIVE_INFINITY if none,
     * NaN if a column is in an unloaded chunk.
     */
    private double support(ServerLevel level, double cx, double cz, double y0, double ny) {
        double best = Double.NEGATIVE_INFINITY;
        int top = Mth.floor(y0 + 0.5), bottom = Mth.floor(ny);
        for (BlockPos column : columns(cx, cz, 0)) {
            if (!level.hasChunkAt(column)) return Double.NaN;
            for (int y = top; y >= bottom; y--) {
                BlockPos pos = column.atY(y);
                BlockState state = level.getBlockState(pos);
                var shape = state.getCollisionShape(level, pos);
                if (shape.isEmpty()) continue;
                double surface = y + shape.max(Direction.Axis.Y);
                if (surface > ny - 1.0e-6 && surface <= y0 + 0.5) best = Math.max(best, surface);
            }
        }
        return best;
    }

    /** The head's underside has met {@code surface}: land (or come back down onto the flange). */
    private void hit(ServerLevel level, double surface, double vy, boolean flange) {
        double energy = HeadMissile.impactEnergyJ(massKg, vy);
        setPos(getX(), surface, getZ());
        double rest = surface;
        if (!flange) {
            int dug = crater(level, surface, energy);
            LogUtils.getLogger().info("Reactor head landed at {} on a surface at {}: {} MJ, {} blocks removed", blockPosition(),
                    Math.round(surface * 10.0) / 10.0, Math.round(energy / 1.0e5) / 10.0, dug);
            double after = support(level, getX(), getZ(), surface + 0.5, surface - HeadMissile.MAX_CRATER_ROWS - 1.5);
            if (!Double.isNaN(after) && after != Double.NEGATIVE_INFINITY) rest = after;
        }
        setPos(getX(), rest, getZ());
        level.playSound(null, getX(), rest, getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, flange ? 5.0f : 12.0f, 0.55f);
        level.sendParticles(ParticleTypes.POOF, getX(), rest + 0.5, getZ(), 40, width() / 3.0, 0.3, depth() / 3.0, 0.02);
        settle(level, rest);
    }

    private void settle(ServerLevel level, double base) {
        RandomSource random = RandomSource.create(Mth.getSeed(BlockPos.containing(getX(), base, getZ())) ^ Double.doubleToLongBits(massKg));
        double tilt = 2.0 + random.nextDouble() * 5.0;
        double direction = random.nextDouble() * Math.PI * 2.0;
        entityData.set(TILT_X, (float) (tilt * Math.cos(direction)));
        entityData.set(TILT_Z, (float) (tilt * Math.sin(direction)));
        // The low side of a tilted head is on the ground; the high side is lifted by half the rise across it.
        double lift = 0.5 * Math.sin(Math.toRadians(tilt)) * 0.5 * Math.max(width(), depth());
        setPos(getX(), base + lift, getZ());
        setDeltaMovement(Vec3.ZERO);
        entityData.set(RESTING, true);
        landY = Double.NaN;
        hasImpulse = true;
    }

    /**
     * Break the blocks under the head for the energy it arrives with: the footprint, row by row down from the surface,
     * a row at a time while the energy covers it, the centre first when it covers only part of a row, at most
     * {@link HeadMissile#MAX_CRATER_ROWS} rows and {@link HeadMissile#MAX_CRATER_BLOCKS} blocks. Blocks the blasts
     * never break are left, and a protection mod that refuses a block keeps it.
     */
    private int crater(ServerLevel level, double surface, double energyJ) {
        AccidentBlast.Guard guard = AccidentBlast.guard(level);
        int topRow = Mth.ceil(surface - 1.0e-6) - 1;
        double remaining = energyJ;
        int total = 0;
        Vec3 centre = position();
        for (int row = 0; row < HeadMissile.MAX_CRATER_ROWS && total < HeadMissile.MAX_CRATER_BLOCKS; row++) {
            List<BlockPos> cells = new ArrayList<>();
            List<Double> costs = new ArrayList<>();
            for (BlockPos column : columns(getX(), getZ(), topRow - row)) {
                if (!level.hasChunkAt(column)) continue;
                BlockState state = level.getBlockState(column);
                if (state.isAir() || state.getBlock() instanceof LiquidBlock || !guard.mayBreak(column, state)) continue;
                cells.add(column);
            }
            cells.sort(Comparator.comparingDouble(c -> Math.hypot(c.getX() + 0.5 - centre.x, c.getZ() + 0.5 - centre.z)));
            List<BlockPos> take = new ArrayList<>();
            boolean partial = false;
            for (BlockPos cell : cells) {
                double cost = HeadMissile.crushJ(ContainmentStrength.contribution(level, cell, level.getBlockState(cell)));
                if (remaining < cost || total + take.size() >= HeadMissile.MAX_CRATER_BLOCKS) {
                    partial = true;
                    break;
                }
                remaining -= cost;
                take.add(cell);
            }
            if (!take.isEmpty()) {
                AccidentBlast.breakProtected(level, getX(), topRow - row + 0.5, getZ(), 3.0f, new ArrayList<>(take));
                total += take.size();
            }
            if (partial) break;
        }
        return total;
    }

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------

    @Override
    public boolean isPickable() {
        return isResting();
    }

    @Override
    public boolean canBeCollidedWith() {
        return isResting();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return !(source.getEntity() instanceof Player);
    }

    /** A player with a pickaxe, or in creative, takes the resting head apart for scrap iron. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || !isResting() || !(source.getEntity() instanceof Player player)) return false;
        if (!player.getAbilities().instabuild && !player.getMainHandItem().is(ItemTags.PICKAXES)) {
            player.displayClientMessage(BwrText.tr("status.bwr.reactor_head.needs_pickaxe", "The head is too heavy to move by hand; use a pickaxe."), true);
            return false;
        }
        int ingots = (int) Math.clamp(Math.round(massKg / 2000.0), 4, 64);
        spawnAtLocation(new ItemStack(Items.IRON_INGOT, ingots));
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.ANVIL_BREAK, SoundSource.BLOCKS, 1.0f, 0.7f);
        discard();
        return true;
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("Width", width());
        tag.putFloat("Depth", depth());
        tag.putFloat("HeadHeight", height());
        tag.putDouble("Mass", massKg);
        tag.putLong("Launched", launchedAt());
        if (isResting()) {
            tag.putBoolean("Resting", true);
            tag.putFloat("TiltX", tiltXDegrees());
            tag.putFloat("TiltZ", tiltZDegrees());
        } else {
            tag.putDouble("DriftX", driftX);
            tag.putDouble("DriftZ", driftZ);
            tag.putDouble("ClearBase", clearBaseY);
            if (!Double.isNaN(landY)) tag.putDouble("LandY", landY);
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(WIDTH, Math.clamp(tag.getFloat("Width"), 1.0f, 64.0f));
        entityData.set(DEPTH, Math.clamp(tag.getFloat("Depth"), 1.0f, 64.0f));
        entityData.set(HEIGHT, Math.clamp(tag.getFloat("HeadHeight"), 0.25f, 16.0f));
        double mass = tag.getDouble("Mass");
        massKg = Double.isFinite(mass) && mass > 0.0 ? mass : HeadMissile.massKg(Math.round(width()), Math.round(depth()));
        entityData.set(LAUNCHED, tag.getLong("Launched"));
        boolean resting = tag.getBoolean("Resting");
        entityData.set(RESTING, resting);
        entityData.set(TILT_X, tag.getFloat("TiltX"));
        entityData.set(TILT_Z, tag.getFloat("TiltZ"));
        driftX = finiteOr(tag.getDouble("DriftX"), 0.0);
        driftZ = finiteOr(tag.getDouble("DriftZ"), 0.0);
        clearBaseY = tag.contains("ClearBase") ? tag.getDouble("ClearBase") : Double.NEGATIVE_INFINITY;
        landY = tag.contains("LandY") ? tag.getDouble("LandY") : Double.NaN;
        refreshDimensions();
    }

    private static double finiteOr(double v, double fallback) {
        return Double.isFinite(v) ? v : fallback;
    }
}
