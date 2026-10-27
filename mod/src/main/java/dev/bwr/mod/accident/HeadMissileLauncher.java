package dev.bwr.mod.accident;

import dev.bwr.core.accident.HeadMissile;
import dev.bwr.mod.containment.ContainmentStrength;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Throws the vessel head when it fails, and decides whether the blocks above it hold it. The numbers are
 * {@link HeadMissile}'s; this class reads the world for them.
 *
 * <h2>What holds a head</h2>
 * Nothing in particular. The head rises through the world one row at a time starting at the first row above the vessel
 * shell. A row is the blocks under the head's footprint (an ellipse of the vessel's outside width and depth). Each
 * block resists by its blast resistance on the rating basis the walls and the blasts use,
 * {@link ContainmentStrength#contribution}: {@code 5.8 * sqrt(blast resistance)} psi, capped per block, with data pack
 * overrides applied, and takes {@code rating * 1 m^2 * 1 m} of energy to push out ({@link HeadMissile#crushJ}). The row's
 * capacity is the sum over its blocks. So a thick enough stack of anything stops it: a few rows of obsidian,
 * reinforced deepslate, or another mod's reinforced concrete (through whatever blast resistance it reports), many rows of
 * weaker stone, and a lot of anything flimsy.
 * <ul>
 *   <li>A head with at least the row's capacity left breaks the row (through the protected path of the blasts: Start,
 *       a {@code BlockEvent.BreakEvent} for every block, Detonate, vanilla drops) and goes on, minus that capacity and
 *       the work against gravity.</li>
 *   <li>A head with less is <b>stopped</b> by the row. The row stands, it is the ceiling of the cone, and the head
 *       comes to rest under it. A row the head could break but a protection mod would not let it break stops it too.</li>
 *   <li>A row with a block nothing can break (bedrock, a controller, a formed reactor) stops it.</li>
 *   <li>Rows that are open or in an unloaded chunk cost only gravity; a chunk is never loaded to look.</li>
 *   <li>A head that is past {@link HeadMissile#MAX_CLIMB_BLOCKS} rows with energy left is <b>not contained</b>: it flies.</li>
 * </ul>
 *
 * <h2>What the head then does</h2>
 * Not contained: it rises to {@code min(climb cap, free rise)} blocks above the vessel top, drifts off the vertical
 * once it is above everything it broke, comes down {@link HeadMissile#DRIFT_MIN_BLOCKS} to
 * {@code DRIFT_MIN + DRIFT_SPREAD} blocks beyond the vessel's edge in a direction picked from the position and time, lands,
 * digs and rests. Contained within {@link HeadMissile#LODGE_ROWS} rows of the top: it stays lodged under the row that
 * stopped it. Contained higher up, or having run out of energy in the air: it rises to there and comes back down onto
 * the flange, tilted.
 */
public final class HeadMissileLauncher {
    private HeadMissileLauncher() {
    }

    /** What the launch did. {@code ceilingY} is the lowest height the steam cone must not touch, or Integer.MAX_VALUE. */
    public record Launch(ReactorHeadEntity head, boolean contained, int ceilingY, int stopRow, int rowsBroken,
                         double massKg, double kineticJ, double riseBlocks) {
    }

    /**
     * The sample points under a head centred on (cx, cz): one per metre across the width and depth, those inside the
     * ellipse, as block columns at height {@code y}.
     */
    public static List<BlockPos> footprint(double cx, double cz, int width, int depth, int y) {
        List<BlockPos> cells = new ArrayList<>();
        double a = width / 2.0, b = depth / 2.0;
        for (int i = 0; i < width; i++) {
            double x = cx - a + 0.5 + i;
            for (int j = 0; j < depth; j++) {
                double z = cz - b + 0.5 + j;
                double u = (x - cx) / a, w = (z - cz) / b;
                if (u * u + w * w <= 1.0) cells.add(new BlockPos(Mth.floor(x), y, Mth.floor(z)));
            }
        }
        return cells;
    }

    /**
     * Throw the head of the vessel whose shell spans {@code shellMin}..{@code shellMax}.
     *
     * @param storedEnergyJ the failure's stored energy, J ({@code BlastPhysics.headFailureEnergyJ})
     * @param pressurePsig  the gauge pressure the head let go at
     * @return the launch, or null when the energy is nil or the head could not be spawned
     */
    public static Launch launch(ServerLevel level, BlockPos shellMin, BlockPos shellMax, double storedEnergyJ, double pressurePsig) {
        Launch launch = throwHead(level, shellMin, shellMax, storedEnergyJ, pressurePsig);
        if (launch == null) {
            LogUtils.getLogger().warn("Reactor head at {} was not thrown", shellMin);
        } else {
            LogUtils.getLogger().info("Reactor head at {} thrown: {} t, {} MJ, {} {}, rise {} blocks, {} blocks broken", shellMin,
                    Math.round(launch.massKg() / 100.0) / 10.0, Math.round(launch.kineticJ() / 1.0e5) / 10.0,
                    launch.contained() ? "held" : "free", launch.contained() && launch.stopRow() >= 0 ? "by row " + launch.stopRow() : "",
                    Math.round(launch.riseBlocks() * 10.0) / 10.0, launch.rowsBroken());
            dev.bwr.mod.rumble.Rumble.impulse(level, net.minecraft.world.phys.Vec3.atCenterOf(shellMin).add(
                    (shellMax.getX() - shellMin.getX()) / 2.0, shellMax.getY() - shellMin.getY(), (shellMax.getZ() - shellMin.getZ()) / 2.0),
                    launch.kineticJ());
        }
        return launch;
    }

    private static Launch throwHead(ServerLevel level, BlockPos shellMin, BlockPos shellMax, double storedEnergyJ, double pressurePsig) {
        int w = shellMax.getX() - shellMin.getX() + 1, d = shellMax.getZ() - shellMin.getZ() + 1;
        int h = shellMax.getY() - shellMin.getY() + 1;
        double mass = HeadMissile.massKg(w, d);
        double ke = HeadMissile.kineticEnergyJ(storedEnergyJ, pressurePsig, w, d);
        float headHeight = ReactorHeadEntity.headHeight(w, d, h);
        double baseY = shellMin.getY() + h - headHeight;
        int topY = shellMax.getY() + 1;
        double ax = (shellMin.getX() + shellMax.getX() + 1) / 2.0, az = (shellMin.getZ() + shellMax.getZ() + 1) / 2.0;

        ReactorHeadEntity head = ReactorHeadEntity.create(level, w, d, h);
        head.setPos(ax, baseY, az);

        AccidentBlast.Guard guard = AccidentBlast.guard(level);
        HeadMissile.Climb climb = new HeadMissile.Climb(mass, ke);
        int highestBroken = -1, stopRow = -1, broken = 0;
        boolean struck = false;
        for (int row = 0; row < HeadMissile.MAX_CLIMB_BLOCKS; row++) {
            int y = topY + row;
            double capacity = 0.0;
            boolean unbreakable = false;
            List<BlockPos> solid = new ArrayList<>();
            if (y < level.getMaxBuildHeight()) {
                for (BlockPos column : footprint(ax, az, w, d, y)) {
                    if (!level.hasChunkAt(column)) continue;
                    BlockState state = level.getBlockState(column);
                    if (state.isAir() || state.getBlock() instanceof LiquidBlock) continue;
                    if (!guard.mayBreak(column, state)) {
                        unbreakable = true;
                        continue;
                    }
                    capacity += HeadMissile.crushJ(ContainmentStrength.contribution(level, column, state));
                    solid.add(column);
                }
            }
            if (unbreakable) {
                capacity = Double.POSITIVE_INFINITY;
            } else if (capacity > 0.0 && climb.canPunch(capacity)) {
                List<BlockPos> removed = AccidentBlast.breakProtected(level, ax, y + 0.5, az, 4.0f, new ArrayList<>(solid));
                broken += removed.size();
                if (removed.size() < solid.size()) {
                    capacity = Double.POSITIVE_INFINITY; // a protection mod kept a block: the row holds
                } else {
                    highestBroken = row;
                }
            }
            HeadMissile.Step step = climb.next(capacity);
            if (step == HeadMissile.Step.STOPPED) {
                struck = true;
                stopRow = row;
                break;
            }
            if (step == HeadMissile.Step.PEAKED) {
                stopRow = climb.rows();
                break;
            }
        }
        boolean cleared = climb.cleared();
        long now = level.getGameTime();
        float yaw = (float) (Mth.getSeed(shellMin) % 360L);
        if (!cleared) {
            int ceiling = struck ? topY + stopRow : Integer.MAX_VALUE;
            if (struck && stopRow <= HeadMissile.LODGE_ROWS) {
                head.launch(0.0, 0.0, 0.0, Double.NEGATIVE_INFINITY, baseY, now, yaw);
                if (!level.addFreshEntity(head)) return null;
                head.restHere(level, baseY + stopRow);
                return new Launch(head, true, ceiling, stopRow, broken, mass, ke, stopRow);
            }
            double rise = Math.max(0.5, stopRow);
            head.launch(HeadMissile.speedForRise(rise) / HeadMissile.TICKS_PER_SECOND, 0.0, 0.0, Double.NEGATIVE_INFINITY, baseY, now, yaw);
            if (!level.addFreshEntity(head)) return null;
            return new Launch(head, true, ceiling, stopRow, broken, mass, ke, rise);
        }

        double roof = level.getMaxBuildHeight() - 2.0 - headHeight;
        double rise = Math.max(0.5, Math.min(climb.apexBlocks(mass), roof - baseY));
        double v0 = HeadMissile.speedForRise(rise);
        double clearBase = Math.max(topY, topY + highestBroken + 1);
        double gateRise = Math.max(0.0, clearBase - baseY);
        double gateSeconds = gateRise >= rise ? HeadMissile.flightSeconds(rise) / 2.0
                : (v0 - Math.sqrt(Math.max(0.0, v0 * v0 - 2.0 * HeadMissile.GRAVITY * gateRise))) / HeadMissile.GRAVITY;
        RandomSource random = RandomSource.create(Mth.getSeed(shellMin) ^ (now * 0x9E3779B97F4A7C15L));
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = HeadMissile.driftDistanceBlocks(w, d, random.nextDouble());
        double driftSeconds = Math.max(1.0, HeadMissile.flightSeconds(rise) - gateSeconds);
        double perTick = distance / (driftSeconds * HeadMissile.TICKS_PER_SECOND);
        head.launch(v0 / HeadMissile.TICKS_PER_SECOND, Math.cos(angle) * perTick, Math.sin(angle) * perTick, clearBase,
                Double.NaN, now, yaw);
        if (!level.addFreshEntity(head)) return null;
        return new Launch(head, false, Integer.MAX_VALUE, -1, broken, mass, ke, rise);
    }
}
