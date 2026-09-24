package dev.bwr.mod.water;
import dev.bwr.core.thermal.*;
import dev.bwr.mod.fuel.BwrDataComponents;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
/** Ordinary water remains NeoForge/Mekanism-compatible. Untagged external water enters at 13 C. */
public final class ThermalWater {
    public static final double AMBIENT_C=13;
    private ThermalWater(){}
    public static double enthalpy(FluidStack water){return enthalpy(water,AMBIENT_C);}
    public static double enthalpy(FluidStack water,double fallbackC){return water.getOrDefault(BwrDataComponents.WATER_ENTHALPY.get(),Saturation.subcooledLiquidEnthalpyKJPerKg(fallbackC));}
    public static double temperature(FluidStack water,double fallbackC){return WaterInventory.temperature(enthalpy(water,fallbackC));}
    public static FluidStack stack(int kg,double h){
        if(kg<=0)return FluidStack.EMPTY;
        var stack=new FluidStack(Fluids.WATER,kg);stack.set(BwrDataComponents.WATER_ENTHALPY.get(),h);return stack;
    }
    public static FluidStack atTemperature(int kg,double c){return stack(kg,Saturation.subcooledLiquidEnthalpyKJPerKg(c));}
}
