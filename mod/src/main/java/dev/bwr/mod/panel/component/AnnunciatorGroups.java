package dev.bwr.mod.panel.component;

import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Group-level ACK and RESET: every annunciator window of one number in the
 * panel group that contains {@code from}. Only loaded blocks are visited (a
 * chunk is never loaded for this), and every block whose windows changed is
 * saved and re-sent to watchers once.
 */
public final class AnnunciatorGroups {
    private AnnunciatorGroups() {
    }

    /** Acknowledges every alarming window of {@code number}; returns how many changed. */
    public static int acknowledge(ServerLevel level, PanelBlockEntity from, int number) {
        return apply(level, from, number, true);
    }

    /** Resets every acknowledged, cleared window of {@code number}; returns how many changed. */
    public static int reset(ServerLevel level, PanelBlockEntity from, int number) {
        return apply(level, from, number, false);
    }

    private static int apply(ServerLevel level, PanelBlockEntity from, int number, boolean acknowledge) {
        PanelGroup group = from.group();
        List<BlockPos> members = group == null ? List.of(from.getBlockPos()) : group.members();
        int changed = 0;
        List<PanelBlockEntity> touched = new ArrayList<>();
        for (BlockPos pos : members) {
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof PanelBlockEntity be)) {
                continue;
            }
            boolean any = false;
            for (PanelComponent component : be.owned()) {
                if (component.type() instanceof AnnunciatorType && component.data() instanceof AnnunciatorData data
                        && data.group == number && (acknowledge ? data.logic.acknowledge() : data.logic.reset())) {
                    component.touch();
                    changed++;
                    any = true;
                }
            }
            if (any) {
                touched.add(be);
            }
        }
        for (PanelBlockEntity be : touched) {
            be.syncFull();
        }
        return changed;
    }
}
