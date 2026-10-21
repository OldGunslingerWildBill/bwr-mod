package dev.bwr.mod.gui.client;

import dev.bwr.mod.gui.CoreMapSnapshot;
import dev.bwr.mod.gui.RefuellingMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.BwrStyle.Type;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.Ui;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The refuelling floor. Same lattice widget as the control room, painted by
 * burnup instead of flux, with the bundle's identity on the right.
 *
 * <p>Two overlays here as well, because the two questions a refuelling outage
 * asks are different: <b>burnup</b> shows how far through its life each bundle
 * is, and <b>k-inf</b> shows what each one is still worth. A bundle can be
 * heavily burned and still worth keeping if it started highly enriched, and a
 * gadded bundle gets better for its first few thousand MWd/t, so the two maps
 * genuinely disagree and both are worth having.
 *
 * <p>Shuffling is mark-then-swap: click a bundle, press MARK, click another,
 * press SWAP. Exposure travels with the bundle, so a partly-burned assembly
 * moved to the periphery keeps everything that has happened to it and simply
 * burns slower from now on.
 *
 * <p>Layout: the menu's inventory slots fix the panel at 320 x 230 (the inventory sits at x 48, y 148 and 206), so the
 * map and the statistics fill what is above the inventory and the four actions stand beside it.
 */
public class RefuellingScreen extends BwrScreen<RefuellingMenu> {

    private enum Overlay {
        BURNUP, KINF
    }

    private static final int MAP_COLUMN = 164;
    private static final int TOP_HEIGHT = 112;

    private Overlay overlay = Overlay.BURNUP;

    private LatticeGridWidget grid;
    private BwrButton loadButton;
    private BwrButton unloadButton;
    private BwrButton markButton;
    private BwrButton swapButton;
    private BwrButton clearButton;
    private BwrButton fitButton;

