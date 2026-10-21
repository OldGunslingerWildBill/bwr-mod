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
    private static final String LOCKED = "Locked: a computer holds the controls. ";

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
            speed = new BwrEditBox(font, Component.literal("Shaft speed percent"),
                    Component.literal("Type the shaft speed demand as a percentage from 0 to 100, one decimal at most, then press Enter or Apply."),
                    5, PercentInput.FILTER, this::applySpeed);
            apply = BwrButton.primary("Apply speed", "Send the typed speed demand to the turbine.", b -> applySpeed());
            run = BwrButton.secondary("Start turbine", "Start the turbine.",
                    b -> menu.sendCommand(TerryTurbineMenu.RUN, menu.running ? 0 : 1));
            control = BwrButton.secondary("Control: Panel", "Choose who controls this turbine.",
                    b -> menu.sendCommand(TerryTurbineMenu.CONTROL, (menu.control + 1) % 3));
            suction = BwrButton.secondary("Water source", "Choose the water source.",
                    b -> menu.sendCommand(TerryTurbineMenu.SUCTION, menu.poolSuction ? 0 : 1));
        }
        var steam = Layout.card("Steam circuit",
                Layout.row("Admission", () -> num(menu.inletPressure, 1), "psig"),
                Layout.row("Exhaust", () -> num(menu.exhaustPressure, 1), "psig"),
                Layout.row("Steam used", () -> num(menu.steamFlow, 2), "kg/s"));
        var water = Layout.card("Water circuit",
                Layout.row("Delivered", () -> num(menu.flow, 1), "kg/s"),
                Layout.row("Water load", () -> num(menu.pressure, 1), "psi"),
                Layout.row("Inlet water", () -> Double.isFinite(menu.temperature) ? num(menu.temperature, 1) : "Unavailable",
                        () -> Double.isFinite(menu.temperature) ? "C" : ""));
        var operate = Layout.column(BwrStyle.SP1,
                Layout.statusLine(() -> !menu.present ? Status.CAUTION : menu.running ? Status.NORMAL : Status.OFF,
                        () -> !menu.present ? "Waiting for turbine..." : menu.running ? "Steam drive | Run command active" : "Steam drive | Stopped",
                        () -> readyPorts() + " of 4 ports linked"),
                Layout.column(0,
                        Layout.row("Shaft speed / target", () -> pct(menu.actual) + " / " + pct(menu.target), "%"),
                        Layout.meter("Shaft speed", () -> menu.actual, null, () -> "")),
                Layout.split(BwrStyle.SP2, steam, water).stackBelow(340),
                Layout.column(BwrStyle.SP1,
                        Layout.controls(BwrStyle.SP1).label("Shaft speed (%)", 92).add(speed, 1).fixed(apply, 92),
                        Layout.controls(BwrStyle.SP1).add(run, 1).add(control, 1),
                        Layout.controls(BwrStyle.SP1).add(suction, 1))).showIf(() -> tab == 0);
        var ports = Layout.column(BwrStyle.SP2,
                Layout.card("Connections and world coordinates",
                        new PortRow(0), new PortRow(1), new PortRow(2), new PortRow(3)),
                Layout.note("Each flange takes the pipe named in its tooltip. A port is Linked when a route of the right pipe reaches it; "
                        + "Water suction reads Water ready when it holds water and Dry / shut when it does not."))
                .showIf(() -> tab == 1);
        return Layout.column(0, operate, ports);
    }

    @Override
    protected UiElement buildTitleItems() {
        return Layout.titleItems(
                Layout.chip(() -> menu.present && menu.control == 2 ? Status.INFO : Status.NONE,
                        () -> !menu.present ? "" : switch (menu.control) {
                            case 1 -> "REDSTONE";
                            case 2 -> "COMPUTER CONTROL";
                            default -> "PANEL";
                        }),
                Layout.tabs(new String[]{"OPERATE", "PORTS"},
                        new String[]{"Readouts and controls", "Where each pipe connects, in world coordinates, and whether it is linked"},
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
        speed.setEditable(manual);
        speed.setInvalid(!speed.getValue().isEmpty() && manual && percent() == null);
        apply.active = manual && percent() != null;
        apply.setTooltipText(locked ? LOCKED + "Take control back with the Control button." : "Send the typed speed demand to the turbine.");
        run.active = manual;
        run.setMessage(Component.literal(menu.running ? "Stop turbine" : "Start turbine"));
        run.setTooltipText(locked ? LOCKED : menu.running ? "Stop the turbine." : "Start the turbine.");
        control.active = menu.present;
        control.setMessage(Component.literal("Control: " + switch (menu.control) {
            case 1 -> "Redstone";
            case 2 -> "Computer";
            default -> "Panel";
        }));
        control.setTooltipText("Who controls this turbine: the panel, redstone or a computer. Click to cycle.");
        suction.active = manual;
        suction.setMessage(Component.literal(menu.poolSuction ? "Water source: Suppression pool" : "Water source: Condensate tank / piped water"));
        suction.setTooltipText(locked ? LOCKED : menu.poolSuction
                ? "Water source: the suppression pool. Click to draw from the condensate tank or piped water instead."
                : "Water source: the condensate tank or piped water. Click to draw from the suppression pool instead.");
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
            return ready ? (index == 2 ? "Water ready" : "Linked") : (index == 2 ? "Dry / shut" : "No route");
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
