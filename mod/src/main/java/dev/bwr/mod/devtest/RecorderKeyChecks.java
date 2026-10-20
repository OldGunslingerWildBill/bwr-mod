package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelGeometry;
import dev.bwr.mod.panel.client.BindScreen;
import dev.bwr.mod.panel.client.PanelClientHooks;
import dev.bwr.mod.panel.client.RecorderClientKeys;
import dev.bwr.mod.panel.client.RecorderClientStats;
import dev.bwr.mod.panel.client.RecorderMessageScreen;
import dev.bwr.mod.panel.client.RecorderSetupScreen;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PaperlessRecorderData;
import dev.bwr.mod.panel.component.RecorderKey;
import dev.bwr.mod.panel.component.RecorderScreen;
import dev.bwr.mod.panel.component.RecorderUi;
import dev.bwr.mod.panel.model.RecorderLayout;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The key and setup stages of {@link RecorderClientCheck}: the face keys are clicked the way a player's crosshair
 * would (the player is turned toward the key, the block under the crosshair is picked, and the same
 * {@code InteractionKeyMappingTriggered} event the game raises on a right click is posted to the real handler), and
 * what each press should do is asserted on the client's UI state and on the server's recorder. Then the setup screen is
 * opened by the MENU key and by the wrench, every tab photographed, edited through its own widgets and sent, and the
 * bind screen's port pager on a 48 channel recorder is photographed.
 */
final class RecorderKeyChecks {
    private RecorderKeyChecks() {
    }

    private record Step(String name, int ticks, Consumer<Minecraft> action) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static int index;
    private static int countdown;
    private static boolean built;
    private static long marker;

    static boolean done() {
        return built && index >= STEPS.size();
    }

    private static RecorderClientCheck.Rig rig() {
        return RecorderClientCheck.rig();
    }

    private static PanelComponent client(Minecraft mc, int which) {
        return rig().c(mc, which);
    }

    private static PanelBlockEntity clientBlock(Minecraft mc, int which) {
        BlockPos pos = which == 1 ? rig().be1.getBlockPos() : rig().be2.getBlockPos();
        return (PanelBlockEntity) mc.level.getBlockEntity(pos);
    }

    private static PaperlessRecorderData server(int which) {
        return which == 1 ? rig().d1() : rig().d2();
    }

