package dev.bwr.mod.compat.coldsweat;

import com.mojang.logging.LogUtils;
import com.momosoftworks.coldsweat.api.event.core.registry.BlockTempRegisterEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The Cold Sweat integration (optional). This is the only package that names a Cold Sweat type, and {@code BwrMod} calls
 * {@link #register()} only when {@code cold_sweat} is loaded, so a JVM without Cold Sweat never resolves any of it.
 *
 * <p>It uses Cold Sweat's Java API (2.4.3 and later): a {@code BlockTemp} registered on {@code BlockTempRegisterEvent}.
 * The datapack and config route (a {@code block_temperatures} entry) gives a block one fixed temperature and cannot read a
 * block entity, and an RTG's heat is its thermal watts, which change with what is loaded; so the dynamic API is the clean one.
 * Any failure (an older or newer Cold Sweat whose API differs) is logged and leaves the RTGs as plain blocks.
 */
public final class ColdSweatCompat {

    private ColdSweatCompat() {
    }

    public static void register() {
        try {
            NeoForge.EVENT_BUS.addListener(BlockTempRegisterEvent.class, ColdSweatCompat::onRegister);
        } catch (Throwable t) {
            LogUtils.getLogger().warn("Realistic BWR: Cold Sweat is present but its BlockTemp API did not match; RTGs will not warm players", t);
        }
    }

    private static void onRegister(BlockTempRegisterEvent event) {
        try {
            event.register(new RtgBlockTemp());
            LogUtils.getLogger().info("Realistic BWR: the RTG and the Large RTG are Cold Sweat heat sources");
        } catch (Throwable t) {
            LogUtils.getLogger().warn("Realistic BWR: could not register the RTG heat sources with Cold Sweat", t);
        }
    }
}
