package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.CoreMapSnapshot;
import dev.bwr.mod.gui.ReactorPanelMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import dev.bwr.mod.reactor.VesselState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The control room. The core map with two overlays on the left, the plant
 * readouts in the middle, and the actuators down the right.
 *
 * <h2>The two overlays</h2>
 * <b>Flux</b> paints each bundle by its share of core power, which is the
 * output of the nodal diffusion solve and therefore shows rod shadowing, the
 * burnup gradient and the hot spot around a stuck rod. <b>Rods</b> paints the
 * rod lattice by insertion, 00 fully in to 48 fully out. They are the same
 * widget on different lattices. <b>Info</b> replaces the map with the planning
 * estimates and the core's condition.
 *
 * <h2>The buttons</h2>
 * Scram fires immediately and unconditionally; it is a switch that de-energises
 * the drives, not a request that gets evaluated. Rod motion commands a notch
 * demand and the drives index toward it at their own speed, on their own
 * accumulators, if they have power and water. The head buttons ask the vessel
 * to change state and print the physical reason when it cannot. Nothing on this
 * screen watches a number and acts on it.
 */
public class ReactorPanelScreen extends BwrScreen<ReactorPanelMenu> {

    private enum Overlay {
        FLUX, RODS, INFO
    }

    private static final int READOUT_COLUMN = 128;
    private static final int ACTION_COLUMN = 64;

    private Overlay overlay = Overlay.FLUX;

    private LatticeGridWidget grid;
    private BwrButton scram;
    private BwrButton resetScram;
    private BwrButton head;
    private BwrButton refuel;
    private BwrButton rodIn;
    private BwrButton rodOut;
    private BwrButton allIn;
    private BwrButton allOut;

    /** Rod the player last clicked on the rod overlay, or -1 for "all rods". */
    private int selectedRod = -1;