    /** Changes to the server's recorder happen on the server thread. */
    private static void onServer(Minecraft mc, Runnable change) {
        mc.getSingleplayerServer().execute(change);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError("Recorder key check: " + message);
        }
    }

    // ---- aiming and clicking

    /** Turns the player toward key {@code key} of recorder {@code which}, as a player aiming the crosshair would. */
    private static void aimAt(Minecraft mc, int which, RecorderKey key) {
        PanelBlockEntity owner = clientBlock(mc, which);
        PanelComponent comp = client(mc, which);
        RecorderLayout layout = RecorderClientStats.layout(comp);
        RecorderLayout.Box box = layout.key(key);
        double cu = layout.cellX(box.centreX());
        double cv = layout.cellY(box.centreY());
        PanelGeometry.Frame frame = PanelGeometry.frame(owner.panelType(), owner.facing());
        BlockPos pos = owner.getBlockPos();
        Vec3 target = new Vec3(pos.getX() + frame.x(cu, cv, 0.0), pos.getY() + frame.y(cu, cv, 0.0), pos.getZ() + frame.z(cu, cv, 0.0));
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 d = target.subtract(eye);
        float yaw = (float) (Math.toDegrees(Math.atan2(-d.x, d.z)));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.yHeadRot = yaw;
        mc.gameRenderer.pick(1.0f);
    }

    /** What the crosshair is on: the key the layout resolves for the picked cell (the same maths as the click handler's), or null. */
    private static RecorderKey picked(Minecraft mc, int which) {
        PanelBlockEntity owner = clientBlock(mc, which);
        PanelComponent comp = client(mc, which);
        if (!(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                || !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof PanelBlockEntity clicked)) {
            return null;
        }
        double[] cell = new double[2];
        PanelGeometry.Frame frame = PanelGeometry.frame(clicked.panelType(), clicked.facing());
        BlockPos pos = clicked.getBlockPos();
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 look = mc.player.getViewVector(1.0f);
        if (!PanelGeometry.rayToCell(frame, pos.getX(), pos.getY(), pos.getZ(), eye.x, eye.y, eye.z, look.x, look.y, look.z, cell)) {
            return null;
        }
        var group = owner.group();
        double ox = (group.columnOf(pos) - group.columnOf(owner.getBlockPos())) * PanelGeometry.GRID;
        double oy = (group.rowOf(pos) - group.rowOf(owner.getBlockPos())) * PanelGeometry.GRID;
        return RecorderClientKeys.keyAt(owner, comp, ox + cell[0] - comp.x(), oy + cell[1] - comp.y());
    }

    /** Aims at the key, checks what the crosshair is on, and right clicks it through the real handler. */
    private static void click(Minecraft mc, int which, RecorderKey key) {
        aimAt(mc, which, key);
        RecorderKey under = picked(mc, which);
        require(under == key, "aiming at " + key + " the crosshair resolved " + under);
        var event = new InputEvent.InteractionKeyMappingTriggered(1, mc.options.keyUse, InteractionHand.MAIN_HAND);
        NeoForge.EVENT_BUS.post(event);
        require(event.isCanceled(), "the click on " + key + " was not taken by the recorder's handler (isUseItem=" + event.isUseItem()
                + ", screen=" + mc.screen + ", hit=" + mc.hitResult + ", hand=" + mc.player.getMainHandItem() + ")");
        RecorderClientStats.ui(client(mc, which)).lastKeyTick = mc.level.getGameTime();
    }

    private static void shot(Minecraft mc, String name) {
        try {
            var dir = mc.gameDirectory.toPath().resolve("recorder-check");
            Files.createDirectories(dir);
            CheckShots.save(mc,dir.resolve(name + ".png"));
            LogUtils.getLogger().info("RECORDER CLIENT PASS {}", name);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void wrench(Minecraft mc, boolean give) {
        var server = mc.getSingleplayerServer();
        var uuid = mc.player.getUUID();
        server.execute(() -> server.getPlayerList().getPlayer(uuid).setItemInHand(InteractionHand.MAIN_HAND,
                give ? new ItemStack(BwrItems.PANEL_WRENCH.get()) : ItemStack.EMPTY));
    }

    private static <T extends GuiEventListener> List<T> widgets(Minecraft mc, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (GuiEventListener child : mc.screen.children()) {
            if (type.isInstance(child)) {
                out.add(type.cast(child));
            }
        }
        return out;
    }

    // ---- the script

    private static void add(String name, int wait, Consumer<Minecraft> action) {
        STEPS.add(new Step(name, wait, action));
    }

    private static void camera(int which) {
        add("camera " + which, 40, mc -> {
            double[] view = RecorderClientCheck.face(which, false);
            RecorderClientCheck.teleport(mc, view[0], view[1], 40 - view[2]);
            RecorderClientStats.showHitBoxes = false;
        });
    }

    private static void build() {
        built = true;
        // ---- DX1000
        camera(1);
        add("every key of the DX1000 resolves to itself under the crosshair, hit boxes outlined", 10, mc -> {
            RecorderClientStats.showHitBoxes = true;
            for (RecorderKey key : RecorderKey.values()) {
                if (key.softNumber() > 5 || key == RecorderKey.COVER_TAB) {
                    continue;
                }
                aimAt(mc, 1, key);
                require(picked(mc, 1) == key, "the crosshair on " + key + " resolved " + picked(mc, 1));
            }
            mc.player.setXRot(0);
            mc.player.setYRot(0);
        });
        add("screenshot hit boxes", 8, mc -> shot(mc, "keys-dx1000-hitboxes"));
        add("hit boxes off", 4, mc -> RecorderClientStats.showHitBoxes = false);
        add("show the trend", 14, mc -> onServer(mc, () -> server(1).setScreen(RecorderScreen.TREND)));
        add("DISP opens the menu", 8, mc -> {
            RecorderClientStats.ui(client(mc, 1)).mode = RecorderUi.Mode.NONE;
            click(mc, 1, RecorderKey.DISP);
            require(RecorderClientStats.ui(client(mc, 1)).mode == RecorderUi.Mode.MENU, "DISP did not open the menu");
        });
        add("menu on screen", 20, mc -> shot(mc, "keys-dx1000-menu-open"));
        add("DOWN then DISP chooses DIGITAL", 12, mc -> {
            click(mc, 1, RecorderKey.DOWN);
            require(RecorderClientStats.ui(client(mc, 1)).cursor == 1, "DOWN did not move the menu cursor: mode " + RecorderClientStats.ui(client(mc, 1)).mode
                    + " cursor " + RecorderClientStats.ui(client(mc, 1)).cursor + " lastKey " + RecorderClientStats.ui(client(mc, 1)).lastKey
                    + " screen " + ((PaperlessRecorderData) client(mc, 1).data()).screen);
            click(mc, 1, RecorderKey.DISP);
        });
        add("check digital", 12, mc -> {
            require(server(1).screen == RecorderScreen.DIGITAL, "the server shows " + server(1).screen);
            require(((PaperlessRecorderData) client(mc, 1).data()).screen == RecorderScreen.DIGITAL, "the client did not get DIGITAL");
            require(RecorderClientStats.ui(client(mc, 1)).mode == RecorderUi.Mode.NONE, "the menu stayed open");
        });
        add("RIGHT changes the group", 12, mc -> click(mc, 1, RecorderKey.RIGHT));
        add("check group", 4, mc -> {
            require(server(1).currentGroup == 1, "RIGHT: the server's group is " + server(1).currentGroup);
            click(mc, 1, RecorderKey.LEFT);
        });
        add("check group back", 12, mc -> {
            require(server(1).currentGroup == 0, "LEFT: the server's group is " + server(1).currentGroup);
            click(mc, 1, RecorderKey.DOWN);
        });
        add("check bar", 12, mc -> {
            require(server(1).screen == RecorderScreen.BAR, "DOWN steps digital to bar: " + server(1).screen);
            click(mc, 1, RecorderKey.UP);
        });
        add("check digital again", 12, mc -> {
            require(server(1).screen == RecorderScreen.DIGITAL, "UP steps bar to digital: " + server(1).screen);
            click(mc, 1, RecorderKey.UP);
        });
        add("trend again", 12, mc -> {
            require(server(1).screen == RecorderScreen.TREND, "UP steps digital to trend: " + server(1).screen);
            marker = server(1).logSequence();
            click(mc, 1, RecorderKey.FUNC);
            require(RecorderClientStats.ui(client(mc, 1)).mode == RecorderUi.Mode.FUNC, "FUNC did not open the strip");
        });
        add("FUNC strip on screen", 20, mc -> shot(mc, "keys-dx1000-func-strip"));
        add("soft key 1 is AlarmACK", 14, mc -> {
            require(server(1).unacknowledgedCount() > 0, "no alarm waits to be acknowledged");
            click(mc, 1, RecorderKey.SOFT1);
        });
        add("check ack", 4, mc -> {
            boolean ack = server(1).logEntries().stream().anyMatch(e -> e.seq() >= marker && e.kind() == PaperlessRecorderData.LogEntry.ACK);
            require(ack, "soft key 1 under FUNC did not acknowledge: log " + server(1).logEntries());
            marker = server(1).messageSequence();
            click(mc, 1, RecorderKey.FUNC);
            click(mc, 1, RecorderKey.SOFT2);
            require(RecorderClientStats.ui(client(mc, 1)).mode == RecorderUi.Mode.MESSAGE, "Message did not open the message keys");
        });
        add("message keys on screen", 20, mc -> shot(mc, "keys-dx1000-message-strip"));
        add("soft key 1 writes message 1", 14, mc -> click(mc, 1, RecorderKey.SOFT1));
        add("check message", 4, mc -> {
            require(server(1).messageSequence() > marker && server(1).messageEntries().get(server(1).messageEntries().size() - 1).text().equals("MESSAGE 1"),
                    "message 1 was not written: " + server(1).messageEntries());
            click(mc, 1, RecorderKey.STOP);
        });
        add("check stop", 12, mc -> {
            require(!server(1).running, "STOP did not stop memory sampling");
            click(mc, 1, RecorderKey.START);
        });
        add("check start", 12, mc -> {
            require(server(1).running, "START did not start it");
            click(mc, 1, RecorderKey.FUNC);
            click(mc, 1, RecorderKey.SOFT3);
        });
        add("free message box", 14, mc -> {
            require(mc.screen instanceof RecorderMessageScreen, "Free message did not open the text box: " + mc.screen);
            marker = server(1).messageSequence();
            ((RecorderMessageScreen) mc.screen).submit("HELLO KEYS");
        });
        add("check free message", 12, mc -> {
            var messages = server(1).messageEntries();
            require(server(1).messageSequence() > marker && messages.get(messages.size() - 1).text().equals("HELLO KEYS"), "free message: " + messages);
            wrench(mc, false);
        });
        add("MENU key opens the setup screen", 12, mc -> {
            click(mc, 1, RecorderKey.MENU);
            require(mc.screen instanceof RecorderSetupScreen, "MENU did not open the setup screen: " + mc.screen);
        });
        add("setup without a wrench", 25, mc -> {
            shot(mc, "setup-dx1000-no-wrench");
            mc.setScreen(null);
            wrench(mc, true);
        });
        // ---- setup screen through the wrench
        add("open setup with the wrench", 30, mc -> PanelClientHooks.openConfig(rig().be1.getBlockPos(), rig().s1.id()));
        add("channels tab", 14, mc -> {
            require(mc.screen instanceof RecorderSetupScreen, "the wrench did not open the setup screen");
            shot(mc, "setup-dx1000-channels");
            ((RecorderSetupScreen) mc.screen).showTab("alarms");
        });
        add("alarms tab", 14, mc -> {
            shot(mc, "setup-dx1000-alarms");
            ((RecorderSetupScreen) mc.screen).showTab("groups");
        });
        add("groups tab", 14, mc -> {
            shot(mc, "setup-dx1000-groups");
            ((RecorderSetupScreen) mc.screen).showTab("display");
        });
        add("display tab", 14, mc -> {
            shot(mc, "setup-dx1000-display");
            ((RecorderSetupScreen) mc.screen).showTab("messages");
        });
        add("messages tab", 14, mc -> {
            shot(mc, "setup-dx1000-messages");
            ((RecorderSetupScreen) mc.screen).showTab("channels");
        });
        add("edit through the widgets", 14, mc -> {
            RecorderSetupScreen screen = (RecorderSetupScreen) mc.screen;
            List<EditBox> boxes = widgets(mc, EditBox.class);
            boxes.get(0).setValue("EDITED TAG");   // tag
            boxes.get(4).setValue("250");           // span high
            screen.showTab("alarms");
        });
        add("edit alarm", 10, mc -> {
            RecorderSetupScreen screen = (RecorderSetupScreen) mc.screen;
            List<CycleButton> cycles = widgets(mc, CycleButton.class);
            cycles.get(0).onPress();                // alarm 1 type: off -> H
            List<EditBox> boxes = widgets(mc, EditBox.class);
            boxes.get(0).setValue("12.5");          // alarm 1 value
            require(screen.working().channel(0).alarms[0].type.equals("H") && screen.working().channel(0).alarms[0].value == 12.5,
                    "the widgets did not edit the working copy");
            require(screen.changes().contains("Ch01Tag") && screen.changes().contains("Ch01Hi") && screen.changes().contains("Ch01Al1Type")
                    && screen.changes().size() == 4, "the screen would send exactly the four changed settings: " + screen.changes());
            require(screen.send(), "nothing to send");
            mc.setScreen(null);
        });
        add("check server got the edit", 14, mc -> {
            PaperlessRecorderData d = server(1);
            require(d.channel(0).tag.equals("EDITED TAG") && d.channel(0).hi == 250.0 && d.channel(0).alarms[0].type.equals("H")
                    && d.channel(0).alarms[0].value == 12.5, "the server's recorder after Done: " + d.channel(0).tag + " " + d.channel(0).hi
                    + " " + d.channel(0).alarms[0].type);
            PaperlessRecorderData c = (PaperlessRecorderData) client(mc, 1).data();
            require(c.channel(0).tag.equals("EDITED TAG"), "the client did not receive the new settings");
            onServer(mc, () -> {
                d.channel(0).tag = "MSIV A POS";
                d.channel(0).hi = 100;
                d.channel(0).alarms[0].type = "off";
                rig().be1.syncFull();
            });
            wrench(mc, false); // keys are clicked with an empty hand: the wrench opens screens instead
        });
        // ---- DX2000
        camera(2);
        add("start from a closed cover", 40, mc -> onServer(mc, () -> {
            if (server(2).coverOpen) {
                server(2).toggleCover();
            }
        }));
        add("DX2000 cover closed: START swallowed, DISP answers", 12, mc -> {
            PaperlessRecorderData d = (PaperlessRecorderData) client(mc, 2).data();
            require(!d.coverOpen, "the cover starts closed");
            RecorderClientStats.showHitBoxes = true;
            PanelBlockEntity owner = clientBlock(mc, 2);
            RecorderLayout layout = RecorderClientStats.layout(client(mc, 2));
            RecorderLayout.Box start = layout.key(RecorderKey.START);
            require(RecorderClientKeys.keyAt(owner, client(mc, 2), layout.cellX(start.centreX()), layout.cellY(start.centreY())) == null,
                    "START under the closed cover must not answer");
            RecorderClientStats.ui(client(mc, 2)).mode = RecorderUi.Mode.NONE;
            click(mc, 2, RecorderKey.DISP);
            require(RecorderClientStats.ui(client(mc, 2)).mode == RecorderUi.Mode.MENU, "DISP is not behind the cover: mode "
                    + RecorderClientStats.ui(client(mc, 2)).mode + " lastKey " + RecorderClientStats.ui(client(mc, 2)).lastKey + " cover "
                    + ((PaperlessRecorderData) client(mc, 2).data()).coverOpen + " style " + ((PaperlessRecorderData) client(mc, 2).data()).style);
        });
        add("DX2000 menu with the cover closed", 25, mc -> {
            shot(mc, "keys-dx2000-hitboxes-cover-closed");
            RecorderClientStats.ui(client(mc, 2)).mode = RecorderUi.Mode.NONE;
            RecorderClientStats.showHitBoxes = false;
            click(mc, 2, RecorderKey.COVER_TAB);
        });
        add("cover tab opened it", 40, mc -> {
            require(server(2).coverOpen, "the cover tab did not open the cover on the server");
            RecorderClientStats.showHitBoxes = true;
        });
        add("every key of the DX2000 resolves with the cover open", 10, mc -> {
            for (RecorderKey key : RecorderKey.values()) {
                aimAt(mc, 2, key);
                require(picked(mc, 2) == key, "DX2000: the crosshair on " + key + " resolved " + picked(mc, 2));
            }
            click(mc, 2, RecorderKey.STOP);
        });
        add("cover open, hit boxes", 14, mc -> {
            shot(mc, "keys-dx2000-hitboxes-cover-open");
            require(!server(2).running, "STOP behind the open cover did not stop sampling");
            RecorderClientStats.showHitBoxes = false;
            click(mc, 2, RecorderKey.START);
        });
        add("start again, close the cover", 12, mc -> {
            require(server(2).running, "START did not start it");
            click(mc, 2, RecorderKey.COVER_TAB);
        });
        add("closed again", 40, mc -> require(!server(2).coverOpen, "the tab did not close the cover"));
        // ---- DX2000 setup screen and the bind screen pager
        add("give the wrench", 10, mc -> wrench(mc, true));
        add("DX2000 setup", 30, mc -> PanelClientHooks.openConfig(rig().be2.getBlockPos(), rig().s2.id()));
        add("DX2000 channel 45", 14, mc -> {
            RecorderSetupScreen screen = (RecorderSetupScreen) mc.screen;
            screen.selectChannel(44);
        });
        add("DX2000 channel list on screen", 14, mc -> {
            shot(mc, "setup-dx2000-channel45");
            ((RecorderSetupScreen) mc.screen).showTab("display");
        });
        add("DX2000 display tab", 14, mc -> {
            shot(mc, "setup-dx2000-display");
            ((RecorderSetupScreen) mc.screen).selectGroup(34);
            ((RecorderSetupScreen) mc.screen).showTab("groups");
        });
        add("DX2000 groups", 14, mc -> {
            shot(mc, "setup-dx2000-group35");
            mc.setScreen(null);
        });
        add("bind screen with 48 ports", 12, mc -> {
            var payload = new PanelPayloads.LinkerScreen(rig().be2.getBlockPos(), rig().s2.id(), PanelPayloads.LINK_OK, new BlockPos(48, 112, 46),
                    "block.bwr.msiv", List.of(new PanelPayloads.SignalEntry("position", "Valve position", "fraction open")),
                    List.of());
            PanelClientHooks.openBind(payload);
            require(mc.screen instanceof BindScreen, "the bind screen did not open");
        });
        add("bind page 1", 14, mc -> {
            shot(mc, "bind-dx2000-page1");
            Button next = null;
            for (Button b : widgets(mc, Button.class)) {
                if (b.getMessage().getString().equals(">")) {
                    next = b;
                }
            }
            require(next != null && next.active, "the pager has no next page for 48 ports");
            for (int i = 0; i < 3; i++) {
                next.onPress();
                next = null;
                for (Button b : widgets(mc, Button.class)) {
                    if (b.getMessage().getString().equals(">")) {
                        next = b;
                    }
                }
            }
        });
        add("bind page 4", 14, mc -> {
            shot(mc, "bind-dx2000-page4");
            mc.setScreen(null);
            wrench(mc, false);
        });
    }

    static void step(Minecraft mc, MinecraftServer server) {
        if (!built) {
            build();
        }
        if (countdown > 0) {
            countdown--;
            return;
        }
        if (index >= STEPS.size()) {
            return;
        }
        Step step = STEPS.get(index++);
        step.action().accept(mc);
        countdown = step.ticks();
        LogUtils.getLogger().info("RECORDER KEYS step {}: {}", index, step.name());
    }
}
