package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.PanelComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Entry points the common network code calls into the client through. */
public final class PanelClientHooks {
    private PanelClientHooks() {
    }

    /** Opens the linker's binding screen with the device lists the server sent. */
    public static void openBind(dev.bwr.mod.panel.net.PanelPayloads.LinkerScreen payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.isLoaded(payload.pos())
                || !(minecraft.level.getBlockEntity(payload.pos()) instanceof PanelBlockEntity be)) {
            return;
        }
        PanelComponent component = be.component(payload.id());
        if (component != null && component.type() != null) {
            minecraft.setScreen(new BindScreen(be, component, payload));
        }
    }

    /** Opens the wrench screen for a component the client already knows about. */
    public static void openConfig(BlockPos owner, int id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.isLoaded(owner)
                || !(minecraft.level.getBlockEntity(owner) instanceof PanelBlockEntity be)) {
            return;
        }
        PanelComponent component = be.component(id);
        if (component != null && component.type() != null) {
            // A recorder has far more settings than the generic screen can show: it has its own, with tabs.
            minecraft.setScreen(component.data() instanceof dev.bwr.mod.panel.component.PaperlessRecorderData
                    ? new RecorderSetupScreen(be, component) : new ConfigScreen(be, component));
        }
    }
}
