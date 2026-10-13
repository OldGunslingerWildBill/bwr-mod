package dev.bwr.mod.panel;

import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.text.BwrText;
import net.minecraft.network.chat.Component;

/** Common-side feedback for wiring operations; the receiving player selects the language. */
public final class PanelMessages {
    private PanelMessages() { }

    public static Component wiringResult(WiringOps.Result result) {
        return switch (result) {
            case OK -> BwrText.tr("message.bwr.panel_bind.result.ok", "Connected");
            case NOT_HOLDING_LINKER -> BwrText.tr("message.bwr.panel_bind.result.not_holding_linker", "Hold a panel linker");
            case OUT_OF_REACH -> BwrText.tr("message.bwr.panel_bind.result.out_of_reach", "The panel is out of reach");
            case NOT_PERMITTED -> BwrText.tr("message.bwr.panel_bind.result.not_permitted", "You cannot modify this device");
            case NO_SUCH_COMPONENT -> BwrText.tr("message.bwr.panel_bind.result.no_such_component", "The component is no longer present");
            case BAD_PORT -> BwrText.tr("message.bwr.panel_bind.result.bad_port", "This port is not available");
            case BAD_VALUE -> BwrText.tr("message.bwr.panel_bind.result.bad_value", "The binding value is outside the supported range");
            case NO_LINK -> BwrText.tr("message.bwr.panel_bind.result.no_link", "Link a plant device first");
            case WRONG_DIMENSION -> BwrText.tr("message.bwr.panel_bind.result.wrong_dimension", "The linked device is in another dimension");
            case OUT_OF_RANGE -> BwrText.tr("message.bwr.panel_bind.result.out_of_range", "The linked device is out of range");
            case DEVICE_MISMATCH -> BwrText.tr("message.bwr.panel_bind.result.device_mismatch", "The linked device has changed; select it again");
            case NO_DEVICE -> BwrText.tr("message.bwr.panel_bind.result.no_device", "The linked device is not loaded or no longer there");
            case NO_SUCH_KEY -> BwrText.tr("message.bwr.panel_bind.result.no_such_key", "The device no longer provides this signal or command");
            case NOT_IN_GROUP -> BwrText.tr("message.bwr.panel_bind.result.not_in_group", "Choose a component in the same panel group");
            case TOO_MANY -> BwrText.tr("message.bwr.panel_bind.result.too_many", "This output already has the maximum number of connections");
        };
    }

    public static Component wiringRefused(WiringOps.Result result) {
        return BwrText.tr("message.bwr.panel_bind.refused", "Not wired: %s", wiringResult(result));
    }
}
