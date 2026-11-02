package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.fabrication.FabricationRecipe;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.Footprints;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
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
 * The owner-approved size table of the switch parts (toggle, key switch, rotary selector, setpoint knob, control switch,
 * push-to-turn, joystick bypass): retired sizes stay on a type's list at their index (an old item stack and a saved panel
 * keep them) but are not offered by the size picker or the cycling of the item ({@link Footprints}); new sizes are appended;
 * a new item starts at the new default.
 *
 * <p>World fixture at 351_700 / 351_700 (see {@link HardwareStage}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class SwitchSizeTests {
    private SwitchSizeTests() {
    }

    static final int ORIGIN_X = 351_700;
    static final int ORIGIN_Z = 351_700;

    private static Footprint f(int w, int h) {
        return Footprint.of(w, h);
    }

    /** One row of the approved table. */
    private record Row(ComponentType type, List<Footprint> retired, List<Footprint> kept, List<Footprint> added, Footprint start) {
    }

    private static final List<Row> TABLE = List.of(
            new Row(ComponentTypes.TOGGLE_SWITCH, List.of(f(1, 1)), List.of(f(2, 2), f(6, 8), f(8, 10)), List.of(f(4, 4)), f(4, 4)),
            new Row(ComponentTypes.KEY_SWITCH, List.of(f(1, 2)), List.of(f(2, 4), f(6, 8), f(8, 10)), List.of(f(3, 4), f(4, 6)), f(3, 4)),
            new Row(ComponentTypes.ROTARY_SELECTOR, List.of(f(1, 2), f(2, 3)), List.of(f(2, 4), f(8, 10), f(12, 14)),
                    List.of(f(4, 6), f(6, 8)), f(4, 6)),
            new Row(ComponentTypes.SETPOINT_KNOB, List.of(f(1, 1)), List.of(f(2, 2), f(8, 8), f(12, 12)), List.of(f(4, 4), f(6, 6)), f(4, 4)),
            new Row(ComponentTypes.CONTROL_SWITCH, List.of(f(1, 2), f(2, 3)), List.of(f(8, 12), f(12, 16)), List.of(f(6, 9)), f(8, 12)),
            new Row(ComponentTypes.PUSH_TURN_SWITCH, List.of(f(1, 1)), List.of(f(2, 2), f(8, 8), f(12, 12), f(16, 16)),
                    List.of(f(4, 4), f(6, 6)), f(4, 4)),
            new Row(ComponentTypes.JOYSTICK_SWITCH, List.of(f(1, 1), f(2, 2)), List.of(f(4, 4), f(8, 8), f(12, 12), f(16, 16)),
                    List.of(f(6, 6)), f(6, 6)));

    private static FakePlayer player(GameTestHelper helper, String name) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(
                UUID.nameUUIDFromBytes(("switch-size-" + name).getBytes(StandardCharsets.UTF_8)), "ss-" + name));
    }

    private static ItemStack stackAt(ComponentType type, Footprint size) {
        ItemStack stack = new ItemStack(type.item());
        stack.set(BwrDataComponents.COMPONENT_VARIANT.get(), type.footprints().indexOf(size));
        return stack;
    }

    @GameTest(template = "empty")
    public static void theListsHoldEveryApprovedSizeAndTheNewOnesAreAppended(GameTestHelper h) {
        for (Row row : TABLE) {
            List<Footprint> sizes = row.type().footprints();
            String name = row.type().id().getPath();
            for (List<Footprint> group : List.of(row.retired(), row.kept(), row.added())) {
                for (Footprint size : group) {
                    h.assertTrue(sizes.contains(size), name + " lost " + size + ": " + sizes);
                }
            }
            h.assertTrue(sizes.size() == new HashSet<>(sizes).size(), name + " lists a size twice: " + sizes);
            // The added sizes are the end of the list, in the table's order, so no stored index moved.
            h.assertTrue(sizes.subList(sizes.size() - row.added().size(), sizes.size()).equals(row.added()),
                    name + ": the new sizes are not appended at the end: " + sizes);
            Set<Footprint> offered = new HashSet<>();
            for (int index : Footprints.offered(row.type())) {
                offered.add(sizes.get(index));
            }
            Set<Footprint> expected = new HashSet<>(row.kept());
            expected.addAll(row.added());
            h.assertTrue(offered.equals(expected), name + " offers " + offered + ", expected " + expected);
            h.assertTrue(row.type().retiredFootprints().equals(new HashSet<>(row.retired())),
                    name + " retires " + row.type().retiredFootprints() + ", expected " + row.retired());
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void aRetiredSizeIsNotOfferedByThePickerOrTheCycle(GameTestHelper h) {
        FakePlayer player = player(h, "offer");
        for (Row row : TABLE) {
            ComponentType type = row.type();
            String name = type.id().getPath();
            ComponentItem item = (ComponentItem) type.item();
            // The size picker lists item.sizes(): none of the retired ones, all of the others, smallest first.
            List<Footprint> listed = new ArrayList<>();
            for (int index : item.sizes()) {
                listed.add(type.footprints().get(index));
            }
            for (Footprint size : row.retired()) {
                h.assertTrue(!listed.contains(size), name + ": the picker lists the retired " + size);
            }
            h.assertTrue(listed.size() == row.kept().size() + row.added().size() && listed.containsAll(row.kept())
                    && listed.containsAll(row.added()), name + ": the picker lists " + listed);
            // Cycling from the default goes round the offered sizes only, and wraps.
            ItemStack stack = new ItemStack(item);
            Set<Footprint> seen = new HashSet<>();
            for (int i = 0; i < listed.size() * 2 + 2; i++) {
                Footprint now = ComponentItem.footprint(stack);
                h.assertTrue(!row.retired().contains(now), name + ": cycling reached the retired " + now);
                seen.add(now);
                ComponentItem.cycle(stack, player);
            }
            h.assertTrue(seen.equals(new HashSet<>(listed)), name + ": cycling reached " + seen + ", not " + listed);
            // An item that holds a retired size cycles out of it onto an offered one, never onto another retired size.
            for (Footprint old : row.retired()) {
                ItemStack oldStack = stackAt(type, old);
                ComponentItem.cycle(oldStack, player);
                h.assertTrue(listed.contains(ComponentItem.footprint(oldStack)),
                        name + ": cycling from the retired " + old + " went to " + ComponentItem.footprint(oldStack));
                // The picker's server side refuses a retired index and leaves the stack alone.
                ItemStack picked = new ItemStack(item);
                h.assertTrue(!ComponentItem.select(picked, player, type.footprints().indexOf(old))
                        && picked.get(BwrDataComponents.COMPONENT_VARIANT.get()) == null,
                        name + ": the picker's payload took the retired " + old);
            }
            // ...and accepts the others.
            for (Footprint size : listed) {
                ItemStack picked = new ItemStack(item);
                h.assertTrue(ComponentItem.select(picked, player, type.footprints().indexOf(size))
                        && ComponentItem.footprint(picked).equals(size), name + ": the picker's payload refused " + size);
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void anOldStackWithARetiredIndexStillPlacesAtThatSize(GameTestHelper h) {
        HardwareStage.run(h, ORIGIN_X, ORIGIN_Z, "old-stack", s -> {
            PanelBlockEntity be = s.be();
            for (Row row : TABLE) {
                for (Footprint old : row.retired()) {
                    ComponentType type = row.type();
                    ItemStack stack = stackAt(type, old);
                    h.assertTrue(ComponentItem.footprint(stack).equals(old), type.id() + ": the stored index of " + old
                            + " now means " + ComponentItem.footprint(stack));
                    s.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    h.assertTrue(PanelOps.place(s.player, InteractionHand.MAIN_HAND, s.origin, 0, 0) == PanelGroup.Result.OK,
                            "placing the old " + old + " " + type.id());
                    PanelComponent placed = be.component(be.claimAt(0, 0).id());
                    h.assertTrue(placed.type() == type && placed.w() == old.w() && placed.h() == old.h(),
                            type.id() + ": the old " + old + " placed " + placed.w() + "x" + placed.h());
                    // Picked up again, it drops an item that holds the same index, so the size survives the trip.
                    ItemStack dropped = PanelBlockEntity.stackFor(placed);
                    h.assertTrue(ComponentItem.footprint(dropped).equals(old), type.id() + ": the dropped item places "
                            + ComponentItem.footprint(dropped) + ", not " + old);
                    s.wrench();
                    h.assertTrue(PanelOps.remove(s.player, s.origin, placed.id()) && be.owned().isEmpty(), "picked up again");
                }
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aSavedPlacedComponentOfARetiredSizeLoadsAndKeepsItsSize(GameTestHelper h) {
        HardwareStage.run(h, ORIGIN_X + 64, ORIGIN_Z, "saved", s -> {
            // Every retired size on one board (they are at most 2 x 3 cells), saved with the block and loaded into a fresh block.
            List<PanelComponent> placed = new ArrayList<>();
            int n = 0;
            for (Row row : TABLE) {
                for (Footprint old : row.retired()) {
                    placed.add(s.place(row.type(), (n % 8) * 2, (n / 8) * 4, old));
                    n++;
                }
            }
            CompoundTag saved = s.be().saveWithoutMetadata(s.level.registryAccess());
            s.level.setBlock(s.origin, Blocks.AIR.defaultBlockState(), 2);
            s.level.setBlock(s.origin, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, HardwareStage.FACING), 3);
            PanelBlockEntity fresh = s.be();
            h.assertTrue(fresh.owned().isEmpty(), "the fresh block is empty before loading");
            fresh.loadWithComponents(saved, s.level.registryAccess());
            h.assertTrue(fresh.owned().size() == placed.size(), "the loaded block holds " + fresh.owned().size() + " of " + placed.size());
            for (PanelComponent before : placed) {
                PanelComponent after = fresh.component(before.id());
                h.assertTrue(after != null && after.type() == before.type() && after.w() == before.w() && after.h() == before.h(),
                        before.type().id() + ": a saved " + before.w() + "x" + before.h() + " component loads as "
                                + (after == null ? "nothing" : after.w() + "x" + after.h()));
                // And the single component's own tag, as the network sync carries it.
                PanelComponent single = PanelComponent.load(before.save());
                h.assertTrue(single.w() == before.w() && single.h() == before.h(), "its own tag loads as " + single.w() + "x" + single.h());
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theNewSizesPlaceAndWork(GameTestHelper h) {
        HardwareStage.run(h, ORIGIN_X + 128, ORIGIN_Z, "new-sizes", s -> {
            PanelBlockEntity be = s.be();
            for (Row row : TABLE) {
                ComponentType type = row.type();
                for (Footprint size : row.added()) {
                    ItemStack stack = stackAt(type, size);
                    h.assertTrue(ComponentItem.footprint(stack).equals(size) && Footprints.offered(type).length > 0
                            && !Footprints.retired(type, type.footprints().indexOf(size)), type.id() + ": " + size + " is not on offer");
                    s.player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    h.assertTrue(PanelOps.place(s.player, InteractionHand.MAIN_HAND, s.origin, 0, 0) == PanelGroup.Result.OK,
                            "placing a " + type.id() + " at " + size);
                    PanelComponent placed = be.component(be.claimAt(0, 0).id());
                    h.assertTrue(placed.w() == size.w() && placed.h() == size.h(), type.id() + " placed " + placed.w() + "x" + placed.h());
                    // The click and the scroll land on the part at its new size (the click value is read at a cell inside it).
                    clickWorks(h, s, placed, size);
                    s.wrench();
                    h.assertTrue(PanelOps.remove(s.player, s.origin, placed.id()) && be.owned().isEmpty(), "picked up again");
                }
            }
        });
    }

    /** Operates the part the way the client sends a click on its right side and checks the output moved. */
    private static void clickWorks(GameTestHelper h, HardwareStage s, PanelComponent part, Footprint size) {
        ComponentType type = part.type();
        double right = type.clickValue(part, size.w() * 0.9, size.h() * 0.5);
        double top = type.clickValue(part, size.w() * 0.5, size.h() * 0.1);
        if (type == ComponentTypes.TOGGLE_SWITCH) {
            h.assertTrue(PanelOps.operate(s.player, s.origin, part.id(), OperateAction.PRESS, right) && part.output(0) == 1.0,
                    "a click flips the toggle at " + size);
        } else if (type == ComponentTypes.ROTARY_SELECTOR) {
            h.assertTrue(right == 1.0 && type.clickValue(part, size.w() * 0.1, size.h() * 0.5) == -1.0
                    && PanelOps.operate(s.player, s.origin, part.id(), OperateAction.PRESS, right) && part.output(0) == 1.0,
                    "the right half of the selector turns it up at " + size + ": " + part.output(0));
        } else if (type == ComponentTypes.SETPOINT_KNOB) {
            h.assertTrue(PanelOps.operate(s.player, s.origin, part.id(), OperateAction.PRESS, right) && part.output(0) > 0.0,
                    "the right half of the knob raises it at " + size + ": " + part.output(0));
        } else if (type == ComponentTypes.JOYSTICK_SWITCH) {
            h.assertTrue(PanelOps.operate(s.player, s.origin, part.id(), OperateAction.PRESS, top) && part.output(0) == 1.0
                    && part.output(4) == 1.0, "a click on the top of the plate is up at " + size);
        }
        // The other types (key, control, push-to-turn) need a key or a held press, which their own tests cover at any size.
    }

    @GameTest(template = "empty")
    public static void aNewItemStartsAtTheNewDefault(GameTestHelper h) {
        for (Row row : TABLE) {
            ComponentType type = row.type();
            String name = type.id().getPath();
            h.assertTrue(row.type().footprints().get(type.realDefaultIndex()).equals(row.start()), name + ": the default index is "
                    + type.footprints().get(type.realDefaultIndex()) + ", not " + row.start());
            h.assertTrue(!Footprints.retired(type, type.realDefaultIndex()), name + ": the default is a retired size");
            // A bare stack, the creative tab's entry, and what the fabrication desk (or, for the joystick, the crafting grid) makes.
            h.assertTrue(ComponentItem.footprint(new ItemStack(type.item())).equals(row.start()), name + ": a bare stack is not " + row.start());
            h.assertTrue(ComponentItem.footprint(type.item().getDefaultInstance()).equals(row.start()), name + ": the creative entry is not " + row.start());
            List<ItemStack> made = new ArrayList<>();
            for (var holder : h.getLevel().getRecipeManager().getAllRecipesFor(FabricationRecipe.TYPE)) {
                ItemStack result = holder.value().getResultItem(h.getLevel().registryAccess());
                if (result.is(type.item())) {
                    made.add(result);
                }
            }
            for (var holder : h.getLevel().getRecipeManager().getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING)) {
                ItemStack result = holder.value().getResultItem(h.getLevel().registryAccess());
                if (result.is(type.item())) {
                    made.add(result);
                }
            }
            h.assertTrue(!made.isEmpty(), name + " has no recipe");
            for (ItemStack result : made) {
                h.assertTrue(ComponentItem.footprint(result).equals(row.start()), name + ": a recipe makes a " + ComponentItem.footprint(result));
            }
            // The picker starts on it: the stack's current size is the default, and it is one of the rows.
            int current = ComponentItem.variant(new ItemStack(type.item()), type);
            boolean listed = false;
            for (int index : ((ComponentItem) type.item()).sizes()) {
                listed |= index == current;
            }
            h.assertTrue(listed, name + ": the size picker starts on a size it does not list");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aBareItemPlacesTheNewDefault(GameTestHelper h) {
        HardwareStage.run(h, ORIGIN_X + 192, ORIGIN_Z, "default", s -> {
            PanelBlockEntity be = s.be();
            for (Row row : TABLE) {
                s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(row.type().item()));
                h.assertTrue(PanelOps.place(s.player, InteractionHand.MAIN_HAND, s.origin, 0, 0) == PanelGroup.Result.OK,
                        "placing a bare " + row.type().id());
                PanelComponent placed = be.component(be.claimAt(0, 0).id());
                h.assertTrue(placed.w() == row.start().w() && placed.h() == row.start().h(),
                        row.type().id() + ": a bare item placed " + placed.w() + "x" + placed.h() + ", not " + row.start());
                s.wrench();
                h.assertTrue(PanelOps.remove(s.player, s.origin, placed.id()) && be.owned().isEmpty(), "picked up again");
            }
        });
    }
}
