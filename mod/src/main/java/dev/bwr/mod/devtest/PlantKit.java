package dev.bwr.mod.devtest;

import dev.bwr.mod.condenser.CondenserBlock;
import dev.bwr.mod.condenser.CondenserBlockEntity;
import dev.bwr.mod.cooling.CoolingBlock;
import dev.bwr.mod.cooling.CoolingBlockEntity;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.eccs.CondensateStorageTankBlockEntity;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.eccs.SuctionSource;
import dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.RpvWaterInjectionPortBlock;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import dev.bwr.mod.steam.TurbineSteamOutletBlockEntity;
import dev.bwr.mod.steam.TurbineValveBlock;
import dev.bwr.mod.steam.TurbineValveBlockEntity;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.ArrayList;
import java.util.List;

/**
 * A whole plant in one force-loaded area, built from the same pieces the other fixtures use: a formed, fuelled 15 x 15
 * reactor with all 185 drives, two recirculation pumps, two main steam lines (nozzle, control valve, MSIV, turbine
 * outlet), a suppression pool with an RHR loop, motor feed, HPCS and LPCS pumps drawing from a condensate tank, a
 * condenser, a cooling tower with its circulating pump, a relief valve. Everything is placed through the real
 * blocks. {@link #energise} stands in for the cables a player would run (a machine only draws what its own buffer
 * takes, so topping the buffers up once a second keeps every machine running as it would on a live grid).
 *
 * <p>The kit is shared by the tick profile ({@link TickBudgetTests}) and the chunk-boundary tests.
 */
final class PlantKit {
    final ServerLevel level;
    /** South-west-bottom corner of the reactor interior. */
    final BlockPos min;
    ReactorControllerBlockEntity reactor;
    final List<String> notes = new ArrayList<>();
    final List<RecirculationPumpBlockEntity> recirculation = new ArrayList<>();
    final List<EccsPumpBlockEntity> eccs = new ArrayList<>();
    final List<FeedwaterPumpBlockEntity> feed = new ArrayList<>();
    final List<BlockPos> msivs = new ArrayList<>();
    final List<TurbineSteamOutletBlockEntity> outlets = new ArrayList<>();
    final List<CoolingBlockEntity> cooling = new ArrayList<>();
    CondenserBlockEntity condenser;
    SuppressionPoolBlockEntity pool;
    CondensateStorageTankBlockEntity tank;
    BlockPos lo;
    BlockPos hi;

    PlantKit(ServerLevel level, BlockPos min) {
        this.level = level;
        this.min = min;
        this.lo = min.offset(-60, -12, -24);
        this.hi = min.offset(60, 24, 70);
    }

    // ------------------------------------------------------------------ chunks and cleanup

    private net.neoforged.neoforge.common.util.FakePlayer watcher;

    /**
     * Puts a fake player next to {@code where}, in the level's player list, so that chunk tracking, block entity
     * update packets and "send to players tracking this chunk" all do their real work (their packets go nowhere).
     * Without a watcher the server builds none of them and a measured tick is cheaper than a real one.
     */
    void watch(BlockPos where) {
        watcher = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(
                java.util.UUID.nameUUIDFromBytes("bwr-tick-watcher".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "tick-watcher"));
        watcher.setPos(where.getX() + .5, where.getY(), where.getZ() + .5);
        level.addNewPlayer(watcher);
    }

    void unwatch() {
        if (watcher != null) {
            level.removePlayerImmediately(watcher, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            watcher = null;
        }
    }

    int trackers() {
        return level.getChunkSource().chunkMap.getPlayers(new net.minecraft.world.level.ChunkPos(reactor.getBlockPos()), false).size();
    }

    void hold(boolean force) {
        for (int x = lo.getX() >> 4; x <= hi.getX() >> 4; x++) {
            for (int z = lo.getZ() >> 4; z <= hi.getZ() >> 4; z++) {
                FixtureChunks.force(level, this, x, z, force);
                if (force) {
                    level.getChunk(x, z);
                }
            }
        }
    }

    void clear() {
        FixtureCleanup.drainFuel(level, lo, hi);
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            if (!level.getBlockState(p).isAir()) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        FixtureCleanup.discardItems(level, lo, hi);
    }

    // ------------------------------------------------------------------ small helpers

    private void put(BlockPos p, BlockState s) {
        level.setBlock(p, s, 3);
    }

    void pipe(Block material, BlockPos... points) {
        if (points.length == 1) {
            put(points[0], material.defaultBlockState());
        }
        for (int i = 1; i < points.length; i++) {
            BlockPos a = points[i - 1];
            BlockPos b = points[i];
            int dx = Integer.signum(b.getX() - a.getX());
            int dy = Integer.signum(b.getY() - a.getY());
            int dz = Integer.signum(b.getZ() - a.getZ());
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1) {
                throw new IllegalArgumentException("pipe segment is not axis aligned: " + a + " " + b);
            }
            for (BlockPos p = a; ; p = p.offset(dx, dy, dz)) {
                put(p, material.defaultBlockState());
                if (p.equals(b)) {
                    break;
                }
            }
        }
    }

