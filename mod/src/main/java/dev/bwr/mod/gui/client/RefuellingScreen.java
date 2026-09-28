package dev.bwr.mod.gui.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.gui.CoreMapSnapshot;
import dev.bwr.mod.gui.RefuellingMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 */
public class RefuellingScreen extends BwrScreen<RefuellingMenu> {

    public static final ResourceLocation TEXTURE = BwrMod.id("textures/gui/refuelling.png");

    private static final int WIDTH = 256;
    private static final int HEIGHT = 230;

    private static final int GRID_X = 8;
    private static final int GRID_Y = 30;
    private static final int GRID_W = 146;
    private static final int GRID_H = 84;

    private static final int DETAIL_X = 164;
    private static final int DETAIL_RIGHT = 246;

    private enum Overlay {
        BURNUP, KINF
    }

    private Overlay overlay = Overlay.BURNUP;

    private LatticeGridWidget grid;
    private Button burnupTab;
    private Button kinfTab;
    private Button loadButton;
    private Button unloadButton;
    private Button markButton;
    private Button swapButton;

    public RefuellingScreen(RefuellingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TEXTURE, WIDTH, HEIGHT);
    }

    @Override
    protected void init() {
        super.init();

        burnupTab = addRenderableWidget(Button.builder(Component.literal("BURNUP"),
                        b -> overlay = Overlay.BURNUP)
                .bounds(leftPos + 6, topPos + 14, 52, 12).build());
        kinfTab = addRenderableWidget(Button.builder(Component.literal("K-INF"),
                        b -> overlay = Overlay.KINF)
                .bounds(leftPos + 60, topPos + 14, 46, 12).build());

        grid = addRenderableWidget(new LatticeGridWidget(
                leftPos + GRID_X, topPos + GRID_Y, GRID_W, GRID_H, cell -> {
        }));
        grid.enableRefuellingControls();
        addRenderableWidget(Button.builder(Component.literal("CLEAR"), b->{grid.clearSelection();grid.setMarked(-1);})
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Clear selected positions and the swap mark.")))
                .bounds(leftPos+110,topPos+14,40,12).build());
        addRenderableWidget(Button.builder(Component.literal("FIT"), b->grid.resetView())
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Fit the core to the map. Scroll to zoom; right-drag to pan.")))
                .bounds(leftPos+152,topPos+14,28,12).build());

        int row = topPos + 129;
        loadButton = addRenderableWidget(Button.builder(Component.literal("LOAD"),
                        b -> menu.sendBatch(true, grid.selectedCells()))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Load selected empty positions in click order, using held fuel first, then inventory.")))
                .bounds(leftPos + 8, row, 54, 16).build());
        unloadButton = addRenderableWidget(Button.builder(Component.literal("UNLOAD"),
                        b -> menu.sendBatch(false, grid.selectedCells()))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Unload selected occupied positions. Items drop beside you if your inventory is full.")))
                .bounds(leftPos + 66, row, 58, 16).build());
        markButton = addRenderableWidget(Button.builder(Component.literal("MARK"),
                        b -> {grid.setMarked(grid.marked() == grid.selected() ? -1 : grid.selected());grid.clearSelection();})
                .bounds(leftPos + 128, row, 52, 16).build());
        swapButton = addRenderableWidget(Button.builder(Component.literal("SWAP"), b -> {
                    menu.sendCommand(RefuellingMenu.CMD_SWAP, grid.marked(), grid.selected());
                    grid.setMarked(-1);
                })
                .bounds(leftPos + 184, row, 56, 16).build());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        burnupTab.active = overlay != Overlay.BURNUP;
        kinfTab.active = overlay != Overlay.KINF;
        grid.setCells(cells());

        CoreMapSnapshot map = menu.map;
        int selected = grid.selected();
        boolean valid = map != null && selected >= 0 && selected < map.coreSlotCount;
        int[] selection=grid.selectedCells();
        boolean empty=false,occupied=false;
        if(map!=null)for(int cell:selection)if(cell<map.coreSlotCount){if(map.isOccupied(cell))occupied=true;else empty=true;}
        loadButton.active = empty && menu.bundlesInInventory > 0;
        unloadButton.active = occupied;
        markButton.active = valid && selection.length==1;
        swapButton.active = valid && selection.length==1 && grid.marked() >= 0 && grid.marked() != selected;
    }

    @Override public boolean mouseClicked(double x,double y,int button) {
        if(button==1 && grid.isMouseOver(x,y))return grid.mouseClicked(x,y,button);
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if(button==1 && grid.mouseDragged(x,y,button,dx,dy))return true;
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button) {
        if(button==1 && grid.mouseReleased(x,y,button))return true;
        return super.mouseReleased(x,y,button);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, TEXT_BRIGHT, false);
        String head = "head off";
        graphics.drawString(font, head, imageWidth - 6 - font.width(head), titleLabelY,
                ACCENT, false);
    }

    @Override
    protected List<Component> hoverTooltip() {
        return menu.map == null ? List.of() : grid.hoverTooltip();
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!menu.open || menu.map == null) {
            text(graphics, "Waiting for the core...", 12, 40, TEXT_DIM);
            return;
        }
        detail(graphics);
    }

    private void detail(GuiGraphics graphics) {
        CoreMapSnapshot map = menu.map;
        int y = 32;
        readout(graphics, "FUEL", menu.loadedAssemblies + "/" + menu.assemblyCount,
                DETAIL_X, y, DETAIL_RIGHT, TEXT_BRIGHT);
        readout(graphics, "k-inf", num(menu.aggregateKInf, 4), DETAIL_X, y += 10,
                DETAIL_RIGHT, TEXT_BRIGHT);
        readout(graphics, "k ARO", num(menu.kEffAllRodsOut, 4), DETAIL_X, y += 10,
                DETAIL_RIGHT, menu.endOfCycle ? WARN : GOOD);
        readout(graphics, "beta", num(menu.effectiveBeta, 5), DETAIL_X, y += 10,
                DETAIL_RIGHT, TEXT_BRIGHT);
        readout(graphics, "burn av", big(menu.averageBurnup), DETAIL_X, y += 10,
                DETAIL_RIGHT, TEXT_BRIGHT);
        readout(graphics, "burn pk", big(menu.peakBurnup), DETAIL_X, y += 10,
                DETAIL_RIGHT, TEXT_BRIGHT);
        readout(graphics, "peaking", num(map.peakingFactor(), 2), DETAIL_X, y += 10,
                DETAIL_RIGHT, TEXT_BRIGHT);
        readout(graphics, "in hand", String.valueOf(menu.bundlesInInventory), DETAIL_X, y += 10,
                DETAIL_RIGHT, TEXT_BRIGHT);

        int selected = grid.selected();
        String selectedLine;
        if (selected < 0 || selected >= map.coreSlotCount) {
            selectedLine = "Wheel zoom / Drag RMB to pan";
        } else if (grid.selectedCells().length>1) {
            selectedLine = grid.selectedCells().length + " selected | Click to toggle";
        } else if (!map.isOccupied(selected)) {
            selectedLine = String.format(Locale.ROOT, "Position %d: empty.", selected);
        } else if (map.isInsert(selected)) {
            selectedLine = Component.translatable("insert.bwr." + map.insertId(selected)).getString()
                    + String.format(Locale.ROOT, " | %.1f%%", 100 * map.insertProgress[selected]);
        } else {
            selectedLine = String.format(Locale.ROOT,
                    "Position %d: %s, %.2f%% fissile, %,.0f MWd/t, k-inf %.4f",
                    selected, map.fuelTypeName(selected),
                    map.enrichmentWeightFraction[selected] * 100.0,
                    map.burnupMwdPerTonne[selected], map.kInf[selected]);
        }
        text(graphics, font.plainSubstrByWidth(selectedLine, GRID_W), 8, 116, TEXT);
        if (grid.marked() >= 0) {
            text(graphics, "Marked: " + grid.marked(),
                    164, 112, 0xFFFFC24A);
        }
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
                    return 0xFF191C20;
                }
                if (map.isInsert(cell)) return 0xFFB886D6;
                if (overlay == Overlay.BURNUP) {
                    // Fresh is green, spent is red.
                    double f = Math.min(1.0, map.burnupMwdPerTonne[cell] / peakBurnup);
                    int r = (int) Math.round(60 + 180 * f);
                    int g = (int) Math.round(200 - 150 * f);
                    return 0xFF000000 | (r << 16) | (g << 8) | 70;
                }
                // k-inf: below 1.0 is dark, well above is bright.
                double f = Math.max(0.0, Math.min(1.0, (map.kInf[cell] - 0.90) / 0.35));
                int v = (int) Math.round(50 + 190 * f);
                return 0xFF000000 | (v << 16) | (v << 8) | (v / 2 + 40);
            }

            @Override
            public List<Component> tooltip(int cell) {
                List<Component> out = new ArrayList<>();
                if (!map.isOccupied(cell)) {
                    out.add(Component.literal("Position " + cell + ": empty"));
                    out.add(Component.literal("Select it and press LOAD to place a bundle."));
                    return out;
                }
                if (map.isInsert(cell)) return map.insertTooltip(cell);
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
