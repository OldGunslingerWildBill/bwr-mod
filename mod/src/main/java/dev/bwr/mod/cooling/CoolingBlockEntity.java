package dev.bwr.mod.cooling;

import dev.bwr.core.turbine.CoolingWaterUnit;
import dev.bwr.core.turbine.CoolingWaterUnit.Design;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;

/** Sparse parts share one finite water inventory, one motor, and one tick. */
public class CoolingBlockEntity extends BlockEntity {
    private BlockPos root;private UUID assembly=UUID.randomUUID();private int cell;private int layoutVersion=3;
    private final CoolingWaterUnit unit;private final MachineEnergy energy;
    private final Map<CoolingBlock.Port,IFluidHandler> handlers=new EnumMap<>(CoolingBlock.Port.class);
    private long checkedAt=Long.MIN_VALUE,drainTick=Long.MIN_VALUE;private int drained;
    private boolean complete;public boolean forming,structureDirty=true;
    private double target,actual;private int draw;
    public float fanAngle,previousFanAngle;
    private double vaporActivity;
    private long vaporUpdated=Long.MIN_VALUE;
    public CoolingBlockEntity(BlockPos p,BlockState s){
        super(BwrBlockEntities.COOLING.get(),p,s);root=p.immutable();cell=layout().controllerIndex();
        unit=s.getValue(CoolingBlock.CONTROLLER)?new CoolingWaterUnit(design()):null;
        energy=unit==null?null:new MachineEnergy(EccsPower.fePerTickFromWatts(design().watts));
        target=design()==Design.NATURAL||design()==Design.INTAKE?1:0;
    }
    public Design design(){return ((CoolingBlock)getBlockState().getBlock()).design;}
    public CoolingLayout layout(){return layoutVersion==1?CoolingLayout.legacy(design()):layoutVersion==2?CoolingLayout.previous(design()):CoolingLayout.get(design());}
    public boolean previousPumpModel(){return layoutVersion<3&&CoolingLayout.revisedPump(design());}
    public BlockPos root(){return root;}public int cellIndex(){return cell;}public CoolingWaterUnit plant(){return unit;}
    public double target(){return target;}public double actual(){return actual;}public int draw(){return draw;}
    public int storedFE(){return energy==null?0:energy.getEnergyStored();}
    public double vaporActivity(){return vaporActivity;}
    public void setTarget(double n){if(Double.isFinite(n)&&n>=0&&n<=1&&design().watts>0){target=n;setChanged();}}
    public void bind(CoolingBlockEntity owner,int index){root=owner.worldPosition;assembly=owner.assembly;cell=index;layoutVersion=owner.layoutVersion;setChanged();}
    public boolean matches(CoolingBlockEntity owner){return root.equals(owner.worldPosition)&&assembly.equals(owner.assembly)&&getBlockState().getBlock()==owner.getBlockState().getBlock()&&getBlockState().getValue(CoolingBlock.FACING)==owner.getBlockState().getValue(CoolingBlock.FACING);}
    public CoolingBlockEntity owner(){
        if(level==null||isRemoved()||!level.isLoaded(root))return null;
        return level.getBlockEntity(root) instanceof CoolingBlockEntity o&&o.unit!=null&&!o.isRemoved()&&matches(o)?o:null;
    }
    public boolean ready(){
        if(unit==null||forming||owner()!=this)return false;
        var b=layout().bounds(worldPosition,getBlockState().getValue(CoolingBlock.FACING));
        for(int x=((int)Math.floor(b.minX))>>4;x<=((int)Math.ceil(b.maxX)-1)>>4;x++)for(int z=((int)Math.floor(b.minZ))>>4;z<=((int)Math.ceil(b.maxZ)-1)>>4;z++)
            if(!level.isLoaded(new BlockPos(x*16,worldPosition.getY(),z*16)))return false;
        if(structureDirty||checkedAt==Long.MIN_VALUE||level.getGameTime()-checkedAt>=20){
            complete=true;
            for(var c:layout().cells){var p=layout().world(root,getBlockState().getValue(CoolingBlock.FACING),c);
                if(!(level.getBlockEntity(p) instanceof CoolingBlockEntity part)||!part.matches(this)||part.cell!=c.index()){complete=false;break;}}
            checkedAt=level.getGameTime();structureDirty=false;
        }return complete;
    }
    public IEnergyStorage power(){
        return new IEnergyStorage(){
            public int receiveEnergy(int n,boolean simulate){if(!ready())return 0;int got=energy.receiveEnergy(n,simulate);if(got>0&&!simulate)setChanged();return got;}
            public int extractEnergy(int n,boolean simulate){return 0;}
            public int getEnergyStored(){return ready()?energy.getEnergyStored():0;}
            public int getMaxEnergyStored(){return energy.getMaxEnergyStored();}
            public boolean canExtract(){return false;}public boolean canReceive(){return design().watts>0;}
        };
    }
    public IFluidHandler water(CoolingBlock.Port role){
        if(unit==null||!role.water())return null;
        return handlers.computeIfAbsent(role,r->new IFluidHandler(){
            public int getTanks(){return 1;}
            public FluidStack getFluidInTank(int tank){int n=tank==0&&ready()?(int)Math.floor(r==CoolingBlock.Port.INLET?unit.input():unit.output()):0;return n>0?dev.bwr.mod.water.ThermalWater.stack(n,r==CoolingBlock.Port.INLET?unit.inputH():unit.outputH()):FluidStack.EMPTY;}
            public int getTankCapacity(int tank){return tank==0?design().capacity:0;}
            public boolean isFluidValid(int tank,FluidStack stack){return tank==0&&r!=CoolingBlock.Port.OUTLET&&stack.is(Fluids.WATER);}
            public int fill(FluidStack stack,FluidAction action){
                if(!ready()||!isFluidValid(0,stack))return 0;
                int n=(int)Math.floor(r==CoolingBlock.Port.INLET?unit.fillInput(stack.getAmount(),dev.bwr.mod.water.ThermalWater.enthalpy(stack),true):unit.fillMakeup(stack.getAmount(),dev.bwr.mod.water.ThermalWater.enthalpy(stack),true));
                if(n>0&&action.execute()){if(r==CoolingBlock.Port.INLET)unit.fillInput(n,dev.bwr.mod.water.ThermalWater.enthalpy(stack),false);else unit.fillMakeup(n,dev.bwr.mod.water.ThermalWater.enthalpy(stack),false);setChanged();}return n;
            }
            public FluidStack drain(FluidStack stack,FluidAction action){return stack.is(Fluids.WATER)?drain(stack.getAmount(),action):FluidStack.EMPTY;}
            public FluidStack drain(int requested,FluidAction action){
                if(r!=CoolingBlock.Port.OUTLET||!ready()||design().watts>0&&!design().tower&&actual<=0)return FluidStack.EMPTY;
                long tick=level.getGameTime();int used=tick==drainTick?drained:0;
                double limit=design().flow/20*(design().watts>0&&!design().tower?actual:1);
                int n=(int)Math.floor(unit.drain(Math.min(Math.max(0,requested),Math.max(0,limit-used)),true));
                if(n<=0)return FluidStack.EMPTY;
                double h=unit.outputH();if(action.execute()){unit.drain(n,false);drainTick=tick;drained=used+n;setChanged();}return dev.bwr.mod.water.ThermalWater.stack(n,h);
            }
        });
    }
    /** Renewable lake abstraction: wet screen on two exterior sides; a solid lakebed is valid. */
    public boolean submerged(){
        if(level==null)return false;int count=0;
        for(Direction d:Direction.Plane.HORIZONTAL)if(source(root.relative(d,2))||source(root.relative(d,2).above()))count++;
        return count>=2;
    }
    private boolean source(BlockPos p){return level.isLoaded(p)&&level.getFluidState(p).isSource()&&level.getFluidState(p).is(Fluids.WATER);}
    public static void serverTick(Level l,BlockPos p,BlockState s,CoolingBlockEntity be){
        if(be.unit==null)return;
        be.unit.clearReadouts();be.actual=0;be.draw=0;
        if(!be.ready() && !be.forming && be.owner()==be && l.getGameTime()%20==0
                && dev.bwr.mod.world.AssemblyAccess.allLoaded(l,p,s)){l.destroyBlock(p,true);return;}
        if(be.ready()){
            int rating=EccsPower.fePerTickFromWatts(be.design().watts);
            be.actual=rating==0?1:Math.min(be.target,Math.cbrt((double)be.energy.getEnergyStored()/rating));
            be.draw=rating==0?0:Math.min(be.energy.getEnergyStored(),(int)Math.ceil(rating*Math.pow(be.actual,3)));
            be.energy.drain(be.draw);
            if(be.design()==Design.INTAKE){if(be.submerged())be.unit.fillInput(be.design().flow/20,false);else be.actual=0;}
            else if(!be.design().tower&&be.actual>0)CoolingWaterTransfer.pull(be);
            be.unit.tick(.05,be.actual);
            // Towers are basins: the circulation pump must draw their cooled output.
            if(!be.design().tower&&be.design()!=Design.INTAKE&&be.actual>0)be.push();
            if(be.draw>0||be.unit.flow()>0)be.setChanged();
        }
        be.vaporActivity=be.design().tower&&be.ready()&&be.unit.flow()>0&&be.unit.heatMW()>0
                ?Math.clamp(be.unit.flow()/be.design().flow,0,1):0;
        if(l.getGameTime()%5==0)l.sendBlockUpdated(p,s,s,2);
    }
    public static void clientTick(Level l,BlockPos p,BlockState s,CoolingBlockEntity be){
        be.previousFanAngle=be.fanAngle;
        be.fanAngle+=12*(float)be.actual; // Positive model-space Y rotation; 40 visual RPM at full speed.
        if(be.fanAngle > 3600){be.fanAngle-=3600;be.previousFanAngle-=3600;}
        if(!be.design().tower||be.vaporActivity<=0||l.getGameTime()-be.vaporUpdated>40)return;
        boolean natural=be.design()==Design.NATURAL;
        var bounds=be.layout().bounds(p,s.getValue(CoolingBlock.FACING));
        if(l.getNearestPlayer(bounds.getCenter().x,bounds.maxY,bounds.getCenter().z,256,false)==null)return;
        double strength=Math.sqrt(be.vaporActivity);
        // One overlapping layer each tick avoids bursts and keeps two full towers below the shared cap.
        {
            double x,z,y;
            if(natural){
                double a=l.random.nextDouble()*Math.PI*2,r=Math.sqrt(l.random.nextDouble())*(bounds.maxX-bounds.minX)*.10;
                x=bounds.getCenter().x+Math.cos(a)*r;z=bounds.getCenter().z+Math.sin(a)*r;y=bounds.maxY-.15;
            }else{
                var rotors=be.layout().rotors;if(rotors.isEmpty())return;
                var rotor=rotors.get((int)Math.floorMod(l.getGameTime()*7+p.asLong(),rotors.size()));var c=be.layout().controller;
                double dx=rotor.x-c.getX()-.5,dz=rotor.z-c.getZ()-.5;
                double a=Math.toRadians(switch(s.getValue(CoolingBlock.FACING)){case EAST->90;case SOUTH->180;case WEST->270;default->0;});
                x=p.getX()+.5+dx*Math.cos(a)-dz*Math.sin(a);z=p.getZ()+.5+dx*Math.sin(a)+dz*Math.cos(a);
                y=Math.max(bounds.maxY+.1,p.getY()+rotor.y-c.getY()+.7);
            }
            if(!l.canSeeSky(BlockPos.containing(x,y,z)))return;
            l.addParticle(dev.bwr.mod.registry.BwrParticles.COOLING_VAPOR.get(),true,x,y,z,
                    natural?5.0:3.4,natural?.43:.30,.12+.72*strength);
        }
    }
    private void push(){
        for(var port:layout().ports)if(port.role()==CoolingBlock.Port.OUTLET){
            var p=layout().world(root,getBlockState().getValue(CoolingBlock.FACING),port);var face=CoolingBlock.portFace(level.getBlockState(p));var next=p.relative(face);
            if(!level.isLoaded(next))continue;var out=water(CoolingBlock.Port.OUTLET);var available=out.drain(Integer.MAX_VALUE,IFluidHandler.FluidAction.SIMULATE);
            if(available.isEmpty())continue;var sink=level.getCapability(Capabilities.FluidHandler.BLOCK,next,face.getOpposite());
            if(sink!=null){int n=sink.fill(available,IFluidHandler.FluidAction.EXECUTE);out.drain(n,IFluidHandler.FluidAction.EXECUTE);}
        }
    }
    @Override public void onLoad(){super.onLoad();if(level!=null&&!level.isClientSide()&&unit==null)level.scheduleTick(worldPosition,getBlockState().getBlock(),20);}
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r){var t=saveWithoutMetadata(r);t.putDouble("VaporActivity",vaporActivity);return t;}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){
        super.saveAdditional(t,r);t.putLong("Root",root.asLong());t.putUUID("Assembly",assembly);t.putInt("Cell",cell);t.putInt("LayoutVersion",layoutVersion);
        if(unit!=null){t.putDouble("InputH",unit.inputH());t.putDouble("OutputH",unit.outputH());t.putDouble("Input",unit.input());t.putDouble("Output",unit.output());t.putDouble("Loss",unit.totalLoss());t.putDouble("Target",target);t.putDouble("Actual",actual);t.putInt("Energy",energy.getEnergyStored());}
    }
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){
        super.loadAdditional(t,r);layoutVersion=Math.clamp(t.getInt("LayoutVersion"),1,3);root=t.contains("Root")?BlockPos.of(t.getLong("Root")):worldPosition;if(t.hasUUID("Assembly"))assembly=t.getUUID("Assembly");cell=t.contains("Cell")?t.getInt("Cell"):layout().controllerIndex();
        if(unit!=null){unit.restore(t.getDouble("Input"),t.getDouble("Output"),t.getDouble("Loss"),
                t.contains("InputH")?t.getDouble("InputH"):dev.bwr.core.thermal.Saturation.subcooledLiquidEnthalpyKJPerKg(design().tower?24:13),
                t.contains("OutputH")?t.getDouble("OutputH"):dev.bwr.core.thermal.Saturation.subcooledLiquidEnthalpyKJPerKg(13));energy.setStored(t.getInt("Energy"));
            if(t.contains("Target"))setTarget(t.getDouble("Target"));double a=t.getDouble("Actual");actual=Double.isFinite(a)?Math.clamp(a,0,1):0;}
        structureDirty=true;checkedAt=drainTick=Long.MIN_VALUE;drained=0;
        double vapor=t.getDouble("VaporActivity");vaporActivity=Double.isFinite(vapor)?Math.clamp(vapor,0,1):0;
        vaporUpdated=level==null?0:level.getGameTime();
    }
}
