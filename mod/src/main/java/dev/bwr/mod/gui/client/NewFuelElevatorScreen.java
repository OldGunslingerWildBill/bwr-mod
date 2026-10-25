package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.NewFuelElevatorMenu;
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
 * The new fuel elevator's screen: the pool it is on, what the carriage is doing, the assemblies waiting, the free rack cells, the water
 * over an assembly at the transfer height against what the hoist needs, and the reason for the last refusal. The buttons are commands
 * a player presses: raise the coolest assembly to the hatch, try a blocked assembly again, take the assembly waiting at the hatch.
 */
public class NewFuelElevatorScreen extends BwrScreen<NewFuelElevatorMenu> {

    private BwrButton raise;
    private BwrButton retry;
    private BwrButton take;

    public NewFuelElevatorScreen(NewFuelElevatorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 380, 232);
    }

        private Status waterStatus() {
        if (!menu.linked) {
            return Status.NONE;
        }
        return menu.waterOverM + 1e-9 < menu.minCoverM ? Status.ALARM : menu.waterOverM < menu.minCoverM + 0.5 ? Status.CAUTION : Status.NORMAL;
    }

    private Status stateStatus() {
        return switch (menu.state) {
            case "lowering", "raising" -> Status.INFO;
            case "waiting", "unlinked" -> Status.CAUTION;
            default -> Status.NORMAL;
        };
    }

    @Override
    protected UiElement buildUi() {
        if (raise == null) {
            raise = BwrButton.secondary("RAISE COOLEST", "Take the assembly that has cooled longest out of its rack cell and bring it up to the hatch. Out of the water it is a radiation source.",
                    b -> menu.sendCommand(NewFuelElevatorMenu.CMD_RAISE));
            retry = BwrButton.secondary("TRY AGAIN", "Try again to lower an assembly that is waiting in the hatch (it was blocked by the racks or the water level).",
                    b -> menu.sendCommand(NewFuelElevatorMenu.CMD_RETRY));
            take = BwrButton.secondary("TAKE FROM HATCH", "Take the assembly that is waiting at the hatch into your inventory.",
                    b -> menu.sendCommand(NewFuelElevatorMenu.CMD_TAKE));
        }
        var pool = Layout.card("Pool",
                Layout.row("Free rack cells", () -> menu.freeCells + " / " + menu.cells, "")
                        .status(() -> menu.freeCells == 0 ? Status.ALARM : Status.NONE)
                        .tip("Cells of the racks with nothing in them. An assembly goes into the first free one."),
                Layout.row("Water level", () -> num(menu.levelM, 2), "m").tip("Metres of water above the pool floor."),
                Layout.row("Water over the handle", () -> num(Math.max(0.0, menu.waterOverM), 2), "m")
                        .status(this::waterStatus)
                        .tip(() -> List.of(Component.literal(String.format(Locale.ROOT,
                                "Metres of water over an assembly's handle at the height it runs over the racks. The hoist needs at least %.1f m, the refuelling bridge's own limit.",
                                menu.minCoverM)))),
                Layout.meter("Water for the hoist", () -> menu.minCoverM > 0 ? Math.min(1.0, Math.max(0.0, menu.waterOverM / menu.minCoverM)) : 0.0,
                        () -> menu.linked && menu.waterOverM + 1e-9 < menu.minCoverM ? Status.ALARM : Status.NONE,
                        () -> num(Math.max(0.0, menu.waterOverM), 1) + " of " + num(menu.minCoverM, 1) + " m")).compact();
        var carriage = Layout.card("Carriage",
                Layout.row("State", () -> menu.state, "").status(this::stateStatus),
                Layout.row("Leg", () -> menu.leg.isEmpty() ? "-" : menu.leg, ""),
                Layout.meter("Trip", () -> menu.progress, null, () -> menu.leg.isEmpty() ? "-" : num(menu.secondsLeft, 0) + " s left"),
                Layout.row("Waiting in the hatch", () -> String.valueOf(menu.queued), "")
                        .tip("Assemblies in the hatch, counting the one on its way down. A hopper or a pipe can fill it; up to four wait."),
                Layout.row("At the hatch", () -> menu.landing ? num(menu.landingKw, 1) + " kW" : "-", "")
                        .status(() -> menu.landing && menu.landingKw > 1.0 ? Status.ALARM : Status.NONE)
                        .tip("An assembly raised out of the pool waits here. Out of the water its decay heat is gamma with nothing in the way."),
                Layout.row("Coolest in the pool", () -> menu.coolestKw < 0 ? "-" : num(menu.coolestKw, 1), "kW")
                        .status(() -> menu.coolestKw > 50.0 ? Status.CAUTION : Status.NONE)
                        .tip("Decay heat of the assembly RAISE would bring up. Above about 50 kW it is a dangerous source in the air.")).compact();
        var controls = Layout.controls(BwrStyle.SP2).add(raise, 1).add(retry, 1).add(take, 1);
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no new fuel elevator here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                Layout.column(BwrStyle.SP2,
                        Layout.column(BwrStyle.SP1,
                                Layout.note("Not on a formed pool.").body().status(() -> Status.ALARM),
                                Layout.note(() -> menu.problem).body())
                                .showIf(() -> !menu.linked),
                        Layout.split(BwrStyle.SP2, pool, carriage),
                        Layout.note(() -> menu.lastRefusal.isEmpty() ? "" : "Last refusal: " + menu.lastRefusal).maxLines(3)
                                .status(() -> Status.CAUTION).showIf(() -> !menu.lastRefusal.isEmpty()),
                        controls).showIf(() -> menu.present));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        boolean idle = menu.present && menu.linked && menu.state.equals("idle");
        raise.active = idle && menu.coolestKw >= 0.0 && !menu.landing;
        retry.active = menu.present && menu.linked && menu.queued > 0 && !menu.state.equals("lowering");
        take.active = menu.present && menu.landing;
    }
}
