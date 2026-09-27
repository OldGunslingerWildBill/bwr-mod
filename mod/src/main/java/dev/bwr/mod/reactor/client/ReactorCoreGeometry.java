package dev.bwr.mod.reactor.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.core.fuel.AbwrShroudGeometry;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.reactor.*;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

/** Blender pieces instanced from the saved core map. No block entities, scans or
 * tessellation per frame. Closed vessels skip the entire internal draw outside. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class ReactorCoreGeometry {
    public static final String[] PARTS={"channel","bundle_top","insert_top","guide_cell","support_cell","guide_tube","blade","shroud","shroud_rim","abwr_shroud","abwr_shroud_rim"};
    public static ModelResourceLocation model(String name){return ModelResourceLocation.standalone(BwrMod.id("block/reactor_core/"+name+"/body"));}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional event){for(String part:PARTS)event.register(model(part));}
    public record Layout(float pitchX,float pitchZ,float bottom,float top,int latticeWidth) {
        public double x(int slot){return (slot%latticeWidth+.5-latticeWidth/2.0)*pitchX;}
        public double z(int slot){return (slot/latticeWidth+.5-latticeWidth/2.0)*pitchZ;}
    }
    public static float shroudOuterRadius(VesselCoreAppearance core) {
        return core.internalPumps()?(float)AbwrShroudGeometry.OUTER_RADIUS:.375f;
    }
    public static float fuelEnvelopeRadius(VesselCoreAppearance core) {
        return core.internalPumps()?(float)AbwrShroudGeometry.FUEL_RADIUS:.362f;
    }
    public static Layout layout(VesselAppearance.Envelope e,VesselCoreAppearance core) {
        if(core.internalPumps()) {
            var packed=dev.bwr.core.fuel.RipCorePacking.layout(e.width()-2,e.depth()-2);
            return new Layout((float)packed.pitchX(),(float)packed.pitchZ(),2,e.height()-6.15f,core.latticeWidth());
        }
        float nx=e.width()-2,nz=e.depth()-2;
        if(core.layoutVersion()==1) {
            // Legacy centre-outward masks do not occupy the complete lattice.
            float extent=1;
            for(var c:core.cells())extent=Math.max(extent,(float)Math.hypot(c.slot()%core.latticeWidth()+.5-core.latticeWidth()/2.0,
                    c.slot()/core.latticeWidth()+.5-core.latticeWidth()/2.0)+.71f);
            nx=nz=extent;
        }
        float px=.35f*e.width()/nx,pz=.35f*e.depth()/nz,radial=0;
        for(var c:core.cells())radial=Math.max(radial,(float)Math.hypot(
                (Math.abs(c.slot()%core.latticeWidth()+.5-core.latticeWidth()/2.0)+.5)*px/e.width(),
                (Math.abs(c.slot()/core.latticeWidth()+.5-core.latticeWidth()/2.0)+.5)*pz/e.depth()));
        float fit=radial==0?1:fuelEnvelopeRadius(core)/radial;
        fit=Math.min(1,fit);
        return new Layout(px*fit,pz*fit,2,e.height()-6.15f,core.latticeWidth());
    }
    public static void build(MachineMeshCache.Builder b,VesselAppearance.Envelope e,VesselCoreAppearance core) {
        var l=layout(e,core);float px=l.pitchX(),pz=l.pitchZ(),detail=Math.min(px,pz),height=l.top()-l.bottom();
        for(var cell:core.cells()) {
            double x=l.x(cell.slot()),z=l.z(cell.slot());
            b.part(model("support_cell"),x,l.bottom()-.18*detail,z,px,detail,pz);
            b.part(model("guide_cell"),x,l.top()-.05,z,px,detail,pz);
            if(cell.content()!=0) {
                b.part(model("channel"),x,l.bottom(),z,px,height,pz);
                b.part(model(cell.content()==2?"insert_top":"bundle_top"),x,l.top(),z,px,detail,pz);
            }
        }
        for(var drive:core.drives())b.part(model("guide_tube"),driveX(e,core,l,drive),1.28,driveZ(e,core,l,drive),px,.55f,pz);
        String shroud=core.internalPumps()?"abwr_shroud":"shroud";
        String rim=core.internalPumps()?"abwr_shroud_rim":"shroud_rim";
        b.part(model(shroud),0,1.7,0,e.width(),l.top()-1.55f,e.depth());
        b.part(model(rim),0,1.7,0,e.width(),1,e.depth());
        b.part(model(rim),0,l.top()+.15,0,e.width(),1,e.depth());
    }
    public static double driveX(VesselAppearance.Envelope e,VesselCoreAppearance c,Layout l,VesselCoreAppearance.Drive d){
        if(c.layoutVersion()==1)return (d.x()+(c.latticeWidth()-(e.width()-2))/2-c.latticeWidth()/2.0)*l.pitchX();
        return (d.x()+.5-(e.width()-2)/2.0)*2*l.pitchX();
    }
    public static double driveZ(VesselAppearance.Envelope e,VesselCoreAppearance c,Layout l,VesselCoreAppearance.Drive d){
        if(c.layoutVersion()==1)return (d.z()+(c.latticeWidth()-(e.depth()-2))/2-c.latticeWidth()/2.0)*l.pitchZ();
        return (d.z()+.5-(e.depth()-2)/2.0)*2*l.pitchZ();
    }
    private ReactorCoreGeometry(){}
}
