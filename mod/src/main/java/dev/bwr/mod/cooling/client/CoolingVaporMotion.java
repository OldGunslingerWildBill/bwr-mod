package dev.bwr.mod.cooling.client;

import net.minecraft.client.multiplayer.ClientLevel;

/** The classic particle and instanced plume integrate the same motion rule. */
public final class CoolingVaporMotion {
    public record Step(double x, double y, double z, float size, float alpha) {}

    private CoolingVaporMotion() {}

    public static Step step(ClientLevel level, int age, int lifetime, double phase, double lift,
                            double sourceX, double sourceY, double sourceZ, long windSalt,
                            double vx, double vy, double vz, float initialSize, float opacity, double currentY) {
        double progress = (double)age / lifetime;
        var wind = CoolingVaporWind.sample(level.getGameTime() * .05, sourceX, sourceZ,
                Math.max(0, currentY - sourceY), windSalt, level.isRaining());
        double eddy = .008 + .024 * progress;
        double x = vx + (wind.x() + Math.sin(age * .029 + phase) * eddy - vx) * .045;
        double z = vz + (wind.z() + Math.cos(age * .023 + phase) * eddy - vz) * .045;
        double y = vy + (lift * (1 - .6 * progress) * wind.liftFactor() - vy) * .06;
        float size = initialSize * (1 + 2.5f * (float)progress);
        double dispersal = Math.clamp((progress - .35) / .65, 0, 1);
        double fade = 1 - dispersal * dispersal * (3 - 2 * dispersal);
        float alpha = opacity * (float)Math.min(1, age / 6.0) * (float)fade;
        return new Step(x, y, z, size, alpha);
    }
}
