package dev.bwr.mod.devtest;

import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.StudTensionerBlockEntity;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity;
import dev.bwr.mod.refuelling.CraneRunwayRailBlock;
import dev.bwr.mod.refuelling.HeadStandBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * A refuel floor with the reactor building crane, built block by block as a player would, on top of {@link RefuellingBridgeFixture}
 * (a 5 x 5 x 8 reactor, a spent fuel pool, the cattle chute and the refuelling bridge). With {@code O} the reactor's interior minimum
 * corner:
 * <pre>
 *   crane runway rails  x O.x-3..O.x+22  z O.z-14 and O.z+9   y O.y+railDy (default 14), console beside the first rail
 *   head stand          O.x+2, O.y+9, O.z-9   (the middle of a 7 x 7 head, clear of the 7 x 7 vessel and between the rails)
 *   equipment pads      O.x+6, O.y+1, O.z+5 and O.z-1  (in the pit beside the cattle chute)
 *   stud tensioner      O.x-3, O.y+9, O.z+2   (on the refuel floor beside the well)
 * </pre>
 * The head starts ON the vessel with every stud at load, the dryer and the separator assembly in, and the vessel flooded to its flange.
 * No CC types here, so a {@code @GameTestHolder} class may use it.
 */
final class CraneFixture {

    final RefuellingBridgeFixture base;
    final ServerLevel level;
    final BlockPos o;
    final int railDy;
    final BlockPos console;
    final BlockPos stand;
    final BlockPos tensionerPos;
    final List<BlockPos> pads = new ArrayList<>();
    BuildingCraneBlockEntity crane;
    StudTensionerBlockEntity tensioner;
    HeadStandBlockEntity standBe;
    /** FE a program or cable put into the tensioner's pack, for the energy accounting of a run. */
    long feDelivered;
    final List<Phase> phases = new ArrayList<>();

    private CraneFixture(RefuellingBridgeFixture base, int railDy) {
        this.base = base;
        this.level = base.level;
        this.o = base.o;
        this.railDy = railDy;
        this.console = o.offset(-4, railDy, -14);
        this.stand = o.offset(2, 9, -9);
        this.tensionerPos = o.offset(-3, 9, 2);
        pads.add(o.offset(6, 1, 5));
        pads.add(o.offset(6, 1, -1));
    }

    static CraneFixture build(ServerLevel level, BlockPos o) throws Exception {
        return build(level, o, 14);
    }

    static CraneFixture build(ServerLevel level, BlockPos o, int railDy) throws Exception {
        RefuellingBridgeFixture base = RefuellingBridgeFixture.build(level, o, 2);
        CraneFixture f = new CraneFixture(base, railDy);
        ReactorControllerBlockEntity r = base.reactor;
        // the bridge fixture starts open with both assemblies elsewhere: the crane's start is the plant as it runs, head on, everything in
        r.internalIn(VesselInternal.DRYER);
        r.internalIn(VesselInternal.SEPARATOR);
        r.setVesselState(VesselState.SHUTDOWN);
        r.core().getPressureVessel().setCollapsedLevelIn(60.0);
        for (BlockPos pad : f.pads) {
            for (int y = 1; y <= 8; y++) {
                level.setBlock(pad.above(y), Blocks.AIR.defaultBlockState(), 2);
            }
            level.setBlock(pad, BwrBlocks.EQUIPMENT_PAD.get().defaultBlockState(), 3);
        }
        for (int x = -3; x <= 22; x++) {
            for (int z : new int[]{-14, 9}) {
                level.setBlock(o.offset(x, railDy, z), BwrBlocks.CRANE_RUNWAY_RAIL.get().defaultBlockState()
                        .setValue(CraneRunwayRailBlock.AXIS, Direction.Axis.X), 2);
            }
        }
        level.setBlock(f.console, BwrBlocks.BUILDING_CRANE_CONSOLE.get().defaultBlockState(), 2);
        level.setBlock(f.stand, BwrBlocks.HEAD_STAND.get().defaultBlockState(), 2);
        level.setBlock(f.tensionerPos, BwrBlocks.STUD_TENSIONER.get().defaultBlockState(), 2);
        f.crane = (BuildingCraneBlockEntity) level.getBlockEntity(f.console);
        f.standBe = (HeadStandBlockEntity) level.getBlockEntity(f.stand);
        f.tensioner = (StudTensionerBlockEntity) level.getBlockEntity(f.tensionerPos);
        f.crane.refreshSite();
        return f;
    }

    ReactorControllerBlockEntity reactor() {
        return base.reactor;
    }

    /** Steps the crane a tick at a time, as its scheduled tick would, until it is idle or halted. Returns the ticks used. */
    int runCrane(int limit) {
        int n = 0;
        while (crane.busy() && n < limit) {
            record();
            crane.scheduledTick(level);
            n++;
        }
        record();
        return n;
    }

    /** Steps until the crane reaches {@code phase} (its first tick), or is still. */
    int runCraneUntil(Phase phase, int limit) {
        int n = 0;
        while (crane.busy() && crane.phase() != phase && n < limit) {
            record();
            crane.scheduledTick(level);
            n++;
        }
        record();
        return n;
    }

    private void record() {
        Phase now = crane.phase();
        if (phases.isEmpty() || phases.get(phases.size() - 1) != now) {
            phases.add(now);
        }
    }

    /**
     * Steps the stud tensioner a tick at a time until it stops, a cable keeping its pack topped up whenever it falls under one stud's energy
     * (the FE put in is counted in {@link #feDelivered}). Returns the ticks used.
     */
    int runTensioner(int limit, boolean powered) {
        int n = 0;
        while (tensioner.running() && n < limit) {
            while (powered && tensioner.energyFe() < 6_000) {
                // a cable gives the pack at most one tick's share per call
                int accepted = tensioner.energy().receiveEnergy(StudTensionerBlockEntity.FE_CAPACITY, false);
                feDelivered += accepted;
                if (accepted == 0) {
                    break;
                }
            }
            tensioner.scheduledTick(level);
            n++;
        }
        return n;
    }

    /** The player flooded the cavity to {@code metres} over the fuel, as the bridge fixture does. */
    void floodCavity(double metres) {
        base.floodCavity(metres);
    }

    void dispose() {
        try {
            if (crane != null) {
                crane.abort();
                crane.dropCarried(level);
            }
        } catch (RuntimeException ignored) {
            // the fixture is being thrown away
        }
        for (int x = -5; x <= 23; x++) {
            for (int z : new int[]{-14, 9}) {
                level.setBlock(o.offset(x, railDy, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        level.setBlock(console, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(stand, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(tensionerPos, Blocks.AIR.defaultBlockState(), 2);
        base.dispose();
    }
}
