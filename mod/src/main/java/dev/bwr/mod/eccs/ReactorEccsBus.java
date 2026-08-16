package dev.bwr.mod.eccs;

import dev.bwr.core.ReactorCore;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Sums what every emergency system attached to one reactor is doing and hands
 * the totals to {@link ReactorCore}.
 *
 * <h2>Why this exists</h2>
 * {@code ReactorCore} takes one injection flow, one spray flow, one relief
 * flow and one boron rate. It deliberately does not know which machine any of
 * them came from — {@code ReactorCore#setInjectionFlowKgPerS} says as much. So
 * six machines that each write those setters directly would clobber each other,
 * and the last one to tick would win. This class is the summing junction.
 *
 * <h2>Channel ownership</h2>
 * A channel is only written if some machine has claimed it. A plant with no
 * ECCS at all is untouched, so {@code ReactorPeripheral.setInjectionFlow} still
 * works exactly as it did for players who have not built any of this hardware.
 * Once real machines exist they own the channel, which is the right answer:
 * what reaches the vessel should be what the pumps actually delivered.
 *
 * <ul>
 *   <li><b>Injection, spray and boron</b> are claimed by the injection pumps.</li>
 *   <li><b>Relief</b> — steam leaving the vessel to the suppression pool — is
 *       claimed by the ADS, which opens the relief valves, and by the
 *       turbine-driven pumps, whose drive steam leaves the vessel exactly the
 *       same way and lands in exactly the same place.</li>
 * </ul>
 *
 * <h2>What a key means, and why relief is keyed on the valve</h2>
 * The key is not "the machine that told us"; it is <b>the piece of hardware the
 * flow physically belongs to</b>, because contributions are <i>summed</i> across
 * keys and a piece of hardware must appear exactly once.
 *
 * <p>For a pump those are the same thing, so a pump keys on itself. A relief
 * valve is not: an ADS controller, a suppression pool controller, or several of
 * each can all be within sight of the same valve bank, and every one of them is
 * looking at the same steam. Reporters therefore key relief contributions on the
 * <b>valve's</b> position, so a second reporter overwrites the first with the
 * same number instead of adding to it. Both halves of the relief path — steam
 * out of the vessel and steam into the pool — then stay equal no matter which
 * combination of machines the player happened to build, and a redstone-actuated
 * valve with no ADS anywhere still takes its steam out of the vessel.
 *
 * <h2>Staleness</h2>
 * A contribution that has not been refreshed for a few ticks is dropped. That
 * is what stops a broken or chunk-unloaded pump from leaving phantom water
 * arriving in the vessel forever, and it is why {@link EccsNetwork} applies the
 * totals from a server tick handler rather than from the machines themselves.
 *
 * <p>No judgements anywhere in this class. It adds numbers up.
 */
public final class ReactorEccsBus {

    /** Contributions older than this are treated as gone. */
    private static final long STALE_TICKS = 3L;

    /** What one machine says it is doing this tick. */
    public static final class Contribution {
        long tick;
        double injectionKgPerS;
        double injectionTemperatureC;
        double sprayKgPerS;
        double sprayTemperatureC;
        double steamDrawKgPerS;
        double boronPpmPerMinute;
        boolean claimsInjectionChannels;
        boolean claimsReliefChannel;
    }

    private final Map<BlockPos, Contribution> contributions = new HashMap<>();

    // --- last applied totals, published for panels and peripherals ----
    private double totalInjectionKgPerS;
    private double totalSprayKgPerS;
    private double totalSteamKgPerS;
    private double totalBoronPpmPerMinute;

    /**
     * Post what one piece of hardware is delivering. Overwrites the previous
     * contribution under the same key.
     *
     * @param machine the position the flow belongs to — the machine's own for a
     *                pump, the <i>valve's</i> for a relief contribution, so that
     *                several reporters watching one valve cannot sum it twice
     */
    public void report(BlockPos machine, long gameTime,
                       double injectionKgPerS, double injectionTemperatureC,
                       double sprayKgPerS, double sprayTemperatureC,
                       double steamDrawKgPerS, double boronPpmPerMinute,
                       boolean claimsInjectionChannels, boolean claimsReliefChannel) {
        Contribution c = contributions.computeIfAbsent(machine.immutable(), p -> new Contribution());
        c.tick = gameTime;
        c.injectionKgPerS = finite(injectionKgPerS);
        c.injectionTemperatureC = injectionTemperatureC;
        c.sprayKgPerS = finite(sprayKgPerS);
        c.sprayTemperatureC = sprayTemperatureC;
        c.steamDrawKgPerS = finite(steamDrawKgPerS);
        c.boronPpmPerMinute = finite(boronPpmPerMinute);
        c.claimsInjectionChannels = claimsInjectionChannels;
        c.claimsReliefChannel = claimsReliefChannel;
    }

    /** Forget a machine entirely — it was broken, or it is going away. */
    public void withdraw(BlockPos machine) {
        contributions.remove(machine);
    }

    /** True once nothing is reporting, so the bus can be discarded. */
    public boolean isEmpty() {
        return contributions.isEmpty();
    }

    public int machineCount() {
        return contributions.size();
    }

    /**
     * Add every live contribution up and write the totals into the core.
     *
     * <p>Idempotent: calling it twice in one tick writes the same values twice.
     * Water temperatures are mass-weighted, because two pumps on different
     * suction sources really do arrive as one mixed stream.
     */
    public void applyTo(ReactorCore core, long gameTime) {
        expire(gameTime);
        if (core == null) {
            return;
        }

        double injection = 0.0;
        double injectionHeat = 0.0;
        double spray = 0.0;
        double sprayHeat = 0.0;
        double steam = 0.0;
        double boron = 0.0;
        boolean anyInjectionClaim = false;
        boolean anyReliefClaim = false;

        for (Contribution c : contributions.values()) {
            if (c.claimsInjectionChannels) {
                anyInjectionClaim = true;
                injection += c.injectionKgPerS;
                injectionHeat += c.injectionKgPerS * c.injectionTemperatureC;
                spray += c.sprayKgPerS;
                sprayHeat += c.sprayKgPerS * c.sprayTemperatureC;
                boron += c.boronPpmPerMinute;
            }
            if (c.claimsReliefChannel) {
                anyReliefClaim = true;
                steam += c.steamDrawKgPerS;
            }
        }

        totalInjectionKgPerS = injection;
        totalSprayKgPerS = spray;
        totalSteamKgPerS = steam;
        totalBoronPpmPerMinute = boron;

        if (anyInjectionClaim) {
            core.setInjectionFlowKgPerS(injection);
            core.setCoreSprayFlowKgPerS(spray);
            core.setBoronInjectionPpmPerMinute(boron);
            if (injection > 0.0) {
                core.setInjectionTemperatureC(injectionHeat / injection);
            }
            if (spray > 0.0) {
                core.setCoreSprayTemperatureC(sprayHeat / spray);
            }
        }
        if (anyReliefClaim) {
            core.setReliefSteamFlowKgPerS(steam);
        }
    }

    private void expire(long gameTime) {
        Iterator<Map.Entry<BlockPos, Contribution>> it = contributions.entrySet().iterator();
        while (it.hasNext()) {
            if (gameTime - it.next().getValue().tick > STALE_TICKS) {
                it.remove();
            }
        }
    }

    private static double finite(double v) {
        return Double.isFinite(v) && v > 0.0 ? v : 0.0;
    }

    // --- measurements -------------------------------------------------

    /** Total emergency injection reaching the vessel, kg/s. */
    public double getTotalInjectionKgPerS() {
        return totalInjectionKgPerS;
    }

    /** Total core spray reaching the spargers, kg/s. */
    public double getTotalSprayKgPerS() {
        return totalSprayKgPerS;
    }

    /** Total steam leaving the vessel to the pool — relief valves plus turbine drives, kg/s. */
    public double getTotalSteamKgPerS() {
        return totalSteamKgPerS;
    }

    /** Total boron being added, ppm per minute. */
    public double getTotalBoronPpmPerMinute() {
        return totalBoronPpmPerMinute;
    }
}
