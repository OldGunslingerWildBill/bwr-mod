package dev.bwr.mod.devtest;

import dev.bwr.core.fuel.*;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.flow.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.*;
import net.neoforged.neoforge.capabilities.Capabilities;
import java.util.*;

@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class InternalPumpRegressionTests {
    static void validate(ReactorControllerBlockEntity be)throws Exception {
        var m=ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate",Level.class);m.setAccessible(true);m.invoke(be,be.getLevel());
    }
    static BlockPos install(ServerLevel level,BlockPos min,InternalPumpLayout.Mount mount,Direction facing) {
        var b=BwrBlocks.RIP_PUMP.get();var s=b.defaultBlockState().setValue(PumpAssemblyBlock.ASSEMBLED,true).setValue(PumpAssemblyBlock.FACING,facing);
        int dx=Integer.MAX_VALUE,dz=Integer.MAX_VALUE;
        for(int i=0;i<b.cellCount();i++){var o=TurbineAssemblyBlock.turn(b.cellOffset(i),facing);dx=Math.min(dx,o.getX());dz=Math.min(dz,o.getZ());}
        var root=min.offset(mount.x()-dx,-1,mount.z()-dz);
        for(int i=0;i<b.cellCount();i++)level.removeBlock(root.offset(TurbineAssemblyBlock.turn(b.cellOffset(i),facing)),false);
        for(int i=0;i<b.cellCount();i++)level.setBlock(root.offset(TurbineAssemblyBlock.turn(b.cellOffset(i),facing)),s.setValue(PumpAssemblyBlock.CELL,i),2);
        return root;
    }
    @GameTest(template="empty",timeoutTicks=400)
    public static void internalPumpPositionsScaleAndRespectFacing(GameTestHelper h)throws Exception {
        int k=0;
        for(int[] size:new int[][]{{5,5},{15,15},{21,21},{7,11}}) {
            var min=new BlockPos(5500+50*k++,190,5500);var l=h.getLevel();var be=CompactCoreRegressionTests.build(l,min,size[0],size[1],false);
            var base=new CompactCoreLayout(size[0],size[1]);var sites=InternalPumpLayout.mounts(size[0],size[1]);
            int i=0;for(var site:sites)install(l,min,site,Direction.from2DDataValue(i++%4));
            validate(be);h.assertTrue(be.isFormed(),"RIP formation failed: "+be.statusLines());
            var expected=new CompactCoreLayout(size[0],size[1],true);
            h.assertTrue(be.assemblyCount()==expected.assemblyCount()&&be.assemblyCount()<base.assemblyCount(),"core not reduced");
            h.assertTrue(be.structure().internalPumpPositions().size()==sites.size(),"mounts missed after rotation");
            for(var root:be.structure().internalPumpPositions())h.assertTrue(RecirculationNetwork.installedRip(l,be.structure(),root),"valid pump not bound");
            l.removeBlock(be.getBlockPos(),false);
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=400)
    public static void ripProtectsFuelAndPreservesDriveState(GameTestHelper h)throws Exception {
        var l=h.getLevel();var min=new BlockPos(5750,190,5500);var be=CompactCoreRegressionTests.build(l,min,15,15,false);
        var site=InternalPumpLayout.mounts(15,15).getFirst();var base=new CompactCoreLayout(15,15);
        var reduced=new CompactCoreLayout(15,15,true);
        var kept=new HashSet<Integer>();for(int p:reduced.fuelPositions())kept.add(p);
        int removed=Arrays.stream(base.fuelPositions()).filter(p->!kept.contains(p)).findFirst().orElseThrow();
        h.assertTrue(be.loadAssembly(removed,new ItemStack(BwrItems.FUEL_ASSEMBLY.get())),"test fuel failed to load");
        var root=install(l,min,site,Direction.WEST);validate(be);
        h.assertTrue(!be.isFormed()&&be.core().getCoreLoading().assemblyAt(removed)!=null,"pump deleted occupied fuel");
        // Restore construction and unload, just as the diagnostic asks the player to do.
        l.removeBlock(root,false);
        for(int x=0;x<2;x++)for(int z=0;z<2;z++) {
            l.setBlock(min.offset(site.x()+x,-1,site.z()+z),BwrBlocks.REACTOR_VESSEL.get().defaultBlockState(),2);
            l.setBlock(min.offset(site.x()+x,-2,site.z()+z),BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),2);
        }
        validate(be);be.unloadAssembly(removed);
        int survivor=base.drives().size()/2;var cell=base.drives().get(survivor);
        be.core().setRodNotchDemand(survivor,8);be.core().step();
        be.rods().setNotchDemand(survivor,12);
        double position=be.core().getRodPositionNotches(survivor),elapsed=be.core().toState().elapsedSeconds();
        root=install(l,min,site,Direction.SOUTH);validate(be);
        h.assertTrue(be.isFormed(),"RIP conversion failed after unloading: "+be.statusLines());
        int now=reduced.drives().indexOf(cell);
        h.assertTrue(now>=0&&be.core().getRodPositionNotches(now)==position,"rod position changed identity");
        h.assertTrue(be.rods().getCommandedNotchIndices()[now]==12,"rod demand changed identity");
        h.assertTrue(be.core().toState().elapsedSeconds()==elapsed,"conversion reset transient");
        var tag=be.saveWithFullMetadata(l.registryAccess());var state=be.getBlockState();var controller=be.getBlockPos();
        be.onChunkUnloaded();l.removeBlockEntity(controller);
        be=(ReactorControllerBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(controller,state,tag,l.registryAccess());l.setBlockEntity(be);validate(be);
        h.assertTrue(be.isFormed()&&be.core().getRodPositionNotches(now)==position&&be.rods().getCommandedNotchIndices()[now]==12,"RIP reload changed saved drive state");
        // Moving or adding pumps around the reserved ring must not punch new holes
        // or change the core diameter/drive identities.
        l.removeBlock(root,false);
        for(int x=0;x<2;x++)for(int z=0;z<2;z++) {
            l.setBlock(min.offset(site.x()+x,-1,site.z()+z),BwrBlocks.REACTOR_VESSEL.get().defaultBlockState(),2);
            l.setBlock(min.offset(site.x()+x,-2,site.z()+z),BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),2);
        }
        var movedSite=InternalPumpLayout.mounts(15,15).getLast();
        var movedLayout=new CompactCoreLayout(15,15,true);
        root=install(l,min,movedSite,Direction.EAST);validate(be);
        int moved=movedLayout.drives().indexOf(cell);
        h.assertTrue(reduced.drives().size()==movedLayout.drives().size()&&now==moved,"moving a pump changed the central core");
        h.assertTrue(be.isFormed()&&be.core().getRodPositionNotches(moved)==position&&be.rods().getCommandedNotchIndices()[moved]==12,"relocated RIP reassigned surviving rod state");
        l.removeBlock(root,false);
        for(int x=0;x<2;x++)for(int z=0;z<2;z++) {
            l.setBlock(min.offset(movedSite.x()+x,-1,movedSite.z()+z),BwrBlocks.REACTOR_VESSEL.get().defaultBlockState(),2);
            l.setBlock(min.offset(movedSite.x()+x,-2,movedSite.z()+z),BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),2);
        }
        validate(be);
        h.assertTrue(be.isFormed()&&be.assemblyCount()==base.assemblyCount(),"removing final RIP did not restore the ordinary core");
        h.assertTrue(be.core().getRodPositionNotches(survivor)==position&&be.rods().getCommandedNotchIndices()[survivor]==12,"expansion changed the surviving blade");
        int newDrive=base.drives().indexOf(new CompactCoreLayout.Drive(site.x(),site.z()));
        h.assertTrue(newDrive>=0&&be.core().getRodPositionNotches(newDrive)==0&&be.rods().getCommandedNotchIndices()[newDrive]==0,"restored drive must start fully inserted");
        h.assertTrue(be.core().toState().accumulatorCharge()[newDrive]==0,"restored drive invented accumulator water");
        l.removeBlock(controller,false);h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=300)
    public static void ripNeedsValidMountAndRealPower(GameTestHelper h)throws Exception {
        var l=h.getLevel();var min=new BlockPos(5800,190,5500);var be=CompactCoreRegressionTests.build(l,min,5,5,false);
        var site=InternalPumpLayout.mounts(5,5).getFirst();var root=install(l,min,site,Direction.NORTH);validate(be);
        h.assertTrue(be.isFormed(),"minimum mounted pump did not form");
        var pump=(RecirculationPumpBlockEntity)l.getBlockEntity(root);pump.setTargetSpeedFraction(1);
        RecirculationPumpBlockEntity.serverTick(l,root,pump.getBlockState(),pump);
        h.assertTrue(be.getBlockPos().equals(pump.getControllerPos()),"pump did not discover vessel");
        h.assertTrue(RecirculationNetwork.measure(l,be,List.of(root)).fraction()==0,"unpowered pump invented flow");
        var b=BwrBlocks.RIP_PUMP.get();var port=root.offset(b.cellOffset(0));
        var energy=l.getCapability(Capabilities.EnergyStorage.BLOCK,port,Direction.DOWN);
        h.assertTrue(energy!=null,"RIP motor cable cannot connect");
        for(int i=0;i<700;i++){energy.receiveEnergy(Integer.MAX_VALUE,false);pump.tickPump(.05);}
        h.assertTrue(Math.abs(RecirculationNetwork.measure(l,be,List.of(root)).fraction()-.25)<1e-5,"RIP did not deliver its rated quarter of minimum core flow");
        l.removeBlock(port,false);
        h.assertTrue(RecirculationNetwork.measure(l,be,List.of(root)).internalPumps()==0,"broken pump retained flow");
        h.assertTrue(energy.receiveEnergy(100,false)==0,"broken pump retains cached FE capability");
        validate(be);h.assertTrue(!be.isFormed(),"missing mounting flange still forms");
        l.removeBlock(be.getBlockPos(),false);
        var other=CompactCoreRegressionTests.build(l,min.offset(50,0,0),15,15,false);
        install(l,min.offset(50,0,0),new InternalPumpLayout.Mount(6,6),Direction.NORTH);validate(other);
        h.assertTrue(!other.isFormed(),"central invalid RIP location accepted");
        l.removeBlock(other.getBlockPos(),false);h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=400)
    public static void ripAlpha19SaveGainsPerimeterWithoutLosingFuelOrDriveState(GameTestHelper h)throws Exception {
        var l=h.getLevel();var min=new BlockPos(5900,190,5500);
        var be=CompactCoreRegressionTests.build(l,min,15,15,false);
        for(var site:InternalPumpLayout.mounts(15,15))install(l,min,site,Direction.NORTH);
        var packed=new CompactCoreLayout(15,15,true);
        for(var drive:packed.drives())l.setBlock(min.offset(drive.x(),-2,drive.z()),BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),2);
        validate(be);h.assertTrue(be.isFormed(),"expanded test fixture failed");
        double q=InternalPumpLayout.design(15,15).coreScale();
        var oldDrives=new ArrayList<CompactCoreLayout.Drive>();var fuel=new HashSet<Integer>();
        int[] rodAt=new int[42*42];Arrays.fill(rodAt,-1);
        for(int x=0;x<15;x++)for(int z=0;z<15;z++)if(alpha19Inside(2*x-14,2*z-14,236,q)) {
            int rod=oldDrives.size();oldDrives.add(new CompactCoreLayout.Drive(x,z));
            for(int a=0;a<2;a++)for(int b=0;b<2;b++){int slot=(6+2*z+b)*42+6+2*x+a;fuel.add(slot);rodAt[slot]=rod;}
        }
        for(int x=0;x<30;x++)for(int z=0;z<30;z++)if(alpha19Inside(2*x-29,2*z-29,970,q))fuel.add((6+z)*42+6+x);
        h.assertTrue(fuel.size()==444&&oldDrives.size()==101,"alpha.19 fixture changed");
        for(int slot:fuel)h.assertTrue(be.loadAssembly(slot,new ItemStack(BwrItems.FUEL_ASSEMBLY.get())),"old fuel slot lost");
        var config=new dev.bwr.core.CoreConfig();config.assemblyCount=444;config.controlRodCount=101;
        var old=new dev.bwr.core.ReactorCore(config,be.core().getCoreLoading());
        old.getNodalFluxSolver().setRodLatticeMap(new dev.bwr.core.nodal.RodLatticeMap(101,42*42,rodAt));
        old.initialiseCold();old.setRodNotchDemand(50,8);old.step();
        var snapshot=old.toState();int[] demand=new int[101];demand[50]=12;
        var tag=be.saveWithFullMetadata(l.registryAccess());var controller=be.getBlockPos();var state=be.getBlockState();
        tag.put("Core",ReactorStateNbt.write(snapshot));tag.putIntArray("RodDemand",demand);
        tag.putIntArray("CoreDriveCells",oldDrives.stream().mapToInt(d->d.x()*21+d.z()).toArray());
        var newDrives=packed.drives().stream().filter(d->!oldDrives.contains(d)).toList();
        h.assertTrue(newDrives.size()==8,"expected eight new reference drives");
        for(var drive:newDrives)l.removeBlock(min.offset(drive.x(),-2,drive.z()),false);
        be.onChunkUnloaded();l.removeBlockEntity(controller);
        be=(ReactorControllerBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(controller,state,tag,l.registryAccess());l.setBlockEntity(be);
        validate(be);h.assertTrue(!be.isFormed()&&be.statusLines().stream().anyMatch(s->s.contains("missing control rod drive")),"upgrade failed to report additional drives");
        h.assertTrue(tag.getList("CoreFuel",10).equals(be.saveWithFullMetadata(l.registryAccess()).getList("CoreFuel",10)),"invalid upgrade dropped fuel while waiting for drives");
        for(var drive:newDrives)l.setBlock(min.offset(drive.x(),-2,drive.z()),BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),2);
        validate(be);h.assertTrue(be.isFormed()&&be.assemblyCount()==476&&be.core().getControlRodCount()==109,"upgrade did not gain usable perimeter");
        h.assertTrue(tag.getList("CoreFuel",10).equals(be.saveWithFullMetadata(l.registryAccess()).getList("CoreFuel",10)),"upgrade changed saved fuel metadata");
        h.assertTrue(be.core().toState().elapsedSeconds()==snapshot.elapsedSeconds(),"upgrade reset transient time");
        for(int oldId=0;oldId<101;oldId++) {
            int now=packed.drives().indexOf(oldDrives.get(oldId));
            h.assertTrue(be.core().getRodPositionNotches(now)==snapshot.rodPositionsNotches()[oldId]&&be.rods().getCommandedNotchIndices()[now]==demand[oldId],"upgrade reassigned surviving drive state");
        }
        for(var drive:newDrives) {
            int rod=packed.drives().indexOf(drive);
            h.assertTrue(be.core().getRodPositionNotches(rod)==0&&be.core().toState().accumulatorCharge()[rod]==0&&be.rods().getCommandedNotchIndices()[rod]==0,"new peripheral drive did not start inserted/uncharged");
            h.assertTrue(Arrays.equals(packed.rodMap().positionsOfRod(rod),be.core().getNodalFluxSolver().rodLatticeMap().positionsOfRod(rod)),"new drive shadows wrong fuel");
        }
        int extra=Arrays.stream(packed.fuelPositions()).filter(p->!fuel.contains(p)).findFirst().orElseThrow();
        h.assertTrue(be.loadAssembly(extra,new ItemStack(BwrItems.FUEL_ASSEMBLY.get())),"extra fuel is only cosmetic");
        var saved=be.saveWithFullMetadata(l.registryAccess());be.onChunkUnloaded();l.removeBlockEntity(controller);
        be=(ReactorControllerBlockEntity)net.minecraft.world.level.block.entity.BlockEntity.loadStatic(controller,state,saved,l.registryAccess());l.setBlockEntity(be);validate(be);
        h.assertTrue(be.isFormed()&&be.core().getCoreLoading().assemblyAt(extra)!=null,"extra fuel lost on reload");
        l.removeBlock(controller,false);h.succeed();
    }
    private static boolean alpha19Inside(int x,int z,int limit,double q) {
        return 225L*((long)x*x*225+(long)z*z*225)<=(long)limit*15*15*15*15*q*q;
    }
}
