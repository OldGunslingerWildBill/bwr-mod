package dev.bwr.mod.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.bwr.mod.BwrMod;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * The mod's own keys, in Options > Controls under "Realistic BWR". Client actions read these with {@code isDown()}, never a
 * raw key code or {@code Screen.hasAltDown()}, so the player's choice (saved in options.txt like any other key) is the
 * one that counts. Nothing here reaches the server: the key only decides which packet the client sends.
 *
 * <p>The defaults of the size picker and the tape mode are chosen so that neither shows red in the Controls screen: Left Alt is
 * not a vanilla key, and the tape's old key, Shift, is vanilla Sneak. (NeoForge counts two mappings on the same key as a clash
 * whatever their contexts.) The wheel key is the one exception: it defaults to the middle mouse button, which is vanilla Pick
 * Block, so it shows red in Controls on purpose (see {@link #WHEEL_TURN}).
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class BwrKeys {
    public static final String CATEGORY = "key.categories.bwr";
    public static final String SIZE_PICKER_NAME = "key.bwr.size_picker";
    public static final String MIMIC_MODE_NAME = "key.bwr.mimic_mode";
    public static final String WHEEL_TURN_NAME = "key.bwr.wheel_turn";

    /** Held with a click and a panel component item in hand: opens the size picker. Default Left Alt (as before). */
    public static final KeyMapping SIZE_PICKER = new KeyMapping(SIZE_PICKER_NAME, KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, CATEGORY);

    /** Held with the mouse wheel and the mimic tape in hand: switches draw, erase and edit. Default Right Shift (it was sneak). */
    public static final KeyMapping MIMIC_MODE = new KeyMapping(MIMIC_MODE_NAME, KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, CATEGORY);

    /**
     * Held while scrolling over a panel knob, selector or thumbwheel: only then does the wheel turn it (otherwise the wheel
     * changes the hotbar slot as usual, so a stray scroll cannot move a control). Default the middle mouse button, the owner's
     * choice. That button is also vanilla Pick Block, so the Controls screen shows the pair in red; {@code PanelClientOperate}
     * cancels the pick-block press while the crosshair is on a scrollable component so holding the wheel there never picks a block.
     *
     * <p>The red is accepted rather than hidden: NeoForge compares only the two mappings' conflict contexts, and the stock ones
     * are UNIVERSAL (clashes with everything), GUI and IN_GAME (Pick Block's own, so it clashes with this one). Hiding the red
     * would take a custom context that clashes with no IN_GAME key at all, and then a player who moves this mapping onto, say,
     * Swap Hands or a movement key would get no warning either. The red is the honest signal for a shared key.
     */
    public static final KeyMapping WHEEL_TURN = new KeyMapping(WHEEL_TURN_NAME, KeyConflictContext.IN_GAME,
            InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, CATEGORY);

    /** Every mapping of the mod, for the registration and the client check. */
    public static final KeyMapping[] ALL = {SIZE_PICKER, MIMIC_MODE, WHEEL_TURN};

    private BwrKeys() {
    }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        for (KeyMapping mapping : ALL) {
            event.register(mapping);
        }
    }
}
