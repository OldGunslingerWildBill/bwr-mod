package dev.bwr.mod.registry;

import dev.bwr.mod.BwrMod;
import net.minecraft.core.particles.*;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.*;

public final class BwrParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLES=DeferredRegister.create(Registries.PARTICLE_TYPE,BwrMod.MOD_ID);
    public static final DeferredHolder<ParticleType<?>,SimpleParticleType> COOLING_VAPOR=PARTICLES.register("cooling_vapor",()->new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>,SimpleParticleType> ACCIDENT_STEAM=PARTICLES.register("accident_steam",()->new SimpleParticleType(false));
    private BwrParticles(){}
}
