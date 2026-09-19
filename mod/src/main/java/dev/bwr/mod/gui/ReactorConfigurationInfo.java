package dev.bwr.mod.gui;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import net.minecraft.network.FriendlyByteBuf;

/** Read-only construction estimates. These never cap, command or protect the reactor. */
public record ReactorConfigurationInfo(double fuelLoadedRatingMW,double flowSupportedMW,double steamEquivalentKgPerS,
                                       double flowCeilingFraction,double flowCeilingKgPerS,double inletSubcoolingKJPerKg,
                                       int matchedJetAssemblies,int unmatchedJetAssemblies,int externalPumps,int internalPumps) {
    public static final ReactorConfigurationInfo EMPTY=new ReactorConfigurationInfo(0,0,0,0,0,0,0,0,0,0);
    public static ReactorConfigurationInfo capture(ReactorControllerBlockEntity be) {
        if(be==null || !be.isFormed() || be.core()==null)return EMPTY;
        var core=be.core();
        double loaded=be.assemblyCount()>0?Math.min(1,(double)core.getCoreLoading().loadedAssemblyCount()/be.assemblyCount()):0;
        double flow=be.getRecirculationCapacityFraction();
        double fuelRating=be.getConfiguredRatedThermalMW()*loaded;
        // A deliberately simple planning comparison, not a prediction of criticality or a safe operating limit.
        double supported=be.getConfiguredRatedThermalMW()*Math.min(loaded,flow);
        double rise=Saturation.vapourEnthalpyKJPerKg(Saturation.psiaFromPsig(core.getPressurePsig()))
                -Saturation.subcooledLiquidEnthalpyKJPerKg(core.getFeedwaterTemperatureC());
        return new ReactorConfigurationInfo(fuelRating,supported,rise>0?supported*1000/rise:0,flow,
                flow*PhysicalConstants.RATED_CORE_FLOW_LB_PER_HR*.45359237/3600,
                core.getVoidModel().getCoreInletSubcoolingKJPerKg(),be.getConnectedJetPairs(),be.getUnmatchedJets(),
                be.getConnectedExternalPumps(),be.getInstalledInternalPumps());
    }
    public void write(FriendlyByteBuf b) {
        for(double v:new double[]{fuelLoadedRatingMW,flowSupportedMW,steamEquivalentKgPerS,flowCeilingFraction,flowCeilingKgPerS,inletSubcoolingKJPerKg})b.writeDouble(v);
        b.writeVarInt(matchedJetAssemblies);b.writeVarInt(unmatchedJetAssemblies);b.writeVarInt(externalPumps);b.writeVarInt(internalPumps);
    }
    public static ReactorConfigurationInfo read(FriendlyByteBuf b) {
        return new ReactorConfigurationInfo(b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),
                b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt());
    }
}
