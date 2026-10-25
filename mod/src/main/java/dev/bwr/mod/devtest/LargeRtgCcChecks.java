package dev.bwr.mod.devtest;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import dev.bwr.mod.isotope.LargeRtgBlockEntity;
import dev.bwr.mod.isotope.RtgBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;

import java.util.Map;

/**
 * The computer faces of the Large RTG and of the small RTG's heat-only switch. They touch CC:Tweaked types, so they live
 * here and not in a {@code @GameTestHolder} class: a run without CC:Tweaked never loads this class
 * ({@link LargeRtgTests#computerFaces} checks first).
 */
final class LargeRtgCcChecks {

    private LargeRtgCcChecks() {
    }

    static void run(GameTestHelper h) throws Exception {
        ServerLevel l = h.getLevel();
        BlockPos root = new BlockPos(7400, 200, 7400);
        l.getChunk(root);
        for (var p : BlockPos.betweenClosed(root.offset(-2, -1, -2), root.offset(3, 5, 3))) {
            l.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        }
        var item = new ItemStack(BwrBlocks.LARGE_RTG.get());
        ((net.minecraft.world.item.BlockItem) item.getItem()).place(new DirectionalPlaceContext(l, root, Direction.SOUTH, item, Direction.UP));
        var rtg = (LargeRtgBlockEntity) l.getBlockEntity(root);
        // a part cell is a computer face too, and it is the controller's
        BlockPos part = root.offset(1, 2, 1);
        var face = l.getCapability(PeripheralCapability.get(), part, Direction.UP);
        h.assertTrue(face != null && face.getType().equals("bwr_large_rtg"), "no bwr_large_rtg face on a part cell: " + face);
        Map<?, ?> idle = status(face);
        h.assertTrue(num(idle, "massKg") == 0 && num(idle, "thermalW") == 0 && num(idle, "fePerTick") == 0 && Boolean.FALSE.equals(idle.get("heatOnly")), "empty status: " + idle);
        rtg.loadFrom(40, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get(), 40));
        rtg.loadFrom(2, new ItemStack(BwrItems.CAESIUM_137_BLOCK.get(), 2));
        Map<?, ?> full = status(face);
        double thermal = 40 * 230.0 + 2 * 2250.0 * 0.42;
        h.assertTrue(Math.abs(num(full, "massKg") - 14.5) < 1e-9 && Math.abs(num(full, "thermalW") - thermal) < 1e-6
                && Math.abs(num(full, "electricW") - thermal * 0.05) < 1e-6 && Math.abs(num(full, "fePerTick") - thermal * 0.05 / 13.4) < 1e-6
                && num(full, "capacityKg") == 1000.0, "loaded status: " + full);
        h.assertTrue(full.get("kgByIsotope") instanceof Map<?, ?> kg && kg.get("Sr-90 with Y-90") instanceof Number n && Math.abs(n.doubleValue() - 10.0) < 1e-9,
                "kgByIsotope: " + full.get("kgByIsotope"));
        Object[] set = PeripheralTestCalls.call(face, "setHeatOnly", true);
        h.assertTrue(Boolean.TRUE.equals(set[0]) && rtg.heatOnly(), "setHeatOnly(true) did not switch it: " + java.util.Arrays.toString(set));
        Map<?, ?> heat = status(face);
        h.assertTrue(num(heat, "fePerTick") == 0 && num(heat, "electricW") == 0 && Math.abs(num(heat, "thermalW") - thermal) < 1e-6 && Boolean.TRUE.equals(heat.get("heatOnly")),
                "heat-only status: " + heat);
        PeripheralTestCalls.call(face, "setHeatOnly", false);
        h.assertTrue(!rtg.heatOnly(), "setHeatOnly(false) did not switch it back");
        rtg.removalDrops();
        l.destroyBlock(root, false);

        BlockPos sp = new BlockPos(7400, 200, 7410);
        l.getChunk(sp);
        l.removeBlock(sp, false);
        l.setBlock(sp, BwrBlocks.RTG.get().defaultBlockState(), 2);
        var small = (RtgBlockEntity) l.getBlockEntity(sp);
        var sface = l.getCapability(PeripheralCapability.get(), sp, Direction.UP);
        h.assertTrue(sface != null && sface.getType().equals("bwr_rtg"), "no bwr_rtg face: " + sface);
        h.assertTrue(Boolean.FALSE.equals(status(sface).get("heatOnly")), "the small RTG's status has no heatOnly");
        PeripheralTestCalls.call(sface, "setHeatOnly", true);
        h.assertTrue(small.heatOnly() && Boolean.TRUE.equals(status(sface).get("heatOnly")), "the small RTG's heat-only switch");
        small.removalDrops();
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
