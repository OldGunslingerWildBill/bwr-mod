package dev.bwr.mod.devtest;

import dev.bwr.mod.gui.*;
import dev.bwr.mod.gui.client.*;
import dev.bwr.mod.gui.net.RefuellingBatchPayload;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import java.util.*;

/** Real mouse input, wire commands and inventory conservation on the 17x17x28 fixture. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class RefuellingClientCheck {
    private static final BlockPos MIN=new BlockPos(160,190,160),ROOT=MIN.offset(-1,3,7);
    private static boolean started,requested,checked;private static int age,joined,stage,shown;
    private static volatile Throwable failure;
    private static int[] selected;
    private static final String[] NAMES={"selected-zoomed","loaded","unloaded","marked","swapped"};
    private static void check(boolean condition,String why){if(!condition)throw new AssertionError(why);}
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event)throws Exception {
        if(!Boolean.getBoolean("bwr.refuellingCheck"))return;
        if(failure!=null)throw new AssertionError("Server batch checks",failure);
        var mc=Minecraft.getInstance();if(++age>2500)throw new AssertionError("Refuelling timeout stage "+stage);
        if(!started&&mc.screen!=null&&mc.getOverlay()==null) {
            started=true;mc.options.pauseOnLostFocus=false;mc.options.guiScale().set(3);
            mc.createWorldOpenFlows().createFreshLevel("bwr-refuelling-"+System.currentTimeMillis(),new LevelSettings("Refuelling check",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0,false,false),r->r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);return;
        }
        if(mc.player==null||mc.level==null||mc.getSingleplayerServer()==null||++joined<40)return;
        if(!requested){
            requested=true;var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
            server.execute(()->{try{
                var p=server.getPlayerList().getPlayer(uuid);var l=p.serverLevel();
                p.setNoGravity(true);p.getAbilities().flying=true;p.onUpdateAbilities();p.connection.teleport(ROOT.getX()-2,ROOT.getY(),ROOT.getZ(),-90,0);
                var be=CompactCoreRegressionTests.build(l,MIN,15,15,25,false);be.setVesselState(VesselState.REFUELING);
                serverChecks(p,be);RefuellingMenu.open(p,be);
            }catch(Throwable ex){failure=ex;}});
        }
        if(!(mc.screen instanceof RefuellingScreen screen)||!(mc.player.containerMenu instanceof RefuellingMenu menu)||menu.map==null)return;
        var grid=(LatticeGridWidget)screen.children().stream().filter(c->c instanceof LatticeGridWidget).findFirst().orElseThrow();
        if(++shown<30)return;
        if(!checked){
            checked=true;
            if(stage==0){
                int[] a=point(grid,0);screen.mouseClicked(a[0],a[1],0);screen.mouseReleased(a[0],a[1],0);
                int[] b=point(grid,1);screen.mouseClicked(b[0],b[1],0);screen.mouseReleased(b[0],b[1],0);
                check(grid.selectedCells().length==2,"Second click replaced selection");
                grid.onClick(b[0],b[1]);check(grid.selectedCells().length==1,"Click did not deselect");grid.onClick(b[0],b[1]);
                for(int i=0;i<5;i++)screen.mouseScrolled(a[0],a[1],0,1);
                grid.onClick(a[0],a[1]);check(!Arrays.stream(grid.selectedCells()).anyMatch(v->v==0),"Zoom anchor/hit-test drifted");grid.onClick(a[0],a[1]);
                int cellSize=number(grid,"cellPx");check(cellSize>2,"Wheel did not zoom");
                grid.onClick(grid.getX()-1,grid.getY());check(grid.selectedCells().length==2,"Clipped area changed selection");
                screen.mouseClicked(a[0],a[1],1);screen.mouseDragged(a[0]+12,a[1]+8,1,12,8);screen.mouseReleased(a[0]+12,a[1]+8,1);
                int[] p0=point(grid,0);grid.onClick(p0[0],p0[1]);check(grid.selectedCells().length==1,"Pan hit-test drifted");grid.onClick(p0[0],p0[1]);
                grid.resetView();check(number(grid,"cellPx")<cellSize,"FIT did not reset zoom");
                for(int i=2;i<5;i++){int[] p1=point(grid,i);grid.onClick(p1[0],p1[1]);}
                selected=grid.selectedCells();check(selected.length==5,"Selection lost after zoom/pan");
                int[] anchor=point(grid,0);for(int i=0;i<3;i++)grid.mouseScrolled(anchor[0],anchor[1],0,1);
            }
            if(stage==1)press(screen,"LOAD");
            if(stage==2)press(screen,"UNLOAD");
            if(stage==3){grid.clearSelection();int[] p=point(grid,0);grid.onClick(p[0],p[1]);}
            if(stage==4){grid.clearSelection();int[] p=point(grid,1);grid.onClick(p[0],p[1]);}
        }
        if(stage==1&&shown==80){check(menu.loadedAssemblies==5&&menu.bundlesInInventory==0,"Bulk load packet/inventory failed");}
        if(stage==2&&shown==80){check(menu.loadedAssemblies==0&&menu.bundlesInInventory==5,"Bulk unload lost/duplicated bundles");}
        if(stage==3&&shown==50)press(screen,"MARK");
        if(stage==3&&shown==80){check(grid.marked()==0&&grid.selectedCells().length==0,"MARK did not prepare single target");grid.setSelected(0);}
        if(stage==4&&shown==50){check(grid.selectedCells().length==1,"Swap target ambiguous");press(screen,"SWAP");}
        if(shown<100)return;
        var dir=mc.gameDirectory.toPath().resolve("refuelling-check");java.nio.file.Files.createDirectories(dir);
        try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(dir.resolve(NAMES[stage]+".png"));}
        com.mojang.logging.LogUtils.getLogger().info("REFUELLING CHECK PASS {}",NAMES[stage]);
        checked=false;shown=0;if(++stage==NAMES.length){java.nio.file.Files.writeString(dir.resolve("PASS.txt"),"PASS: zoom cursor anchor, clipping, right-drag pan, toggle selection, fit reset, batch load/unload wire commands, MARK/SWAP, duplicate/invalid slots, insufficient inventory, fuel exposure, full inventory drops, closed head refusal.\n");mc.stop();}
    }
    private static int number(LatticeGridWidget grid,String name)throws Exception {var f=LatticeGridWidget.class.getDeclaredField(name);f.setAccessible(true);return f.getInt(grid);}
    private static int[] point(LatticeGridWidget grid,int cell)throws Exception {
        var f=LatticeGridWidget.class.getDeclaredField("cells");f.setAccessible(true);var c=(LatticeGridWidget.Cells)f.get(grid);int slot=c.latticeIndex(cell),size=number(grid,"cellPx");
        return new int[]{number(grid,"originX")+(slot%c.latticeWidth()-number(grid,"minColumn"))*size+size/2,number(grid,"originY")+(slot/c.latticeWidth()-number(grid,"minRow"))*size+size/2};
    }
    private static void press(RefuellingScreen screen,String text){
        var b=(Button)screen.children().stream().filter(c->c instanceof Button v&&v.getMessage().getString().equals(text)).findFirst().orElseThrow();
        check(b.active,"Button disabled: "+text);b.onPress();
    }
    private static void serverChecks(net.minecraft.server.level.ServerPlayer p,ReactorControllerBlockEntity be) {
        var inv=p.getInventory();inv.clearContent();var m=new RefuellingMenu(9,inv,ROOT);
        var data=new dev.bwr.mod.fuel.FuelAssemblyData("leu",.035,180,12345,.31);
        inv.setItem(0,dev.bwr.mod.fuel.FuelAssemblyItem.stackOf(BwrItems.FUEL_ASSEMBLY.get(),data));
        inv.setItem(1,new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));inv.selected=0;
        m.handleBatch(p,true,new int[]{0,0,1,2});check(be.core().getCoreLoading().loadedAssemblyCount()==2,"Duplicates/exhaustion created fuel");
        inv.setItem(2,new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));m.handleBatch(p,true,new int[]{2,-1});
        check(be.core().getCoreLoading().loadedAssemblyCount()==2&&!inv.getItem(2).isEmpty(),"Invalid batch partly applied");
        be.setVesselState(VesselState.SHUTDOWN);m.handleBatch(p,false,new int[]{0,1});
        check(be.core().getCoreLoading().loadedAssemblyCount()==2,"Closed head allowed refuelling");be.setVesselState(VesselState.REFUELING);
        m.handleBatch(p,false,new int[]{0,0,1,2});check(be.core().getCoreLoading().loadedAssemblyCount()==0,"Batch unload incomplete");
        boolean recovered=false;for(int i=0;i<inv.getContainerSize();i++)if(inv.getItem(i).is(BwrItems.FUEL_ASSEMBLY.get())&&dev.bwr.mod.fuel.FuelAssemblies.dataOf(inv.getItem(i)).equals(data))recovered=true;
        check(recovered,"Exposure data lost");
        inv.clearContent();inv.setItem(0,new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));m.handleBatch(p,true,new int[]{0});
        for(int i=0;i<inv.getContainerSize();i++)inv.setItem(i,new ItemStack(Items.COBBLESTONE,64));
        m.handleBatch(p,false,new int[]{0});
        var drops=p.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,p.getBoundingBox().inflate(4));
        check(drops.stream().filter(e->e.getItem().is(BwrItems.FUEL_ASSEMBLY.get())).mapToInt(e->e.getItem().getCount()).sum()==1,"Full inventory lost/duplicated unload");
        drops.forEach(net.minecraft.world.entity.Entity::discard);inv.clearContent();
        for(int i=0;i<5;i++)inv.setItem(i,new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
        var buf=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),p.registryAccess());
        try{var command=new RefuellingBatchPayload(9,true,new int[]{0,1,763});RefuellingBatchPayload.STREAM_CODEC.encode(buf,command);var restored=RefuellingBatchPayload.STREAM_CODEC.decode(buf);check(Arrays.equals(command.slots(),restored.slots())&&restored.load()&&restored.containerId()==9,"Batch wire roundtrip");}finally{buf.release();}
    }
}
