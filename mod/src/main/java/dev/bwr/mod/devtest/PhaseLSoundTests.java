package dev.bwr.mod.devtest;

import dev.bwr.mod.BwrMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Registry integration test: needs no blocks, forced chunks, or sound playback. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PhaseLSoundTests {
    static final List<String> PATHS = List.of("annunciator_horn", "alarm_acknowledge", "relay_click",
            "contactor_click", "control_switch_detent", "breaker_close", "breaker_trip", "pump_motor",
            "turbine_whine", "steam_flow", "srv_lift", "msiv_slam", "scram_buzzer", "edg_start",
            "edg_run", "geiger_click");

    private PhaseLSoundTests() { }

    @GameTest(template = "empty", batch = "l4_sounds", timeoutTicks = 20)
    public static void everyPlaceholderSoundIsRegisteredWithVariableRange(GameTestHelper helper) {
        for (String path : PATHS) {
            var id = BwrMod.id(path);
            helper.assertTrue(BuiltInRegistries.SOUND_EVENT.containsKey(id), "missing sound registry entry " + id);
            var sound = BuiltInRegistries.SOUND_EVENT.get(id);
            helper.assertTrue(sound != null && sound.getLocation().equals(id), "sound registry key/location mismatch " + id);
            // Test the public behavior, not a private representation or just a source declaration.
            helper.assertTrue(sound.getRange(0.5f) == 16.0f && sound.getRange(1.0f) == 16.0f
                            && sound.getRange(2.0f) == 32.0f,
                    "sound does not use Minecraft variable-range behavior " + id);
        }
        helper.succeed();
    }
}
