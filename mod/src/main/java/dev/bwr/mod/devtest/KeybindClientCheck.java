package dev.bwr.mod.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.BwrKeys;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.panel.client.SizePickerScreen;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.SetpointKnobData;
import dev.bwr.mod.panel.mimic.MimicMode;
import dev.bwr.mod.panel.mimic.MimicTapeItem;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import java.nio.file.Files;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Disposable real-client check of the mod keys (run with -PbwrKeybindCheck through run-mc.ps1). It proves that
 * (1) all three mappings are registered in the "Realistic BWR" category with their documented defaults and lang entries, and
 * show no clash in the Controls screen; (2) the size picker and the tape mode switch are driven by the mapping: moved to
 * another key they follow it, and with the old key down (and sneak down) they do nothing; (3) a rebinding is written to
 * options.txt under the mapping name; (4) holding a key with no item in hand does nothing; (5) the wheel key: a scroll over a
 * panel knob without it leaves the knob alone and scrolls the hotbar (the real MouseHandler path), with it held turns the knob and leaves
 * the hotbar, a middle press on the knob picks no block while one on a normal block does (the real pick-block path), and moved to a
 * keyboard key it works the same way (hold it and scroll) while the middle button no longer turns anything.
 * It does not run in ordinary play or ship in the release jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class KeybindClientCheck {
    private static final InputConstants.Key Z = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_Z);
    private static final InputConstants.Key X = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_X);
    private static final InputConstants.Key ALT = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_LEFT_ALT);
    private static final InputConstants.Key SHIFT = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_LEFT_SHIFT);
    private static final InputConstants.Key RSHIFT = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_RIGHT_SHIFT);
    private static final InputConstants.Key MIDDLE = InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);

    /** A vertical panel with a setpoint knob on it (north face), and a gold block to pick, near the world spawn. */
    private static final BlockPos PANEL = new BlockPos(2, -58, 4);
    private static final BlockPos GOLD = new BlockPos(6, -58, 1);
    private static volatile int knobId = -2;
    private static boolean standing, synced;
    private static Vec3 feet;
    private static double value0, value1;
    private static int slot;

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
        if (standing) {
            // flying, stock still, at the spot the knob is reached from (the panel and the gold block are a few blocks from spawn)
            mc.player.getAbilities().flying = true;
            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.setPos(feet.x, feet.y, feet.z);
        }
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
                hand(mc, ItemStack.EMPTY);
                build(mc);
                next(40);
            }
            case 5 -> {
                if (!synced) {
                    // The block reached the client with the tick's block updates; send the components now (sent in the same tick they
                    // would arrive before the client had the block entity, and be dropped).
                    synced = true;
                    mc.getSingleplayerServer().execute(() -> {
                        if (mc.getSingleplayerServer().overworld().getBlockEntity(PANEL) instanceof PanelBlockEntity be) {
                            be.syncFull();
                        }
                    });
                    wait = 20;
                    return;
                }
                // No wheel key: the knob stays, the hotbar scrolls.
                require(knobId >= 0, "the knob was not placed on the server");
                require(mc.player.getMainHandItem().isEmpty(), "the check expects an empty hand: " + mc.player.getMainHandItem());
                look(mc, knobFace(mc));
                requireAimedAt(mc, PANEL, "the knob");
                value0 = knob(mc);
                require(value0 == 0.0, "the new knob should read 0 but reads " + value0);
                slot = 4;
                mc.player.getInventory().selected = slot;
                require(!scroll(1.0).isCanceled(), "a scroll over the knob with no wheel key held was taken (it would turn the knob)");
                wheel(mc, 1.0);
                require(mc.player.getInventory().selected == slot - 1,
                        "the wheel over the knob with no key held did not scroll the hotbar: slot " + slot + " -> " + mc.player.getInventory().selected);
                slot--;
                next(12);
            }
            case 6 -> {
                require(knob(mc) == value0, "a scroll with no wheel key turned the knob: " + value0 + " -> " + knob(mc));
                // The wheel key held (middle button, the default): the knob turns, the hotbar stays.
                require(BwrKeys.WHEEL_TURN.getKey().equals(MIDDLE), "the wheel key is not on the middle button by default");
                KeyMapping.set(MIDDLE, true);
                wheel(mc, 1.0);
                KeyMapping.set(MIDDLE, false);
                require(mc.player.getInventory().selected == slot, "the wheel with the key held also scrolled the hotbar: slot " + slot + " -> " + mc.player.getInventory().selected);
                next(12);
            }
            case 7 -> {
                value1 = knob(mc);
                require(value1 == value0 + 1.0, "one notch with the wheel key held should turn the knob from " + value0 + " to " + (value0 + 1.0) + " but it reads " + value1);
                // Key let go again: back to the hotbar, the knob stays.
                wheel(mc, 1.0);
                require(mc.player.getInventory().selected == slot - 1, "after letting the wheel key go the wheel did not scroll the hotbar again");
                slot--;
                // A middle press on the knob picks no block: the event is cancelled with no swing, and the real press picks nothing.
                var pick = pick(mc);
                require(pick.isCanceled() && !pick.shouldSwingHand(), "the pick-block press on the knob was not cancelled without a swing");
                press(MIDDLE);
                next(4);
            }
            case 8 -> {
                KeyMapping.set(MIDDLE, false);
                require(knob(mc) == value1, "the middle press or the scroll without the key turned the knob: " + value1 + " -> " + knob(mc));
                require(!has(mc, BwrBlocks.CONTROL_PANEL_VERTICAL.get().asItem()), "a middle press on the knob picked the panel block");
                require(!has(mc, Blocks.GOLD_BLOCK.asItem()), "the gold block is in the inventory before it was picked");
                // Pick block on a normal block is vanilla's.
                look(mc, Vec3.atCenterOf(GOLD));
                requireAimedAt(mc, GOLD, "the gold block");
                var normal = pick(mc);
                require(!normal.isCanceled(), "the pick-block press on a normal block was cancelled");
                press(MIDDLE);
                next(4);
            }
            case 9 -> {
                KeyMapping.set(MIDDLE, false);
                require(has(mc, Blocks.GOLD_BLOCK.asItem()), "a middle press on a normal block did not pick it (pick block is broken there)");
                // Moved to a keyboard key (Z): the same, by hand and with Z. The middle button then turns nothing and is Pick Block's again.
                rebind(mc, BwrKeys.WHEEL_TURN, Z);
                look(mc, knobFace(mc));
                requireAimedAt(mc, PANEL, "the knob");
                var freed = pick(mc);
                require(!freed.isCanceled(), "with the wheel key moved to Z the pick-block press on the knob is still cancelled");
                slot = mc.player.getInventory().selected;
                KeyMapping.set(MIDDLE, true);
                wheel(mc, 1.0);
                KeyMapping.set(MIDDLE, false);
                int moved = Math.floorMod(slot - 1, 9);
                require(mc.player.getInventory().selected == moved && knob(mc) == value1,
                        "with the wheel key on Z the middle button still turns the knob or no longer scrolls the hotbar");
                require(!scroll(1.0).isCanceled(), "a scroll with the wheel key on Z and nothing held was taken");
                slot = moved;
                KeyMapping.set(Z, true);
                wheel(mc, 1.0);
                KeyMapping.set(Z, false);
                require(mc.player.getInventory().selected == slot, "Z (the wheel key) held: the hotbar scrolled as well");
                next(12);
            }
            case 10 -> {
                require(knob(mc) == value1 + 1.0, "Z (the rebound wheel key) held did not turn the knob one notch from " + value1 + ": " + knob(mc));
                rebind(mc, BwrKeys.WHEEL_TURN, BwrKeys.WHEEL_TURN.getDefaultKey());
                require(BwrKeys.WHEEL_TURN.getKey().equals(MIDDLE), "the default was not put back");
                LogUtils.getLogger().info("KEYBIND CLIENT CHECK wheel key: no key = hotbar, key held = knob, pick block cancelled on the knob only, rebound to Z: ok");
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
        require(BwrKeys.ALL.length == 3, "expected three mod mappings");
        for (KeyMapping mapping : BwrKeys.ALL) {
            require(Arrays.asList(mc.options.keyMappings).contains(mapping), mapping.getName() + " is not in Options.keyMappings (not registered)");
            require(mapping.getCategory().equals("key.categories.bwr"), mapping.getName() + " is in category " + mapping.getCategory());
            require(I18n.exists(mapping.getName()), "no lang entry for " + mapping.getName());
            for (KeyMapping other : mc.options.keyMappings) {
                // The Controls screen shows a key red when KeyMapping.same says it shares a key with another one.
                // (the wheel key and Pick Block share the middle button on purpose and show red; nothing else may clash)
                boolean pair = (mapping == BwrKeys.WHEEL_TURN && other == mc.options.keyPickItem)
                        || (mapping == mc.options.keyPickItem && other == BwrKeys.WHEEL_TURN);
                require(other == mapping || pair || !mapping.same(other), mapping.getName() + " clashes with " + other.getName() + " (it would show red in Controls)");
            }
        }
        require(I18n.exists("key.categories.bwr") && I18n.get("key.categories.bwr").equals("Realistic BWR"), "the category is not named Realistic BWR");
        require(BwrKeys.SIZE_PICKER.getDefaultKey().equals(ALT), "size picker default is " + BwrKeys.SIZE_PICKER.getDefaultKey());
        require(BwrKeys.MIMIC_MODE.getDefaultKey().equals(RSHIFT), "mimic mode default is " + BwrKeys.MIMIC_MODE.getDefaultKey());
        require(BwrKeys.WHEEL_TURN.getDefaultKey().equals(MIDDLE), "wheel turn default is " + BwrKeys.WHEEL_TURN.getDefaultKey());
        require(I18n.get("key.bwr.wheel_turn").equals("Turn controls with the wheel"), "the wheel key is named " + I18n.get("key.bwr.wheel_turn"));
        require(BwrKeys.WHEEL_TURN.same(mc.options.keyPickItem), "the wheel key and Pick Block should show red (the shared middle button) but do not clash");
        require(BwrKeys.SIZE_PICKER.getName().equals("key.bwr.size_picker") && BwrKeys.MIMIC_MODE.getName().equals("key.bwr.mimic_mode")
                && BwrKeys.WHEEL_TURN.getName().equals("key.bwr.wheel_turn"), "mapping names changed");
        // Nothing in hand yet: holding the keys does nothing (a click or a scroll with an empty hand is not taken).
        require(mc.player.getMainHandItem().isEmpty(), "the check expects an empty hand to start");
        KeyMapping.set(ALT, true);
        KeyMapping.set(RSHIFT, true);
        KeyMapping.set(MIDDLE, true);
        require(!use(mc).isCanceled() && mc.screen == null && !scroll(1.0).isCanceled(), "a mod key did something with its item not in hand");
        KeyMapping.set(ALT, false);
        KeyMapping.set(RSHIFT, false);
        KeyMapping.set(MIDDLE, false);

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

    // ---- the wheel key

    /** Builds the panel with its knob and the gold block (server thread), and stands the player in front of the panel. */
    private static void build(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        PanelGeometry.Frame frame = PanelGeometry.frame(PanelType.VERTICAL, Direction.NORTH);
        Vec3 target = new Vec3(PANEL.getX() + frame.x(8, 8, 0.0), PANEL.getY() + frame.y(8, 8, 0.0), PANEL.getZ() + frame.z(8, 8, 0.0));
        feet = target.add(frame.nx * 2.0, frame.ny * 2.0, frame.nz * 2.0).subtract(0.0, 1.62, 0.0);
        server.execute(() -> {
            var level = server.overworld();
            level.setBlock(PANEL, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH), 3);
            level.setBlock(GOLD, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            PanelComponent knob = PanelOps.placeDirect(level, PANEL, 4, 4, ComponentTypes.SETPOINT_KNOB, Footprint.of(8, 8));
            knobId = knob == null ? -1 : knob.id();
        });
        standing = true;
    }

    /** The knob's value on the server. */
    private static double knob(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        // (a level's block entities are only read on the server thread)
        return server.submit(() -> ((SetpointKnobData) ((PanelBlockEntity) server.overworld().getBlockEntity(PANEL)).component(knobId).data()).value).join();
    }

    /** The middle of the knob's face (the knob is cells 4 to 11 of the block). */
    private static Vec3 knobFace(Minecraft mc) {
        var be = mc.level.getBlockEntity(PANEL) instanceof PanelBlockEntity panel ? panel : null;
        require(be != null && be.component(knobId) != null, "the client has no knob yet: block " + mc.level.getBlockState(PANEL) + ", entity "
                + mc.level.getBlockEntity(PANEL) + ", knob id " + knobId + ", player at " + mc.player.position());
        PanelGeometry.Frame frame = PanelGeometry.frame(be.panelType(), be.facing());
        return new Vec3(PANEL.getX() + frame.x(8, 8, 0.0), PANEL.getY() + frame.y(8, 8, 0.0), PANEL.getZ() + frame.z(8, 8, 0.0));
    }

    private static void look(Minecraft mc, Vec3 target) {
        Vec3 d = target.subtract(mc.player.getEyePosition(1.0f));
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.yHeadRot = yaw;
        mc.gameRenderer.pick(1.0f);
    }

    private static void requireAimedAt(Minecraft mc, BlockPos pos, String what) {
        require(mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos),
                "the crosshair is not on " + what + ": " + mc.hitResult);
    }

    /** The mouse wheel the way the game takes it: MouseHandler.onScroll fires the NeoForge event, then scrolls the hotbar unless it was cancelled. */
    private static void wheel(Minecraft mc, double delta) throws Exception {
        var onScroll = MouseHandler.class.getDeclaredMethod("onScroll", long.class, double.class, double.class);
        onScroll.setAccessible(true);
        onScroll.invoke(mc.mouseHandler, mc.getWindow().getWindow(), 0.0, delta);
    }

    private static InputEvent.InteractionKeyMappingTriggered pick(Minecraft mc) {
        var event = new InputEvent.InteractionKeyMappingTriggered(2, mc.options.keyPickItem, InteractionHand.MAIN_HAND);
        NeoForge.EVENT_BUS.post(event);
        return event;
    }

    /** A press of the button as the mouse handler makes it: a click for the game's next tick to consume, and the key down. */
    private static void press(InputConstants.Key key) {
        KeyMapping.click(key);
        KeyMapping.set(key, true);
    }

    private static boolean has(Minecraft mc, net.minecraft.world.item.Item item) {
        return mc.player.getInventory().hasAnyMatching(stack -> stack.is(item));
    }
}
