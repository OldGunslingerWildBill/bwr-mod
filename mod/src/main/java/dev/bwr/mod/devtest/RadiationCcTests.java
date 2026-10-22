package dev.bwr.mod.devtest;

import dev.bwr.mod.BwrMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The area radiation monitor's CC:Tweaked peripheral. This holder names no CC type: the checks live in
 * {@link RadiationCcChecks} and run only when CC:Tweaked is present, so the no-CC build (-PbwrNoCC) still passes.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class RadiationCcTests {
    private RadiationCcTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theMonitorsPeripheralReportsTheDoseRateAndTheAlarm(GameTestHelper helper) {
        if (!BwrMod.isComputerCraftPresent()) {
            helper.succeed();
            return;
        }
        RadiationCcChecks.run(helper);
    }
}
