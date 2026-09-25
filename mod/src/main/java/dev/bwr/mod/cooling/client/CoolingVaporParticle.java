package dev.bwr.mod.cooling.client;

import dev.bwr.mod.registry.BwrParticles;
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
    private final float initialSize,opacity;
    private final double lift,phase;

    private CoolingVaporParticle(ClientLevel level,double x,double y,double z,double size,double rise,double strength,SpriteSet sprites){
        super(level,x,y,z);
        initialSize=(float)Math.clamp(size,.5,5);lift=Math.clamp(rise,.15,.55);
        opacity=(float)Math.clamp(strength,.1,.6);phase=random.nextDouble()*Math.PI*2;
        lifetime=420+random.nextInt(180);hasPhysics=false;
        rCol=.94f;gCol=.96f;bCol=.98f;quadSize=initialSize;alpha=0;
        pickSprite(sprites);
    }
    @Override public void tick(){
        xo=x;yo=y;zo=z;
        if(age++>=lifetime){remove();return;}
        double progress=(double)age/lifetime;
        double time=level.getGameTime()*.0025;
        double windAngle=.55+.45*Math.sin(time*.37)+.20*Math.sin(time*.81);
        double wind=.055+.025*Math.sin(time*.61)+(level.isRaining()?.055:0);
        double eddy=.022+.045*progress;
        xd+=(Math.cos(windAngle)*wind+Math.sin(age*.029+phase)*eddy-xd)*.025;
        zd+=(Math.sin(windAngle)*wind+Math.cos(age*.023+phase)*eddy-zd)*.025;
        yd+=(lift*(1-.6*progress)-yd)*.06;
        move(xd,yd,zd);
        quadSize=initialSize*(1+2.5f*(float)progress);
        alpha=opacity*(float)Math.min(1,age/25.0)*(float)Math.pow(1-progress,1.4);
        setSize(quadSize*2,quadSize*2);
    }
    @Override public ParticleRenderType getRenderType(){return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;}
    @Override public Optional<ParticleGroup> getParticleGroup(){return Optional.of(GROUP);}
    @SubscribeEvent public static void providers(RegisterParticleProvidersEvent event){
        event.registerSpriteSet(BwrParticles.COOLING_VAPOR.get(),sprites->(type,level,x,y,z,size,rise,strength)->{
            var setting=net.minecraft.client.Minecraft.getInstance().options.particles().get();
            if(setting==net.minecraft.client.ParticleStatus.MINIMAL
                    ||setting==net.minecraft.client.ParticleStatus.DECREASED&&level.random.nextBoolean())return null;
            return new CoolingVaporParticle(level,x,y,z,size,rise,strength,sprites);
        });
    }
}
