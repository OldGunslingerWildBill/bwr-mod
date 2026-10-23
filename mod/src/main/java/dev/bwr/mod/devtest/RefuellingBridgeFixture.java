package dev.bwr.mod.devtest;

import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.mod.fuel.FuelAssemblyData;
import dev.bwr.mod.fuel.FuelAssemblyItem;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.refuelling.CanalGateBlock;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity;
import dev.bwr.mod.refuelling.RefuellingRailBlock;
import dev.bwr.mod.refuelling.TransferCanalBlock;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * A whole refuelling floor built block by block, as a player would: a 5 x 5 x 8 reactor (head off), a 9 x 9 x 8 spent fuel pool
 * three blocks to its east with the rims level, a cattle chute of two canal blocks and a gate between them, two runs of rail
 * along x on either side, and the bridge console beside a rail. With {@code O} the reactor's interior minimum corner:
 * <pre>
 *   reactor interior  x O.x..O.x+4   z O.z..O.z+4   y O.y..O.y+7   (roof / refuel floor at O.y+8)
 *   pool shell        x O.x+9..O.x+17 z O.z-2..O.z+6 y O.y+1..O.y+8  (interior floor O.y+2)
 *   cattle chute      x O.x+6..O.x+8  z O.z+2        y O.y..O.y+8   (a trench 9 deep: canal, gate, canal)
 *   rails             x O.x-1..O.x+18 z O.z-3 and O.z+7  y O.y+9
 *   console           O.x-1, O.y+9, O.z-4
 * </pre>
 * No CC types here, so a {@code @GameTestHolder} class may use it.
 */
final class RefuellingBridgeFixture {

    final ServerLevel level;
    final BlockPos o;
    ReactorControllerBlockEntity reactor;
    SpentFuelPoolFixture pool;
    RefuellingBridgeBlockEntity bridge;
    final BlockPos console;
    final BlockPos gate;
    final BlockPos canalNear;
    final BlockPos canalFar;
    /** Canal blocks stacked in each column of the chute (the top one is level with the refuel floor). */
    static final int CANAL_DEPTH = 9;
    final List<Phase> phases = new ArrayList<>();

    private RefuellingBridgeFixture(ServerLevel level, BlockPos o) {
        this.level = level;
        this.o = o;
        this.console = o.offset(-1, 9, -4);
        this.canalNear = o.offset(6, 8, 2);
        this.gate = o.offset(7, 8, 2);
        this.canalFar = o.offset(8, 8, 2);
    }

