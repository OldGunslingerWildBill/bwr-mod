package dev.bwr.mod.eccs;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.ReactorCore;
import dev.bwr.core.feedwater.FeedwaterDesign;
import dev.bwr.core.feedwater.FeedwaterHeating;
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
 *   <li><b>Relief</b> — steam leaving the vessel other than through the main
 *       steam nozzles — is claimed by the ADS, which opens the relief valves,
 *       and by every turbine-driven pump, whose drive steam leaves the vessel
 *       exactly the same way. Where it <i>lands</i> is a separate report and
 *       not this class's business: RCIC and HPCI exhaust into the suppression
 *       pool and tell the pool so, while a turbine-driven feed pump exhausts to
 *       the plant's condenser — the player's Mekanism turbine — and tells it
 *       nothing.</li>
 *   <li><b>Feedwater</b> is claimed by the reactor feed pumps, through
 *       {@link #reportFeedwater}. It is the normal level control path and a
 *       different system from emergency injection, so it is a different
 *       channel; {@code SPEC.md} section 15.</li>
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
        double feedwaterKgPerS;
        double feedwaterSuctionTemperatureC;
        boolean claimsInjectionChannels;
        boolean claimsReliefChannel;
        boolean claimsFeedwaterChannel;
    }

    private final Map<BlockPos, Contribution> contributions = new HashMap<>();
    // Remember ownership until its final zero has reached the core. Otherwise
    // removing/expiring the last reporter leaves the last rate latched forever.
    private boolean appliedInjection, appliedRelief, appliedFeedwater;

    // --- last applied totals, published for panels and peripherals ----
    private double totalInjectionKgPerS;
    private double totalSprayKgPerS;
    private double totalSteamKgPerS;
    private double totalBoronPpmPerMinute;
    private double totalFeedwaterKgPerS;
    private double totalFeedwaterTemperatureC =
            PhysicalConstants.RATED_FEEDWATER_TEMPERATURE_C;

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
        // An emergency machine owns no feedwater. Written rather than left
        // alone, so that one key is completely defined by whichever report
        // method last wrote it and a machine can never inherit a stale figure
        // from a different kind of machine that once stood in the same block.
        c.feedwaterKgPerS = 0.0;
        c.feedwaterSuctionTemperatureC = 0.0;
        c.claimsFeedwaterChannel = false;
    }

    /**
     * Post what one reactor feed pump is delivering — {@code SPEC.md} section
     * 15. The normal level control path, and a separate channel from emergency
     * injection because it is a separate system in the plant and in
     * {@link ReactorCore}.
     *
     * <p>The suction temperature is the temperature of the <b>condensate</b>,
     * not of the feedwater that arrives: the heater string sits between them and
     * is applied in {@link #applyTo} once the whole plant's flow is known, since
     * how hot the heaters run depends on total steam flow rather than on any one
     * pump. See {@link FeedwaterHeating}.
     *
     * <p>A turbine-driven feed pump also reports {@code steamDrawKgPerS} and
     * claims the relief channel, because its drive steam genuinely leaves the
     * vessel. Unlike RCIC and HPCI it does <b>not</b> report an exhaust to the
     * suppression pool: it exhausts to the plant's condenser, which in this mod
     * is the player's Mekanism turbine, the same place the main steam goes.
     *
     * @param machine             the pump's own position — a pump is one piece of
     *                            hardware and appears exactly once, so it keys on
     *                            itself
     * @param claimsReliefChannel true for a turbine-driven pump, whatever its
     *                            drive steam currently is, and false for a motor.
     *                            It must <b>not</b> be derived from the steam
     *                            flow being non-zero: a turbine pump that coasts
     *                            to a stop would then stop claiming the channel,
     *                            and if it were the only claimant on the bus the
     *                            core would keep the last relief flow it was
     *                            given forever — steam leaving a vessel that no
     *                            machine is taking any out of. Claiming while
     *                            zero is what makes the channel go to zero. The
     *                            emergency machines claim on the same basis
     */
    public void reportFeedwater(BlockPos machine, long gameTime,
                                double feedwaterKgPerS, double suctionTemperatureC,
                                double steamDrawKgPerS, boolean claimsReliefChannel) {
        Contribution c = contributions.computeIfAbsent(machine.immutable(), p -> new Contribution());
        c.tick = gameTime;
        c.feedwaterKgPerS = finite(feedwaterKgPerS);
        c.feedwaterSuctionTemperatureC = suctionTemperatureC;
        c.claimsFeedwaterChannel = true;
        c.steamDrawKgPerS = finite(steamDrawKgPerS);
        c.claimsReliefChannel = claimsReliefChannel;
        // A feed pump owns none of the emergency channels. Same reasoning as
        // the tail of report() above.
        c.injectionKgPerS = 0.0;
        c.injectionTemperatureC = 0.0;
        c.sprayKgPerS = 0.0;
        c.sprayTemperatureC = 0.0;
        c.boronPpmPerMinute = 0.0;
        c.claimsInjectionChannels = false;
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
        double feedwater = 0.0;
        double feedwaterHeat = 0.0;
        boolean anyInjectionClaim = false;
        boolean anyReliefClaim = false;
        boolean anyFeedwaterClaim = false;

        for (Contribution c : contributions.values()) {
            if (c.claimsFeedwaterChannel) {
                anyFeedwaterClaim = true;
                feedwater += c.feedwaterKgPerS;
                feedwaterHeat += c.feedwaterKgPerS * c.feedwaterSuctionTemperatureC;
            }
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
        totalFeedwaterKgPerS = feedwater;
        // Mass-weighted, like the injection temperature above, because two feed
        // pumps on different suctions really do arrive as one mixed stream.
        // With no flow there is nothing to weight, so the heater string is
        // reported at the condensate temperature a stopped plant would have.
        double mixedSuctionC = feedwater > 0.0
                ? feedwaterHeat / feedwater : CondensateStorageTankBlockEntity.STORED_TEMPERATURE_C;
        totalFeedwaterTemperatureC = FeedwaterHeating.finalTemperatureC(mixedSuctionC,
                feedwater / FeedwaterDesign.RATED_FEEDWATER_FLOW_KG_PER_S);

        if (anyInjectionClaim || appliedInjection) {
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
        if (anyReliefClaim || appliedRelief) {
            core.setReliefSteamFlowKgPerS(steam);
        }
        if (anyFeedwaterClaim || appliedFeedwater) {
            core.setFeedwaterFlowKgPerS(feedwater);
            core.setFeedwaterTemperatureC(totalFeedwaterTemperatureC);
        }
        appliedInjection=anyInjectionClaim;
        appliedRelief=anyReliefClaim;
        appliedFeedwater=anyFeedwaterClaim;
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

    /** Total feedwater reaching the vessel from the feed pumps, kg/s. */
    public double getTotalFeedwaterKgPerS() {
        return totalFeedwaterKgPerS;
    }

    /**
     * Temperature feedwater arrives at, degrees C — after the modelled heater
     * string, not the temperature of the condensate the pumps are taking.
     */
    public double getTotalFeedwaterTemperatureC() {
        return totalFeedwaterTemperatureC;
    }
}
