package dev.bwr.mod.reactor.client;

import dev.bwr.mod.registry.BwrParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.ParticleGroup;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import java.util.Optional;

/** White condensed-water plume, with a slight neutral shading variation. */
@EventBusSubscriber(modid="bwr",value=Dist.CLIENT)
public final class AccidentSteamParticle extends TextureSheetParticle {
    private static final ParticleGroup GROUP=new ParticleGroup(600);
    private final float size;
    private AccidentSteamParticle(ClientLevel l,double x,double y,double z,double vx,double vy,double vz,SpriteSet sprites){
        super(l,x,y,z);xd=vx;yd=vy;zd=vz;hasPhysics=false;
        lifetime=70+random.nextInt(40);size=.45f+random.nextFloat()*.6f;quadSize=size;alpha=.85f;pickSprite(sprites);
        rCol=gCol=bCol=.94f+random.nextFloat()*.06f;
    }
    @Override public void tick(){
        xo=x;yo=y;zo=z;if(age++>=lifetime){remove();return;}
        double t=(double)age/lifetime;
        xd*=.965;zd*=.965;yd=yd*.965+.012;
        xd+=Math.sin(age*.21+x*.2)*.009;zd+=Math.cos(age*.17+z*.2)*.009;
        move(xd,yd,zd);quadSize=size*(1+3*(float)t);alpha=.85f*(1-(float)t);

    }
    @Override public ParticleRenderType getRenderType(){return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;}
    @Override public Optional<ParticleGroup> getParticleGroup(){return Optional.of(GROUP);}
    @SubscribeEvent public static void register(RegisterParticleProvidersEvent e){
        e.registerSpriteSet(BwrParticles.ACCIDENT_STEAM.get(),sprites->(type,l,x,y,z,vx,vy,vz)->{
            var setting=net.minecraft.client.Minecraft.getInstance().options.particles().get();
            if(setting==net.minecraft.client.ParticleStatus.MINIMAL||setting==net.minecraft.client.ParticleStatus.DECREASED&&l.random.nextBoolean())return null;
            return new AccidentSteamParticle(l,x,y,z,vx,vy,vz,sprites);
        });
    }
}
