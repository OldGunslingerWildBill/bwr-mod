package dev.bwr.mod.reactor.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.reactor.*;
import net.minecraft.client.resources.model.ModelResourceLocation;
import java.util.*;

/** Formed hydraulic housings follow the same circular coordinates as their blades. */
public final class VesselDriveGeometry {
    public static final ModelResourceLocation MODEL=ModelResourceLocation.standalone(BwrMod.id("block/control_rod_drive"));
    public record Mount(double x,double z,float scale,double neckHeight){}
    private VesselDriveGeometry(){}
    public static List<Mount> layout(VesselAppearance.Envelope e,VesselCoreAppearance core) {
        var grid=ReactorCoreGeometry.layout(e,core);
        float scale=Math.min(.9f,Math.min(grid.pitchX(),grid.pitchZ())*1.65f);
        List<Mount> mounts=new ArrayList<>();
        for(var drive:core.drives()) {
            double x=ReactorCoreGeometry.driveX(e,core,grid,drive),z=ReactorCoreGeometry.driveZ(e,core,grid,drive);
            double radial=x*x/Math.pow(.46*e.width(),2)+z*z/Math.pow(.46*e.depth(),2);
            double head=1.2-1.02*Math.sqrt(Math.max(0,1-radial));
            mounts.add(new Mount(x,z,scale,head+.10));
        }
        return List.copyOf(mounts);
    }
    public static void build(MachineMeshCache.Builder b,VesselAppearance.Envelope e,VesselCoreAppearance core) {
        for(var m:layout(e,core)) {
            b.part(MODEL,m.x()-.5*m.scale(),-1,m.z()-.5*m.scale(),m.scale(),1,m.scale());
            b.part(ReactorCoreGeometry.model("guide_tube"),m.x(),-.03,m.z(),m.scale()*.65f,(float)(m.neckHeight()+.03),m.scale()*.65f);
        }
    }
}
