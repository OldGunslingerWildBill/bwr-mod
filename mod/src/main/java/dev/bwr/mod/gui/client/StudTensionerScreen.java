package dev.bwr.mod.gui.client;

import dev.bwr.core.refuelling.CraneMotion;
import dev.bwr.mod.gui.StudTensionerMenu;
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
 * The stud tensioner's screen: how many of the closure studs are at load, what the tool is doing and how long and how much power the
 * rest of the pass needs. Detension and Retension are the player's; the pack never starts on its own.
 */
public class StudTensionerScreen extends BwrScreen<StudTensionerMenu> {

    private BwrButton detension;
    private BwrButton retension;
    private BwrButton stop;

    public StudTensionerScreen(StudTensionerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 380, 200);
    }

    private static String clock(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String fe(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private int atLoad() {
        return menu.headOff ? 0 : menu.studTotal - menu.studsOff;
    }

    private String stateText() {
        return switch (menu.mode) {
            case 1 -> "Detensioning";
            case 2 -> "Retensioning";
            default -> "Idle";
        };
    }

    @Override
    protected UiElement buildUi() {
        if (detension == null) {
            detension = BwrButton.primary("DETENSION", "Start a pass round the flange: each stud is stretched, its nut backed off and the tool moves to the next. The head can be lifted when every nut is off.",
                    b -> menu.sendCommand(StudTensionerMenu.CMD_DETENSION));
            retension = BwrButton.primary("RETENSION", "Start a pass round the flange bringing each stud back to load. The head must be back on the flange; the vessel holds pressure when every stud is at load.",
                    b -> menu.sendCommand(StudTensionerMenu.CMD_RETENSION));
            stop = BwrButton.danger("STOP", "Stop the tool where it is. Studs already done stay done; the stud in hand starts again.",
                    b -> menu.sendCommand(StudTensionerMenu.CMD_STOP));
        }
        var studs = Layout.card("Closure studs",
                Layout.row("At load", () -> atLoad() + " / " + menu.studTotal).dense()
                        .status(() -> menu.headOff ? Status.INFO : atLoad() == menu.studTotal ? Status.NORMAL : Status.CAUTION)
                        .tip("The studs at their working load. The vessel holds pressure only when every one is."),
                Layout.meter("Studs at load", () -> menu.studTotal == 0 ? 0.0 : atLoad() / (double) menu.studTotal, null,
                        () -> atLoad() + " / " + menu.studTotal),
                Layout.row("Head", () -> menu.headOff ? "off" : "on").dense()
                        .tip("With the head off the studs stand bare in the flange. The crane lifts and sets the head."),
                Layout.row("Pressure", () -> String.format(Locale.ROOT, "%.0f", menu.psig), "psig").dense()
                        .status(() -> menu.psig > 25.0 && !menu.headOff ? Status.CAUTION : Status.NONE)
                        .tip("The studs are detensioned only at 25 psig or less.")).compact();
        var tool = Layout.card("Tensioner",
                Layout.row("State", this::stateText).dense().status(() -> menu.mode != 0 ? Status.INFO : Status.NONE),
                Layout.meter("Stud in hand", () -> menu.mode == 0 ? 0.0 : menu.studProgress, null,
                        () -> menu.mode == 0 ? "-" : String.format(Locale.ROOT, "%.0f %%", menu.studProgress * 100)),
                Layout.row("Left in the pass", () -> menu.mode == 0 ? "-" : clock(menu.secondsLeft), "").dense()
                        .tip("Time for the studs still to do at this pace."),
                Layout.row("Power", () -> fe(menu.energyFe), "FE").dense()
                        .status(() -> menu.mode != 0 && menu.energyFe < (menu.mode == 1 ? CraneMotion.DETENSION_FE_PER_STUD : CraneMotion.RETENSION_FE_PER_STUD) ? Status.ALARM : Status.NONE)
                        .tip(() -> List.of(Component.literal(String.format(Locale.ROOT,
                                "The pack's buffer holds up to %,d FE. A stud takes %,d FE going loose and %,d coming back to load.",
                                menu.capacityFe, CraneMotion.DETENSION_FE_PER_STUD, CraneMotion.RETENSION_FE_PER_STUD)))),
                Layout.row("Power still needed", () -> menu.mode == 0 ? "-" : fe(menu.feLeft), "FE").dense(),
                Layout.note(() -> menu.message).maxLines(2).tip(() -> List.of(Component.literal(menu.message)))).compact();
        var controls = Layout.card("", Layout.controls(BwrStyle.SP1).add(detension, 1).add(retension, 1).add(stop, 1)).compact();
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no stud tensioner here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                Layout.column(BwrStyle.SP2,
                        Layout.note("No formed reactor flange is within reach. Stand the tensioner on the refuel floor beside the well.").body()
                                .status(() -> Status.CAUTION).showIf(() -> !menu.reactorFound),
                        Layout.split(BwrStyle.SP2, studs, tool),
                        controls).showIf(() -> menu.present));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        boolean can = menu.present && menu.reactorFound && menu.mode == 0;
        detension.active = can && !menu.headOff && menu.studsOff < menu.studTotal;
        retension.active = can && !menu.headOff && menu.studsOff > 0;
        stop.active = menu.present && menu.mode != 0;
    }
}
