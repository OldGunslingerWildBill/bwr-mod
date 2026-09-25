package dev.bwr.mod.devtest;

import dev.bwr.core.pool.SuppressionPool;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.mod.cooling.*;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.suppression.*;
import dev.bwr.mod.steam.*;
import dev.bwr.mod.water.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import static net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.*;

@GameTestHolder("bwr") @net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class SuppressionTankRegressionTests {
    private static final int SHIFT=Math.floorMod(java.util.UUID.randomUUID().hashCode(),100_000)*64;
    public static SuppressionPoolBlockEntity build(ServerLevel l,BlockPos o)throws Exception {
        var pool=SuppressionBasinRegressionTests.build(l,o);
        l.setBlock(o.offset(2,2,0),BwrBlocks.SUPPRESSION_POOL_STEAM_INLET.get().defaultBlockState(),3);
        for(var p:BlockPos.betweenClosed(o.offset(1,4,1),o.offset(7,4,5)))l.setBlock(p,BwrBlocks.SUPPRESSION_POOL_WALL.get().defaultBlockState(),3);
        SuppressionBasinRegressionTests.validate(pool);
        if(!pool.isFormed()||!pool.isEnclosedTank())throw new AssertionError(pool.statusLines());
        return pool;
    }
    private static void near(GameTestHelper h,double a,double b,String m){h.assertTrue(Double.isFinite(a)&&Math.abs(a-b)<1e-6*Math.max(1,Math.abs(b)),m+": "+a+" != "+b);}
    private static void tick(ServerLevel l,SuppressionPoolBlockEntity pool){SuppressionPoolBlockEntity.serverTick(l,pool.getBlockPos(),pool.getBlockState(),pool);}
    private static void pipe(ServerLevel l,BlockPos a,BlockPos b,boolean steam){for(var p:BlockPos.betweenClosed(a,b))l.setBlock(p,steam?BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState():BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(l,p),3);}

    @GameTest(template="empty",timeoutTicks=200)
    public static void mekanismSteamInlet(GameTestHelper h)throws Exception {
        if(!net.neoforged.fml.ModList.get().isLoaded("mekanism")){h.succeed();return;}
        var l=h.getLevel();var o=new BlockPos(16300+SHIFT,190,16300);var pool=build(l,o);var at=o.offset(2,2,0);
        pool.water(false).fill(new FluidStack(Fluids.WATER,50_000),EXECUTE);
        var cap=l.getCapability(net.neoforged.neoforge.capabilities.BlockCapability.createSided(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekanism","chemical_handler"),mekanism.api.chemical.IChemicalHandler.class),at,Direction.NORTH);
        h.assertTrue(cap!=null&&l.getCapability(net.neoforged.neoforge.capabilities.BlockCapability.createSided(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekanism","chemical_handler"),mekanism.api.chemical.IChemicalHandler.class),at,Direction.UP)==null,"Mekanism steam inlet face incorrect");
        var steam=mekanism.api.MekanismAPI.CHEMICAL_REGISTRY.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekanism","steam"));
        var stack=new mekanism.api.chemical.ChemicalStack(steam,200_000);
        near(h,cap.insertChemical(0,stack,mekanism.api.Action.SIMULATE).getAmount(),100_000,"steam simulation allowance incorrect");near(h,pool.pool().inletSteam.mass(),0,"chemical simulation mutated inventory");
        near(h,cap.insertChemical(0,stack,mekanism.api.Action.EXECUTE).getAmount(),100_000,"chemical execute mismatch");
        near(h,pool.receiveSteam(1,2770,1015,false),0,"BWR and Mekanism bypassed common allowance");
        h.assertTrue(cap.extractChemical(0,100,mekanism.api.Action.EXECUTE).isEmpty(),"inlet extracted steam");
        double before=pool.pool().getTemperatureC();tick(l,pool);h.assertTrue(pool.pool().getTemperatureC()>before,"Mekanism steam did not heat water");
        var roof=o.offset(4,4,3);l.removeBlock(roof,false);
        near(h,cap.insertChemical(0,stack,mekanism.api.Action.EXECUTE).getAmount(),200_000,"cached chemical handler filled broken tank");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=300)
    public static void enclosedSteamHeatAndLifecycle(GameTestHelper h)throws Exception {
        var l=h.getLevel();var o=new BlockPos(16000+SHIFT,190,16000);var pool=build(l,o);var inlet=o.offset(2,2,0);
        near(h,pool.pool().getMassKg(),0,"tank spawned free water");
        h.assertTrue(pool.steamPortCount()==1,"steam inlet not owned");
        h.assertTrue(l.getCapability(Capabilities.FluidHandler.BLOCK,inlet,Direction.NORTH)==null,"steam flange accepted water");
        pool.water(false).fill(new FluidStack(Fluids.WATER,50_000),EXECUTE);
        double before=pool.pool().getTemperatureC();
        near(h,pool.receiveSteam(100,2770,1015,true),100,"simulation rejected steam");near(h,pool.pool().inletSteam.mass(),0,"simulation mutated buffer");
        near(h,pool.receiveSteam(100,2770,1015,false),100,"steam was not received");
        near(h,pool.receiveSteam(1,2770,1015,false),0,"shared inlet exceeded per-tick limit");
        // Break, save and replace the controller with steam waiting inside the shared tank inventory.
        var roof=o.offset(4,4,3);l.removeBlock(roof,false);
        h.assertTrue(!pool.isFormed(),"roof breach did not invalidate tank");tick(l,pool);
        near(h,pool.pool().inletSteam.mass(),100,"broken tank consumed steam");
        var root=pool.getBlockPos();var tag=pool.saveWithFullMetadata(l.registryAccess());l.removeBlockEntity(root);
        l.setBlockEntity(BlockEntity.loadStatic(root,l.getBlockState(root),tag,l.registryAccess()));pool=(SuppressionPoolBlockEntity)l.getBlockEntity(root);
        SuppressionBasinRegressionTests.validate(pool);h.assertTrue(!pool.isFormed(),"roof breach reloaded as open basin");
        l.setBlock(roof,BwrBlocks.SUPPRESSION_POOL_WALL.get().defaultBlockState(),3);SuppressionBasinRegressionTests.validate(pool);tick(l,pool);
        h.assertTrue(pool.pool().getTemperatureC()>before+.5&&pool.pool().getCumulativeHeatInputMJ()>250,"steam failed to heat pool");
        near(h,pool.pool().getMassKg(),50_100,"condensate mass missing");near(h,pool.pool().inletSteam.mass(),0,"buffer not consumed");
        near(h,pool.steamInKgPerS(),2000,"steam GUI measurement missing");
        l.setBlock(inlet,BwrBlocks.SUPPRESSION_POOL_STEAM_INLET.get().defaultBlockState().setValue(SuppressionPoolSteamPortBlock.FACING,Direction.SOUTH),3);
        SuppressionBasinRegressionTests.validate(pool);h.assertTrue(!pool.isFormed(),"inward-facing steam port accepted");
        System.out.println("SUPPRESSION TANK PASS: enclosed shell, heat, steam/water separation, finite allocation, breach/repair and reload");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=300)
    public static void steamPipeAndValveControl(GameTestHelper h)throws Exception {
        var l=h.getLevel();var o=new BlockPos(16100+SHIFT,190,16100);var pool=build(l,o);
        pool.water(false).fill(new FluidStack(Fluids.WATER,50_000),EXECUTE);
        var vo=o.offset(15,0,-5);
        for(int x=(vo.getX()-2)>>4;x<=(vo.getX()+28)>>4;x++)for(int z=(vo.getZ()-5)>>4;z<=(vo.getZ()+18)>>4;z++)l.getChunk(x,z);
        var build=PhaseOneRegressionTests.class.getDeclaredMethod("buildVessel",ServerLevel.class,BlockPos.class,int.class);build.setAccessible(true);build.invoke(null,l,vo,11);
        var cp=vo.offset(-1,3,2);l.setBlock(cp,BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(),2);
        var np=vo.offset(11,6,5);l.setBlock(np,BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),2);
        var r=(dev.bwr.mod.reactor.ReactorControllerBlockEntity)l.getBlockEntity(cp);var validate=r.getClass().getDeclaredMethod("revalidate",Level.class);validate.setAccessible(true);validate.invoke(r,l);
        h.assertTrue(r.isFormed(),"steam source fixture incomplete");
        var n=(dev.bwr.mod.reactor.RpvSteamOutletBlockEntity)l.getBlockEntity(np);n.noteController(cp);n.setPosition(1);
        var f=r.core().getClass().getDeclaredField("vessel");f.setAccessible(true);((dev.bwr.core.thermal.PressureVessel)f.get(r.core())).restorePressurePsig(1000);
        pipe(l,np.east(),np.east(2),true);pipe(l,np.east(2),new BlockPos(np.getX()+2,np.getY(),o.getZ()-5),true);
        pipe(l,new BlockPos(np.getX()+2,np.getY(),o.getZ()-5),o.offset(2,6,-5),true);
        pipe(l,o.offset(2,6,-5),o.offset(2,2,-5),true);pipe(l,o.offset(2,2,-5),o.offset(2,2,-1),true);
        var vp=o.offset(2,2,-3);l.setBlock(vp,BwrBlocks.STEAM_STOP_VALVE.get().defaultBlockState(),3);
        var v=(TurbineValveBlockEntity)l.getBlockEntity(vp);v.setTarget(0);
        n.setComputerControlled(true);n.setPosition(1);n.refreshAttachment(l);n.flowKgPerS(1000);tick(l,pool);near(h,pool.steamInKgPerS(),0,"closed stop valve admitted steam");
        v.setTarget(1);for(int i=0;i<100;i++)TurbineValveBlockEntity.serverTick(l,vp,v.getBlockState(),v);
        n.flowKgPerS(1000);double mass=pool.pool().getMassKg();tick(l,pool);
        h.assertTrue(pool.pool().getMassKg()>mass,"BWR pipe did not transfer nozzle steam: flow="+n.getLastFlowKgPerS()+" open="+n.getLineOpenFraction()+" source="+n.getControllerPos()+" tank="+pool.statusLines());
        double available=n.availableFlowKgPerS(l.getGameTime());tick(l,pool);near(h,n.availableFlowKgPerS(l.getGameTime()),available,"tank exceeded shared tick allocation");
        l.removeBlock(o.offset(2,2,-2),false);tick(l,pool);near(h,pool.steamInKgPerS(),0,"broken pipe continued transfer");
        // Convert the same source to an ADS relief route with its downward outlet.
        pipe(l,o.offset(2,2,-3),o.offset(2,2,-1),true);l.removeBlock(o.offset(2,2,-4),false);
        var ads=o.offset(2,3,-3);l.setBlock(ads,BwrBlocks.ADS_RELIEF_VALVE.get().defaultBlockState().setValue(AdsReliefValveBlock.FACING,Direction.EAST),3);
        pipe(l,o.offset(2,3,-4),o.offset(2,3,-4),true);
        var relief=(SafetyReliefValveBlockEntity)l.getBlockEntity(ads);relief.setComputerControlled(true);relief.setOpen(true);
        pool.pool().fromArray(SuppressionPool.empty(105_000).toArray());SuppressionBasinRegressionTests.validate(pool);relief.revalidateDischarge(l);
        h.assertTrue(!relief.isDischargeSubmerged(),"dry tank treated as submerged relief sink");
        pool.water(false).fill(new FluidStack(Fluids.WATER,50_000),EXECUTE);
        long now=l.getGameTime();var data=l.getServer().getWorldData().overworldData();
        try{
            data.setGameTime(now+1);n.setPosition(1);n.flowKgPerS(1000);tick(l,pool);
            h.assertTrue(relief.isDischargeSubmerged()&&pool.pool().getTemperatureC()>13,"pumped filling failed to enable steam condensation through relief inlet");
            near(h,pool.pool().getMassKg()-50_000,relief.getLastFlowKgPerS()/20,"wall inlet double-counted relief steam");
            near(h,SteamValveRouting.claimWithoutRelief(l,o.offset(2,2,0),n,1000),0,"direct inlet pulled around relief allocation");
            l.removeBlock(o.offset(2,2,-2),false);data.setGameTime(now+2);double held=pool.pool().getMassKg();tick(l,pool);near(h,pool.pool().getMassKg(),held,"disconnected relief heated former tank");
        }finally{data.setGameTime(now);}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=300)
    public static void oceanMakeupExchangerDischarge(GameTestHelper h)throws Exception {
        var l=h.getLevel();var o=new BlockPos(16200+SHIFT,190,16200);var pool=build(l,o);var rhr=SuppressionBasinRegressionTests.connect(l,o);SuppressionBasinRegressionTests.validate(pool);
        pool.pool().fromArray(new SuppressionPool(80_000,80).toArray());pool.pool().resizeCapacityKeepingInventory(105_000);
        var hp=o.offset(6,6,-3);var hx=(RhrHeatExchangerBlockEntity)l.getBlockEntity(hp);
        // Keep the primary header away from the two adjacent secondary flange connections.
        for(int x=7;x<=10;x++)l.removeBlock(o.offset(x,6,-4),false);
        pipe(l,o.offset(10,6,-6),o.offset(10,6,-3),false);pipe(l,o.offset(6,6,-6),o.offset(10,6,-6),false);pipe(l,o.offset(6,6,-6),o.offset(6,6,-4),false);
        var intake=CoolingRuntimeCheck.place(l,Design.INTAKE,o.offset(-7,6,-18),Direction.NORTH);
        var pump=CoolingRuntimeCheck.place(l,Design.MAKEUP,o.offset(-7,6,-11),Direction.NORTH);
        for(var p:java.util.List.of(intake.root().north(2),intake.root().west(2)))l.setBlock(p,Blocks.WATER.defaultBlockState(),3);
        var ip=CoolingRuntimeCheck.port(intake,CoolingBlock.Port.OUTLET).south();var pi=CoolingRuntimeCheck.port(pump,CoolingBlock.Port.INLET).north();pipe(l,ip,pi,false);
        var po=CoolingRuntimeCheck.port(pump,CoolingBlock.Port.OUTLET).south();var cold=hp.west();
        var bend=new BlockPos(cold.getX(),po.getY(),cold.getZ());
        pipe(l,po,new BlockPos(po.getX(),po.getY(),cold.getZ()),false);pipe(l,new BlockPos(po.getX(),po.getY(),cold.getZ()),bend,false);pipe(l,bend,cold,false);
        var discharge=hp.east(2);pipe(l,hp.east(),discharge.west(),false);
        l.setBlock(discharge,BwrBlocks.WATER_DISCHARGE_PORT.get().defaultBlockState().setValue(WaterDischargeBlock.FACING,Direction.EAST),3);
        var out=(WaterDischargeBlockEntity)l.getBlockEntity(discharge);long time=l.getGameTime();var data=l.getServer().getWorldData().overworldData();
        double supplied=0;
        try{
            pump.setTarget(1);
            for(int i=0;i<200;i++){
                data.setGameTime(time+i);pump.power().receiveEnergy(Integer.MAX_VALUE,false);rhr.energy().setStored(rhr.energy().getMaxEnergyStored());
                double before=intake.plant().input()+intake.plant().output();
                CoolingBlockEntity.serverTick(l,intake.root(),intake.getBlockState(),intake);supplied+=intake.plant().input()+intake.plant().output()-before;
                CoolingBlockEntity.serverTick(l,pump.root(),pump.getBlockState(),pump);
                EccsPumpBlockEntity.serverTick(l,rhr.getBlockPos(),rhr.getBlockState(),rhr);RhrHeatExchangerBlockEntity.serverTick(l,hp,hx.getBlockState(),hx);
            }
            h.assertTrue(out.totalKg()>0&&out.temperatureC()>13&&pool.pool().getTemperatureC()<80,"ocean -> makeup pump -> exchanger -> discharge failed: out="+out.totalKg()+" temp="+out.temperatureC()+" pool="+pool.pool().getTemperatureC()+" pump="+pump.plant().flow()+" hx cold/hot="+hx.plant().cold()+"/"+hx.plant().hot()+" rhr="+rhr.connectionStatus());
            near(h,pool.pool().getMassKg(),80_000,"isolated primary cooling loop lost water");
            near(h,intake.plant().input()+intake.plant().output()+pump.plant().input()+pump.plant().output()+hx.plant().cold()+hx.plant().hot()+out.totalKg(),supplied,"secondary water was duplicated/lost");
            double discarded=out.totalKg();l.setBlock(discharge.east(),Blocks.STONE.defaultBlockState(),3);data.setGameTime(time+201);RhrHeatExchangerBlockEntity.serverTick(l,hp,hx.getBlockState(),hx);near(h,out.totalKg(),discarded,"blocked outlet discarded water");
        }finally{data.setGameTime(time);}
        System.out.println("OCEAN RHR PASS: real intake, powered makeup, exchanger, heated discharge, separate water accounting and blocked outfall");h.succeed();
    }
}
