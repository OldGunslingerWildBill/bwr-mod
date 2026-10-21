package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentItem;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelNetwork;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.registry.BwrItems;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Real size controls: every instrument and control has its real size appended to its footprint list and a new item
 * starts at twice that in each dimension (also appended); the big sizes stay on the list (they are reached by cycling or the size picker) and indices stored in
 * old item stacks and sizes saved in old components mean what they always did.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class RealScaleTests {
    private RealScaleTests() {}

    /** Fixture origin of the placing test (grep before changing: no other test uses 146_000). */
    private static final int ORIGIN_X = 146_000;
    private static final int ORIGIN_Z = 146_000;

    /**
     * The footprint lists as they were before real sizes: the first entries of each list never move. The bar gauge got
     * its real size (1x3) last: its BarGaugeTests assumed a case at least 11 cells tall until their margins were made
     * relative to the case (see CONTROL-PANELS.md, "Real sizes").
     */
    private static final List<Object[]> LEGACY = List.of(
            legacy(ComponentTypes.DIAL_GAUGE, 12, 12, 16, 16, 20, 20, 24, 24, 32, 32),
            legacy(ComponentTypes.EDGEWISE_GAUGE_VERTICAL, 6, 16, 6, 24, 8, 24, 8, 32),
            legacy(ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, 16, 6, 24, 6, 24, 8, 32, 8),
            legacy(ComponentTypes.CONTROL_SWITCH, 8, 12, 12, 16),
            legacy(ComponentTypes.KEY_SWITCH, 6, 8, 8, 10),
            legacy(ComponentTypes.ROTARY_SELECTOR, 8, 10, 12, 14),
            legacy(ComponentTypes.SETPOINT_KNOB, 8, 8, 12, 12),
            legacy(ComponentTypes.TOGGLE_SWITCH, 6, 8, 8, 10),
            legacy(ComponentTypes.THUMBWHEEL, 9, 6, 3, 6, 6, 6, 12, 6, 15, 6, 18, 6, 21, 6, 24, 6),
            legacy(ComponentTypes.LAMP, 4, 4, 6, 6),
            legacy(ComponentTypes.PUSHBUTTON, 6, 6, 8, 8),
            legacy(ComponentTypes.RPS_TRIP, 16, 8, 24, 12, 32, 16),
            legacy(ComponentTypes.PUSH_TURN_SWITCH, 8, 8, 12, 12, 16, 16),
            legacy(ComponentTypes.ANNUNCIATOR, 12, 8, 16, 12),
            legacy(ComponentTypes.SEVEN_SEGMENT_DISPLAY, 20, 8, 5, 8, 10, 8, 15, 8, 25, 8, 30, 8, 40, 8, 50, 8, 60, 8),
            legacy(ComponentTypes.SIXTEEN_SEGMENT_DISPLAY, 20, 8, 5, 8, 10, 8, 15, 8, 25, 8, 30, 8, 40, 8, 50, 8, 60, 8),
            legacy(ComponentTypes.BAR_GRAPH, 3, 24, 3, 12, 3, 16, 3, 32, 24, 3, 12, 3, 16, 3, 32, 3),
            // The owner's bar gauge, real size since the gauge polish (its layout tests are relative to the case).
            legacy(ComponentTypes.BAR_GAUGE, 10, 16, 8, 16, 12, 24, 16, 32));

    private static Object[] legacy(ComponentType type, int... sizes) {
        List<Footprint> list = new ArrayList<>();
        for (int i = 0; i < sizes.length; i += 2) {
            list.add(Footprint.of(sizes[i], sizes[i + 1]));
        }
        return new Object[] {type, list};
    }

    @SuppressWarnings("unchecked")
    private static List<Footprint> legacyOf(Object[] row) {
        return (List<Footprint>) row[1];
    }

    private static List<ComponentType> inScope() {
        List<ComponentType> types = new ArrayList<>();
        for (Object[] row : LEGACY) {
            types.add((ComponentType) row[0]);
        }
        return types;
    }

    private static FakePlayer player(GameTestHelper helper, String name) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(
                UUID.nameUUIDFromBytes(("real-scale-" + name).getBytes(StandardCharsets.UTF_8)), "rs-" + name));
    }

    @GameTest(template = "empty")
    public static void everyInstrumentAndControlHasItsRealSizeAtTheEndOfItsList(GameTestHelper h) {
        for (Object[] row : LEGACY) {
            ComponentType type = (ComponentType) row[0];
            List<Footprint> old = legacyOf(row);
            List<Footprint> sizes = type.footprints();
            String name = type.id().getPath();
            h.assertTrue(type.realSizes() > 0, name + " has no real size");
            h.assertTrue(sizes.size() == old.size() + type.realSizes(), name + ": the real sizes are the whole tail of the list ("
                    + sizes + ")");
            for (int i = 0; i < old.size(); i++) {
                h.assertTrue(sizes.get(i).equals(old.get(i)), name + ": index " + i + " moved from " + old.get(i) + " to " + sizes.get(i));
            }
            int limit = type.maxSpanBlocks() * 16;
            for (int i = old.size(); i < sizes.size(); i++) {
                Footprint f = sizes.get(i);
                h.assertTrue(f.w() >= 1 && f.h() >= 1 && f.w() <= limit && f.h() <= limit, name + ": " + f + " is outside the limits");
                // A real size is a lot smaller than the old default (the long displays and bar graphs are thin).
                h.assertTrue(f.w() * f.h() < old.get(0).w() * old.get(0).h() || f.w() * f.h() <= 12,
                        name + ": the real size " + f + " is not smaller than the old default " + old.get(0));
                h.assertTrue(!old.contains(f), name + ": real size " + f + " repeats an old size");
            }
            // A new item starts at twice the first real size in each dimension (owner's decision, gauge polish); the exact
            // real size stays on the list, right after the old sizes.
            // The control switch is the owner's exception: it starts at its first size, 8x12 (index 0).
            Footprint firstReal = sizes.get(old.size());
            Footprint start = type == ComponentTypes.CONTROL_SWITCH ? Footprint.of(8, 12) : Footprint.of(2 * firstReal.w(), 2 * firstReal.h());
            h.assertTrue(sizes.get(type.realDefaultIndex()).equals(start) && (type == ComponentTypes.CONTROL_SWITCH
                            ? type.realDefaultIndex() == 0 : type.realDefaultIndex() >= old.size()),
                    name + ": a new item starts at " + start + ", not at " + sizes.get(type.realDefaultIndex()));
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void newItemsStartAtRealSizeAndTheBigOnesStaySelectable(GameTestHelper h) {
        for (ComponentType type : inScope()) {
            Item item = type.item();
            h.assertTrue(item instanceof ComponentItem, type.id() + " has no component item");
            // A bare stack and the creative tab's entry (a default instance) are the same thing.
            ItemStack bare = new ItemStack(item);
            ItemStack tab = item.getDefaultInstance();
            Footprint real = type.footprints().get(type.realDefaultIndex());
            h.assertTrue(ComponentItem.footprint(bare).equals(real), type.id() + ": a bare stack places " + ComponentItem.footprint(bare)
                    + " instead of the real size " + real);
            h.assertTrue(ComponentItem.footprint(tab).equals(real), type.id() + ": the creative tab entry is not at real size");
            // Cycling reaches every size, the old big ones included, from smallest to biggest, and wraps.
            FakePlayer player = player(h, "cycle");
            ItemStack stack = new ItemStack(item);
            java.util.Set<Footprint> seen = new java.util.HashSet<>();
            int[] order = type.sizeOrder();
            // Walk to the smallest size first: cycling from the default may begin mid-list.
            for (int i = 0; i < order.length; i++) {
                if (ComponentItem.footprint(stack).equals(type.footprints().get(order[0]))) {
                    break;
                }
                ComponentItem.cycle(stack, player);
            }
            int previousArea = -1;
            for (int i = 0; i < order.length; i++) {
                Footprint f = ComponentItem.footprint(stack);
                int area = f.w() * f.h();
                h.assertTrue(area >= previousArea, type.id() + ": cycling went down from area " + previousArea + " to " + f);
                previousArea = area;
                seen.add(f);
                ComponentItem.cycle(stack, player);
            }
            h.assertTrue(seen.size() == new java.util.HashSet<>(type.footprints()).size(), type.id() + ": cycling missed a size: " + seen);
            h.assertTrue(ComponentItem.footprint(stack).equals(type.footprints().get(order[0])), type.id() + ": cycling does not wrap to the smallest");
            for (Object[] row : LEGACY) {
                if (row[0] == type) {
                    for (Footprint old : legacyOf(row)) {
                        h.assertTrue(seen.contains(old), type.id() + ": the old size " + old + " cannot be chosen any more");
                    }
                }
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void craftedStacksStartAtRealSize(GameTestHelper h) {
        for (ComponentType type : inScope()) {
            var recipe = h.getLevel().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("bwr", type.id().getPath()));
            h.assertTrue(recipe.isPresent(), type.id() + " has no recipe of its own name");
            ItemStack result = recipe.get().value().getResultItem(h.getLevel().registryAccess());
            h.assertTrue(result.is(type.item()), type.id() + ": its recipe makes " + result);
            h.assertTrue(ComponentItem.footprint(result).equals(type.footprints().get(type.realDefaultIndex())),
                    type.id() + ": a crafted stack is " + ComponentItem.footprint(result) + ", not the real size");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void storedIndicesAndSavedSizesKeepTheirMeaning(GameTestHelper h) {
        for (Object[] row : LEGACY) {
            ComponentType type = (ComponentType) row[0];
            List<Footprint> old = legacyOf(row);
            for (int i = 0; i < old.size(); i++) {
                // An item stack made before real sizes holds an index; it places what that index always did.
                ItemStack stack = new ItemStack(type.item());
                stack.set(BwrDataComponents.COMPONENT_VARIANT.get(), i);
                h.assertTrue(ComponentItem.footprint(stack).equals(old.get(i)), type.id() + ": stored index " + i + " changed meaning");
                // A component placed at that size loads at that size, however the list has grown.
                PanelComponent placed = new PanelComponent(1, type, 0, 0, old.get(i), "OLD");
                PanelComponent loaded = PanelComponent.load(placed.save());
                h.assertTrue(loaded.w() == old.get(i).w() && loaded.h() == old.get(i).h(),
                        type.id() + ": a saved " + old.get(i) + " component loads as " + loaded.w() + "x" + loaded.h());
                // Its item drops with its index, also index 0, so that it is not placed at the real size instead.
                ItemStack dropped = PanelBlockEntity.stackFor(loaded);
                h.assertTrue(ComponentItem.footprint(dropped).equals(old.get(i)), type.id() + ": the item of an old " + old.get(i)
                        + " component places " + ComponentItem.footprint(dropped));
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aBareStackPlacesTheRealSizeAndAnOldIndexPlacesTheBigOne(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "real-place", s -> {
            List<ComponentType> types = List.of(ComponentTypes.DIAL_GAUGE, ComponentTypes.LAMP,
                    ComponentTypes.ANNUNCIATOR, ComponentTypes.EDGEWISE_GAUGE_VERTICAL,
                    ComponentTypes.PUSHBUTTON, ComponentTypes.SEVEN_SEGMENT_DISPLAY, ComponentTypes.THUMBWHEEL);
            // An item holding an old index still places the old big size. (Placed first and picked up again with the wrench,
            // since the doubled real size parts that follow fill the board to row 10 and the old dial needs 12.)
            PanelBlockEntity be = s.be();
            ItemStack old = new ItemStack(BwrItems.DIAL_GAUGE.get());
            old.set(BwrDataComponents.COMPONENT_VARIANT.get(), 0);
            s.player.setItemInHand(InteractionHand.MAIN_HAND, old);
            helper.assertTrue(PanelOps.place(s.player, InteractionHand.MAIN_HAND, s.origin, 0, 0) == PanelGroup.Result.OK,
                    "placing an old dial index");
            int oldId = be.claimAt(0, 0).id();
            PanelComponent big = be.component(oldId);
            helper.assertTrue(big.w() == 12 && big.h() == 12, "an old index 0 dial is 12x12, not " + big.w() + "x" + big.h());
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
            helper.assertTrue(PanelOps.remove(s.player, s.origin, oldId) && be.owned().isEmpty(), "the old dial is picked up again");
            // The control switch starts at 8x12 (the owner's exception to twice the real size): placed and picked up on its own.
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ComponentTypes.CONTROL_SWITCH.item()));
            helper.assertTrue(PanelOps.place(s.player, InteractionHand.MAIN_HAND, s.origin, 0, 0) == PanelGroup.Result.OK,
                    "placing a bare control switch");
            int switchId = be.claimAt(0, 0).id();
            helper.assertTrue(be.component(switchId).w() == 8 && be.component(switchId).h() == 12,
                    "a bare control switch is 8x12, not " + be.component(switchId).w() + "x" + be.component(switchId).h());
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
            helper.assertTrue(PanelOps.remove(s.player, s.origin, switchId) && be.owned().isEmpty(), "the control switch is picked up again");
            // Real size parts are only a few cells across, so a dense row of them fits where one old dial filled the board.
            int cx = 0;
            int cy = 0;
            int rowHeight = 0;
            for (ComponentType type : types) {
                Footprint real = type.footprints().get(type.realDefaultIndex());
                if (cx + real.w() > 16) {
                    cx = 0;
                    cy += rowHeight;
                    rowHeight = 0;
                }
                s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(type.item()));
                helper.assertTrue(PanelOps.place(s.player, InteractionHand.MAIN_HAND, s.origin, cx, cy) == PanelGroup.Result.OK,
                        "placing a bare " + type.id() + " at " + cx + "," + cy);
                PanelBlockEntity.Claim claim = be.claimAt(cx, cy);
                helper.assertTrue(claim != null, type.id() + " claimed no cell");
                PanelComponent placed = be.component(claim.id());
                helper.assertTrue(placed.type() == type && placed.w() == real.w() && placed.h() == real.h(),
                        type.id() + " placed " + placed.w() + "x" + placed.h() + " instead of " + real);
                cx += real.w();
                rowHeight = Math.max(rowHeight, real.h());
            }
            helper.assertTrue(be.owned().size() == types.size(), "all of them are on one block face: " + be.owned().size());
        });
    }

    /** The owner's decision: a new item starts at twice the real size in each dimension (a control switch at 8x12), the exact real size one pick away. */
    @GameTest(template = "empty")
    public static void theDefaultIsTwiceTheRealSizeAndTheExactRealSizeStaysSelectable(GameTestHelper h) {
        Object[][] expected = {
                {ComponentTypes.DIAL_GAUGE, Footprint.of(4, 4), Footprint.of(2, 2)},
                {ComponentTypes.CONTROL_SWITCH, Footprint.of(8, 12), Footprint.of(1, 2)},
                {ComponentTypes.LAMP, Footprint.of(2, 2), Footprint.of(1, 1)},
                {ComponentTypes.ANNUNCIATOR, Footprint.of(2, 2), Footprint.of(1, 1)},
                {ComponentTypes.ANNUNCIATOR_CONTROL, Footprint.of(4, 2), Footprint.of(2, 1)},
                {ComponentTypes.BAR_GAUGE, Footprint.of(2, 6), Footprint.of(1, 3)},
                {ComponentTypes.BAR_GRAPH, Footprint.of(2, 6), Footprint.of(1, 3)},
                {ComponentTypes.EDGEWISE_GAUGE_VERTICAL, Footprint.of(2, 6), Footprint.of(1, 3)},
                {ComponentTypes.EDGEWISE_GAUGE_HORIZONTAL, Footprint.of(6, 2), Footprint.of(3, 1)},
                {ComponentTypes.SEVEN_SEGMENT_DISPLAY, Footprint.of(8, 4), Footprint.of(4, 2)},
                {ComponentTypes.SIXTEEN_SEGMENT_DISPLAY, Footprint.of(8, 4), Footprint.of(4, 2)},
                {ComponentTypes.THUMBWHEEL, Footprint.of(6, 4), Footprint.of(3, 2)},
                {ComponentTypes.KEY_SWITCH, Footprint.of(2, 4), Footprint.of(1, 2)},
                {ComponentTypes.ROTARY_SELECTOR, Footprint.of(2, 4), Footprint.of(1, 2)},
                {ComponentTypes.SETPOINT_KNOB, Footprint.of(2, 2), Footprint.of(1, 1)},
                {ComponentTypes.TOGGLE_SWITCH, Footprint.of(2, 2), Footprint.of(1, 1)},
                {ComponentTypes.PUSHBUTTON, Footprint.of(2, 2), Footprint.of(1, 1)},
                {ComponentTypes.PUSH_TURN_SWITCH, Footprint.of(2, 2), Footprint.of(1, 1)},
                {ComponentTypes.RPS_TRIP, Footprint.of(4, 2), Footprint.of(2, 1)}};
        for (Object[] row : expected) {
            ComponentType type = (ComponentType) row[0];
            Footprint twice = (Footprint) row[1];
            Footprint exact = (Footprint) row[2];
            h.assertTrue(ComponentItem.footprint(new ItemStack(type.item())).equals(twice), type.id() + ": a bare stack is not " + twice);
            h.assertTrue(ComponentItem.footprint(type.item().getDefaultInstance()).equals(twice), type.id() + ": the creative entry is not " + twice);
            h.assertTrue(type.footprints().contains(exact), type.id() + ": the exact real size " + exact + " is gone");
            int exactIndex = type.footprints().indexOf(exact);
            ItemStack picked = new ItemStack(type.item());
            picked.set(BwrDataComponents.COMPONENT_VARIANT.get(), exactIndex);
            h.assertTrue(ComponentItem.footprint(picked).equals(exact), type.id() + ": the exact real size cannot be picked");
        }
        h.succeed();
    }

    // ---- the size picker's payload

    private static IPayloadContext context(FakePlayer player) {
        return (IPayloadContext) java.lang.reflect.Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
                new Class<?>[] {IPayloadContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("player")) {
                        return player;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Sends the payload as a client would: encoded and decoded with the wire codec, then given to the real handler. */
    private static void send(GameTestHelper h, FakePlayer player, boolean offHand, int variant) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        PanelPayloads.SetSize.STREAM_CODEC.encode(buffer, new PanelPayloads.SetSize(offHand, variant));
        PanelNetwork.handleSetSize(PanelPayloads.SetSize.STREAM_CODEC.decode(buffer), context(player));
    }

    @GameTest(template = "empty")
    public static void thePickerPayloadSetsTheHeldStackToAValidSize(GameTestHelper h) {
        FakePlayer player = player(h, "pick-ok");
        player.getInventory().clearContent();
        ItemStack held = new ItemStack(BwrItems.DIAL_GAUGE.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        int last = ComponentTypes.DIAL_GAUGE.footprints().size() - 1;
        for (int index : new int[] {0, 3, last, ComponentTypes.DIAL_GAUGE.realDefaultIndex()}) {
            send(h, player, false, index);
            h.assertTrue(ComponentItem.footprint(player.getMainHandItem()).equals(ComponentTypes.DIAL_GAUGE.footprints().get(index)),
                    "index " + index + " was not applied: " + ComponentItem.footprint(player.getMainHandItem()));
        }
        // The off hand is addressed by its own flag.
        ItemStack off = new ItemStack(BwrItems.LAMP.get());
        player.setItemInHand(InteractionHand.OFF_HAND, off);
        send(h, player, true, 1);
        h.assertTrue(ComponentItem.footprint(player.getOffhandItem()).equals(Footprint.of(6, 6)), "the off hand lamp is 6x6 now");
        h.assertTrue(ComponentItem.footprint(player.getMainHandItem()).equals(ComponentTypes.DIAL_GAUGE.footprints().get(
                ComponentTypes.DIAL_GAUGE.realDefaultIndex())), "the main hand dial did not change");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void thePickerPayloadRefusesBadIndicesAndWrongItems(GameTestHelper h) {
        FakePlayer player = player(h, "pick-bad");
        player.getInventory().clearContent();
        ItemStack lamp = new ItemStack(BwrItems.LAMP.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, lamp);
        int size = ComponentTypes.LAMP.footprints().size();
        for (int index : new int[] {-1, size, size + 5, 63, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            send(h, player, false, index);
            h.assertTrue(lamp.get(BwrDataComponents.COMPONENT_VARIANT.get()) == null,
                    "the bad index " + index + " changed the stack: " + lamp.get(BwrDataComponents.COMPONENT_VARIANT.get()));
        }
        // Not a component item: a pickaxe, the wrench, an empty hand; a size is never written to them.
        for (ItemStack other : new ItemStack[] {new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(BwrItems.PANEL_WRENCH.get()),
                ItemStack.EMPTY}) {
            player.setItemInHand(InteractionHand.MAIN_HAND, other);
            send(h, player, false, 0);
            h.assertTrue(other.get(BwrDataComponents.COMPONENT_VARIANT.get()) == null, "a " + other + " was given a size");
        }
        // A limited item (a DX1000 may only be a DX1000) refuses the size of the other make.
        ItemStack dx1000 = new ItemStack(BwrItems.PAPERLESS_RECORDER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, dx1000);
        send(h, player, false, 1);
        h.assertTrue(ComponentItem.footprint(dx1000).equals(Footprint.of(16, 16)), "a DX1000 took the DX2000 size");
        // The wrong hand: the lamp is in the main hand, the off hand is empty.
        player.setItemInHand(InteractionHand.MAIN_HAND, lamp);
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        send(h, player, true, 1);
        h.assertTrue(lamp.get(BwrDataComponents.COMPONENT_VARIANT.get()) == null, "the lamp in the other hand changed");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void thePickerPayloadChangesOnlyTheHeldStack(GameTestHelper h) {
        FakePlayer player = player(h, "pick-only");
        player.getInventory().clearContent();
        ItemStack held = new ItemStack(BwrItems.CONTROL_SWITCH.get());
        ItemStack sameKind = new ItemStack(BwrItems.CONTROL_SWITCH.get());
        ItemStack neighbour = new ItemStack(BwrItems.DIAL_GAUGE.get());
        player.getInventory().setItem(3, sameKind);
        player.getInventory().setItem(4, neighbour);
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        send(h, player, false, 1);
        h.assertTrue(ComponentItem.footprint(player.getMainHandItem()).equals(Footprint.of(12, 16)), "the held switch is 12x16");
        h.assertTrue(sameKind.get(BwrDataComponents.COMPONENT_VARIANT.get()) == null
                && ComponentItem.footprint(sameKind).equals(ComponentTypes.CONTROL_SWITCH.footprints().get(
                ComponentTypes.CONTROL_SWITCH.realDefaultIndex())), "another switch in the inventory changed");
        h.assertTrue(neighbour.get(BwrDataComponents.COMPONENT_VARIANT.get()) == null, "a dial in the inventory changed");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void sizeOrderIsByAreaAndLimitedItemsOfferOnlyTheirOwn(GameTestHelper h) {
        for (ComponentType type : inScope()) {
            int[] order = type.sizeOrder();
            h.assertTrue(order.length == type.footprints().size(), type.id() + ": the order lists every size once");
            for (int i = 1; i < order.length; i++) {
                Footprint a = type.footprints().get(order[i - 1]);
                Footprint b = type.footprints().get(order[i]);
                h.assertTrue(a.w() * a.h() <= b.w() * b.h(), type.id() + ": " + a + " is listed before " + b);
            }
            h.assertTrue(((ComponentItem) type.item()).sizes().length == order.length, type.id() + ": its item offers every size");
        }
        ComponentItem dx1000 = (ComponentItem) BwrItems.PAPERLESS_RECORDER.get();
        h.assertTrue(dx1000.sizes().length == 1 && dx1000.sizes()[0] == 0, "a DX1000 offers its one size");
        h.succeed();
    }
}
