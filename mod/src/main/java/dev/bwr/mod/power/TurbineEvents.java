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

    /**
     * A shaft excursion (TURBINE-MAINTENANCE.md): a burst LP rotor broke loose and one flow's rotor half left the casing. Posted once,
     * after its path was worked out and the blocks in it were removed, with where it came to rest (the Turbine Shaft Wreck block, or
     * null if it was left as an item).
     */
    public static final class Excursion extends Event {
        private final ServerLevel level;
        private final BlockPos turbine;
        private final Vec3 origin, direction, end;
        private final double energyJ, rpm;
        private final int blocksBroken;
        private final BlockPos wreck;

        public Excursion(ServerLevel level, BlockPos turbine, Vec3 origin, Vec3 direction, Vec3 end, double energyJ, double rpm, int blocksBroken,
                         BlockPos wreck) {
            this.level = level;
            this.turbine = turbine.immutable();
            this.origin = origin;
            this.direction = direction;
            this.end = end;
            this.energyJ = energyJ;
            this.rpm = rpm;
            this.blocksBroken = blocksBroken;
            this.wreck = wreck == null ? null : wreck.immutable();
        }

        public ServerLevel level() { return level; }
        /** The LP section's controller block. */
        public BlockPos turbine() { return turbine; }
        /** Where the rotor half started (its seat in the casing), world coordinates. */
        public Vec3 origin() { return origin; }
        /** Its unit direction along the floor. */
        public Vec3 direction() { return direction; }
        /** Where it came to rest. */
        public Vec3 end() { return end; }
        /** Its energy along the floor once it was through its casing's end wall, J. */
        public double energyJ() { return energyJ; }
        /** Shaft speed when it broke loose, rpm. */
        public double rpm() { return rpm; }
        public int blocksBroken() { return blocksBroken; }
        /** The Turbine Shaft Wreck block it lies as, or null if it was left as an item. */
        public BlockPos wreck() { return wreck; }
    }

    /** A turbine fire started (TURBINE-MAINTENANCE.md): lube oil (a dry bearing, a cut line) or a generator's hydrogen. Posted once, when it lights. */
    public static final class Fire extends Event {
        private final ServerLevel level;
        private final BlockPos at;
        private final dev.bwr.core.turbine.TurbineFire.Kind kind;
        private final double fuel;

        public Fire(ServerLevel level, BlockPos at, dev.bwr.core.turbine.TurbineFire.Kind kind, double fuel) {
            this.level = level;
            this.at = at.immutable();
            this.kind = kind;
            this.fuel = fuel;
        }

        public ServerLevel level() { return level; }
        /** The burning section's controller block. */
        public BlockPos at() { return at; }
        public dev.bwr.core.turbine.TurbineFire.Kind kind() { return kind; }
        /** What caught: oil, L, or hydrogen, kg. */
        public double fuel() { return fuel; }
    }

    static void post(Event e) {
        NeoForge.EVENT_BUS.post(e);
    }
}
