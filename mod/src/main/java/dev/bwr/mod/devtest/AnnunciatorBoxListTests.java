package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.MachineNames;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.AnnunciatorLogic;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.AnnunciatorBoxList;
import dev.bwr.mod.panel.model.AnnunciatorBoxList.Filter;
import dev.bwr.mod.panel.model.AnnunciatorBoxList.Show;
import dev.bwr.mod.panel.model.AnnunciatorBoxList.Wiring;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.AnnunciatorBoxRows;
import dev.bwr.mod.panel.wiring.CcSource;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.panel.wiring.RedstoneSource;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.signal.DeviceResolver;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The annunciator box list, as far as a server can test it: the rows of a box read from the panels (numbers, legend,
 * address, trigger, wiring, state, counts and filters, {@link AnnunciatorBoxRows}), and the one thing the server adds
 * for the screen, the names of the machines the box reads: what the reply holds, who is refused it, and its size cap.
 * The screen itself is the client's and is not opened here.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class AnnunciatorBoxListTests {
    static final int ORIGIN_X = 142_300;
    static final int ORIGIN_Z = 143_956;

    private AnnunciatorBoxListTests() {
    }

    private static AnnunciatorData data(PanelComponent w) {
        return (AnnunciatorData) w.data();
    }

    private static String numbers(List<AnnunciatorBoxRows.Row> rows) {
        StringBuilder text = new StringBuilder();
        for (AnnunciatorBoxRows.Row row : rows) {
            text.append(row.number()).append(' ');
        }
        return text.toString().trim();
    }

    /** A valve to read: the three-block MSIV the wiring tests use, two blocks from the panel. */
    private static BlockPos valve(HardwareStage s) {
        BlockPos root = s.origin.offset(2, -1, 2);
        var block = BwrBlocks.MSIV.get();
        var state = block.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
        s.level.setBlock(root, state, 3);
        block.setPlacedBy(s.level, root, state, null, new ItemStack(block));
        return root;
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theBoxListReadsEveryLightOfABoxInReadingOrder(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "bl-rows", s -> {
            BlockPos device = s.origin.offset(2, -1, 2);
            // A box of 2 x 2 windows placed out of order, a window by itself, and a part that is no window.
            PanelComponent d = s.place(ComponentTypes.ANNUNCIATOR, 2, 2, Footprint.of(2, 2));
            PanelComponent a = s.place(ComponentTypes.ANNUNCIATOR, 0, 0, Footprint.of(2, 2));
            PanelComponent c = s.place(ComponentTypes.ANNUNCIATOR, 0, 2, Footprint.of(2, 2));
            PanelComponent b = s.place(ComponentTypes.ANNUNCIATOR, 2, 0, Footprint.of(2, 2));
            PanelComponent alone = s.place(ComponentTypes.ANNUNCIATOR, 10, 0, Footprint.of(2, 2));
            PanelComponent module = s.place(ComponentTypes.ANNUNCIATOR_CONTROL, 0, 8, Footprint.of(4, 2));
            data(a).line1 = "RPV LEVEL";
            data(a).line3 = "LOW";
            data(a).address = 101;
            data(a).condition = AnnunciatorLogic.BELOW;
            data(a).setpoint = -38.0;
            data(c).boxName = "P603";
            a.setSource(0, new PlantSource(device, "position"));
            b.setSource(0, new CcSource());
            c.setSource(0, RedstoneSource.INSTANCE);
            s.be().setInput(a, 0, 0.0);
            s.be().setInput(b, 0, 1.0);
            s.be().setInput(c, 0, 0.0);

            List<AnnunciatorBoxRows.Row> rows = AnnunciatorBoxRows.rows(s.be(), d.id());
            helper.assertTrue(rows.size() == 4 && rows.get(0).component() == a && rows.get(1).component() == b
                    && rows.get(2).component() == c && rows.get(3).component() == d && "1-1 1-2 2-1 2-2".equals(numbers(rows)),
                    "the four windows of the box, in reading order, numbered as the box prints them: " + numbers(rows));
            for (PanelComponent asked : new PanelComponent[] {a, b, c}) {
                helper.assertTrue("1-1 1-2 2-1 2-2".equals(numbers(AnnunciatorBoxRows.rows(s.be(), asked.id()))), "the same box from any of its windows");
            }
            AnnunciatorBoxRows.Row first = rows.get(0);
            helper.assertTrue("RPV LEVEL LOW".equals(first.legend()) && first.data().address == 101 && "00101".equals(first.data().addressText())
                    && AnnunciatorLogic.BELOW.equals(first.data().condition) && first.data().setpoint == -38.0
                    && first.owner().equals(s.origin) && first.id() == a.id(), "legend, address and trigger of a light");
            helper.assertTrue(first.wiring() == Wiring.PLANT && first.source() instanceof PlantSource plant && plant.device().equals(device)
                    && "position".equals(plant.key()), "a plant signal, with its machine and its signal");
            helper.assertTrue(rows.get(1).wiring() == Wiring.COMPUTER && rows.get(2).wiring() == Wiring.REDSTONE
                    && rows.get(3).wiring() == Wiring.NONE && rows.get(3).legend().isEmpty() && rows.get(3).data().address == AnnunciatorData.NO_ADDRESS,
                    "a computer, redstone, and a window that is not wired, has no legend and no address");
            helper.assertTrue(first.show() == Show.OFF && rows.get(1).show() == Show.ALARM && rows.get(3).show() == Show.NO_SIGNAL,
                    "off, in alarm, and no signal: " + first.show() + " " + rows.get(1).show() + " " + rows.get(3).show());
            helper.assertTrue("P603".equals(AnnunciatorBoxRows.boxName(rows)), "the box's name is the one a window carries");

            AnnunciatorBoxList.Counts counts = AnnunciatorBoxRows.counts(rows);
            helper.assertTrue(counts.lights() == 4 && counts.wired() == 3 && counts.notWired() == 1 && counts.inAlarm() == 1, "the header counts: " + counts);
            helper.assertTrue(AnnunciatorBoxRows.filter(rows, Filter.ALL).size() == 4
                    && AnnunciatorBoxRows.filter(rows, Filter.NOT_WIRED).equals(List.of(rows.get(3)))
                    && AnnunciatorBoxRows.filter(rows, Filter.IN_ALARM).equals(List.of(rows.get(1)))
                    && AnnunciatorBoxRows.filter(rows, Filter.COMPUTER).equals(List.of(rows.get(1))), "the four filters");
            // The state is read from the logic each time: acknowledged is still lit, cleared is off.
            data(b).logic.acknowledge();
            helper.assertTrue(rows.get(1).show() == Show.ACKNOWLEDGED && AnnunciatorBoxRows.counts(rows).inAlarm() == 1, "acknowledged: still lit");
            s.be().setInput(b, 0, 0.0);
            helper.assertTrue(rows.get(1).show() == Show.OFF && AnnunciatorBoxRows.counts(rows).inAlarm() == 0
                    && AnnunciatorBoxRows.filter(rows, Filter.IN_ALARM).isEmpty(), "cleared: off");

            List<AnnunciatorBoxRows.Row> lone = AnnunciatorBoxRows.rows(s.be(), alone.id());
            helper.assertTrue(lone.size() == 1 && lone.get(0).component() == alone && "1-1".equals(lone.get(0).number())
                    && AnnunciatorBoxRows.boxName(lone).isEmpty(), "a window by itself is a box of one, without a name");
            helper.assertTrue(AnnunciatorBoxRows.rows(s.be(), module.id()).isEmpty() && AnnunciatorBoxRows.rows(s.be(), 9_999).isEmpty()
                    && AnnunciatorBoxRows.rows(null, 0).isEmpty(), "anything that is no annunciator window has no list");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theServerNamesABoxsMachinesOnlyForAPlayerWhoMayOpenItsWrenchScreen(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X + 64, ORIGIN_Z, "bl-names", s -> {
            BlockPos root = valve(s);
            BlockPos stone = s.origin.offset(-2, -1, 2);
            s.level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
            BlockPos far = s.origin.offset(4_000, 0, 0);
            BlockEntity machine = DeviceResolver.resolve(s.level, root);
            helper.assertTrue(machine != null, "the fixture's valve is a machine");
            MachineNames.setName(machine, "MSIV A");

            PanelComponent a = s.place(ComponentTypes.ANNUNCIATOR, 0, 0, Footprint.of(2, 2));
            PanelComponent b = s.place(ComponentTypes.ANNUNCIATOR, 2, 0, Footprint.of(2, 2));
            PanelComponent c = s.place(ComponentTypes.ANNUNCIATOR, 4, 0, Footprint.of(2, 2));
            PanelComponent d = s.place(ComponentTypes.ANNUNCIATOR, 6, 0, Footprint.of(2, 2));
            PanelComponent e = s.place(ComponentTypes.ANNUNCIATOR, 8, 0, Footprint.of(2, 2));
            PanelComponent module = s.place(ComponentTypes.ANNUNCIATOR_CONTROL, 0, 8, Footprint.of(4, 2));
            a.setSource(0, new PlantSource(root.above(), "position")); // one cell of the valve
            b.setSource(0, new PlantSource(root, "position"));         // another cell of the same valve
            c.setSource(0, new PlantSource(stone, "position"));        // a block that is no machine
            d.setSource(0, new PlantSource(far, "position"));          // a place that is not loaded
            e.setSource(0, new PlantSource(root.above(), "open"));     // the first cell again

            s.wrench();
            boolean unloaded = s.level.getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null;
            PanelPayloads.BoxNames reply = AnnunciatorBoxRows.names(s.player, s.origin, c.id(), PanelPayloads.MAX_BOX_DEVICES);
            helper.assertTrue(reply != null && reply.pos().equals(s.origin) && reply.id() == c.id(), "the reply names the box it is for");
            helper.assertTrue(reply.names().size() == 2 && reply.names().get(0).device().equals(root.above())
                    && reply.names().get(1).device().equals(root), "each machine position once; no stone, nothing unloaded: " + reply.names());
            String blockKey = s.level.getBlockState(machine.getBlockPos()).getBlock().getDescriptionId();
            helper.assertTrue("MSIV A".equals(reply.names().get(0).name()) && "MSIV A".equals(reply.names().get(1).name())
                    && blockKey.equals(reply.names().get(0).blockKey()), "the machine's own name and its block: " + reply.names().get(0));
            helper.assertTrue(!unloaded || s.level.getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null,
                    "nothing was loaded to answer");

            // A machine nobody named is sent with its block alone.
            MachineNames.setName(machine, "");
            PanelPayloads.DeviceName unnamed = AnnunciatorBoxRows.names(s.player, s.origin, a.id(), PanelPayloads.MAX_BOX_DEVICES).names().get(0);
            helper.assertTrue(unnamed.name().isEmpty() && blockKey.equals(unnamed.blockKey()), "an unnamed machine: " + unnamed);

            // The size cap.
            helper.assertTrue(AnnunciatorBoxRows.names(s.player, s.origin, a.id(), 1).names().size() == 1
                    && AnnunciatorBoxRows.names(s.player, s.origin, a.id(), 0).names().isEmpty()
                    && AnnunciatorBoxRows.names(s.player, s.origin, a.id(), 1_000_000).names().size() == 2, "the reply is capped");

            // Refusals: what is no window, no wrench in hand, out of reach.
            helper.assertTrue(AnnunciatorBoxRows.names(s.player, s.origin, module.id(), 8) == null
                    && AnnunciatorBoxRows.names(s.player, s.origin, 9_999, 8) == null
                    && AnnunciatorBoxRows.names(s.player, s.origin, -1, 8) == null
                    && AnnunciatorBoxRows.names(s.player, s.origin.above(40), a.id(), 8) == null, "only an annunciator window has a box");
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_LINKER.get()));
            helper.assertTrue(AnnunciatorBoxRows.names(s.player, s.origin, a.id(), 8) == null, "it needs the Panel Wrench in hand, like the wrench screen");
            s.wrench();
            s.player.setPos(s.origin.getX() + 14.5, s.origin.getY() - 1.0, s.origin.getZ() - 1.5);
            helper.assertTrue(!PanelOps.inReach(s.player, s.origin) && AnnunciatorBoxRows.names(s.player, s.origin, a.id(), 8) == null,
                    "and the window within reach");
            s.player.setPos(s.origin.getX() + 0.5, s.origin.getY() - 1.0, s.origin.getZ() - 1.5);
            helper.assertTrue(AnnunciatorBoxRows.names(s.player, s.origin, a.id(), 8) != null, "back in reach it answers again");
        });
    }
}
