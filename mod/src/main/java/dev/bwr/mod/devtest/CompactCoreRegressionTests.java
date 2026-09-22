package dev.bwr.mod.devtest;

import dev.bwr.core.fuel.CompactCoreLayout;
import dev.bwr.mod.gui.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.*;
import dev.bwr.mod.rods.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class CompactCoreRegressionTests {
    private static void validate(ReactorControllerBlockEntity be) throws Exception {
        var method=ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate",Level.class);
        method.setAccessible(true);method.invoke(be,be.getLevel());
    }

    static ReactorControllerBlockEntity build(ServerLevel level,BlockPos min,int width,int depth,boolean legacy) throws Exception {
        return build(level,min,width,depth,8,legacy);
    }

    static ReactorControllerBlockEntity build(ServerLevel level,BlockPos min,int width,int depth,int height,boolean legacy) throws Exception {
        for(int x=(min.getX()-16)>>4;x<=(min.getX()+width+16)>>4;x++)
            for(int z=(min.getZ()-16)>>4;z<=(min.getZ()+depth+16)>>4;z++)level.getChunk(x,z);
        for(int x=-1;x<=width;x++)for(int z=-1;z<=depth;z++)for(int y=-2;y<=height;y++) {
            boolean shell=y>=-1&&(x==-1||x==width||z==-1||z==depth||y==-1||y==height);
            level.setBlock(min.offset(x,y,z),(shell?BwrBlocks.REACTOR_VESSEL.get():Blocks.AIR).defaultBlockState(),2);
        }
        var pos=min.offset(-1,3,depth/2);
        level.setBlock(pos,BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(),2);
        var be=(ReactorControllerBlockEntity)level.getBlockEntity(pos);
        if(legacy) be.loadWithComponents(new CompoundTag(),level.registryAccess()); // Pre-version save.
        if(legacy) {
            for(int x=1;x<width;x+=2)for(int z=1;z<depth;z+=2)
                level.setBlock(min.offset(x,-2,z),BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),2);
        } else for(var drive:new CompactCoreLayout(width,depth).drives())
            level.setBlock(min.offset(drive.x(),-2,drive.z()),BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),2);
        validate(be);
        return be;
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void compactPhysicalDrivesAndSnapshots(GameTestHelper h) throws Exception {
        var level=h.getLevel();var min=new BlockPos(4400,190,4400);
        var be=build(level,min,15,15,false);
        h.assertTrue(be.isFormed(),"compact reference failed: "+be.statusLines());
        h.assertTrue(be.assemblyCount()==764&&be.core().getControlRodCount()==185,"reference count mismatch");
        h.assertTrue(!be.loadAssembly(0,new ItemStack(BwrItems.FUEL_ASSEMBLY.get())),"fuel accepted outside mask");
        var layout=new CompactCoreLayout(15,15);
        for(int r=0;r<185;r++) {
            var drive=(ControlRodDriveBlockEntity)level.getBlockEntity(be.structure().crdPositions().get(r));
            h.assertTrue(drive.rodIndex()==r,"physical drive ID mismatch");
            h.assertTrue(java.util.Arrays.equals(layout.rodMap().positionsOfRod(r),be.core().getNodalFluxSolver().rodLatticeMap().positionsOfRod(r)),"physical/physics mapping mismatch");
        }
        int slot=be.corePositions()[763];
        var exposure=new dev.bwr.mod.fuel.FuelAssemblyData("leu",.04,180,12345,.31);
        be.loadAssembly(slot,dev.bwr.mod.fuel.FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(),exposure));
        // Actual server menu wire format, including the sparse physical-drive grid.
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        var menu=new ReactorPanelMenu(1,player.getInventory(),be.getBlockPos());
        var writer=ReactorPanelMenu.class.getDeclaredMethod("writeSnapshot",FriendlyByteBuf.class);
        var reader=ReactorPanelMenu.class.getDeclaredMethod("readSnapshot",FriendlyByteBuf.class);
        writer.setAccessible(true);reader.setAccessible(true);
        var buf=new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            writer.invoke(menu,buf);reader.invoke(menu,buf);
            h.assertTrue(buf.readableBytes()==0&&menu.map.coreSlotCount==764,"menu snapshot desynchronised");
            h.assertTrue(java.util.Arrays.equals(menu.map.latticeIndex,be.corePositions()),"GUI fuel map differs from physics");
            for(int r=0;r<185;r++)h.assertTrue(menu.rodLatticeIndices[r]==layout.drives().get(r).z()*15+layout.drives().get(r).x(),"GUI drive placed incorrectly");
        } finally {buf.release();}
        var before=be.core();
        var removed=be.structure().crdPositions().get(184);
        level.removeBlock(removed,false);validate(be);
        h.assertTrue(!be.isFormed()&&be.core()==before,"missing drive discarded the core");
        h.assertTrue(be.statusLines().stream().anyMatch(s->s.contains("missing control rod drive")),"missing drive not diagnosed");
        level.setBlock(removed,BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),2);validate(be);
        h.assertTrue(be.isFormed()&&be.core()==before,"repair reinitialised the core");
        be.rods().setNotchLabelDemand(184,28);
        for(int i=0;i<5;i++) {be.rods().preStep(.05);be.core().step();be.rods().postStep();}
        double moving=be.core().getRodPositionNotches(184);
        h.assertTrue(moving>0&&moving<14,"compact blade jumped directly to the demanded notch");
        var snapshot=be.saveWithFullMetadata(level.registryAccess());
        var p=be.getBlockPos();var state=be.getBlockState();level.removeBlockEntity(p);
        var restored=(ReactorControllerBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(p,state,snapshot,level.registryAccess());
        level.setBlockEntity(restored);validate(restored);
        h.assertTrue(restored.isFormed()&&restored.coreLayoutVersion()==2&&restored.assemblyCount()==764,"saved compact core changed layout");
        h.assertTrue(restored.core().getRodPositionNotches(184)==moving&&restored.rods().getCommandedNotchLabel(184)==28,"reload lost physical motion or standing demand");
        var recovered=restored.takeFuelForRemoval();
        h.assertTrue(recovered.size()==1&&dev.bwr.mod.fuel.FuelAssemblies.dataOf(recovered.getFirst()).equals(exposure),"reload lost peripheral bundle exposure");
        level.removeBlock(p,false);h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void legacyCoreAndBrokenSaveKeepTheirLayout(GameTestHelper h) throws Exception {
        var level=h.getLevel();var be=build(level,new BlockPos(4450,190,4450),15,15,true);
        h.assertTrue(be.isFormed()&&be.assemblyCount()==225&&be.core().getControlRodCount()==49,"legacy drive layout changed");
        var fuel=new ItemStack(BwrItems.FUEL_ASSEMBLY.get());be.loadAssembly(be.corePositions()[224],fuel);
        be.rods().setNotchLabelDemand(0,28);
        var saved=be.saveWithFullMetadata(level.registryAccess());
        saved.remove("CoreLayoutVersion");saved.remove("CoreInteriorWidth");saved.remove("CoreInteriorDepth");
        var p=be.getBlockPos();var state=be.getBlockState();
        var floor=be.structure().interiorMin().below();level.removeBlock(floor,false);level.removeBlockEntity(p);
        var restored=(ReactorControllerBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(p,state,saved,level.registryAccess());
        level.setBlockEntity(restored);validate(restored);
        h.assertTrue(!restored.isFormed()&&restored.coreLayoutVersion()==1,"broken legacy core was converted");
        saved=restored.saveWithFullMetadata(level.registryAccess());level.removeBlockEntity(p);
        restored=(ReactorControllerBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(p,state,saved,level.registryAccess());
        level.setBlockEntity(restored);level.setBlock(floor,BwrBlocks.REACTOR_VESSEL.get().defaultBlockState(),2);validate(restored);
        h.assertTrue(restored.isFormed()&&restored.assemblyCount()==225&&restored.rods().getCommandedNotchLabel(0)==28,"legacy demand or capacity lost");
        h.assertTrue(restored.core().getCoreLoading().loadedAssemblyCount()==1,"legacy inventory lost");
        level.removeBlock(p,false);h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void compactMaximumAndManifold(GameTestHelper h) throws Exception {
        var level=h.getLevel();var be=build(level,new BlockPos(4500,190,4500),21,21,false);
        h.assertTrue(be.isFormed()&&be.assemblyCount()==1476&&be.core().getControlRodCount()==357,"maximum layout is capped");
        var a=new ControlRodDriveHardware();var b=new ControlRodDriveHardware();
        a.setEnergyStoredFe(500);b.setEnergyStoredFe(0);a.setWaterStoredMb(0);b.setWaterStoredMb(400);
        b.setHealth(.4);a.shareSuppliesWith(b);
        h.assertTrue(a.getEnergyStoredFe()+b.getEnergyStoredFe()==500&&a.getWaterStoredMb()+b.getWaterStoredMb()==400,"manifold created inventory");
        h.assertTrue(b.getHealth()==.4,"sharing repaired wear");
        var drives=be.structure().crdPositions();
        // Empty every drive, then supply opposite inventories to touching cells.
        for(var pos:drives) {
            var hardware=((ControlRodDriveBlockEntity)level.getBlockEntity(pos)).hardware();
            hardware.setEnergyStoredFe(0);hardware.setWaterStoredMb(0);
        }
        var first=drives.get(0);var second=first.south();
        var da=(ControlRodDriveBlockEntity)level.getBlockEntity(first);
        var db=(ControlRodDriveBlockEntity)level.getBlockEntity(second);
        da.energy().receiveEnergy(10000,false);
        db.water().fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,4000),net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);
        ControlRodDriveBlockEntity.serverTick(level,first,da.getBlockState(),da);
        ControlRodDriveBlockEntity.serverTick(level,second,db.getBlockState(),db);
        h.assertTrue(da.hardware().canPerformNormalMotion()&&db.hardware().canPerformNormalMotion(),"touching compact drives cannot share bottom-face supplies");
        level.removeBlock(be.getBlockPos(),false);h.succeed();
    }
}
