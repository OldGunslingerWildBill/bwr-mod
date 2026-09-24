package dev.bwr.mod.devtest;

import dev.bwr.core.thermal.Saturation;
import dev.bwr.core.turbine.SurfaceCondenser;
import dev.bwr.mod.condenser.*;
import dev.bwr.mod.cooling.*;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.water.ThermalWater;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.*;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.*;

/** Adjacent LP seats and real pump-to-hotwell plumbing, including shared inlet space. */
@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class CondenserMakeupRegressionTests {
    @GameTest(template="empty",timeoutTicks=200)
    public static void adjacentLpCondensersHaveWallFacingPorts(GameTestHelper h){
        var l=h.getLevel();var root=new BlockPos(12200,210,12200);ReleaseRegressionTests.load(l,root);
        var block=BwrBlocks.ARABELLE_CONDENSER.get();var lp=BwrBlocks.LP_TURBINE.get();var layout=CondenserLayout.INSTANCE;
        for(var shaft:Direction.Plane.HORIZONTAL){
            var facing=shaft.getClockWise();var other=root.relative(shaft,7);
            for(var at:new BlockPos[]{root,other}){
                var ls=lp.placementState().setValue(PumpAssemblyBlock.FACING,shaft);
                l.setBlock(at.above(6),ls,3);lp.setPlacedBy(l,at.above(6),ls,null,new ItemStack(lp));
                h.assertTrue(CondenserBlock.canPlaceAt(l,null,at,facing),"adjacent condenser overlaps LP or its neighbor");
                var cs=block.defaultBlockState().setValue(CondenserBlock.FACING,facing);
                l.setBlock(at,cs,3);block.setPlacedBy(l,at,cs,null,new ItemStack(block));
            }
            for(var at:new BlockPos[]{root,other}){
                var be=(CondenserBlockEntity)l.getBlockEntity(at);
                h.assertTrue(be.ready()&&((PowerModuleBlockEntity)l.getBlockEntity(at.above(6))).condenser()==be,"adjacent LP lost its condenser");
                for(var port:layout.ports){
                    var p=layout.world(at,facing,port);var face=CondenserBlock.portFace(l.getBlockState(p));
                    h.assertTrue(l.getBlockState(p.relative(face)).isAir(),"neighbor blocks a condenser socket: "+port.role());
                    if(port.role()==CondenserBlock.Port.BYPASS||port.role()==CondenserBlock.Port.CONDENSATE)
                        h.assertTrue(face.getAxis()!=shaft.getAxis(),"main pipes face the next turbine");
                    if(port.role()==CondenserBlock.Port.COLD||port.role()==CondenserBlock.Port.HOT)
                        h.assertTrue(Math.abs(p.getX()-at.getX())==4&&shaft.getAxis()==Direction.Axis.Z
                                ||Math.abs(p.getZ()-at.getZ())==4&&shaft.getAxis()==Direction.Axis.X,"CW elbow is not on hall side");
                }
            }
            for(var at:new BlockPos[]{root,other}){l.removeBlock(at,false);l.removeBlock(at.above(6),false);}
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void makeupPumpFeedsOnlyFiniteHotwell(GameTestHelper h){
        var l=h.getLevel();var root=new BlockPos(12300,210,12300);ReleaseRegressionTests.load(l,root);
        var block=BwrBlocks.ARABELLE_CONDENSER.get();var state=block.defaultBlockState();
        l.setBlock(root,state,3);block.setPlacedBy(l,root,state,null,new ItemStack(block));
        var owner=(CondenserBlockEntity)l.getBlockEntity(root);h.assertTrue(owner.ready(),"condenser fixture incomplete");
        var ports=owner.layout().ports.stream().filter(p->p.role()==CondenserBlock.Port.MAKEUP).toList();
        h.assertTrue(ports.size()==2,"two side makeup nozzles missing");
        var a=owner.layout().world(root,Direction.NORTH,ports.getFirst());var b=owner.layout().world(root,Direction.NORTH,ports.getLast());
        var inlet=l.getCapability(Capabilities.FluidHandler.BLOCK,a,Direction.WEST);
        h.assertTrue(inlet!=null&&inlet.getTankCapacity(0)==SurfaceCondenser.CONDENSATE_CAPACITY,"wrong hotwell capability");
        h.assertTrue(inlet.fill(new FluidStack(Fluids.LAVA,1000),EXECUTE)==0&&inlet.drain(1000,EXECUTE).isEmpty(),"makeup is not water-only inlet");
        h.assertTrue(l.getCapability(Capabilities.FluidHandler.BLOCK,a,Direction.EAST)==null,"makeup accepts wrong face");
        var written=new java.util.HashSet<BlockPos>();
        java.util.function.BiConsumer<BlockPos,BlockPos> line=(from,to)->{
            h.assertTrue(from.getX()==to.getX()&&from.getY()==to.getY()||from.getX()==to.getX()&&from.getZ()==to.getZ()||from.getY()==to.getY()&&from.getZ()==to.getZ(),"non-axis test pipe");
            for(var p:BlockPos.betweenClosed(from,to)){h.assertTrue(l.getBlockState(p).isAir()||l.getBlockState(p).is(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get()),"pipe crosses fixture");
                l.setBlock(p,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,p),3);written.add(p.immutable());}
        };
        line.accept(a.west(),b.west());var network=l.getCapability(Capabilities.FluidHandler.BLOCK,a.west(),Direction.WEST);
        double waterH=Saturation.subcooledLiquidEnthalpyKJPerKg(22);
        owner.plant().restore(171,22,SurfaceCondenser.CONDENSATE_CAPACITY-10);
        var water=ThermalWater.stack(100,waterH);
        h.assertTrue(network.fill(water,SIMULATE)==10,"two makeup ports double-reserved shared hotwell space");
        ReleaseRegressionTests.near(h,owner.plant().condensate(),199990,"simulation changed inventory");
        h.assertTrue(network.fill(water,EXECUTE)==10&&network.fill(water,EXECUTE)==0,"overfilled hotwell");
        owner.plant().drainCondensate(200000,false);
        var pump=CoolingRuntimeCheck.place(l,dev.bwr.core.turbine.CoolingWaterUnit.Design.MAKEUP,root.offset(-8,0,-10),Direction.NORTH);
        var out=CoolingRuntimeCheck.port(pump,CoolingBlock.Port.OUTLET).south();
        var bend=new BlockPos(out.getX(),out.getY(),a.getZ());line.accept(out,bend);line.accept(bend,a.west());
        pump.plant().fillInput(1000,waterH,false);pump.setTarget(1);
        CoolingBlockEntity.serverTick(l,pump.root(),pump.getBlockState(),pump);
        ReleaseRegressionTests.near(h,owner.plant().condensate(),0,"unpowered pump transferred water");
        pump.power().receiveEnergy(Integer.MAX_VALUE,false);
        CoolingBlockEntity.serverTick(l,pump.root(),pump.getBlockState(),pump);
        ReleaseRegressionTests.near(h,owner.plant().condensate(),75,"makeup pump failed to deliver rated tick volume");
        ReleaseRegressionTests.near(h,pump.plant().input()+pump.plant().output()+owner.plant().condensate(),1000,"pump transfer duplicated water");
        ReleaseRegressionTests.near(h,owner.plant().condensateH(),waterH,"makeup lost inlet heat");
        ReleaseRegressionTests.near(h,owner.plant().cold(),171,"makeup entered CW inlet");
        ReleaseRegressionTests.near(h,owner.plant().hot(),22,"makeup entered CW outlet");
        var saved=owner.saveWithoutMetadata(l.registryAccess());owner.loadWithComponents(saved,l.registryAccess());
        h.assertTrue(owner.ready()&&owner.layout()==CondenserLayout.INSTANCE,"v4 save lost layout/ownership");
        ReleaseRegressionTests.near(h,owner.plant().condensate(),75,"reload lost hotwell mass");
        ReleaseRegressionTests.near(h,owner.plant().condensateH(),waterH,"reload lost hotwell heat");
        var outlet=owner.water(CondenserBlock.Port.CONDENSATE);var drained=outlet.drain(25,EXECUTE);
        h.assertTrue(drained.getAmount()==25,"condensate outlet cannot deliver makeup");
        ReleaseRegressionTests.near(h,ThermalWater.enthalpy(drained),waterH,"drained makeup lost heat");
        l.removeBlock(root,false);h.assertTrue(inlet.fill(water,EXECUTE)==0&&network.fill(water,EXECUTE)==0,"destroyed hotwell accepted water");
        l.removeBlock(pump.root(),false);for(var p:written)l.removeBlock(p,false);h.succeed();
    }
}
