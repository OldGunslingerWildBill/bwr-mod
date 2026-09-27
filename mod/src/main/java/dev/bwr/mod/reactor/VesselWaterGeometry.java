package dev.bwr.mod.reactor;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.thermal.PressureVessel;

/** Maps physical free-surface level onto the deliberately compressed visual vessel.
 * Active-fuel top/bottom agree with ReactorCoreGeometry at every supported size.
 * Instrument reference-leg errors never move this surface. No world water blocks.
 */
public final class VesselWaterGeometry {
    public static double rim(VesselAppearance.Envelope e) {
        return e.height()-Math.min(e.height()*.22,Math.min(e.width(),e.depth())*.18)-.08;
    }
    public static double height(VesselAppearance.Envelope e,double levelIn) {
        double zero=-PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN;
        double baf=PressureVessel.BAF_ON_INSTRUMENT_SCALE_IN,taf=PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN;
        if(!Double.isFinite(levelIn)||levelIn<=zero)return .28;
        if(levelIn<=baf)return .28+(2-.28)*(levelIn-zero)/(baf-zero);
        double top=e.height()-6.15;
        if(levelIn<=taf)return 2+(top-2)*(levelIn-baf)/(taf-baf);
        // +60 in is the top of the normal narrow-range band; overfill stays inside the open lip.
        return top+(rim(e)-top)*Math.clamp((levelIn-taf)/(60-taf),0,1);
    }
    public static double radiusFraction(double y) {
        return y<1.2?.442*Math.sqrt(Math.max(0,1-Math.pow((1.2-y)/.94,2))):.442;
    }
    private VesselWaterGeometry(){}
}
