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
            loadButton = BwrButton.secondary("LOAD", "Load the selected empty positions in click order, using held fuel first, then inventory.",
                    b -> menu.sendBatch(true, grid.selectedCells()));
            unloadButton = BwrButton.secondary("UNLOAD", "Unload the selected occupied positions. Items drop beside you if your inventory is full.",
                    b -> menu.sendBatch(false, grid.selectedCells()));
            markButton = BwrButton.secondary("MARK", "Mark the selected position as the source of a swap.", b -> {
                grid.setMarked(grid.marked() == grid.selected() ? -1 : grid.selected());
                grid.clearSelection();
            });
            swapButton = BwrButton.secondary("SWAP", "Swap the marked position with the selected one.", b -> {
                menu.sendCommand(RefuellingMenu.CMD_SWAP, grid.marked(), grid.selected());
                grid.setMarked(-1);
            });
            clearButton = BwrButton.secondary("CLEAR", "Clear selected positions and the swap mark.", b -> {
                grid.clearSelection();
                grid.setMarked(-1);
            });
            fitButton = BwrButton.secondary("FIT", "Fit the core to the map. Scroll to zoom and right-drag to pan; with the map focused use the arrow keys, plus, minus and Home.",
                    b -> grid.resetView());
        }

        var map = Layout.column(BwrStyle.SP1,
                Layout.fill(grid, 84),
                Layout.legend(() -> overlay == Overlay.BURNUP ? "Fresh" : "0.90",
                        () -> overlay == Overlay.BURNUP ? big(Math.max(1.0, menu.peakBurnup)) + " MWd/t" : "1.25 k-inf",
                        f -> overlay == Overlay.BURNUP ? LatticeColours.burnup(f) : LatticeColours.kInf(f)));

        var stats = Layout.card("",
                Layout.row("Fuel", () -> menu.loadedAssemblies + "/" + menu.assemblyCount).dense()
                        .tip("Fuel bundles loaded out of the core's positions."),
                Layout.row("k-inf", () -> num(menu.aggregateKInf, 4)).dense().tip("Infinite-medium multiplication factor of the whole loading."),
                Layout.row("k ARO", () -> num(menu.kEffAllRodsOut, 4)).dense().status(() -> menu.endOfCycle ? Status.CAUTION : Status.NONE)
                        .tip(() -> List.of(Component.literal("Effective multiplication factor with all rods out."),
                                Component.literal(menu.endOfCycle ? "Flagged by the core model as end of cycle." : "Not at end of cycle."))),
                Layout.row("beta", () -> num(menu.effectiveBeta, 5)).dense().tip("Effective delayed neutron fraction."),
                Layout.row("burn av", () -> big(menu.averageBurnup), "MWd/t").dense().tip("Average burnup of the loaded bundles."),
                Layout.row("burn pk", () -> big(menu.peakBurnup), "MWd/t").dense().tip("Peak burnup of any bundle."),
                Layout.row("peaking", () -> map() == null ? "-" : num(map().peakingFactor(), 2)).dense()
                        .tip("Radial peaking factor: the hottest bundle's power over the core average."),
                Layout.row("in hand", () -> String.valueOf(menu.bundlesInInventory)).dense()
                        .tip("Fuel bundles in your inventory, available to LOAD."))
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
                Layout.note("Waiting for the core...").body().status(() -> Status.INFO).showIf(() -> !ready()),
                Layout.sized(TOP_HEIGHT, top).showIf(this::ready),
                lower);
    }

    @Override
    protected UiElement buildTitleItems() {
        return Layout.titleItems(
                Layout.tabs(new String[]{"BURNUP", "K-INF"},
                        new String[]{"Colour each position by burnup, from fresh to the core's peak.",
                                "Colour each position by k-infinity: what the bundle is still worth."},
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
            loadButton.setTooltipText(!empty ? "To load: select one or more empty positions on the map first."
                    : "You have no fuel bundles in your inventory to load.");
        } else {
            loadButton.setTooltipText("Load the selected empty positions in click order, using held fuel first, then inventory.");
        }
        unloadButton.setTooltipText(occupied ? "Unload the selected occupied positions. Items drop beside you if your inventory is full."
                : "To unload: select one or more occupied positions on the map first.");
        markButton.setTooltipText(markButton.active ? (grid.marked() == selected ? "Remove the swap mark."
                : "Mark the selected position as the source of a swap.") : "To mark: select exactly one position first.");
        swapButton.setTooltipText(swapButton.active ? "Swap the marked position with the selected one."
                : "To swap: mark one position, then select a different one.");
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
        if (button == 1 && grid.mouseDragged(x, y, button, dx, dy)) {
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == 1 && grid.mouseReleased(x, y, button)) {
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
            line = "Wheel zooms; right-drag pans.";
        } else if (grid.selectedCells().length > 1) {
            line = grid.selectedCells().length + " selected | Click to toggle";
        } else if (!map.isOccupied(selected)) {
            line = String.format(Locale.ROOT, "Position %d: empty.", selected);
        } else if (map.isInsert(selected)) {
            line = Component.translatable("insert.bwr." + map.insertId(selected)).getString()
                    + String.format(Locale.ROOT, " | %.1f%%", 100 * map.insertProgress[selected]);
        } else {
            line = String.format(Locale.ROOT, "Position %d: %s, %.2f%% fissile, %,.0f MWd/t, k-inf %.4f",
                    selected, map.fuelTypeName(selected), map.enrichmentWeightFraction[selected] * 100.0,
                    map.burnupMwdPerTonne[selected], map.kInf[selected]);
        }
        if (grid.marked() >= 0) {
            // two lines at most: the selection gets one, the mark the other
            line = Ui.fit(line, Type.CAPTION, 120) + "\nMarked: " + grid.marked();
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
                    out.add(Component.literal("Select it and press LOAD to place a bundle."));
                    return out;
                }
                if (map.isInsert(cell)) {
                    return map.insertTooltip(cell);
                }
                out.add(Component.literal(String.format(Locale.ROOT, "Position %d - %s",
                        cell, map.fuelTypeName(cell))));
                out.add(Component.literal(String.format(Locale.ROOT, "%.2f%% fissile as fabricated",
                        map.enrichmentWeightFraction[cell] * 100.0)));
                out.add(Component.literal(String.format(Locale.ROOT, "burnup %,.0f MWd/t",
                        map.burnupMwdPerTonne[cell])));
                out.add(Component.literal(String.format(Locale.ROOT, "k-inf %.4f",
                        map.kInf[cell])));
                out.add(Component.literal(String.format(Locale.ROOT,
                        "last power share %.0f%% of the hottest bundle",
                        map.relativeFlux[cell] * 100.0)));
                return out;
            }
        };
    }
}
