package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;

/** Opt-in integration check: a real CC:Tweaked computer runs Lua against a real adjacent bwr_panel. */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class PanelCcRuntimeCheck {
    private static final BlockPos PANEL = new BlockPos(10, 110, 10);
    private static final BlockPos COMPUTER = PANEL.west();
    private static final String TOKEN = Long.toUnsignedString(System.nanoTime(), 36);
    private static final String SCRIPT = """
            local function trace(s)
              local f = assert(fs.open('phase-i-trace', 'w')); f.write(s); f.close()
            end
            trace('boot')
            local p = peripheral.find('bwr_panel')
            trace('peripheral=' .. tostring(p))
            assert(p, 'no bwr_panel peripheral on the real computer')
            local list = p.list()
            trace('list=' .. textutils.serialize(list))
            assert(list['TEST-NAME'] and list['TEST-GFX'] and list['TEST-BTN'], 'missing panel components')
            assert(p.get('TEST-BTN'), 'get(TEST-BTN) failed')
            trace('get')
            assert(p.setText('TEST-NAME', 'CC READY'), 'setText failed')
            trace('text')
            assert(p.setPixel('TEST-GFX', 2, 2, 14), 'setPixel failed')
            trace('pixel')
            local f = assert(fs.open('phase-i-ready', 'w')); f.write('__TOKEN__'); f.close()
            local event, name, label, value, port = os.pullEvent('bwr_panel_input')
            assert(event == 'bwr_panel_input', 'wrong event')
            assert(name == peripheral.getName(p), 'first event argument is not this panel peripheral name')
            assert(label == 'TEST-BTN', 'wrong control label: ' .. tostring(label))
            assert(type(value) == 'number' and value > 0, 'wrong control value')
            assert(type(port) == 'string', 'missing output port')
            f = assert(fs.open('phase-i-single', 'w')); f.write('__TOKEN__'); f.close()
            local names, count = {}, 0
            repeat
              sleep(0.05)
              names, count = {}, 0
              for _, side in ipairs(peripheral.getNames()) do
                if peripheral.getType(side) == 'bwr_panel' then names[side] = true; count = count + 1 end
              end
            until count >= 2
            f = assert(fs.open('phase-i-ready2', 'w')); f.write('__TOKEN__'); f.close()
            local event2, name2, label2, value2, port2 = os.pullEvent('bwr_panel_input')
            assert(event2 == 'bwr_panel_input' and names[name2], 'second event has wrong peripheral name')
            assert(label2 == 'SECOND-BTN' and type(value2) == 'number' and value2 > 0,
              'second panel input was wrong')
            local timer = os.startTimer(0.5)
            while true do
              local kind, value = os.pullEvent()
              assert(kind ~= 'bwr_panel_input', 'one computer received a duplicate input event')
              if kind == 'timer' and value == timer then break end
            end
            local r = assert(fs.open('phase-i-result', 'w'))
            r.write('PASS|__TOKEN__|' .. name .. '|' .. name2); r.close()
            """;
    private static int age, stage, buttonId, secondId;
    // Keep the opt-in harness safe to load without CC:Tweaked. It names CC implementation classes only by reflection.
    private static Object mount;
    private static Object computer;

    private PanelCcRuntimeCheck() {}

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("bwr.panelCcCheck")) return;
        var server = event.getServer();
        if (++age > 700) {
            logTerminal();
            throw new IllegalStateException("Real CC panel check timed out at stage " + stage);
        }
        if (stage == 0) {
            if (age < 20) return;
            try {
                var level = server.overworld();
                FixtureChunks.force(level, PANEL.getX() >> 4, PANEL.getZ() >> 4, true);
                level.getChunk(PANEL.getX() >> 4, PANEL.getZ() >> 4);
                for (int y = 109; y <= 112; y++) for (int x = 8; x <= 11; x++)
                    level.setBlock(new BlockPos(x, y, 10), Blocks.AIR.defaultBlockState(), 3);
                for (int dy = 0; dy < 3; dy++)
                    level.setBlock(PANEL.above(dy), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                            .setValue(PanelBlock.FACING, Direction.NORTH), 3);
                var name = PanelOps.placeDirect(level, PANEL, 1, 1, ComponentTypes.NAMEPLATE, Footprint.of(12, 3));
                var button = PanelOps.placeDirect(level, PANEL.above(), 2, 2,
                        ComponentTypes.PUSHBUTTON, Footprint.of(3, 3));
                var graphic = PanelOps.placeDirect(level, PANEL.above(2), 0, 0,
                        ComponentTypes.GRAPHIC_DISPLAY, Footprint.of(16, 16));
                if (name == null || button == null || graphic == null)
                    throw new AssertionError("CC panel fixture components did not fit");
                name.setLabel("TEST-NAME");
                button.setLabel("TEST-BTN");
                graphic.setLabel("TEST-GFX");
                buttonId = button.id();
                var ccBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("computercraft", "computer_normal"));
                if (ccBlock == Blocks.AIR) throw new AssertionError("CC computer block is absent");
                level.setBlock(COMPUTER.above(), ccBlock.defaultBlockState(), 3);
                Object be = level.getBlockEntity(COMPUTER.above());
                if (be == null) throw new AssertionError("CC computer block entity was not created");
                computer = be.getClass().getMethod("createServerComputer").invoke(be);
                mount = computer.getClass().getMethod("createRootMount").invoke(computer);
                write("startup.lua", SCRIPT.replace("__TOKEN__", TOKEN));
                computer.getClass().getMethod("turnOn").invoke(computer);
                LogUtils.getLogger().info("PANEL CC CHECK: real computer started next to panel");
                stage = 1;
            } catch (Exception failure) {
                throw new IllegalStateException("Could not start real CC panel check", failure);
            }
            return;
        }
        try {
            if (stage == 1 && TOKEN.equals(read("phase-i-ready"))) {
                var level = server.overworld();
                var player = FakePlayerFactory.get(level, new GameProfile(
                        UUID.nameUUIDFromBytes("panel-real-cc-check".getBytes(StandardCharsets.UTF_8)), "panel-real-cc"));
                player.setGameMode(GameType.CREATIVE);
                player.setPos(9.5, 111, 9.5);
                if (!PanelOps.operate(player, PANEL.above(), buttonId, OperateAction.PRESS, 1))
                    throw new AssertionError("Could not operate real panel button");
                LogUtils.getLogger().info("PANEL CC CHECK: Lua ready, button pressed");
                stage = 2;
            }
            if (stage == 2 && TOKEN.equals(read("phase-i-single"))) {
                var level = server.overworld();
                for (BlockPos pos : new BlockPos[] {COMPUTER.above(2), COMPUTER.west().above(2),
                        COMPUTER.west().above()})
                    level.setBlock(pos, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                            .setValue(PanelBlock.FACING, Direction.NORTH), 3);
                var second = PanelOps.placeDirect(level, COMPUTER.west().above(), 2, 2,
                        ComponentTypes.PUSHBUTTON, Footprint.of(3, 3));
                if (second == null) throw new AssertionError("Second CC panel button did not fit");
                second.setLabel("SECOND-BTN");
                secondId = second.id();
                LogUtils.getLogger().info("PANEL CC CHECK: second panel attachment added");
                stage = 3;
            }
            if (stage == 3 && TOKEN.equals(read("phase-i-ready2"))) {
                var level = server.overworld();
                var player = FakePlayerFactory.get(level, new GameProfile(
                        UUID.nameUUIDFromBytes("panel-real-cc-check".getBytes(StandardCharsets.UTF_8)), "panel-real-cc"));
                player.setPos(9.5, 111, 9.5);
                if (!PanelOps.operate(player, COMPUTER.west().above(), secondId, OperateAction.PRESS, 1))
                    throw new AssertionError("Could not operate second panel button");
                LogUtils.getLogger().info("PANEL CC CHECK: Lua saw multiple attachments, second button pressed");
                stage = 4;
            }
            if (stage == 4) {
                String result = read("phase-i-result");
                if (result != null && result.startsWith("PASS|" + TOKEN + "|")) {
                    if (result.split("\\|").length != 4)
                        throw new AssertionError("Lua result was " + result);
                    LogUtils.getLogger().info("PANEL CC CHECK PASS: {}", result);
                    FixtureChunks.force(server.overworld(), PANEL.getX() >> 4, PANEL.getZ() >> 4, false);
                    server.halt(false);
                    stage = 3;
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Real CC computer filesystem failed", failure);
        }
    }

    private static SeekableByteChannel open(String path, Set<StandardOpenOption> options) throws Exception {
        try {
            return (SeekableByteChannel) mount.getClass().getMethod("openFile", String.class, Set.class)
                    .invoke(mount, path, options);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof IOException io) throw io;
            throw failure;
        }
    }

    private static void write(String path, String value) throws Exception {
        try (var channel = open(path, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING))) {
            channel.write(ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static String read(String path) throws Exception {
        if (!(boolean) mount.getClass().getMethod("exists", String.class).invoke(mount, path)) return null;
        try (var channel = open(path, Set.of(StandardOpenOption.READ))) {
            ByteBuffer buffer = ByteBuffer.allocate(2048);
            channel.read(buffer);
            buffer.flip();
            return StandardCharsets.UTF_8.decode(buffer).toString();
        }
    }

    private static void logTerminal() {
        if (computer == null) return;
        try {
            var type = computer.getClass();
            Object state = type.getMethod("getTerminalState").invoke(computer);
            Object terminal = state.getClass().getMethod("create").invoke(state);
            int height = (int) terminal.getClass().getMethod("getHeight").invoke(terminal);
            LogUtils.getLogger().error("PANEL CC TERMINAL state={}", type.getMethod("getState").invoke(computer));
            for (int y = 0; y < height; y++) {
                Object line = terminal.getClass().getMethod("getLine", int.class).invoke(terminal, y);
                LogUtils.getLogger().error("PANEL CC TERMINAL {}: {}", y, line);
            }
        } catch (ReflectiveOperationException failure) {
            LogUtils.getLogger().error("PANEL CC TERMINAL read failed", failure);
        }
    }
}
