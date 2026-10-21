package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.reactor.CoreRodMap;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * The LED rod pattern display ({@code bwr:led_rod_display}): a panel of small amber and red LEDs, two for every rod, in
 * the layout of the core, the kind of display an RWM / RSCS cabinet has beside the full core display. It reads the reactor
 * the way the full core display and the rod select matrix do: one input, {@code core}, bound with the linker to the reactor
 * controller (any of its signals; {@code formed} is the natural one), and the server reads that reactor's rods into one
 * snapshot ({@link CoreDisplayData}) that clients draw. See {@link LedRodDisplayData} for what the LEDs mean.
 *
 * <p>It shares the data of the core displays but not their type: it is a small display (tiles of 1 to 3 cells, at most 4
 * blocks across like every other type), so it does not take the 8 block span of the full core display.
 *
 * <p>No outputs and nothing to operate. A cell a rod is the real size: 17 x 17 cells for the reference 185 rod core, 26 x 26
 * for the biggest core. A new item starts at twice that, 34 x 34 (2 cells a rod); 51 x 51 gives 3 cells a rod. A new
 * component takes the biggest tile its size holds for the reference core.
 */
public final class LedRodDisplayType extends PanelInputType {
    public static final String ID_PATH = "led_rod_display";
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(51, 51),
            // The real tail: twice the real size first (2 cells a rod, where a new item starts), then the exact real sizes:
            // a cell a rod for the reference core, and for the biggest core a display can show.
            Footprint.of(34, 34), Footprint.of(17, 17), Footprint.of(26, 26));
    private static final List<ConfigField> SCHEMA = List.of(
            ConfigField.choice(LedRodDisplayData.KEY_MODE, "component.bwr.field.led_mode", LedRodDisplayData.MODES),
            ConfigField.integer("TileCells", "component.bwr.field.tile_cells", LedRodDisplayData.MIN_TILE, LedRodDisplayData.MAX_TILE),
            ConfigField.text("Caption", "component.bwr.field.caption", CoreDisplayData.MAX_CAPTION));
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("core"));

    LedRodDisplayType() {
        super(BwrMod.id(ID_PATH));
    }

    @Override
    public int realSizes() {
        return 3;
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new LedRodDisplayData();
    }

    /** A display placed with {@code footprint} starts at the biggest tile (1 to 3 cells) that holds the reference core. */
    @Override
    public ComponentData createData(Footprint footprint) {
        LedRodDisplayData data = new LedRodDisplayData();
        data.tileCells = LedRodDisplayData.fittingTileFor(footprint.w(), footprint.h());
        return data;
    }

    @Override
    public List<ConfigField> configSchema() {
        return SCHEMA;
    }

    @Override
    public List<PortSpec> inputPorts(ComponentData data) {
        return INPUTS;
    }

    @Override
    public String labelPrefix() {
        return "LED";
    }

    /** Not operable: a display. */
    @Override
    public boolean isInteractive() {
        return false;
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        return false;
    }

    @Override
    public Component refuseBinding(ServerLevel level, PanelComponent component, int inputIndex, BlockPos device) {
        if (inputIndex != 0) {
            return null;
        }
        if (!level.isLoaded(device)
                || !(dev.bwr.mod.signal.DeviceResolver.resolve(level, device) instanceof ReactorControllerBlockEntity reactor)) {
            return dev.bwr.mod.text.BwrText.tr("message.bwr.core_display.not_a_reactor",
                    "A core display reads a reactor controller: link the reactor, not another device");
        }
        CoreRodMap map = reactor.isFormed() ? reactor.coreMap() : null;
        return map == null ? null : tooSmall(component, (LedRodDisplayData) component.data(), map, new CompoundTag());
    }

    @Override
    public Component refuseConfig(ServerLevel level, PanelComponent component, CompoundTag proposed) {
        LedRodDisplayData data = (LedRodDisplayData) component.data();
        CoreRodMap map = data.snapshot().formed() ? data.snapshot().cells().map() : null;
        return map == null ? null : tooSmall(component, data, map, proposed);
    }

    private static Component tooSmall(PanelComponent component, LedRodDisplayData data, CoreRodMap map, CompoundTag config) {
        int[] needed = data.neededCells(map, config);
        if (component.w() >= needed[0] && component.h() >= needed[1]) {
            return null;
        }
        int blocksW = (needed[0] + PanelGeometry.GRID - 1) / PanelGeometry.GRID;
        int blocksH = (needed[1] + PanelGeometry.GRID - 1) / PanelGeometry.GRID;
        return dev.bwr.mod.text.BwrText.tr("message.bwr.core_display.too_small",
                "This core needs %s x %s cells (%s x %s blocks) at this tile size; the component is %s x %s. Use a larger size or fewer cells per tile",
                needed[0], needed[1], blocksW, blocksH, component.w(), component.h());
    }
}
