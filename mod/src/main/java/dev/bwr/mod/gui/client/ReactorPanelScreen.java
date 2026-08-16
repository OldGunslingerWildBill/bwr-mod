package dev.bwr.mod.gui.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.CoreMapSnapshot;
import dev.bwr.mod.gui.ReactorPanelMenu;
import dev.bwr.mod.reactor.VesselState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The control room. The core map with two overlays on the left, the plant
 * readouts on the right, and the actuators along the bottom.
 *
 * <h2>The two overlays</h2>
 * <b>Flux</b> paints each bundle by its share of core power, which is the
 * output of the nodal diffusion solve and therefore shows rod shadowing, the
 * burnup gradient and the hot spot around a stuck rod. <b>Rods</b> paints the
 * rod lattice by insertion, 00 fully in to 48 fully out. They are the same
 * widget on different lattices.
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

    public static final ResourceLocation TEXTURE = BwrMod.id("textures/gui/reactor_panel.png");

    private static final int WIDTH = 256;
    private static final int HEIGHT = 230;

    private static final int GRID_X = 8;
    private static final int GRID_Y = 30;
    private static final int GRID_W = 146;
    private static final int GRID_H = 124;

    private static final int READOUT_X = 164;
    private static final int READOUT_RIGHT = 246;
    /**
     * The readout column runs from here down to just above the two full-width
     * summary lines at y=164. Fifteen entries at a nine pixel pitch end at 146,
     * so the column stays clear of them; adding a sixteenth means moving the
     * summary block, not tightening the pitch further.
     */
    private static final int READOUT_Y = 20;
    private static final int READOUT_STEP = 9;

    private enum Overlay {
        FLUX, RODS
    }

    private Overlay overlay = Overlay.FLUX;

    private LatticeGridWidget grid;
    private Button fluxTab;
    private Button rodTab;
    private Button headButton;

    /** Rod the player last clicked on the rod overlay, or -1 for "all rods". */
    private int selectedRod = -1;

    public ReactorPanelScreen(ReactorPanelMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TEXTURE, WIDTH, HEIGHT);
    }

    @Override
    protected void init() {
        super.init();

        fluxTab = addRenderableWidget(Button.builder(Component.literal("FLUX"), b -> setOverlay(Overlay.FLUX))
                .bounds(leftPos + 6, topPos + 14, 44, 12).build());
        rodTab = addRenderableWidget(Button.builder(Component.literal("RODS"), b -> setOverlay(Overlay.RODS))
                .bounds(leftPos + 52, topPos + 14, 44, 12).build());

        grid = addRenderableWidget(new LatticeGridWidget(
                leftPos + GRID_X, topPos + GRID_Y, GRID_W, GRID_H, this::onCellClicked));

        int row1 = topPos + 190;
        int row2 = topPos + 206;

        addRenderableWidget(Button.builder(Component.literal("SCRAM"),
                        b -> menu.sendCommand(ReactorPanelMenu.CMD_SCRAM))
                .bounds(leftPos + 10, row1, 44, 16).build());
        addRenderableWidget(Button.builder(Component.literal("RESET"),
                        b -> menu.sendCommand(ReactorPanelMenu.CMD_RESET_SCRAM))
                .bounds(leftPos + 58, row1, 42, 16).build());
        headButton = addRenderableWidget(Button.builder(Component.literal("HEAD OFF"), b -> toggleHead())
                .bounds(leftPos + 104, row1, 70, 16).build());
        addRenderableWidget(Button.builder(Component.literal("REFUEL"),
                        b -> menu.sendCommand(ReactorPanelMenu.CMD_OPEN_REFUELLING))
                .bounds(leftPos + 178, row1, 62, 16).build());

        addRenderableWidget(Button.builder(Component.literal("ROD IN"),
                        b -> menu.sendCommand(ReactorPanelMenu.CMD_ROD_STEP, selectedRod, -1))
                .bounds(leftPos + 10, row2, 48, 16).build());
        addRenderableWidget(Button.builder(Component.literal("ROD OUT"),
                        b -> menu.sendCommand(ReactorPanelMenu.CMD_ROD_STEP, selectedRod, 1))
                .bounds(leftPos + 62, row2, 52, 16).build());
        addRenderableWidget(Button.builder(Component.literal("ALL IN 00"),
                        b -> menu.sendCommand(ReactorPanelMenu.CMD_ROD_NOTCH, -1, 0))
                .bounds(leftPos + 118, row2, 58, 16).build());
        addRenderableWidget(Button.builder(Component.literal("ALL OUT 48"),
                        b -> menu.sendCommand(ReactorPanelMenu.CMD_ROD_NOTCH, -1, 48))
                .bounds(leftPos + 180, row2, 60, 16).build());
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

    // -----------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        fluxTab.active = overlay != Overlay.FLUX;
        rodTab.active = overlay != Overlay.RODS;
        headButton.setMessage(Component.literal(
                menu.vesselState == VesselState.REFUELING ? "HEAD ON" : "HEAD OFF"));
        grid.setCells(overlay == Overlay.FLUX ? fluxCells() : rodCells());
        grid.visible = menu.formed;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, TEXT_BRIGHT, false);
        String state = menu.formed
                ? menu.vesselState.getSerializedName()
                : "not formed";
        graphics.drawString(font, state, imageWidth - 6 - font.width(state), titleLabelY,
                menu.vesselState == VesselState.REFUELING ? ACCENT : TEXT_DIM, false);
    }

    @Override
    protected List<Component> hoverTooltip() {
        return menu.formed ? grid.hoverTooltip() : List.of();
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!menu.formed) {
            int y = 40;
            text(graphics, "Reactor not formed.", 12, y, ALARM);
            for (String line : menu.statusLines) {
                y += 10;
                text(graphics, line.length() > 78 ? line.substring(0, 78) : line, 12, y, TEXT);
            }
            return;
        }

        readouts(graphics);
        secondary(graphics);
    }

    private void readouts(GuiGraphics graphics) {
        int y = READOUT_Y;
        line(graphics, "POWER", pct(menu.powerFractionOfRated), y,
                fractionColour(menu.powerFractionOfRated, 1.05, 1.20));
        line(graphics, "MWth", big(menu.thermalMW), y += READOUT_STEP, TEXT_BRIGHT);
        line(graphics, "PRESS", big(menu.pressurePsig) + " psig", y += READOUT_STEP,
                pressureColour(menu.pressurePsig));
        line(graphics, "dP/dt", num(menu.pressureRatePsiPerS, 1), y += READOUT_STEP, TEXT_BRIGHT);
        line(graphics, "LEVEL", num(menu.indicatedLevelIn, 1) + " in", y += READOUT_STEP,
                menu.uncoveredFuelFraction > 0.0 ? ALARM : TEXT_BRIGHT);
        line(graphics, "FLOW", pct(menu.coreFlowFraction), y += READOUT_STEP, TEXT_BRIGHT);
        line(graphics, "STEAM", big(menu.steamKgPerS), y += READOUT_STEP, TEXT_BRIGHT);
        line(graphics, "RHO", num(menu.reactivityDollars, 2) + "$", y += READOUT_STEP,
                menu.reactivityDollars >= 1.0 ? ALARM : TEXT_BRIGHT);
        // The nuclear instrument ladder. The count rate is what reads anything
        // at all on a shutdown core, and the two period indications are what
        // the player cross-checks: a source range channel driven past its
        // paralyzable peak rolls back down and its period meter then reports a
        // negative period on a core that is climbing, while the IRM alongside
        // it reads positive. Neither line is picked for the player and neither
        // is coloured, because deciding which channel to believe is the job
        // this panel exists to hand them.
        line(graphics, "SRM", sci(menu.srmCountsPerSecond), y += READOUT_STEP, TEXT_BRIGHT);
        line(graphics, "SRM T", period(menu.srmPeriodSeconds), y += READOUT_STEP, TEXT_BRIGHT);
        line(graphics, "IRM T", period(menu.irmPeriodSeconds), y += READOUT_STEP, TEXT_BRIGHT);
        line(graphics, "FUEL", big(menu.fuelTemperatureC) + "C", y += READOUT_STEP, TEXT_BRIGHT);
        line(graphics, "PKCLAD", big(menu.peakCladTemperatureC) + "C", y += READOUT_STEP,
                cladColour(menu.peakCladTemperatureC));
        line(graphics, "VOID", pct(menu.voidFraction), y += READOUT_STEP, TEXT_BRIGHT);
        line(graphics, "ACCUM", menu.chargedAccumulators + "/" + menu.rodCount,
                y += READOUT_STEP,
                menu.chargedAccumulators < menu.rodCount ? WARN : GOOD);
    }

    private void line(GuiGraphics graphics, String label, String value, int y, int colour) {
        readout(graphics, label, value, READOUT_X, y, READOUT_RIGHT, colour);
    }

    /**
     * A period meter's indication. Steady flux is a legitimately infinite
     * period, so "inf" is a reading and not an error.
     */
    private static String period(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || Math.abs(seconds) > 9999.0) {
            return "inf";
        }
        return num(seconds, 1) + "s";
    }

    private void secondary(GuiGraphics graphics) {
        String top = String.format(Locale.ROOT,
                "k-inf %.4f   beta %.5f   burnup %,.0f MWd/t   xenon %.0f%%   APRM %.1f%%",
                menu.aggregateKInf, menu.betaEffective, menu.averageBurnup,
                menu.xenonFraction * 100.0, menu.aprmPercent);
        String bottom = String.format(Locale.ROOT,
                "decay %.2f%%   oxid %.2f%%   H2 %.1f kg   uncovered %.0f%%   "
                        + "spray ring %.0f%%   pumps %d   drives %d pwr / %d water",
                menu.decayHeatFraction * 100.0, menu.oxidationFraction * 100.0, menu.hydrogenKg,
                menu.uncoveredFuelFraction * 100.0, menu.sprayRingCompleteness * 100.0,
                menu.pumpCount, menu.poweredDrives, menu.waterSuppliedDrives);
        text(graphics, top, 10, 164, TEXT);
        text(graphics, bottom, 10, 174,
                menu.uncoveredFuelFraction > 0.0 || menu.oxidationFraction > 0.0 ? WARN : TEXT);

        if (menu.scramActive) {
            text(graphics, "SCRAM", 10, 18, ALARM);
        }
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
                if (!map.isOccupied(cell)) {
                    return 0xFF191C20;
                }
                return heatColour(map.relativeFlux[cell]);
            }

            @Override
            public List<Component> tooltip(int cell) {
                List<Component> out = new ArrayList<>();
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

    /**
     * Rods sit in the gap between four assemblies, one per 2x2 group, so their
     * lattice is half the size of the assembly lattice along each axis. The
     * multiblock walks x outside z, so rod index is
     * {@code xIndex * rodsPerZ + zIndex} and the drawn position is column
     * {@code xIndex}, row {@code zIndex} — a plain top-down map of the drives.
     *
     * <p>The shape comes from the snapshot rather than from
     * {@code ceil(sqrt(count))}. That guess is only right when the vessel
     * interior is square, and interiors are legal anywhere from 5x5 to 21x21 in
     * either direction: a 5-wide by 11-deep interior gives 2 columns of 5 rods,
     * where the square guess would draw a 4-wide grid and put every rod except
     * the first somewhere it physically is not. Nothing crashed, which is why
     * it survived — the mapping stayed injective, so clicking cell {@code i}
     * still selected rod {@code i}, and only the picture was wrong.
     */
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

        int perX = menu.rodsPerX;
        int perZ = menu.rodsPerZ;
        if (perX <= 0 || perZ <= 0 || perX * perZ != count) {
            // Shape unknown or out of step with the rod count — an old snapshot,
            // or a vessel caught mid-rebuild. Fall back to the squarest grid
            // that holds them all. Still injective, so the overlay degrades to
            // "positions are arbitrary" rather than to a crash.
            perZ = (int) Math.ceil(Math.sqrt(count));
            perX = (count + perZ - 1) / perZ;
        }
        final int columns = perX;
        final int rows = perZ;

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
                // row * columns + column, with column = xIndex and row = zIndex.
                return (cell % rows) * columns + (cell / rows);
            }

            @Override
            public int colour(int cell) {
                double out = notch[cell] / 48.0;
                int grey = (int) Math.round(40 + 150 * (1.0 - out));
                int green = (int) Math.round(60 + 160 * out);
                boolean moving = demand[cell] != notch[cell];
                return moving
                        ? 0xFF000000 | (200 << 16) | (160 << 8) | 40
                        : 0xFF000000 | (grey << 16) | (green << 8) | grey;
            }

            @Override
            public List<Component> tooltip(int cell) {
                return List.of(
                        Component.literal(String.format(Locale.ROOT, "Rod %d at notch %02d",
                                cell, notch[cell])),
                        Component.literal(String.format(Locale.ROOT,
                                "demanded %02d   (00 fully inserted, 48 fully withdrawn)",
                                demand[cell])));
            }
        };
    }
}
