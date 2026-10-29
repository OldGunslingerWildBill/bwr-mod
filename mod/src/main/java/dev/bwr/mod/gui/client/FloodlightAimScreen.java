package dev.bwr.mod.gui.client;

import dev.bwr.mod.floodlight.BeamLampBlockEntity;
import dev.bwr.mod.gui.FloodlightAimMenu;
import dev.bwr.mod.gui.client.style.BwrButton;
import dev.bwr.mod.gui.client.style.BwrStyle;
import dev.bwr.mod.gui.client.style.BwrStyle.Status;
import dev.bwr.mod.gui.client.style.Layout;
import dev.bwr.mod.gui.client.style.UiElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The xenon floodlight's aiming screen: heading, tilt and reach, each with a minus and a plus button (one step, ten with
 * Shift; reach one block, eight with Shift). The numbers change as you click; the lamp's head turns at once and its light
 * follows a moment later.
 */
public class FloodlightAimScreen extends BwrScreen<FloodlightAimMenu> {
    private BwrButton headingLess, headingMore, tiltLess, tiltMore, reachLess, reachMore;

    public FloodlightAimScreen(FloodlightAimMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 340, 176);
    }

    @Override
    protected UiElement buildUi() {
        if (headingLess == null) {
            headingLess = BwrButton.secondary("-", "Turn the lamp 1 degree to the left. Hold Shift for 10 degrees.", b -> turn(-1));
            headingMore = BwrButton.secondary("+", "Turn the lamp 1 degree to the right. Hold Shift for 10 degrees.", b -> turn(1));
            tiltLess = BwrButton.secondary("-", "Tilt the lamp 1 degree down. Hold Shift for 10 degrees.", b -> tilt(-1));
            tiltMore = BwrButton.secondary("+", "Tilt the lamp 1 degree up. Hold Shift for 10 degrees.", b -> tilt(1));
            reachLess = BwrButton.secondary("-", "Shorten the reach by 1 block. Hold Shift for 8 blocks.", b -> reach(-1));
            reachMore = BwrButton.secondary("+", "Lengthen the reach by 1 block. Hold Shift for 8 blocks.", b -> reach(1));
        }
        var heading = Layout.card("Heading",
                Layout.row("Angle", () -> Integer.toString(menu.yaw), "deg")
                        .tip(() -> java.util.List.of(Component.literal("How far the lamp is turned from the way it faces, 0 to 359 degrees. Plus turns it to the right."))),
                Layout.note("0 = straight on"),
                Layout.gap(BwrStyle.SP1),
                Layout.controls(BwrStyle.SP1).add(headingLess, 1).add(headingMore, 1));
        var tilt = Layout.card("Tilt",
                Layout.row("Angle", () -> signed(menu.pitch), "deg")
                        .tip(() -> java.util.List.of(Component.literal("How far the lamp points up (plus) or down (minus), from -75 to +30 degrees."))),
                Layout.note("0 = level"),
                Layout.gap(BwrStyle.SP1),
                Layout.controls(BwrStyle.SP1).add(tiltLess, 1).add(tiltMore, 1));
        var reach = Layout.card("Reach",
                Layout.row("Length", () -> Integer.toString(menu.reach), "blocks")
                        .tip(() -> java.util.List.of(Component.literal("How far the beam looks for something to light, 8 to 128 blocks. The usual reach is 32."))),
                Layout.note("usual reach: 32"),
                Layout.gap(BwrStyle.SP1),
                Layout.controls(BwrStyle.SP1).add(reachLess, 1).add(reachMore, 1));
        var light = Layout.card("Light",
                Layout.statusLine(() -> !menu.present ? Status.CAUTION : menu.lit ? Status.NORMAL : Status.OFF,
                        () -> !menu.present ? "Waiting for the lamp to report"
                                : menu.lit ? "Lit: " + menu.lights + " light blocks placed (at most " + FloodlightAimMenu.maxLights() + ")"
                                : "Off. It lights when it has a redstone signal.",
                        null)).compact();
        return Layout.column(BwrStyle.SP2,
                Layout.split(BwrStyle.SP2, heading, tilt, reach).stackBelow(300),
                light,
                Layout.note("Hold Shift to change by 10 degrees, or 8 blocks of reach."));
    }

    private static String signed(int degrees) {
        return degrees > 0 ? "+" + degrees : Integer.toString(degrees);
    }

    private static int amount(int one, int many) {
        return Screen.hasShiftDown() ? many : one;
    }

    private void turn(int direction) {
        menu.aimTo(menu.yaw + direction * amount(1, 10), menu.pitch);
    }

    private void tilt(int direction) {
        menu.aimTo(menu.yaw, menu.pitch + direction * amount(1, 10));
    }

    private void reach(int direction) {
        menu.reachTo(menu.reach + direction * amount(1, 8));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        menu.clientTick();
        boolean ready = menu.present;
        headingLess.active = headingMore.active = ready;
        tiltLess.active = ready && menu.pitch > BeamLampBlockEntity.PITCH_MIN;
        tiltMore.active = ready && menu.pitch < BeamLampBlockEntity.PITCH_MAX;
        reachLess.active = ready && menu.reach > BeamLampBlockEntity.REACH_MIN;
        reachMore.active = ready && menu.reach < BeamLampBlockEntity.REACH_MAX;
    }
}
