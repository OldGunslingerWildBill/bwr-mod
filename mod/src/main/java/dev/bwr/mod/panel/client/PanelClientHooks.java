package dev.bwr.mod.panel.client;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.PanelComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Entry points the common network code calls into the client through. */
public final class PanelClientHooks {
    private PanelClientHooks() {
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
            minecraft.setScreen(new ConfigScreen(be, component));
        }
    }
}
