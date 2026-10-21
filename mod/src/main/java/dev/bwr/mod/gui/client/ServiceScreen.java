package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.ServiceMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
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
 * The small hardware panel for the SLC boron tank, the ADS controller, the safety/relief valve and the water discharge
 * port. The server sends up to twelve plain text lines; this screen lays them out as a state line, label-value rows and
 * notes. The text itself is the server's, unchanged.
 */
public final class ServiceScreen extends BwrScreen<ServiceMenu> {
    private static final int MAX_LINES = 12;

    private BwrButton toggle;
    private BwrButton division;
    private BwrButton redstone;

    public ServiceScreen(ServiceMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 336, 176);
    }

    @Override
    protected UiElement buildUi() {
        if (toggle == null) {
            toggle = BwrButton.primary("Open", "Send the open or close command.", b -> menu.sendCommand(1, 0));
            division = BwrButton.secondary("Division", "Cycle through divisions 1 to 4. Only valves of the same division are commanded together.",
                    b -> menu.sendCommand(2, 0));
            redstone = BwrButton.secondary("Use redstone", "Hand the valve back to redstone: it then follows the redstone signal at its position.",
                    b -> menu.sendCommand(3, 0));
        }
        UiElement[] lines = new UiElement[MAX_LINES];
        for (int i = 0; i < MAX_LINES; i++) {
            lines[i] = new ServiceLine(i);
        }
        Layout.Controls actions = Layout.controls(BwrStyle.SP2)
                .add(toggle, 1, () -> menu.kind > 1)
                .add(division, 1, () -> menu.kind == 2 || menu.kind == 3)
                .add(redstone, 1, () -> menu.kind == 2 || menu.kind == 3);
        return Layout.column(BwrStyle.SP2,
                Layout.card("", lines),
                Layout.flex(),
                actions.showIf(() -> menu.kind > 1));
    }

    @Override
    protected UiElement buildTitleItems() {
        return Layout.titleItems(Layout.chip(this::stateStatus, this::stateWord).showIf(() -> menu.kind > 1));
    }

    private Status stateStatus() {
        return switch (menu.kind) {
            case 2, 3 -> menu.active ? Status.INFO : Status.OFF;
            case 4 -> menu.active ? Status.NORMAL : Status.OFF;
            default -> Status.NONE;
        };
    }

    private String stateWord() {
        return switch (menu.kind) {
            case 2, 3 -> menu.active ? "OPEN" : "CLOSED";
            case 4 -> menu.active ? "ENABLED" : "DISABLED";
            default -> "";
        };
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        toggle.setMessage(Component.literal(menu.active ? "Close" : "Open"));
        toggle.setTooltipText(menu.kind == 4
                ? "Switch the discharge port on or off."
                : menu.active ? "Command the valve closed." : "Command the valve open.");
        division.setMessage(Component.literal("Division: " + menu.division));
    }

    /** One of the server's lines, drawn as a state line, a label-value row or a note, whichever it is. */
    private final class ServiceLine extends UiElement {
        private final int index;
        private boolean cut;

        ServiceLine(int index) {
            this.index = index;
        }

        private String text() {
            List<String> lines = menu.lines;
            return index < lines.size() ? lines.get(index) : null;
        }

        private int split(String s) {
            int i = s.indexOf(": ");
            return i > 0 && i <= 28 && !s.substring(0, i).contains(".") ? i : -1;
        }

        @Override
        protected int measure(int width) {
            String s = text();
            if (s == null) {
                return 0;
            }
            if (split(s) >= 0 || index == 0) {
                return BwrStyle.ROW;
            }
            return Ui.wrapStrings(s, width, Type.CAPTION, 0).size() * BwrStyle.LINE;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my) {
            String s = text();
            if (s == null) {
                return;
            }
            int cut = split(s);
            int ty = y + (BwrStyle.ROW - 8) / 2;
            if (cut >= 0) {
                String label = s.substring(0, cut);
                String value = s.substring(cut + 2);
                int maxValue = w - 28;
                String shownValue = Ui.fit(value, Type.BODY, maxValue);
                int valueX = x + w - Ui.width(shownValue, Type.BODY);
                String shownLabel = Ui.fit(label, Type.BODY, valueX - x - BwrStyle.SP2);
                this.cut = !shownValue.equals(value) || !shownLabel.equals(label);
                Ui.text(g, shownLabel, x, ty, Type.BODY, BwrStyle.TEXT_SECONDARY);
                Ui.text(g, shownValue, valueX, ty, Type.BODY, BwrStyle.TEXT_PRIMARY);
            } else if (index == 0) {
                Status st = s.toLowerCase(Locale.ROOT).contains("incomplete") || s.toLowerCase(Locale.ROOT).contains("blocked")
                        ? Status.CAUTION : Status.NORMAL;
                Ui.statusIcon(g, x, y + (BwrStyle.ROW - BwrStyle.ICON) / 2, st);
                String shown = Ui.fit(s, Type.BODY, w - BwrStyle.ICON - BwrStyle.SP1);
                this.cut = !shown.equals(s);
                Ui.text(g, shown, x + BwrStyle.ICON + BwrStyle.SP1, ty, Type.BODY, st.colour);
            } else {
                List<String> wrapped = Ui.wrapStrings(s, w, Type.CAPTION, 0);
                for (int i = 0; i < wrapped.size(); i++) {
                    Ui.text(g, wrapped.get(i), x, y + i * BwrStyle.LINE + 1, Type.CAPTION);
                }
                this.cut = false;
            }
        }

        @Override
        public List<Component> tooltipAt(int mx, int my) {
            String s = text();
            return s != null && cut && contains(mx, my) ? List.of(Component.literal(s)) : List.of();
        }

        @Override
        public void narrate(List<String> out) {
            String s = text();
            if (s != null) {
                out.add(s);
            }
        }
    }
}
