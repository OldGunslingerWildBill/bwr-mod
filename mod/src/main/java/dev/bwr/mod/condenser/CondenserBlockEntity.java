package dev.bwr.mod.condenser;

import dev.bwr.core.thermal.Saturation;
import dev.bwr.core.turbine.*;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.steam.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;

/** Parts hold only owner/index data; exactly one controller owns all inventories. */
public class CondenserBlockEntity extends BlockEntity {
    private BlockPos root;
    private UUID assembly=UUID.randomUUID();
    private int cell;
    private int layoutVersion=4;
    private final SurfaceCondenser plant;
    private final Map<CondenserBlock.Port,IFluidHandler> handlers=new EnumMap<>(CondenserBlock.Port.class);
    private long checkedAt=Long.MIN_VALUE;
    private boolean complete;
    public boolean forming,structureDirty=true;
    public CondenserBlockEntity(BlockPos p,BlockState s){
        super(BwrBlockEntities.CONDENSER.get(),p,s);root=p.immutable();cell=CondenserLayout.INSTANCE.controllerIndex();
        plant=s.getValue(CondenserBlock.CONTROLLER)?new SurfaceCondenser():null;
    }
    public BlockPos root(){return root;}
    public int cellIndex(){return cell;}
    public CondenserLayout layout(){return switch(layoutVersion){case 1->CondenserLayout.LEGACY;case 2->CondenserLayout.COMPACT_V2;case 3->CondenserLayout.WIDE_V3;default->CondenserLayout.INSTANCE;};}
    public SurfaceCondenser plant(){return plant;}
    public void bind(CondenserBlockEntity owner,int index){root=owner.worldPosition;assembly=owner.assembly;cell=index;layoutVersion=owner.layoutVersion;setChanged();}
    public boolean matches(CondenserBlockEntity owner){return root.equals(owner.worldPosition)&&assembly.equals(owner.assembly)
            &&layoutVersion==owner.layoutVersion&&getBlockState().getValue(CondenserBlock.FACING)==owner.getBlockState().getValue(CondenserBlock.FACING);}
    public CondenserBlockEntity owner(){
        if(level==null||isRemoved()||!level.isLoaded(root))return null;
        if(level.getBlockEntity(root) instanceof CondenserBlockEntity owner&&owner.plant!=null&&!owner.isRemoved()&&matches(owner))return owner;
        return null;
    }
    public boolean ready(){
        if(plant==null||forming||owner()!=this)return false;
        var layout=layout();var facing=getBlockState().getValue(CondenserBlock.FACING);
        var bounds=layout.bounds(worldPosition,facing);
        for(int x=((int)Math.floor(bounds.minX))>>4;x<=((int)Math.ceil(bounds.maxX)-1)>>4;x++)
            for(int z=((int)Math.floor(bounds.minZ))>>4;z<=((int)Math.ceil(bounds.maxZ)-1)>>4;z++)
                if(!level.isLoaded(new BlockPos(x*16,worldPosition.getY(),z*16)))return false;
        if(structureDirty||checkedAt==Long.MIN_VALUE||level.getGameTime()-checkedAt>=20){
            complete=true;
            for(var entry:layout.cells){var p=layout.world(worldPosition,facing,entry);
                if(!(level.getBlockEntity(p) instanceof CondenserBlockEntity part)||!part.matches(this)||part.cell!=entry.index()){
                    complete=false;break;
                }
            }
            checkedAt=level.getGameTime();structureDirty=false;
        }
        return complete;
    }
    public static boolean acceptsSteam(Level l,BlockPos p){
        if(!(l.getBlockEntity(p) instanceof CondenserBlockEntity part))return false;
        var owner=part.owner();return owner!=null&&owner.ready()&&owner.plant.steam.space()>1e-6;
    }
    public IFluidHandler water(CondenserBlock.Port role){
        if(plant==null||!role.water())return null;
        return handlers.computeIfAbsent(role,r->new IFluidHandler(){
            private double enthalpy(){return switch(r){case COLD->plant.coldH();case HOT->plant.hotH();default->plant.condensateH();};}
            private double stored(){return switch(r){case COLD->plant.cold();case HOT->plant.hot();default->plant.condensate();};}
            private boolean live(){return owner()==CondenserBlockEntity.this&&ready();}
            public int getTanks(){return 1;}
            public FluidStack getFluidInTank(int tank){int n=tank==0&&live()?(int)Math.floor(stored()):0;return n>0?dev.bwr.mod.water.ThermalWater.stack(n,enthalpy()):FluidStack.EMPTY;}
            public int getTankCapacity(int tank){return tank==0?(r==CondenserBlock.Port.CONDENSATE||r==CondenserBlock.Port.MAKEUP?SurfaceCondenser.CONDENSATE_CAPACITY:SurfaceCondenser.COOLING_CAPACITY):0;}
            public boolean isFluidValid(int tank,FluidStack stack){return tank==0&&r.inlet()&&stack.is(Fluids.WATER);}
            private double fillInlet(double amount,double h,boolean simulate){return r==CondenserBlock.Port.MAKEUP?plant.fillMakeup(amount,h,simulate):plant.fillCold(amount,h,simulate);}
            public int fill(FluidStack stack,FluidAction action){
                if(!live()||!isFluidValid(0,stack))return 0;
                int n=(int)Math.floor(fillInlet(stack.getAmount(),dev.bwr.mod.water.ThermalWater.enthalpy(stack),true));
                if(n>0&&action.execute()){fillInlet(n,dev.bwr.mod.water.ThermalWater.enthalpy(stack),false);setChanged();}return n;
            }
            public FluidStack drain(FluidStack stack,FluidAction action){return stack.is(Fluids.WATER)?drain(stack.getAmount(),action):FluidStack.EMPTY;}
            public FluidStack drain(int max,FluidAction action){
                if(r.inlet()||!live())return FluidStack.EMPTY;
                int n=(int)Math.floor(Math.min(Math.max(0,max),stored()));if(n<=0)return FluidStack.EMPTY;
                double h=enthalpy();
                if(action.execute()){if(r==CondenserBlock.Port.HOT)plant.drainHot(n,false);else plant.drainCondensate(n,false);setChanged();}
                return dev.bwr.mod.water.ThermalWater.stack(n,h);
            }
        });
    }
    public static void serverTick(Level l,BlockPos p,BlockState s,CondenserBlockEntity be){
        if(be.plant==null)return;
        be.plant.clearReadouts();if(!be.ready()){
            if(!be.forming && be.owner()==be && l.getGameTime()%20==0 && dev.bwr.mod.world.AssemblyAccess.allLoaded(l,p,s))l.destroyBlock(p,true);
            return;
        }
        be.pushWater();be.pullSteam();double before=be.plant.steam.mass();be.plant.tick(.05);
        if(before!=be.plant.steam.mass())be.setChanged();
    }
    private void pullSteam(){
        var layout=layout();var facing=getBlockState().getValue(CondenserBlock.FACING);
        double budget=SurfaceCondenser.HEAT_REJECTION_MW*1000/20;
        for(var port:layout.ports){if(port.role()!=CondenserBlock.Port.BYPASS)continue;
            var at=layout.world(worldPosition,facing,port);
            // Geometry is shared and event-invalidated; current source inventories are resolved below.
            var survey=SteamLineNetwork.survey(level,at);if(survey.truncated())continue;
            for(var source:survey.nozzles()){
                if(!level.isLoaded(source)||!(level.getBlockEntity(source) instanceof RpvSteamOutletBlockEntity nozzle))continue;
                var controller=nozzle.getControllerPos();
                if(controller==null||!level.isLoaded(controller)||!(level.getBlockEntity(controller) instanceof ReactorControllerBlockEntity reactor)||!reactor.isFormed())continue;
                double pressure=Saturation.psiaFromPsig(reactor.core().getPressurePsig());double h=Saturation.vapourEnthalpyKJPerKg(pressure);
                double heat=Math.max(1,h-Saturation.subcooledLiquidEnthalpyKJPerKg(SurfaceCondenser.CONDENSATE_C));
                double wanted=Math.min(plant.steam.space(),budget/heat);if(wanted<=0)continue;
                double kg=SteamValveRouting.claim(level,at,nozzle,wanted*20)/20;
                if(kg>0){plant.steam.offer(new SteamInventory.Packet(kg,h,pressure));budget=Math.max(0,budget-kg*heat);setChanged();}
            }
        }
    }
    private void pushWater(){
        var layout=layout();var facing=getBlockState().getValue(CondenserBlock.FACING);
        for(var port:layout.ports){if(port.role()!=CondenserBlock.Port.HOT&&port.role()!=CondenserBlock.Port.CONDENSATE)continue;
            var pos=layout.world(worldPosition,facing,port);var state=level.getBlockState(pos);var face=CondenserBlock.portFace(state);var next=pos.relative(face);
            if(!level.isLoaded(next))continue;var out=water(port.role());var available=out.drain(Integer.MAX_VALUE,IFluidHandler.FluidAction.SIMULATE);if(available.isEmpty())continue;
            var target=level.getCapability(Capabilities.FluidHandler.BLOCK,next,face.getOpposite());
            if(target!=null){int n=target.fill(available,IFluidHandler.FluidAction.EXECUTE);out.drain(n,IFluidHandler.FluidAction.EXECUTE);}
        }
    }
    @Override public void onLoad(){super.onLoad();if(level!=null&&!level.isClientSide()&&plant==null)level.scheduleTick(worldPosition,getBlockState().getBlock(),20);}
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r){return saveWithoutMetadata(r);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){
        super.saveAdditional(t,r);t.putLong("Root",root.asLong());t.putUUID("Assembly",assembly);t.putInt("Cell",cell);t.putInt("LayoutVersion",layoutVersion);
        if(plant!=null){t.putDouble("ColdH",plant.coldH());t.putDouble("HotH",plant.hotH());t.putDouble("CondensateH",plant.condensateH());t.putDouble("Cold",plant.cold());t.putDouble("Hot",plant.hot());t.putDouble("Condensate",plant.condensate());
            t.putDouble("SteamMass",plant.steam.mass());t.putDouble("SteamEnthalpy",plant.steam.enthalpy());t.putDouble("SteamPressure",plant.steam.pressure());}
    }
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){
        super.loadAdditional(t,r);root=t.contains("Root")?BlockPos.of(t.getLong("Root")):worldPosition;
        layoutVersion=t.contains("LayoutVersion")?Math.clamp(t.getInt("LayoutVersion"),1,4):1;
        if(t.hasUUID("Assembly"))assembly=t.getUUID("Assembly");cell=t.contains("Cell")?t.getInt("Cell"):layout().controllerIndex();
        if(plant!=null){plant.restore(t.getDouble("Cold"),t.getDouble("Hot"),t.getDouble("Condensate"),
                t.contains("ColdH")?t.getDouble("ColdH"):Saturation.subcooledLiquidEnthalpyKJPerKg(SurfaceCondenser.COLD_C),
                t.contains("HotH")?t.getDouble("HotH"):Saturation.subcooledLiquidEnthalpyKJPerKg(SurfaceCondenser.HOT_C),
                t.contains("CondensateH")?t.getDouble("CondensateH"):Saturation.subcooledLiquidEnthalpyKJPerKg(SurfaceCondenser.CONDENSATE_C));
            plant.steam.restore(t.getDouble("SteamMass"),t.getDouble("SteamEnthalpy"),t.getDouble("SteamPressure"));}
        structureDirty=true;checkedAt=Long.MIN_VALUE;
    }
}
