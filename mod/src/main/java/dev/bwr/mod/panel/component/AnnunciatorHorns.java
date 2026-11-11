package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which annunciator horns sound: one per panel group that has a window whose alarm is still
 * {@linkplain AnnunciatorLogic#audible() audible}, placed in the middle of the group's blocks that hold such a window.
 * It only reads the panels it is given, so the client's horn player and a test on the server both use it.
 */
public final class AnnunciatorHorns {
    /** One group's horn: {@code key} names the group (its first block), the rest is where it sounds. */
    public record Horn(BlockPos key, double x, double y, double z) {
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

    /** The horns of {@code panels} that are in {@code level} and not removed, by group. */
    public static Map<BlockPos, Horn> sounding(Iterable<PanelBlockEntity> panels, Level level) {
        Map<BlockPos, double[]> sums = new LinkedHashMap<>();
        for (PanelBlockEntity panel : panels) {
            if (panel == null || panel.isRemoved() || panel.getLevel() != level || !audible(panel)) {
                continue;
            }
            BlockPos pos = panel.getBlockPos();
            double[] sum = sums.computeIfAbsent(key(panel), k -> new double[4]);
            sum[0] += pos.getX() + 0.5;
            sum[1] += pos.getY() + 0.5;
            sum[2] += pos.getZ() + 0.5;
            sum[3]++;
        }
        Map<BlockPos, Horn> horns = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, double[]> entry : sums.entrySet()) {
            double[] sum = entry.getValue();
            horns.put(entry.getKey(), new Horn(entry.getKey(), sum[0] / sum[3], sum[1] / sum[3], sum[2] / sum[3]));
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
