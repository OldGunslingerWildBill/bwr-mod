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
    /** Instrument level (in) up to which the drawn surface is true to the drawn internals' scale: the top of the normal band. */
    public static final double TRUE_SCALE_TOP_IN = 30;
    public static double height(VesselAppearance.Envelope e,double levelIn) {
        double zero=-PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN;
        double baf=PressureVessel.BAF_ON_INSTRUMENT_SCALE_IN,taf=PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN;
        if(!Double.isFinite(levelIn)||levelIn<=zero)return .28;
        if(levelIn<=baf)return .28+(2-.28)*(levelIn-zero)/(baf-zero);
        double top=VesselInternalsGeometry.coreTop(e);
        if(levelIn<=taf)return 2+(top-2)*(levelIn-baf)/(taf-baf);
        // Above the fuel: true to the drawn vessel's scale (VesselProfile) up to +30 in, so the operating level sits on the
        // separators and the dryer skirt dips into it as in a real vessel. The physics keeps the lip at +60 in
        // (PressureVessel.FLANGE_LEVEL_IN), so the last 30 in are stretched up to the drawn lip; overfill stays inside it.
        var p=VesselProfile.of(e,false);
        double at30=p.up(PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN+TRUE_SCALE_TOP_IN);
        if(levelIn<=TRUE_SCALE_TOP_IN)return p.up(PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN+levelIn);
        return at30+(rim(e)-at30)*Math.clamp((levelIn-TRUE_SCALE_TOP_IN)/(60-TRUE_SCALE_TOP_IN),0,1);
    }
    /** Inverse of the rendered collapsed/free-surface scale, for physical nozzle heights. */
    public static double levelAtHeight(VesselAppearance.Envelope e, double height) {
        double zero=-PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN;
        double baf=PressureVessel.BAF_ON_INSTRUMENT_SCALE_IN, taf=PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN;
        double top=VesselInternalsGeometry.coreTop(e);
        if(height<=.28) return zero;
        if(height<=2) return zero+(height-.28)/(2-.28)*(baf-zero);
        if(height<=top) return baf+(height-2)/(top-2)*(taf-baf);
        var p=VesselProfile.of(e,false);
        double at30=p.up(PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN+TRUE_SCALE_TOP_IN);
        if(height<=at30) return p.inchAt(height)-PhysicalConstants.INSTRUMENT_ZERO_ABOVE_VESSEL_ZERO_IN;
        return TRUE_SCALE_TOP_IN+(height-at30)/(rim(e)-at30)*(60-TRUE_SCALE_TOP_IN);
    }
    /** The drawn inner wall at height y, a fraction of the width: the v2 bottom head (VesselProfile). */
    public static double radiusFraction(VesselAppearance.Envelope e,double y) {
        return VesselProfile.of(e,false).waterRadiusFraction(y);
    }
    /** The alpha.21 shallow dish, kept for callers that have no envelope. The renderers use {@link #radiusFraction(VesselAppearance.Envelope,double)}. */
    public static double radiusFraction(double y) {
        return y<1.2?.442*Math.sqrt(Math.max(0,1-Math.pow((1.2-y)/.94,2))):.442;
    }
    private VesselWaterGeometry(){}
}
