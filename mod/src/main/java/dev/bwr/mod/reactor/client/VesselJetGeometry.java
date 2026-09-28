package dev.bwr.mod.reactor.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.reactor.*;
import net.minecraft.client.resources.model.ModelResourceLocation;
import java.util.*;

/** Modular Blender pumps installed around the outside of the shroud.
 * Only actual complete pumps are rendered; capacity/pairing still use saved construction cells. */
public final class VesselJetGeometry {
    public static final String[] PARTS={"lower","straight","upper","brace"};
    public static ModelResourceLocation model(String part) {return ModelResourceLocation.standalone(BwrMod.id("block/installed_jet/"+part+"/body"));}
    // Fixed fittings use one uniform scale. Only plain cylindrical pipes change length.
    public static final double HALF_WIDTH=.61,HALF_DEPTH=.48,MODEL_RADIUS=Math.hypot(HALF_WIDTH,HALF_DEPTH);
    public static final double LOWER_HEIGHT=1.6,CROWN_HEIGHT=1,MIN_STRAIGHT=1.4,MAX_STRAIGHT=3.4;
    public record Mount(VesselAppearance.Jet jet,double x,double z,double yaw,float scale,float straightLength,double bottom,double top) {}
    public static List<Mount> layout(VesselAppearance.Envelope e,VesselCoreAppearance core) {
        if(e.jets().isEmpty())return List.of();
        double cx=e.min().getX()+e.width()/2.0,cz=e.min().getZ()+e.depth()/2.0;
        var sorted=new ArrayList<>(e.jets());
        sorted.sort(Comparator.comparingDouble((VesselAppearance.Jet j)->Math.atan2(j.root().getZ()+.5-cz,j.root().getX()+.5-cx))
                .thenComparing(VesselAppearance.Jet::root));
        double inner=ReactorCoreGeometry.shroudOuterRadius(core),outer=.448;
        double radius=(inner+outer)/2,minSize=Math.min(e.width(),e.depth());
        double first=Math.atan2(sorted.getFirst().root().getZ()+.5-cz,sorted.getFirst().root().getX()+.5-cx);
        double low=0,high=1.25;
        for(int trial=0;trial<28;trial++) {
            double scale=(low+high)/2;boolean fits=true;
            for(int i=0;i<sorted.size()&&fits;i++) {
                double a=first+i*Math.PI*2/sorted.size(),x=radius*e.width()*Math.cos(a),z=radius*e.depth()*Math.sin(a);
                fits=clearsWalls(e,x,z,Math.atan2(x,z),scale,inner+.018/minSize,outer-.018/minSize);
            }
            if(fits)low=scale;else high=scale;
        }
        double scale=low;
        if(sorted.size()>1)scale=Math.min(scale,(2*radius*minSize*Math.sin(Math.PI/sorted.size())-.035)/(2*MODEL_RADIUS));
        var coreLayout=ReactorCoreGeometry.layout(e,core);
        double bottom=1.72,target=coreLayout.bottom()+.72*(coreLayout.top()-coreLayout.bottom())-bottom;
        scale=Math.min(scale,target/(LOWER_HEIGHT+CROWN_HEIGHT+MIN_STRAIGHT));
        double straight=Math.clamp(target/scale-LOWER_HEIGHT-CROWN_HEIGHT,MIN_STRAIGHT,MAX_STRAIGHT);
        double top=bottom+(LOWER_HEIGHT+CROWN_HEIGHT+straight)*scale;
        var result=new ArrayList<Mount>();
        for(int i=0;i<sorted.size();i++) {
            // Preserve circular order; spreading the installed set also keeps two construction rows
            // from projecting into the same space on the curved shroud.
            double a=first+i*Math.PI*2/sorted.size(),x=radius*e.width()*Math.cos(a),z=radius*e.depth()*Math.sin(a);
            result.add(new Mount(sorted.get(i),x,z,Math.atan2(x,z),(float)scale,(float)straight,bottom,top));
        }
        return List.copyOf(result);
    }
    /** Bounds are transformed into the vessel ellipse's unit-circle space. The
     * nearest point on each edge protects the shroud; corners protect the wall. */
    public static boolean clearsWalls(VesselAppearance.Envelope e,double x,double z,double yaw,double scale,double inner,double outer) {
        double[][] p=new double[4][2];double cos=Math.cos(yaw),sin=Math.sin(yaw);
        for(int i=0;i<4;i++) {
            double dx=(i==0||i==3?-HALF_WIDTH:HALF_WIDTH)*scale,dz=(i<2?-HALF_DEPTH:HALF_DEPTH)*scale;
            p[i][0]=(x+dx*cos+dz*sin)/e.width();p[i][1]=(z-dx*sin+dz*cos)/e.depth();
            if(Math.hypot(p[i][0],p[i][1])>outer)return false;
        }
        for(int i=0;i<4;i++) {
            double[] a=p[i],b=p[(i+1)%4];double dx=b[0]-a[0],dz=b[1]-a[1];
            double length=dx*dx+dz*dz,t=length>0?Math.clamp(-(a[0]*dx+a[1]*dz)/length,0,1):0;
            if(Math.hypot(a[0]+t*dx,a[1]+t*dz)<inner)return false;
        }
        return true;
    }
    public static void build(MachineMeshCache.Builder b,VesselAppearance.Envelope e,VesselCoreAppearance core) {
        for(var m:layout(e,core)) {
            b.pose.pushPose();b.pose.translate(m.x(),m.bottom(),m.z());
            b.pose.mulPose(com.mojang.math.Axis.YP.rotation((float)m.yaw()));
            b.pose.scale(m.scale(),m.scale(),m.scale());
            b.model(model("lower"));
            b.part(model("straight"),0,LOWER_HEIGHT,0,1,m.straightLength(),1);
            b.part(model("upper"),0,LOWER_HEIGHT+m.straightLength(),0,1,1,1);
            b.part(model("brace"),0,LOWER_HEIGHT+.42*m.straightLength(),0,1,1,1);
            b.pose.popPose();
        }
    }
    private VesselJetGeometry(){}
}
