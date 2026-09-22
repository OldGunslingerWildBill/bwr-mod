package dev.bwr.mod.devtest;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Permanent regressions for the phase-one audit findings. Excluded from the release JAR. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PhaseOneRegressionTests {
    private static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> PORT_TICKET=
            net.minecraft.server.level.TicketType.create("bwr_audit_port",java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));

    @GameTest(template="empty",timeoutTicks=1000)
    public static void partialChunkLifecycle(GameTestHelper h) {
        var l=h.getLevel();var root=new BlockPos(1151,220,1158);loadAround(l,root);
        var block=BwrBlocks.NUCLEAR_GENERATOR.get();l.removeBlock(root,false);
        var state=block.placementState();l.setBlock(root,state,3);block.setPlacedBy(l,root,state,null,new net.minecraft.world.item.ItemStack(block));
        var owner=(dev.bwr.mod.power.PowerModuleBlockEntity)l.getBlockEntity(root);
        var saved=owner.saveWithoutMetadata(l.registryAccess());saved.putDouble("Energy",10000);owner.loadWithComponents(saved,l.registryAccess());owner.setChanged();
        var port=block.terminal(root,state);var chunk=new net.minecraft.world.level.ChunkPos(port);
        l.getChunkSource().addRegionTicket(PORT_TICKET,chunk,0,chunk);
        var cache=net.neoforged.neoforge.capabilities.BlockCapabilityCache.create(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,l,port,block.terminalFace(state));
        var handler=cache.getCapability();h.assertTrue(handler!=null&&handler.getEnergyStored()==10000,"chunk fixture has no energy port");
        var rootChunk=l.getChunkAt(root);
        h.startSequence()
                .thenWaitUntil(()->h.assertTrue(!l.isLoaded(root)&&l.isLoaded(port),"waiting for controller unload and retained port chunk"))
                .thenExecute(()->{
                    // The neighboring FULL ticket can retain the root as a LIGHT chunk. Exercise
                    // the engine's discard callback explicitly, retaining its serialized BE for reload.
                    var tag=owner.saveWithFullMetadata(l.registryAccess());
                    l.unload(rootChunk);rootChunk.setBlockEntityNbt(tag);
                    h.assertTrue(l.isLoaded(port)&&owner.isRemoved(),"asymmetric chunk unload did not occur");
                    h.assertTrue(cache.getCapability()==handler&&handler.extractEnergy(1000,false)==0,"cached port extracted while controller chunk was absent");
                    l.getChunkAt(root);
                    h.assertTrue(l.getBlockEntity(root)!=owner,"controller instance was not replaced on reload");
                    h.assertTrue(handler.extractEnergy(1000,false)==1000&&handler.getEnergyStored()==9000,"remote cache did not reconnect to reloaded inventory");
                    h.assertTrue(owner.energyStored()==10000,"cached transfer mutated obsolete inventory");
                })
                .thenWaitUntil(()->h.assertTrue(!l.isLoaded(root),"controller chunk has not unloaded again"))
                .thenExecute(()->{
                    h.assertTrue(handler.extractEnergy(1000,false)==0,"second unload exposed inventory");
                    l.removeBlock(port,false); // Child break while only its remote chunk is loaded.
                    l.getChunkAt(root);state.tick(l,root,l.random);
                    h.assertTrue(l.getBlockState(root).isAir(),"controller failed to finish teardown after reload");
                    l.getChunkSource().removeRegionTicket(PORT_TICKET,chunk,0,chunk);
                    System.out.println("REGRESSION CHUNK LIFECYCLE: chunk availability, engine unload callback, NBT restoration, cached extraction, reconnect and deferred teardown passed");
                }).thenSucceed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void stoppedRecirculationOverride(GameTestHelper h) throws Exception {
        var l=h.getLevel();var o=new BlockPos(660,195,660);buildVessel(l,o,11);
        var a=o.offset(-1,3,2);l.setBlock(a,BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(),2);
        var r=(dev.bwr.mod.reactor.ReactorControllerBlockEntity)l.getBlockEntity(a);
        l.setBlock(o.offset(11,6,2),BwrBlocks.RECIRCULATION_OUTLET.get().defaultBlockState().setValue(dev.bwr.mod.reactor.RpvWaterInjectionPortBlock.FACING,net.minecraft.core.Direction.EAST),2);
        l.setBlock(o.offset(11,0,2),BwrBlocks.RECIRCULATION_INLET.get().defaultBlockState().setValue(dev.bwr.mod.reactor.RpvWaterInjectionPortBlock.FACING,net.minecraft.core.Direction.EAST),2);
        var validate=r.getClass().getDeclaredMethod("revalidate",Level.class);validate.setAccessible(true);validate.invoke(r,l);
        var root=o.offset(17,0,5);loadAround(l,root);var block=BwrBlocks.RECIRCULATION_PUMP.get();var s=block.placementState();
        l.removeBlock(root,false);
        l.setBlock(root,s,3);block.setPlacedBy(l,root,s,null,new net.minecraft.world.item.ItemStack(block));
        int[][] paths={{17,0,8,17,6,8},{17,6,8,13,6,8},{13,6,8,13,6,2},{13,6,2,12,6,2},{17,2,2,17,0,2},{17,0,2,12,0,2}};
        for(var path:paths)for(var p:BlockPos.betweenClosed(o.offset(path[0],path[1],path[2]),o.offset(path[3],path[4],path[5])))l.setBlock(p,BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().defaultBlockState(),3);
        var motor=(dev.bwr.mod.flow.RecirculationPumpBlockEntity)l.getBlockEntity(root);motor.bindController(r);
        h.assertTrue(dev.bwr.mod.flow.RecirculationCircuit.controller(l,root)==r,"recirc fixture not connected");
        var gather=r.getClass().getDeclaredMethod("gatherPumpFlow",Level.class);gather.setAccessible(true);gather.invoke(r,l);
        double capacity=r.getRecirculationCapacityFraction();
        new dev.bwr.mod.peripheral.ReactorPeripheral(r).setRecirculationFlow(capacity);
        for(int i=0;i<3;i++)gather.invoke(r,l);
        System.out.println("REGRESSION STOPPED RECIRC: speed="+motor.getActualSpeedFraction()+" energy="+motor.energy().getEnergyStored()+" hardwareCapacity="+capacity+" solverDemand="+r.core().getRecirculationFlowFractionDemand());
        h.assertTrue(capacity>0&&motor.getActualSpeedFraction()==0&&r.core().getRecirculationFlowFractionDemand()==0,"computer demand bypassed stopped hardware");
        for(int i=0;i<80;i++){motor.receiveEnergyFe(motor.getEnergyCapacityFe(),false);gather.invoke(r,l);}
        h.assertTrue(motor.getActualSpeedFraction()>0&&r.core().getRecirculationFlowFractionDemand()>0
                &&r.core().getRecirculationFlowFractionDemand()<=capacity,"powered computer-controlled motor failed to deliver bounded flow");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void unusedSteamBranch(GameTestHelper h){
        var l=h.getLevel();var root=new BlockPos(740,200,740);loadAround(l,root);var block=BwrBlocks.TURBINE_FEED_PUMP.get();var s=block.placementState();
        l.removeBlock(root,false);
        l.setBlock(root,s,3);block.setPlacedBy(l,root,s,null,new net.minecraft.world.item.ItemStack(block));
        var role=dev.bwr.mod.eccs.AssemblyPort.STEAM_INLET;var port=block.portPosition(root,s,role);var face=block.portFace(s,role);
        for(int i=1;i<=3;i++)l.setBlock(port.relative(face,i),BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(),3);
        l.setBlock(port.relative(face,4),BwrBlocks.RPV_STEAM_OUTLET.get().defaultBlockState(),3);
        var before=dev.bwr.mod.eccs.AssemblyPlumbing.trace(l,root,s,role);
        var branch=face.getAxis()==net.minecraft.core.Direction.Axis.X?net.minecraft.core.Direction.NORTH:net.minecraft.core.Direction.EAST;
        var pos=port.relative(face,2).relative(branch);
        l.setBlock(pos,BwrBlocks.TURBINE_CONTROL_VALVE.get().defaultBlockState().setValue(dev.bwr.mod.steam.TurbineValveBlock.FACING,branch),3);
        var valve=(dev.bwr.mod.steam.TurbineValveBlockEntity)l.getBlockEntity(pos);valve.setTarget(.1);valve.stroke(2);
        var after=dev.bwr.mod.eccs.AssemblyPlumbing.trace(l,root,s,role);
        System.out.println("REGRESSION DEAD BRANCH: before="+before.opening()+" after="+after.opening()+" same sources="+before.ends().equals(after.ends()));
        h.assertTrue(before.opening()==1&&after.opening()==1&&before.ends().equals(after.ends()),"unused side branch throttled a valid route");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void reactorOwnershipAndResize(GameTestHelper h) throws Exception {
        var l=h.getLevel();var origin=new BlockPos(520,200,520);
        var validate=dev.bwr.mod.reactor.ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate",Level.class);
        validate.setAccessible(true);
        buildVessel(l,origin,7);
        var a=origin.offset(-1,3,2);var b=origin.offset(7,3,2);
        for(var p:java.util.List.of(a,b))l.setBlock(p,BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(),2);
        var first=(dev.bwr.mod.reactor.ReactorControllerBlockEntity)l.getBlockEntity(a);
        var second=(dev.bwr.mod.reactor.ReactorControllerBlockEntity)l.getBlockEntity(b);
        validate.invoke(first,l);validate.invoke(second,l);
        h.assertTrue(!first.isFormed()&&!second.isFormed(),"two controllers claimed one vessel");
        l.setBlock(b,BwrBlocks.REACTOR_VESSEL.get().defaultBlockState(),2);
        validate.invoke(first,l);h.assertTrue(first.isFormed(),"sole controller did not reform");
        var fuel=new net.minecraft.world.item.ItemStack(dev.bwr.mod.registry.BwrItems.FUEL_ASSEMBLY.get());
        int outside=first.corePositions()[first.corePositions().length-1];first.loadAssembly(outside,fuel);
        // Resize the physical footprint while preserving the west-wall controller.
        buildVessel(l,origin,5);validate.invoke(first,l);
        h.assertTrue(!first.isFormed(),"shrink hid live fuel");
        h.assertTrue(first.saveWithoutMetadata(l.registryAccess()).getList("CoreFuel",10).size()==1,"rejected shrink lost fuel");
        buildVessel(l,origin,7);validate.invoke(first,l);
        h.assertTrue(first.isFormed()&&java.util.Arrays.stream(first.corePositions()).anyMatch(p->p==outside),"restoring the vessel did not recover the outer bundle");
        var exposure=new dev.bwr.mod.fuel.FuelAssemblyData("leu",.04,180,12345,.31);
        first.core().getCoreLoading().load(outside,dev.bwr.mod.fuel.FuelAssemblies.toCore(exposure));
        var saved=first.saveWithoutMetadata(l.registryAccess());
        // Recovery must work on a reloaded controller before its first formation tick.
        var pending=new dev.bwr.mod.reactor.ReactorControllerBlockEntity(a,first.getBlockState());
        pending.loadWithComponents(saved,l.registryAccess());
        var recovered=pending.takeFuelForRemoval();
        h.assertTrue(recovered.size()==1&&dev.bwr.mod.fuel.FuelAssemblies.dataOf(recovered.getFirst()).equals(exposure),"pending fuel recovery lost exposure");
        h.assertTrue(pending.takeFuelForRemoval().isEmpty(),"pending fuel recovered twice");
        l.destroyBlock(a,true);
        var drops=l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(a).inflate(2));
        var bundles=drops.stream().filter(e->e.getItem().is(dev.bwr.mod.registry.BwrItems.FUEL_ASSEMBLY.get())).toList();
        h.assertTrue(bundles.size()==1&&dev.bwr.mod.fuel.FuelAssemblies.dataOf(bundles.getFirst().getItem()).equals(exposure),"mining failed to preserve fuel exposure");
        h.assertTrue(first.takeFuelForRemoval().isEmpty(),"live fuel recovered twice");
        drops.forEach(net.minecraft.world.entity.item.ItemEntity::discard);
        h.succeed();
    }
    private static void buildVessel(net.minecraft.server.level.ServerLevel l,BlockPos o,int width){
        for(int x=-1;x<=width;x++)for(int y=-1;y<=8;y++)for(int z=-1;z<=width;z++){
            var p=o.offset(x,y,z);
            if(l.getBlockState(p).is(BwrBlocks.REACTOR_CONTROLLER.get()))continue;
            boolean shell=x==-1||x==width||y==-1||y==8||z==-1||z==width;
            l.setBlock(p,shell?BwrBlocks.REACTOR_VESSEL.get().defaultBlockState():Blocks.AIR.defaultBlockState(),2);
        }
        for(var drive:new dev.bwr.core.fuel.CompactCoreLayout(width,width).drives())
            l.setBlock(o.offset(drive.x(),-2,drive.z()),BwrBlocks.CONTROL_ROD_DRIVE.get().defaultBlockState(),2);
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void fabricatorEnergyPersistence(GameTestHelper h){
        var l=h.getLevel();var p=new BlockPos(600,200,600);
        l.removeBlock(p,false);
        l.setBlock(p,BwrBlocks.FUEL_FABRICATOR.get().defaultBlockState(),2);
        var chunk=l.getChunkAt(p);chunk.setUnsaved(false);
        var energy=l.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,p,net.minecraft.core.Direction.UP);
        h.assertTrue(energy.receiveEnergy(10000,true)==10000&&!chunk.isUnsaved(),"simulated charging dirtied the chunk");
        int accepted=energy.receiveEnergy(10000,false);
        System.out.println("REGRESSION FABRICATOR ENERGY: accepted="+accepted+" chunkDirty="+chunk.isUnsaved());
        h.assertTrue(accepted==10000&&chunk.isUnsaved(),"charging failed to mark chunk dirty");
        var saved=l.getBlockEntity(p).saveWithFullMetadata(l.registryAccess());
        l.removeBlockEntity(p);
        l.setBlockEntity(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(p,l.getBlockState(p),saved,l.registryAccess()));
        var restored=l.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,p,net.minecraft.core.Direction.UP);
        h.assertTrue(restored.getEnergyStored()==10000,"saved fabricator lost idle charge");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void poolOwnership(GameTestHelper h) throws Exception {
        var l=h.getLevel();var origin=h.absolutePos(BlockPos.ZERO).offset(400,240,400);
        var revalidate=SuppressionPoolBlockEntity.class.getDeclaredMethod("revalidate",Level.class);
        revalidate.setAccessible(true);
        loadAround(l,origin);loadAround(l,origin.east(12));
        // Separate 64-source basins, twelve blocks apart; updates suppressed during fixture setup.
        for(int offset:new int[]{0,12}) {
            for(int x=-1;x<=4;x++)for(int y=-1;y<=4;y++)for(int z=-1;z<=4;z++)
                l.setBlock(origin.offset(x+offset,y,z),Blocks.STONE.defaultBlockState(),2);
            for(int x=0;x<4;x++)for(int y=0;y<4;y++)for(int z=0;z<4;z++)
                l.setBlock(origin.offset(x+offset,y,z),Blocks.WATER.defaultBlockState(),2);
        }
        var a=origin.west();var b=origin.north();
        l.setBlock(a,BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get().defaultBlockState(),2);
        l.setBlock(b,BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get().defaultBlockState(),2);
        var first=(SuppressionPoolBlockEntity)l.getBlockEntity(a);
        var second=(SuppressionPoolBlockEntity)l.getBlockEntity(b);
        revalidate.invoke(first,l);revalidate.invoke(second,l);
        h.assertTrue(first.isFormed()&&!second.isFormed(),"duplicate pool controller formed");
        double firstDraw=first.pool().drawSuctionKg(1000000,1);
        double secondDraw=second.pool().drawSuctionKg(1000000,1);
        System.out.println("REGRESSION POOL DUPLICATE: physical=64000 kg; first="+firstDraw+" second="+secondDraw+" total="+(firstDraw+secondDraw));
        h.assertTrue(firstDraw+secondDraw<=64000,"basin water duplicated");
        // A quencher in the other basin must not belong to either controller of this basin.
        var q=origin.offset(12,-1,0);var srv=q.east(2);
        l.setBlock(q,BwrBlocks.SUPPRESSION_POOL_QUENCHER.get().defaultBlockState(),2);
        l.setBlock(q.east(),BwrBlocks.PRESSURISED_TUBE.get().defaultBlockState(),2);
        l.setBlock(srv,BwrBlocks.SAFETY_RELIEF_VALVE.get().defaultBlockState(),2);
        revalidate.invoke(first,l);
        System.out.println("REGRESSION WRONG BASIN: ownsQuencher="+first.ownsQuencher(q)+" countedDischargingValves="+first.dischargingValveCount());
        h.assertTrue(!first.ownsQuencher(q)&&first.dischargingValveCount()==0,"another basin claimed steam");
        // Replacing only a controller must not reset the unchanged basin's water reserve.
        l.removeBlock(a,false);l.setBlock(a,BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get().defaultBlockState(),2);
        var replacement=(SuppressionPoolBlockEntity)l.getBlockEntity(a);revalidate.invoke(replacement,l);
        System.out.println("REGRESSION POOL REPLACE: previously drained reserve="+first.pool().getMassKg()+" replacement="+replacement.pool().getMassKg());
        h.assertTrue(replacement.pool().getMassKg()==first.pool().getMassKg()&&replacement.pool().getMassKg()<64000,"replacing controller replenished basin water");
        var registry=dev.bwr.mod.suppression.SuppressionBasinData.get(l);
        var saved=registry.save(new net.minecraft.nbt.CompoundTag(),l.registryAccess());
        var read=registry.getClass().getDeclaredMethod("load",net.minecraft.nbt.CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class);read.setAccessible(true);
        var restored=(dev.bwr.mod.suppression.SuppressionBasinData)read.invoke(null,saved,l.registryAccess());
        var cells=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for(int x=0;x<4;x++)for(int y=0;y<4;y++)for(int z=0;z<4;z++)cells.add(origin.offset(x,y,z).asLong());
        var claim=restored.claim(l,a,cells,new dev.bwr.core.pool.SuppressionPool());
        h.assertTrue(claim.problem()==null&&claim.pool().getMassKg()==replacement.pool().getMassKg(),"saved basin inventory changed on reload");
        h.succeed();
    }
    private static void loadAround(net.minecraft.server.level.ServerLevel l,BlockPos p){
        for(int x=(p.getX()-24)>>4;x<=(p.getX()+24)>>4;x++)for(int z=(p.getZ()-24)>>4;z<=(p.getZ()+24)>>4;z++)l.getChunk(x,z);
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void liveFuelReload(GameTestHelper h) throws Exception {
        var l=h.getLevel();var o=new BlockPos(820,200,820);buildVessel(l,o,5);
        var p=o.west().above(3);l.setBlock(p,BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(),2);
        var r=(dev.bwr.mod.reactor.ReactorControllerBlockEntity)l.getBlockEntity(p);
        var validate=r.getClass().getDeclaredMethod("revalidate",Level.class);validate.setAccessible(true);validate.invoke(r,l);
        h.assertTrue(r.isFormed(),"reload fixture not formed");
        var fuel=new dev.bwr.mod.fuel.FuelAssemblyData("leu",.04,180,12345,.31);
        int slot=r.corePositions()[0];r.core().getCoreLoading().load(slot,dev.bwr.mod.fuel.FuelAssemblies.toCore(fuel));
        r.refreshFuelDefinitions();double oldK=r.core().getCoreLoading().aggregateKInf();var old=java.util.List.copyOf(dev.bwr.mod.fuel.FuelTypes.all());
        try(var stream=PhaseOneRegressionTests.class.getResourceAsStream("/data/bwr/fuel_type/leu.json")) {
            var json=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();json.addProperty("k_inf_base",1.32);json.addProperty("beta",.005);
            var loader=new dev.bwr.mod.fuel.FuelTypeLoader();var apply=loader.getClass().getDeclaredMethod("apply",java.util.Map.class,net.minecraft.server.packs.resources.ResourceManager.class,net.minecraft.util.profiling.ProfilerFiller.class);apply.setAccessible(true);
            apply.invoke(loader,java.util.Map.of(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bwr","leu"),json),l.getServer().getResourceManager(),net.minecraft.util.profiling.InactiveProfiler.INSTANCE);
            r.refreshFuelDefinitions();var loaded=r.core().getCoreLoading().assemblyAt(slot);
            h.assertTrue(loaded.fuelType()==dev.bwr.mod.fuel.FuelTypes.byName("leu")&&loaded.fuelType().kInfBase()==1.32,"live core retained old fuel definition");
            h.assertTrue(dev.bwr.mod.fuel.FuelAssemblyData.of(loaded).equals(fuel),"reload reset bundle exposure");
            h.assertTrue(r.core().getCoreLoading().aggregateKInf()>oldK,"reload did not update derived loading");
            h.assertTrue(Math.abs(r.core().toState().betaEff()-.005)<1e-10,"reload left kinetics on old delayed-neutron constants");
        } finally {dev.bwr.mod.fuel.FuelTypes.replaceAll(old);r.refreshFuelDefinitions();}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void remotePortReplacement(GameTestHelper h) {
        var l=h.getLevel();var root=new BlockPos(880,220,880);loadAround(l,root);
        for(var b:java.util.List.of(BwrBlocks.NUCLEAR_GENERATOR.get())){
            var state=b.placementState();l.setBlock(root,state,3);b.setPlacedBy(l,root,state,null,new net.minecraft.world.item.ItemStack(b));
            var owner=l.getBlockEntity(root);var saved=owner.saveWithFullMetadata(l.registryAccess());saved.putDouble("Energy",10000);saved.putDouble("Water",10000);owner.loadWithComponents(saved,l.registryAccess());
            var pos=b.generator()?b.terminal(root,state):b.portPosition(root,state,dev.bwr.mod.eccs.AssemblyPort.WATER_DISCHARGE);
            var side=b.generator()?b.terminalFace(state):b.portFace(state,dev.bwr.mod.eccs.AssemblyPort.WATER_DISCHARGE);
            var energy=b.generator()?l.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,pos,side):null;
            var water=b.generator()?null:l.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,pos,side);
            h.assertTrue(b.generator()?energy!=null:water!=null,"no remote port capability");
            l.removeBlockEntity(root);
            h.assertTrue(b.generator()?energy.extractEnergy(1000,false)==0:water.drain(1000,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE).isEmpty(),"cached port extracted from removed controller");
            // A cache acquired while the root is absent must also reconnect without replacing the pipe.
            var disconnected=b.generator()?l.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,pos,side):null;
            var newOwner=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(root,state,saved,l.registryAccess());l.setBlockEntity(newOwner);
            int taken=b.generator()?energy.extractEnergy(1000,false):water.drain(1000,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE).getAmount();
            h.assertTrue(taken==1000,"cached port failed to reconnect");
            h.assertTrue(b.generator()?disconnected!=null&&disconnected.getEnergyStored()==9000:((dev.bwr.mod.power.PowerModuleBlockEntity)newOwner).waterStored()==9000,"remote transfer did not debit current inventory");
            h.assertTrue(((dev.bwr.mod.power.PowerModuleBlockEntity)owner).energyStored()==10000&&((dev.bwr.mod.power.PowerModuleBlockEntity)owner).waterStored()==10000,"obsolete owner was mutated");
            l.removeBlock(root,false);
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void unloadedScans(GameTestHelper h) throws Exception {
        var l=h.getLevel();var root=new BlockPos(32015,180,32015);l.getChunkAt(root);
        for(var b:java.util.List.of(BwrBlocks.REACTOR_CONTROLLER.get(),BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get(),
                BwrBlocks.HPCS_PUMP.get(),BwrBlocks.ADS_CONTROLLER.get(),BwrBlocks.MOTOR_FEED_PUMP.get())){
            l.setBlock(root,b.defaultBlockState(),2);var be=l.getBlockEntity(root);
            var absent=new java.util.ArrayList<BlockPos>();
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++){var p=root.offset(16*x,0,16*z);if(!l.isLoaded(p))absent.add(p);}
            h.assertTrue(!absent.isEmpty(),"no unloaded chunks around scan fixture");
            String method=be instanceof dev.bwr.mod.eccs.EccsPumpBlockEntity || be instanceof dev.bwr.mod.eccs.AdsControllerBlockEntity
                    ? "rebind" : be instanceof dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity ? "maybeRebind" : "revalidate";
            var validate=be.getClass().getDeclaredMethod(method,Level.class);validate.setAccessible(true);validate.invoke(be,l);
            for(var p:absent)h.assertTrue(!l.isLoaded(p),b+" validation loaded missing chunk "+p);
            l.removeBlock(root,false);
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void remoteWaterReconnect(GameTestHelper h) {
        var l=h.getLevel();var root=new BlockPos(1280,200,1280);loadAround(l,root);
        for(var block:java.util.List.of(BwrBlocks.ARABELLE_CONDENSER.get(),BwrBlocks.CIRCULATING_WATER_PUMP.get(),BwrBlocks.MOTOR_FEED_PUMP.get())) {
            l.removeBlock(root,false);
            var state=block instanceof dev.bwr.mod.eccs.PumpAssemblyBlock p?p.placementState():block.defaultBlockState();
            l.setBlock(root,state,3);block.setPlacedBy(l,root,state,null,new net.minecraft.world.item.ItemStack(block));
            var owner=l.getBlockEntity(root);BlockPos port;net.minecraft.core.Direction face;
            if(owner instanceof dev.bwr.mod.condenser.CondenserBlockEntity c){
                var cell=c.layout().ports.stream().filter(p->p.role()==dev.bwr.mod.condenser.CondenserBlock.Port.COLD).findFirst().orElseThrow();
                port=c.layout().world(root,state.getValue(dev.bwr.mod.condenser.CondenserBlock.FACING),cell);face=dev.bwr.mod.condenser.CondenserBlock.portFace(l.getBlockState(port));
            }else if(owner instanceof dev.bwr.mod.cooling.CoolingBlockEntity c){
                var cell=c.layout().ports.stream().filter(p->p.role()==dev.bwr.mod.cooling.CoolingBlock.Port.INLET).findFirst().orElseThrow();
                port=c.layout().world(root,state.getValue(dev.bwr.mod.cooling.CoolingBlock.FACING),cell);face=dev.bwr.mod.cooling.CoolingBlock.portFace(l.getBlockState(port));
            }else{
                var b=(dev.bwr.mod.eccs.PumpAssemblyBlock)block;port=b.portPosition(root,state,dev.bwr.mod.eccs.AssemblyPort.WATER_SUCTION);face=b.portFace(state,dev.bwr.mod.eccs.AssemblyPort.WATER_SUCTION);
            }
            var cached=l.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,port,face);
            var water=new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,500);
            var execute=net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE;
            h.assertTrue(cached!=null&&cached.fill(water,execute)==500,"initial remote fill failed: "+block);
            var saved=owner.saveWithFullMetadata(l.registryAccess());l.removeBlockEntity(root);
            // Query a second connection during the controller replacement window.
            var whileMissing=l.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,port,face);
            h.assertTrue(whileMissing!=null,"missing controller was cached as a permanently null port");
            var restored=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(root,state,saved,l.registryAccess());l.setBlockEntity(restored);
            if(restored instanceof dev.bwr.mod.condenser.CondenserBlockEntity c)c.structureDirty=true;
            if(restored instanceof dev.bwr.mod.cooling.CoolingBlockEntity c)c.structureDirty=true;
            h.assertTrue(cached.fill(water,execute)==500&&whileMissing.fill(water,execute)==500,"remote fluid port did not reconnect: "+block);
            h.assertTrue(cached.getFluidInTank(0).getAmount()==1500,"reconnected port lost or duplicated water: "+block);
            l.removeBlock(root,false);
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=200)
    public static void incompleteAssemblyRecovery(GameTestHelper h) {
        var l=h.getLevel();var root=new BlockPos(960,200,960);long time=l.getGameTime();
        var fake=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);var oldMode=fake.gameMode.getGameModeForPlayer();var oldPos=fake.position();
        try {
            for(var block:java.util.List.of(BwrBlocks.HPCS_PUMP.get(),BwrBlocks.RCIC_TWL.get(),BwrBlocks.LP_TURBINE.get(),BwrBlocks.ARABELLE_CONDENSER.get(),BwrBlocks.CIRCULATING_WATER_PUMP.get(),BwrBlocks.CONDENSATE_STORAGE_TANK.get())){
                for(int x=58;x<=62;x++)for(int z=58;z<=62;z++)l.getChunk(x,z);
                var state=block instanceof dev.bwr.mod.eccs.PumpAssemblyBlock p?p.placementState():block.defaultBlockState();
                l.setBlock(root,state,3);block.setPlacedBy(l,root,state,null,new net.minecraft.world.item.ItemStack(block));
                if(block==BwrBlocks.CONDENSATE_STORAGE_TANK.get()){
                    var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);player.setPos(1000,250,1000);
                    var message=dev.bwr.mod.eccs.CondensateTankAssembly.resize((dev.bwr.mod.eccs.CondensateStorageTankBlockEntity)l.getBlockEntity(root),player,3,3);
                    h.assertTrue(message.startsWith("Assembled"),message);state=l.getBlockState(root);
                }
                var cells=dev.bwr.mod.world.AssemblyAccess.cells(l,root,state);
                var states=new java.util.HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();var tags=new java.util.HashMap<BlockPos,net.minecraft.nbt.CompoundTag>();
                for(var p:cells){states.put(p,l.getBlockState(p));var be=l.getBlockEntity(p);if(be!=null)tags.put(p,be.saveWithFullMetadata(l.registryAccess()));}
                var missing=cells.stream().filter(p->!p.equals(root)).findFirst().orElseThrow();
                // Recreate the saved state left when only a child chunk was available at removal.
                l.removeBlock(root,false);
                for(var p:cells)if(!p.equals(missing))l.setBlock(p,states.get(p),2);
                for(var e:tags.entrySet())if(!e.getKey().equals(missing))l.setBlockEntity(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(e.getKey(),states.get(e.getKey()),e.getValue(),l.registryAccess()));
                ((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime((time/20+1)*20);
                var owner=l.getBlockEntity(root);
                if(owner instanceof dev.bwr.mod.condenser.CondenserBlockEntity c)dev.bwr.mod.condenser.CondenserBlockEntity.serverTick(l,root,state,c);
                else if(owner instanceof dev.bwr.mod.cooling.CoolingBlockEntity c)dev.bwr.mod.cooling.CoolingBlockEntity.serverTick(l,root,state,c);
                else state.tick(l,root,l.random);
                for(var p:cells)h.assertTrue(!l.getBlockState(p).is(block),"incomplete assembly survived recovery: "+block+" at "+p);
                l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(root).inflate(30)).forEach(net.minecraft.world.entity.item.ItemEntity::discard);
            }
        } finally {((net.minecraft.world.level.storage.ServerLevelData)l.getLevelData()).setGameTime(time);fake.setGameMode(oldMode);fake.setPos(oldPos);}
        h.succeed();
    }
}
