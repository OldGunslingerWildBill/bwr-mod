package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.plant.PlantBuiltin;
import dev.bwr.mod.panel.plant.PlantLayout;
import dev.bwr.mod.panel.plant.PlantTouch;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * The plant computer ({@code bwr:plant_computer}): a safety parameter display for the control room, a screen of text,
 * numbers, bars, trends and green / amber / red tiles up to 4 x 4 blocks. Its pages come from a CC:Tweaked program
 * ({@code bwr_plant_computer}, see {@code PLANT-COMPUTER.md}), or from a built-in page that reads the reactor its one input,
 * {@code reactor}, is bound to with the Networking Wrench ({@link PlantBuiltin}). See {@link PlantComputerData}.
 *
 * <p>It is read-only towards the plant by construction: no outputs, no sinks, and the only thing a touch does is change the
 * built-in page or raise the CC event {@code plant_computer_touch} for a program that chose to listen.
 */
public final class PlantComputerType extends PanelInputType {
    public static final String ID_PATH = "plant_computer";
    public static final List<Footprint> FOOTPRINTS = List.of(
            Footprint.of(64, 48), Footprint.of(32, 24), Footprint.of(32, 32), Footprint.of(48, 32), Footprint.of(48, 48),
            Footprint.of(64, 32), Footprint.of(64, 64));
    private static final List<ConfigField> SCHEMA = PlantComputerData.schema();
    private static final List<PortSpec> INPUTS = List.of(PortSpec.of("reactor"));

    PlantComputerType() {
        super(BwrMod.id(ID_PATH));
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new PlantComputerData();
    }

    @Override
    public ComponentData createData(Footprint footprint) {
        PlantComputerData data = new PlantComputerData();
        data.setFallbackSize(footprint.w(), footprint.h());
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
        return "PC";
    }

    /** The built-in pages keep reading the reactor, and a program's rate-limited patches keep going out. */
    @Override
    public boolean keepsSampling(PanelComponent component) {
        return true;
    }

    @Override
    public Component refuseBinding(ServerLevel level, PanelComponent component, int inputIndex, BlockPos device) {
        if (inputIndex != 0) {
            return null;
        }
        if (!level.isLoaded(device)
                || !(dev.bwr.mod.signal.DeviceResolver.resolve(level, device) instanceof ReactorControllerBlockEntity)) {
            return dev.bwr.mod.text.BwrText.tr("message.bwr.plant_computer.not_a_reactor",
                    "A plant computer reads a reactor controller: link the reactor, not another device");
        }
        return null;
    }

    // ---- touch: soft keys

    @Override
    public boolean isInteractive() {
        return true;
    }

    /** The click as {@code row * 256 + column} of the character grid (0-based), or -1 outside it. */
    @Override
    public double clickValue(PanelComponent component, double cellX, double cellY) {
        PlantComputerData data = (PlantComputerData) component.data();
        int[] cell = PlantLayout.of(component.w(), component.h(), data.textSize).cellAt(cellX, cellY);
        return cell == null ? -1.0 : cell[1] * 256.0 + cell[0];
    }

    /**
     * A press on a soft key. On a program page it raises {@code plant_computer_touch} (page, key) for the CC computers on the
     * panel; on a built-in page it shows that page. Nothing else: no output changes and nothing reaches the plant.
     */
    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        if (action != OperateAction.PRESS || value < 0) {
            return false;
        }
        PlantComputerData data = (PlantComputerData) context.component().data();
        int key = data.keyAtClick(value);
        if (key == 0) {
            return false;
        }
        if (!data.acceptTouch()) {
            return true;
        }
        if (data.programMode()) {
            PlantTouch.fire(context.level(), context.owner(), context.component(), data.program().selected() + 1, key);
        } else if (data.showBuiltinPage(key)) {
            data.sampleContent(context.level(), context.owner(), context.component());
            context.owner().setChanged();
            context.syncFull();
        }
        return true;
    }
}
