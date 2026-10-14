package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementWidget;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Real advancement UI in an opt-in disposable world; stripped with all devtest classes. */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class AdvancementClientCheck {
    private static final List<String> IDS = List.of("root", "first_criticality", "power_to_grid",
            "survive_scram", "refuel", "seal_containment", "build_control_room",
            "lower_head_failure", "melt_through");
    private static final String[] NAMES = {"01-hidden-accidents", "02-power-title",
            "03-lower-head-revealed", "04-complete-tree", "05-melt-through-title"};
    private static boolean started, requested;
    private static volatile boolean granted;
    private static volatile Throwable failure;
    private static volatile int[] fullTreeBounds;
    private static int age, joined, stage, shown;
    private static CheckScreen screen;

    private AdvancementClientCheck() { }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) throws Exception {
        if (!Boolean.getBoolean("bwr.advancementClientCheck")) return;
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) throw new IllegalStateException("Advancement client fixture failed", failure);
        if (++age > 3000) throw new IllegalStateException("Advancement client check timed out at " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.guiScale().set(2);
            mc.options.hideGui = false;
            mc.options.enableVsync().set(false);
            mc.createWorldOpenFlows().createFreshLevel("bwr-advancement-check-" + System.currentTimeMillis(),
                    new LevelSettings("Advancement client check", GameType.CREATIVE, false,
                            Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false),
                    r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                            .createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getConnection() == null
                || mc.getSingleplayerServer() == null || ++joined < 60) return;
        if (!requested) {
            requested = true;
            granted = false;
            shown = 0;
            screen = null;
            mc.setScreen(null);
            int selected = stage;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                try {
                    var player = server.getPlayerList().getPlayer(uuid);
                    if (player == null) throw new AssertionError("Client fixture player missing");
                    player.serverLevel().setDayTime(6000);
                    int[] bounds = emptyBounds();
                    var icons = new HashSet<String>();
                    for (String id : IDS) {
                        var entry = server.getAdvancements().get(BwrMod.id("milestones/" + id));
                        if (entry == null) throw new AssertionError("Missing advancement " + id);
                        var display = entry.value().display().orElseThrow();
                        require(!display.getIcon().isEmpty(), "empty server icon " + id);
                        require(icons.add(BuiltInRegistries.ITEM.getKey(display.getIcon().getItem()).toString()),
                                "duplicate icon item " + id);
                        // Server positions include hidden nodes before the client receives them.
                        include(bounds, Mth.floor(display.getX() * 28), Mth.floor(display.getY() * 27));
                        boolean award = selected >= 3 || id.equals("root") || id.equals("first_criticality")
                                || id.equals("power_to_grid") || id.equals("seal_containment")
                                || id.equals("build_control_room")
                                || selected >= 2 && id.equals("lower_head_failure");
                        if (!award) continue;
                        player.getAdvancements().award(entry, "achieved");
                    }
                    require(icons.size() == IDS.size(), "all nine server icons must be distinct");
                    fullTreeBounds = bounds;
                    granted = true;
                } catch (Throwable problem) { failure = problem; }
            });
            return;
        }
        if (!granted || ++shown < 50) return;
        var client = mc.getConnection().getAdvancements();
        var root = client.get(BwrMod.id("milestones/root"));
        if (root == null) return;
        if (screen == null) {
            screen = new CheckScreen(client);
            mc.setScreen(screen);
            client.setSelectedTab(root, true); // init selects the first available tab; select BWR afterwards.
            shown = 50;
            return;
        }
        if (mc.screen != screen) throw new AssertionError("Advancement screen unexpectedly closed");
        if (shown == 70) {
            requireFits(fullTreeBounds, "full tree at stage " + stage);
            require(fullTreeBounds[2] - fullTreeBounds[0] > fullTreeBounds[3] - fullTreeBounds[1],
                    "full tree must be wider than tall");
            screen.assertFits();
            require(screen.visible("lower_head_failure") == (stage >= 2), "lower-head visibility");
            require(screen.visible("melt_through") == (stage >= 3), "melt-through visibility");
            var icons = new HashSet<String>();
            for (String id : IDS) {
                var node = client.getTree().get(BwrMod.id("milestones/" + id));
                if (node == null) continue;
                var display = node.advancement().display().orElseThrow();
                require(!display.getIcon().isEmpty(), "empty icon " + id);
                require(icons.add(BuiltInRegistries.ITEM.getKey(display.getIcon().getItem()).toString()),
                        "duplicate client icon item " + id);
                require(!display.getTitle().getString().startsWith("advancements.bwr."), "unresolved title " + id);
                require(!display.getDescription().getString().startsWith("advancements.bwr."), "unresolved description " + id);
            }
            if (stage >= 3) {
                require(icons.size() == IDS.size(), "completed tree must contain all nine distinct icons");
                require(Arrays.equals(screen.bounds(), fullTreeBounds), "completed tree bounds differ from server");
            }
            switch (stage) {
                case 1 -> screen.focus("power_to_grid");
                case 2 -> screen.focus("lower_head_failure");
                case 4 -> screen.focus("melt_through");
                default -> { }
            }
        }
        if (shown < 130) return;
        var output = mc.gameDirectory.toPath().resolve("advancement-check");
        Files.createDirectories(output);
        try (var capture = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            capture.writeToFile(output.resolve(NAMES[stage] + ".png"));
        }
        LogUtils.getLogger().info("ADVANCEMENT CLIENT PASS {}: full tree {}x{} fits {}x{} without scrolling; hidden-state, distinct icons and translations checked",
                NAMES[stage], fullTreeBounds[2] - fullTreeBounds[0], fullTreeBounds[3] - fullTreeBounds[1],
                AdvancementsScreen.WINDOW_INSIDE_WIDTH, AdvancementsScreen.WINDOW_INSIDE_HEIGHT);
        if (++stage == NAMES.length) {
            LogUtils.getLogger().info("ADVANCEMENT CLIENT COMPLETE: {} captures", NAMES.length);
            mc.stop();
        } else requested = false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError("Advancement client: " + message);
    }

    private static int[] emptyBounds() {
        return new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
    }

    private static void include(int[] bounds, int x, int y) {
        bounds[0] = Math.min(bounds[0], x); bounds[1] = Math.min(bounds[1], y);
        bounds[2] = Math.max(bounds[2], x + 28); bounds[3] = Math.max(bounds[3], y + 27);
    }

    private static void requireFits(int[] bounds, String label) {
        require(bounds != null && bounds[0] < bounds[2] && bounds[1] < bounds[3], label + " has no bounds");
        require(bounds[2] - bounds[0] <= AdvancementsScreen.WINDOW_INSIDE_WIDTH,
                label + " exceeds vanilla viewport width: " + Arrays.toString(bounds));
        require(bounds[3] - bounds[1] <= AdvancementsScreen.WINDOW_INSIDE_HEIGHT,
                label + " exceeds vanilla viewport height: " + Arrays.toString(bounds));
    }

    /** Native screen/rendering, with deterministic hover coordinates for repeatable captures. */
    private static final class CheckScreen extends AdvancementsScreen {
        private final ClientAdvancements client;
        private int hoverX = -1, hoverY = -1;

        CheckScreen(ClientAdvancements client) { super(client); this.client = client; }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, hoverX, hoverY, partialTick);
        }

        AdvancementWidget widget(String id) {
            var node = client.getTree().get(BwrMod.id("milestones/" + id));
            return node == null ? null : getAdvancementWidget(node);
        }

        boolean visible(String id) {
            var widget = widget(id);
            return widget != null && widget.isMouseOver(0, 0, widget.getX() + 13, widget.getY() + 13);
        }

        int[] bounds() {
            int[] bounds = emptyBounds();
            for (String id : IDS) {
                var widget = widget(id);
                if (widget == null) continue;
                include(bounds, widget.getX(), widget.getY());
            }
            return bounds;
        }

        void assertFits() {
            int[] bounds = bounds();
            requireFits(bounds, "client tree at stage " + stage);
            // Match AdvancementTab's initial native centering; never pan the screen.
            int scrollX = 117 - (bounds[2] + bounds[0]) / 2;
            int scrollY = 56 - (bounds[3] + bounds[1]) / 2;
            for (String id : IDS) {
                var widget = widget(id);
                if (widget == null) continue;
                int x = scrollX + widget.getX(), y = scrollY + widget.getY();
                require(x >= 0 && y >= 0 && x + 28 <= WINDOW_INSIDE_WIDTH && y + 27 <= WINDOW_INSIDE_HEIGHT,
                        "centered widget clipped at stage " + stage + ": " + id);
            }
        }

        void focus(String id) {
            var widget = widget(id);
            require(widget != null && visible(id), "hover target unavailable " + id);
            int[] bounds = bounds();
            int scrollX = 117 - (bounds[2] + bounds[0]) / 2;
            int scrollY = 56 - (bounds[3] + bounds[1]) / 2;
            requireFits(bounds, "hover tree");
            require(widget.isMouseOver(scrollX, scrollY, scrollX + widget.getX() + 13, scrollY + widget.getY() + 13),
                    "native hover target not hit " + id);
            hoverX = (width - WINDOW_WIDTH) / 2 + 9 + scrollX + widget.getX() + 13;
            hoverY = (height - WINDOW_HEIGHT) / 2 + 18 + scrollY + widget.getY() + 13;
        }
    }
}
