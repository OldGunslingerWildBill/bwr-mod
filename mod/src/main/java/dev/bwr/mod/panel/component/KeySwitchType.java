package dev.bwr.mod.panel.component;

import dev.bwr.mod.BwrMod;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Key switch, 2x3 (spec 2.1): a maintained 0/1 that can only be operated while
 * the player holds the matching {@link PanelKeyItem} (unless the switch is
 * configured not to require one). A refused attempt changes nothing and says so
 * on the action bar.
 */
public final class KeySwitchType extends PanelInputType {
    public static final List<Footprint> FOOTPRINTS = List.of(Footprint.of(6, 8), Footprint.of(8, 10),
            // real size: a keyed escutcheon, 3 x 4 in
            Footprint.of(1, 2));
    private static final List<ConfigField> SCHEMA = PanelStyles.withLook(KeySwitchData.TYPE, List.of(
            ConfigField.bool("RequireKey", "component.bwr.field.require_key"),
            ConfigField.text("KeyCode", "component.bwr.field.key_code", KeySwitchData.MAX_CODE)));
    private static final List<PortSpec> OUTPUTS = List.of(PortSpec.of("state"));

    KeySwitchType() {
        super(BwrMod.id("key_switch"));
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
        return new KeySwitchData();
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
        return "KS";
    }

    @Override
    public void onLoaded(PanelComponent component) {
        component.storeOutput(0, ((KeySwitchData) component.data()).on ? 1.0 : 0.0);
    }

    @Override
    public boolean operate(OperateContext context, OperateAction action, double value) {
        KeySwitchData data = (KeySwitchData) context.component().data();
        boolean next;
        switch (action) {
            case PRESS -> next = !data.on;
            case TURN -> next = value > 0;
            case SET -> next = value >= 0.5;
            default -> {
                return false;
            }
        }
        if (data.requireKey && !PanelKeyItem.holdsKey(context.player(), data.keyCode)) {
            if (context.player() != null) {
                context.player().displayClientMessage(Component.translatable("message.bwr.panel.key_required"), true);
            }
            return false;
        }
        data.on = next;
        markSaved(context);
        context.setOutput(0, next ? 1.0 : 0.0);
        return true;
    }
}
