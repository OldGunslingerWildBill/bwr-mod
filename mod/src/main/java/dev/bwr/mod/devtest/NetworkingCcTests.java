package dev.bwr.mod.devtest;

import dev.bwr.mod.BwrMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Part 7 on a real CC:Tweaked wired network. This holder names no CC type: the checks live in {@link NetworkingCcChecks}
 * and run only when CC:Tweaked is present, so the no-CC build (-PbwrNoCC) still builds and passes.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class NetworkingCcTests {
    private NetworkingCcTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void ccPortsFindTheRightMachineOnARealWiredNetwork(GameTestHelper helper) {
        if (!BwrMod.isComputerCraftPresent()) {
            helper.succeed();
            return;
        }
        NetworkingCcChecks.run(helper);
    }
}
