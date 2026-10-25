package dev.bwr.mod.devtest;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.isotope.IsotopeSeparatorBlockEntity;
import dev.bwr.mod.isotope.RtgBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * The computer faces of the RTG and of the isotope separator. They touch CC:Tweaked types, so they live here and not in a
 * {@code @GameTestHolder} class: a run without CC:Tweaked never loads this class ({@link RtgTests#computerFaces} checks first).
 */
final class RtgCcChecks {

    private RtgCcChecks() {
    }

    static void run(GameTestHelper h) throws Exception {
        ServerLevel l = h.getLevel();
        BlockPos p = new BlockPos(16000, 210, 16500);
        l.getChunk(p);
        l.removeBlock(p, false);
        l.setBlock(p, BwrBlocks.RTG.get().defaultBlockState(), 2);
        var rtg = (RtgBlockEntity) l.getBlockEntity(p);
        var face = l.getCapability(PeripheralCapability.get(), p, Direction.UP);
        h.assertTrue(face != null && face.getType().equals("bwr_rtg"), "no bwr_rtg face: " + face);
        Map<?, ?> idle = status(face);
        h.assertTrue(num(idle, "capsules") == 0 && num(idle, "thermalW") == 0 && num(idle, "fePerTick") == 0, "empty RTG status: " + idle);
        for (int i = 0; i < 6; i++) {
            rtg.fuel().setStackInSlot(i, new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get()));
        }
        Map<?, ?> full = status(face);
        h.assertTrue(num(full, "capsules") == 6 && Math.abs(num(full, "thermalW") - 1380.0) < 1e-6
                && Math.abs(num(full, "electricW") - 69.0) < 1e-6 && Math.abs(num(full, "fePerTick") - 69.0 / 13.4) < 1e-6, "loaded RTG status: " + full);
        h.assertTrue(Math.abs(num(full, "efficiency") - 0.05) < 1e-12 && num(full, "outputMultiplier") == 1.0, "efficiency and multiplier: " + full);
        rtg.removalDrops();
        l.removeBlock(p, false);

        BlockPos sp = new BlockPos(16000, 210, 16510);
        l.getChunk(sp);
        l.removeBlock(sp, false);
        l.setBlock(sp, BwrBlocks.ISOTOPE_SEPARATOR.get().defaultBlockState(), 2);
        var sep = (IsotopeSeparatorBlockEntity) l.getBlockEntity(sp);
        var sepFace = l.getCapability(PeripheralCapability.get(), sp, Direction.UP);
        h.assertTrue(sepFace != null && sepFace.getType().equals("bwr_isotope_separator"), "no bwr_isotope_separator face: " + sepFace);
        sep.addWaste(1_000_000L);
        Map<?, ?> st = status(sepFace);
        h.assertTrue(Math.abs(num(st, "strontiumHeldG") - 109.725) < 1e-6 && Math.abs(num(st, "caesiumHeldG") - 181.335) < 1e-6
                && Math.abs(num(st, "americiumHeldG") - 75.0) < 1e-6, "separator status: " + st);
        sep.removalDrops();
        l.removeBlock(sp, false);
    }

    private static Map<?, ?> status(Object peripheral) throws Exception {
        Object[] result = PeripheralTestCalls.call(peripheral, "getStatus");
        return (Map<?, ?>) result[0];
    }

    private static double num(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number n)) {
            throw new AssertionError(key + " is not a number: " + value + " in " + map);
        }
        return n.doubleValue();
    }
}