    static RefuellingBridgeFixture build(ServerLevel level, BlockPos o, int racks) throws Exception {
        RefuellingBridgeFixture f = new RefuellingBridgeFixture(level, o);
        for (int cx = (o.getX() - 24) >> 4; cx <= (o.getX() + 40) >> 4; cx++) {
            for (int cz = (o.getZ() - 24) >> 4; cz <= (o.getZ() + 24) >> 4; cz++) {
                FixtureChunks.force(level, o, cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        // the old floor first: an earlier run's console still holds a carried assembly
        if (level.getBlockEntity(f.console) instanceof RefuellingBridgeBlockEntity old) {
            old.removalDrops();
        }
        for (int x = -2; x <= 20; x++) {
            for (int z = -5; z <= 8; z++) {
                for (int y = 8; y <= 11; y++) {
                    level.setBlock(o.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        f.reactor = CompactCoreRegressionTests.build(level, o, 5, 5, 8, false);
        f.reactor.setVesselState(VesselState.REFUELING);
        f.floodCavity(3.0);
        f.pool = SpentFuelPoolFixture.build(level, o.offset(9, 1, -2), 9, 9, 8, racks);
        for (int x = -1; x <= 18; x++) {
            for (int z : new int[]{-3, 7}) {
                level.setBlock(o.offset(x, 9, z), BwrBlocks.REFUELLING_RAIL.get().defaultBlockState()
                        .setValue(RefuellingRailBlock.AXIS, Direction.Axis.X), 2);
            }
        }
        for (int dy = 0; dy < CANAL_DEPTH; dy++) {
            boolean top = dy == 0;
            boolean floor = dy == CANAL_DEPTH - 1;
            for (BlockPos column : new BlockPos[]{f.canalNear, f.gate, f.canalFar}) {
                var block = column.equals(f.gate) ? BwrBlocks.CANAL_GATE.get() : BwrBlocks.TRANSFER_CANAL.get();
                var state = block.defaultBlockState().setValue(TransferCanalBlock.AXIS, Direction.Axis.X)
                        .setValue(TransferCanalBlock.TOP, top).setValue(TransferCanalBlock.FLOOR, floor);
                if (column.equals(f.gate)) {
                    state = state.setValue(CanalGateBlock.OPEN, true);
                }
                level.setBlock(column.below(dy), state, 2);
            }
        }
        level.setBlock(f.console, BwrBlocks.REFUELLING_BRIDGE_CONSOLE.get().defaultBlockState(), 2);
        f.bridge = (RefuellingBridgeBlockEntity) level.getBlockEntity(f.console);
        f.bridge.refreshSite();
        return f;
    }

    /** The player floods the cavity: the vessel's collapsed level is set to {@code metres} over the top of active fuel. */
    void floodCavity(double metres) {
        reactor.core().getPressureVessel().setCollapsedLevelIn(dev.bwr.core.PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN + metres / 0.0254);
    }

    /** Fills the pool to the rim (7 m, 3 m over the racks). */
    void fillPool(double metres) {
        pool.be.pool().restore(metres * 49_000.0, 25.0);
        pool.be.simulateSeconds(1, level.getGameTime());
    }

    void closeGate() {
        setGate(false);
    }

    void openGate() {
        setGate(true);
    }

    private void setGate(boolean open) {
        for (int dy = 0; dy < CANAL_DEPTH; dy++) {
            level.setBlock(gate.below(dy), level.getBlockState(gate.below(dy)).setValue(CanalGateBlock.OPEN, open), 3);
        }
    }

    /** A shallow trench: only the top {@code keep} canal blocks of each column are left (the lowest of those takes the floor). */
    void shallowCanal(int keep) {
        for (int dy = keep; dy < CANAL_DEPTH; dy++) {
            for (BlockPos column : new BlockPos[]{canalNear, gate, canalFar}) {
                level.setBlock(column.below(dy), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /** What a bucket does to one column of the trench. */
    void setCanalWater(boolean wet) {
        for (int dy = 0; dy < CANAL_DEPTH; dy++) {
            level.setBlock(canalNear.below(dy), level.getBlockState(canalNear.below(dy)).setValue(TransferCanalBlock.WATER, wet), 3);
        }
    }

    void drainCanal() {
        setCanalWater(false);
    }

    /** An assembly of fresh burnup; unloading it from the core stamps the discharge. */
    static ItemStack fresh(double burnup) {
        return FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(), new FuelAssemblyData("leu", 0.035, 180.0, burnup, 1.0));
    }

    int corePosition(int index) {
        return reactor.corePositions()[index];
    }

    /** Steps the bridge a tick at a time, as its scheduled tick would, until it is idle or halted. Returns the ticks used. */
    int runUntilStill(int limit) {
        int n = 0;
        while (bridge.busy() && n < limit) {
            record();
            bridge.scheduledTick(level);
            n++;
        }
        record();
        return n;
    }

    /** Steps until the bridge reaches {@code phase} (its first tick), or is still. Returns the ticks used. */
    int runUntil(Phase phase, int limit) {
        int n = 0;
        while (bridge.busy() && bridge.phase() != phase && n < limit) {
            record();
            bridge.scheduledTick(level);
            n++;
        }
        record();
        return n;
    }

    void record() {
        Phase now = bridge.phase();
        if (phases.isEmpty() || phases.get(phases.size() - 1) != now) {
            phases.add(now);
        }
    }

    /** Everything that holds fuel: the core, the pool, the grapple. */
    int assemblies() {
        return reactor.core().getCoreLoading().loadedAssemblyCount() + pool.be.assemblyCount() + (bridge.carried().isEmpty() ? 0 : 1);
    }

    void dispose() {
        FixtureCleanup.drainAt(level, reactor.getBlockPos());
        bridge.removalDrops();
        pool.dispose();
        for (int x = -2; x <= 20; x++) {
            for (int z = -5; z <= 8; z++) {
                for (int y = -2; y <= 11; y++) {
                    BlockPos p = o.offset(x, y, z);
                    if (x >= 9 && x <= 18 && z >= -3 && z <= 7 && y >= 1 && y <= 9) {
                        continue; // the pool fixture cleared its own box
                    }
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        FixtureCleanup.discardItems(level, o.offset(-3, -3, -6), o.offset(21, 12, 9));
        for (int cx = (o.getX() - 24) >> 4; cx <= (o.getX() + 40) >> 4; cx++) {
            for (int cz = (o.getZ() - 24) >> 4; cz <= (o.getZ() + 24) >> 4; cz++) {
                FixtureChunks.force(level, o, cx, cz, false);
            }
        }
    }

    SpentFuelPoolBlockEntity poolBe() {
        return pool.be;
    }
}
