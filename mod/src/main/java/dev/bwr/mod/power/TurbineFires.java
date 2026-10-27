package dev.bwr.mod.power;

import com.mojang.logging.LogUtils;
import dev.bwr.core.turbine.TurbineFire;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.world.LoadedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Turbine fires in the world (TURBINE-MAINTENANCE.md); the laws are {@link TurbineFire}'s. A fire belongs to a turbine section (any
 * section, the generator too): it is lit by a cut oil line (a disc fragment, a blade thrown down through the lower casing, a shaft
 * excursion's rotor half), by a bearing run dry past {@link TurbineFire#BEARING_IGNITION_C} with oil in the train's tanks, or by a
 * generator's hydrogen let out when the train comes apart. A train with no lube oil system has no oil to burn.
 *
 * <h2>Once a second, while it burns</h2>
 * The shaft train's own tick steps it (no ticker of its own): the pool burns down, the running oil pumps spray more oil out of the
 * train's tanks into it, the section loses condition, real Minecraft fire is kept lit on the floor beside its bearings at both ends (a
 * place event per block, so a protection mod can refuse it; only into loaded air), and smoke rises over it. Water in or beside any of
 * those places puts it out; so does running out of fuel. Nothing puts it out for you and nothing stops a pump.
 */
public final class TurbineFires {
    private TurbineFires() {}

    /** A blade thrown this steeply downwards (the sine of its release angle under this) goes through the lower casing and its oil lines. */
    static final double OIL_LINES_BELOW_SIN = -0.5;

    /** The Main Oil Tanks of a train's sections, loaded ones. */
    static List<TurbineAuxBlockEntity> tanks(List<PowerModuleBlockEntity> train) {
        List<TurbineAuxBlockEntity> out = new ArrayList<>();
        for (var m : train) {
            for (var a : m.auxEntities()) {
                if (a.kind() == TurbineAuxBlock.Kind.LUBE_OIL && !out.contains(a)) out.add(a);
            }
        }
        return out;
    }

    private static double drain(List<TurbineAuxBlockEntity> tanks, double litres) {
        double got = 0;
        for (var t : tanks) {
            if (got >= litres) break;
            got += t.drainOil(litres - got);
        }
        return got;
    }

    /** A piece thrown out of {@code at} cut an oil line: the oil in the lines pours onto the hot casing and catches, if the train has any. */
    static void lineCut(ServerLevel level, PowerModuleBlockEntity at, List<PowerModuleBlockEntity> train) {
        double got = drain(tanks(train), TurbineFire.LINE_SPILL_L);
        if (got > 0) light(level, at, TurbineFire.Kind.OIL, got, "an oil line cut by a thrown piece");
    }

    /** A bearing of {@code m} ran dry past the ignition point: the oil in its housing and drains catches. */
    static void bearing(ServerLevel level, PowerModuleBlockEntity m, List<TurbineAuxBlockEntity> lube) {
        double got = drain(lube, TurbineFire.BEARING_SPILL_L);
        if (got > 0) light(level, m, TurbineFire.Kind.OIL, got, "a wiped bearing");
    }

    /** The train came apart round a generator: its hydrogen escapes at the broken seals and burns there. */
    static void hydrogen(ServerLevel level, PowerModuleBlockEntity generator) {
        double kg = 0;
        for (var s : generator.genCoolingSkids()) kg += s.ventHydrogen(s.h2Kg());
        if (kg > 0) light(level, generator, TurbineFire.Kind.HYDROGEN, kg, "the generator's hydrogen at its broken seals");
    }

    private static void light(ServerLevel level, PowerModuleBlockEntity m, TurbineFire.Kind kind, double fuel, String cause) {
        if (!m.ignite(kind, fuel)) return;
        m.status = kind == TurbineFire.Kind.HYDROGEN ? "Hydrogen fire at the generator's seals" : "Lube oil fire at the bearings";
        LogUtils.getLogger().info("Turbine fire at {}: {} from {} ({} {})", m.getBlockPos().toShortString(), kind, cause, Math.round(fuel),
                kind == TurbineFire.Kind.HYDROGEN ? "kg" : "L");
        TurbineEvents.post(new TurbineEvents.Fire(level, m.getBlockPos(), kind, fuel));
        keepLit(level, m);
    }

