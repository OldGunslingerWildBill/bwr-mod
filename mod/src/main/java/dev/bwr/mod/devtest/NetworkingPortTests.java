package dev.bwr.mod.devtest;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.MachineNames;
import dev.bwr.mod.panel.MachinePorts;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.InputSources;
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
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Part 7, the parts that need no CC:Tweaked: the machine port (0000 to 9999) and its save, the {@code cc_port} source
 * and its save, the refusals of a bind by port, and the Networking screen's port edit. The checks that need a real wired
 * network are in {@link NetworkingCcChecks}, behind {@code BwrMod.isComputerCraftPresent()}.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class NetworkingPortTests {
    private NetworkingPortTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aMachinePortRoundTripsAndAnOldSaveHasNone(GameTestHelper helper) {
        NetStage.run(helper, 12, s -> {
            var valve = s.msiv(s.devices);
            CompoundTag before = valve.saveWithFullMetadata(s.level.registryAccess());
            s.check(MachinePorts.get(valve) == MachinePorts.NONE && !before.toString().contains("machine_port"),
                    "a machine nobody gave a port has none and saves none: " + before);
            int generation = MachinePorts.generation();
            s.check(MachinePorts.set(valve, 42) && MachinePorts.get(valve) == 42 && MachinePorts.generation() > generation,
                    "a port is set (and cached maps are told)");
            s.check(MachinePorts.format(42).equals("0042") && MachinePorts.format(0).equals("0000")
                    && MachinePorts.format(9999).equals("9999"), "ports show as four digits");
            CompoundTag saved = valve.saveWithFullMetadata(s.level.registryAccess());
            BlockEntity copy = BlockEntity.loadStatic(valve.getBlockPos(), valve.getBlockState(), saved, s.level.registryAccess());
            s.check(copy != null && MachinePorts.get(copy) == 42, "the port survives a save and load: " + saved);
            BlockEntity old = BlockEntity.loadStatic(valve.getBlockPos(), valve.getBlockState(), before, s.level.registryAccess());
            s.check(old != null && MachinePorts.get(old) == MachinePorts.NONE
                    && !old.saveWithFullMetadata(s.level.registryAccess()).toString().contains("machine_port"),
                    "an old save has no port and re-saves without one");
            // The ends of the range are ports; anything outside is refused and changes nothing.
            s.check(MachinePorts.set(valve, 0) && MachinePorts.get(valve) == 0 && MachinePorts.set(valve, 9999)
                    && MachinePorts.get(valve) == 9999, "0000 and 9999 are ports");
            s.check(!MachinePorts.set(valve, 10000) && !MachinePorts.set(valve, -1) && !MachinePorts.set(valve, -5)
                    && MachinePorts.get(valve) == 9999, "port 10000 or -1 is refused and nothing changes");
            MachinePorts.clear(valve);
            s.check(MachinePorts.get(valve) == MachinePorts.NONE && !valve.saveWithFullMetadata(s.level.registryAccess())
                    .toString().contains("machine_port"), "clearing the port removes it from the save");
            s.removeMsiv(s.devices);
        });
    }

    @GameTest(template = "empty")
    public static void ccPortSourceSavesAndLoads(GameTestHelper helper) {
        CcPortSource plain = new CcPortSource(42, "level", 1.0, 0.0);
        CompoundTag tag = InputSources.save(plain);
        helper.assertTrue(tag.getString("Kind").equals("cc_port") && tag.getInt("Port") == 42 && tag.getString("Key").equals("level")
                && !tag.contains("Scale") && !tag.contains("Offset"), "a default transform saves no scale or offset: " + tag);
        helper.assertTrue(InputSources.save(InputSources.load(tag)).equals(tag), "and reloads unchanged");
        CcPortSource scaled = new CcPortSource(9999, "mw_now", 0.05, 3.0);
        CompoundTag scaledTag = InputSources.save(scaled);
        helper.assertTrue(InputSources.load(scaledTag) instanceof CcPortSource back && back.port() == 9999 && back.scale() == 0.05
                && back.offset() == 3.0 && InputSources.save(back).equals(scaledTag), "a transform round-trips");
        helper.assertTrue(plain.isLive() && plain.kind().equals("cc_port"), "it is a live source");

        // Malformed data is kept verbatim and inert instead of being rewritten.
        for (String broken : new String[] {"port-high", "port-low", "no-key", "zero-scale"}) {
            CompoundTag bad = new CompoundTag();
            bad.putString("Kind", "cc_port");
            bad.putInt("Port", broken.equals("port-high") ? 10000 : broken.equals("port-low") ? -1 : 5);
            if (!broken.equals("no-key")) {
                bad.putString("Key", "level");
            }
            if (broken.equals("zero-scale")) {
                bad.putDouble("Scale", 0.0);
            }
            helper.assertTrue(InputSources.save(InputSources.load(bad)).equals(bad) && !(InputSources.load(bad) instanceof CcPortSource),
                    "malformed (" + broken + ") is preserved, not trusted");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void bindingByPortRefusesBadPortsAndSaysWhenThereIsNoNetwork(GameTestHelper helper) {
        NetStage.run(helper, 13, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent gauge = s.gauge(block.getBlockPos(), 1, 1);
            s.wrench(null);
            for (int bad : new int[] {-1, 10000, 12345}) {
                s.check(s.outcome(ccPort(block, gauge, bad, "level")).result() == Result.BAD_VALUE,
                        "port " + bad + " is refused through the real Bind payload");
            }
            Result none = s.outcome(ccPort(block, gauge, 5, "level")).result();
            s.check(none == (CcPortBridge.available() ? Result.PORT_EMPTY : Result.NO_CC),
                    "a port with no network behind it says so: " + none);
            s.wire(ccPort(block, gauge, 5, "level"));
            s.check(!(gauge.source(0) instanceof CcPortSource), "nothing was bound");
            // A saved CC port source with no network reads no signal and never loads a chunk.
            gauge.setSource(0, new CcPortSource(5, "level", 1.0, 0.0));
            block.resample(gauge, true);
            s.check(Double.isNaN(gauge.input(0)), "a CC port with no machine reads no signal: " + gauge.input(0));
        });
    }

    private static PanelPayloads.Bind ccPort(PanelBlockEntity block, PanelComponent component, int port, String key) {
        return new PanelPayloads.Bind(block.getBlockPos(), component.id(), false, 0, BindKind.CC_PORT.ordinal(), BlockPos.ZERO,
                key, BlockPos.ZERO, 0, port, 1.0);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theNetworkingScreenSetsAndClearsThePort(GameTestHelper helper) {
        NetStage.run(helper, 14, s -> {
            var valve = s.msiv(s.devices);
            s.wrench(s.devices);
            s.standBy(s.devices);
            s.wire(NetworkingPayloads.Edit.STREAM_CODEC, new NetworkingPayloads.Edit(s.devices, "CCW-1", "{name}", 1234),
                    NetworkingNet::handleEdit);
            s.check(MachinePorts.get(valve) == 1234 && MachineNames.name(valve).equals("CCW-1"), "Done sets the port by hand");
            var screen = NetworkingOps.screenFor(s.player, s.devices);
            s.check(screen.port() == 1234 && screen.cc() == BwrMod.isComputerCraftPresent(),
                    "the screen shows the port, and whether CC ports apply: " + screen.port() + " cc=" + screen.cc());
            s.wire(NetworkingPayloads.Edit.STREAM_CODEC, new NetworkingPayloads.Edit(s.devices, "CCW-1", "{name}"),
                    NetworkingNet::handleEdit);
            s.check(MachinePorts.get(valve) == 1234, "an edit that keeps the port leaves it");
            s.wire(NetworkingPayloads.Edit.STREAM_CODEC, new NetworkingPayloads.Edit(s.devices, "OTHER", "{name}", 12345),
                    NetworkingNet::handleEdit);
            s.check(MachinePorts.get(valve) == 1234 && MachineNames.name(valve).equals("CCW-1"),
                    "a port outside 0 to 9999 refuses the whole edit");
            s.wire(NetworkingPayloads.Edit.STREAM_CODEC,
                    new NetworkingPayloads.Edit(s.devices, "CCW-1", "{name}", NetworkingPayloads.PORT_CLEAR), NetworkingNet::handleEdit);
            s.check(MachinePorts.get(valve) == MachinePorts.NONE, "an empty port field clears it");
            s.check(WiringOps.Result.values().length > 0, "sanity");
            s.removeMsiv(s.devices);
        });
    }
}
