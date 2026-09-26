package dev.bwr.mod.reactor.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.reactor.VesselAppearance;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.ArrayList;

/** Builds only on a vessel mesh cache miss. No per-frame world scans or new ticking blocks. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class CoreSpargerGeometry {
    public static final String[] PARTS={"header","nozzle_lpcs","nozzle_hpcs"};
    public static ModelResourceLocation model(String part) {return ModelResourceLocation.standalone(BwrMod.id("block/core_sparger/"+part+"/body"));}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional event){for(var part:PARTS)event.register(model(part));}
    public static void build(MachineMeshCache.Builder b,VesselAppearance.Envelope e) {
        if(e.spargers().isEmpty())return;
        int x0=e.min().getX()+1,x1=e.max().getX()-1,z0=e.min().getZ()+1,z1=e.max().getZ()-1;
        var perimeter=new ArrayList<BlockPos>();
        for(int x=x0;x<=x1;x++)perimeter.add(new BlockPos(x,0,z0));
        for(int z=z0+1;z<=z1;z++)perimeter.add(new BlockPos(x1,0,z));
        for(int x=x1-1;x>=x0;x--)perimeter.add(new BlockPos(x,0,z1));
        for(int z=z1-1;z>z0;z--)perimeter.add(new BlockPos(x0,0,z));
        double cx=e.min().getX()+e.width()/2.0,cz=e.min().getZ()+e.depth()/2.0;
        double rx=e.width()*.40,rz=e.depth()*.40;
        for(var segment:e.spargers()) {
            int i=perimeter.indexOf(new BlockPos(segment.pos().getX(),0,segment.pos().getZ()));
            if(i<0)continue;
            double a=angle(perimeter.get(i),cx,cz,rx,rz);
            double previous=angle(perimeter.get((i+perimeter.size()-1)%perimeter.size()),cx,cz,rx,rz);
            double next=angle(perimeter.get((i+1)%perimeter.size()),cx,cz,rx,rz);
            double start=a-forward(a-previous)/2,end=a+forward(next-a)/2;
            double y=segment.pos().getY()+.52-e.min().getY();
            int steps=Math.max(2,(int)Math.ceil((end-start)/Math.toRadians(3)));
            for(int j=0;j<steps;j++) {
                double u=start+(end-start)*j/steps,v=start+(end-start)*(j+1)/steps;
                var from=new Vector3f((float)(rx*Math.cos(u)),(float)y,(float)(rz*Math.sin(u)));
                var delta=new Vector3f((float)(rx*Math.cos(v)),(float)y,(float)(rz*Math.sin(v))).sub(from);
                float length=delta.length();
                b.pose.pushPose();b.pose.translate(from.x,from.y,from.z);
                b.pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0,0,1),delta.normalize()));
                b.part(model("header"),0,0,-.002,1,1,length+.004f);b.pose.popPose();
            }
            b.pose.pushPose();b.pose.translate(rx*Math.cos(a),y,rz*Math.sin(a));
            b.pose.mulPose(com.mojang.math.Axis.YP.rotation((float)(-a-Math.PI)));
            b.model(model("nozzle_"+segment.loop().getSerializedName()));b.pose.popPose();
        }
    }
    private static double angle(BlockPos p,double cx,double cz,double rx,double rz){return Math.atan2((p.getZ()+.5-cz)/rz,(p.getX()+.5-cx)/rx);}
    private static double forward(double a){return (a+Math.PI*2)%(Math.PI*2);}
    private CoreSpargerGeometry(){}
}