    void water(BlockPos... points) {
        pipe(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get(), points);
    }

    void steam(BlockPos... points) {
        pipe(BwrBlocks.PRESSURISED_TUBE.get(), points);
    }

    /**
     * Water line from a flange to a target flange: out of the flange three blocks (clear of the machine's own cells),
     * up to {@code y}, along x, along z, then down.
     */
    void route(BlockPos from, Direction out, int y, BlockPos to) {
        BlockPos clear = from.relative(out, 3);
        water(from, clear, new BlockPos(clear.getX(), y, clear.getZ()), new BlockPos(to.getX(), y, clear.getZ()),
                new BlockPos(to.getX(), y, to.getZ()), to);
    }

    private void place(PumpAssemblyBlock b, BlockPos root, Direction facing) {
        BlockState s = b.placementState().setValue(PumpAssemblyBlock.FACING, facing);
        put(root, s);
        b.setPlacedBy(level, root, s, null, new ItemStack(b));
    }

    private BlockPos flange(PumpAssemblyBlock b, BlockPos root, BlockState s, AssemblyPort role) {
        return b.portPosition(root, s, role).relative(b.portFace(s, role));
    }

    private BlockPos at(int x, int y, int z) {
        return min.offset(x, y, z);
    }

    // ------------------------------------------------------------------ the plant

    /** Builds everything. Each part reports instead of aborting, so one run shows what is and is not working. */
    void build() throws Exception {
        reactor = CompactCoreRegressionTests.build(level, min, 15, 15, false);
        if (!reactor.isFormed()) {
            throw new IllegalStateException("plant reactor did not form: " + reactor.statusLines());
        }
        FcdTestSupport.supplyDrives(level, reactor);
        for (int position : reactor.corePositions()) {
            reactor.loadAssembly(position, new ItemStack(dev.bwr.mod.registry.BwrItems.FUEL_ASSEMBLY.get()));
        }
        reactor.core().initialiseAtTotalPowerFraction(0.8);
        reactor.rods().setCore(reactor.core());
        part("recirculation", this::recirculation);
        part("steam lines", this::steamLines);
        part("condensate tank and feed", this::feedwater);
        part("suppression pool and RHR", this::poolAndRhr);
        part("condenser and cooling", this::condenserAndCooling);
        part("containment room", this::containmentRoom);
        part("panel boards", this::panels);
        reactor.markStructureDirty();
    }

    private interface Part {
        void run() throws Exception;
    }

    private void part(String name, Part part) {
        try {
            part.run();
            notes.add("built " + name);
        } catch (Exception | AssertionError e) {
            notes.add("FAILED " + name + ": " + e);
            e.printStackTrace();
        }
    }

