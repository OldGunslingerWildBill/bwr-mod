package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.TerryTurbineMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrEditBox;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.Ui;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.Locale;

/**
 * Terry RCIC/HPCI panel: live steam and water readouts, the speed, run and control buttons on the OPERATE tab, and the
 * flange map (where each pipe connects, in world coordinates, and whether it is linked) on the PORTS tab.
 */
public final class TerryTurbineScreen extends BwrScreen<TerryTurbineMenu> {
    private static final String[] PORT_NAMES = {"Steam admission", "Steam exhaust", "Water suction", "Water discharge"};
    private static final String[] PORT_PIPES = {"High-pressure steam pipe", "High-pressure steam pipe",
            "Water pipe or Mekanism mechanical pipe", "High-pressure water pipe"};
    private static final String LOCKED = "This is locked because a computer is controlling the turbine. ";
    private static final String TAKE_BACK = "Use the control button to take control back.";

    private BwrEditBox speed;
    private BwrButton apply;
    private BwrButton run;
    private BwrButton control;
    private BwrButton suction;
    private double displayedTarget = Double.NaN;
    private int tab;

    public TerryTurbineScreen(TerryTurbineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 384, 216);
    }

    private boolean manual() {
        return menu.present && menu.control != 2;
    }

    @Override
    protected UiElement buildUi() {
        if (speed == null) {
            speed = new BwrEditBox(font, Component.literal("Shaft speed demand in percent"),
                    Component.literal("Type the shaft speed you want as a percentage from 0 to 100, with at most one decimal, then press Enter or Apply speed."),
                    5, PercentInput.FILTER, this::applySpeed);
            apply = BwrButton.primary("Apply speed", "Send the typed shaft speed to the turbine.", b -> applySpeed());
            run = BwrButton.secondary("Start turbine", "Start the turbine.",
                    b -> menu.sendCommand(TerryTurbineMenu.RUN, menu.running ? 0 : 1));
            control = BwrButton.secondary("Controlled from this panel", "Choose who controls this turbine.",
                    b -> menu.sendCommand(TerryTurbineMenu.CONTROL, (menu.control + 1) % 3));
            suction = BwrButton.secondary("Water source", "Choose where the turbine pump takes its water from.",
                    b -> menu.sendCommand(TerryTurbineMenu.SUCTION, menu.poolSuction ? 0 : 1));
        }
        var steam = Layout.card("Steam",
                Layout.row("Inlet pressure", () -> num(menu.inletPressure, 1), "psig"),
                Layout.row("Exhaust pressure", () -> num(menu.exhaustPressure, 1), "psig"),
                Layout.row("Steam used", () -> num(menu.steamFlow, 2), "kg/s"));
        var water = Layout.card("Water",
                Layout.row("Water delivered", () -> num(menu.flow, 1), "kg/s"),
                Layout.row("Pressure rise", () -> num(menu.pressure, 1), "psi"),
                Layout.row("Inlet temperature", () -> Double.isFinite(menu.temperature) ? num(menu.temperature, 1) : "Unavailable",
                        () -> Double.isFinite(menu.temperature) ? "C" : ""));
        var operate = Layout.column(BwrStyle.SP1,
                Layout.statusLine(() -> !menu.present ? Status.CAUTION : menu.running ? Status.NORMAL : Status.OFF,
                        () -> !menu.present ? "Waiting for the turbine to report"
                                : menu.running ? "Running on steam at " + Math.round(menu.actual * 100) + "% of its " + Math.round(menu.target * 100) + "% target"
                                : "Stopped",
                        () -> readyPorts() + " of 4 ports linked"),
                Layout.column(0,
                        Layout.row("Shaft speed and target", () -> pct(menu.actual) + " / " + pct(menu.target), "%"),
                        Layout.meter("Shaft speed", () -> menu.actual, null, () -> "")),
                Layout.split(BwrStyle.SP2, steam, water).stackBelow(340),
                Layout.column(BwrStyle.SP1,
                        Layout.controls(BwrStyle.SP1).label("Shaft speed demand (%)", 124).add(speed, 1).fixed(apply, 92),
                        Layout.controls(BwrStyle.SP1).add(run, 1).add(control, 1),
                        Layout.controls(BwrStyle.SP1).add(suction, 1))).showIf(() -> tab == 0);
        var ports = Layout.column(BwrStyle.SP2,
                Layout.card("Pipe connections and where they are",
                        new PortRow(0), new PortRow(1), new PortRow(2), new PortRow(3)),
                Layout.note("Each flange takes the pipe named in its tooltip. A port is linked when a route of the right pipe reaches it. "
                        + "The water suction port says it has water when it holds some, and dry or shut when it does not."))
                .showIf(() -> tab == 1);
        return Layout.column(0, operate, ports);
    }

    @Override
    protected UiElement buildTitleItems() {
        return Layout.titleItems(
                Layout.chip(() -> Status.INFO, () -> "Computer in control").showIf(() -> menu.present && menu.control == 2),
                Layout.tabs(new String[]{"OPERATE", "PORTS"},
                        new String[]{"The readings and the controls.", "Where each pipe connects, in world coordinates, and whether it is linked."},
                        () -> tab, i -> tab = i));
    }

    private int readyPorts() {
        int n = 0;
        for (boolean ready : menu.portReady) {
            if (ready) {
                n++;
            }
        }
        return n;
    }

    private Integer percent() {
        return PercentInput.perMille(speed.getValue());
    }

    private void applySpeed() {
        Integer p = percent();
        if (p != null) {
            menu.sendCommand(TerryTurbineMenu.SPEED, p);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (!speed.isFocused() && Double.compare(displayedTarget, menu.target) != 0) {
            speed.setValue(String.format(Locale.ROOT, "%.1f", menu.target * 100));
            displayedTarget = menu.target;
        }
        boolean manual = manual();
        boolean locked = menu.control == 2;
        String why = !menu.present ? "The turbine has not reported yet, so there is nothing to control. " : locked ? LOCKED : "";
        String back = locked ? TAKE_BACK : "";
        speed.setEditable(manual);
        speed.setInvalid(!speed.getValue().isEmpty() && manual && percent() == null);
        apply.active = manual && percent() != null;
        apply.setTooltipText(!why.isEmpty() ? why + back : percent() == null
                ? "The speed has to be a number from 0 to 100, with at most one decimal." : "Send the typed shaft speed to the turbine.");
        run.active = manual;
        run.setMessage(Component.literal(menu.running ? "Stop turbine" : "Start turbine"));
        run.setTooltipText(!why.isEmpty() ? why + back : menu.running ? "Stop the turbine." : "Start the turbine.");
        control.active = menu.present;
        control.setMessage(Component.literal(switch (menu.control) {
            case 1 -> "Controlled by redstone";
            case 2 -> "Controlled by a computer";
            default -> "Controlled from this panel";
        }));
        control.setTooltipText(!menu.present ? why : switch (menu.control) {
            case 1 -> "A redstone signal starts and stops this turbine. Click to hand control to a computer.";
            case 2 -> "A computer controls this turbine. Click to take control back to this panel.";
            default -> "This panel controls this turbine. Click to switch to redstone control.";
        });
        suction.active = manual;
        suction.setMessage(Component.literal(menu.poolSuction ? "Taking water from the suppression pool"
                : "Taking water from the condensate tank or piped supply"));
        suction.setTooltipText(!why.isEmpty() ? why + back : menu.poolSuction
                ? "The turbine pump is taking water from the suppression pool. Click to take it from the condensate tank or piped supply instead."
                : "The turbine pump is taking water from the condensate tank or piped supply. Click to take it from the suppression pool instead.");
    }

    /** One flange: its name, its world position (cut with a tooltip if it does not fit) and whether it is linked. */
    private final class PortRow extends UiElement {
        private final int index;
        private boolean cut;

        PortRow(int index) {
            this.index = index;
        }

        @Override
        protected int measure(int width) {
            return BwrStyle.ROW + BwrStyle.SP1;
        }

        private String stateWord() {
            boolean ready = menu.portReady[index];
            return ready ? (index == 2 ? "Has water" : "Linked") : (index == 2 ? "Dry or shut" : "No pipe route");
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            int ty = y + (BwrStyle.ROW - 8) / 2;
            boolean ready = menu.portReady[index];
            Status st = ready ? Status.NORMAL : Status.OFF;
            String word = stateWord();
            int wordW = Ui.width(word, Type.BODY);
            Ui.text(g, PORT_NAMES[index], x, ty, Type.BODY, BwrStyle.TEXT_PRIMARY);
            int stateRight = x + w;
            Ui.textRight(g, word, stateRight, ty, Type.BODY, st.colour);
            Ui.statusIcon(g, stateRight - wordW - BwrStyle.SP1 - BwrStyle.ICON, y + (BwrStyle.ROW - BwrStyle.ICON) / 2, st);
            int coordX = x + 104;
            int coordMax = stateRight - wordW - BwrStyle.SP1 - BwrStyle.ICON - BwrStyle.SP2 - coordX;
            String location = menu.portLocations[index];
            String shown = Ui.fit(location, Type.CAPTION, coordMax);
            cut = !shown.equals(location);
            Ui.text(g, shown, coordX, ty, Type.CAPTION);
        }

        @Override
        public List<Component> tooltipAt(int mx, int my) {
            if (!contains(mx, my)) {
                return List.of();
            }
            return List.of(Component.literal(PORT_NAMES[index] + ": " + menu.portLocations[index]),
                    Component.literal(PORT_PIPES[index]), Component.literal(stateWord()));
        }

        @Override
        public void narrate(List<String> out) {
            out.add(PORT_NAMES[index] + ", " + menu.portLocations[index] + ", " + stateWord());
        }
    }
}
