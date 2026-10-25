package dev.bwr.mod.rwcu;

import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.reactor.FormedReactorRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Activated crud in the coolant lines as a radiation source. The crud circulates with the water through the loops outside
 * the vessel, so its gamma ({@code CoolantChemistry#crudGammaWatts}) is shared out over the vessel's water ports, where the
 * lines leave it, behind the pipe's own shielding ({@link #TRANSMISSION}). One provider for every formed reactor: it costs a
 * loop over the reactors and their ports whenever an instrument samples, never a tick.
 */
public final class CoolantCrudRadiation {
    private CoolantCrudRadiation() {}

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("bwr", "coolant_crud");
    /** What the pipe wall and the water around the crud let through. */
    public static final double TRANSMISSION = 0.15;

    public static void register() { RadiationSources.register(ID, CoolantCrudRadiation::doseRate); }

    static double doseRate(Level level, Vec3 at) {
        if (level.isClientSide()) return 0;
        double total = 0;
        for (var reactor : FormedReactorRegistry.controllers(level)) {
            var core = reactor.core();
            var structure = reactor.structure();
            if (core == null || structure == null) continue;
            double watts = core.getChemistry().crudGammaWatts();
            if (!(watts > 0)) continue;
            var ports = structure.waterInjectionPositions();
            if (ports.isEmpty()) continue;
            double share = watts / ports.size();
            for (BlockPos port : ports) total += DoseModel.pointSourceMsvPerH(share, at.distanceTo(Vec3.atCenterOf(port)), TRANSMISSION);
        }
        return total;
    }
}