    public ReactorPanelScreen(ReactorPanelMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 384, 224);
    }

    // ---------------------------------------------------------------
    // Widgets and layout
    // ---------------------------------------------------------------

    @Override
    protected UiElement buildUi() {
        if (grid == null) {
            grid = new LatticeGridWidget(0, 0, 100, 84, this::onCellClicked);
            scram = BwrButton.danger("SCRAM", "Scram: de-energise the drives and insert every rod. It acts at once; nothing is checked first.",
                    b -> menu.sendCommand(ReactorPanelMenu.CMD_SCRAM));
            resetScram = BwrButton.secondary("RESET", "Reset the scram signal so the drives can be driven out again.",
                    b -> menu.sendCommand(ReactorPanelMenu.CMD_RESET_SCRAM));
            head = BwrButton.secondary("HEAD OFF", "Ask the vessel to take its head off or put it back. It refuses, and says why, if that is physically impossible.",
                    b -> toggleHead());
            refuel = BwrButton.secondary("REFUEL", "Open the refuelling floor. Only possible with the head off.",
                    b -> menu.sendCommand(ReactorPanelMenu.CMD_OPEN_REFUELLING));
            rodIn = BwrButton.secondary("ROD IN", "Step the selected rod in by one notch, or every rod if none is selected.",
                    b -> menu.sendCommand(ReactorPanelMenu.CMD_ROD_STEP, selectedRod, -1));
            rodOut = BwrButton.secondary("ROD OUT", "Step the selected rod out by one notch, or every rod if none is selected.",
                    b -> menu.sendCommand(ReactorPanelMenu.CMD_ROD_STEP, selectedRod, 1));
            allIn = BwrButton.secondary("ALL IN 00", "Demand every rod fully inserted (notch 00).",
                    b -> menu.sendCommand(ReactorPanelMenu.CMD_ROD_NOTCH, -1, 0));
            allOut = BwrButton.secondary("ALL OUT 48", "Demand every rod fully withdrawn (notch 48).",
                    b -> menu.sendCommand(ReactorPanelMenu.CMD_ROD_NOTCH, -1, 48));
        }

        UiElement[] unformed = new UiElement[12];
        for (int i = 0; i < unformed.length; i++) {
            final int index = i;
            unformed[i] = Layout.note(() -> index < menu.statusLines.size() ? menu.statusLines.get(index).getString() : "")
                    .body().showIf(() -> index < menu.statusLines.size());
        }
        var notFormed = Layout.card("Reactor",
                Layout.note(() -> Component.translatable("status.bwr.reactor_controller.reactor_not_formed").getString())
                        .body().status(() -> Status.ALARM));
        var notFormedLines = Layout.column(BwrStyle.SP1, unformed);

        var flux = Layout.split(BwrStyle.SP1);
        flux.add(mapColumn(), 1);
        flux.fixed(readouts(), READOUT_COLUMN);

        var main = Layout.column(BwrStyle.SP2,
                Layout.column(BwrStyle.SP2, notFormed, notFormedLines).showIf(() -> !menu.formed),
                flux.showIf(() -> menu.formed && overlay != Overlay.INFO),
                info().showIf(() -> menu.formed && overlay == Overlay.INFO));

        var actions = Layout.column(BwrStyle.SP1,
                Layout.controls(0).add(scram, 1),
                Layout.controls(0).add(resetScram, 1),
                Layout.controls(0).add(head, 1),
                Layout.controls(0).add(refuel, 1),
                Layout.controls(0).add(rodIn, 1),
                Layout.controls(0).add(rodOut, 1),
                Layout.controls(0).add(allIn, 1),
                Layout.controls(0).add(allOut, 1));

        var root = Layout.split(BwrStyle.SP1);
        root.add(main, 1);
        root.fixed(actions, ACTION_COLUMN);
        return root;
    }

    @Override
    protected UiElement buildTitleItems() {
        return Layout.titleItems(Layout.tabs(new String[]{"FLUX", "RODS", "INFO"},
                new String[]{"Colour each fuel bundle by its share of core power.",
                        "Show every control rod by its insertion, 00 fully in to 48 fully out. Click a rod to select it for ROD IN and ROD OUT.",
                        "Planning estimates, recirculation sizing and the condition of the core."},
                () -> overlay.ordinal(), i -> setOverlay(Overlay.values()[i])));
    }

    private UiElement mapColumn() {
        return Layout.column(BwrStyle.SP1,
                Layout.fill(grid, 60),
                Layout.legend(() -> overlay == Overlay.FLUX ? "0 %" : "00 in",
                        () -> overlay == Overlay.FLUX ? "100 %" : "48 out",
                        f -> overlay == Overlay.FLUX ? heatColour(f) : LatticeColours.rod(f))
                        .tip(() -> List.of(Component.literal(overlay == Overlay.FLUX
                                ? "Each bundle's power as a share of the hottest bundle's, 0 to 100 %."
                                : "Dark: rod fully inserted (00). Bright green: fully withdrawn (48). Amber: moving to a new demand."))),
                Layout.line(() -> selectedRod < 0 ? "ROD IN / OUT: all rods" : "ROD IN / OUT: rod " + (selectedRod + 1),
                        Type.CAPTION).showIf(() -> overlay == Overlay.RODS));
    }

    private UiElement readouts() {
        return Layout.column(0,
                Layout.statusLine(() -> menu.vesselState == VesselState.REFUELING ? Status.INFO : Status.NONE,
                        () -> capitalised(menu.vesselState.getSerializedName()), () -> menu.scramActive ? "SCRAM" : "")
                        .rightStatus(() -> menu.scramActive ? Status.ALARM : Status.NONE)
                        .tip(() -> List.of(Component.literal("Vessel state: " + menu.vesselState.getSerializedName()),
                                Component.literal(menu.scramActive ? "A scram is active: the drives are de-energised." : "No scram active."))),
                Layout.gap(BwrStyle.SP1),
                // power
                Layout.row("Power", () -> pct(menu.powerFractionOfRated), "%").dense()
                        .status(() -> fractionStatus(menu.powerFractionOfRated, 1.05, 1.20))
                        .tip("Total power as a fraction of rated. Amber from 105 %, red from 120 %: colour and shape only, nothing trips."),
                Layout.row("Thermal", () -> big(menu.thermalMW), "MWth").dense(),
                Layout.gap(BwrStyle.SP1),
                // pressure and level
                Layout.row("Pressure", () -> big(menu.pressurePsig), "psig").dense().status(() -> pressureStatus(menu.pressurePsig))
                        .tip("Dome pressure. Amber from the 1,250 psig design limit, red from the 1,375 psig code limit: colour and shape only."),
                Layout.row("dP/dt", () -> num(menu.pressureRatePsiPerS, 1), "psi/s").dense().tip("Rate of change of dome pressure."),
                Layout.row("Level", () -> num(menu.indicatedLevelIn, 1), "in").dense()
                        .status(() -> menu.uncoveredFuelFraction > 0.0 ? Status.ALARM : Status.NONE)
                        .tip("Indicated water level. Red while any fuel is uncovered."),
                Layout.row("Flow", () -> pct(menu.coreFlowFraction), "%").dense().tip("Core flow as a fraction of rated."),
                Layout.row("Steam", () -> big(menu.steamKgPerS), "kg/s").dense().tip("Steam generation."),
                Layout.gap(BwrStyle.SP1),
                // neutronics: the instrument ladder. The count rate is what reads anything at all on a shutdown core, and the
                // two period indications are what the player cross-checks. Neither is coloured: deciding which channel to
                // believe is the job this panel exists to hand them.
                Layout.row("Reactivity", () -> num(menu.reactivityDollars, 2), "$").dense()
                        .status(() -> menu.reactivityDollars >= 1.0 ? Status.ALARM : Status.NONE)
                        .tip("Reactivity in dollars. Red at or above 1.00: prompt critical."),
                Layout.row("SRM rate", () -> sci(menu.srmCountsPerSecond), "cps").dense().tip("Source range monitor count rate."),
                Layout.row("SRM period", () -> periodValue(menu.srmPeriodSeconds), () -> periodUnit(menu.srmPeriodSeconds)).dense()
                        .tip("Source range monitor period meter. A saturated channel can read a negative period on a climbing core."),
                Layout.row("IRM period", () -> periodValue(menu.irmPeriodSeconds), () -> periodUnit(menu.irmPeriodSeconds)).dense()
                        .tip("Intermediate range monitor period meter. Cross-check it against the source range channel."),
                Layout.gap(BwrStyle.SP1),
                // fuel
                Layout.row("Fuel temp", () -> big(menu.fuelTemperatureC), "C").dense(),
                Layout.row("Peak clad", () -> big(menu.peakCladTemperatureC), "C").dense().status(() -> cladStatus(menu.peakCladTemperatureC))
                        .tip("Peak cladding temperature. Amber from 1,000 C; red from 1,200 C, the zirconium-water reaction onset."),
                Layout.row("Void", () -> pct(menu.voidFraction), "%").dense().tip("Core void fraction."),
                Layout.row("Accum.", () -> menu.chargedAccumulators + "/" + menu.rodCount).dense()
                        .status(() -> menu.chargedAccumulators < menu.rodCount ? Status.CAUTION : Status.NORMAL)
                        .tip("Control rod drive accumulators charged, out of all rods. Caution while any is not."));
    }

    /** The planning estimates and the core's condition: what used to be spread over hover regions, as rows with tips. */
    private UiElement info() {
        var c = (java.util.function.Supplier<dev.bwr.mod.gui.ReactorConfigurationInfo>) () -> menu.configuration;
        List<Component> planning = List.of(
                Component.literal("Fuel rating = reference MW x loaded-slot fraction."),
                Component.literal("Power estimate = reference MW x min(fuel fraction, flow ceiling)."),
                Component.literal("Steam equivalent uses current pressure and feedwater temperature."),
                Component.literal("These are planning estimates, not operating limits."),
                Component.literal("Actual output depends on rods, fuel condition and water supply."));
        var content = Layout.column(BwrStyle.SP1,
                Layout.section("Fuel and configuration"),
                Layout.row("Fuel bundles", () -> menu.loadedAssemblies + " / " + menu.assemblyCount)
                        .tip(this::fuelTypeTooltip),
                Layout.row("Fuel-loaded rating*", () -> big(c.get().fuelLoadedRatingMW()), "MWth").tip(() -> planning),
                Layout.row("Flow-supported power*", () -> big(c.get().flowSupportedMW()), "MWth").tip(() -> planning),
                Layout.row("Steam equivalent*", () -> big(c.get().steamEquivalentKgPerS()), "kg/s").tip(() -> planning),
                Layout.row("k-inf", () -> num(menu.aggregateKInf, 4)),
                Layout.row("Decay heat", () -> pct(menu.decayHeatFraction), "%"),
                Layout.section("Recirculation"),
                Layout.row("Jets matched / needed", () -> c.get().matchedJetAssemblies() + " / " + c.get().requiredJets()).tip(this::jetTooltip),
                Layout.row("RCP / RIP installed", () -> c.get().externalPumps() + " / " + c.get().internalPumps()).tip(this::jetTooltip),
                Layout.row("Flow ceiling", () -> pct(c.get().flowCeilingFraction()), "%").tip(this::jetTooltip),
                Layout.row("Flow ceiling mass flow", () -> big(c.get().flowCeilingKgPerS()), "kg/s").tip(this::jetTooltip),
                Layout.row("Vessel interior volume", () -> String.valueOf(c.get().interiorVolume()), "blocks^3").tip(this::volumeTooltip),
                Layout.section("Drives and pumps"),
                Layout.row("Drives powered", () -> String.valueOf(menu.poweredDrives)),
                Layout.row("Drives with water", () -> String.valueOf(menu.waterSuppliedDrives)),
                Layout.row("Pumps", () -> String.valueOf(menu.pumpCount)),
                Layout.section("Core condition"),
                Layout.row("Average burnup", () -> big(menu.averageBurnup), "MWd/t"),
                Layout.row("Effective beta", () -> num(menu.betaEffective, 5)),
                Layout.row("Xenon", () -> pct(menu.xenonFraction), "%"),
                Layout.row("APRM", () -> num(menu.aprmPercent, 1), "%"),
                Layout.row("Oxidation", () -> pct(menu.oxidationFraction), "%")
                        .status(() -> menu.oxidationFraction > 0 ? Status.CAUTION : Status.NONE),
                Layout.row("Hydrogen", () -> num(menu.hydrogenKg, 1), "kg"),
                Layout.row("Uncovered fuel", () -> pct(menu.uncoveredFuelFraction), "%")
                        .status(() -> menu.uncoveredFuelFraction > 0 ? Status.CAUTION : Status.NONE),
                Layout.row("Spray ring complete", () -> pct(menu.sprayRingCompleteness), "%"),
                Layout.section("Live output"),
                Layout.row("MWth / steam kg/s", () -> big(menu.thermalMW) + " / " + big(menu.steamKgPerS)),
                Layout.note("* Planning estimates; hover a row for its basis."));
        return Layout.card("", Layout.scrollArea(60, content));
    }

    private List<Component> fuelTypeTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Loaded fuel; FLUX shows each bundle"));
        if (menu.map != null) {
            var counts = new java.util.LinkedHashMap<String, Integer>();
            for (int i = 0; i < menu.map.coreSlotCount; i++) {
                if (menu.map.isOccupied(i)) {
                    counts.merge(menu.map.fuelTypeName(i), 1, Integer::sum);
                }
            }
            counts.forEach((name, count) -> lines.add(Component.literal(count + " x " + name)));
        }
        return lines;
    }

    private List<Component> jetTooltip() {
        var c = menu.configuration;
        return List.of(
                Component.literal("Jets must have a matching assembly across the vessel."),
                Component.literal("Place bases 1 or 2 blocks above the bottom shell; face oppositely."),
                Component.literal("Each RCP supports up to " + dev.bwr.core.flow.RecirculationSizing.JETS_PER_EXTERNAL_PUMP + " normal placed jet assemblies."),
                Component.literal("Targets assume normal jets and full-speed external pumps."),
                Component.literal("Full-flow estimate: " + c.requiredExternalPumps() + " RCPs with jets, or "
                        + (int) Math.ceil(c.requiredJets() / dev.bwr.core.flow.RecirculationSizing.INTERNAL_PUMP_UNITS) + " RIPs."),
                Component.literal("RIPs need valid floor mounts and FE; hold a RIP to show mounting sites."),
                Component.literal("Unmatched jets: " + c.unmatchedJetAssemblies() + "; internal pumps: " + c.internalPumps()),
                Component.literal("Required core flow: " + big(c.requiredFlowKgPerS()) + " kg/s"),
                Component.literal("Actual core flow: " + big(menu.coreFlowKgPerS) + " kg/s"));
    }

    private List<Component> volumeTooltip() {
        var c = menu.configuration;
        return List.of(
                Component.literal("Whole interior: width x height x depth; shell excluded."),
                Component.literal("12 jets x cube root(volume / 200), rounded up to pairs."),
                Component.literal("Geometric sizing; lowering water level does not reduce the target."),
                Component.literal("Water: " + big(menu.coolantTemperatureC) + " C; inlet subcooling: " + big(c.inletSubcoolingKJPerKg()) + " kJ/kg"));
    }

    private void setOverlay(Overlay next) {
        overlay = next;
        grid.setSelected(-1);
        selectedRod = -1;
    }

    private void onCellClicked(int cell) {
        selectedRod = overlay == Overlay.RODS ? cell : -1;
    }

    private void toggleHead() {
        VesselState next = menu.vesselState == VesselState.REFUELING
                ? VesselState.SHUTDOWN
                : VesselState.REFUELING;
        menu.sendCommand(ReactorPanelMenu.CMD_SET_VESSEL_STATE, next.ordinal());
    }

    // ---------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        head.setMessage(Component.literal(menu.vesselState == VesselState.REFUELING ? "HEAD ON" : "HEAD OFF"));
        head.setTooltipText(menu.vesselState == VesselState.REFUELING
                ? "Ask the vessel to put its head back on. It refuses, and says why, if that is physically impossible."
                : "Ask the vessel to take its head off. It refuses, and says why, if pressure or anything else makes that impossible.");
        grid.setCells(overlay == Overlay.FLUX ? fluxCells() : rodCells());
    }

    @Override
    protected List<Component> hoverTooltip() {
        return menu.formed && overlay != Overlay.INFO ? grid.hoverTooltip() : List.of();
    }

    private static String capitalised(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String periodValue(double seconds) {
        // Steady flux is a legitimately infinite period, so "inf" is a reading and not an error.
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || Math.abs(seconds) > 9999.0) {
            return "inf";
        }
        return num(seconds, 1);
    }

    private static String periodUnit(double seconds) {
        return periodValue(seconds).equals("inf") ? "" : "s";
    }

    // -----------------------------------------------------------------
    // Overlays
    // -----------------------------------------------------------------

    private LatticeGridWidget.Cells fluxCells() {
        CoreMapSnapshot map = menu.map;
        if (map == null) {
            return null;
        }
        return new LatticeGridWidget.Cells() {
            @Override
            public int count() {
                return map.coreSlotCount;
            }

            @Override
            public int latticeWidth() {
                return map.latticeWidth;
            }

            @Override
            public int latticeIndex(int cell) {
                return map.latticeIndex[cell];
            }

            @Override
            public int colour(int cell) {
                if (map.isInsert(cell)) {
                    return LatticeColours.INSERT;
                }
                if (!map.isOccupied(cell)) {
                    return LatticeColours.EMPTY;
                }
                return heatColour(map.relativeFlux[cell]);
            }

            @Override
            public List<Component> tooltip(int cell) {
                List<Component> out = new ArrayList<>();
                if (map.isInsert(cell)) {
                    return map.insertTooltip(cell);
                }
                if (!map.isOccupied(cell)) {
                    out.add(Component.literal("Position " + cell + ": empty"));
                    return out;
                }
                out.add(Component.literal(String.format(Locale.ROOT,
                        "Position %d - %s", cell, map.fuelTypeName(cell))));
                out.add(Component.literal(String.format(Locale.ROOT,
                        "%.2f MW  (%.0f%% of the hottest bundle)",
                        map.assemblyThermalMW(cell), map.relativeFlux[cell] * 100.0)));
                out.add(Component.literal(String.format(Locale.ROOT,
                        "burnup %,.0f MWd/t   k-inf %.4f",
                        map.burnupMwdPerTonne[cell], map.kInf[cell])));
                return out;
            }
        };
    }

    /** Sparse physical CRD positions supplied by the server, with stable blade IDs. */
    private LatticeGridWidget.Cells rodCells() {
        // Capture the arrays, not the menu fields. readSnapshot reallocates
        // both on every snapshot, this closure is rebuilt only once per client
        // tick, and LatticeGridWidget iterates count() every frame — so reading
        // menu.rodNotchLabels[cell] live meant that a vessel rebuilt smaller by
        // another player threw ArrayIndexOutOfBoundsException out of the render
        // loop, where nothing catches it, and took the client down. The flux
        // overlay captures its CoreMapSnapshot for the same reason.
        final int[] notch = menu.rodNotchLabels;
        final int[] demand = menu.rodDemandLabels;
        final int count = Math.min(notch.length, demand.length);
        if (count == 0) {
            return null;
        }

        final int columns = Math.max(1, menu.rodsPerX);
        final int[] indices = menu.rodLatticeIndices;
        if (indices.length != count) {
            return null;
        }

        return new LatticeGridWidget.Cells() {
            @Override
            public int count() {
                return count;
            }

            @Override
            public int latticeWidth() {
                return columns;
            }

            @Override
            public int latticeIndex(int cell) {
                // Explicit floor position: compact layouts have empty corner cells.
                return indices[cell];
            }

            @Override
            public int colour(int cell) {
                boolean moving = demand[cell] != notch[cell];
                return moving ? LatticeColours.MOVING : LatticeColours.rod(notch[cell] / 48.0);
            }

            @Override
            public List<Component> tooltip(int cell) {
                return List.of(
                        Component.literal(String.format(Locale.ROOT, "Rod %d at notch %02d",
                                cell + 1, notch[cell])),
                        Component.literal(String.format(Locale.ROOT,
                                "demanded %02d   (00 fully inserted, 48 fully withdrawn)",
                                demand[cell])));
            }
        };
    }
}
