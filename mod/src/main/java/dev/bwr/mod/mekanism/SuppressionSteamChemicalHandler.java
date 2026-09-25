package dev.bwr.mod.mekanism;

import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.suppression.*;
import mekanism.api.Action;
import mekanism.api.chemical.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;

/** Optional Mekanism input. Every access resolves live tank ownership; simulation never adds heat. */
final class SuppressionSteamChemicalHandler implements IChemicalHandler {
    private final Level level;private final BlockPos pos;private final Direction side;
    SuppressionSteamChemicalHandler(Level level,BlockPos pos,Direction side){this.level=level;this.pos=pos.immutable();this.side=side;}
    private SuppressionPoolBlockEntity owner(){
        if(!level.isLoaded(pos)||!(level.getBlockState(pos).getBlock() instanceof SuppressionPoolSteamPortBlock)
                ||side!=level.getBlockState(pos).getValue(SuppressionPoolSteamPortBlock.FACING))return null;
        return SuppressionPoolSteamPortBlock.owner(level,pos);
    }
    @Override public int getChemicalTanks(){return 1;}
    @Override public ChemicalStack getChemicalInTank(int tank){
        var pool=owner();var steam=MekanismSteam.steam();
        long amount=tank==0&&pool!=null?(long)Math.floor(pool.pool().inletSteam.mass()*1000):0;
        return amount>0&&steam!=null?new ChemicalStack(steam,amount):ChemicalStack.EMPTY;
    }
    @Override public void setChemicalInTank(int tank,ChemicalStack stack){}
    @Override public long getChemicalTankCapacity(int tank){return tank==0?10_000_000:0;}
    @Override public boolean isValid(int tank,ChemicalStack stack){var steam=MekanismSteam.steam();return tank==0&&steam!=null&&!stack.isEmpty()&&stack.is(steam);}
    @Override public ChemicalStack insertChemical(int tank,ChemicalStack stack,Action action){
        var pool=owner();if(pool==null||!isValid(tank,stack))return stack;
        // Mekanism chemicals carry no enthalpy; receive saturated atmospheric steam at this boundary.
        double psia=Saturation.psiaFromPsig(0),h=Saturation.vapourEnthalpyKJPerKg(psia);
        long accepted=(long)Math.floor(pool.receiveSteam(stack.getAmount()/1000.0,h,psia,true)*1000);
        if(accepted<=0)return stack;
        if(action.execute())pool.receiveSteam(accepted/1000.0,h,psia,false);
        return accepted>=stack.getAmount()?ChemicalStack.EMPTY:stack.copyWithAmount(stack.getAmount()-accepted);
    }
    @Override public ChemicalStack extractChemical(int tank,long amount,Action action){return ChemicalStack.EMPTY;}
}
