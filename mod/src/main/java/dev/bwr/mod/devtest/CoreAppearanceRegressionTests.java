package dev.bwr.mod.devtest;

import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class CoreAppearanceRegressionTests {
    @GameTest(template="empty",timeoutTicks=400)
    public static void coreVisualsFollowInventoryAndLegacyLayout(GameTestHelper h)throws Exception {
        int[][] sizes={{5,5},{15,15},{21,21},{7,11}};
        for(int k=0;k<sizes.length;k++) {
            var min=new BlockPos(5100+k*50,190,5100);
            var be=CompactCoreRegressionTests.build(h.getLevel(),min,sizes[k][0],sizes[k][1],false);
            var initial=VesselCoreAppearance.capture(be);
            h.assertTrue(initial.cells().size()==be.assemblyCount(),"visual capacity differs from core");
            h.assertTrue(initial.cells().stream().allMatch(c->c.content()==0),"empty core invented fuel");
            h.assertTrue(initial.drives().size()==be.core().getControlRodCount(),"drive count mismatch");
            int slot=be.corePositions()[0];be.loadAssembly(slot,new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
            var tag=be.getUpdateTag(h.getLevel().registryAccess());var env=VesselAppearance.read(tag);
            var loaded=VesselCoreAppearance.read(tag,env);
            h.assertTrue(loaded.equals(VesselCoreAppearance.capture(be)),"visual NBT roundtrip changed core");
            h.assertTrue(loaded.cells().stream().filter(c->c.content()==1).count()==1,"loaded assembly not shown");
            be.unloadAssembly(slot);
            h.assertTrue(initial.equals(VesselCoreAppearance.capture(be)),"unload left a ghost bundle");
            be.loadAssembly(slot,new ItemStack(BwrItems.SPECIALTY_ROD.get()));
            h.assertTrue(VesselCoreAppearance.capture(be).cells().stream().filter(c->c.content()==2).count()==1,"specialty insert missing");
            var live=be.getUpdateTag(h.getLevel().registryAccess());
            var blades=live.getIntArray("VisualBladeInsertion");
            h.assertTrue(blades.length==initial.drives().size()&&blades[0]==65535,"fully inserted blade snapshot wrong");
            tag.putIntArray("VisualCoreCells",new int[]{-1});
            h.assertTrue(VesselCoreAppearance.read(tag,env)==null,"malformed visual index accepted");
            h.assertTrue(VesselCoreAppearance.read(new CompoundTag(),env)==null,"old packet invented a core");
            h.getLevel().removeBlock(be.getBlockPos(),false);
        }
        var old=CompactCoreRegressionTests.build(h.getLevel(),new BlockPos(5350,190,5100),15,15,true);
        var legacy=VesselCoreAppearance.capture(old);
        h.assertTrue(legacy.layoutVersion()==1&&legacy.cells().size()==225&&legacy.drives().size()==49,"legacy visual converted to compact");
        h.getLevel().removeBlock(old.getBlockPos(),false);h.succeed();
    }
}
