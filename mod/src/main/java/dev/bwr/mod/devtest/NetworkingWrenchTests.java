package dev.bwr.mod.devtest;

import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.LinkTools;
import dev.bwr.mod.panel.NetworkingWrenchItem;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.PanelLinkerItem;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.panel.wiring.WiringOps.Result;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Networking Wrench (part 1): plain-click selection and its clearing, the wrench as a second wiring tool next to
 * the Panel Linker (the same bind through the same payload, the same refusals), and the linker left as it was.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class NetworkingWrenchTests {
    private NetworkingWrenchTests() {
    }

    private static UseOnContext click(NetStage s, ItemStack stack, BlockPos pos) {
        return new UseOnContext(s.level, s.player, InteractionHand.MAIN_HAND, stack,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void wrenchPlainClickSelectsAndDeselects(GameTestHelper helper) {
        NetStage.run(helper, 0, s -> {
            s.msiv(s.devices);
            ItemStack stack = s.wrench(null);
            NetworkingWrenchItem item = (NetworkingWrenchItem) stack.getItem();
            BlockPos stone = s.devices.east(3);
            s.level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);

            item.useOn(click(s, stack, stone));
            s.check(NetworkingWrenchItem.selected(stack) == null, "a block that reports no signals is not selected");

            item.useOn(click(s, stack, s.devices.above()));
            GlobalPos selected = NetworkingWrenchItem.selected(stack);
            s.check(selected != null && selected.pos().equals(s.devices.above())
                            && selected.dimension().equals(s.level.dimension()),
                    "a plain click on any cell of a device selects it: " + selected);

            item.useOn(click(s, stack, s.devices));
            s.check(NetworkingWrenchItem.selected(stack) == null, "clicking the same device again (another cell) deselects it");

            item.useOn(click(s, stack, s.devices));
            s.check(NetworkingWrenchItem.selected(stack) != null, "selected again");
            s.player.setShiftKeyDown(true);
            InteractionResultHolder<ItemStack> air = item.use(s.level, s.player, InteractionHand.MAIN_HAND);
            s.check(air.getResult().consumesAction() && NetworkingWrenchItem.selected(stack) == null,
                    "sneak-right-click in the air deselects");
            s.player.setShiftKeyDown(false);

            // The wrench carries its selection in the same component the linker does.
            s.check(BwrDataComponents.LINKED_DEVICE.get() != null && LinkTools.isTool(stack) && LinkTools.held(s.player) == stack,
                    "LinkTools accepts the wrench");
            s.removeMsiv(s.devices);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void wrenchBindIsTheSameAsALinkerBind(GameTestHelper helper) {
        NetStage.run(helper, 1, s -> {
            s.wall(3, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelBlockEntity second = s.be(s.at(1, 0));
            PanelBlockEntity third = s.be(s.at(2, 0));
            PanelComponent byWrench = s.gauge(block.getBlockPos(), 1, 1);
            PanelComponent byLinker = s.gauge(second.getBlockPos(), 1, 1);
            s.msiv(s.devices);

            s.wrench(s.devices);
            s.wire(s.plantIn(block, byWrench, 0, s.devices, "position", 100, 0));
            s.linker(s.devices);
            s.wire(s.plantIn(second, byLinker, 0, s.devices, "position", 100, 0));
            s.check(byWrench.source(0) instanceof PlantSource a && byLinker.source(0) instanceof PlantSource b
                            && a.device().equals(b.device()) && a.key().equals(b.key()) && a.scale() == b.scale()
                            && a.offset() == b.offset() && a.scale() == 100,
                    "the real Bind payload with the wrench makes the same source as with the linker: "
                            + byWrench.source(0) + " / " + byLinker.source(0));

            // A command the same way.
            PanelComponent button = s.control(third.getBlockPos(), 1, 3);
            s.wrench(s.devices);
            s.wire(s.plantOut(third, button, 0, s.devices, "open"));
            s.check(button.sinks(0).size() == 1 && button.sinks(0).get(0) instanceof PlantSink sink
                    && sink.device().equals(s.devices) && sink.key().equals("open"), "a wrench command bind");

            // The binding screen opens for either tool and lists the device's signals.
            s.wrench(s.devices);
            var screen = WiringOps.screenFor(s.player, block.getBlockPos(), byWrench.id());
            s.check(screen != null && screen.status() == PanelPayloads.LINK_OK && screen.device().equals(s.devices)
                    && screen.signals().stream().anyMatch(e -> e.key().equals("position")),
                    "the wrench opens the binding screen: " + screen);
            s.removeMsiv(s.devices);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void wrenchRefusesTheSameBindingsTheLinkerRefuses(GameTestHelper helper) {
        NetStage.run(helper, 2, s -> {
            s.wall(1, 1);
            PanelBlockEntity block = s.be(s.at(0, 0));
            PanelComponent gauge = s.gauge(block.getBlockPos(), 1, 1);
            s.msiv(s.devices);

            s.wrench(null);
            s.check(s.outcome(s.plantIn(block, gauge, 0, s.devices, "position", 1, 0)).result() == Result.NO_LINK,
                    "a wrench with nothing selected cannot bind a plant signal");
            // A device the client names that is not the selected one.
            s.wrench(s.devices);
            s.check(s.outcome(s.plantIn(block, gauge, 0, s.devices.east(), "position", 1, 0)).result() == Result.DEVICE_MISMATCH,
                    "the client cannot name a device other than the selected one");
            // Another dimension.
            ItemStack elsewhere = s.wrench(null);
            elsewhere.set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(Level.NETHER, s.devices));
            s.check(s.outcome(s.plantIn(block, gauge, 0, s.devices, "position", 1, 0)).result() == Result.WRONG_DIMENSION,
                    "a device from another dimension is refused");
            // Out of range.
            BlockPos beyond = s.origin.offset(-(PanelConfig.linkRange() + 20), 0, 0);
            s.wrench(beyond);
            s.check(s.outcome(s.plantIn(block, gauge, 0, beyond, "position", 1, 0)).result() == Result.OUT_OF_RANGE,
                    "beyond the configured range is refused");
            // Not a provider.
            BlockPos stone = s.devices.east(4);
            s.level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
            s.wrench(stone);
            s.check(s.outcome(s.plantIn(block, gauge, 0, stone, "position", 1, 0)).result() == Result.NO_DEVICE,
                    "a block that is not a signal provider is refused");
            // No tool at all.
            s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            s.check(s.outcome(s.plantIn(block, gauge, 0, s.devices, "position", 1, 0)).result() == Result.NOT_HOLDING_LINKER,
                    "no tool, no binding");
            s.check(!(gauge.source(0) instanceof PlantSource), "none of the refusals bound anything");
            s.removeMsiv(s.devices);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theLinkerStaysAsItWas(GameTestHelper helper) {
        NetStage.run(helper, 3, s -> {
            s.msiv(s.devices);
            ItemStack stack = s.linker(null);
            PanelLinkerItem item = (PanelLinkerItem) stack.getItem();
            item.useOn(click(s, stack, s.devices.above()));
            s.check(PanelLinkerItem.linked(stack) == null, "a plain click does not select with the linker (it never did)");
            s.player.setShiftKeyDown(true);
            item.useOn(click(s, stack, s.devices.above()));
            s.check(PanelLinkerItem.linked(stack) != null && PanelLinkerItem.linked(stack).pos().equals(s.devices.above()),
                    "a sneak click stores the clicked cell, as before");
            s.player.setShiftKeyDown(false);
            s.check(PanelLinkerItem.held(s.player) == stack && LinkTools.held(s.player) == stack,
                    "the linker is still found by both lookups");
            s.check(LinkTools.heldWrench(s.player).isEmpty(), "and is not a wrench");
            ItemStack wrench = new ItemStack(BwrItems.NETWORKING_WRENCH.get());
            s.player.setItemInHand(InteractionHand.OFF_HAND, wrench);
            s.check(LinkTools.held(s.player) == stack && LinkTools.heldWrench(s.player) == wrench,
                    "main hand wins when both tools are held");
            s.player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            s.removeMsiv(s.devices);
        });
    }
}
