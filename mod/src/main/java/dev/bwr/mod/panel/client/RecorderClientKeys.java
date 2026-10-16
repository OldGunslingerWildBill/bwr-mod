package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PaperlessRecorderData;
import dev.bwr.mod.panel.component.RecorderKey;
import dev.bwr.mod.panel.component.RecorderUi;
import dev.bwr.mod.panel.model.PanelModels;
import dev.bwr.mod.panel.model.RecorderLayout;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Clicks on the face of a paperless recorder: the click position (in cells from the component's top-left) is turned
 * into a key by the bezel's {@link RecorderLayout} (the part bounds of the baked model by name, or the built in
 * rectangles of the stand-in), the key goes through the client's {@link RecorderUi} (menu, FUNC strip, cover), and
 * what that asks of the server is sent as an ordinary operate packet carrying an action code that the server
 * validates. Opening the setup screen and the free message box happen here, on the client that clicked.
 */
public final class RecorderClientKeys {
    private RecorderClientKeys() {
    }

    /** A click landed on the recorder at ({@code cellX}, {@code cellY}); returns whether it was on a key. */
    public static boolean click(PanelBlockEntity owner, PanelComponent component, double cellX, double cellY) {
        RecorderKey key = keyAt(owner, component, cellX, cellY);
        if (key == null) {
            return false;
        }
        press(owner, component, key);
        return true;
    }

    /** The key under a cell position, the same way {@link #click} finds it. */
    public static RecorderKey keyAt(PanelBlockEntity owner, PanelComponent component, double cellX, double cellY) {
        PaperlessRecorderData data = (PaperlessRecorderData) component.data();
        RecorderLayout layout = RecorderClientStats.layout(component);
        if (layout == null) {
            float aspect = (float) PanelGeometry.frame(owner.panelType(), owner.facing()).vLength;
            layout = RecorderLayout.of(data.style, PanelModels.get(data.style.id()), component.w(), component.h(), aspect);
        }
        return layout.keyAt(cellX, cellY, data.style.hasCover() && !data.coverOpen);
    }

    /** A key was pressed (by a click or by a check): the client logic, then what it asks of the server. */
    public static void press(PanelBlockEntity owner, PanelComponent component, RecorderKey key) {
        Minecraft mc = Minecraft.getInstance();
        PaperlessRecorderData data = (PaperlessRecorderData) component.data();
        RecorderUi ui = RecorderClientStats.ui(component);
        RecorderUi.Result result = ui.press(key, data);
        ui.lastKeyTick = mc.level == null ? 0L : mc.level.getGameTime();
        if (!result.handled()) {
            return;
        }
        if (result.action() != 0) {
            PacketDistributor.sendToServer(new dev.bwr.mod.panel.net.PanelPayloads.Operate(owner.getBlockPos(), component.id(),
                    OperateAction.PRESS.ordinal(), result.action()));
        }
        switch (result.effect()) {
            case OPEN_SETUP -> PanelClientHooks.openConfig(owner.getBlockPos(), component.id());
            case OPEN_FREE_MESSAGE -> mc.setScreen(new RecorderMessageScreen(owner.getBlockPos(), component.id()));
            default -> {
            }
        }
    }
}
