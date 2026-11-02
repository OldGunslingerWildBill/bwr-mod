package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.Footprints;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The owner's size table for the buttons, lamps and indicators (alpha.60): the retired sizes stay on each type's list
 * (item stacks hold an index into it, placed parts a raw width and height) but nothing offers them any more, the new
 * sizes are appended, and a fresh item starts at the new default.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class IndicatorSizeTableTests {
    private IndicatorSizeTableTests() {}

    private static final int ORIGIN_X = 351_300;
    private static final int ORIGIN_Z = 351_300;

    /** One row of the table: the list before the table, what is retired, what is added, and the new default. */
    private record Row(ComponentType type, int[] before, int[] retired, int[] added, int[] fresh) {
        List<Footprint> beforeList() {
            return pairs(before);
        }

        List<Footprint> retiredList() {
            return pairs(retired);
        }

        List<Footprint> addedList() {
            return pairs(added);
        }

        Footprint freshSize() {
            return Footprint.of(fresh[0], fresh[1]);
        }

        @Override
        public String toString() {
            return type.id().getPath();
        }
    }

    private static List<Footprint> pairs(int... sizes) {
        List<Footprint> list = new ArrayList<>();
        for (int i = 0; i < sizes.length; i += 2) {
            list.add(Footprint.of(sizes[i], sizes[i + 1]));
        }
        return list;
    }

    private static int[] a(int... values) {
        return values;
    }

    private static final int[] SEGMENT_BEFORE = a(20, 8, 5, 8, 10, 8, 15, 8, 25, 8, 30, 8, 40, 8, 50, 8, 60, 8,
            4, 2, 1, 2, 2, 2, 3, 2, 6, 2, 8, 2, 8, 4);
    private static final int[] SEGMENT_RETIRED = a(4, 2, 1, 2, 2, 2, 3, 2, 6, 2, 8, 2);
    private static final int[] SEGMENT_ADDED = a(9, 6, 12, 6, 18, 6, 6, 4, 12, 4);

    /** Every type of the table, from the owner's approved sizes. */
    private static final List<Row> TABLE = List.of(
            new Row(ComponentTypes.PUSHBUTTON, a(6, 6, 8, 8, 1, 1, 2, 2), a(1, 1, 2, 2), a(3, 3, 4, 4), a(4, 4)),
            new Row(ComponentTypes.LAMP, a(4, 4, 6, 6, 1, 1, 2, 2), a(1, 1), a(3, 3), a(3, 3)),
            new Row(ComponentTypes.ANNUNCIATOR, a(12, 8, 16, 12, 1, 1, 2, 2), a(1, 1, 2, 2), a(8, 6, 8, 8), a(8, 6)),
            new Row(ComponentTypes.ANNUNCIATOR_CONTROL, a(24, 6, 2, 1, 4, 2), a(2, 1, 4, 2), a(16, 4, 32, 8), a(32, 8)),
            new Row(ComponentTypes.RPS_TRIP, a(16, 8, 24, 12, 32, 16, 2, 1, 4, 2), a(2, 1, 4, 2), a(8, 4, 12, 6), a(12, 6)),
            new Row(ComponentTypes.BAR_GRAPH, a(3, 24, 3, 12, 3, 16, 3, 32, 24, 3, 12, 3, 16, 3, 32, 3, 1, 3, 3, 1, 1, 6, 6, 1, 2, 6),
                    a(1, 3, 3, 1, 1, 6, 6, 1), a(6, 2), a(2, 6)),
            new Row(ComponentTypes.SEVEN_SEGMENT_DISPLAY, SEGMENT_BEFORE, SEGMENT_RETIRED, SEGMENT_ADDED, a(8, 4)),
            new Row(ComponentTypes.SIXTEEN_SEGMENT_DISPLAY, SEGMENT_BEFORE, SEGMENT_RETIRED, SEGMENT_ADDED, a(8, 4)),
            new Row(ComponentTypes.DIAL_GAUGE, a(12, 12, 16, 16, 20, 20, 24, 24, 32, 32, 2, 2, 3, 3, 4, 4), a(2, 2, 3, 3), a(6, 6, 8, 8), a(4, 4)),
            new Row(ComponentTypes.BAR_GAUGE, a(10, 16, 8, 16, 12, 24, 16, 32, 1, 3, 2, 6), a(1, 3), a(4, 16), a(2, 6)),
            new Row(ComponentTypes.EDGEWISE_GAUGE_VERTICAL, a(6, 16, 6, 24, 8, 24, 8, 32, 1, 3, 2, 6), a(1, 3), a(3, 8), a(2, 6)),
            new Row(ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, a(16, 6, 24, 6, 24, 8, 32, 8, 3, 1, 6, 2), a(3, 1), a(8, 3), a(6, 2)));

    private static FakePlayer player(GameTestHelper h, String name) {
        return FakePlayerFactory.get(h.getLevel(), new GameProfile(
                UUID.nameUUIDFromBytes(("indicator-sizes-" + name).getBytes(StandardCharsets.UTF_8)), "is-" + name));
    }

    private static ItemStack stackAt(ComponentType type, int index) {
        ItemStack stack = new ItemStack(type.item());
        stack.set(BwrDataComponents.COMPONENT_VARIANT.get(), index);
        return stack;
    }

    // ---- the table itself

    @GameTest(template = "empty")
    public static void theSizeTableIsOnTheListsAndNewItemsStartAtTheNewDefault(GameTestHelper h) {
        for (Row row : TABLE) {
            ComponentType type = row.type();
            List<Footprint> sizes = type.footprints();
            List<Footprint> before = row.beforeList();
            // Indices never move: the old list is the front of the new one, the new sizes are appended in order.
            h.assertTrue(sizes.size() == before.size() + row.addedList().size(), row + ": the list is the old list plus the new sizes: " + sizes);
            h.assertTrue(sizes.subList(0, before.size()).equals(before), row + ": an old index moved: " + sizes);
            h.assertTrue(sizes.subList(before.size(), sizes.size()).equals(row.addedList()), row + ": the new sizes are not at the end: " + sizes);
            // Exactly the retired sizes are retired, and everything else (the kept and the unmentioned ones) is offered.
            Set<Integer> expectedRetired = new HashSet<>();
            for (Footprint f : row.retiredList()) {
                h.assertTrue(sizes.contains(f), row + ": the retired " + f + " left the list");
                expectedRetired.add(sizes.indexOf(f));
            }
            h.assertTrue(Footprints.retired(type).equals(expectedRetired), row + ": retired " + Footprints.retired(type) + ", expected " + expectedRetired);
            Set<Footprint> offered = new HashSet<>();
            for (int index : Footprints.offered(type)) {
                offered.add(sizes.get(index));
            }
            Set<Footprint> expectedOffered = new HashSet<>(sizes);
            expectedOffered.removeAll(row.retiredList());
            h.assertTrue(offered.equals(expectedOffered), row + ": offers " + offered + ", expected " + expectedOffered);
            // The new default is what a fresh item, the creative entry and the shared default index give, and it is on offer.
            Footprint fresh = row.freshSize();
            h.assertTrue(sizes.get(Footprints.defaultIndex(type)).equals(fresh), row + ": the default index is " + sizes.get(Math.max(0, Footprints.defaultIndex(type))));
            h.assertTrue(ComponentItem.footprint(new ItemStack(type.item())).equals(fresh), row + ": a new item places "
                    + ComponentItem.footprint(new ItemStack(type.item())) + ", not " + fresh);
            h.assertTrue(ComponentItem.footprint(type.item().getDefaultInstance()).equals(fresh), row + ": the creative entry is not " + fresh);
            h.assertTrue(offered.contains(fresh), row + ": the default " + fresh + " is not offered");
        }
        h.succeed();
    }

    // ---- the picker and the cycle

    @GameTest(template = "empty")
    public static void aRetiredSizeIsNotOfferedByThePickerOrTheCycle(GameTestHelper h) {
        FakePlayer player = player(h, "cycle");
        for (Row row : TABLE) {
            ComponentType type = row.type();
            ComponentItem item = (ComponentItem) type.item();
            Set<Integer> retired = new HashSet<>();
            for (Footprint f : row.retiredList()) {
                retired.add(type.footprints().indexOf(f));
            }
            // The picker lists every size but the retired ones.
            Set<Integer> picker = new HashSet<>();
            for (int index : item.sizes()) {
                h.assertTrue(!retired.contains(index), row + ": the picker offers the retired " + type.footprints().get(index));
                picker.add(index);
            }
            h.assertTrue(picker.size() == type.footprints().size() - retired.size(), row + ": the picker lists " + picker.size() + " sizes");
            // Cycling from a fresh item twice round visits every offered size and never a retired one.
            ItemStack stack = new ItemStack(type.item());
            Set<Integer> visited = new HashSet<>();
            for (int i = 0; i < 2 * type.footprints().size(); i++) {
                ComponentItem.cycle(stack, player);
                int index = type.footprints().indexOf(ComponentItem.footprint(stack));
                h.assertTrue(!retired.contains(index), row + ": cycling reached the retired " + type.footprints().get(index));
                visited.add(index);
            }
            h.assertTrue(visited.equals(picker), row + ": cycling visited " + visited + " of " + picker);
            // The picker's server side refuses a retired index and leaves the stack alone; a new size is taken.
            for (int index : retired) {
                ItemStack held = new ItemStack(type.item());
                h.assertTrue(!ComponentItem.select(held, player, index) && held.get(BwrDataComponents.COMPONENT_VARIANT.get()) == null,
                        row + ": the picker took the retired " + type.footprints().get(index));
            }
            ItemStack held = new ItemStack(type.item());
            int added = type.footprints().indexOf(row.addedList().get(0));
            h.assertTrue(ComponentItem.select(held, player, added) && ComponentItem.footprint(held).equals(row.addedList().get(0)),
                    row + ": the picker did not take the new " + row.addedList().get(0));
            // An old stack holding a retired index cycles on to an offered size instead of staying or wrapping to a retired one.
            for (int index : retired) {
                ItemStack old = stackAt(type, index);
                ComponentItem.cycle(old, player);
                int after = type.footprints().indexOf(ComponentItem.footprint(old));
                h.assertTrue(after != index && !retired.contains(after), row + ": cycling from the retired " + type.footprints().get(index)
                        + " went to " + type.footprints().get(after));
            }
        }
        h.succeed();
    }

    // ---- placing

    /** A stage of two panel blocks side by side (the widest new size, 32 cells, spans both). */
    private static void run(GameTestHelper helper, int slot, String name, java.util.function.Consumer<HardwareStage> body) {
        HardwareStage.run(helper, ORIGIN_X + slot * 400, ORIGIN_Z, name, s -> {
            s.level.setBlock(s.origin.relative(Direction.WEST), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, HardwareStage.FACING), 3);
            body.accept(s);
        });
    }

    /** Places {@code stack} at the top left of the first panel, checks the size that was placed, and picks the part up again. */
    private static PanelComponent placeAndRemove(HardwareStage s, String what, ItemStack stack, ComponentType type, Footprint expected) {
        PanelBlockEntity be = s.be();
        s.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        s.helper.assertTrue(PanelOps.place(s.player, InteractionHand.MAIN_HAND, s.origin, 0, 0) == PanelGroup.Result.OK, what + ": placing was refused");
        PanelBlockEntity.Claim claim = be.claimAt(0, 0);
        s.helper.assertTrue(claim != null, what + ": claimed no cell");
        PanelComponent placed = be.component(claim.id());
        s.helper.assertTrue(placed.type() == type && placed.w() == expected.w() && placed.h() == expected.h(),
                what + ": placed " + placed.w() + "x" + placed.h() + " instead of " + expected);
        // The part drops an item that places the same size again (also for a retired index).
        s.helper.assertTrue(ComponentItem.footprint(PanelBlockEntity.stackFor(placed)).equals(expected), what + ": its item places "
                + ComponentItem.footprint(PanelBlockEntity.stackFor(placed)) + " instead of " + expected);
        s.wrench();
        s.helper.assertTrue(PanelOps.remove(s.player, s.origin, placed.id()) && be.owned().isEmpty(), what + ": the part was not picked up again");
        return placed;
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void anOldStackWithARetiredIndexStillPlacesThatSize(GameTestHelper helper) {
        run(helper, 0, "retired-place", s -> {
            for (Row row : TABLE) {
                ComponentType type = row.type();
                for (Footprint retired : row.retiredList()) {
                    int index = type.footprints().indexOf(retired);
                    ItemStack old = stackAt(type, index);
                    helper.assertTrue(ComponentItem.footprint(old).equals(retired), row + ": the old index " + index + " means " + ComponentItem.footprint(old));
                    placeAndRemove(s, row + " old index " + index, old, type, retired);
                }
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theNewSizesAndTheNewDefaultsPlace(GameTestHelper helper) {
        run(helper, 1, "new-place", s -> {
            for (Row row : TABLE) {
                ComponentType type = row.type();
                for (Footprint added : row.addedList()) {
                    int index = type.footprints().indexOf(added);
                    helper.assertTrue(index >= 0 && !Footprints.isRetired(type, index), row + ": the new " + added + " is missing or retired");
                    placeAndRemove(s, row + " new size " + added, stackAt(type, index), type, added);
                }
                // A bare stack is a freshly made item: it places the new default.
                placeAndRemove(s, row + " bare stack", new ItemStack(type.item()), type, row.freshSize());
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aSavedPlacedComponentOfARetiredSizeLoadsAndKeepsItsSize(GameTestHelper helper) {
        run(helper, 2, "retired-save", s -> {
            PanelBlockEntity be = s.be();
            for (Row row : TABLE) {
                ComponentType type = row.type();
                for (Footprint retired : row.retiredList()) {
                    String what = row + " " + retired;
                    // The part on its own: saved and loaded again.
                    PanelComponent alone = new PanelComponent(1, type, 0, 0, retired, "OLD");
                    PanelComponent loaded = PanelComponent.load(alone.save());
                    helper.assertTrue(loaded.w() == retired.w() && loaded.h() == retired.h(), what + ": a saved part loads as " + loaded.w() + "x" + loaded.h());
                    // Placed on a panel, the panel saved and read back the way a world does it.
                    PanelComponent placed = s.place(type, 0, 0, retired);
                    CompoundTag saved = be.saveWithFullMetadata(s.level.registryAccess());
                    BlockEntity back = BlockEntity.loadStatic(s.origin, s.level.getBlockState(s.origin), saved, s.level.registryAccess());
                    helper.assertTrue(back instanceof PanelBlockEntity, what + ": the panel did not load");
                    List<PanelComponent> owned = ((PanelBlockEntity) back).owned();
                    helper.assertTrue(owned.size() == 1 && owned.get(0).type() == type && owned.get(0).w() == retired.w() && owned.get(0).h() == retired.h(),
                            what + ": the loaded panel holds " + describe(owned));
                    s.wrench();
                    helper.assertTrue(PanelOps.remove(s.player, s.origin, placed.id()) && be.owned().isEmpty(), what + ": the part was not picked up again");
                }
            }
        });
    }

    private static String describe(List<PanelComponent> owned) {
        List<String> out = new ArrayList<>();
        for (PanelComponent c : owned) {
            out.add(c.type().id().getPath() + " " + c.w() + "x" + c.h());
        }
        return out.toString();
    }
}
