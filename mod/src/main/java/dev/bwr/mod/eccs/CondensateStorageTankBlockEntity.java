package dev.bwr.mod.eccs;

import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The condensate storage tank — the cold, finite half of {@code SPEC.md}
 * section 9.2's suction choice.
 *
 * <h2>One millibucket is one kilogram</h2>
 * Not an arbitrary conversion: a Minecraft water source block is one cubic
 * metre and one bucket, and a cubic metre of water is a tonne. So a bucket is
 * 1000 mB and 1000 kg, and the two units are the same number. That makes the
 * 2,000,000 mB capacity here 2000 tonnes, or about 528,000 US gallons — which
 * is the size of a real BWR condensate storage tank, and it is why the numbers
 * below come out where they should:
 *
 * <ul>
 *   <li>RCIC at 700 gpm drains a full tank in about twelve and a half hours.</li>
 *   <li>HPCI at 5000 gpm drains it in about one and three quarters.</li>
 *   <li>A low pressure system at 6350 gpm drains it in under an hour and a
 *       half, which is why nobody sensible runs LPCS on tank suction.</li>
 * </ul>
 *
 * <p>Nothing here switches to pool suction when the tank runs down, warns that
 * it is running down, or refuses to empty itself. The level is published; what
 * to do about it is Lua's problem.
 */
public class CondensateStorageTankBlockEntity extends BlockEntity {

    public int diameter=1,height=1,paidBlocks=1;
    int breakCost;
    private BlockPos root;
    private java.util.UUID assembly=java.util.UUID.randomUUID();
    private long checkedAt=Long.MIN_VALUE;private boolean complete;
    public boolean assembled(){return getBlockState().getValue(CondensateStorageTankBlock.ASSEMBLED);}
    public BlockPos root(){return root;}
    public boolean belongsTo(CondensateStorageTankBlockEntity o){return root.equals(o.worldPosition)&&assembly.equals(o.assembly);}
    public CondensateStorageTankBlockEntity owner(){
        if(isRemoved())return null;
        if(!assembled())return this;
        if(level==null)return root.equals(worldPosition)?this:null;
        return level.isLoaded(root)&&level.getBlockEntity(root) instanceof CondensateStorageTankBlockEntity o&&!o.isRemoved()&&o.getBlockState().getValue(CondensateStorageTankBlock.CONTROLLER)&&belongsTo(o)?o:null;
    }
    public void markStructureDirty(){checkedAt=Long.MIN_VALUE;setChanged();}
    public boolean ready(){
        var o=owner();if(o==null)return false;if(o!=this)return o.ready();if(!assembled())return true;
        if(level==null||!CondensateTankShape.valid(diameter,height))return false;
        int half=diameter/2;for(int x=(worldPosition.getX()-half)>>4;x<=(worldPosition.getX()+half)>>4;x++)for(int z=(worldPosition.getZ()-half)>>4;z<=(worldPosition.getZ()+half)>>4;z++)if(!level.isLoaded(new BlockPos(x*16,worldPosition.getY(),z*16)))return false;
        if(checkedAt==Long.MIN_VALUE||level.getGameTime()-checkedAt>=20){complete=true;for(var off:CondensateTankShape.get(diameter,height).cells.keySet())if(!(level.getBlockEntity(worldPosition.offset(off)) instanceof CondensateStorageTankBlockEntity p)||!p.belongsTo(this)){complete=false;break;}checkedAt=level.getGameTime();}return complete;
    }
    public void configure(int d,int h,int blocks){diameter=d;height=h;paidBlocks=blocks;tank.setCapacity(CondensateTankShape.get(d,h).capacity);markStructureDirty();}
    public void bind(CondensateStorageTankBlockEntity o){root=o.worldPosition;assembly=o.assembly;diameter=o.diameter;height=o.height;paidBlocks=root.equals(worldPosition)?o.paidBlocks:0;markStructureDirty();}

