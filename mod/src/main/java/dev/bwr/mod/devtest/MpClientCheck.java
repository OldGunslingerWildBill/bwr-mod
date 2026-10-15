package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.CoreDisplayData;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.panel.wiring.WiringOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The client half of the multiplayer check (C3): one of two real game clients joined to a real dedicated server. It
 * does what the server's step files say ({@link MpServerCheck}), and ONLY through what a real player sends: the
 * mod's panel payloads (the same ones the click, wrench and linker screens send), inventory clicks, the selected hotbar
 * slot, the movement keys, and the connect and disconnect a player does from the menu. It never touches a server object.
 * After each step it writes what it sees on its own client level (the full core display and matrix snapshots, the switch,
 * the meters, the linker binding, the panel's labels, its inventory) and takes the step's screenshot.
 *
 * <p>Commands (one step line, separated by {@code ;}): {@code wait N}, {@code matrix ROD}, {@code click LABEL},
 * {@code hold LABEL N} / {@code holdnr LABEL N} (hold, repeating the press as the client does, with / without the
 * release at the end), {@code switch LABEL V}, {@code bind LABEL KEY}, {@code place ITEM COL ROW CX CY},
 * {@code remove LABEL}, {@code rename LABEL NEW}, {@code renameat COL ROW CX CY NEW}, {@code walk fwd|back N},
 * {@code disconnect}, {@code reconnect}, {@code expectrestart}, {@code finish}. Inert without
 * {@code -Dbwr.mpCheck=client}; stripped from the release jar.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class MpClientCheck {
    private static final boolean ON = "client".equals(System.getProperty("bwr.mpCheck"));
    private static final String WHO = System.getProperty("bwr.mp.name", "A");
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();

    private static int age, inWorld, retry, waitTicks, lastSeq, stepSeq, holdLeft, holdTick, walkLeft, finishLeft = -1;
    private static boolean started, holdRelease, offlineOnPurpose, wasConnected, running, finishAfterAck, walkBack;
    private static String holdLabel = "", shotName = "", failure = "";
    private static final ArrayDeque<String> QUEUE = new ArrayDeque<>();
    private static BlockPos wall, reactor;
    private static int cols, rows;

    private MpClientCheck() {
    }

    private static void log(String message, Object... args) {
        LOG.info("MP CLIENT " + WHO + ": " + message, args);
    }

    private static boolean connected(Minecraft mc) {
        return mc.level != null && mc.player != null && mc.getConnection() != null && mc.getSingleplayerServer() == null;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        if (!ON) return;
        Minecraft mc = Minecraft.getInstance();
        age++;
        if (!started) {
            if (mc.getOverlay() == null && mc.screen != null) {
                started = true;
                mc.options.pauseOnLostFocus = false;
                mc.options.enableVsync().set(false);
                mc.options.framerateLimit().set(40);
                mc.options.renderDistance().set(8);
                mc.options.fov().set(100);
                mc.options.guiScale().set(2);
                mc.options.hideGui = true;
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                RenderEnvironment.log("multiplayer-" + WHO);
            }
            return;
        }
        if (finishLeft >= 0) {
            if (--finishLeft == 0) {
                log("finished, closing the client");
                mc.stop();
            }
            return;
        }
        mc.getToasts().clear();
        boolean in = connected(mc);
        if (in) {
            if (!wasConnected) log("connected to {}", mc.getCurrentServer() == null ? "?" : mc.getCurrentServer().ip);
            wasConnected = true;
            inWorld++;
        } else {
            if (wasConnected) log("is no longer connected ({})", mc.screen == null ? "no screen" : mc.screen.getClass().getSimpleName());
            wasConnected = false;
            inWorld = 0;
            // A connection lost without the check asking for it (the server stopping) is joined again, as a player would.
            if (!offlineOnPurpose && !(mc.screen instanceof ConnectScreen) && mc.getOverlay() == null && age > 300 && ++retry % 150 == 0) {
                connect(mc);
            }
        }
        if (in && wall == null) {
            Map<String, String> fixture = MpProtocol.read(MpProtocol.dir().resolve("fixture.properties"));
            if (fixture != null) {
                wall = pos(fixture.get("wall"));
                reactor = pos(fixture.get("reactor"));
                cols = Integer.parseInt(fixture.get("cols"));
                rows = Integer.parseInt(fixture.get("rows"));
            }
        }
        boolean ready = in && wall != null && inWorld >= 60 && find(mc, "FCD-1") != null && !(mc.screen instanceof TitleScreen);
        if (walkLeft > 0 && in) {
            mc.options.keyUp.setDown(!walkBack);
            mc.options.keyDown.setDown(walkBack);
            if (--walkLeft == 0) {
                mc.options.keyUp.setDown(false);
                mc.options.keyDown.setDown(false);
            }
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (holdLeft > 0 && ready) {
            PanelBlockEntityAndComponent target = find(mc, holdLabel);
            if (target != null && holdTick++ % 5 == 0) operate(target, OperateAction.PRESS, clickValue(target));
            if (--holdLeft == 0 && holdRelease && target != null) operate(target, OperateAction.RELEASE, 0.0);
            return;
        }
        if (!running) {
            if (age % 3 != 0) return;
            Map<String, String> step = MpProtocol.read(MpProtocol.step(lastSeq + 1));
            if (step == null) return;
            stepSeq = ++lastSeq;
            shotName = step.getOrDefault("shot", "");
            String line = step.getOrDefault(WHO + ".cmd", "");
            QUEUE.clear();
            for (String command : line.split(";")) if (!command.isBlank()) QUEUE.add(command.trim());
            running = true;
            failure = "";
            log("step {} '{}': {}", stepSeq, step.get("name"), line.isEmpty() ? "(just look)" : line);
        }
        if (!QUEUE.isEmpty()) {
            String next = QUEUE.peek();
            boolean offlineSafe = next.startsWith("wait") || next.startsWith("reconnect") || next.startsWith("expectrestart") || next.startsWith("finish");
            if (!ready && !offlineSafe) return;
            try {
                run(mc, QUEUE.poll());
            } catch (RuntimeException e) {
                LOG.error("MP CLIENT " + WHO + " command failed", e);
                QUEUE.clear();
                failure = e.toString();
            }
            return;
        }
        if (in && !ready) return; // joined but not settled: wait for the panel before looking
        if (!shotName.isEmpty()) screenshot(mc, shotName);
        writeAck(mc, in);
        running = false;
        if (finishAfterAck) finishLeft = 40;
    }

    private static BlockPos pos(String text) {
        String[] p = text.split(",");
        return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    private static BlockPos wallAt(int col, int row) {
        return wall.relative(Direction.WEST, col).below(row);
    }

    // ---- looking at the panel (the client's own replicated block entities)

    private record PanelBlockEntityAndComponent(PanelBlockEntity owner, PanelComponent component) {
    }

    private static PanelBlockEntityAndComponent find(Minecraft mc, String label) {
        if (wall == null || mc.level == null) return null;
        for (int row = 0; row < rows; row++)
            for (int col = 0; col < cols; col++)
                if (mc.level.getBlockEntity(wallAt(col, row)) instanceof PanelBlockEntity be)
                    for (PanelComponent c : be.owned()) if (label.equals(c.label())) return new PanelBlockEntityAndComponent(be, c);
        return null;
    }

    private static PanelBlockEntityAndComponent need(Minecraft mc, String label) {
        PanelBlockEntityAndComponent found = find(mc, label);
        if (found == null) throw new IllegalStateException("no component " + label + " on this client's panel");
        return found;
    }

    private static double clickValue(PanelBlockEntityAndComponent t) {
        return t.component().type().clickValue(t.component(), t.component().w() / 2.0, t.component().h() / 2.0);
    }

    private static void operate(PanelBlockEntityAndComponent t, OperateAction action, double value) {
        PacketDistributor.sendToServer(new PanelPayloads.Operate(t.owner().getBlockPos(), t.component().id(), action.ordinal(), value));
    }

    // ---- commands

    private static void run(Minecraft mc, String command) {
        String[] w = command.split("\\s+");
        switch (w[0]) {
            case "wait" -> waitTicks = Integer.parseInt(w[1]);
            case "matrix" -> operate(need(mc, "RSM-1"), OperateAction.PRESS, Double.parseDouble(w[1]));
            case "click" -> {
                PanelBlockEntityAndComponent t = need(mc, w[1]);
                operate(t, OperateAction.PRESS, clickValue(t));
                if (t.component().type().holdable()) {
                    QUEUE.addFirst("release " + w[1]);
                    QUEUE.addFirst("wait 2");
                }
            }
            case "release" -> operate(need(mc, w[1]), OperateAction.RELEASE, 0.0);
            case "hold", "holdnr" -> {
                holdLabel = w[1];
                holdLeft = Integer.parseInt(w[2]);
                holdTick = 0;
                holdRelease = w[0].equals("hold");
            }
            case "switch" -> operate(need(mc, w[1]), OperateAction.PRESS, Double.parseDouble(w[2]));
            case "bind" -> {
                QUEUE.addFirst("dobind " + w[1] + " " + w[2]);
                QUEUE.addFirst("wait 6");
                QUEUE.addFirst("linkeropen " + w[1]);
                QUEUE.addFirst("wait 3");
                QUEUE.addFirst("selectitem panel_linker");
            }
            case "linkeropen" -> {
                PanelBlockEntityAndComponent t = need(mc, w[1]);
                PacketDistributor.sendToServer(new PanelPayloads.LinkerOpen(t.owner().getBlockPos(), t.component().id()));
            }
            case "dobind" -> {
                PanelBlockEntityAndComponent t = need(mc, w[1]);
                PacketDistributor.sendToServer(new PanelPayloads.Bind(t.owner().getBlockPos(), t.component().id(), false, 0,
                        WiringOps.BindKind.PLANT.ordinal(), reactor, w[2], BlockPos.ZERO, 0, 0, 1.0));
                if (mc.screen != null) mc.setScreen(null); // the binding screen the server opened
            }
            case "place" -> {
                QUEUE.addFirst("doplace " + w[2] + " " + w[3] + " " + w[4] + " " + w[5]);
                QUEUE.addFirst("wait 3");
                QUEUE.addFirst("selectitem " + w[1]);
            }
            case "doplace" -> PacketDistributor.sendToServer(new PanelPayloads.Place(wallAt(Integer.parseInt(w[1]), Integer.parseInt(w[2])),
                    Integer.parseInt(w[3]), Integer.parseInt(w[4]), false));
            case "remove" -> {
                QUEUE.addFirst("doremove " + w[1]);
                QUEUE.addFirst("wait 3");
                QUEUE.addFirst("selectitem panel_wrench");
            }
            case "doremove" -> {
                PanelBlockEntityAndComponent t = find(mc, w[1]);
                if (t == null) {
                    log("{} is already gone from my panel; nothing to remove", w[1]); // the other player was first
                } else {
                    PacketDistributor.sendToServer(new PanelPayloads.Remove(t.owner().getBlockPos(), t.component().id()));
                }
            }
            case "rename" -> {
                QUEUE.addFirst("dorename " + w[1] + " " + w[2]);
                QUEUE.addFirst("wait 3");
                QUEUE.addFirst("selectitem panel_wrench");
            }
            case "renameat" -> {
                QUEUE.addFirst("dorenameat " + w[1] + " " + w[2] + " " + w[3] + " " + w[4] + " " + w[5]);
                QUEUE.addFirst("wait 3");
                QUEUE.addFirst("selectitem panel_wrench");
            }
            case "dorename" -> {
                PanelBlockEntityAndComponent t = need(mc, w[1]);
                CompoundTag edit = new CompoundTag();
                edit.putString("Label", w[2]);
                PacketDistributor.sendToServer(new PanelPayloads.Configure(t.owner().getBlockPos(), t.component().id(), edit));
            }
            case "dorenameat" -> {
                BlockPos block = wallAt(Integer.parseInt(w[1]), Integer.parseInt(w[2]));
                if (!(mc.level.getBlockEntity(block) instanceof PanelBlockEntity be)) throw new IllegalStateException("no panel block at " + block);
                PanelBlockEntity.Claim claim = be.claimAt(Integer.parseInt(w[3]), Integer.parseInt(w[4]));
                if (claim == null) throw new IllegalStateException("nothing at cell " + w[3] + "," + w[4] + " of " + block + " on this client");
                CompoundTag edit = new CompoundTag();
                edit.putString("Label", w[5]);
                PacketDistributor.sendToServer(new PanelPayloads.Configure(claim.owner(), claim.id(), edit));
            }
            case "selectitem" -> selectItem(mc, w[1]);
            case "walk" -> {
                walkBack = w[1].equals("back");
                walkLeft = Integer.parseInt(w[2]);
            }
            case "disconnect" -> {
                log("disconnecting (as the pause menu's Disconnect button does)");
                offlineOnPurpose = true;
                // What the pause menu's Disconnect button does: the level closes the connection (the server sees it at once),
                // then the client goes back to the title screen.
                mc.level.disconnect();
                mc.disconnect(new TitleScreen());
            }
            case "reconnect" -> {
                offlineOnPurpose = false;
                connect(mc);
                QUEUE.addFirst("reconnected");
            }
            case "reconnected" -> {
                // Not done until the join has completed and settled.
                if (!connected(mc) || inWorld < 100) QUEUE.addFirst("reconnected");
            }
            case "expectrestart" -> {
                log("the server is about to stop; will reconnect on my own");
                offlineOnPurpose = false;
            }
            case "finish" -> finishAfterAck = true;
            default -> throw new IllegalArgumentException("unknown command " + command);
        }
    }

    private static void connect(Minecraft mc) {
        log("connecting to 127.0.0.1:{}", MpProtocol.PORT);
        String address = "127.0.0.1:" + MpProtocol.PORT;
        ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
                new ServerData("bwr-mp", address, ServerData.Type.OTHER), false, null);
    }

    /** Puts the first stack of this bwr item in the selected hotbar slot, with the inventory clicks a player makes. */
    private static void selectItem(Minecraft mc, String path) {
        var inventory = mc.player.getInventory();
        int at = -1;
        for (int i = 0; i < inventory.getContainerSize() && at < 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(BwrMod.id(path))) at = i;
        }
        if (at < 0) throw new IllegalStateException("no " + path + " in this inventory");
        if (at < 9) {
            inventory.selected = at;
        } else {
            mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, at, inventory.selected, ClickType.SWAP, mc.player);
        }
    }

    // ---- what this client sees

    private static void writeAck(Minecraft mc, boolean in) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("online", String.valueOf(in));
        m.put("failure", failure);
        if (in && wall != null) {
            PanelBlockEntityAndComponent fcd = find(mc, "FCD-1"), matrix = find(mc, "RSM-1"), sw = find(mc, "SW-1"),
                    dial1 = find(mc, "DIAL-1"), dial2 = find(mc, "DIAL-2"), dial3 = find(mc, "DIAL-3");
            m.put("fcd", fcd == null ? "" : MpProtocol.digest(((CoreDisplayData) fcd.component().data()).snapshot()));
            m.put("matrix", matrix == null ? "" : MpProtocol.digest(((CoreDisplayData) matrix.component().data()).snapshot()));
            m.put("selectedSeen", fcd == null ? "-1" : String.valueOf(((CoreDisplayData) fcd.component().data()).snapshot().selected()));
            m.put("switch", sw == null ? "NaN" : String.valueOf(sw.component().output(0)));
            m.put("dial1", dial1 == null ? "NaN" : String.valueOf(dial1.component().input(0)));
            m.put("dial3", dial3 == null ? "NaN" : String.valueOf(dial3.component().input(0)));
            m.put("dial2key", dial2 != null && dial2.component().source(0) instanceof PlantSource p ? p.key() : "");
            List<String> labels = new ArrayList<>();
            for (int row = 0; row < rows; row++)
                for (int col = 0; col < cols; col++)
                    if (mc.level.getBlockEntity(wallAt(col, row)) instanceof PanelBlockEntity be)
                        for (PanelComponent c : be.owned()) labels.add(c.label());
            Collections.sort(labels);
            m.put("labels", String.join(",", labels));
            Map<String, Integer> items = new TreeMap<>();
            for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals(BwrMod.MOD_ID))
                    items.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), stack.getCount(), Integer::sum);
            }
            m.put("items", items.toString());
        }
        MpProtocol.write(MpProtocol.ack(stepSeq, WHO), m);
        log("step {} done: online={} selected={} switch={} dial2={} items={} failure='{}'", stepSeq, in, m.get("selectedSeen"), m.get("switch"),
                m.get("dial2key"), m.get("items"), failure);
    }

    private static void screenshot(Minecraft mc, String name) {
        if (!connected(mc)) return;
        try {
            var directory = mc.gameDirectory.toPath().resolve("mp-check");
            Files.createDirectories(directory);
            try (var shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                shot.writeToFile(directory.resolve(name + "-" + WHO + ".png"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("screenshot failed", e);
        }
    }
}
