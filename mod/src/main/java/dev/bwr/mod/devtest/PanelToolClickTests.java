package dev.bwr.mod.devtest;

import io.netty.buffer.Unpooled;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelNetwork;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A click on a panel control with the Panel Wrench or the Networking Wrench (or the Panel Linker) in either hand is the
 * tool's click, never the control's: no press, hold, turn or toggle, whatever the client sends. The tool's own action is
 * unchanged, and a bare hand still operates. A control held down when the player takes up a tool is still let go.
 *
 * <p>The operate request goes through the real C2S handler (encoded and decoded with the wire codec), through
 * {@link PanelOps#toolRefusesOperate} and through the vanilla bare-hand path {@link PanelOps#forwardUse}. World fixture at
 * 184_400 / 181_000 (see {@link HardwareStage}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelToolClickTests {
    private PanelToolClickTests() {
    }

    static final int ORIGIN_X = 184_400;
    static final int ORIGIN_Z = 181_000;

    /** A tool, in the main or the off hand. */
    private record Grip(String name, Supplier<Item> item, InteractionHand hand) {
    }

    private static final List<Grip> GRIPS = List.of(
            new Grip("panel wrench, main hand", BwrItems.PANEL_WRENCH::get, InteractionHand.MAIN_HAND),
            new Grip("panel wrench, off hand", BwrItems.PANEL_WRENCH::get, InteractionHand.OFF_HAND),
            new Grip("networking wrench, main hand", BwrItems.NETWORKING_WRENCH::get, InteractionHand.MAIN_HAND),
            new Grip("networking wrench, off hand", BwrItems.NETWORKING_WRENCH::get, InteractionHand.OFF_HAND),
            new Grip("panel linker, main hand", BwrItems.PANEL_LINKER::get, InteractionHand.MAIN_HAND));

    private static void grip(HardwareStage s, Grip grip) {
        s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        s.player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        s.player.setItemInHand(grip.hand(), new ItemStack(grip.item().get()));
    }

    private static void bareHands(HardwareStage s) {
        s.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        s.player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
    }

    /** The smallest footprint a type offers, so four controls fit on one block. */
    private static Footprint smallest(ComponentType type) {
        Footprint best = type.footprints().get(0);
        for (Footprint f : type.footprints()) {
            if (f.w() * f.h() < best.w() * best.h()) {
                best = f;
            }
        }
        return best;
    }

    /** A pushbutton, a toggle switch, a control switch (the lever) and a rotary selector, side by side. */
    private static List<PanelComponent> controls(HardwareStage s) {
        List<PanelComponent> list = new ArrayList<>();
        ComponentType[] types = {ComponentTypes.PUSHBUTTON, ComponentTypes.TOGGLE_SWITCH, ComponentTypes.CONTROL_SWITCH,
                ComponentTypes.ROTARY_SELECTOR};
        int[][] at = {{0, 0}, {8, 0}, {0, 8}, {8, 8}};
        for (int i = 0; i < types.length; i++) {
            list.add(s.place(types[i], at[i][0], at[i][1], smallest(types[i])));
        }
        return list;
    }

    /** Everything the control remembers: its outputs and its saved data. */
    private static String state(PanelComponent c) {
        StringBuilder out = new StringBuilder(c.type().id().getPath()).append(':');
        for (int i = 0; i < c.outputCount(); i++) {
            out.append(c.output(i)).append(',');
        }
        return out.append(c.data().toTag()).toString();
    }

    private static IPayloadContext context(ServerPlayer player) {
        return (IPayloadContext) java.lang.reflect.Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
                new Class<?>[] {IPayloadContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("player")) {
                        return player;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static int stamp;

    /** The operate packet a client would send, encoded and decoded with the wire codec, given to the real handler. */
    private static void send(HardwareStage s, PanelComponent c, OperateAction action, double value) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), s.level.registryAccess());
        PanelPayloads.Operate.STREAM_CODEC.encode(buffer,
                new PanelPayloads.Operate(s.origin, c.id(), action.ordinal(), value, ++stamp));
        PanelPayloads.Operate decoded = PanelPayloads.Operate.STREAM_CODEC.decode(buffer);
        try {
            var handler = PanelNetwork.class.getDeclaredMethod("handleOperate", PanelPayloads.Operate.class, IPayloadContext.class);
            handler.setAccessible(true);
            handler.invoke(null, decoded, context(s.player));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not call the operate handler", e);
        }
    }

    private static final OperateAction[] ACTIONS = {OperateAction.PRESS, OperateAction.TURN, OperateAction.SET, OperateAction.PRESS};
    private static final double[] VALUES = {1.0, 1.0, 1.0, -1.0};

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aToolInEitherHandNeverOperatesAControl(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "tool-click-refuse", s -> {
            List<PanelComponent> controls = controls(s);
            for (Grip grip : GRIPS) {
                grip(s, grip);
                for (PanelComponent c : controls) {
                    String before = state(c);
                    String what = grip.name() + " / " + c.type().id().getPath();
                    for (int i = 0; i < ACTIONS.length; i++) {
                        send(s, c, ACTIONS[i], VALUES[i]);
                        helper.assertTrue(state(c).equals(before), what + ": the " + ACTIONS[i] + " packet got through to the control: "
                                + state(c));
                    }
                    // The vanilla bare-hand path (the other hand of a player with a tool in one) does not operate it either.
                    InteractionResult used = PanelOps.forwardUse(s.player, s.be(), c.x() + c.w() / 2, c.y() + c.h() / 2,
                            c.x() + c.w() / 2.0, c.y() + c.h() / 2.0);
                    helper.assertTrue(used == InteractionResult.PASS && state(c).equals(before),
                            what + ": the bare-hand use operated it: " + used + " " + state(c));
                }
            }
            bareHands(s);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theToolsOwnActionStillWorks(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "tool-click-own", s -> {
            List<PanelComponent> controls = controls(s);
            PanelComponent button = controls.get(0);
            PanelComponent toggle = controls.get(1);
            PanelComponent lever = controls.get(2);
            PanelComponent selector = controls.get(3);
            // The panel wrench edits and removes, from either hand.
            for (InteractionHand hand : InteractionHand.values()) {
                grip(s, new Grip("wrench", BwrItems.PANEL_WRENCH::get, hand));
                CompoundTag edit = new CompoundTag();
                edit.putString("Caption", hand == InteractionHand.MAIN_HAND ? "MAIN" : "OFF");
                helper.assertTrue(PanelOps.configure(s.player, s.origin, button.id(), edit)
                        && button.caption().equals(edit.getString("Caption")), "the wrench in " + hand + " does not configure");
            }
            helper.assertTrue(PanelOps.remove(s.player, s.origin, selector.id()) && s.be().component(selector.id()) == null,
                    "the wrench removes a control");
            // The networking wrench and the linker open the binding screen of a control, from either hand.
            for (Grip grip : GRIPS.subList(2, 5)) {
                grip(s, grip);
                helper.assertTrue(WiringOps.screenFor(s.player, s.origin, lever.id()) != null,
                        grip.name() + ": no binding screen for the control");
            }
            // And with the same hands empty, the controls work again (the refusal is the tool's, not a broken control).
            bareHands(s);
            helper.assertTrue(PanelOps.operate(s.player, s.origin, button.id(), OperateAction.PRESS, 1.0) && button.output(0) == 1.0,
                    "bare hand: the pushbutton presses");
            helper.assertTrue(PanelOps.operate(s.player, s.origin, button.id(), OperateAction.RELEASE, 0.0) && button.output(0) == 0.0,
                    "bare hand: the pushbutton lets go");
            String before = state(toggle);
            helper.assertTrue(PanelOps.operate(s.player, s.origin, toggle.id(), OperateAction.PRESS, 1.0) && !state(toggle).equals(before),
                    "bare hand: the toggle switch toggles");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aBareHandStillOperatesEveryControl(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "tool-click-bare", s -> {
            List<PanelComponent> controls = controls(s);
            bareHands(s);
            PanelComponent button = controls.get(0);
            PanelComponent toggle = controls.get(1);
            PanelComponent lever = controls.get(2);
            PanelComponent selector = controls.get(3);
            // Through the real packet handler, as a client sends it.
            send(s, button, OperateAction.PRESS, 1.0);
            helper.assertTrue(button.output(0) == 1.0, "the pushbutton press packet works: " + state(button));
            send(s, button, OperateAction.RELEASE, 0.0);
            helper.assertTrue(button.output(0) == 0.0, "the pushbutton release packet works: " + state(button));
            String toggled = state(toggle);
            send(s, toggle, OperateAction.PRESS, 1.0);
            helper.assertTrue(!state(toggle).equals(toggled), "the toggle packet works: " + state(toggle));
            String leverBefore = state(lever);
            send(s, lever, OperateAction.SET, 2.0);
            helper.assertTrue(!state(lever).equals(leverBefore) || lever.output(0) != 0.0 || lever.output(1) != 0.0 || lever.output(2) != 0.0,
                    "the lever packet works: " + state(lever));
            send(s, selector, OperateAction.TURN, 1.0);
            helper.assertTrue(selector.output(0) == 1.0, "the selector turn packet works: " + state(selector));
            // The vanilla bare-hand path still presses.
            helper.assertTrue(PanelOps.forwardUse(s.player, s.be(), button.x() + button.w() / 2, button.y() + button.h() / 2,
                    button.x() + button.w() / 2.0, button.y() + button.h() / 2.0) == InteractionResult.CONSUME && button.output(0) == 1.0, "the bare-hand use presses the button");
            PanelOps.operate(s.player, s.origin, button.id(), OperateAction.RELEASE, 0.0);
            // A non-tool item in the hand does not matter.
            s.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.STICK));
            helper.assertTrue(PanelOps.operate(s.player, s.origin, selector.id(), OperateAction.TURN, 1.0) && selector.output(0) == 2.0,
                    "a stick in the hand still operates");
            bareHands(s);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void takingUpAToolMidHoldStillLetsGoOfTheHeldControl(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "tool-click-hold", s -> {
            List<PanelComponent> controls = controls(s);
            PanelComponent button = controls.get(0);
            PanelComponent selector = controls.get(3);
            for (Grip grip : GRIPS) {
                bareHands(s);
                helper.assertTrue(PanelOps.operate(s.player, s.origin, button.id(), OperateAction.PRESS, 1.0, ++stamp) && button.output(0) == 1.0,
                        grip.name() + ": the button is held with a bare hand");
                grip(s, grip);
                // The client keeps repeating PRESS while the key is down; none of it reaches the held control now...
                send(s, button, OperateAction.PRESS, 1.0);
                // ... but the RELEASE the client sends when the tool is taken up does: nothing stays held.
                send(s, button, OperateAction.RELEASE, 0.0);
                helper.assertTrue(button.output(0) == 0.0, grip.name() + ": the button is still held after the release: " + state(button));
                // A repeat of the click still in flight after the RELEASE must not take hold of it again.
                send(s, button, OperateAction.PRESS, 1.0);
                helper.assertTrue(button.output(0) == 0.0, grip.name() + ": a late PRESS held the button again: " + state(button));
                // A RELEASE is not a way to operate a control that is not held.
                String before = state(selector);
                send(s, selector, OperateAction.RELEASE, 0.0);
                helper.assertTrue(state(selector).equals(before), grip.name() + ": a RELEASE operated the selector");
            }
            bareHands(s);
            // The fail-safe still holds the other way round: with the tool away the same button presses again.
            helper.assertTrue(PanelOps.operate(s.player, s.origin, button.id(), OperateAction.PRESS, 1.0, ++stamp) && button.output(0) == 1.0,
                    "the button presses again with a bare hand");
            PanelOps.operate(s.player, s.origin, button.id(), OperateAction.RELEASE, 0.0, ++stamp);
        });
    }
}
