package dev.bwr.mod.eccs.client;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The formed condensate storage tank (CONDENSATE-TANK.md), drawn from the Blender parts of art/models/condensate_tank/build_cst.py:
 * the scaled parts (mat, ringwall, shell, seams, top angle, cone roof, name band) stretch with the diameter and height, everything
 * else (nozzles, pads, manway, heater, gauge board, anchor chairs, caged ladder, roof landing, hatch, vent, handrail, overflow) is
 * full size and placed on the shell. This placement is the same as {@code layout()} in build_cst.py; change both together.
 * The four water flanges keep their centres one block above the base on the box sides, as before. Built once per size into a
 * cached mesh.
 */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class CondensateTankRenderer implements BlockEntityRenderer<CondensateStorageTankBlockEntity> {
    private record Shape(int diameter,int height){}
    public static final String[] PARTS={"base","mat","curb","wall","seam","seam_v","top_angle","roof","roof_seams","roof_vent","roof_hatch","stencil",
            "port","repad","manway","heater","gauge_head","gauge_board","gauge_top","plate","rad_sign","overflow_top","overflow_foot","pipe","pipe_clip",
            "anchor_chair","ladder","cage","landing","post","rail"};
    static final double EAVE_DROP=1.3,WALL_FOOT=.158,PORT_Y=1.5;
    public static ModelResourceLocation model(String part){return ModelResourceLocation.standalone(BwrMod.id("block/condensate_tank/"+part+"/body"));}
    public CondensateTankRenderer(BlockEntityRendererProvider.Context c){}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional e){for(var p:PARTS)e.register(model(p));}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers e){e.registerBlockEntityRenderer(BwrBlockEntities.CONDENSATE_STORAGE_TANK.get(),CondensateTankRenderer::new);}
    @Override public void render(CondensateStorageTankBlockEntity be,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        if(!be.assembled()||!be.getBlockState().getValue(CondensateStorageTankBlock.CONTROLLER)||!CondensateTankShape.valid(be.diameter,be.height))return;
        int d=be.diameter,h=be.height;
        if(be.getLevel()!=null)light=LevelRenderer.getLightColor(be.getLevel(),be.getBlockPos().above(h));
        pose.pushPose();pose.translate(.5,0,.5);
        MachineMeshCache.draw(new Shape(d,h),b->build(b,d,h),pose,buffers,light,overlay);
        pose.popPose();
    }

    /** The cone roof's rise for a diameter (about a 1 in 10 slope, capped). */
    static double rise(int d){return Math.min(.95,.05*d+.12);}

    private static void put(MachineMeshCache.Builder b,String part,double x,double y,double z,double yaw,double sx,double sy,double sz){
        b.pose.pushPose();b.pose.translate(x,y,z);
        if(yaw!=0)b.pose.mulPose(Axis.YP.rotationDegrees((float)yaw));
        b.pose.scale((float)sx,(float)sy,(float)sz);b.model(model(part));b.pose.popPose();
    }
    /** A bar part (unit radius, unit length along +z) from a to b. */
    private static void bar(MachineMeshCache.Builder b,String part,double ax,double ay,double az,double bx,double by,double bz,double r){
        var v=new Vector3f((float)(bx-ax),(float)(by-ay),(float)(bz-az));float length=v.length();if(length<1e-4)return;
        b.pose.pushPose();b.pose.translate(ax,ay,az);b.pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0,0,1),v.normalize()));
        b.pose.scale((float)r,(float)r,length);b.model(model(part));b.pose.popPose();
    }
    /** A unit part on the shell at angle a (degrees; 0 is east, -90 north), its local -z turned outward. */
    private static void onShell(MachineMeshCache.Builder b,double radius,String part,double aDeg,double y,double sy){
        double a=Math.toRadians(aDeg);put(b,part,radius*Math.cos(a),y,radius*Math.sin(a),-(aDeg+90),1,sy,1);
    }
    private static boolean clear(double aDeg,double radius,double arc,double... others){
        for(double o:others){double diff=Math.abs(((aDeg-o+180)%360+360)%360-180);if(Math.toRadians(diff)*radius<=arc)return false;}
        return true;
    }

    static void build(MachineMeshCache.Builder b,int d,int h){
        double R=.47*d,eave=h-EAVE_DROP,rr=rise(d);
        double zl=-Math.sqrt(R*R-.49)-.20,ladderA=Math.toDegrees(Math.atan2(zl+.20,.7));
        java.util.function.DoubleUnaryOperator roofY=r->eave+rr*Math.max(0,Math.min(1,(.48*d-r)/(.44*d)));
        put(b,"mat",0,0,0,0,d,1,d);
        for(int k=0;k<4;k++){double t=Math.toRadians(90*k);put(b,"curb",-Math.sin(t)*(d/2.0-.14),0,-Math.cos(t)*(d/2.0-.14),90*k,d,1,1);}
        put(b,"base",0,0,0,0,d,1,d);
        put(b,"wall",0,WALL_FOOT,0,0,d,eave-WALL_FOOT,d);
        int courses=Math.max(1,(int)Math.round((eave-WALL_FOOT)/2.6));double ch=(eave-WALL_FOOT)/courses;
        int plates=Math.max(2,(int)Math.round(Math.PI*d/9));
        for(int c=0;c<courses;c++){
            double y0=WALL_FOOT+c*ch;
            if(c>0)put(b,"seam",0,y0-.0175,0,0,d,1,d);
            for(int j=0;j<plates;j++){double a=360.0*(j+.5*(c%2))/plates+17;if(clear(a,R,.30,ladderA))onShell(b,R,"seam_v",a,y0,ch);}
        }
        put(b,"top_angle",0,eave,0,0,d,1,d);
        put(b,"roof",0,eave,0,0,d,rr,d);
        put(b,"roof_seams",0,eave,0,0,d,rr,d);
        put(b,"roof_vent",0,eave+rr,0,0,1,1,1);
        // nozzles as before: four flanges one block above the base, faces on the box sides; a pad where each meets the shell
        for(int k=0;k<4;k++){
            double t=Math.toRadians(90*k);
            put(b,"port",-Math.sin(t)*d/2.0,PORT_Y,-Math.cos(t)*d/2.0,90*k,1,1,1);
            put(b,"repad",-Math.sin(t)*R,PORT_Y,-Math.cos(t)*R,90*k,1,1,1);
        }
        onShell(b,R,"manway",135,.62,1);
        onShell(b,R,"heater",45,.55,1);
        onShell(b,R,"gauge_head",-150,.55,1);
        for(double y=.9;y+1<=eave-.35+1e-6;y+=1)onShell(b,R,"gauge_board",-150,y,1);
        onShell(b,R,"gauge_top",-150,eave+.02,1);
        if(d>=5){
            onShell(b,R,"plate",112,1.25,1);
            onShell(b,R,"rad_sign",-118,Math.min(2.1,eave-.45),1);
            if(eave-WALL_FOOT>=3.5){double band=.41*d/12*1.5;put(b,"stencil",0,eave-.28*(eave-WALL_FOOT)-band/2,0,120,d,band,d);}
            double a=Math.toRadians(165),top=eave-.45;
            onShell(b,R,"overflow_top",165,top,1);
            onShell(b,R,"overflow_foot",165,.45,1);
            double px=(R+.52)*Math.cos(a),pz=(R+.52)*Math.sin(a);
            bar(b,"pipe",px,.67,pz,px,top-.22,pz,.10);
            for(double yc=1.6;yc<top-.6;yc+=2.0)onShell(b,R,"pipe_clip",165,yc,1);
        }
        int chairs=Math.max(8,4*(int)Math.round(Math.PI*d/1.7/4));
        for(int j=0;j<chairs;j++){double a=360.0*(j+.5)/chairs;if(clear(a,R,.42,135,45,-150,165,ladderA))onShell(b,R,"anchor_chair",a,WALL_FOOT,1);}
        // caged ladder up the existing ladder column (cage from 2.2 up) and the roof landing reaching in over the eave
        double land=roofY.applyAsDouble(R-.75)+.03;
        for(int y=0;y<h-1;y++){
            if(.2+y+1>land+.02)break;
            put(b,"ladder",.7,.2+y,zl,0,1,1,1);
            if(.2+y>=2.2)put(b,"cage",.7,.2+y,zl,0,1,1,1);
        }
        put(b,"cage",.7,land,zl,0,1,1,1);
        put(b,"landing",.7,land,zl,0,1,1,1);
        if(d>=5){double ha=Math.toRadians(ladderA-Math.toDegrees(1.3/R)),hr=R-1.1;put(b,"roof_hatch",hr*Math.cos(ha),roofY.applyAsDouble(hr),hr*Math.sin(ha),-(Math.toDegrees(ha)+90),1,1,1);}
        // perimeter handrail on the roof edge: posts about every 1.9 blocks, top and mid rails as chords, open at the landing
        double rp=R-.07,yp=roofY.applyAsDouble(rp);
        int posts=Math.max(6,(int)Math.round(Math.PI*2*rp/1.9));
        double[] angles=new double[posts];boolean[] keep=new boolean[posts];
        for(int j=0;j<posts;j++){angles[j]=360.0*(j+.5)/posts-90;keep[j]=clear(angles[j],R,.62,ladderA);}
        for(int j=0;j<posts;j++)if(keep[j]){double ar=Math.toRadians(angles[j]);put(b,"post",rp*Math.cos(ar),yp,rp*Math.sin(ar),0,1,1,1);}
        for(int j=0;j<posts;j++){
            int n=(j+1)%posts;if(!keep[j]||!keep[n])continue;
            double a0=Math.toRadians(angles[j]),a1=Math.toRadians(angles[n]);
            bar(b,"rail",rp*Math.cos(a0),yp+1.05,rp*Math.sin(a0),rp*Math.cos(a1),yp+1.05,rp*Math.sin(a1),.02);
            bar(b,"rail",rp*Math.cos(a0),yp+.55,rp*Math.sin(a0),rp*Math.cos(a1),yp+.55,rp*Math.sin(a1),.016);
        }
    }
    @Override public AABB getRenderBoundingBox(CondensateStorageTankBlockEntity be){int half=be.diameter/2;return new AABB(Vec3.atLowerCornerOf(be.getBlockPos().offset(-half,0,-half)),Vec3.atLowerCornerOf(be.getBlockPos().offset(half+1,be.height,half+1))).inflate(.75);}
    @Override public int getViewDistance(){return 192;}
    @Override public boolean shouldRenderOffScreen(CondensateStorageTankBlockEntity be){return be.getBlockState().getValue(CondensateStorageTankBlock.CONTROLLER);}
    @Override public boolean shouldRender(CondensateStorageTankBlockEntity be,Vec3 camera){return be.assembled()&&be.getBlockState().getValue(CondensateStorageTankBlock.CONTROLLER)&&MachineMeshCache.withinDistance(getRenderBoundingBox(be),camera,getViewDistance());}
}
