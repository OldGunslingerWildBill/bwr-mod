package dev.bwr.mod.reactor.client;

import com.mojang.blaze3d.vertex.*;
import dev.bwr.mod.reactor.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.resources.ResourceLocation;

/** Small emissive volume clipped to submerged fuel. No particles, world lights or mesh rebuilds. */
final class VesselCherenkovGlow {
    private static final int N=48;
    private static final double[] X=new double[N+1],Z=new double[N+1];
    private static final double[] RADII={0,.55,.82,1};
    private static final float[] ALPHAS={.72f,.65f,.26f,0};
    private static final ResourceLocation WATER=ResourceLocation.withDefaultNamespace("block/water_still");
    static {for(int i=0;i<=N;i++){X[i]=Math.cos(-i*Math.PI*2/N);Z[i]=Math.sin(-i*Math.PI*2/N);}}
    static void render(ReactorControllerBlockEntity be,VesselAppearance.Envelope e,VesselCoreAppearance core,float partial,PoseStack pose,MultiBufferSource buffers) {
        double surface=VesselWaterGeometry.height(e,be.clientWaterLevelIn(partial));
        float strength=(float)CherenkovAppearance.strength(be.clientPowerFractionOfRated(),be.clientHasFuel(),be.clientWaterPresent(),surface);
        if(strength<.002)return;
        double top=Math.min(surface-.035,e.height()-5.88);
        if(top<=2.02)return;
        double radius=ReactorCoreGeometry.fuelEnvelopeRadius(core)*1.01;
        var sprite=Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(WATER);
        var out=buffers.getBuffer(RenderType.eyes(TextureAtlas.LOCATION_BLOCKS));
        var p=pose.last();
        disk(out,p,sprite,e,radius,top,strength);
        // Gentle light at the surface fades with the depth above the core.
        if(surface>top+.01)disk(out,p,sprite,e,radius,surface+.008,strength*(float)(.85/(1+(surface-top)*.12)));
        double rx=e.width()*radius*.88,rz=e.depth()*radius*.88;
        for(int i=0;i<N;i++) {
            vertex(out,p,sprite,rx*X[i],2.02,rz*Z[i],.1f*strength);
            vertex(out,p,sprite,rx*X[i+1],2.02,rz*Z[i+1],.1f*strength);
            vertex(out,p,sprite,rx*X[i+1],top,rz*Z[i+1],.035f*strength);
            vertex(out,p,sprite,rx*X[i],top,rz*Z[i],.035f*strength);
        }
    }
    private static void disk(VertexConsumer out,PoseStack.Pose p,TextureAtlasSprite sprite,VesselAppearance.Envelope e,double radius,double y,float strength) {
        for(int ring=0;ring<RADII.length-1;ring++)for(int i=0;i<N;i++) {
            double inner=radius*RADII[ring],outer=radius*RADII[ring+1];
            vertex(out,p,sprite,e.width()*inner*X[i],y,e.depth()*inner*Z[i],ALPHAS[ring]*strength);
            vertex(out,p,sprite,e.width()*outer*X[i],y,e.depth()*outer*Z[i],ALPHAS[ring+1]*strength);
            vertex(out,p,sprite,e.width()*outer*X[i+1],y,e.depth()*outer*Z[i+1],ALPHAS[ring+1]*strength);
            vertex(out,p,sprite,e.width()*inner*X[i+1],y,e.depth()*inner*Z[i+1],ALPHAS[ring]*strength);
        }
    }
    private static void vertex(VertexConsumer out,PoseStack.Pose p,TextureAtlasSprite sprite,double x,double y,double z,float alpha) {
        // The additive shader uses ONE/ONE blending: premultiply RGB so the
        // radial fade and physical power actually extinguish the emitted light.
        out.addVertex(p,(float)x,(float)y,(float)z).setColor(.04f*alpha,.6f*alpha,alpha,alpha)
                // A constant animated-water texel keeps the light smooth;
                // wrapping UVs independently at vertices creates triangular seams.
                .setUv(sprite.getU(.5f),sprite.getV(.5f))
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(p,0,1,0);
    }
    private VesselCherenkovGlow(){}
}
