package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.SpentFuelPoolMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The spent fuel pool panel: water and fuel heat on the left, the cooling train under them, and the racks on the right,
 * a page of {@value SpentFuelPoolMenu#PAGE_RACKS} lines at a time (assemblies, decay heat, hottest fuel) with buttons beside
 * the pump switch to page through up to 2,048 of them. Everything is a measurement; the only control is the cooling pump's switch. The
 * colours are typography: a pool above 60 C or with fuel under 1 m of water is drawn amber, a boiling one or one with
 * uncovered fuel red, and nothing reads them.
 */
public class SpentFuelPoolScreen extends BwrScreen<SpentFuelPoolMenu> {

    private BwrButton cooling;
    private BwrButton firstPage;
    private BwrButton prevPage;
    private BwrButton nextPage;
    private BwrButton lastPage;

    public SpentFuelPoolScreen(SpentFuelPoolMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 440, 236);
    }

    private static String time(double seconds) {
        return SpentFuelPoolBlockEntity.duration(seconds);
    }

    private Status waterStatus() {
        if (menu.temperatureC >= 99.9) {
            return Status.ALARM;
        }
        return menu.temperatureC >= 60.0 ? Status.CAUTION : Status.NONE;
    }

    private Status coverStatus() {
        if (menu.assemblies == 0) {
            return Status.NONE;
        }
        if (menu.waterAboveFuelM < 0.0) {
            return Status.ALARM;
        }
        return menu.waterAboveFuelM < 1.0 ? Status.CAUTION : Status.NONE;
    }

    private int rackTotal() {
        return Math.max(menu.rackCount, menu.racks.size());
    }

    private String pageText() {
        int n = rackTotal();
        if (n == 0) {
            return "";
        }
        int shown = Math.min(menu.racks.size(), SpentFuelPoolMenu.PAGE_RACKS);
        return String.format(Locale.ROOT, "%d-%d of %d", menu.rackFirst + 1, menu.rackFirst + Math.max(1, shown), n);
    }

    private void page(int first) {
        int n = rackTotal();
        int last = n == 0 ? 0 : (n - 1) / SpentFuelPoolMenu.PAGE_RACKS * SpentFuelPoolMenu.PAGE_RACKS;
        menu.sendCommand(SpentFuelPoolMenu.CMD_RACK_PAGE, Math.max(0, Math.min(last, first)));
    }

    private UiElement rackRow(int i) {
        return Layout.row(
                        () -> i < menu.racks.size() ? "Rack " + (menu.rackFirst + i + 1) + "  " + menu.racks.get(i).assemblies() + "/4" : "",
                        () -> i < menu.racks.size()
                                ? (menu.racks.get(i).assemblies() == 0 ? "empty"
                                : num(menu.racks.get(i).decayW() / 1000.0, 1) + " kW  " + big(menu.racks.get(i).hottestC()) + " C")
                                : "",
                        () -> "")
                .status(() -> {
                    if (i >= menu.racks.size()) {
                        return Status.NONE;
                    }
                    var r = menu.racks.get(i);
                    return r.burning() > 0 ? Status.ALARM : r.uncovered() > 0 || r.damaged() > 0 ? Status.CAUTION : Status.NONE;
                })
                .tip(() -> i >= menu.racks.size() ? List.of()
                        : List.of(Component.literal(String.format(Locale.ROOT,
                        "Rack at %d, %d, %d: top %.0f m above the floor. %d of 4 cells hold fuel; %d uncovered, %d with failed cladding, %d burning.",
                        menu.racks.get(i).pos().getX(), menu.racks.get(i).pos().getY(), menu.racks.get(i).pos().getZ(),
                        (double) menu.racks.get(i).topM(), menu.racks.get(i).assemblies(), menu.racks.get(i).uncovered(),
                        menu.racks.get(i).damaged(), menu.racks.get(i).burning()))))
                .showIf(() -> i < menu.racks.size());
    }

    @Override
    protected UiElement buildUi() {
        if (cooling == null) {
            cooling = BwrButton.secondary("Switch pump on", "Start the cooling pump. It needs power on the controller and cold water at the blue port.",
                    b -> menu.sendCommand(SpentFuelPoolMenu.CMD_COOLING, menu.coolingEnabled ? 0 : 1));
        }
        var pool = Layout.card("Pool",
                Layout.row("Water level", () -> num(menu.levelM, 2), "m")
                        .tip("Metres of water above the pool floor."),
                Layout.row("Above the fuel", () -> menu.assemblies == 0 ? "-" : num(menu.waterAboveFuelM, 2), "m")
                        .status(this::coverStatus)
                        .tip("Metres of water over the top of the highest rack that holds fuel. Below zero the fuel is uncovered."),
                Layout.row("Temperature", () -> num(menu.temperatureC, 1), "C").status(this::waterStatus),
                Layout.row("Water lost", () -> big((menu.evaporationKgPerS + menu.boilKgPerS) * 3600.0), "kg/h")
                        .status(() -> menu.boilKgPerS > 0 ? Status.ALARM : Status.NONE)
                        .tip(() -> List.of(Component.literal(String.format(Locale.ROOT, "Evaporating %.0f kg/h, boiling off %.0f kg/h. The pool never refills itself: supply makeup water.",
                                menu.evaporationKgPerS * 3600.0, menu.boilKgPerS * 3600.0)))),
                Layout.meter("Pool fill", () -> menu.capacityKg > 0 ? menu.massKg / menu.capacityKg : 0.0, null,
                        () -> big(menu.massKg) + " kg"),
                Layout.row("Assemblies", () -> menu.assemblies + " / " + menu.cells, "").tip("Assemblies stored, and cells in the racks."),
                Layout.row("Decay heat", () -> num(menu.decayW / 1000.0, 1), "kW")
                        .tip(() -> List.of(Component.literal(String.format(Locale.ROOT, "%.1f kW heats the water, %.1f kW is in fuel standing in air.",
                                menu.coveredDecayW / 1000.0, menu.uncoveredDecayW / 1000.0)))),
                Layout.row("Time to boil", () -> time(menu.secondsToBoil), "")
                        .tip("At the present heat, cooling and losses. Decay heat falls, so this is a worst case."),
                Layout.row("Time to uncover", () -> time(menu.secondsToUncover), "")
                        .status(() -> menu.assemblies > 0 && menu.secondsToUncover < 3600.0 ? Status.CAUTION : Status.NONE)
                        .tip("Until boil-off lowers the water to the top of the highest fuel, at the present heat and cooling.")).compact();
        var train = Layout.card("Cooling train",
                Layout.row("Pump", () -> menu.coolingRunning ? "running" : menu.coolingEnabled ? "on, no power/water" : "off", "")
                        .status(() -> menu.coolingEnabled && !menu.coolingRunning ? Status.CAUTION : Status.NONE),
                Layout.row("Heat removed", () -> num(menu.coolingW / 1.0e6, 2), "MW")
                        .tip(() -> List.of(Component.literal(String.format(Locale.ROOT, "Cold water held %,.0f kg (%.1f C), warm water held %,.0f kg (%.1f C).",
                                menu.coldKg, menu.coldC, menu.hotKg, menu.hotC)))),
                Layout.meter("Pump power", () -> menu.energyFE / (double) 80_000, () -> menu.energyFE > 0 ? Status.NONE : Status.CAUTION,
                        () -> big(menu.energyFE) + " FE")).compact();
        if (firstPage == null) {
            firstPage = BwrButton.secondary("<<", "The first page of racks.", b -> page(0));
            prevPage = BwrButton.secondary("<", "The previous page of racks.", b -> page(menu.rackFirst - SpentFuelPoolMenu.PAGE_RACKS));
            nextPage = BwrButton.secondary(">", "The next page of racks.", b -> page(menu.rackFirst + SpentFuelPoolMenu.PAGE_RACKS));
            lastPage = BwrButton.secondary(">>", "The last page of racks.", b -> page(Integer.MAX_VALUE));
        }
        List<UiElement> rows = new ArrayList<>();
        for (int i = 0; i < SpentFuelPoolMenu.PAGE_RACKS; i++) {
            rows.add(rackRow(i));
        }
        var racks = Layout.card(() -> pageText().isEmpty() ? "Racks" : "Racks " + pageText(), Layout.scrollArea(40, Layout.column(0, rows.toArray(new UiElement[0])))).compact();
        // the pager sits beside the pump switch: a row of its own would not fit the smallest window
        var controls = Layout.controls(BwrStyle.SP1).add(cooling, 1)
                .fixed(firstPage, 18).fixed(prevPage, 14).fixed(nextPage, 14).fixed(lastPage, 18);
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no pool controller here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                Layout.column(BwrStyle.SP1,
                        Layout.note("Spent fuel pool not formed.").body().status(() -> Status.ALARM),
                        Layout.note(() -> menu.problem).body(),
                        Layout.note("Build a lined floor and four walls up to the rim, one controller in the shell, racks inside."))
                        .showIf(() -> menu.present && !menu.formed),
                Layout.column(BwrStyle.SP2,
                        Layout.statusLine(() -> menu.burningAssemblies > 0 ? Status.ALARM : menu.damagedAssemblies > 0 ? Status.CAUTION : Status.NORMAL,
                                () -> menu.burningAssemblies > 0 ? menu.burningAssemblies + (menu.burningAssemblies == 1 ? " assembly burning" : " assemblies burning")
                                        : menu.damagedAssemblies > 0 ? menu.damagedAssemblies + (menu.damagedAssemblies == 1 ? " assembly with failed cladding" : " assemblies with failed cladding")
                                        : "Fuel intact",
                                () -> menu.footprint + " m2 x " + menu.depth + " m"),
                        Layout.split(BwrStyle.SP2, pool, Layout.column(BwrStyle.SP1, train, controls, racks)).stackBelow(380))
                        .showIf(() -> menu.present && menu.formed));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        cooling.active = menu.present && menu.formed;
        boolean paging = menu.present && menu.formed;
        firstPage.active = paging && menu.rackFirst > 0;
        prevPage.active = paging && menu.rackFirst > 0;
        nextPage.active = paging && menu.rackFirst + SpentFuelPoolMenu.PAGE_RACKS < rackTotal();
        lastPage.active = nextPage.active;
        cooling.setMessage(Component.literal(menu.coolingEnabled ? "Switch pump off" : "Switch pump on"));
    }
}
