package dev.bwr.mod.reactor.client;

import com.mojang.blaze3d.vertex.*;
import dev.bwr.mod.reactor.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;

/** One small translucent disk per open vessel. Static steel/core GPU meshes stay cached. */
final class VesselWaterSurface {
    private static final ResourceLocation WATER=ResourceLocation.withDefaultNamespace("block/water_still");
    private static final int SEGMENTS=64;
    static void render(VesselAppearance.Envelope e,double level,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        double y=VesselWaterGeometry.height(e,level),r=VesselWaterGeometry.radiusFraction(y);
        var sprite=Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(WATER);
        var out=buffers.getBuffer(RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));
        var matrix=pose.last();
        for(int i=0;i<SEGMENTS;i++) {
            double a=-i*Math.PI*2/SEGMENTS,b=-(i+1)*Math.PI*2/SEGMENTS;
            vertex(out,matrix,0,y,0,sprite.getU(.5f),sprite.getV(.5f),light,overlay);
            vertex(out,matrix,r*e.width()*Math.cos(a),y,r*e.depth()*Math.sin(a),sprite.getU((float)(.5+.5*Math.cos(a))),sprite.getV((float)(.5+.5*Math.sin(a))),light,overlay);
            vertex(out,matrix,r*e.width()*Math.cos(b),y,r*e.depth()*Math.sin(b),sprite.getU((float)(.5+.5*Math.cos(b))),sprite.getV((float)(.5+.5*Math.sin(b))),light,overlay);
            vertex(out,matrix,0,y,0,sprite.getU(.5f),sprite.getV(.5f),light,overlay);
        }
    }
    private static void vertex(VertexConsumer out,PoseStack.Pose p,double x,double y,double z,float u,float v,int light,int overlay) {
        out.addVertex(p,(float)x,(float)y,(float)z).setColor(70,139,197,160).setUv(u,v).setOverlay(overlay).setLight(light).setNormal(p,0,1,0);
    }
    private VesselWaterSurface(){}
}
