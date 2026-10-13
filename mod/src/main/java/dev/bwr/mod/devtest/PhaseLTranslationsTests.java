package dev.bwr.mod.devtest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.gui.ReactorPanelMenu;
import dev.bwr.mod.panel.PanelMessages;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.reactor.ValidationResult;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exercises the actual chat path and language selection after network serialization. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PhaseLTranslationsTests {
    private PhaseLTranslationsTests() { }

    @GameTest(template = "empty", batch = "l2_translations", timeoutTicks = 200)
    public static void chatCarriesKeysAndArgumentsToTheReceivingLanguage(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = new BlockPos(67_900, 230, 67_900);
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        List<Component> received = new ArrayList<>();
        Language original = Language.getInstance();
        try {
            level.setChunkForced(cx, cz, true);
            level.getChunk(cx, cz);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(pos, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
            var player = new FakePlayer(level, new GameProfile(UUID.fromString("240fb30c-e874-4a5e-af5a-dcbe2f073bbd"), "TranslationChat")) {
                @Override public void displayClientMessage(Component message, boolean overlay) { received.add(message); }
            };
            level.getBlockState(pos).useWithoutItem(level, player,
                    new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            helper.assertTrue(!received.isEmpty(), "containment click sent no report");
            helper.assertTrue(received.stream().allMatch(c -> c.getContents() instanceof TranslatableContents),
                    "chat was flattened to literal English before reaching the player");
            Component leaking = received.getFirst();
            String key = ((TranslatableContents) leaking.getContents()).getKey();
            Component linked = BwrText.tr("status.bwr.containment_controller.linked_reactors_suppression_pools",
                    "Linked reactors: %s; suppression pools: %s", 2, 3);
            Component fault = new ValidationResult().fail(pos, linked).components().getFirst();
            String json = Component.Serializer.toJson(fault, level.registryAccess());
            Component decoded = Component.Serializer.fromJson(json, level.registryAccess());
            helper.assertTrue(decoded != null && BwrText.english(decoded).equals(
                    "FAULT: Linked reactors: 2; suppression pools: 3 at 67900, 230, 67900"),
                    "nested status or position arguments changed during serialization");
            Map<String, String> translations = Map.of(key, "PRUEBA: fuga",
                    "status.bwr.containment_controller.linked_reactors_suppression_pools", "PISCINAS %2$s / REACTORES %1$s",
                    "status.bwr.validation.fault", "FALLO: %s",
                    "status.bwr.validation.at", "%1$s @ %2$s/%3$s/%4$s");
            Language.inject(new Language() {
                @Override public String getOrDefault(String id, String fallback) { return translations.getOrDefault(id, original.getOrDefault(id, fallback)); }
                @Override public boolean has(String id) { return translations.containsKey(id) || original.has(id); }
                @Override public boolean isDefaultRightToLeft() { return false; }
                @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return original.getVisualOrder(text); }
            });
            helper.assertTrue(leaking.getString().equals("PRUEBA: fuga"), "chat ignored the receiving language");
            helper.assertTrue(decoded.getString().equals("FALLO: PISCINAS 3 / REACTORES 2 @ 67900/230/67900"),
                    "translated nested arguments or translator argument ordering were lost");
            var controller = (ContainmentControllerBlockEntity) level.getBlockEntity(pos);
            helper.assertTrue(controller.statusLines().getFirst().equals("Containment leaking"),
                    "legacy status/CC diagnostics changed with the client's language");

            // Exercise the actual menu snapshot writer/reader as well as the component codec.
            level.setBlock(pos, BwrBlocks.REACTOR_CONTROLLER.get().defaultBlockState(), 3);
            var sending = new ReactorPanelMenu(1, player.getInventory(), pos) {
                byte[] snapshot() {
                    var buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                    try {
                        writeSnapshot(buffer);
                        byte[] bytes = new byte[buffer.readableBytes()];
                        buffer.readBytes(bytes);
                        return bytes;
                    } finally { buffer.release(); }
                }
            };
            var receiving = new ReactorPanelMenu(1, player.getInventory(), pos);
            receiving.acceptSnapshot(sending.snapshot());
            helper.assertTrue(!receiving.formed && !receiving.statusLines.isEmpty()
                            && receiving.statusLines.stream().allMatch(c -> c.getContents() instanceof TranslatableContents),
                    "reactor menu snapshot lost translated status components");
            helper.assertTrue(BwrText.english(receiving.statusLines).contains("Reactor not formed."),
                    "reactor menu snapshot changed the diagnostic text");
            helper.succeed();
        } finally {
            Language.inject(original);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            level.setChunkForced(cx, cz, false);
        }
    }

    @GameTest(template = "empty", batch = "l2_translations", timeoutTicks = 100)
    public static void panelChatAndEveryWiringStatusHaveTranslations(GameTestHelper helper) throws Exception {
        JsonObject english;
        try (var stream = PhaseLTranslationsTests.class.getResourceAsStream("/assets/bwr/lang/en_us.json")) {
            helper.assertTrue(stream != null, "English language resource missing");
            english = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (var result : WiringOps.Result.values()) {
            Component message = PanelMessages.wiringRefused(result);
            checkKeys(helper, english, message);
            helper.assertTrue(!BwrText.english(message).contains("message.bwr."), "panel wiring status leaked a translation key");
        }
        for (String key : List.of("message.bwr.panel.key_required", "message.bwr.panel.footprint",
                "message.bwr.panel_linker.no_signals", "message.bwr.panel_linker.linked", "message.bwr.panel_linker.cleared")) {
            helper.assertTrue(english.has(key) && !english.get(key).getAsString().isBlank(), "missing panel chat translation " + key);
        }
        helper.succeed();
    }

    private static void checkKeys(GameTestHelper helper, JsonObject english, Component message) {
        helper.assertTrue(message.getContents() instanceof TranslatableContents, "panel status contains literal English");
        var text = (TranslatableContents) message.getContents();
        helper.assertTrue(english.has(text.getKey()), "missing translation " + text.getKey());
        helper.assertTrue(english.get(text.getKey()).getAsString().equals(text.getFallback()), "English fallback drift " + text.getKey());
        for (Object arg : text.getArgs()) if (arg instanceof Component nested) checkKeys(helper, english, nested);
    }
}
