package dev.bwr.mod.radiation.client;

import dev.bwr.core.radiation.DoseModel;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.radiation.RadiationReadout;
import dev.bwr.mod.radiation.SurveyMeterItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * The client half of the survey meter: a small readout while the meter is held, and the Geiger click.
 *
 * <p>Both run from the latest {@link RadiationReadout} the server pushed (four a second, only to the holder); neither
 * asks the server for anything. The click is the vanilla comparator click at a high pitch, played as a random process
 * whose mean rate is {@link DoseModel#geigerClicksPerSecond}: at most a couple of clicks in a tick, 40 a second at the
 * very most. With no reading (the meter put away, a stalled server) there is no readout and no click.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class RadiationClient {
    private static final RandomSource RANDOM = RandomSource.create();

    private RadiationClient() {
    }

    @SubscribeEvent
    public static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(BwrMod.id("survey_meter_readout"), (graphics, delta) -> drawReadout(graphics));
    }

    /** Whether the local player holds a meter and a current reading is in. */
    public static boolean showing() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && SurveyMeterItem.heldBy(mc.player) && RadiationReadout.fresh();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused() || !showing()) {
            return;
        }
        double expected = DoseModel.geigerClicksPerSecond(RadiationReadout.doseRate()) / 20.0;
        int clicks = 0;
        for (double left = expected; left > 0.0 && clicks < 3; left -= 1.0) {
            if (RANDOM.nextDouble() < Math.min(1.0, left)) {
                clicks++;
            }
        }
        for (int i = 0; i < clicks; i++) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.COMPARATOR_CLICK,
                    1.7f + RANDOM.nextFloat() * 0.35f, 0.35f));
        }
    }

    /** The colour of a dose rate: green for background-like, then yellow, orange and red. */
    public static int colour(double msvPerHour) {
        if (msvPerHour < 0.01) {
            return 0xFF6BE070;
        }
        if (msvPerHour < 1.0) {
            return 0xFFF2D13B;
        }
        if (msvPerHour < 100.0) {
            return 0xFFF08A24;
        }
        return 0xFFF0392B;
    }

    private static void drawReadout(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (!showing() || mc.options.hideGui) {
            return;
        }
        double rate = RadiationReadout.doseRate();
        String number = DoseModel.formatRate(rate);
        Component title = Component.translatable("hud.bwr.survey_meter.title");
        Component dose = Component.translatable("hud.bwr.survey_meter.dose", DoseModel.formatDose(RadiationReadout.personalDose()));
        var font = mc.font;
        int width = Math.max(font.width(number) * 2, Math.max(font.width(title), font.width(dose))) + 12;
        int x = 8;
        int y = graphics.guiHeight() / 2 - 24;
        graphics.fill(x, y, x + width, y + 46, 0xB0101418);
        graphics.renderOutline(x, y, width, 46, 0xFF5A646E);
        graphics.drawString(font, title, x + 6, y + 4, 0xFFB8C0C8, false);
        graphics.pose().pushPose();
        graphics.pose().translate(x + 6, y + 15, 0);
        graphics.pose().scale(2.0f, 2.0f, 1.0f);
        graphics.drawString(font, number, 0, 0, colour(rate), false);
        graphics.pose().popPose();
        graphics.drawString(font, dose, x + 6, y + 35, 0xFFB8C0C8, false);
    }
}
