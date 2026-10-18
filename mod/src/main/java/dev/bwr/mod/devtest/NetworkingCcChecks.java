package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.panel.MachineNames;
import dev.bwr.mod.panel.MachinePorts;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.networking.NetworkingNet;
import dev.bwr.mod.panel.networking.NetworkingOps;
import dev.bwr.mod.panel.networking.NetworkingPayloads;
import dev.bwr.mod.panel.wiring.CcPortBridge;
import dev.bwr.mod.panel.wiring.CcPortSource;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.panel.wiring.WiringOps.BindKind;
import dev.bwr.mod.panel.wiring.WiringOps.Result;
import dev.bwr.mod.signal.Quantity;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Part 7 against a real CC:Tweaked wired network: a panel, a full wired modem touching the panel, and two machines
 * touching the modem. References CC types, so callers must be behind {@code BwrMod.isComputerCraftPresent()}; the
 * {@code @GameTestHolder} class that calls it never names a CC type, and the checks are skipped under {@code -PbwrNoCC}.
 */
final class NetworkingCcChecks {
    private NetworkingCcChecks() {
    }

    private static PanelPayloads.Bind byPort(PanelBlockEntity block, PanelComponent component, int port, String key) {
        return new PanelPayloads.Bind(block.getBlockPos(), component.id(), false, 0, BindKind.CC_PORT.ordinal(), BlockPos.ZERO,
                key, BlockPos.ZERO, 0, port, 1.0);
    }

