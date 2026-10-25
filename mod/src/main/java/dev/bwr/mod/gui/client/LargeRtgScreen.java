package dev.bwr.mod.gui.client;

import dev.bwr.core.isotope.LargeRtgStore;
import dev.bwr.mod.gui.LargeRtgMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.Locale;

/**
 * The Large RTG: stored mass by isotope against the 1,000 kg cap on the left; on the right what it makes (thermal W,
 * electric W, FE per tick), the dose a metre from the block through its housing (and, for scale, what the bare core would
 * read), the energy buffer, and the two controls: the heat-only switch and a button that loads every fuel item the
 * player carries.
 */
public class LargeRtgScreen extends BwrScreen<LargeRtgMenu> {

    private BwrButton heat;
    private BwrButton load;

    public LargeRtgScreen(LargeRtgMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 336, 224);
    }

    @Override
    protected boolean trayLimitsBody() {
        return false;
    }

    private static String kg(double grams) {
        double kg = grams / 1000.0;
        // 999.7 kg must not read 1000: the cap is exactly 1,000 kg and the meter says so only at it
        return kg >= 100 ? String.format(Locale.ROOT, "%.1f", kg) : String.format(Locale.ROOT, "%.2f", kg);
    }

    private static String watts(double w) {
        if (w >= 1.0e6) {
            return String.format(Locale.ROOT, "%.2f MW", w / 1.0e6);
        }
        return w >= 1.0e4 ? String.format(Locale.ROOT, "%.1f kW", w / 1000.0) : String.format(Locale.ROOT, "%.1f W", w);
    }

    private String halfLife() {
        double y = menu.shortestHalfLifeYears;
        if (menu.items == 0 || y <= 0.0) {
            return "-";
        }
        return y >= 100.0 ? String.format(Locale.ROOT, "%.0f", y) : String.format(Locale.ROOT, "%.1f", y);
    }

    private LargeRtgMenu.Kind kind(int i) {
        return i < menu.kinds.size() ? menu.kinds.get(i) : null;
    }

    @Override
    protected UiElement buildUi() {
        if (heat == null) {
            heat = BwrButton.secondary("Heat only",
                    "Stops the electricity and nothing else. The thermocouples turn only 5 % of the heat into FE, so the unit makes its full thermal power either way; "
                            + "with this on, none of it leaves as FE (use it as a heater). Nothing switches it for you.",
                    b -> menu.sendCommand(LargeRtgMenu.CMD_HEAT_ONLY, menu.heatOnly ? 0 : 1));
            load = BwrButton.secondary("Load my fuel",
                    "Puts every isotope ingot, block and capsule in your inventory into the unit, as much as the 1,000 kg cap allows. "
                            + "You can also right-click the block with them, or feed it with a hopper or pipe.",
                    b -> menu.sendCommand(LargeRtgMenu.CMD_LOAD_ALL));
        }
        var stored = Layout.card("Stored material",
                Layout.meter("Mass", () -> menu.massGrams / LargeRtgStore.CAPACITY_GRAMS,
                        () -> menu.massGrams >= LargeRtgStore.CAPACITY_GRAMS - 1e-6 ? Status.CAUTION : Status.NONE,
                        () -> kg(menu.massGrams) + " of 1,000 kg").label("Loaded")
                        .tip("Total mass of isotope in the unit, ingots, blocks and capsules together. 1,000 kg is the cap; an item that would pass it is refused."),
                Layout.line(() -> menu.items == 0 ? "Empty: right-click with ingots or blocks, or use a hopper" : String.format(Locale.ROOT, "%,d items", menu.items), Type.CAPTION),
                row(0), row(1), row(2), row(3), row(4), row(5), row(6), row(7),
                Layout.note("The 'bare core' reading is what a metre from the bare material would read without the housing.").showIf(() -> menu.items > 0)).compact();

        var power = Layout.card("Output",
                Layout.row("Thermal", () -> watts(menu.thermalWatts), "")
                        .tip("Heat the material gives off: grams times watts per gram, less decay. The body always runs at this, heat-only or not."),
                Layout.row("Electric", () -> watts(menu.electricWatts), "")
                        .tip("The thermocouples turn 5 % of the heat into electricity. Zero in heat-only mode."),
                Layout.row("Generating", () -> String.format(Locale.ROOT, "%,.1f", menu.fePerTick), "FE/t")
                        .tip("Electric watts over 13.4 W per FE/t, the mod's one conversion, times the server's RTG output multiplier (1 unless changed)."),
                Layout.row("Half-life", this::halfLife, "years")
                        .tip("The shortest half-life of what is loaded.")).compact();

        var safety = Layout.card("Radiation and buffer",
                Layout.row("Dose at 1 m", () -> dev.bwr.mod.isotope.LargeRtgBlockEntity.doseText(menu.doseMsvPerH), "")
                        .status(() -> menu.doseMsvPerH > 1.0 ? Status.ALARM : menu.doseMsvPerH > 0.01 ? Status.CAUTION : Status.NONE)
                        .tip("What a person a metre from the block reads, through 40 cm of lead-equivalent housing. This is the real number: it is not hidden when the housing stops everything."),
                Layout.row("Bare core, 1 m", () -> dev.bwr.mod.isotope.LargeRtgBlockEntity.doseText(menu.bareDoseMsvPerH), "")
                        .tip("For scale: the same material with no housing."),
                Layout.meter("Buffer", () -> menu.bufferFe / 1_000_000.0, () -> Status.NONE,
                        () -> String.format(Locale.ROOT, "%,.0f FE", menu.bufferFe)).label("Energy")).compact();

        var controls = Layout.column(BwrStyle.SP1, Layout.controls(BwrStyle.SP1).add(heat, 1), Layout.controls(BwrStyle.SP1).add(load, 1));
        var split = Layout.split(BwrStyle.SP1);
        split.fixed(Layout.column(BwrStyle.SP2, stored, Layout.flex()), 168);
        split.add(Layout.column(BwrStyle.SP1, power, safety, controls), 1);
        return Layout.column(BwrStyle.SP2,
                Layout.note("There is no Large RTG here.").body().status(() -> Status.ALARM).showIf(() -> !menu.present),
                split.showIf(() -> menu.present));
    }

    /** One isotope row: its label, then mass and heat. */
    private UiElement row(int i) {
        return Layout.row(() -> kind(i) == null ? "" : kind(i).label(),
                        () -> kind(i) == null ? "" : kg(kind(i).grams()), () -> kind(i) == null ? "" : "kg " + watts(kind(i).thermalWatts()))
                .showIf(() -> kind(i) != null)
                .tip("Mass of this isotope and the heat it gives off now.");
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (heat != null) {
            heat.setMessage(Component.literal(menu.heatOnly ? "Heat only: ON" : "Heat only: off"));
            heat.active = menu.present;
        }
        if (load != null) {
            load.active = menu.present && menu.massGrams < LargeRtgStore.CAPACITY_GRAMS - 1e-6;
        }
    }
}