    /** One second of every fire on the train's sections that this tick advances. */
    static void second(ServerLevel level, List<PowerModuleBlockEntity> train, List<PowerModuleBlockEntity> active) {
        for (var m : active) {
            if (!m.burning()) continue;
            if (wet(level, m)) {
                m.extinguish();
                m.status = "The fire is out";
                continue;
            }
            if (m.fire() == TurbineFire.Kind.OIL) {
                double pool = TurbineFire.poolAfter(m.fireFuel(), 1);
                double psig = m.aux().lubeGoverned ? m.aux().oilPsig : 0;
                double sprayed = drain(tanks(train), TurbineFire.sprayLitres(psig, 1)); // it burns as it comes
                if (pool <= 0 && sprayed <= 0) {
                    m.extinguish();
                    m.status = "The oil fire burned out";
                    continue;
                }
                m.setFireFuel(pool);
            } else {
                double left = TurbineFire.hydrogenAfter(m.fireFuel(), 1);
                if (left <= 0) {
                    m.extinguish();
                    m.status = "The hydrogen burned off";
                    continue;
                }
                m.setFireFuel(left);
            }
            m.addShaftDamage(TurbineFire.damage(1));
            keepLit(level, m);
        }
    }

    /**
     * Where a section's fire burns: on the floor beside its bearings, one block out from the casing on both sides at both ends (the oil
     * drains and the bearing pedestals are there; a generator's seals are at the same places).
     */
    public static List<BlockPos> fireCells(PowerModuleBlockEntity m) {
        Direction f = m.getBlockState().getValue(PumpAssemblyBlock.FACING);
        Direction side = f.getClockWise();
        int halfLength = m.block().halfLength(), halfWidth = m.lp() ? 3 : 2;
        List<BlockPos> out = new ArrayList<>(4);
        for (int end : new int[]{1, -1}) {
            for (int s : new int[]{1, -1}) {
                out.add(m.getBlockPos().relative(f, end * (halfLength - 1)).relative(side, s * (halfWidth + 1)));
            }
        }
        return out;
    }

    /** Water in or beside any of the fire's places. */
    private static boolean wet(ServerLevel level, PowerModuleBlockEntity m) {
        for (BlockPos p : fireCells(m)) {
            for (BlockPos q : new BlockPos[]{p, p.north(), p.south(), p.east(), p.west(), p.above()}) {
                BlockState s = LoadedWorld.state(level, q);
                if (s != null && s.getFluidState().is(FluidTags.WATER)) return true;
            }
        }
        return false;
    }

    /** Real fire in the loaded, empty places beside the bearings that can hold it, and smoke over the section. */
    private static void keepLit(ServerLevel level, PowerModuleBlockEntity m) {
        var fake = FakePlayerFactory.getMinecraft(level);
        for (BlockPos p : fireCells(m)) {
            BlockState here = LoadedWorld.state(level, p), below = LoadedWorld.state(level, p.below());
            if (here == null || below == null || !here.isAir()) continue;
            BlockState fire = BaseFireBlock.getState(level, p);
            if (!fire.canSurvive(level, p)) continue;
            var event = new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, p), below, fake);
            if (NeoForge.EVENT_BUS.post(event).isCanceled()) continue;
            level.setBlock(p, fire, Block.UPDATE_ALL);
            level.sendParticles(m.fire() == TurbineFire.Kind.HYDROGEN ? ParticleTypes.FLAME : ParticleTypes.LAVA,
                    p.getX() + 0.5, p.getY() + 0.6, p.getZ() + 0.5, 6, 0.4, 0.4, 0.4, 0.01);
        }
        BlockPos top = m.getBlockPos().above(5);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, top.getX() + 0.5, top.getY() + 0.2, top.getZ() + 0.5, 24, 1.6, 0.4, 1.6, 0.03);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, top.getX() + 0.5, top.getY(), top.getZ() + 0.5, 4, 1.2, 0.2, 1.2, 0.01);
    }
}
