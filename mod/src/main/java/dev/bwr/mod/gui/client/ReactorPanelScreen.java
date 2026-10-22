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
            scram = BwrButton.danger("Scram", "Scram the reactor: this cuts power to the drives and inserts every rod at once. Nothing is checked first.",
                    b -> menu.sendCommand(ReactorPanelMenu.CMD_SCRAM));
            resetScram = BwrButton.secondary("Reset", "Reset the scram signal, so the rods can be driven out again.",
                    b -> menu.sendCommand(ReactorPanelMenu.CMD_RESET_SCRAM));
            head = BwrButton.secondary("Lift head", "Ask the vessel to take its head off. It refuses, and tells you why, if that is physically impossible.",
                    b -> toggleHead());
            refuel = BwrButton.secondary("Refuel", "Open the refuelling floor. This only works with the vessel head off.",
                    b -> menu.sendCommand(ReactorPanelMenu.CMD_OPEN_REFUELLING));
            rodIn = BwrButton.secondary("Insert rod", "Push the selected rod in by one notch, or every rod if none is selected.",
                    b -> menu.sendCommand(ReactorPanelMenu.CMD_ROD_STEP, selectedRod, -1));
            rodOut = BwrButton.secondary("Pull rod", "Pull the selected rod out by one notch, or every rod if none is selected.",
                    b -> menu.sendCommand(ReactorPanelMenu.CMD_ROD_STEP, selectedRod, 1));
            allIn = BwrButton.secondary("All in (00)", "Tell every rod to go all the way in (notch 00).",
                    b -> menu.sendCommand(ReactorPanelMenu.CMD_ROD_NOTCH, -1, 0));
            allOut = BwrButton.secondary("All out (48)", "Tell every rod to go all the way out (notch 48).",
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
                new String[]{"Colour each fuel bundle by its share of the core's power.",
                        "Show every control rod by how far in it is, from 00 fully in to 48 fully out. Click a rod to select it for the Insert rod and Pull rod buttons.",
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
                                ? "Each bundle's power as a share of the hottest bundle's, from 0 to 100%."
                                : "Dark means the rod is fully in (00). Bright green means it is fully out (48). Amber means it is moving to a new position."))),
                Layout.line(() -> selectedRod < 0 ? "The rod buttons move all rods" : "The rod buttons move rod " + (selectedRod + 1),
                        Type.CAPTION).showIf(() -> overlay == Overlay.RODS));
    }

    private UiElement readouts() {
        return Layout.column(0,
                Layout.statusLine(() -> menu.vesselState == VesselState.REFUELING ? Status.INFO : Status.NONE,
                        () -> capitalised(menu.vesselState.getSerializedName()), () -> menu.scramActive ? "Scram active" : "")
                        .rightStatus(() -> menu.scramActive ? Status.ALARM : Status.NONE)
                        .tip(() -> List.of(Component.literal("The vessel is in the " + menu.vesselState.getSerializedName() + " state."),
                                Component.literal(menu.scramActive ? "A scram is active, so the drives have no power." : "There is no scram active."))),
                Layout.gap(BwrStyle.SP1),
                // power
                Layout.row("Power", () -> pct(menu.powerFractionOfRated), "%").dense()
                        .status(() -> fractionStatus(menu.powerFractionOfRated, 1.05, 1.20))
                        .tip("The reactor's total power as a share of its rated power. It turns amber from 105% and red from 120%. That is only a colour and a shape; nothing trips."),
                Layout.row("Heat output", () -> big(menu.thermalMW), "MWth").dense()
                        .tip("The heat the core is producing, in megawatts thermal."),
                Layout.gap(BwrStyle.SP1),
                // pressure and level
                Layout.row("Pressure", () -> big(menu.pressurePsig), "psig").dense().status(() -> pressureStatus(menu.pressurePsig))
                        .tip("The pressure in the steam dome. It turns amber from the 1,250 psig design limit and red from the 1,375 psig code limit. That is only a colour and a shape."),
                Layout.row("dP/dt", () -> num(menu.pressureRatePsiPerS, 1), "psi/s").dense().tip("How fast the dome pressure is rising or falling, in psi per second."),
                Layout.row("Level", () -> num(menu.indicatedLevelIn, 1), "in").dense()
                        .status(() -> menu.uncoveredFuelFraction > 0.0 ? Status.ALARM : Status.NONE)
                        .tip("The water level the instruments show. It is red while any fuel is uncovered."),
                Layout.row("Core flow", () -> pct(menu.coreFlowFraction), "%").dense().tip("The flow of water through the core as a share of its rated flow."),
                Layout.row("Steam flow", () -> big(menu.steamKgPerS), "kg/s").dense().tip("How much steam the reactor is making."),
                Layout.gap(BwrStyle.SP1),
                // neutronics: the instrument ladder. The count rate is what reads anything at all on a shutdown core, and the
                // two period indications are what the player cross-checks. Neither is coloured: deciding which channel to
                // believe is the job this panel exists to hand them.
                Layout.row("Reactivity", () -> num(menu.reactivityDollars, 2), "$").dense()
                        .status(() -> menu.reactivityDollars >= 1.0 ? Status.ALARM : Status.NONE)
                        .tip("The reactivity in dollars. It is red at or above 1.00, which is prompt critical."),
                Layout.row("SRM rate", () -> sci(menu.srmCountsPerSecond), "cps").dense().tip("The count rate on the source range monitor (SRM), in counts per second."),
                Layout.row("SRM period", () -> periodValue(menu.srmPeriodSeconds), () -> periodUnit(menu.srmPeriodSeconds)).dense()
                        .tip("The period on the source range monitor (SRM). A saturated channel can read a negative period while the core is climbing."),
                Layout.row("IRM period", () -> periodValue(menu.irmPeriodSeconds), () -> periodUnit(menu.irmPeriodSeconds)).dense()
                        .tip("The period on the intermediate range monitor (IRM). Check it against the source range channel."),
                Layout.gap(BwrStyle.SP1),
                // fuel
                Layout.row("Fuel temp", () -> big(menu.fuelTemperatureC), "C").dense().tip("The average temperature of the fuel."),
                Layout.row("Peak cladding", () -> big(menu.peakCladTemperatureC), "C").dense().status(() -> cladStatus(menu.peakCladTemperatureC))
                        .tip("The hottest fuel cladding temperature. It turns amber from 1,000 C and red from 1,200 C, where the zirconium-water reaction starts."),
                Layout.row("Void", () -> pct(menu.voidFraction), "%").dense().tip("The share of the core's volume that is steam rather than water."),
                Layout.row("Accum.", () -> menu.chargedAccumulators + "/" + menu.rodCount).dense()
                        .status(() -> menu.chargedAccumulators < menu.rodCount ? Status.CAUTION : Status.NORMAL)
                        .tip("How many control rod drive accumulators are charged, out of all the rods. It shows a caution while any is not."));
    }

    /** The planning estimates and the core's condition: what used to be spread over hover regions, as rows with tips. */
    private UiElement info() {
        var c = (java.util.function.Supplier<dev.bwr.mod.gui.ReactorConfigurationInfo>) () -> menu.configuration;
        List<Component> planning = List.of(
                Component.literal("Fuel rating is the reference power times the share of fuel slots that are loaded."),
                Component.literal("Power estimate is the reference power times the smaller of the fuel share and the flow ceiling."),
                Component.literal("The steam equivalent uses the current pressure and feedwater temperature."),
                Component.literal("These are planning estimates, not operating limits."),
                Component.literal("What the reactor really makes depends on the rods, the fuel condition and the water supply."));
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
                Layout.row("External / internal pumps installed", () -> c.get().externalPumps() + " / " + c.get().internalPumps()).tip(this::jetTooltip),
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
                Layout.row("APRM average power", () -> num(menu.aprmPercent, 1), "%"),
                Layout.row("Oxidation", () -> pct(menu.oxidationFraction), "%")
                        .status(() -> menu.oxidationFraction > 0 ? Status.CAUTION : Status.NONE),
                Layout.row("Hydrogen", () -> num(menu.hydrogenKg, 1), "kg"),
                Layout.row("Uncovered fuel", () -> pct(menu.uncoveredFuelFraction), "%")
                        .status(() -> menu.uncoveredFuelFraction > 0 ? Status.CAUTION : Status.NONE),
                Layout.row("Spray ring complete", () -> pct(menu.sprayRingCompleteness), "%"),
                Layout.section("Live output"),
                Layout.row("Heat output / steam flow", () -> big(menu.thermalMW) + " / " + big(menu.steamKgPerS)),
                Layout.note("* These are planning estimates. Hover over a row to see how it is worked out."));
        return Layout.card("", Layout.scrollArea(60, content));
    }

    private List<Component> fuelTypeTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("The fuel loaded; the Flux tab shows each bundle."));
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
                Component.literal("Each jet needs a matching jet assembly on the opposite side of the vessel."),
                Component.literal("Place the bases 1 or 2 blocks above the bottom shell, facing each other."),
                Component.literal("Each external recirculation pump (RCP) supports up to " + dev.bwr.core.flow.RecirculationSizing.JETS_PER_EXTERNAL_PUMP + " normal jet assemblies."),
                Component.literal("The targets assume normal jets and external pumps at full speed."),
                Component.literal("For full flow you need about " + c.requiredExternalPumps() + " external pumps with jets, or "
                        + (int) Math.ceil(c.requiredJets() / dev.bwr.core.flow.RecirculationSizing.INTERNAL_PUMP_UNITS) + " internal pumps (RIPs)."),
                Component.literal("Internal pumps need valid floor mounts and FE; hold one to see where it can be mounted."),
                Component.literal("Jets without a match: " + c.unmatchedJetAssemblies() + ". Internal pumps: " + c.internalPumps() + "."),
                Component.literal("Core flow needed: " + big(c.requiredFlowKgPerS()) + " kg/s"),
                Component.literal("Core flow now: " + big(menu.coreFlowKgPerS) + " kg/s"));
    }

    private List<Component> volumeTooltip() {
        var c = menu.configuration;
        return List.of(
                Component.literal("This is the whole inside of the vessel (width times height times depth), not counting the shell."),
                Component.literal("The jets needed are 12 times the cube root of (volume divided by 200), rounded up to pairs."),
                Component.literal("It is sized by geometry, so lowering the water level does not lower the target."),
                Component.literal("Water: " + big(menu.coolantTemperatureC) + " C. Inlet subcooling: " + big(c.inletSubcoolingKJPerKg()) + " kJ/kg."));
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
        head.setMessage(Component.literal(menu.vesselState == VesselState.REFUELING ? "Seat head" : "Lift head"));
        head.setTooltipText(menu.vesselState == VesselState.REFUELING
                ? "The head is off. Ask the vessel to put its head back on. It refuses, and tells you why, if that is physically impossible."
                : "The head is on. Ask the vessel to take its head off. It refuses, and tells you why, if pressure or anything else makes that impossible.");
        grid.setCells(overlay == Overlay.FLUX ? fluxCells() : rodCells());
    }

    @Override
    protected boolean batchedDrawing() {
        return true;
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
        // The snapshot never changes once it is read, and these cells are rebuilt every client tick, so each cell's
        // colour is worked out once here rather than on every frame.
        final int[] colours = new int[map.coreSlotCount];
        for (int cell = 0; cell < colours.length; cell++) {
            colours[cell] = map.isInsert(cell) ? LatticeColours.INSERT
                    : !map.isOccupied(cell) ? LatticeColours.EMPTY : heatColour(map.relativeFlux[cell]);
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
                return colours[cell];
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

        final int[] colours = new int[count];
        for (int cell = 0; cell < count; cell++) {
            colours[cell] = demand[cell] != notch[cell] ? LatticeColours.MOVING : LatticeColours.rod(notch[cell] / 48.0);
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
                return colours[cell];
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
