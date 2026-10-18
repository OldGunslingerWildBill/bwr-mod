package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.MachineNames;
import dev.bwr.mod.panel.MachinePorts;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.networking.NetworkingNet;
import dev.bwr.mod.panel.networking.NetworkingPayloads;
import dev.bwr.mod.panel.wiring.WiringOps.BindKind;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.signal.SignalSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Part 3: machine names (equipment tags such as RHR-1) and the name push. The push tests send the real
 * "Name linked components" payload, as the screen's button does, and check what the panels hold afterwards.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class NetworkingNameTests {
    private NetworkingNameTests() {
    }

    private static String signalLabel(BlockEntity device, String key) {
        for (SignalSpec spec : SignalProviders.of(device).signals()) {
            if (spec.key().equals(key)) {
                return spec.label();
            }
        }
        throw new AssertionError("no signal " + key);
    }

    private static CompoundTag saved(NetStage s, BlockEntity be) {
        return be.saveWithFullMetadata(s.level.registryAccess());
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void machineNameRoundTripsAndAnOldSaveHasNone(GameTestHelper helper) {
        NetStage.run(helper, 4, s -> {
            var valve = s.msiv(s.devices);
            CompoundTag before = saved(s, valve);
            s.check(MachineNames.name(valve).isEmpty() && !before.contains("neoforge:attachments"),
                    "a machine nobody named has no name and saves no attachment: " + before);
            s.check(SignalProviders.of(valve).readText("name").orElse("?").isEmpty()
                            && SignalProviders.of(valve).signals().stream().anyMatch(sp -> sp.key().equals("name")),
                    "every provider has a name TEXT signal, empty until named");

            s.check(MachineNames.setName(valve, "RHR-1").equals("RHR-1"), "set");
            s.check(SignalProviders.of(valve).readText("name").orElse("").equals("RHR-1"), "the name signal reads it");
            CompoundTag named = saved(s, valve);
            s.check(named.toString().contains("RHR-1"), "the name is saved: " + named);
            BlockEntity copy = BlockEntity.loadStatic(valve.getBlockPos(), valve.getBlockState(), named, s.level.registryAccess());
            s.check(copy != null && MachineNames.name(copy).equals("RHR-1"), "the name survives a save and load");
            s.check(MachineNames.name(SignalProviders.at(s.level, s.devices.above()) == null ? null
                            : dev.bwr.mod.signal.DeviceResolver.resolve(s.level, s.devices.above())).equals("RHR-1"),
                    "another cell of the multiblock resolves to the same name");

            // Old save: a tag written before names existed loads with no name and re-saves without one.
            BlockEntity old = BlockEntity.loadStatic(valve.getBlockPos(), valve.getBlockState(), before, s.level.registryAccess());
            s.check(old != null && MachineNames.name(old).isEmpty() && !saved(s, old).contains("neoforge:attachments"),
                    "an old save loads with no name and re-saves unchanged");

            // Cleaning and clearing.
            s.check(MachineNames.setName(valve, "  §aTOO-LONG-NAME-FOR-A-TAG-0123456789\u0007 ").length() <= MachineNames.MAX_NAME
                            && !MachineNames.name(valve).contains("§") && !MachineNames.name(valve).contains("\u0007"),
                    "a name is cut and cleaned: " + MachineNames.name(valve));
            s.check(MachineNames.setName(valve, "").isEmpty() && !saved(s, valve).toString().contains("machine_name"),
                    "clearing the name removes it from the save");
            s.check(MachineNames.setTemplate(valve, "").equals("{name}"), "an empty template is the default");
            s.check(!saved(s, valve).toString().contains("machine_template"), "the default template is not saved");
            MachineNames.setTemplate(valve, "{name} {signal}");
            s.check(saved(s, valve).toString().contains("{name} {signal}"), "a custom template is saved");
            s.removeMsiv(s.devices);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void nameLinkedComponentsNamesExactlyTheLineLinkedOnes(GameTestHelper helper) {
        NetStage.run(helper, 5, s -> {
            s.wall(4, 1);
            PanelBlockEntity blockA = s.be(s.at(0, 0));
            PanelBlockEntity blockB = s.be(s.at(1, 0));
            PanelBlockEntity blockC = s.be(s.at(2, 0));
            PanelBlockEntity blockD = s.be(s.at(3, 0));
            PanelComponent gaugeA = s.gauge(blockA.getBlockPos(), 1, 1);
            PanelComponent gaugeB = s.gauge(blockB.getBlockPos(), 1, 1);
            PanelComponent gaugeC = s.gauge(blockC.getBlockPos(), 1, 1);
            PanelComponent button = s.control(blockD.getBlockPos(), 1, 1);
            var rhr1 = s.msiv(s.devices);
            BlockPos second = s.devices.west(6);
            var rhr2 = s.msiv(second);
            MachineNames.setName(rhr1, "RHR-1");
            MachineNames.setName(rhr2, "RHR-2");

            // Gauge A: line-linked to RHR-1. Gauge B: line-linked to RHR-2. The button commands RHR-1 (a line link too).
            s.wrench(s.devices);
            s.wire(s.plantIn(blockA, gaugeA, 0, s.devices, "position", 100, 0));
            s.wire(s.plantOut(blockD, button, 0, s.devices, "open"));
            s.wrench(second);
            s.wire(s.plantIn(blockB, gaugeB, 0, second, "position", 100, 0));
            // Gauge C reads RHR-1 through a CC port (CC:Tweaked not needed to hold the binding), not through a line link.
            MachinePorts.set(rhr1, 7);
            gaugeC.setSource(0, new dev.bwr.mod.panel.wiring.CcPortSource(7, "position", 100, 0));
            s.check(gaugeA.source(0) instanceof dev.bwr.mod.panel.wiring.PlantSource
                            && gaugeB.source(0) instanceof dev.bwr.mod.panel.wiring.PlantSource
                            && gaugeC.source(0) instanceof dev.bwr.mod.panel.wiring.CcPortSource
                            && button.sinks(0).size() == 1,
                    "fixture: A and B line-linked, the button commands RHR-1, C is a CC binding");
            s.check(gaugeA.caption().isEmpty() && gaugeB.caption().isEmpty() && gaugeC.caption().isEmpty()
                    && button.caption().isEmpty(), "fixture: nothing is captioned yet");

            // The real payload, as the Name linked components button sends it, from where the player opened the screen.
            s.wrench(s.devices);
            s.standBy(s.devices);
            s.wire(NetworkingPayloads.NameLinked.STREAM_CODEC, new NetworkingPayloads.NameLinked(s.devices),
                    NetworkingNet::handleNameLinked);
            s.check(gaugeA.caption().equals("RHR-1"), "gauge A reads RHR-1, got '" + gaugeA.caption() + "'");
            s.check(button.caption().equals("RHR-1"), "the button that commands RHR-1 reads RHR-1, got '" + button.caption() + "'");
            s.check(gaugeB.caption().isEmpty(), "gauge B (RHR-2) is unchanged, got '" + gaugeB.caption() + "'");
            s.check(gaugeC.caption().isEmpty(), "gauge C (CC binding) is unchanged, got '" + gaugeC.caption() + "'");
            s.check(!rhr1.isComputerControlled() && !rhr1.isDemandOpen() && !rhr2.isComputerControlled(),
                    "no sink fired: neither valve was commanded");
            s.check(blockA.savePanel(false).toString().contains("RHR-1"), "the caption is saved with the panel");

            // The template.
            s.wire(NetworkingPayloads.Edit.STREAM_CODEC, new NetworkingPayloads.Edit(s.devices, "RHR-1", "{name} {signal}"),
                    NetworkingNet::handleEdit);
            s.wire(NetworkingPayloads.NameLinked.STREAM_CODEC, new NetworkingPayloads.NameLinked(s.devices),
                    NetworkingNet::handleNameLinked);
            String expected = "RHR-1 " + signalLabel(rhr1, "position");
            s.check(gaugeA.caption().equals(expected), "with the template {name} {signal}: expected '" + expected + "', got '"
                    + gaugeA.caption() + "'");
            s.check(gaugeB.caption().isEmpty() && gaugeC.caption().isEmpty(), "still only RHR-1's components");
            s.check(!rhr1.isComputerControlled() && !rhr2.isComputerControlled(), "still no sink fired");

            // A machine with no name pushes nothing and leaves captions alone.
            MachineNames.setName(rhr2, "");
            s.wrench(second);
            s.standBy(second);
            s.wire(NetworkingPayloads.NameLinked.STREAM_CODEC, new NetworkingPayloads.NameLinked(second),
                    NetworkingNet::handleNameLinked);
            s.check(gaugeB.caption().isEmpty(), "an unnamed machine gives no caption");

            // Without the wrench in hand the payload does nothing.
            gaugeA.setCaption("KEEP");
            s.standBy(s.devices);
            s.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
            s.wire(NetworkingPayloads.NameLinked.STREAM_CODEC, new NetworkingPayloads.NameLinked(s.devices),
                    NetworkingNet::handleNameLinked);
            s.check(gaugeA.caption().equals("KEEP"), "no wrench, no push");
            s.removeMsiv(s.devices);
            s.removeMsiv(second);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void nameLinkedRespectsThePanelEditPermission(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "fixture: the nether exists");
        // Edit permission is Level#mayInteract; a test server has no spawn protection, so the border stands in: outside
        // it nobody may interact. Only the nether's own border is touched, and it is restored whatever happens.
        WorldBorder border = nether.getWorldBorder();
        double size = border.getSize();
        double cx = border.getCenterX();
        double cz = border.getCenterZ();
        NetStage.run(helper, 6, nether, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent gauge = s.gauge(block.getBlockPos(), 1, 1);
            var valve = s.msiv(s.devices);
            MachineNames.setName(valve, "RHR-1");
            s.wrench(s.devices);
            s.wire(s.plantIn(block, gauge, 0, s.devices, "position", 100, 0));
            try {
                border.setCenter(0, 0);
                border.setSize(200);
                s.check(!dev.bwr.mod.world.AssemblyAccess.permitted(s.level, s.player, block.getBlockPos()),
                        "fixture: outside the border the player may not edit");
                s.standBy(s.devices);
                s.wire(NetworkingPayloads.NameLinked.STREAM_CODEC, new NetworkingPayloads.NameLinked(s.devices),
                        NetworkingNet::handleNameLinked);
                s.standAtPanels();
                s.check(gauge.caption().isEmpty(), "a player without edit permission changes nothing, got '" + gauge.caption() + "'");
                s.wire(NetworkingPayloads.UseMachineName.STREAM_CODEC,
                        new NetworkingPayloads.UseMachineName(block.getBlockPos(), gauge.id()), NetworkingNet::handleUseMachineName);
                s.check(gauge.caption().isEmpty(), "nor does Use machine name");
            } finally {
                border.setCenter(cx, cz);
                border.setSize(size);
            }
            s.check(dev.bwr.mod.world.AssemblyAccess.permitted(s.level, s.player, block.getBlockPos()), "fixture: permitted again");
            s.wire(NetworkingPayloads.UseMachineName.STREAM_CODEC,
                    new NetworkingPayloads.UseMachineName(block.getBlockPos(), gauge.id()), NetworkingNet::handleUseMachineName);
            s.check(gauge.caption().equals("RHR-1"), "Use machine name names one component, got '" + gauge.caption() + "'");
            s.removeMsiv(s.devices);
        });
    }
}