    static void run(GameTestHelper helper) {
        NetStage.run(helper, 15, s -> {
            s.wall(2, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelBlockEntity second = s.be(s.at(1, 0));
            PanelComponent anyGauge = s.gauge(block.getBlockPos(), 1, 1);
            // A bar graph that measures LEVEL, on the same panel block (so on the same CC network) as the dial.
            PanelComponent levelGauge = s.place(block.getBlockPos(), 13, 2, dev.bwr.mod.panel.component.ComponentTypes.BAR_GRAPH,
                    dev.bwr.mod.panel.component.Footprint.of(3, 12));
            CompoundTag measures = new CompoundTag();
            measures.putString("Measures", "LEVEL");
            s.configure(block, levelGauge, measures);

            // The wired network: panel - modem - machines (pool behind the modem, meter above it).
            BlockPos modemPos = s.at(0, 0).relative(Direction.SOUTH);
            BlockPos poolPos = modemPos.relative(Direction.SOUTH);
            BlockPos meterPos = modemPos.above();
            BlockPos poolPos2 = modemPos.east(); // a second pool: the one that will share a port with the first
            Block modem = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("computercraft:wired_modem_full"));
            s.check(modem != Blocks.AIR, "fixture: CC's full wired modem block exists");
            var pool = s.pool(poolPos);
            var meter = s.meter(meterPos);
            var pool2 = s.pool(poolPos2);
            MachineNames.setName(pool, "RHR-1");
            MachinePorts.set(pool, 7);
            MachinePorts.set(meter, 8);
            s.level.setBlock(modemPos, modem.defaultBlockState(), 3);
            // Right-click the modem: it connects the machines next to it to the network (CC's own behaviour).
            s.level.getBlockState(modemPos).useWithoutItem(s.level, s.player,
                    new BlockHitResult(Vec3.atCenterOf(modemPos), Direction.NORTH, modemPos, false));

            s.then(25, () -> {
                s.check(CcPortBridge.available(), "the CC side installed itself on the bridge");
                List<net.minecraft.world.level.block.entity.BlockEntity> onSeven = CcPortBridge.machinesOnPort(block, 7);
                s.check(onSeven.size() == 1 && onSeven.get(0) == pool, "port 7 finds the pool: " + onSeven);
                s.check(CcPortBridge.machinesOnPort(block, 8).equals(List.of(meter)), "port 8 finds the meter");
                s.check(CcPortBridge.machinesOnPort(block, 9).isEmpty(), "port 9 finds nothing");
                s.check(CcPortBridge.machinesOnPort(second, 7).isEmpty(),
                        "a panel not touching the network sees no machines");

                // Binding through the real Bind payload: the right machine is read.
                s.wrench(null);
                s.wire(byPort(block, anyGauge, 7, "level"));
                s.check(anyGauge.source(0) instanceof CcPortSource p && p.port() == 7 && p.key().equals("level"),
                        "the port binding is made: " + anyGauge.source(0));
                block.resample(anyGauge, true);
                double level = SignalProviders.of(pool).read("level").getAsDouble();
                s.check(anyGauge.input(0) == level, "a CcPortSource reads the machine on its port: " + anyGauge.input(0) + " vs " + level);
                s.wire(byPort(block, anyGauge, 8, "mw_now"));
                s.check(anyGauge.source(0) instanceof CcPortSource p && p.port() == 8, "an ANY gauge takes the meter too");
                block.resample(anyGauge, true);
                s.check(anyGauge.input(0) == SignalProviders.of(meter).read("mw_now").getAsDouble(), "and reads the meter");

                // Gauge compatibility holds through a CC port too.
                s.wire(byPort(block, levelGauge, 7, "level"));
                s.check(levelGauge.source(0) instanceof CcPortSource, "a LEVEL gauge takes the level signal by port");
                var refused = s.outcome(byPort(block, levelGauge, 8, "mw_now"));
                s.check(refused.result() == Result.MEASURES_MISMATCH
                        && BwrText.english(refused.detail()).equals("This gauge measures LEVEL; mw now is POWER"),
                        "the same refusal through a CC port: " + refused.result());
                s.check(((CcPortSource) levelGauge.source(0)).port() == 7, "and the level binding stands");
                s.check(s.outcome(byPort(block, anyGauge, 9, "level")).result() == Result.PORT_EMPTY, "an empty port is refused");
                s.check(s.outcome(byPort(block, anyGauge, 10000, "level")).result() == Result.BAD_VALUE
                        && s.outcome(byPort(block, anyGauge, -1, "level")).result() == Result.BAD_VALUE, "port 10000 or -1 is refused");
                s.check(s.outcome(byPort(block, anyGauge, 7, "no_such_signal")).result() == Result.NO_SUCH_KEY, "an unknown signal is refused");

                // The name push never reaches a CC binding, though the machine has a name.
                PanelComponent lineGauge = s.place(second.getBlockPos(), 1, 1, dev.bwr.mod.panel.component.ComponentTypes.PUSHBUTTON,
                        dev.bwr.mod.panel.component.Footprint.of(6, 6)); // on the panel that touches no network
                s.wrench(poolPos);
                s.standBy(poolPos);
                s.wire(NetworkingPayloads.NameLinked.STREAM_CODEC, new NetworkingPayloads.NameLinked(poolPos), NetworkingNet::handleNameLinked);
                s.check(levelGauge.caption().isEmpty() && anyGauge.caption().isEmpty(),
                        "the name push skips CC-port bindings (the pool is named RHR-1): '" + levelGauge.caption() + "'");
                s.check(lineGauge.caption().isEmpty(), "and an unlinked component stays unnamed");

                // Lua: getName, setName, getPort, setPort on the machine's peripheral.
                var server = s.level.getServer();
                var peripheral = s.level.getCapability(PeripheralCapability.get(), poolPos, Direction.NORTH);
                s.check(peripheral != null, "the pool is a peripheral on the face next to the modem");
                try {
                    s.check(PeripheralTestCalls.call(server, peripheral, "getName")[0].equals("RHR-1"), "getName");
                    s.check(((Number) PeripheralTestCalls.call(server, peripheral, "getPort")[0]).intValue() == 7, "getPort");
                    PeripheralTestCalls.call(server, peripheral, "setName", "RHR-9");
                    s.check(MachineNames.name(pool).equals("RHR-9"), "setName");
                    PeripheralTestCalls.call(server, peripheral, "setPort", 11);
                    s.check(MachinePorts.get(pool) == 11, "setPort");
                    boolean refusedHigh = false;
                    boolean refusedLow = false;
                    try {
                        PeripheralTestCalls.call(server, peripheral, "setPort", 10000);
                    } catch (LuaException expected) {
                        refusedHigh = true;
                    }
                    try {
                        PeripheralTestCalls.call(server, peripheral, "setPort", -1);
                    } catch (LuaException expected) {
                        refusedLow = true;
                    }
                    s.check(refusedHigh && refusedLow && MachinePorts.get(pool) == 11, "setPort refuses 10000 and -1");
                    PeripheralTestCalls.call(server, peripheral, "setPort", 7);
                    PeripheralTestCalls.call(server, peripheral, "setName", "RHR-1");
                } catch (LuaException e) {
                    throw new AssertionError("a machine Lua call failed: " + e.getMessage(), e);
                }

                // A duplicate port: two machines on port 7, nothing guessed.
                MachinePorts.set(pool2, 7); // two pools, both with a level signal, on port 7
                s.check(CcPortBridge.machinesOnPort(block, 7).size() == 2, "two machines on port 7");
                block.resample(anyGauge, true);
                anyGauge.setSource(0, new CcPortSource(7, "level", 1.0, 0.0));
                block.resample(anyGauge, true);
                s.check(Double.isNaN(anyGauge.input(0)), "a duplicate port reads no signal: " + anyGauge.input(0));
                var conflict = s.outcome(byPort(block, anyGauge, 7, "level"));
                s.check(conflict.result() == Result.PORT_CONFLICT
                        && BwrText.english(conflict.detail()).equals("Port 0007 is used by 2 machines"),
                        "binding says so: " + conflict.result());
                s.check(CcPortBridge.machinesSharingPort(pool) == 2, "the machine knows its port is shared");
                s.standBy(poolPos);
                var screen = NetworkingOps.screenFor(s.player, poolPos);
                s.check(screen.port() == 7 && screen.portUsers() == 2 && screen.cc(),
                        "the machine's Networking screen warns: port " + screen.port() + " users " + screen.portUsers());
                s.standAtPanels();
                var lookup = WiringOps.ccPortScreenFor(s.player, block.getBlockPos(), anyGauge.id(), 7);
                s.check(lookup.status() == PanelPayloads.LINK_PORT_CONFLICT && lookup.machineName().equals("2"),
                        "the binding screen lookup reports the conflict");
                var found = WiringOps.ccPortScreenFor(s.player, block.getBlockPos(), anyGauge.id(), 9);
                s.check(found.status() == PanelPayloads.LINK_PORT_EMPTY, "and an empty port");
                MachinePorts.clear(pool2);
                var good = WiringOps.ccPortScreenFor(s.player, block.getBlockPos(), anyGauge.id(), 8);
                s.check(good.status() == PanelPayloads.LINK_OK && good.ccPort() == 8
                        && good.signals().stream().anyMatch(e -> e.key().equals("mw_now") && e.quantity() == Quantity.POWER.ordinal()),
                        "the lookup lists the signals of the machine on the port");
                block.resample(anyGauge, true);
                anyGauge.setSource(0, new CcPortSource(7, "level", 1.0, 0.0));
                block.resample(anyGauge, true);
                s.check(!Double.isNaN(anyGauge.input(0)), "with the duplicate gone the port reads again");

                // The network changes: removing the modem takes the machines off it.
                s.level.removeBlock(modemPos, false);
            });
            s.then(25, () -> {
                s.check(CcPortBridge.machinesOnPort(block, 7).isEmpty(), "without the modem no machine answers: "
                        + CcPortBridge.machinesOnPort(block, 7));
                block.resample(s.be(s.at(0, 0)).component(1), true);
                s.check(Double.isNaN(block.component(1).input(0)), "and the binding reads no signal");
                s.level.removeBlock(poolPos, false);
                s.level.removeBlock(meterPos, false);
                s.level.removeBlock(poolPos2, false);
            });
        });
    }
}
