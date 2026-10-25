package dev.bwr.mod.devtest;

import dev.bwr.mod.reactor.RpvWaterInjectionPortBlock;
import dev.bwr.mod.reactor.VesselAppearance;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The recirculation nozzles' look (RECIRC-REMODEL.md): the vessel tells the client which wall ports are recirculation outlets and
 *  inlets, so they get their own forgings; a wrongly faced one still shows as a plain block, and the older kinds keep their wire codes. */
@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class RecircNozzleTests {
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void recirculationPortsAreTypedOnTheWire(GameTestHelper h) throws Exception {
        var l = h.getLevel();
        var min = new BlockPos(63300, 190, 63300);
        var be = CompactCoreRegressionTests.build(l, min, 15, 15, 20, false);
        var outlet = min.offset(7, 12, -1);
        var inlet = min.offset(4, 1, -1);
        var backwards = min.offset(10, 1, -1);
        l.setBlock(outlet, BwrBlocks.RECIRCULATION_OUTLET.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING, Direction.NORTH), 2);
        l.setBlock(inlet, BwrBlocks.RECIRCULATION_INLET.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING, Direction.NORTH), 2);
        l.setBlock(backwards, BwrBlocks.RECIRCULATION_INLET.get().defaultBlockState().setValue(RpvWaterInjectionPortBlock.FACING, Direction.SOUTH), 2);
        SpargerRegressionTests.validate(be);
        h.assertTrue(be.isFormed(), "Recirculation ports broke formation");
        var e = VesselAppearance.read(be.getUpdateTag(l.registryAccess()));
        h.assertTrue(e != null, "No vessel envelope on the wire");
        h.assertTrue(e.kind(outlet) == VesselAppearance.PortKind.RECIRC_OUTLET, "Recirculation outlet not typed: " + e.kind(outlet));
        h.assertTrue(e.kind(inlet) == VesselAppearance.PortKind.RECIRC_INLET, "Recirculation inlet not typed: " + e.kind(inlet));
        h.assertTrue(e.kind(backwards) == VesselAppearance.PortKind.LEGACY, "Wrongly faced recirculation inlet disguised as a nozzle");
        h.assertTrue(e.kind(outlet).isWater() && e.kind(inlet).isWater() && !VesselAppearance.PortKind.STEAM.isWater(), "Water service lost");
        h.assertTrue(VesselAppearance.PortKind.WATER.ordinal() == 2 && VesselAppearance.PortKind.STEAM.ordinal() == 3, "Older wire codes moved");
        h.succeed();
    }
}
