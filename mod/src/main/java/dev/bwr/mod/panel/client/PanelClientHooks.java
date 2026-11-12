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
        if (payload.id() < 0) {
            // A negative id is a mimic path of the block (-localId).
            dev.bwr.mod.panel.mimic.MimicPath path = be.mimicPath(-payload.id());
            if (path != null) {
                minecraft.setScreen(new MimicBindScreen(be, path, payload));
            }
            return;
        }
        PanelComponent component = be.component(payload.id());
        if (component != null && component.type() != null) {
            minecraft.setScreen(new BindScreen(be, component, payload));
        }
    }

    /** Opens the style screen of a mimic path the client already knows about. */
    public static void openMimicConfig(BlockPos owner, int localId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.isLoaded(owner)
                || !(minecraft.level.getBlockEntity(owner) instanceof PanelBlockEntity be)) {
            return;
        }
        dev.bwr.mod.panel.mimic.MimicPath path = be.mimicPath(localId);
        if (path != null) {
            minecraft.setScreen(new MimicPathScreen(be, path));
        }
    }

    /** Opens the size picker for the component item held in the given hand. */
    public static void openSizePicker(boolean offHand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        net.minecraft.world.item.ItemStack held = minecraft.player.getItemInHand(
                offHand ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND);
        if (held.getItem() instanceof dev.bwr.mod.panel.component.ComponentItem item && item.type() != null
                && item.sizes().length > 1) {
            minecraft.setScreen(new SizePickerScreen(item, dev.bwr.mod.panel.component.ComponentItem.variant(held, item.type()),
                    offHand));
        }
    }

    /**
     * The server's answer to an open box list: the names of the machines its windows read. It goes to the list that is
     * on the screen, if one still is; anything else (the screen was closed, an answer for another box) is dropped.
     */
    public static void boxNames(dev.bwr.mod.panel.net.PanelPayloads.BoxNames payload) {
        try {
            if (Minecraft.getInstance().screen instanceof AnnunciatorBoxListScreen list) {
                list.accept(payload);
            }
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("The annunciator box list could not take its machine names", failure);
        }
    }

    /** Opens the box list of the annunciator box a window is in (from the window's wrench screen). */
    public static void openBoxList(BlockPos owner, int id) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null && minecraft.level.isLoaded(owner)
                    && minecraft.level.getBlockEntity(owner) instanceof PanelBlockEntity be && be.component(id) != null) {
                minecraft.setScreen(new AnnunciatorBoxListScreen(owner, id));
            }
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("The annunciator box list could not be opened", failure);
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
