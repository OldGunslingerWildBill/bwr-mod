package dev.bwr.mod.containment;

import dev.bwr.core.accident.ContainmentAtmosphere;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Loaded equipment only. Room and pool ownership are checked live before use. */
public final class ContainmentEquipmentRegistry {
    private static final Map<Level, Set<BlockPos>> ACTIVE = new WeakHashMap<>();
    private ContainmentEquipmentRegistry() {}

    static void register(ContainmentEquipmentBlockEntity equipment) {
        Level level = equipment.getLevel();
        if (level != null && !level.isClientSide()) ACTIVE.computeIfAbsent(level, ignored -> new HashSet<>())
                .add(equipment.getBlockPos().immutable());
    }
    static void unregister(ContainmentEquipmentBlockEntity equipment) {
        Set<BlockPos> positions = ACTIVE.get(equipment.getLevel());
        if (positions != null) positions.remove(equipment.getBlockPos());
    }
    public static ContainmentEquipmentBlockEntity find(ContainmentControllerBlockEntity room, boolean vent) {
        if (room == null || room.getLevel() == null) return null;
        Level level = room.getLevel();
        Set<BlockPos> positions = ACTIVE.get(level);
        if (positions == null) return null;
        for (BlockPos pos : positions) {
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockEntity(pos) instanceof ContainmentEquipmentBlockEntity equipment
                    && !equipment.isRemoved() && equipment.isVent() == vent && equipment.ready(room)) return equipment;
        }
        return null;
    }
    public static boolean command(ReactorControllerBlockEntity reactor, boolean vent, boolean demand) {
        if (reactor.getLevel() == null || reactor.core() == null) return false;
        var room = ContainmentControllerRegistry.linkedReactor(reactor.getLevel(), reactor.getBlockPos());
        var equipment = find(room, vent);
        if (equipment == null) return false;
        equipment.setDemand(demand);
        if (vent) reactor.core().getContainment().setVentOpen(demand);
        else if (demand) reactor.core().getContainment().setInertingCommanded(true);
        else reactor.core().getContainment().stopPurge();
        return true;
    }
    public static void apply(ReactorControllerBlockEntity reactor, ContainmentControllerBlockEntity room) {
        ContainmentAtmosphere atmosphere = reactor.core().getContainment();
        var nitrogen = find(room, false);
        if (nitrogen != null && nitrogen.active(room) && !atmosphere.isInerted()) {
            atmosphere.setInertingCommanded(true);
            nitrogen.spendTick();
        } else if (atmosphere.isInertingCommanded()) atmosphere.stopPurge();
        var vent = find(room, true);
        boolean open = vent != null && vent.active(room);
        atmosphere.setVentOpen(open);
        if (open) vent.spendTick();
    }
}
