package dev.bwr.mod.power;

import dev.bwr.core.turbine.TurbineBlades;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Server-side hooks for whatever wants to react to a turbine (a sound, a screen shake, a log): posted on {@link NeoForge#EVENT_BUS},
 * on the server thread, after the fact. They carry readings only and cannot be cancelled; nothing in the mod listens to them to act on
 * the plant. Subscribe with {@code @SubscribeEvent public static void on(TurbineEvents.Missile e)}.
 */
public final class TurbineEvents {
    private TurbineEvents() {}

    /**
     * A turbine missile was thrown (TURBINE-MAINTENANCE.md): a last-stage blade, or a disc fragment from a burst. Posted once, when the
     * piece leaves the rotor, after its flight was worked out (blocks broken, entities hit) and the blocks were removed.
     */
    public static final class Missile extends Event {
        private final ServerLevel level;
        private final BlockPos turbine;
        private final Vec3 origin, direction, end;
        private final TurbineBlades.Missile kind;
        private final double energyJ, massKg, speedMps, rpm;
        private final int blocksBroken;

        public Missile(ServerLevel level, BlockPos turbine, Vec3 origin, Vec3 direction, Vec3 end, TurbineBlades.Missile kind,
                       double energyJ, double massKg, double speedMps, double rpm, int blocksBroken) {
            this.level = level;
            this.turbine = turbine.immutable();
            this.origin = origin;
            this.direction = direction;
            this.end = end;
            this.kind = kind;
            this.energyJ = energyJ;
            this.massKg = massKg;
            this.speedMps = speedMps;
            this.rpm = rpm;
            this.blocksBroken = blocksBroken;
        }

        public ServerLevel level() { return level; }
        /** The LP section's controller block. */
        public BlockPos turbine() { return turbine; }
        /** Where the piece left the rotor (the wheel of its flow, on the shaft axis), world coordinates. */
        public Vec3 origin() { return origin; }
        /** Its unit direction of flight, in the plane of the wheel. */
        public Vec3 direction() { return direction; }
        /** Where it came to rest (stopped in a block, ran out of range, or reached a chunk that is not loaded). */
        public Vec3 end() { return end; }
        public TurbineBlades.Missile kind() { return kind; }
        /** Its kinetic energy as it left the rotor, J. */
        public double energyJ() { return energyJ; }
        public double massKg() { return massKg; }
        public double speedMps() { return speedMps; }
        /** Shaft speed when it let go, rpm. */
        public double rpm() { return rpm; }
        public int blocksBroken() { return blocksBroken; }
    }

    /**
     * The vibration of a turning shaft train, posted once a second (every 20 game ticks) for each train turning faster than 1 rpm or
     * shaking more than 0.1 mm/s: the worst section's velocity amplitude, mm/s (a healthy rotor's 1.5 mm/s at rated speed, a bowed
     * rotor through the critical band, a thrown blade's imbalance), its speed, and where the train is.
     */
    public static final class Vibration extends Event {
        private final ServerLevel level;
        private final BlockPos at;
        private final Vec3 centre;
        private final double mmPerS, rpm;
        private final int sections;

        public Vibration(ServerLevel level, BlockPos at, Vec3 centre, double mmPerS, double rpm, int sections) {
            this.level = level;
            this.at = at.immutable();
            this.centre = centre;
            this.mmPerS = mmPerS;
            this.rpm = rpm;
            this.sections = sections;
        }

        public ServerLevel level() { return level; }
        /** The worst-shaking section's controller block. */
        public BlockPos at() { return at; }
        /** The middle of the train (the mean of its sections' controller blocks). */
        public Vec3 centre() { return centre; }
        public double mmPerS() { return mmPerS; }
        public double rpm() { return rpm; }
        public int sections() { return sections; }
    }

    static void post(Event e) {
        NeoForge.EVENT_BUS.post(e);
    }
}