    /** Millibuckets, and therefore kilograms, at full. */
    public static final int CAPACITY_MB = 2_000_000;

    /** Temperature of stored condensate, degrees C. Cold, which is the whole point. */
    public static final double STORED_TEMPERATURE_C = 32.0;

    private final FluidTank tank = new FluidTank(CAPACITY_MB,
            stack -> stack.getFluid() == Fluids.WATER) {
        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    };

    /** Sub-kilogram remainder carried between ticks. See {@link #drawKg(double)}. */
    private double pendingDrawKg;

    /** External extraction cannot take the fractional water already supplied to a pump. */
    private final IFluidHandler fluidHandler = new IFluidHandler() {
        @Override public int getTanks(){return 1;}
        @Override public FluidStack getFluidInTank(int index){var o=owner();return index==0&&o!=null&&o.ready()?o.tank.getFluid().copyWithAmount((int)Math.floor(Math.max(0,o.storedKg()-o.pendingDrawKg))):FluidStack.EMPTY;}
        @Override public int getTankCapacity(int index){var o=owner();return index==0&&o!=null?(int)o.capacityKg():0;}
        @Override public boolean isFluidValid(int index,FluidStack stack){return index==0&&stack.is(Fluids.WATER);}
        @Override public int fill(FluidStack stack,FluidAction action){var o=owner();return o!=null&&o.ready()?o.tank.fill(stack,action):0;}
        @Override public FluidStack drain(int amount,FluidAction action){var o=owner();return o!=null&&o.ready()?o.tank.drain(Math.min(Math.max(0,amount),(int)Math.floor(Math.max(0,o.storedKg()-o.pendingDrawKg))),action):FluidStack.EMPTY;}
        @Override public FluidStack drain(FluidStack stack,FluidAction action){return stack.getFluid()==Fluids.WATER?drain(stack.getAmount(),action):FluidStack.EMPTY;}
    };
    public IFluidHandler fluidHandler(){return fluidHandler;}

