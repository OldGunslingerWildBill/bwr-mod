package dev.bwr.mod.reactor.client;

import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.registry.BwrParticles;
import net.minecraft.core.BlockPos;
import java.util.*;

/** Visible-vessel emission, once per client tick. Never rebuilds the vessel mesh. */
public final class VesselAccidentEffects {
    private static final Map<ReactorControllerBlockEntity,Long> LAST=new WeakHashMap<>();
    public static void emit(ReactorControllerBlockEntity be){
        var l=be.getLevel();if(l==null||!l.isClientSide()||be.clientPressurePsig()<=.5)return;
        long now=l.getGameTime();if(Objects.equals(LAST.put(be,now),now))return;
        double speed=.3+Math.min(1.6,be.clientPressurePsig()/1000);
        int emitted=0;
        for(long raw:be.clientBoundaryLeaks()){
            if(emitted++>=3)break;
            var p=BlockPos.of(raw);var e=be.clientVesselEnvelope();var face=e.face(p);
            for(int i=0;i<2;i++)l.addParticle(BwrParticles.ACCIDENT_STEAM.get(),true,
                    p.getX()+.5+face.getStepX()*.8,p.getY()+.5,p.getZ()+.5+face.getStepZ()*.8,
                    face.getStepX()*speed,.25,face.getStepZ()*speed);
        }
        if(be.clientHeadFailed()){
            var e=be.clientVesselEnvelope();double cx=e.min().getX()+e.width()/2.0,cz=e.min().getZ()+e.depth()/2.0;
            double mouth=e.min().getY()+e.height()-Math.min(e.height()*.22,Math.min(e.width(),e.depth())*.18)-.15;
            for(int i=0;i<5;i++){
                double angle=l.random.nextDouble()*Math.PI*2,r=l.random.nextDouble()*e.width()*.22;
                l.addParticle(BwrParticles.ACCIDENT_STEAM.get(),true,cx+Math.cos(angle)*r,mouth,cz+Math.sin(angle)*r,
                        (l.random.nextDouble()-.5)*.3,speed,(l.random.nextDouble()-.5)*.3);
            }
        }
    }
}
