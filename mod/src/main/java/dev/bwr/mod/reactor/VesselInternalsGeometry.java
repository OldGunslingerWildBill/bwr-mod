package dev.bwr.mod.reactor;

/** Shared axial dimensions for the modeled core, water, spray rings and installed hardware.
 * The active fuel stays low in the vessel; extra vessel height adds steam space. */
public final class VesselInternalsGeometry {
    public static final double CORE_BOTTOM=2;
    public static final double MAX_CORE_HEIGHT=9;
    public static double coreHeight(int width,int depth,int height) {
        double desired=Math.clamp(3.5+.55*(Math.min(width,depth)-7),3.5,MAX_CORE_HEIGHT);
        double rim=height-Math.min(height*.22,Math.min(width,depth)*.18)-.08;
        return Math.min(desired,Math.max(.5,rim-CORE_BOTTOM-.7));
    }
    public static double coreTop(VesselAppearance.Envelope e) {
        return CORE_BOTTOM+coreHeight(e.width(),e.depth(),e.height());
    }
    public static int constructionFuelTop(net.minecraft.core.BlockPos min,net.minecraft.core.BlockPos max) {
        int w=max.getX()-min.getX()+3,d=max.getZ()-min.getZ()+3,h=max.getY()-min.getY()+3;
        // Keep enough construction rows for both rings even in very short vessels.
        return Math.min(max.getY()-ReactorStructure.DOME_BLOCKS_ABOVE_ACTIVE_FUEL,
                min.getY()-1+(int)Math.floor(CORE_BOTTOM+coreHeight(w,d,h)));
    }
    public static double spargerHeight(VesselAppearance.Envelope e,CoreSpraySpargerBlock.Loop loop) {
        double top=coreTop(e),space=Math.max(0,VesselWaterGeometry.rim(e)-.45-top-.52);
        return top+.52+loop.blocksAboveTaf()*Math.min(1,space/4);
    }
    private VesselInternalsGeometry(){}
}
