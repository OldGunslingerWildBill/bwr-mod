package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.PanelGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Which annunciator horns sound: one per panel group and sound, for every sound that a window of the group whose
 * alarm is still {@linkplain AnnunciatorLogic#audible() audible} is set to ({@link AnnunciatorSounds}), placed in the
 * middle of the group's blocks that hold such a window. Two audible windows with different sounds are two horns, so
 * both are heard; any number with the same sound are one. It only reads the panels it is given, so the client's horn
 * player and a test on the server both use it.
 */
public final class AnnunciatorHorns {
    /**
     * One horn.
     *
     * @param group the panel group it belongs to, named by the group's first block
     * @param sound the id of the sound it plays ({@code namespace:path})
     */
    public record Horn(BlockPos group, String sound, double x, double y, double z) {
        /** What tells this horn from every other: its group and its sound. */
        public String key() {
            return key(group, sound);
        }

        static String key(BlockPos group, String sound) {
            return group.asLong() + "|" + sound;
        }
    }

    private AnnunciatorHorns() {
    }

    /** Whether any window this block owns is sounding. */
    public static boolean audible(PanelBlockEntity panel) {
        for (PanelComponent component : panel.owned()) {
            if (component.data() instanceof AnnunciatorData data && data.logic.audible()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The horns of {@code panels} that are in {@code level} and not removed, by {@link Horn#key()}, each window
     * sounding what its setting stands for (a custom sound only while the server allows them).
     */
    public static Map<String, Horn> sounding(Iterable<PanelBlockEntity> panels, Level level) {
        boolean custom = PanelConfig.customAlarmSounds();
        return sounding(panels, level, data -> data.soundId(custom));
    }

    /**
     * The same with the caller's own word on what each window sounds (a client leaves out the sounds it does not
     * have). A window for which {@code sound} gives nothing, or fails, sounds the panel alarm.
     */
    public static Map<String, Horn> sounding(Iterable<PanelBlockEntity> panels, Level level, Function<AnnunciatorData, String> sound) {
        Map<String, double[]> sums = new LinkedHashMap<>();
        Map<String, Horn> first = new LinkedHashMap<>();
        for (PanelBlockEntity panel : panels) {
            if (panel == null || panel.isRemoved() || panel.getLevel() != level) {
                continue;
            }
            BlockPos group = null;
            Set<String> counted = null; // the horns this block has been counted for: once each, however many windows sound it
            for (PanelComponent component : panel.owned()) {
                if (!(component.data() instanceof AnnunciatorData data) || !data.logic.audible()) {
                    continue;
                }
                if (group == null) {
                    group = key(panel);
                    counted = new HashSet<>();
                }
                String id;
                try {
                    id = sound.apply(data);
                } catch (RuntimeException failure) {
                    id = null;
                }
                if (id == null || id.isEmpty()) {
                    id = AnnunciatorSounds.PANEL_ID;
                }
                String key = Horn.key(group, id);
                double[] sum = sums.get(key);
                if (sum == null) {
                    sum = new double[4];
                    sums.put(key, sum);
                    first.put(key, new Horn(group, id, 0, 0, 0));
                }
                if (counted.add(key)) {
                    BlockPos pos = panel.getBlockPos();
                    sum[0] += pos.getX() + 0.5;
                    sum[1] += pos.getY() + 0.5;
                    sum[2] += pos.getZ() + 0.5;
                    sum[3]++;
                }
            }
        }
        Map<String, Horn> horns = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : sums.entrySet()) {
            double[] sum = entry.getValue();
            Horn horn = first.get(entry.getKey());
            horns.put(entry.getKey(), new Horn(horn.group(), horn.sound(), sum[0] / sum[3], sum[1] / sum[3], sum[2] / sum[3]));
        }
        return horns;
    }

    /** The group's first block, or the block itself when it has no group (or the group cannot be worked out). */
    private static BlockPos key(PanelBlockEntity panel) {
        try {
            PanelGroup group = panel.group();
            if (group != null && !group.members().isEmpty()) {
                return group.members().get(0).immutable();
            }
        } catch (RuntimeException ignored) {
            // a group that cannot be computed right now: the block sounds on its own
        }
        return panel.getBlockPos().immutable();
    }
}
