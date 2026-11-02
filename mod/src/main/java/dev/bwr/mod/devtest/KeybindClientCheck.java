package dev.bwr.mod.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.BwrKeys;
import dev.bwr.mod.panel.client.SizePickerScreen;
import dev.bwr.mod.panel.mimic.MimicMode;
import dev.bwr.mod.panel.mimic.MimicTapeItem;
import dev.bwr.mod.registry.BwrItems;
import java.nio.file.Files;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Disposable real-client check of the mod keys (run with -PbwrKeybindCheck through run-mc.ps1). It proves that
 * (1) both mappings are registered in the "Realistic BWR" category with their documented defaults and lang entries, and
 * show no clash in the Controls screen; (2) the size picker and the tape mode switch are driven by the mapping: moved to
 * another key they follow it, and with the old key down (and sneak down) they do nothing; (3) a rebinding is written to
 * options.txt under the mapping name; (4) holding a key with no item in hand does nothing.
 * It does not run in ordinary play or ship in the release jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class KeybindClientCheck {
    private static final InputConstants.Key Z = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_Z);
    private static final InputConstants.Key X = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_X);
    private static final InputConstants.Key ALT = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_LEFT_ALT);
    private static final InputConstants.Key SHIFT = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_LEFT_SHIFT);
    private static final InputConstants.Key RSHIFT = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_RIGHT_SHIFT);

    private static boolean started;
    private static int age, joined, phase, wait;
    private static MimicMode tapeStart;

    private KeybindClientCheck() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean("bwr.keybindClientCheck")) return;
        try {
            step();
        } catch (Throwable failure) {
            LogUtils.getLogger().error("KEYBIND CLIENT CHECK FAILED in phase {}", phase, failure);
            Minecraft.getInstance().stop();
        }
    }

    private static void require(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static void step() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (++age > 2400) throw new IllegalStateException("Keybind client check timed out in phase " + phase);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.createWorldOpenFlows().createFreshLevel("bwr-keybind-client-" + System.currentTimeMillis(),
                    new LevelSettings("Keybind client check", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case 0 -> {
                hand(mc, ItemStack.EMPTY); // the welcome guide book may be in the hand
                next(10);
            }
            case 1 -> {
                registration(mc);
                hand(mc, new ItemStack(BwrItems.DIAL_GAUGE.get()));
                next(10);
            }
            case 2 -> {
                require(mc.player.getMainHandItem().is(BwrItems.DIAL_GAUGE.get()), "the dial gauge did not reach the client");
                sizePicker(mc);
                hand(mc, new ItemStack(BwrItems.MIMIC_TAPE.get()));
                next(10);
            }
            case 3 -> {
                require(mc.player.getMainHandItem().getItem() instanceof MimicTapeItem, "the tape did not reach the client");
                tapeStart = MimicTapeItem.mode(mc.player.getMainHandItem());
                mimicMode(mc);
                next(15);
            }
            case 4 -> {
                MimicMode expected = tapeStart.step(2);
                MimicMode seen = MimicTapeItem.mode(mc.player.getMainHandItem());
                require(seen == expected, "two mode-key scrolls should move the tape from " + tapeStart + " to " + expected + " but it is " + seen);
                LogUtils.getLogger().info("KEYBIND CLIENT CHECK PASS");
                mc.stop();
                phase++;
            }
            default -> { }
        }
    }

    private static void next(int ticks) {
        phase++;
        wait = ticks;
    }

    private static void hand(Minecraft mc, ItemStack stack) {
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        server.execute(() -> server.getPlayerList().getPlayer(uuid).setItemInHand(InteractionHand.MAIN_HAND, stack));
    }

    private static void rebind(Minecraft mc, KeyMapping mapping, InputConstants.Key key) {
        mc.options.setKey(mapping, key);
        KeyMapping.resetMapping();
    }

    private static void registration(Minecraft mc) throws Exception {
        require(BwrKeys.ALL.length == 2, "expected two mod mappings");
        for (KeyMapping mapping : BwrKeys.ALL) {
            require(Arrays.asList(mc.options.keyMappings).contains(mapping), mapping.getName() + " is not in Options.keyMappings (not registered)");
            require(mapping.getCategory().equals("key.categories.bwr"), mapping.getName() + " is in category " + mapping.getCategory());
            require(I18n.exists(mapping.getName()), "no lang entry for " + mapping.getName());
            for (KeyMapping other : mc.options.keyMappings) {
                // The Controls screen shows a key red when KeyMapping.same says it shares a key with another one.
                require(other == mapping || !mapping.same(other), mapping.getName() + " clashes with " + other.getName() + " (it would show red in Controls)");
            }
        }
        require(I18n.exists("key.categories.bwr") && I18n.get("key.categories.bwr").equals("Realistic BWR"), "the category is not named Realistic BWR");
        require(BwrKeys.SIZE_PICKER.getDefaultKey().equals(ALT), "size picker default is " + BwrKeys.SIZE_PICKER.getDefaultKey());
        require(BwrKeys.MIMIC_MODE.getDefaultKey().equals(RSHIFT), "mimic mode default is " + BwrKeys.MIMIC_MODE.getDefaultKey());
        require(BwrKeys.SIZE_PICKER.getName().equals("key.bwr.size_picker") && BwrKeys.MIMIC_MODE.getName().equals("key.bwr.mimic_mode"), "mapping names changed");
        // Nothing in hand yet: holding the keys does nothing (a click or a scroll with an empty hand is not taken).
        require(mc.player.getMainHandItem().isEmpty(), "the check expects an empty hand to start");
        KeyMapping.set(ALT, true);
        KeyMapping.set(RSHIFT, true);
        require(!use(mc).isCanceled() && mc.screen == null && !scroll(1.0).isCanceled(), "a mod key did something with its item not in hand");
        KeyMapping.set(ALT, false);
        KeyMapping.set(RSHIFT, false);

        // A rebinding is saved in options.txt under the mapping name, and the default is written back after.
        rebind(mc, BwrKeys.SIZE_PICKER, Z);
        mc.options.save();
        String saved = Files.readString(mc.gameDirectory.toPath().resolve("options.txt"));
        require(saved.contains("key_key.bwr.size_picker:key.keyboard.z"), "options.txt has no key_key.bwr.size_picker:key.keyboard.z line");
        rebind(mc, BwrKeys.SIZE_PICKER, BwrKeys.SIZE_PICKER.getDefaultKey());
        mc.options.save();
        saved = Files.readString(mc.gameDirectory.toPath().resolve("options.txt"));
        require(saved.contains("key_key.bwr.size_picker:key.keyboard.left.alt"), "options.txt did not get the default back");
        LogUtils.getLogger().info("KEYBIND CLIENT CHECK registration, defaults, lang, no clash, nothing done without the item, options.txt: ok");
    }

    private static InputEvent.InteractionKeyMappingTriggered use(Minecraft mc) {
        var event = new InputEvent.InteractionKeyMappingTriggered(1, mc.options.keyUse, InteractionHand.MAIN_HAND);
        NeoForge.EVENT_BUS.post(event);
        return event;
    }

    private static void sizePicker(Minecraft mc) {
        require(mc.screen == null, "a screen is open");
        // No key: a plain click does not open the picker.
        require(!use(mc).isCanceled() && mc.screen == null, "a click without the size-picker key opened the picker");
        // Moved to Z: Z opens it; the old Alt (and sneak) does not.
        rebind(mc, BwrKeys.SIZE_PICKER, Z);
        KeyMapping.set(ALT, true);
        KeyMapping.set(SHIFT, true);
        require(!use(mc).isCanceled() && mc.screen == null, "the old Alt key still opens the picker after the mapping was moved to Z");
        KeyMapping.set(ALT, false);
        KeyMapping.set(SHIFT, false);
        KeyMapping.set(Z, true);
        var event = use(mc);
        require(event.isCanceled() && mc.screen instanceof SizePickerScreen, "Z (the mapped key) did not open the size picker");
        mc.setScreen(null);
        KeyMapping.set(Z, false);
        // Back on the default: Alt opens it again.
        rebind(mc, BwrKeys.SIZE_PICKER, BwrKeys.SIZE_PICKER.getDefaultKey());
        KeyMapping.set(ALT, true);
        require(use(mc).isCanceled() && mc.screen instanceof SizePickerScreen, "the default key did not open the size picker");
        mc.setScreen(null);
        KeyMapping.set(ALT, false);
        LogUtils.getLogger().info("KEYBIND CLIENT CHECK size picker follows the mapping: ok");
    }

    private static InputEvent.MouseScrollingEvent scroll(double delta) {
        var event = new InputEvent.MouseScrollingEvent(0.0, delta, false, false, false, 0.0, 0.0);
        NeoForge.EVENT_BUS.post(event);
        return event;
    }

    private static void mimicMode(Minecraft mc) {
        // Sneak alone no longer switches the mode once the mapping is somewhere else; X does.
        rebind(mc, BwrKeys.MIMIC_MODE, X);
        KeyMapping.set(SHIFT, true);
        require(!scroll(1.0).isCanceled(), "sneak (Shift) still switches the tape mode after the mapping was moved to X");
        KeyMapping.set(SHIFT, false);
        KeyMapping.set(X, true);
        require(scroll(1.0).isCanceled(), "X (the mapped key) did not take the scroll");
        KeyMapping.set(X, false);
        require(!scroll(1.0).isCanceled(), "a scroll with no key held was taken by the tape");
        rebind(mc, BwrKeys.MIMIC_MODE, BwrKeys.MIMIC_MODE.getDefaultKey());
        KeyMapping.set(SHIFT, true);
        require(!scroll(1.0).isCanceled(), "sneak (Shift) takes the scroll on the default mapping");
        KeyMapping.set(SHIFT, false);
        KeyMapping.set(ALT, true);
        require(!scroll(1.0).isCanceled(), "Left Alt (the size-picker key) takes the tape scroll");
        KeyMapping.set(ALT, false);
        KeyMapping.set(RSHIFT, true);
        require(scroll(1.0).isCanceled(), "the default key (Right Shift) did not take the scroll");
        KeyMapping.set(RSHIFT, false);
        LogUtils.getLogger().info("KEYBIND CLIENT CHECK mimic tape mode follows the mapping: ok");
    }
}