    public RefuellingScreen(RefuellingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 320, 230);
    }

    @Override
    protected boolean trayLimitsBody() {
        return false;
    }

    @Override
    protected UiElement buildUi() {
        if (grid == null) {
            grid = new LatticeGridWidget(0, 0, 100, 84, cell -> {
            });
            grid.enableRefuellingControls();
            loadButton = BwrButton.secondary("LOAD", "Load fuel into the selected empty positions, in the order you clicked them. Fuel in your hand goes first, then fuel from your inventory.",
                    b -> menu.sendBatch(true, grid.selectedCells()));
            unloadButton = BwrButton.secondary("UNLOAD", "Take the fuel out of the selected positions. Whatever does not fit in your inventory drops beside you.",
                    b -> menu.sendBatch(false, grid.selectedCells()));
            markButton = BwrButton.secondary("MARK", "Mark the selected position as the one to swap from.", b -> {
                grid.setMarked(grid.marked() == grid.selected() ? -1 : grid.selected());
                grid.clearSelection();
            });
            swapButton = BwrButton.secondary("SWAP", "Swap the marked position with the selected one.", b -> {
                menu.sendCommand(RefuellingMenu.CMD_SWAP, grid.marked(), grid.selected());
                grid.setMarked(-1);
            });
            clearButton = BwrButton.secondary("CLEAR", "Clear the selected positions and the swap mark.", b -> {
                grid.clearSelection();
                grid.setMarked(-1);
            });
            fitButton = BwrButton.secondary("FIT", "Fit the whole core in the map. Scroll to zoom and right-drag to pan. With the map focused, the arrow keys, plus, minus and Home do the same.",
                    b -> grid.resetView());
        }

        var map = Layout.column(BwrStyle.SP1,
                Layout.fill(grid, 84),
                Layout.legend(() -> overlay == Overlay.BURNUP ? "Fresh" : "0.90",
                        () -> overlay == Overlay.BURNUP ? big(Math.max(1.0, menu.peakBurnup)) + " MWd/t" : "1.25 k-inf",
                        f -> overlay == Overlay.BURNUP ? LatticeColours.burnup(f) : LatticeColours.kInf(f)));

        var stats = Layout.card("",
                Layout.row("Fuel", () -> menu.loadedAssemblies + "/" + menu.assemblyCount).dense()
                        .tip("How many of the core's positions have a fuel bundle in them."),
                Layout.row("k-inf", () -> num(menu.aggregateKInf, 4)).dense().tip("The infinite-medium multiplication factor (k-inf) of the whole loading."),
                Layout.row("k ARO", () -> num(menu.kEffAllRodsOut, 4)).dense().status(() -> menu.endOfCycle ? Status.CAUTION : Status.NONE)
                        .tip(() -> List.of(Component.literal("The effective multiplication factor with all rods out (ARO)."),
                                Component.literal(menu.endOfCycle ? "The core model says the cycle has ended." : "The cycle has not ended."))),
                Layout.row("Beta", () -> num(menu.effectiveBeta, 5)).dense().tip("The effective delayed neutron fraction."),
                Layout.row("Avg burn", () -> big(menu.averageBurnup), "MWd/t").dense().tip("The average burnup of the loaded bundles."),
                Layout.row("Peak burn", () -> big(menu.peakBurnup), "MWd/t").dense().tip("The highest burnup of any bundle."),
                Layout.row("Peaking", () -> map() == null ? "-" : num(map().peakingFactor(), 2)).dense()
                        .tip("The radial peaking factor: the hottest bundle's power divided by the core average."),
                Layout.row("In hand", () -> String.valueOf(menu.bundlesInInventory)).dense()
                        .tip("The fuel bundles in your inventory that you can load."))
                .compact();
        var selection = Layout.note(this::selectionText).maxLines(2);
        var side = Layout.column(BwrStyle.SP1, stats, selection);

        var top = Layout.split(BwrStyle.SP1);
        top.fixed(map, MAP_COLUMN);
        top.add(side, 1);

        int[] tray = relativeTray();
        int actionsX = tray == null ? 216 : tray[0] + tray[2] + BwrStyle.SP1;
        var actions = Layout.card("Actions",
                Layout.controls(BwrStyle.SP1).add(loadButton, 1).add(unloadButton, 1),
                Layout.gap(BwrStyle.SP1),
                Layout.controls(BwrStyle.SP1).add(markButton, 1).add(swapButton, 1))
                .compact();
        actions.tip("The vessel head is off: bundles are lifted out through the top of the open vessel.");
        var lower = Layout.split(0);
        lower.fixed(Layout.gap(0), actionsX - BwrStyle.SP2);
        lower.add(actions, 1);

        return Layout.column(BwrStyle.SP1,
                Layout.note("Waiting for the core to report").body().status(() -> Status.INFO).showIf(() -> !ready()),
                Layout.sized(TOP_HEIGHT, top).showIf(this::ready),
                lower);
    }

    @Override
    protected UiElement buildTitleItems() {
        return Layout.titleItems(
                Layout.tabs(new String[]{"BURNUP", "K-INF"},
                        new String[]{"Colour each position by its burnup, from fresh fuel up to the core's highest.",
                                "Colour each position by its k-infinity, which shows what the bundle is still worth."},
                        () -> overlay.ordinal(), i -> overlay = Overlay.values()[i]),
                Layout.controls(BwrStyle.SP1).fixed(clearButton, 46).fixed(fitButton, 30));
    }

    private boolean ready() {
        return menu.open && menu.map != null;
    }

    private CoreMapSnapshot map() {
        return menu.map;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        grid.setCells(cells());

        CoreMapSnapshot map = menu.map;
        int selected = grid.selected();
        boolean valid = map != null && selected >= 0 && selected < map.coreSlotCount;
        int[] selection = grid.selectedCells();
        boolean empty = false;
        boolean occupied = false;
        if (map != null) {
            for (int cell : selection) {
                if (cell < map.coreSlotCount) {
                    if (map.isOccupied(cell)) {
                        occupied = true;
                    } else {
                        empty = true;
                    }
                }
            }
        }
        loadButton.active = empty && menu.bundlesInInventory > 0;
        unloadButton.active = occupied;
        markButton.active = valid && selection.length == 1;
        swapButton.active = valid && selection.length == 1 && grid.marked() >= 0 && grid.marked() != selected;

        if (!loadButton.active) {
            loadButton.setTooltipText(!empty ? "To load fuel, first select one or more empty positions on the map."
                    : "You have no fuel bundles in your inventory to load.");
        } else {
            loadButton.setTooltipText("Load fuel into the selected empty positions, in the order you clicked them. Fuel in your hand goes first, then fuel from your inventory.");
        }
        unloadButton.setTooltipText(occupied ? "Take the fuel out of the selected positions. Whatever does not fit in your inventory drops beside you."
                : "To unload fuel, first select one or more occupied positions on the map.");
        markButton.setTooltipText(markButton.active ? (grid.marked() == selected ? "Remove the swap mark."
                : "Mark the selected position as the one to swap from.") : "To mark a position, first select exactly one on the map.");
        swapButton.setTooltipText(swapButton.active ? "Swap the marked position with the selected one."
                : "To swap, first mark one position, then select a different one.");
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 1 && grid.isMouseOver(x, y)) {
            return grid.mouseClicked(x, y, button);
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if ((button == 0 || button == 1) && grid.mouseDragged(x, y, button, dx, dy)) {
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if ((button == 0 || button == 1) && grid.mouseReleased(x, y, button)) {
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    @Override
    protected List<Component> hoverTooltip() {
        return menu.map == null ? List.of() : grid.hoverTooltip();
    }

    /** What is selected on the map, and the swap mark. */
    private String selectionText() {
        CoreMapSnapshot map = menu.map;
        if (map == null) {
            return "";
        }
        int selected = grid.selected();
        String line;
        if (selected < 0 || selected >= map.coreSlotCount) {
            line = "Scroll to zoom, and right-drag to pan.";
        } else if (grid.selectedCells().length > 1) {
            line = grid.selectedCells().length + " selected. Click a position to add or remove it.";
        } else if (!map.isOccupied(selected)) {
            line = String.format(Locale.ROOT, "Position %d: empty.", selected);
        } else if (map.isInsert(selected)) {
            line = Component.translatable("insert.bwr." + map.insertId(selected)).getString()
                    + String.format(Locale.ROOT, " | %.1f%%", 100 * map.insertProgress[selected]);
        } else {
            line = String.format(Locale.ROOT, "Position %d: %s, %.2f%% fissile, burnup %,.0f MWd/t, k-inf %.4f",
                    selected, map.fuelTypeName(selected), map.enrichmentWeightFraction[selected] * 100.0,
                    map.burnupMwdPerTonne[selected], map.kInf[selected]);
        }
        if (grid.marked() >= 0) {
            // two lines at most: the selection gets one, the mark the other
            line = Ui.fit(line, Type.CAPTION, 120) + "\nMarked position: " + grid.marked();
        }
        return line;
    }

    private LatticeGridWidget.Cells cells() {
        CoreMapSnapshot map = menu.map;
        if (map == null) {
            return null;
        }
        // Scale burnup against the peak in this core rather than an absolute
        // number, because what "highly burned" means depends on the fuel.
        double peakBurnup = Math.max(1.0, menu.peakBurnup);
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
                    return LatticeColours.EMPTY;
                }
                if (map.isInsert(cell)) {
                    return LatticeColours.INSERT;
                }
                if (overlay == Overlay.BURNUP) {
                    return LatticeColours.burnup(Math.min(1.0, map.burnupMwdPerTonne[cell] / peakBurnup));
                }
                // k-inf: below 1.0 is dark, well above is bright.
                return LatticeColours.kInf((map.kInf[cell] - 0.90) / 0.35);
            }

            @Override
            public List<Component> tooltip(int cell) {
                List<Component> out = new ArrayList<>();
                if (!map.isOccupied(cell)) {
                    out.add(Component.literal("Position " + cell + ": empty"));
                    out.add(Component.literal("Select it and press Load to place a bundle."));
                    return out;
                }
                if (map.isInsert(cell)) {
                    return map.insertTooltip(cell);
                }
                out.add(Component.literal(String.format(Locale.ROOT, "Position %d - %s",
                        cell, map.fuelTypeName(cell))));
                out.add(Component.literal(String.format(Locale.ROOT, "%.2f%% fissile when it was made",
                        map.enrichmentWeightFraction[cell] * 100.0)));
                out.add(Component.literal(String.format(Locale.ROOT, "burnup %,.0f MWd/t",
                        map.burnupMwdPerTonne[cell])));
                out.add(Component.literal(String.format(Locale.ROOT, "k-inf %.4f",
                        map.kInf[cell])));
                out.add(Component.literal(String.format(Locale.ROOT,
                        "its last power was %.0f%% of the hottest bundle's",
                        map.relativeFlux[cell] * 100.0)));
                return out;
            }
        };
    }
}
