package dev.bwr.mod.power;

import com.mojang.logging.LogUtils;
import dev.bwr.core.turbine.TurbineBlades;
import dev.bwr.mod.accident.AccidentBlast;
import dev.bwr.mod.containment.ContainmentStrength;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A turbine missile (TURBINE-MAINTENANCE.md): a last-stage blade, or a disc fragment, thrown out of an LP turbine through its casing.
 * The energies are {@link TurbineBlades}'; this class flies the piece through the world.
 *
 * <h2>The flight</h2>
 * The piece leaves the wheel of its flow (on the shaft axis, 2.5 blocks from the middle of the LP towards that end) tangentially, so it
 * flies in the plane of the wheel: across the shaft, at an angle in that plane picked from the position and the time (a test may give
 * the angle). Its own casing takes {@link TurbineBlades#BLADE_CASING_J} (a disc fragment {@link TurbineBlades#DISC_CASING_J}) on the
 * way out; the LP's own cells are passed through, not broken. Then, block by block along a ballistic line (it drops under gravity; at
 * 400 m/s that is little):
 * <ul>
 *   <li>air and fluids cost nothing;</li>
 *   <li>a block the blasts never break (bedrock, a controller, a formed reactor's structure), another turbine, pump or generator
 *       casing, or one a protection mod keeps, <b>stops</b> it (a casing it embeds in takes shaft damage from what is left);</li>
 *   <li>any other block costs {@link TurbineBlades#perforationJ} of its blast rating; with that much left the block is broken (the
 *       blasts' protected path: an explosion start, a break event per block, vanilla drops) and the piece goes on, otherwise it stops
 *       in it;</li>
 *   <li>an entity on the line is hurt by what the piece carries (a few MJ kill anything), once;</li>
 *   <li>a block in a chunk that is not loaded <b>stops</b> it there: a chunk is never loaded to look;</li>
 *   <li>it goes at most {@link #RANGE_BLOCKS} blocks.</li>
 * </ul>
 * The flight is worked out in the tick the piece lets go (at hundreds of metres a second it crosses a turbine hall in a fraction of
 * a second). Breaking blocks is never optional, as for every accident blast; no setting turns it off.
 */
public final class TurbineMissile {
    private TurbineMissile() {}

    public static final int RANGE_BLOCKS = 160;
    private static final double STEP = 0.2;
    private static final double GRAVITY = 9.81;
    /** Energy an entity takes out of the piece, J. */
    private static final double ENTITY_J = 5_000;

    public enum Stop { STOPPED_IN_BLOCK, UNBREAKABLE, PROTECTED, CASING, UNLOADED, OUT_OF_WORLD, RANGE }

    public record Result(Vec3 origin, Vec3 direction, Vec3 end, double energyJ, double leftJ, List<BlockPos> broken, Stop stop,
                         TurbineBlades.Missile kind, int entitiesHit) {
    }

    /** The middle of the wheel of {@code flow} of the LP at {@code root}: 2.5 blocks from its middle along the shaft, at the shaft. */
    public static Vec3 wheel(PowerModuleBlockEntity lp, int flow) {
        Direction f = lp.getBlockState().getValue(PumpAssemblyBlock.FACING);
        BlockPos r = lp.getBlockPos();
        double s = flow == 0 ? 2.5 : -2.5;
        return new Vec3(r.getX() + 0.5 + f.getStepX() * s, r.getY() + 2.5, r.getZ() + 0.5 + f.getStepZ() * s);
    }

    /** The unit direction a piece released at {@code angle} (radians in the plane of the wheel, 0 horizontal to the right of the facing, pi/2 up) flies. */
    public static Vec3 direction(PowerModuleBlockEntity lp, double angle) {
        Direction side = lp.getBlockState().getValue(PumpAssemblyBlock.FACING).getClockWise();
        return new Vec3(side.getStepX() * Math.cos(angle), Math.sin(angle), side.getStepZ() * Math.cos(angle));
    }

    /** The release angle picked from the position and the time. */
    public static double pickAngle(ServerLevel level, BlockPos at) {
        RandomSource random = RandomSource.create(Mth.getSeed(at) ^ (level.getGameTime() * 0x9E3779B97F4A7C15L));
        return random.nextDouble() * Math.PI * 2.0;
    }

    /**
     * Throws a piece out of the LP's flow at the train's speed. The section's condition, the thrown mark and (for a disc) the shaft are
     * the caller's; this only flies it. {@code angle} NaN picks one.
     */
    public static Result launch(ServerLevel level, PowerModuleBlockEntity lp, int flow, TurbineBlades.Missile kind, double rpm, double angle) {
        double a = Double.isFinite(angle) ? angle : pickAngle(level, lp.getBlockPos());
        Vec3 origin = wheel(lp, flow);
        Vec3 dir = direction(lp, a);
        double energy = TurbineBlades.missileEnergyJ(kind, rpm);
        double left = TurbineBlades.energyOutsideCasingJ(kind, rpm);
        double speed = Math.max(1, TurbineBlades.missileSpeed(kind, rpm));
        double mass = TurbineBlades.missileMassKg(kind);
        BlockPos root = lp.getBlockPos();
        AccidentBlast.Guard guard = AccidentBlast.guard(level);
        List<BlockPos> broken = new ArrayList<>();
        Set<Entity> hit = new HashSet<>();
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        long last = Long.MIN_VALUE;
        Vec3 at = origin;
        Stop stop = Stop.RANGE;
        double travelled = 0;
        while (travelled < RANGE_BLOCKS) {
            travelled += STEP;
            double t = travelled / speed;
            at = origin.add(dir.scale(travelled)).add(0, -0.5 * GRAVITY * t * t, 0);
            cell.set(Mth.floor(at.x), Mth.floor(at.y), Mth.floor(at.z));
            if (travelled > 1.0 && Mth.floor(travelled) != Mth.floor(travelled - STEP) && level.hasChunkAt(cell)) {
                left = hitEntities(level, at, left, hit); // once a metre along the line
            }
            if (cell.asLong() == last) {
                continue;
            }
            last = cell.asLong();
            if (level.isOutsideBuildHeight(cell)) {
                stop = Stop.OUT_OF_WORLD;
                break;
            }
            if (!level.hasChunkAt(cell)) {
                stop = Stop.UNLOADED;
                break;
            }
            BlockState state = level.getBlockState(cell);
            if (state.getBlock() instanceof PowerModuleBlock b && b.origin(cell, state).equals(root)) {
                continue; // its own casing: the casing's cost is already paid
            }
            if (state.isAir() || state.getBlock() instanceof LiquidBlock || !state.getFluidState().isEmpty() && state.getCollisionShape(level, cell).isEmpty()) {
                continue;
            }
            if (state.getCollisionShape(level, cell).isEmpty() && !state.isSolid()) {
                // grass, flowers, torches: cut through for the floor rate
                if (left < TurbineBlades.MIN_BLOCK_J) {
                    stop = Stop.STOPPED_IN_BLOCK;
                    break;
                }
            }
            if (state.getBlock() instanceof PumpAssemblyBlock pa) {
                // another turbine, pump or generator casing: the piece embeds in it
                if (pa.controller(level, cell, state) instanceof PowerModuleBlockEntity other) {
                    other.addShaftDamage(left / 1.0e7);
                }
                stop = Stop.CASING;
                break;
            }
            if (!guard.mayBreak(cell, state)) {
                stop = Stop.UNBREAKABLE;
                break;
            }
            double cost = TurbineBlades.perforationJ(ContainmentStrength.contribution(level, cell, state));
            if (left < cost) {
                stop = Stop.STOPPED_IN_BLOCK;
                break;
            }
            BlockPos p = cell.immutable();
            List<BlockPos> removed = AccidentBlast.breakProtected(level, at.x, at.y, at.z, 1.0f, new ArrayList<>(List.of(p)));
            if (removed.isEmpty()) {
                stop = Stop.PROTECTED;
                break;
            }
            broken.add(p);
            left -= cost;
        }
        trail(level, origin, at);
        Result result = new Result(origin, dir, at, energy, Math.max(0, left), List.copyOf(broken), stop, kind, hit.size());
        LogUtils.getLogger().info("Turbine missile from {} flow {}: {} {} kg at {} m/s ({} MJ), {} blocks broken, {} entities hit, stopped ({}) at {}",
                root.toShortString(), flow, kind, Math.round(mass), Math.round(speed), Math.round(energy / 1e5) / 10.0, broken.size(), hit.size(),
                stop, BlockPos.containing(at).toShortString());
        TurbineEvents.post(new TurbineEvents.Missile(level, root, origin, dir, at, kind, energy, mass, speed, rpm, broken.size()));
        dev.bwr.mod.rumble.Rumble.impulse(level, origin, energy);
        return result;
    }

    private static double hitEntities(ServerLevel level, Vec3 at, double left, Set<Entity> hit) {
        AABB box = new AABB(at.x - 0.6, at.y - 0.6, at.z - 0.6, at.x + 0.6, at.y + 0.6, at.z + 0.6).inflate(0.4);
        for (Entity e : level.getEntities((Entity) null, box, e -> e.isAlive() && !e.isSpectator())) {
            if (hit.add(e) && left > 0) {
                e.hurt(level.damageSources().explosion(null, null), (float) Math.min(1000.0, left / 2000.0));
                left = Math.max(0, left - ENTITY_J);
            }
        }
        return left;
    }

    private static void trail(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        int n = Math.min(64, Math.max(1, (int) (d.length() / 2)));
        for (int i = 0; i <= n; i++) {
            Vec3 p = from.add(d.scale(i / (double) n));
            level.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.0);
        }
        level.sendParticles(ParticleTypes.EXPLOSION, to.x, to.y, to.z, 1, 0.1, 0.1, 0.1, 0.0);
    }
}