    private void recirculation() {
        for (int k = 0; k < 2; k++) {
            int z = 10 * k;
            put(at(15, 6, 2 + z), BwrBlocks.RECIRCULATION_OUTLET.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING, Direction.EAST));
            put(at(15, 0, 2 + z), BwrBlocks.RECIRCULATION_INLET.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING, Direction.EAST));
            var block = BwrBlocks.RECIRCULATION_PUMP.get();
            BlockPos root = at(21, 0, 5 + z);
            var s = block.placementState();
            level.removeBlock(root, false);
            put(root, s);
            block.setPlacedBy(level, root, s, null, new ItemStack(block));
            int[][] paths = {{21, 0, 8, 21, 6, 8}, {21, 6, 8, 17, 6, 8}, {17, 6, 8, 17, 6, 2}, {17, 6, 2, 16, 6, 2}, {21, 2, 2, 21, 0, 2}, {21, 0, 2, 16, 0, 2}};
            for (int[] path : paths) {
                water(at(path[0], path[1], path[2] + z), at(path[3], path[4], path[5] + z));
            }
            recirculation.add((RecirculationPumpBlockEntity) level.getBlockEntity(root));
        }
    }

    private void steamLines() {
        for (int x : new int[]{4, 10}) {
            put(at(x, 5, -1), BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState());
            BlockPos msiv = at(x, 5, -4);
            steam(at(x, 5, -2));
            var valveBlock = BwrBlocks.TURBINE_CONTROL_VALVE.get();
            put(at(x, 5, -3), valveBlock.defaultBlockState().setValue(TurbineValveBlock.FACING, Direction.NORTH));
            var b = BwrBlocks.MSIV.get();
            var ms = b.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
            put(msiv, ms);
            b.setPlacedBy(level, msiv, ms, null, new ItemStack(b));
            steam(at(x, 5, -5));
            put(at(x, 5, -6), BwrBlocks.TURBINE_STEAM_OUTLET.get().defaultBlockState());
            var valve = (TurbineValveBlockEntity) level.getBlockEntity(at(x, 5, -3));
            valve.setTarget(1);
            valve.stroke(3);
            msivs.add(msiv);
            outlets.add((TurbineSteamOutletBlockEntity) level.getBlockEntity(at(x, 5, -6)));
            outlets.get(outlets.size() - 1).setCommandedFlowKgPerS(250);
        }
        // A relief valve on a short stub off the first line.
        put(at(7, 9, -1), BwrBlocks.SAFETY_RELIEF_VALVE.get().defaultBlockState());
    }

    private void feedwater() {
        // Condensate tank west of the reactor, drawn on by a motor feed pump and the two spray pumps.
        BlockPos tankPos = at(-30, 0, 24);
        put(tankPos, BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState());
        tank = (CondensateStorageTankBlockEntity) level.getBlockEntity(tankPos);
        tank.fillKg(100000);
        PumpAssemblyBlock[] blocks = {BwrBlocks.MOTOR_FEED_PUMP.get(), BwrBlocks.HPCS_PUMP.get(), BwrBlocks.LPCS_PUMP.get()};
        for (int i = 0; i < blocks.length; i++) {
            PumpAssemblyBlock b = blocks[i];
            BlockPos root = at(2 + 12 * i, 0, 28);
            place(b, root, Direction.NORTH);
            BlockState machine = level.getBlockState(root);
            // The reactor end: an injection port in the south wall, flange outside.
            BlockPos port = at(3 + 3 * i, 5, 15);
            put(port, BwrBlocks.RPV_WATER_INJECTION_PORT.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING, Direction.SOUTH));
            BlockPos suction = flange(b, root, machine, AssemblyPort.WATER_SUCTION);
            Direction face = b.portFace(machine, AssemblyPort.WATER_SUCTION);
            route(flange(b, root, machine, AssemblyPort.WATER_DISCHARGE), b.portFace(machine, AssemblyPort.WATER_DISCHARGE), 20 + i, port.south());
            // The suction end: a short pipe to a tank of its own.
            water(suction, suction.relative(face));
            BlockPos own = suction.relative(face, 2);
            put(own, BwrBlocks.CONDENSATE_STORAGE_TANK.get().defaultBlockState());
            ((CondensateStorageTankBlockEntity) level.getBlockEntity(own)).fillKg(100000);
            var be = level.getBlockEntity(root);
            if (be instanceof FeedwaterPumpBlockEntity f) {
                f.setComputerControlled(true);
                f.setRunning(true);
                feed.add(f);
            } else if (be instanceof EccsPumpBlockEntity e) {
                e.setComputerControlled(true);
                e.setSuctionSource(SuctionSource.CONDENSATE_TANK);
                e.setRunning(true);
                eccs.add(e);
            }
        }
    }

    private void poolAndRhr() throws Exception {
        BlockPos origin = at(-34, -1, 40);
        pool = SuppressionBasinRegressionTests.build(level, origin);
        var rhr = SuppressionBasinRegressionTests.connect(level, origin);
        rhr.setComputerControlled(true);
        rhr.setRunning(true);
        eccs.add(rhr);
        pool.pool().fromArray(new dev.bwr.core.pool.SuppressionPool(105_000, 40).toArray());
    }

    private void condenserAndCooling() {
        var cb = BwrBlocks.ARABELLE_CONDENSER.get();
        BlockPos root = at(30, 0, 40);
        var s = cb.defaultBlockState().setValue(CondenserBlock.FACING, Direction.NORTH);
        put(root, s);
        cb.setPlacedBy(level, root, s, null, new ItemStack(cb));
        condenser = (CondenserBlockEntity) level.getBlockEntity(root);
        var tower = BwrBlocks.MECHANICAL_DRAFT_TOWER.get();
        BlockPos towerRoot = at(30, 0, 60);
        var ts = tower.defaultBlockState().setValue(CoolingBlock.FACING, Direction.NORTH);
        put(towerRoot, ts);
        tower.setPlacedBy(level, towerRoot, ts, null, new ItemStack(tower));
        var te = (CoolingBlockEntity) level.getBlockEntity(towerRoot);
        te.setTarget(1);
        cooling.add(te);
        var pump = BwrBlocks.CIRCULATING_WATER_PUMP.get();
        BlockPos pumpRoot = at(44, 0, 60);
        var ps = pump.defaultBlockState().setValue(CoolingBlock.FACING, Direction.NORTH);
        put(pumpRoot, ps);
        pump.setPlacedBy(level, pumpRoot, ps, null, new ItemStack(pump));
        var pe = (CoolingBlockEntity) level.getBlockEntity(pumpRoot);
        pe.setTarget(1);
        cooling.add(pe);
    }

    dev.bwr.mod.containment.ContainmentControllerBlockEntity room;

    /** A sealed obsidian room, 21 blocks to a side inside (9,261 cells), with its controller: the survey and the rating. */
    private void containmentRoom() {
        BlockPos lo = min.offset(-58, 0, -22);
        BlockPos hi = lo.offset(22, 22, 22);
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            boolean shell = p.getX() == lo.getX() || p.getX() == hi.getX() || p.getY() == lo.getY() || p.getY() == hi.getY()
                    || p.getZ() == lo.getZ() || p.getZ() == hi.getZ();
            level.setBlock(p, (shell ? Blocks.OBSIDIAN : Blocks.AIR).defaultBlockState(), 2);
        }
        BlockPos at = lo.offset(11, 1, 11);
        put(at, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState());
        room = (dev.bwr.mod.containment.ContainmentControllerBlockEntity) level.getBlockEntity(at);
        room.markDirty();
    }

    /** Panel components with a live plant input, as bound by the linker. */
    int boundInputs;
    private net.neoforged.neoforge.common.util.FakePlayer operator;

    /**
     * Two boards of 4 x 2 panel blocks in front of the reactor, 24 dial gauges each bound to a signal of a plant
     * device (the reactor, the pumps, the valves), as a player would wire them with the linker. The sampler re-reads
     * every one of them several times a second and ships changed values to whoever watches the chunk.
     */
    private void panels() {
        BlockPos origin = min.offset(8, 14, -16);
        operator = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(
                java.util.UUID.nameUUIDFromBytes("bwr-tick-operator".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "tick-operator"));
        operator.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        operator.getInventory().clearContent();
        operator.setShiftKeyDown(false);
        operator.setPos(origin.getX() - 1.5, origin.getY() - 1.0, origin.getZ() - 2.5);
        List<BlockPos> devices = new ArrayList<>();
        devices.add(reactor.getBlockPos());
        for (var p : recirculation) {
            devices.add(p.getBlockPos());
        }
        devices.addAll(msivs);
        for (var o : outlets) {
            devices.add(o.getBlockPos());
        }
        for (var e : eccs) {
            devices.add(e.getBlockPos());
        }
        for (var f : feed) {
            devices.add(f.getBlockPos());
        }
        int next = 0;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                BlockPos block = origin.relative(Direction.WEST, col).below(row);
                put(block, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(dev.bwr.mod.panel.PanelBlock.FACING, Direction.NORTH));
                for (int g = 0; g < 3; g++) {
                    var component = dev.bwr.mod.panel.PanelOps.placeDirect(level, block, 1 + 5 * g, 1,
                            dev.bwr.mod.panel.component.ComponentTypes.DIAL_GAUGE,
                            dev.bwr.mod.panel.component.Footprint.of(4, 4));
                    if (component == null) {
                        notes.add("panel gauge did not fit at " + block);
                        continue;
                    }
                    BlockPos device = devices.get(next++ % devices.size());
                    var provider = dev.bwr.mod.signal.SignalProviders.at(level, device);
                    if (provider == null || provider.signals().isEmpty()) {
                        continue;
                    }
                    var signals = provider.signals();
                    String key = signals.get(next % signals.size()).key();
                    var owner = (dev.bwr.mod.panel.PanelBlockEntity) level.getBlockEntity(block);
                    var outcome = bind(owner, component.id(), device, key);
                    if (outcome.result() == dev.bwr.mod.panel.wiring.WiringOps.Result.OK) {
                        boundInputs++;
                    }
                }
            }
        }
    }

    private dev.bwr.mod.panel.wiring.WiringOps.Outcome bind(dev.bwr.mod.panel.PanelBlockEntity owner, int id, BlockPos device, String key) {
        ItemStack linker = new ItemStack(dev.bwr.mod.registry.BwrItems.PANEL_LINKER.get());
        linker.set(dev.bwr.mod.fuel.BwrDataComponents.LINKED_DEVICE.get(), net.minecraft.core.GlobalPos.of(level.dimension(), device));
        operator.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, linker);
        return dev.bwr.mod.panel.wiring.WiringOps.bindDetailed(operator, new dev.bwr.mod.panel.net.PanelPayloads.Bind(
                owner.getBlockPos(), id, false, 0, dev.bwr.mod.panel.wiring.WiringOps.BindKind.PLANT.ordinal(),
                device, key, BlockPos.ZERO, 0, 0, 1.0));
    }

    // ------------------------------------------------------------------ keeping it running

    /** Tops up every machine buffer, as a connected grid would. Call about once a second. */
    void energise() {
        for (var p : recirculation) {
            p.receiveEnergyFe(p.getEnergyCapacityFe(), false);
        }
        for (var e : eccs) {
            e.energy().setStored(e.energy().getMaxEnergyStored());
        }
        for (var f : feed) {
            f.energy().setStored(f.energy().getMaxEnergyStored());
        }
        for (BlockPos m : msivs) {
            var cap = level.getCapability(Capabilities.EnergyStorage.BLOCK, m.above(2), Direction.UP);
            if (cap != null) {
                cap.receiveEnergy(20_000, false);
            }
        }
        for (var c : cooling) {
            if (c.power() != null) {
                c.power().receiveEnergy(Integer.MAX_VALUE, false);
            }
        }
        for (var o : outlets) {
            o.drainMilliBuckets(Long.MAX_VALUE, false);
        }
    }

    /** One line per machine class: is it doing anything. */
    List<String> status() {
        List<String> out = new ArrayList<>(notes);
        out.add("reactor formed=" + reactor.isFormed() + " power=" + String.format("%.3f", reactor.core().getThermalPowerMW())
                + " MW pressure=" + String.format("%.1f", reactor.core().getPressurePsig()) + " psig");
        for (var p : recirculation) {
            out.add("recirc pump speed=" + p.getActualSpeedFraction() + " controller=" + p.getControllerPos());
        }
        for (var o : outlets) {
            out.add("turbine outlet delivered=" + o.getDeliveredFlowKgPerS() + " attached=" + o.isAttached() + " nozzleFed=" + o.isNozzleFed()
                    + " nozzles=" + o.getFeedNozzleCount() + " msivs=" + o.getIsolationValveCount());
        }
        for (int x : new int[]{4, 10}) {
            if (level.getBlockEntity(at(x, 5, -1)) instanceof dev.bwr.mod.reactor.RpvSteamOutletBlockEntity n) {
                out.add("nozzle x=" + x + " lineAttached=" + n.isSteamLineAttached() + " outlets=" + n.getLineTurbineOutletCount()
                        + " valves=" + n.getLineIsolationValveCount() + " open=" + n.getLineOpenFraction()
                        + " truncated=" + n.isLineTruncated() + " controller=" + n.getControllerPos() + " lastFlow=" + n.getLastFlowKgPerS());
            } else {
                out.add("nozzle x=" + x + " missing: " + level.getBlockState(at(x, 5, -1)));
            }
        }
        out.add("players tracking the reactor chunk=" + trackers());
        out.add("structure nozzles=" + (reactor.structure() == null ? -1 : reactor.structure().steamOutletCount()));
        for (var f : feed) {
            out.add("feed pump delivered=" + f.getDeliveredFlowKgPerS() + " attached=" + f.isAttached() + " " + f.statusLines());
        }
        for (var e : eccs) {
            out.add("eccs pump " + e.design().displayName() + " delivered=" + e.getDeliveredFlowKgPerS() + " " + e.connectionStatus());
        }
        if (pool != null) {
            out.add("pool formed=" + pool.isFormed());
        }
        if (room != null) {
            out.add("containment room sealed=" + room.isSealed() + " scanning=" + room.isScanning() + " cells=" + room.freeVolume());
        }
        out.add("panel inputs bound=" + boundInputs + " sampler tracks " + dev.bwr.mod.panel.wiring.PanelSampler.trackedCount() + " panels");
        return out;
    }

    BlockPos blockAt(int x, int y, int z) {
        return at(x, y, z);
    }
}
