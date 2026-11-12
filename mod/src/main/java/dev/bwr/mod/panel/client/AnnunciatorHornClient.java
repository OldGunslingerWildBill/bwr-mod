package dev.bwr.mod.panel.client;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.component.AnnunciatorData;
import dev.bwr.mod.panel.component.AnnunciatorHorns;
import dev.bwr.mod.panel.component.AnnunciatorSounds;
import dev.bwr.mod.panel.wiring.PanelNetworkIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Plays the annunciator horn: while any loaded window of a panel group is audible (a new alarm nobody has
 * acknowledged or silenced, {@link AnnunciatorHorns}), one looping {@link AnnunciatorHornSound} at that group for each
 * sound its audible windows are set to (the panel alarm, the synthesised horn or a custom sound), so that two alarms
 * with different sounds are both heard. A custom sound this client does not have is the panel alarm. It
 * looks at the client's loaded panels every few ticks, starts a loop for a group that began sounding, moves the loop
 * with the group's sounding blocks and lets it go when none is left: acknowledged or silenced (the server's full
 * state carries it), the panel unloaded or broken (it leaves the set of loaded panels), or the level gone.
 *
 * <p>Nothing here may break the game: a failure stops every horn, is logged once and turns the horn off for the
 * session.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class AnnunciatorHornClient {
    /** Client ticks between two looks at the panels. */
    static final int PERIOD = 4;

    /** The loops playing, by {@link AnnunciatorHorns.Horn#key()}: one per panel group and sound. */
    private static final Map<String, AnnunciatorHornSound> PLAYING = new HashMap<>();
    private static Level lastLevel;
    private static int age;
    private static boolean broken;

    private AnnunciatorHornClient() {
    }

    /** How many horns are playing, for checks. */
    public static int playing() {
        return PLAYING.size();
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (broken) {
            return;
        }
        try {
            advance(Minecraft.getInstance());
        } catch (Throwable failure) {
            broken = true;
            LogUtils.getLogger().error("The annunciator horn failed and is off for this session", failure);
            stopAll();
        }
    }

    @SubscribeEvent
    public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        stopAll();
        lastLevel = null;
    }

    private static void advance(Minecraft mc) {
        Level level = mc.level;
        if (level != lastLevel) {
            // another world or dimension: the horns of the one left behind are not here
            stopAll();
            lastLevel = level;
        }
        if (level == null || mc.isPaused() || ++age % PERIOD != 0) {
            return;
        }
        Map<String, Boolean> known = new HashMap<>();
        Map<String, AnnunciatorHorns.Horn> wanted = AnnunciatorHorns.sounding(PanelNetworkIndex.clientPanels(), level,
                data -> heard(mc, data, known));
        for (Iterator<Map.Entry<String, AnnunciatorHornSound>> it = PLAYING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, AnnunciatorHornSound> entry = it.next();
            AnnunciatorHorns.Horn horn = wanted.get(entry.getKey());
            if (horn == null || entry.getValue().isStopped() || !mc.getSoundManager().isActive(entry.getValue())) {
                // No longer wanted, or no longer playing (the Blocks volume went to zero, the sound engine reloaded):
                // a loop that is still wanted starts again below, and is heard once it can be.
                entry.getValue().release();
                it.remove();
            } else {
                entry.getValue().keep(horn.x(), horn.y(), horn.z());
            }
        }
        for (AnnunciatorHorns.Horn horn : wanted.values()) {
            if (!PLAYING.containsKey(horn.key())) {
                AnnunciatorHornSound sound = new AnnunciatorHornSound(horn.sound(), horn.x(), horn.y(), horn.z());
                PLAYING.put(horn.key(), sound);
                mc.getSoundManager().play(sound);
            }
        }
    }

    /**
     * The sound a window is heard with here: what its setting stands for, unless this client's sound manager does not
     * know that sound (a custom id no resource pack here defines), which is then the panel alarm. Never silence.
     * {@code known} remembers the answers for the sounds met in one look at the panels.
     */
    private static String heard(Minecraft mc, AnnunciatorData data, Map<String, Boolean> known) {
        String id = data.soundId(PanelConfig.customAlarmSounds());
        if (AnnunciatorSounds.PANEL_ID.equals(id)) {
            return id;
        }
        Boolean has = known.get(id);
        if (has == null) {
            has = knows(mc, id);
            known.put(id, has);
        }
        return has ? id : AnnunciatorSounds.PANEL_ID;
    }

    private static boolean knows(Minecraft mc, String id) {
        try {
            ResourceLocation location = ResourceLocation.tryParse(id);
            return location != null && mc.getSoundManager().getSoundEvent(location) != null;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** Lets every horn go (also when the player leaves). Never throws. */
    public static void stopAll() {
        try {
            for (AnnunciatorHornSound sound : PLAYING.values()) {
                sound.release();
                Minecraft.getInstance().getSoundManager().stop(sound);
            }
        } catch (Throwable ignored) {
            // the sound engine is gone or refuses: a released loop stops itself on its next tick
        } finally {
            PLAYING.clear();
        }
    }
}
