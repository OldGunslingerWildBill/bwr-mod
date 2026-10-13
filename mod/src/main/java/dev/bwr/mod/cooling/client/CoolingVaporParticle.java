package dev.bwr.mod.cooling.client;

import dev.bwr.mod.registry.BwrParticles;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import java.util.Optional;

/** Client-only condensed droplets: buoyant rise, shared changing wind, entrainment and dispersal. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class CoolingVaporParticle extends TextureSheetParticle {
    private static final ParticleGroup GROUP=new ParticleGroup(1_200);
    // Vapor layers blend through each other but still depth-test against solid scenery.
    // Writing depth here cuts later layers into hard circular slices.
    private static final ParticleRenderType VAPOR_SHEET=(tesselator,textures)->{
        var buffer=ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT.begin(tesselator,textures);
        RenderSystem.depthMask(false);
        return buffer;
    };
    private static SpriteSet spriteSet;
    private final float initialSize,opacity;
    private final double lift,phase,sourceX,sourceY,sourceZ;
    private final long windSalt;

    private CoolingVaporParticle(ClientLevel level,double x,double y,double z,double size,double rise,double strength,SpriteSet sprites){
        super(level,x,y,z);
        initialSize=(float)Math.clamp(size,.5,5);lift=Math.clamp(rise,.15,.55);
        opacity=(float)Math.clamp(strength,.05,.9);phase=random.nextDouble()*Math.PI*2;
        sourceX=x;sourceY=y;sourceZ=z;windSalt=level.dimension().location().hashCode();
        lifetime=420+random.nextInt(180);hasPhysics=false;
        rCol=gCol=bCol=1;quadSize=initialSize;alpha=0;
        pickSprite(sprites);
    }
    @Override public void tick(){
        xo=x;yo=y;zo=z;
        if(age++>=lifetime){remove();return;}
        var next=CoolingVaporMotion.step((ClientLevel)level,age,lifetime,phase,lift,sourceX,sourceY,sourceZ,
                windSalt,xd,yd,zd,initialSize,opacity,y);
        xd=next.x();yd=next.y();zd=next.z();
        move(xd,yd,zd);
        quadSize=next.size();alpha=next.alpha();
        setSize(quadSize*2,quadSize*2);
    }
    @Override public ParticleRenderType getRenderType(){return VAPOR_SHEET;}
    @Override public Optional<ParticleGroup> getParticleGroup(){return Optional.of(GROUP);}
    @SubscribeEvent public static void providers(RegisterParticleProvidersEvent event){
        event.registerSpriteSet(BwrParticles.COOLING_VAPOR.get(),sprites->{
            spriteSet=sprites;
            return (type,level,x,y,z,size,rise,strength)->{
            var setting=net.minecraft.client.Minecraft.getInstance().options.particles().get();
            if(setting==net.minecraft.client.ParticleStatus.MINIMAL
                    ||setting==net.minecraft.client.ParticleStatus.DECREASED&&level.random.nextBoolean())return null;
            return new CoolingVaporParticle(level,x,y,z,size,rise,strength,sprites);
            };
        });
    }
    public static SpriteSet spriteSet(){return spriteSet;}
}
