package dev.bwr.mod.water;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
/** Water at different temperatures mixes by energy instead of being rejected as a different fluid. */
public class ThermalWaterTank extends FluidTank {
    private final double legacyC;
    private final java.util.function.DoubleSupplier reserved;
    public ThermalWaterTank(int capacity,double legacyC){this(capacity,legacyC,()->0);}
    public ThermalWaterTank(int capacity,double legacyC,java.util.function.DoubleSupplier reserved){super(capacity,s->s.is(Fluids.WATER));this.legacyC=legacyC;this.reserved=reserved;}
    public double temperatureC(){return ThermalWater.temperature(getFluid(),legacyC);}
    @Override public FluidStack drain(FluidStack requested,FluidAction action){
        // Water selectors from other mods need not duplicate our thermal metadata.
        return requested.is(Fluids.WATER)?drain(requested.getAmount(),action):FluidStack.EMPTY;
    }
    @Override public int fill(FluidStack incoming,FluidAction action){
        if(incoming.isEmpty()||!incoming.is(Fluids.WATER))return 0;
        double h=ThermalWater.enthalpy(incoming);if(!Double.isFinite(h)||h<0||h>5000)return 0;
        int n=Math.max(0,Math.min(incoming.getAmount(),getCapacity()-getFluidAmount()));
        if(n>0&&action.execute()) {
            int old=getFluidAmount();double remaining=Math.max(0,old-reserved.getAsDouble());
            double energy=remaining*ThermalWater.enthalpy(getFluid(),legacyC)+n*h;
            setFluid(ThermalWater.stack(old+n,energy/(remaining+n)));onContentsChanged();
        }
        return n;
    }
}
