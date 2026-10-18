package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.MachineNames;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.networking.NetworkingNet;
import dev.bwr.mod.panel.networking.NetworkingOps;
import dev.bwr.mod.panel.networking.NetworkingPayloads;
import dev.bwr.mod.panel.networking.NetworkingScreenPayloads;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.panel.wiring.WiringOps.BindKind;
import dev.bwr.mod.signal.CommandSpec;
import dev.bwr.mod.signal.Kind;
import dev.bwr.mod.signal.Quantity;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.signal.SignalSpec;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Part 4: the machine Networking screen's server side: what it lists (every signal and command, with quantity and how
 * many components read or send it), the components linked to the machine, the live values by one request, the
 * unlink the screen sends, and the same permission checks as binding.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class NetworkingScreenTests {
    private NetworkingScreenTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theScreenListsEverythingAndTheLinks(GameTestHelper helper) {
        NetStage.run(helper, 9, s -> {
            s.wall(4, 1);
            PanelBlockEntity blockA = s.be(s.at(0, 0));
            PanelBlockEntity blockB = s.be(s.at(1, 0));
            PanelBlockEntity blockC = s.be(s.at(2, 0));
            PanelBlockEntity blockD = s.be(s.at(3, 0));
            PanelComponent gaugeA = s.gauge(blockA.getBlockPos(), 1, 1);
            PanelComponent gaugeB = s.gauge(blockB.getBlockPos(), 1, 1);
            PanelComponent gaugeC = s.gauge(blockC.getBlockPos(), 1, 1);
            PanelComponent button = s.control(blockD.getBlockPos(), 1, 1);
            var valve = s.msiv(s.devices);
            MachineNames.setName(valve, "MSIV-A");
            MachineNames.setTemplate(valve, "{name} {signal}");

            s.wrench(s.devices);
            s.wire(s.plantIn(blockA, gaugeA, 0, s.devices, "position", 100, 0));
            s.wire(s.plantIn(blockB, gaugeB, 0, s.devices, "position", 100, 0));
            s.wire(s.plantOut(blockD, button, 0, s.devices, "open"));
            s.wire(new PanelPayloads.Bind(blockC.getBlockPos(), gaugeC.id(), false, 0, BindKind.CC.ordinal(), BlockPos.ZERO,
                    "", BlockPos.ZERO, 0, 0, 1.0));

            s.standBy(s.devices);
            var data = NetworkingOps.screenFor(s.player, s.devices.above());
            s.check(data != null && data.device().equals(s.devices), "the screen opens from any cell and names the owner: " + data);
            s.check(data.name().equals("MSIV-A") && data.template().equals("{name} {signal}"), "name and template are shown");
            var provider = SignalProviders.of(valve);
            s.check(data.signals().size() == provider.signals().size() && data.commands().size() == provider.commands().size(),
                    "every signal and every command is listed");
            var position = data.signals().stream().filter(sg -> sg.key().equals("position")).findFirst().orElseThrow();
            s.check(position.readers() == 2 && position.quantity() == Quantity.POSITION.ordinal(),
                    "position is read by 2 components and measures POSITION: " + position);
            var name = data.signals().stream().filter(sg -> sg.key().equals("name")).findFirst().orElseThrow();
            s.check(name.quantity() == Quantity.TEXT.ordinal() && name.readers() == 0, "the name TEXT signal is listed");
            var open = data.commands().stream().filter(cm -> cm.key().equals("open")).findFirst().orElseThrow();
            s.check(open.users() == 1, "open is sent by 1 component");
            s.check(data.links().size() == 3, "three line links (two gauges, one button; the CC binding is not one): " + data.links());
            s.check(data.links().stream().filter(l -> !l.command()).count() == 2
                            && data.links().stream().anyMatch(l -> l.command() && l.key().equals("open")
                            && l.component().equals(button.label())),
                    "the links say which components and which direction");

            // The wire form round-trips.
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), s.level.registryAccess());
            NetworkingScreenPayloads.ScreenData.STREAM_CODEC.encode(buffer, data);
            s.check(NetworkingScreenPayloads.ScreenData.STREAM_CODEC.decode(buffer).equals(data), "screen round trip");

            // Live values: one request, one response, in the order of the signal list.
            var values = NetworkingOps.valuesFor(s.player, s.devices);
            s.check(values != null && values.size() == data.signals().size(), "one value per signal");
            int positionIndex = -1;
            for (int i = 0; i < data.signals().size(); i++) {
                if (data.signals().get(i).key().equals("position")) {
                    positionIndex = i;
                }
            }
            s.check(positionIndex >= 0 && values.get(positionIndex).startsWith("0") && values.get(positionIndex).contains("fraction open"),
                    "the position value reads with its unit: " + values.get(positionIndex));
            s.check(values.get(data.signals().indexOf(name)).equals("MSIV-A"), "the name signal reads the name");
            s.check(NetworkingOps.valuesFor(s.player, s.devices) == null, "a second request in the same moment is dropped");
            var out = new RegistryFriendlyByteBuf(Unpooled.buffer(), s.level.registryAccess());
            NetworkingScreenPayloads.Values.STREAM_CODEC.encode(out, new NetworkingScreenPayloads.Values(s.devices, values));
            s.check(NetworkingScreenPayloads.Values.STREAM_CODEC.decode(out).values().equals(values), "values round trip");

            // The unlink buttons send ordinary bind requests: clear an input, remove one sink.
            s.standAtPanels();
            var gaugeLink = data.links().stream().filter(l -> !l.command() && l.id() == gaugeA.id() && l.panel().equals(blockA.getBlockPos())).findFirst().orElseThrow();
            s.wire(new PanelPayloads.Bind(gaugeLink.panel(), gaugeLink.id(), false, gaugeLink.port(), BindKind.NONE.ordinal(),
                    BlockPos.ZERO, "", BlockPos.ZERO, 0, 0, 1.0));
            var sinkLink = data.links().stream().filter(l -> l.command()).findFirst().orElseThrow();
            s.wire(new PanelPayloads.Bind(sinkLink.panel(), sinkLink.id(), true, sinkLink.port(), BindKind.REMOVE_SINK.ordinal(),
                    BlockPos.ZERO, "", BlockPos.ZERO, 0, sinkLink.sink(), 1.0));
            s.check(!(gaugeA.source(0) instanceof PlantSource) && button.sinks(0).isEmpty()
                    && gaugeB.source(0) instanceof PlantSource, "unlinking removes exactly that link");
            s.standBy(s.devices);
            var after = NetworkingOps.screenFor(s.player, s.devices);
            s.check(after.links().size() == 1 && after.signals().stream().filter(sg -> sg.key().equals("position")).findFirst()
                    .orElseThrow().readers() == 1, "the refreshed screen shows one link left");

            // The edit packet the Done button sends.
            s.wire(NetworkingPayloads.Edit.STREAM_CODEC, new NetworkingPayloads.Edit(s.devices, "MSIV-B", ""),
                    NetworkingNet::handleEdit);
            s.check(MachineNames.name(valve).equals("MSIV-B") && MachineNames.template(valve).equals("{name}"),
                    "Done sets the name and restores the default template");

            // The same checks as binding: the wrench in hand, in reach.
            s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            s.check(NetworkingOps.screenFor(s.player, s.devices) == null && NetworkingOps.valuesFor(s.player, s.devices) == null,
                    "no wrench, no screen");
            s.wrench(null);
            s.standAtPanels();
            s.check(NetworkingOps.screenFor(s.player, s.devices) == null, "out of reach, no screen");
            s.check(!NetworkingOps.edit(s.player, s.devices, "NOPE", ""), "nor an edit");
            s.check(MachineNames.name(valve).equals("MSIV-B"), "the name stands");
            s.removeMsiv(s.devices);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void everySignalAndCommandOfAMachineCanBeBound(GameTestHelper helper) {
        NetStage.run(helper, 10, s -> {
            s.wall(2, 1);
            PanelBlockEntity gaugeBlock = s.be(s.at(0, 0));
            PanelBlockEntity buttonBlock = s.be(s.at(1, 0));
            PanelComponent gauge = s.gauge(gaugeBlock.getBlockPos(), 1, 1);
            PanelComponent button = s.control(buttonBlock.getBlockPos(), 1, 1);
            var valve = s.msiv(s.devices);
            var provider = SignalProviders.of(valve);
            s.wrench(s.devices);
            int bound = 0;
            for (SignalSpec spec : provider.signals()) {
                if (spec.kind() == Kind.TEXT) {
                    continue; // text signals are read by CC and shown on captions, not wired to a number input
                }
                s.wire(s.plantIn(gaugeBlock, gauge, 0, s.devices, spec.key(), 1, 0));
                s.check(gauge.source(0) instanceof PlantSource p && p.key().equals(spec.key()), "signal " + spec.key() + " binds to an ANY gauge");
                bound++;
            }
            int commands = 0;
            for (CommandSpec spec : provider.commands()) {
                if (spec.kind() == Kind.TEXT) {
                    continue;
                }
                s.wire(new PanelPayloads.Bind(buttonBlock.getBlockPos(), button.id(), true, 0, BindKind.NONE.ordinal(),
                        BlockPos.ZERO, "", BlockPos.ZERO, 0, 0, 1.0));
                s.wire(s.plantOut(buttonBlock, button, 0, s.devices, spec.key()));
                s.check(button.sinks(0).size() == 1 && button.sinks(0).get(0) instanceof PlantSink k && k.key().equals(spec.key()),
                        "command " + spec.key() + " binds to a button");
                commands++;
            }
            s.check(bound >= 5 && commands >= 1, "the machine has data to bind: " + bound + " signals, " + commands + " commands");
            s.removeMsiv(s.devices);
        });
    }

    @GameTest(template = "empty")
    public static void screenPayloadsRefuseOversizedLists(GameTestHelper helper) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        buffer.writeBlockPos(BlockPos.ZERO);
        buffer.writeUtf("x");
        buffer.writeUtf("");
        buffer.writeUtf("{name}");
        buffer.writeVarInt(0);
        buffer.writeVarInt(0);
        buffer.writeBoolean(false);
        buffer.writeVarInt(NetworkingScreenPayloads.MAX_LISTED + 1);
        boolean rejected = false;
        try {
            NetworkingScreenPayloads.ScreenData.STREAM_CODEC.decode(buffer);
        } catch (RuntimeException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "an oversized signal list must be refused");
        helper.assertTrue(List.of(1).size() == 1, "sanity");
        helper.succeed();
    }
}
