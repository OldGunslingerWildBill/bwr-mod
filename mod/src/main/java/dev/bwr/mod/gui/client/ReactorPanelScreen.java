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
        FLUX, RODS, INFO
    }

    private Overlay overlay = Overlay.FLUX;

    private LatticeGridWidget grid;
    private Button fluxTab;
    private Button rodTab;
    private Button infoTab;
    private List<Component> infoTooltip=List.of();
    private Button headButton;
    private boolean footerHovered;

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
        infoTab = addRenderableWidget(Button.builder(Component.literal("INFO"), b -> setOverlay(Overlay.INFO))
                .bounds(leftPos + 98, topPos + 14, 44, 12).build());

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
        grid.visible=menu.formed && next!=Overlay.INFO;
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
        infoTab.active = overlay != Overlay.INFO;
        headButton.setMessage(Component.literal(
                menu.vesselState == VesselState.REFUELING ? "HEAD ON" : "HEAD OFF"));
        grid.setCells(overlay == Overlay.FLUX ? fluxCells() : rodCells());
        grid.visible = menu.formed && overlay!=Overlay.INFO;
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
        if (footerHovered) return List.of(
                Component.literal("Burnup " + big(menu.averageBurnup) + " MWd/t | beta " + num(menu.betaEffective,5)),
                Component.literal("Xenon " + pct(menu.xenonFraction) + " | APRM " + num(menu.aprmPercent,1) + "%"),
                Component.literal("Oxidation " + pct(menu.oxidationFraction) + " | hydrogen " + num(menu.hydrogenKg,1) + " kg"),
                Component.literal("Uncovered fuel " + pct(menu.uncoveredFuelFraction) + " | spray ring " + pct(menu.sprayRingCompleteness)));

        return !menu.formed?List.of():overlay==Overlay.INFO?infoTooltip:grid.hoverTooltip();
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
        footerHovered = menu.formed && overlay != Overlay.INFO && mouseX >= leftPos+8
                && mouseX < leftPos+248 && mouseY >= topPos+160 && mouseY < topPos+188;
        if (!menu.formed) {
            int y = 40;
            text(graphics, "Reactor not formed.", 12, y, ALARM);
            for (String line : menu.statusLines) {
                y += 10;
                text(graphics, line.length() > 78 ? line.substring(0, 78) : line, 12, y, TEXT);
            }
            return;
        }

        if(overlay==Overlay.INFO) { information(graphics,mouseX,mouseY);return; }
        readouts(graphics);
        secondary(graphics);
    }

    private void information(GuiGraphics g,int mouseX,int mouseY) {
        g.fill(leftPos+5,topPos+28,leftPos+251,topPos+188,0xFF1C222A);
        var c=menu.configuration;
        text(g,"FUEL & CONFIGURATION",12,33,ACCENT);
        readout(g,"Fuel bundles",menu.loadedAssemblies+" / "+menu.assemblyCount,12,46,244,TEXT_BRIGHT);
        readout(g,"Fuel-loaded rating*",big(c.fuelLoadedRatingMW())+" MWth",12,58,244,TEXT_BRIGHT);
        readout(g,"Flow-supported power*",big(c.flowSupportedMW())+" MWth",12,70,244,TEXT_BRIGHT);
        readout(g,"Steam equivalent*",big(c.steamEquivalentKgPerS())+" kg/s",12,82,244,TEXT_BRIGHT);
        text(g,"RECIRCULATION",12,96,ACCENT);
        readout(g,"Jets matched / needed",c.matchedJetAssemblies()+" / "+c.requiredJets(),12,108,244,TEXT_BRIGHT);
        readout(g,"RCPs installed / needed",c.externalPumps()+" / "+c.requiredExternalPumps(),12,120,244,TEXT_BRIGHT);
        readout(g,"Flow ceiling",pct(c.flowCeilingFraction())+" | "+big(c.flowCeilingKgPerS())+" kg/s",12,132,244,TEXT_BRIGHT);
        readout(g,"Vessel interior volume",c.interiorVolume()+" blocks^3",12,144,244,TEXT_BRIGHT);
        text(g,"LIVE OUTPUT",12,158,ACCENT);
        readout(g,"MWth / steam kg/s",big(menu.thermalMW)+" / "+big(menu.steamKgPerS),12,170,244,GOOD);
        text(g,"* Planning estimates; hover for basis",12,181,TEXT_DIM);
        int x=mouseX-leftPos,y=mouseY-topPos;infoTooltip=List.of();
        if(x<8 || x>248)return;
        if(y>=44 && y<57 && menu.map!=null) {
            var counts=new java.util.LinkedHashMap<String,Integer>();
            for(int i=0;i<menu.map.coreSlotCount;i++)if(menu.map.isOccupied(i))counts.merge(menu.map.fuelTypeName(i),1,Integer::sum);
            List<Component> lines=new ArrayList<>();lines.add(Component.literal("Loaded fuel; FLUX shows each bundle"));
            counts.forEach((name,count)->lines.add(Component.literal(count+" x "+name)));infoTooltip=lines;
        } else if(y>=57 && y<93 || y>=180)infoTooltip=List.of(
                Component.literal("Fuel rating = reference MW x loaded-slot fraction."),
                Component.literal("Power estimate = reference MW x min(fuel fraction, flow ceiling)."),
                Component.literal("Steam equivalent uses current pressure and feedwater temperature."),
                Component.literal("These are planning estimates, not operating limits."),
                Component.literal("Actual output depends on rods, fuel condition and water supply."));
        else if(y>=105 && y<143)infoTooltip=List.of(
                Component.literal("Jets must have a matching assembly across the vessel."),
                Component.literal("Place bases 1 or 2 blocks above the bottom shell; face oppositely."),
                Component.literal("Each RCP supports up to "+dev.bwr.core.flow.RecirculationSizing.JETS_PER_EXTERNAL_PUMP+" normal placed jet assemblies."),
                Component.literal("Targets assume normal jets and full-speed external pumps."),
                Component.literal("Unmatched jets: "+c.unmatchedJetAssemblies()+"; internal pumps: "+c.internalPumps()),
                Component.literal("Required core flow: "+big(c.requiredFlowKgPerS())+" kg/s"),
                Component.literal("Actual core flow: "+big(menu.coreFlowKgPerS)+" kg/s"));
        else if(y>=143 && y<155)infoTooltip=List.of(
                Component.literal("Whole interior: width x height x depth; shell excluded."),
                Component.literal("12 jets x cube root(volume / 200), rounded up to pairs."),
                Component.literal("Geometric sizing; lowering water level does not reduce the target."),
                Component.literal("Water: "+big(menu.coolantTemperatureC)+" C; inlet subcooling: "+big(c.inletSubcoolingKJPerKg())+" kJ/kg"));
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
        text(graphics, "Fuel " + menu.loadedAssemblies + "/" + menu.assemblyCount
                + " | k-inf " + num(menu.aggregateKInf,4) + " | decay " + pct(menu.decayHeatFraction), 8, 160, TEXT);
        text(graphics, "Drives: " + menu.poweredDrives + " powered / " + menu.waterSuppliedDrives
                + " water | pumps " + menu.pumpCount, 8, 170, TEXT);
        text(graphics, "Hover here: fuel, damage and cooling details", 8, 180,
                menu.uncoveredFuelFraction > 0 || menu.oxidationFraction > 0 ? WARN : TEXT_DIM);

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
        if (indices.length != count) return null;

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
                double out = notch[cell] / 48.0;
                int grey = (int) Math.round(40 + 80 * (1.0 - out));
                int green = (int) Math.round(120 + 100 * out);
                boolean moving = demand[cell] != notch[cell];
                return moving
                        ? 0xFF000000 | (200 << 16) | (160 << 8) | 40
                        : 0xFF000000 | (grey << 16) | (green << 8) | grey;
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
