package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;

import java.util.List;

/**
 * Momentary pushbutton, 6x6 (spec 2.1): output "state" is 1 while held and 0
 * otherwise. Pressing it also acknowledges or resets an annunciator group when
 * configured with that role ({@link AnnunciatorGroups}); that is display
 * behaviour only and never touches the plant.
 */
public final class PushbuttonType extends PanelInputType {
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(6, 6), Footprint.of(8, 8),
            // real size: a 30.5 mm oiltight pushbutton
            Footprint.of(1, 1));
    private static final List<ConfigField> SCHEMA = PanelStyles.withLook(PushbuttonData.TYPE, List.of(
            ConfigField.color("Color", "component.bwr.field.cap_color"),
            ConfigField.choice("Role", "component.bwr.field.role", PushbuttonData.ROLES),
            ConfigField.integer("AnnGroup", "component.bwr.field.ann_group", AnnunciatorData.MIN_GROUP, AnnunciatorData.MAX_GROUP)));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("state"));

    PushbuttonType() {
        super(BwrMod.id("pushbutton"));
    }

    @Override
    public int realSizes() {
        return 1;
    }

    @Override
    public List<Footprint> footprints() {
        return FOOTPRINTS;
    }

    @Override
    public ComponentData createData() {
        return new PushbuttonData();
    }

    @Override
    public List<ConfigField> configSchema() {
        return SCHEMA;
    }

    @Override
    public List<PortSpec> outputPorts(ComponentData data) {
        return OUTPUTS;
    }

    @Override
    public String labelPrefix() {
        return "PB";
    }

    @Override
    public boolean holdable() {
        return true;
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        PanelComponent component = context.component();
        switch (action) {
            case PRESS -> {
                boolean edge = component.output(0) == 0.0;
                context.setOutput(0, 1.0);
                armFailsafe(context);
                if (edge) {
                    PushbuttonData data = (PushbuttonData) component.data();
                    switch (data.role) {
                        case PushbuttonData.ROLE_ACK -> AnnunciatorGroups.acknowledge(context.level(), context.owner(), data.group);
                        case PushbuttonData.ROLE_RESET -> AnnunciatorGroups.reset(context.level(), context.owner(), data.group);
                        default -> {
                        }
                    }
                }
                return true;
            }
            case RELEASE -> {
                disarm(context);
                context.setOutput(0, 0.0);
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
