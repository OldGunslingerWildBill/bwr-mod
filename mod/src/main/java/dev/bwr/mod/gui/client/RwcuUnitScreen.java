package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.RwcuUnitMenu;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/** Read-only instrumentation of a passive RWCU unit: a headline and the readings the unit reports. */
public class RwcuUnitScreen extends BwrScreen<RwcuUnitMenu> {
    private static final int ROWS = 10;

    public RwcuUnitScreen(RwcuUnitMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 360, 232);
    }

    private static Status status(int s) {
        return switch (s) { case 3 -> Status.ALARM; case 2 -> Status.CAUTION; case 1 -> Status.NORMAL; default -> Status.NONE; };
    }

    @Override
    protected UiElement buildUi() {
        List<UiElement> rows = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            final int index = i;
            rows.add(Layout.row(() -> index < menu.lines.size() ? menu.lines.get(index).label() : "",
                    () -> index < menu.lines.size() ? menu.lines.get(index).value() : "",
                    () -> index < menu.lines.size() ? menu.lines.get(index).unit() : "")
                    .status(() -> index < menu.lines.size() ? status(menu.lines.get(index).status()) : Status.NONE)
                    .showIf(() -> index < menu.lines.size()));
        }
        return Layout.column(BwrStyle.SP2,
                Layout.statusLine(() -> status(menu.headlineStatus), () -> menu.headline.isEmpty() ? "Waiting for the unit to report" : menu.headline, null),
                Layout.card("Readings", rows.toArray(new UiElement[0])),
                Layout.note("Water lines: the pump drives the skid. Nothing here acts on its own."));
    }
}
