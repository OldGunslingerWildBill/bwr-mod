package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaException;
import dev.bwr.mod.peripheral.ReactorPeripheral;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * The CC half of {@link StartupDetectorLocationTests}. References CC classes, so callers must be behind a
 * CC-present check: a {@code catch (LuaException)} in the GameTest holder itself is resolved when the holder is
 * verified, and then the whole GameTest registration fails on a server without CC:Tweaked.
 */
final class StartupDetectorLocationCcChecks {
    private StartupDetectorLocationCcChecks() {}

    static void typicalPlacementMetadata(GameTestHelper h, ReactorControllerBlockEntity be) {
        try {
            var cc = new ReactorPeripheral(be).getDetectorLocations();
            h.assertTrue(cc.size() == 12 && ((java.util.Map<?, ?>) cc.get(1)).get("placement").equals("typical"),
                    "CC missing typical placement metadata");
        } catch (LuaException e) {
            throw new AssertionError(e);
        }
    }
}
