package dev.bwr.mod.power;

import com.mojang.logging.LogUtils;
import dev.bwr.core.turbine.TurbineBlades;
import dev.bwr.mod.accident.AccidentBlast;
import dev.bwr.mod.containment.ContainmentStrength;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The shaft excursion (TURBINE-MAINTENANCE.md): one flow's rotor half of a burst LP, broken loose, leaving through the end of its casing
 * and ploughing across the floor until it comes to rest beside the turbine as a {@link TurbineWreckBlock}. The laws are
 * {@link TurbineBlades}'; this class moves the piece through the world, in the tick it breaks loose, as {@link TurbineMissile} does a
 * missile.
 *
 * <h2>The path</h2>
 * The rotor half starts from its seat (1.5 blocks from the LP's middle along the shaft, towards its flow's end), drops onto the lower
 * casing and, spinning, rolls off perpendicular to its axis: towards the right of the LP's facing (the rotor turns so that its underside
 * moves to the left), and out past the end of the casing it came from. It slides a metre above the floor, its stripped discs a swath
 * three blocks wide and two high. Along it:
 * <ul>
 *   <li>its own casing's cells are passed (the end wall's {@link TurbineBlades#EXCURSION_CASING_J} is already paid); air and fluids
 *       cost nothing;</li>
 *   <li>a block costs what it costs a missile ({@link TurbineBlades#perforationJ}); with that much left it is broken through the
 *       blasts' protected path (an explosion start, a break event per block, vanilla drops), otherwise the piece stops against it;</li>
 *   <li>unbreakable blocks, a block a protection mod keeps, another turbine, pump or generator casing (which takes shaft damage from what
 *       is left), or a chunk that is not loaded stop it; a chunk is never loaded to look;</li>
 *   <li>every metre of floor costs {@link TurbineBlades#excursionFrictionJPerM}; it comes to rest when nothing is left;</li>
 *   <li>what is in its way is hurt by what it carries, once.</li>
 * </ul>
 * It comes to rest on the floor where it stopped, as a Turbine Shaft Wreck block in the last free cell it passed (placing it posts a
 * place event a protection mod can refuse; then, or with no free cell, it drops as a Damaged LP Blade Section instead). Breaking blocks
 * is never optional, as for every accident blast.
 */
public final class TurbineExcursion {
    private TurbineExcursion() {}

    private static final double STEP = 0.25;
    /** Furthest a rotor half goes, blocks (a frictionless floor would still stop here). */
    public static final int RANGE_BLOCKS = 48;
    /** The piece's centre over the turbine's floor while it slides, and its swath's half width across its path, blocks. */
    private static final double RIDE = 1.0, HALF_WIDTH = 0.9;

    public record Result(Vec3 origin, Vec3 direction, Vec3 end, double energyJ, List<BlockPos> broken, TurbineMissile.Stop stop, int entitiesHit,
                         BlockPos wreck) {
    }

    /** The unit direction a rotor half of the LP rolls off along: to the right of its facing, and out past the end of its {@code flow}. */
    public static Vec3 direction(PowerModuleBlockEntity lp, int flow) {
        Direction f = lp.getBlockState().getValue(PumpAssemblyBlock.FACING);
        Direction out = flow == 0 ? f : f.getOpposite();
        Direction side = f.getClockWise();
        return new Vec3(side.getStepX() + 0.35 * out.getStepX(), 0, side.getStepZ() + 0.35 * out.getStepZ()).normalize();
    }

    /** Where the rotor half of {@code flow} starts: the middle of its section, at the height it slides at. */
    public static Vec3 seat(PowerModuleBlockEntity lp, int flow) {
        Direction f = lp.getBlockState().getValue(PumpAssemblyBlock.FACING);
        BlockPos r = lp.getBlockPos();
        double s = flow == 0 ? 1.5 : -1.5;
        return new Vec3(r.getX() + 0.5 + f.getStepX() * s, r.getY() + RIDE, r.getZ() + 0.5 + f.getStepZ() * s);
    }

    /** Drives the rotor half of {@code flow} out of the LP. The LP's state is the caller's ({@link PowerTrain#excursion}); this only moves the piece. */
    public static Result launch(ServerLevel level, PowerModuleBlockEntity lp, int flow, double rpm) {
        Vec3 origin = seat(lp, flow);
        Vec3 dir = direction(lp, flow);
        Vec3 across = new Vec3(-dir.z, 0, dir.x);
        double energy = TurbineBlades.excursionEnergyJ(), left = energy, perM = TurbineBlades.excursionFrictionJPerM();
        BlockPos root = lp.getBlockPos();
        int floorY = root.getY();
        AccidentBlast.Guard guard = AccidentBlast.guard(level);
        List<BlockPos> broken = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Set<Entity> hit = new HashSet<>();
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        BlockPos rest = null;
        Vec3 at = origin;
        TurbineMissile.Stop stop = TurbineMissile.Stop.RANGE;
        double travelled = 0;
        travel:
        while (travelled < RANGE_BLOCKS) {
            travelled += STEP;
            Vec3 next = origin.add(dir.scale(travelled));
            left -= perM * STEP;
            if (left <= 0) {
                stop = TurbineMissile.Stop.STOPPED_IN_BLOCK;
                break;
            }
            for (double w : new double[]{0, -HALF_WIDTH, HALF_WIDTH}) {
                for (int dy = 0; dy <= 1; dy++) {
                    cell.set(Mth.floor(next.x + across.x * w), floorY + dy, Mth.floor(next.z + across.z * w));
                    if (!seen.add(cell.asLong())) {
                        continue;
                    }
                    if (level.isOutsideBuildHeight(cell)) {
                        continue;
                    }
                    if (!level.hasChunkAt(cell)) {
                        stop = TurbineMissile.Stop.UNLOADED;
                        break travel;
                    }
                    BlockState state = level.getBlockState(cell);
                    if (state.getBlock() instanceof PowerModuleBlock b && b.origin(cell, state).equals(root)) {
                        continue; // its own casing: the end wall's cost is paid
                    }
                    if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.canBeReplaced()) {
                        continue;
                    }
                    if (state.getBlock() instanceof PumpAssemblyBlock pa) {
                        if (pa.controller(level, cell, state) instanceof PowerModuleBlockEntity other) {
                            other.addShaftDamage(left / 1.0e7);
                        }
                        stop = TurbineMissile.Stop.CASING;
                        break travel;
                    }
                    if (!guard.mayBreak(cell, state)) {
                        stop = TurbineMissile.Stop.UNBREAKABLE;
                        break travel;
                    }
                    double cost = TurbineBlades.perforationJ(ContainmentStrength.contribution(level, cell, state));
                    if (left < cost) {
                        stop = TurbineMissile.Stop.STOPPED_IN_BLOCK;
                        break travel;
                    }
                    BlockPos p = cell.immutable();
                    if (AccidentBlast.breakProtected(level, next.x, next.y, next.z, 1.5f, new ArrayList<>(List.of(p))).isEmpty()) {
                        stop = TurbineMissile.Stop.PROTECTED;
                        break travel;
                    }
                    broken.add(p);
                    left -= cost;
                }
            }
            at = next;
            if (Mth.floor(travelled) != Mth.floor(travelled - STEP)) {
                left = TurbineMissile.hitEntities(level, at, 1.6, left, hit);
            }
            BlockPos centre = BlockPos.containing(at.x, floorY, at.z);
            if (free(level, centre, root)) {
                rest = centre;
            }
        }
        BlockPos wreck = settle(level, lp, flow, rest);
        TurbineMissile.trail(level, origin, at);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.5, at.z, 30, 1.2, 0.5, 1.2, 0.02);
        Result result = new Result(origin, dir, at, energy, List.copyOf(broken), stop, hit.size(), wreck);
        LogUtils.getLogger().info("Shaft excursion at {} flow {}: a {} t rotor half at {} rpm, {} blocks broken, {} entities hit, stopped ({}) at {}, wreck {}",
                root.toShortString(), flow, Math.round(TurbineBlades.EXCURSION_MASS_KG / 1000), Math.round(rpm), broken.size(), hit.size(), stop,
                BlockPos.containing(at).toShortString(), wreck == null ? "dropped as an item" : wreck.toShortString());
        TurbineEvents.post(new TurbineEvents.Excursion(level, root, origin, dir, at, energy, rpm, broken.size(), wreck));
        dev.bwr.mod.rumble.Rumble.impulse(level, at, energy * 10);
        return result;
    }

    /** A cell the rotor half can come to rest in: loaded, free, not its own casing. */
    private static boolean free(ServerLevel level, BlockPos p, BlockPos root) {
        BlockState s = dev.bwr.mod.world.LoadedWorld.state(level, p);
        return s != null && (s.isAir() || s.canBeReplaced() && !(s.getBlock() instanceof PowerModuleBlock));
    }

    /**
     * Lays the rotor half down at {@code rest} (dropped onto the floor below if it is over a gap, at most a few blocks): a Turbine Shaft
     * Wreck along the LP's shaft axis. A protection mod refusing the place, or no free cell, leaves it as a Damaged LP Blade Section item
     * at the end of its path. Returns where the wreck lies, or null if it is an item.
     */
    private static BlockPos settle(ServerLevel level, PowerModuleBlockEntity lp, int flow, BlockPos rest) {
        Direction f = lp.getBlockState().getValue(PumpAssemblyBlock.FACING);
        if (rest != null) {
            BlockPos p = rest;
            for (int i = 0; i < 6; i++) {
                BlockState below = dev.bwr.mod.world.LoadedWorld.state(level, p.below());
                if (below == null || !(below.isAir() || below.canBeReplaced())) {
                    break;
                }
                p = p.below();
            }
            BlockState wreck = BwrBlocks.TURBINE_SHAFT_WRECK.get().defaultBlockState().setValue(TurbineWreckBlock.AXIS, f.getAxis());
            var snapshot = BlockSnapshot.create(level.dimension(), level, p);
            var event = new BlockEvent.EntityPlaceEvent(snapshot, level.getBlockState(p.below()), FakePlayerFactory.getMinecraft(level));
            if (!NeoForge.EVENT_BUS.post(event).isCanceled() && level.setBlock(p, wreck, Block.UPDATE_ALL)) {
                if (level.getBlockEntity(p) instanceof TurbineWreckBlockEntity be) {
                    be.from(lp.getBlockPos(), flow);
                }
                return p;
            }
        }
        Vec3 end = rest != null ? Vec3.atCenterOf(rest) : seat(lp, flow);
        Containers.dropItemStack(level, end.x, end.y, end.z, TurbineLoadItem.section(1.0));
        return null;
    }
}
