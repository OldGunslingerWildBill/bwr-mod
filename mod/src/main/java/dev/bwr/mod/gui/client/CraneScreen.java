package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.CraneMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.Locale;

/**
 * The reactor building crane's pendant: the crane's state and the load on the hook on the left, where the head, its studs and the
 * dryer and the separators are on the right, and the lift buttons under them. A lift is a move a player queues; the colours are
 * typography and nothing reads them.
 */
public class CraneScreen extends BwrScreen<CraneMenu> {

    private BwrButton head;
    private BwrButton dryer;
    private BwrButton separators;
    private BwrButton abort;
    private BwrButton resume;
    private BwrButton plug;
    private BwrButton cover;
    private BwrButton blades;
    private BwrButton gate;

    public CraneScreen(CraneMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 420, 214);
    }

    private static String phaseWord(String phase) {
        return switch (phase) {
            case "IDLE" -> "Idle";
            case "TRAVEL" -> "Running to the load";
            case "LOWER_EMPTY" -> "Lowering the hook";
            case "LATCH" -> "Hooking on";
            case "RAISE_LOADED" -> "Lifting the load";
            case "TRANSIT" -> "Carrying the load";
            case "LOWER_LOADED" -> "Setting the load down";
            case "RELEASE" -> "Unhooking";
            case "RAISE_EMPTY" -> "Raising the hook";
            case "HALTED" -> "HALTED";
            default -> phase;
        };
    }

    /** COVER ON is next: the nearest LP is open (or a hood hangs). */
    private boolean coverOff() {
        return menu.turbineLoad == 1 || (menu.turbineLoad == 0 && menu.nearestCoverOff);
    }

    /** BLADES OUT is next: a section is in the nearest open LP and none hangs, or a damaged one hangs; BLADES IN when a flow is empty. */
    private boolean bladesOut() {
        return menu.turbineLoad == 0 && menu.nearestSectionsIn == 3;
    }

    private boolean headOn() {
        return !menu.headOff;
    }

    private String headText() {
        if (menu.headOff) {
            return "off, " + menu.headWhere;
        }
        int tight = menu.studTotal - menu.studsOff;
        return tight == menu.studTotal ? "on, studs at load" : menu.studsOff >= menu.studTotal ? "on, studs detensioned" : "on, " + tight + " studs at load";
    }

    private Status headStatus() {
        if (menu.headOff) {
            return Status.INFO;
        }
        return menu.studsOff >= menu.studTotal ? Status.CAUTION : Status.NONE;
    }

    @Override
    protected UiElement buildUi() {
        if (head == null) {
            head = BwrButton.primary("LIFT HEAD", "Queue a lift: the crane carries the vessel head, slung on its strongback, to the nearest free head stand. Every closure stud must be detensioned.",
                    b -> menu.sendCommand(CraneMenu.CMD_HEAD, menu.headOff ? 0 : 1));
            dryer = BwrButton.secondary("DRYER OUT", "Lift the steam dryer out of the open, flooded vessel to the nearest free equipment pad (it comes out first and goes back last).",
                    b -> menu.sendCommand(CraneMenu.CMD_INTERNAL, 0, (menu.internalsOut & 1) == 0 ? 1 : 0));
            separators = BwrButton.secondary("SEPS OUT", "Lift the shroud head and steam separator assembly out to the nearest free equipment pad (after the dryer).",
                    b -> menu.sendCommand(CraneMenu.CMD_INTERNAL, 1, (menu.internalsOut & 2) == 0 ? 1 : 0));
            abort = BwrButton.danger("ABORT", "Stop the crane where it is and forget the queue. A load on the hook stays on it.",
                    b -> menu.sendCommand(CraneMenu.CMD_ABORT));
            resume = BwrButton.secondary("RESUME", "After a halt, try the step again.", b -> menu.sendCommand(CraneMenu.CMD_RESUME));
            plug = BwrButton.secondary("PLUG OFF", "Lift the shield plug section nearest the well that has its four crane anchors off the plug, and set it down on the nearest clear spot of the refuel floor.",
                    b -> menu.sendCommand(CraneMenu.CMD_PLUG, menu.plugsLaid > 0 && !menu.plugHeld ? 0 : 1));
            cover = BwrButton.secondary("COVER OFF", "Lift the nearest LP turbine's hood (its upper casing half) off onto a free Casing Laydown Stand. The shaft must be stopped, the turning gear off, the casing isolated from steam and cool.",
                    b -> menu.sendCommand(CraneMenu.CMD_COVER, coverOff() ? 0 : 1));
            blades = BwrButton.secondary("BLADES OUT", "Lift the worst blade section out of the open LP casing onto a free saddle of an LP Blade Storage Stand.",
                    b -> menu.sendCommand(CraneMenu.CMD_BLADES, bladesOut() ? 1 : 0));
            gate = BwrButton.secondary("GATE OUT", "Lift the canal gate nearest the well out of its canal, by the lifting eyes on its top, onto a Gate Laydown Stand (or clear floor).",
                    b -> menu.sendCommand(CraneMenu.CMD_GATE, menu.gatesLaid > 0 && !menu.gateHeld ? 0 : 1));
        }
        var state = Layout.card("Crane",
                Layout.row("State", () -> phaseWord(menu.phase) + (menu.queued > 0 ? " (+" + menu.queued + ")" : "")).dense()
                        .status(() -> menu.halted ? Status.ALARM : menu.busy ? Status.INFO : Status.NONE)
                        .tip("What the crane is doing, and how many more lifts are queued behind it."),
                Layout.meter("Phase", () -> menu.busy ? menu.progress : 0.0, () -> menu.halted ? Status.ALARM : Status.NONE,
                        () -> menu.busy ? menu.secondsLeft + " s left" : "-"),
                Layout.row("Hook", () -> menu.carried.isEmpty() ? "empty" : menu.carried).dense()
                        .tip("What hangs from the hook block."),
                Layout.row("Hook height", () -> String.format(Locale.ROOT, "%+.1f", menu.hookHeightM), "m").dense()
                        .tip("The hook's height over the floor of the runway rails; its rest position is level with them."),
                Layout.row("Runway", () -> menu.runwayBlocks + " x " + menu.spanBlocks, "m").dense()
                        .tip(() -> List.of(Component.literal("The rails are " + menu.runwayBlocks + " m long along the runway and " + menu.spanBlocks
                                + " m apart, at y " + menu.railY + ".")))).compact();
        var plant = Layout.card("Reactor",
                Layout.row("Head", this::headText).dense().status(this::headStatus)
                        .tip("Where the vessel head is, and how many of its closure studs are at load."),
                Layout.row("Studs at load", () -> menu.headOff ? "0 / " + menu.studTotal : (menu.studTotal - menu.studsOff) + " / " + menu.studTotal).dense()
                        .tip("A stud tensioner backs the nuts off before the head lifts, and brings the studs back to load after the head is set.")
                        .showIf(() -> menu.reactorFound || !(menu.turbineFound || menu.turbineLoad != 0)),
                Layout.row("Nearest LP", () -> menu.turbineLine.isEmpty() ? "none under the crane" : menu.turbineLine).dense()
                        .status(() -> menu.nearestCoverOff ? Status.CAUTION : Status.NONE)
                        .tip("The turbine hall: the LP turbine section nearest the hook, its cover (the hood) and the wear of its two blade sections, A and B (out: lifted out).")
                        .showIf(() -> menu.turbineFound || menu.turbineLoad != 0),
                Layout.row("Next plug", () -> menu.plugNext.isEmpty() ? "none in reach" : menu.plugNext).dense()
                        .status(() -> menu.plugNextBad ? Status.CAUTION : Status.NONE)
                        .tip("The shield plug section PLUG OFF would take next (or the one on the hook): its blocks, its mass against the crane's rating, and its crane anchors. It needs exactly four anchors and must be under the rating; mark a seam to split a section that is too heavy."),
                Layout.row("Next gate", () -> menu.gateNext.isEmpty() ? (menu.gatesLaid > 0 ? menu.gatesLaid + " laid down" : "none in reach") : menu.gateNext).dense()
                        .status(() -> menu.gateNextBad ? Status.CAUTION : Status.NONE)
                        .tip("The canal gate GATE OUT would take next (or the one on the hook): its blocks and mass. It is pulled only with its lifting eyes clear and the water on both sides within 0.3 m."),
                Layout.row("Dryer", () -> (menu.internalsOut & 1) == 0 ? "installed" : "out").dense()
                        .status(() -> (menu.internalsOut & 1) == 0 ? Status.NONE : Status.CAUTION),
                Layout.row("Separators", () -> (menu.internalsOut & 2) == 0 ? "installed" : "out").dense()
                        .status(() -> (menu.internalsOut & 2) == 0 ? Status.NONE : Status.CAUTION),
                Layout.note(() -> menu.message).maxLines(2).status(() -> menu.halted ? Status.ALARM : Status.NONE)
                        .tip(() -> List.of(Component.literal(menu.message.isEmpty() ? menu.move : menu.message)))).compact();
        var controls = Layout.card("",
                Layout.controls(BwrStyle.SP1).add(head, 1).add(dryer, 1).add(separators, 1).add(plug, 1),
                Layout.gap(1),
                Layout.controls(BwrStyle.SP1).add(cover, 1).add(blades, 1).add(gate, 1),
                Layout.gap(1),
                Layout.controls(BwrStyle.SP1).add(abort, 1).add(resume, 1)).compact();
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no crane console here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                Layout.column(BwrStyle.SP1,
                        Layout.note("The crane cannot see its runway.").body().status(() -> Status.ALARM),
                        Layout.note(() -> menu.problem).body())
                        .showIf(() -> menu.present && !menu.ready),
                Layout.column(BwrStyle.SP2,
                        Layout.note("No formed reactor lies between the crane's rails.").body().status(() -> Status.CAUTION).showIf(() -> !menu.reactorFound && !menu.turbineFound),
                        Layout.split(BwrStyle.SP2, state, plant),
                        controls)
                        .showIf(() -> menu.present && menu.ready));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        boolean go = menu.present && menu.ready && menu.reactorFound && !menu.busy && !menu.halted;
        head.setMessage(Component.literal(menu.headOff ? "SET HEAD" : "LIFT HEAD"));
        head.setTooltipText(menu.headOff
                ? "Queue a lift: the crane takes the head from its stand and sets it on the vessel flange. Both the dryer and the separators must be back in. The studs are then tensioned with the stud tensioner."
                : "Queue a lift: the crane carries the vessel head, slung on its strongback, to the nearest free head stand. Every closure stud must be detensioned first.");
        head.active = go;
        boolean dryerOut = (menu.internalsOut & 1) != 0, sepsOut = (menu.internalsOut & 2) != 0;
        dryer.setMessage(Component.literal(dryerOut ? "DRYER IN" : "DRYER OUT"));
        separators.setMessage(Component.literal(sepsOut ? "SEPS IN" : "SEPS OUT"));
        dryer.active = go && menu.headOff;
        separators.active = go && menu.headOff;
        boolean laid = menu.plugsLaid > 0 && !menu.plugHeld;
        plug.setMessage(Component.literal(laid ? "PLUG ON" : "PLUG OFF"));
        plug.setTooltipText(laid
                ? "Queue a lift: the crane sets the shield plug section it last laid down back exactly where it came from. The head must be on the vessel."
                : "Queue a lift: the crane takes the shield plug section nearest the well that has its four crane anchors off the plug, and sets it down on the nearest clear spot of the refuel floor.");
        plug.active = go || (menu.present && menu.ready && menu.reactorFound && !menu.busy && menu.plugHeld);
        boolean idle = menu.present && menu.ready && !menu.busy && !menu.halted;
        cover.setMessage(Component.literal(coverOff() ? "COVER ON" : "COVER OFF"));
        cover.setTooltipText(coverOff()
                ? "Queue a lift: the crane takes a hood from a Casing Laydown Stand and sets it back on the open LP turbine. Both blade sections must be in."
                : "Queue a lift: the crane lifts the nearest LP turbine's hood off onto a free Casing Laydown Stand. The shaft must be stopped, the turning gear off, the casing isolated from steam and at 100 C or less.");
        cover.active = idle && (menu.turbineFound || menu.turbineLoad == 1) && menu.turbineLoad != 2;
        blades.setMessage(Component.literal(bladesOut() ? "BLADES OUT" : "BLADES IN"));
        blades.setTooltipText(bladesOut()
                ? "Queue a lift: the crane lifts the worst blade section out of the open LP casing onto a free saddle of an LP Blade Storage Stand."
                : "Queue a lift: the crane takes a spare LP blade section from a blade stand and sets it into the empty flow of the open casing.");
        blades.active = idle && (menu.nearestCoverOff || menu.turbineLoad == 2) && menu.turbineLoad != 1;
        boolean gatesLaid = menu.gatesLaid > 0 && !menu.gateHeld;
        gate.setMessage(Component.literal(gatesLaid ? "GATE IN" : "GATE OUT"));
        gate.setTooltipText(gatesLaid
                ? "Queue a lift: the crane sets the canal gate it last laid down back exactly in its canal column, closing the canal."
                : "Queue a lift: the crane lifts the canal gate nearest the well out of its canal by the eyes on its top and lays it on a Gate Laydown Stand (or clear floor). The water on both sides must be within 0.3 m.");
        gate.active = go || (menu.present && menu.ready && menu.reactorFound && !menu.busy && menu.gateHeld);
        abort.active = menu.present && menu.ready && (menu.busy || menu.halted || menu.queued > 0 || !menu.carried.isEmpty());
        resume.active = menu.present && menu.ready && menu.halted;
    }
}
