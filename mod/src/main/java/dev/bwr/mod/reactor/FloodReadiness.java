package dev.bwr.mod.reactor;

import dev.bwr.core.PhysicalConstants;
import dev.bwr.core.thermal.PressureVessel;
import dev.bwr.core.thermal.Saturation;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The flood checklist of an open reactor: why the well floods, or why it does not, in the player's words, one line per condition with the place
 * that is wrong. It reads the hardware and the water and commands nothing (the INFO tab and {@code getFloodReadiness()} show it).
 *
 * <ol>
 *   <li><b>head</b>: the head is off the vessel (water over the flange of a closed vessel stays inside it);</li>
 *   <li><b>studs</b>: every closure stud is detensioned (needed before the head lifts);</li>
 *   <li><b>seal</b>: the cavity seal formed, or which side of the ring is how far off, and where;</li>
 *   <li><b>well</b>: the well reaches the flange, or the row and the block where its wall opens (water would spread more than
 *   {@value CavitySeal#WELL_EXTENT} blocks from the vessel there);</li>
 *   <li><b>inflow</b>: water going in now, kg/s, and from what: the feed pumps, the injection pumps (RHR/LPCI, core spray, condensate through a fill
 *   port), the fill ports by gravity, the pool through the canal;</li>
 *   <li><b>outflow</b>: water leaving now, and where to: the drywell (no usable well), the refuel floor (the well is full), the pool through the canal,
 *   the drain ports, the cleanup system's blowdown, boiling off, a break;</li>
 *   <li><b>level</b>: the level now against the flange and the top of the well, and how long the net flow takes to each;</li>
 *   <li><b>depth</b>: the water over the top of the fuel and over the flange.</li>
 * </ol>
 */
public final class FloodReadiness {
    private FloodReadiness() {
    }

    public enum Mark { PASS, FAIL, INFO }

    /** One line: what it is about, whether it is met, and the reason. */
    public record Item(String id, Mark mark, Component text) {
        public String english() {
            return BwrText.english(text);
        }
    }

    /** The water's flows into and out of the open vessel now, kg/s, by path. */
    public record Flows(double feedwater, double injection, double spray, double fillPorts, double fromPool,
                        double spill, boolean spillToDrywell, double drains, double toPool, double cleanup, double steam, double breaks) {
        public double in() {
            return feedwater + injection + spray + fillPorts + fromPool;
        }

        public double out() {
            return spill + drains + toPool + cleanup + steam + breaks;
        }
    }

    public static Flows flows(ReactorControllerBlockEntity be) {
        var core = be.core();
        if (core == null) {
            return new Flows(0, 0, 0, 0, 0, 0, false, 0, 0, 0, 0, 0);
        }
        var vessel = core.getPressureVessel();
        double fills = 0;
        for (var f : be.cavityFills()) {
            fills += f.flowKgPerS();
        }
        double drains = 0;
        for (var d : be.cavityDrains()) {
            drains += d.flowKgPerS();
        }
        double fromPool = 0, toPool = 0;
        if (be.getLevel() != null) {
            for (var pool : dev.bwr.mod.spentfuel.SpentFuelPoolRegistry.controllers(be.getLevel())) {
                var ex = pool.canalExchange();
                if (be.getBlockPos().equals(ex.reactorPos())) {
                    double q = ex.flowKgPerS();
                    if (q > 0) {
                        fromPool += q;
                    } else {
                        toPool -= q;
                    }
                }
            }
        }
        boolean open = !be.headSealed();
        double steam = open ? Math.max(0.0, vessel.getSteamGenerationKgPerS()) : 0.0;
        double breaks = Math.max(0.0, vessel.getBoundaryLiquidFlowKgPerS()) + Math.max(0.0, core.getLiquidLeakKgPerS());
        var c = be.cavity();
        return new Flows(core.getDeliveredFeedwaterFlowKgPerS(), core.getInjectionFlowKgPerS(), core.getCoreSprayFlowKgPerS(), fills, fromPool,
                vessel.getWellSpillKgPerS(), c == null || !c.wellUsable(), drains, toPool, Math.max(0.0, core.getCleanupBlowdownKgPerS()), steam, breaks);
    }

    private static String kg(double kgPerS) {
        return String.format(Locale.ROOT, "%,.0f", kgPerS);
    }

    private static String m(double metres) {
        return String.format(Locale.ROOT, "%.2f", metres);
    }

    private static String duration(double seconds) {
        if (!(seconds > 0) || !Double.isFinite(seconds)) {
            return "-";
        }
        if (seconds < 120) {
            return String.format(Locale.ROOT, "%.0f s", seconds);
        }
        if (seconds < 7200) {
            return String.format(Locale.ROOT, "%.0f min", seconds / 60.0);
        }
        return String.format(Locale.ROOT, "%.1f h", seconds / 3600.0);
    }

    /** True when the build lets the well flood: the head off, a cavity seal, a well that reaches the flange. */
    public static boolean canFlood(List<Item> items) {
        for (Item i : items) {
            if (i.mark() == Mark.FAIL && (i.id().equals("head") || i.id().equals("studs") || i.id().equals("seal") || i.id().equals("well"))) {
                return false;
            }
        }
        return !items.isEmpty();
    }

    /** The checklist, for the reactor as it is now. Empty while the reactor is not formed. */
    public static List<Item> of(ReactorControllerBlockEntity be) {
        List<Item> out = new ArrayList<>();
        var core = be.core();
        if (core == null || !be.isFormed() || be.vesselEnvelope() == null) {
            return out;
        }
        var vessel = core.getPressureVessel();
        var env = be.vesselEnvelope();
        var c = be.cavity();
        boolean headOff = be.vesselState() == VesselState.REFUELING;

        // 1. the head
        if (headOff) {
            BlockPos at = be.headLocation();
            out.add(new Item("head", Mark.PASS, at == null
                    ? BwrText.tr("status.bwr.flood.head_off", "Head off the vessel.")
                    : BwrText.tr("status.bwr.flood.head_off_at", "Head off the vessel (at %s).", at.toShortString())));
        } else {
            out.add(new Item("head", Mark.FAIL, BwrText.tr("status.bwr.flood.head_on",
                    "The head is on the vessel: water raised over the flange stays inside the closed vessel and the well cannot flood. Detension the studs, then LIFT HEAD with the building crane.")));
        }
        // 2. the studs
        int total = be.studTotal();
        int tight = be.studsTensioned();
        if (headOff || tight == 0) {
            out.add(new Item("studs", Mark.PASS, BwrText.tr("status.bwr.flood.studs_off", "All %s closure studs detensioned.", total)));
        } else {
            out.add(new Item("studs", Mark.FAIL, BwrText.tr("status.bwr.flood.studs_tight",
                    "%s of %s closure studs are still at load: back them off with the Stud Tensioner (at 25 psig or less) before the head can lift.", tight, total)));
        }
        // 3. the seal
        if (c == null) {
            out.add(new Item("seal", Mark.FAIL, BwrText.tr("status.bwr.flood.not_surveyed", "The cavity seal has not been surveyed yet (a moment after the reactor forms).")));
            return out;
        }
        if (c.present()) {
            out.add(new Item("seal", Mark.PASS, BwrText.tr("status.bwr.flood.seal", "Cavity seal at y %s: the wall is within one block of the vessel all round.", c.sealRow())));
        } else if (c.failure() == CavitySeal.Failure.WALL_TOO_FAR && c.badPos() != null) {
            String far = c.badGap() > 8 ? "more than 8" : String.valueOf(c.badGap());
            out.add(new Item("seal", Mark.FAIL, BwrText.tr("status.bwr.flood.no_seal",
                    "No cavity seal: the wall must touch the vessel or stand one block off all round, at some row from the flange (y %s) down to y %s. Closest is row y %s: on the %s side the wall is %s blocks from the vessel at x %s, z %s (%s columns open). The water would run into the drywell.",
                    c.flangeRow(), c.lowestRow(), c.bestRow(), c.badSide() == null ? "?" : c.badSide().getName(), far, c.badPos().getX(), c.badPos().getZ(), c.badColumns())));
        } else {
            out.add(new Item("seal", Mark.FAIL, c.describe()));
        }
        // 4. the well
        CavitySeal.WellEnd end = c.wellEnd();
        if (!c.present()) {
            out.add(new Item("well", Mark.FAIL, BwrText.tr("status.bwr.flood.well_needs_seal", "No well without a cavity seal.")));
        } else if (!c.wellUsable()) {
            out.add(new Item("well", Mark.FAIL, end != null && end.kind() == CavitySeal.WellEnd.Kind.LEAK && end.at() != null
                    ? BwrText.tr("status.bwr.flood.well_open",
                    "The well does not reach the flange (y %s): at y %s its wall is open, and water at x %s, z %s would spread more than %s blocks from the vessel. Close the wall round the vessel at that height (a full block, or a canal block).",
                    c.flangeRow(), end.row(), end.at().getX(), end.at().getZ(), CavitySeal.WELL_EXTENT)
                    : BwrText.tr("status.bwr.flood.well_short", "The well does not reach the flange (y %s): it ends at y %s.", c.flangeRow(), c.wellTopRow())));
        } else {
            double capacity = c.profile() == null ? 0.0 : c.profile().toWell().capacityM3();
            Component why = end == null ? Component.empty()
                    : end.kind() == CavitySeal.WellEnd.Kind.LEAK && end.at() != null
                    ? BwrText.tr("status.bwr.flood.well_top_leak", " Above it the water would spread out at x %s, z %s (y %s): the refuel floor, or an opening.", end.at().getX(), end.at().getZ(), end.row())
                    : end.kind() == CavitySeal.WellEnd.Kind.CEILING && end.at() != null
                    ? BwrText.tr("status.bwr.flood.well_top_ceiling", " Above it a block over the vessel closes it (at %s): a shield plug or a floor.", end.at().toShortString())
                    : BwrText.tr("status.bwr.flood.well_top_limit", " (the survey counts at most %s layers).", CavitySeal.MAX_WELL_LAYERS);
            out.add(new Item("well", Mark.PASS, Component.empty().append(BwrText.tr("status.bwr.flood.well",
                    "The well holds water from the seal up to y %s: %s m3 over the flange.", c.wellTopRow() + 1,
                    String.format(Locale.ROOT, "%,.0f", capacity))).append(why)));
        }

        // 5. water going in
        Flows f = flows(be);
        if (f.in() > 0.5) {
            List<String> parts = new ArrayList<>();
            if (f.feedwater() > 0.5) parts.add("feedwater " + kg(f.feedwater()));
            if (f.injection() > 0.5) parts.add("injection pumps " + kg(f.injection()));
            if (f.spray() > 0.5) parts.add("core spray " + kg(f.spray()));
            if (f.fillPorts() > 0.5) parts.add("fill ports by gravity " + kg(f.fillPorts()));
            if (f.fromPool() > 0.5) parts.add("from the pool through the canal " + kg(f.fromPool()));
            out.add(new Item("inflow", Mark.PASS, BwrText.tr("status.bwr.flood.inflow", "Water going in: %s kg/s (%s).", kg(f.in()), String.join(", ", parts))));
        } else {
            out.add(new Item("inflow", Mark.FAIL, BwrText.tr("status.bwr.flood.no_inflow",
                    "Nothing is putting water in. Run your feed, condensate or RHR (LPCI) pumps into the vessel, or pipe a Cavity Fill Port in the well wall from a pump or from a higher Condensate Storage Tank through a Motor-Operated Water Valve.")));
        }
        for (var fill : be.cavityFills()) {
            if (fill.state() != CavityFillPortBlockEntity.State.FILLING && fill.state() != CavityFillPortBlockEntity.State.PUMP_LINE) {
                BlockPos p = fill.getBlockPos();
                out.add(new Item("fill_port", Mark.INFO, BwrText.tr("status.bwr.flood.fill_port", "Fill port at %s: %s", p.toShortString(), fill.state().text())));
            }
        }

        // 6. water going out
        List<String> leaving = new ArrayList<>();
        boolean lost = false;
        if (f.spill() > 0.5) {
            lost = true;
            leaving.add(f.spillToDrywell() ? "into the drywell " + kg(f.spill()) + " (no usable well)" : "over the top of the well onto the refuel floor " + kg(f.spill()) + " (the well is full)");
        }
        if (f.toPool() > 0.5) leaving.add("to the pool through the canal " + kg(f.toPool()));
        if (f.drains() > 0.5) leaving.add("cavity drain ports " + kg(f.drains()));
        if (f.cleanup() > 0.5) leaving.add("cleanup (RWCU) blowdown " + kg(f.cleanup()));
        if (f.steam() > 0.5) leaving.add("boiling off " + kg(f.steam()));
        if (f.breaks() > 0.5) {
            lost = true;
            leaving.add("through a break " + kg(f.breaks()));
        }
        if (leaving.isEmpty()) {
            out.add(new Item("outflow", Mark.PASS, BwrText.tr("status.bwr.flood.no_outflow", "No water is leaving.")));
        } else {
            out.add(new Item("outflow", lost ? Mark.FAIL : Mark.INFO, BwrText.tr("status.bwr.flood.outflow", "Water leaving: %s kg/s (%s).", kg(f.out()), String.join(", ", leaving))));
        }

        // 7. the level
        double level = vessel.getCollapsedLevelIn();
        double overFlangeM = (level - PressureVessel.FLANGE_LEVEL_IN) * PressureVessel.METRES_PER_INCH;
        double net = f.in() - f.out();
        double rho = Saturation.liquidDensityKgPerM3(vessel.getPressurePsia());
        double toFlangeKg = Math.max(0.0, vessel.volumeAtFlangeM3() * rho - vessel.getLiquidMassKg());
        double capacity = c.profile() == null ? 0.0 : c.profile().toWell().capacityM3();
        double toTopKg = Math.max(0.0, (vessel.volumeAtFlangeM3() + capacity) * rho - vessel.getLiquidMassKg());
        double wellTop = c.wellUsable() ? c.wellTopRow() + 1 - env.min().getY() - c.profile().lip() : 0.0;
        if (vessel.getWellWaterM3() <= 0 && overFlangeM > -0.005 && f.spill() > 0.5) {
            out.add(new Item("level", Mark.FAIL, BwrText.tr("status.bwr.flood.at_flange",
                    "Level at the flange, and no higher: the water over it leaves as fast as it comes (see the water leaving).")));
        } else if (overFlangeM < -0.0005 || vessel.getWellWaterM3() <= 0) {
            String eta = net > 0.5 ? BwrText.english(BwrText.tr("status.bwr.flood.eta", " At %s kg/s net it reaches the flange in %s.", kg(net), duration(toFlangeKg / net))) : "";
            out.add(new Item("level", Mark.FAIL, BwrText.tr("status.bwr.flood.below_flange",
                    "Level %s m below the flange (%s in on the narrow range; the flange is +%s in). The dryer and separators come out only with the vessel flooded to its flange.%s",
                    m(-Math.min(0.0, overFlangeM)), Math.round(level), Math.round(PressureVessel.FLANGE_LEVEL_IN), eta)));
        } else {
            String eta = net > 0.5 && toTopKg > 0 ? BwrText.english(BwrText.tr("status.bwr.flood.eta_top", " At %s kg/s net it is full in %s.", kg(net), duration(toTopKg / net))) : "";
            out.add(new Item("level", Mark.PASS, BwrText.tr("status.bwr.flood.over_flange",
                    "Flooded: %s m over the flange, %s m3 in the well and its annulus; the well's top is %s m over the flange.%s",
                    m(Math.max(0.0, overFlangeM)), String.format(Locale.ROOT, "%,.0f", vessel.getWellWaterM3()), m(wellTop), eta)));
        }
        // 8. the depth over the fuel
        double overFuel = (level - PhysicalConstants.TAF_ON_INSTRUMENT_SCALE_IN) * PressureVessel.METRES_PER_INCH;
        out.add(new Item("depth", Mark.INFO, BwrText.tr("status.bwr.flood.depth",
                "%s m of water over the top of the fuel, %s m over the flange.", m(overFuel), m(Math.max(0.0, overFlangeM)))));
        return out;
    }
}