    public CondensateStorageTankBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.CONDENSATE_STORAGE_TANK.get(), pos, state);
        root=pos.immutable();
    }

    /** Internal whole-millibucket store. Pipes must use {@link #fluidHandler()}. */
    public FluidTank tank() {
        var o=owner();return o==null?tank:o.tank;
    }

    /** Water in the tank, kg. */
    public double storedKg() {
        var o=owner();return o==null?0:o.tank.getFluidAmount();
    }
    public double availableWaterKg(){return Math.max(0,storedKg()-pendingDrawKg);}
    void restoreAvailableWater(double kg){
        int whole=(int)Math.ceil(kg);tank.setFluid(whole==0?FluidStack.EMPTY:new FluidStack(Fluids.WATER,whole));
        pendingDrawKg=whole-kg;setChanged();
    }

    /** Capacity, kg. */
    public double capacityKg() {
        var o=owner();return o==null?0:o.tank.getCapacity();
    }

    /** How full, 0 to 1. */
    public double levelFraction() {
        return capacityKg()>0?storedKg()/capacityKg():0;
    }

    /**
     * Take water for a pump suction.
     *
     * <p>A fluid tank counts in whole millibuckets and a tick is a twentieth of
     * a second, so SLC at 2.7 kg/s wants 0.136 kg per tick — which would round
     * either to nothing forever or to a whole kilogram, an eightfold
     * over-draw. The sub-kilogram remainder is therefore carried between ticks
     * and the tank is drained in whole units when it accumulates, so the
     * reported flow stays smooth and the inventory stays exact.
     *
     * @return kilograms actually supplied, less than asked for once the tank is
     *         running out
     */
    public double drawKg(double wantedKg) {
        var o=owner();if(o==null||!ready())return 0;if(o!=this)return o.drawKg(wantedKg);
        if (!Double.isFinite(wantedKg)||!(wantedKg > 0.0)) {
            return 0.0;
        }
        // pendingDrawKg is water already handed to a pump that has NOT yet been
        // taken out of the FluidTank, so it is a debt against the tank contents
        // and it must be SUBTRACTED here. Adding it — which this line used to do
        // — makes the debt itself look like inventory: once the tank empties
        // with any remainder left over, tank.drain returns nothing, the debt
        // stops being repaid, and it becomes the sole term in `available`. Each
        // call then adds its own delivery back into it and the tank supplies an
        // unbounded amount of water it does not have. Do not "simplify" the sign.
        double available = storedKg() - pendingDrawKg;
        double delivered = Math.min(wantedKg, available);
        if (!(delivered > 0.0)) {
            return 0.0;
        }
        pendingDrawKg += delivered;
        setChanged();
        int whole = (int) Math.floor(pendingDrawKg);
        if (whole > 0) {
            FluidStack drained = tank.drain(whole, IFluidHandler.FluidAction.EXECUTE);
            // If the tank could not honour the whole amount the residue stays on
            // the books as a debt, so the next call sees less than it thinks it
            // has. That under-delivers by strictly less than one kilogram and
            // repays itself the moment the tank is refilled — the conservative
            // direction, which is the one to be wrong in.
            pendingDrawKg -= drained.getAmount();
        }
        return delivered;
    }

    /** Put water in by hand, for creative staging and for the bucket. */
    public void fillKg(double kg) {
        var o=owner();if(o==null||!ready())return;if(o!=this){o.fillKg(kg);return;}
        if (!(kg > 0.0)) {
            return;
        }
        tank.fill(new FluidStack(Fluids.WATER, (int) Math.min(Integer.MAX_VALUE, kg)),
                IFluidHandler.FluidAction.EXECUTE);
    }

    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "Condensate storage tank: %,.0f / %,.0f kg (%.1f%%) at %.0f degC",
                storedKg(), capacityKg(), levelFraction() * 100.0, STORED_TEMPERATURE_C));
        out.add("Cold suction, and finite. The suppression pool is the endless, self-heating alternative.");
        return out;
    }

    // --- Persistence ----------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putLong("TankRoot",root.asLong());tag.putUUID("TankAssembly",assembly);tag.putInt("Diameter",diameter);tag.putInt("Height",height);tag.putInt("PaidBlocks",paidBlocks);
        tag.put("Tank", tank.writeToNBT(registries, new CompoundTag()));
        // The carried remainder is a real debt against the tank contents, so it
        // has to survive a save or the sub-kilogram already handed to a pump is
        // silently forgiven on reload.
        tag.putDouble("PendingDraw", pendingDrawKg);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        root=tag.contains("TankRoot")?BlockPos.of(tag.getLong("TankRoot")):worldPosition;if(tag.hasUUID("TankAssembly"))assembly=tag.getUUID("TankAssembly");
        int d=tag.getInt("Diameter"),h=tag.getInt("Height");diameter=CondensateTankShape.valid(d,h)?d:1;height=diameter==1?1:h;
        paidBlocks=tag.contains("PaidBlocks")?Math.clamp(tag.getInt("PaidBlocks"),0,10000):1;
        tank.setCapacity(diameter==1?CAPACITY_MB:CondensateTankShape.get(diameter,height).capacity);checkedAt=Long.MIN_VALUE;
        if (tag.contains("Tank")) {
            tank.readFromNBT(registries, tag.getCompound("Tank"));
        }
        double pending = tag.getDouble("PendingDraw");
        pendingDrawKg = Double.isFinite(pending) ? Math.max(0.0, pending) : 0.0;
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r){return saveWithoutMetadata(r);}
    @Override public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket(){return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);}
    @Override public void onLoad(){super.onLoad();if(level!=null&&!level.isClientSide()&&assembled())level.scheduleTick(worldPosition,getBlockState().getBlock(),20);}
}
