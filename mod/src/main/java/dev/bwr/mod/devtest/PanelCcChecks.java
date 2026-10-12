package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dev.bwr.mod.devtest.PanelWiringTests.TestIoData;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.NameplateData;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.wiring.CcSource;
import dev.bwr.mod.peripheral.LivePeripheral;
import dev.bwr.mod.peripheral.PanelPeripheral;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code bwr_panel} against the real CC:Tweaked classes. References CC types, so callers must be behind
 * {@code BwrMod.isComputerCraftPresent()}; without CC the CC test in {@link PanelWiringTests} just succeeds.
 */
final class PanelCcChecks {
    private PanelCcChecks() {
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, message);
    }

    private static boolean throwsLua(Runnable action) {
        try {
            action.run();
            return false;
        } catch (LuaFailure expected) {
            return true;
        }
    }

    private interface LuaCall {
        void run() throws LuaException;
    }

    private static final class LuaFailure extends RuntimeException {
        LuaFailure(Throwable cause) {
            super(cause);
        }
    }

    private static Runnable lua(LuaCall call) {
        return () -> {
            try {
                call.run();
            } catch (LuaException e) {
                throw new LuaFailure(e);
            }
        };
    }

    /**
     * Drives a real character LCD and a real graphic display through {@code bwr_panel}: sizes, the drawing calls,
     * and that every change is a queued content patch with no full-state resend.
     */
    static void drawDisplays(GameTestHelper helper, PanelBlockEntity block, PanelBlockEntity graphicBlock,
                             PanelComponent lcd, PanelComponent graphic) {
        try {
            PanelPeripheral panel = new PanelPeripheral(block);
            Object[] lcdSize = panel.getSize(lcd.label());
            check(helper, lcdSize.length == 2 && lcdSize[0].equals(16) && lcdSize[1].equals(2), "cc lcd size 16 x 2");
            Object[] gSize = panel.getSize(graphic.label());
            check(helper, gSize.length == 2 && gSize[0].equals(32) && gSize[1].equals(32), "cc graphic size 32 x 32");

            check(helper, panel.write(lcd.label(), 1, 2, "CC HELLO"), "cc write");
            check(helper, ((dev.bwr.mod.panel.component.LcdData) lcd.data()).line(1).equals("  CC HELLO"),
                    "the LCD row holds the text");
            check(helper, panel.setPixel(graphic.label(), 3, 4, 5), "cc setPixel");
            check(helper, panel.blit(graphic.label(), new String[] {"0123", "4567"}), "cc blit");
            var graphicData = (dev.bwr.mod.panel.component.GraphicDisplayData) graphic.data();
            check(helper, graphicData.getPixel(0, 0) == 0 && graphicData.getPixel(3, 1) == 7, "blit landed at the top-left");
            check(helper, block.contentPending() && graphicBlock.contentPending()
                            && !dev.bwr.mod.panel.PanelSync.displaySyncPending(block)
                            && !dev.bwr.mod.panel.PanelSync.displaySyncPending(graphicBlock),
                    "cc drawing queues content patches and no full-state resend");
            check(helper, graphicData.drainPatch() != null, "the graphic display has a patch to send");
            check(helper, panel.clear(graphic.label(), 2) && graphicData.getPixel(31, 31) == 2, "cc clear");
            check(helper, panel.clear(lcd.label(), 0)
                    && ((dev.bwr.mod.panel.component.LcdData) lcd.data()).line(1).isEmpty(), "cc clear on the LCD");
            check(helper, !dev.bwr.mod.panel.PanelSync.displaySyncPending(block)
                    && !dev.bwr.mod.panel.PanelSync.displaySyncPending(graphicBlock), "still no full-state resend");
        } catch (LuaException e) {
            helper.fail("unexpected LuaException: " + e.getMessage());
        }
    }

    static void run(GameTestHelper helper, ServerLevel level, PanelBlockEntity block, PanelComponent io, PanelComponent plate) {
        try {
            exercise(helper, level, block, io, plate);
        } catch (LuaException e) {
            helper.fail("unexpected LuaException: " + e.getMessage());
        }
    }

    private static void exercise(GameTestHelper helper, ServerLevel level, PanelBlockEntity block, PanelComponent io,
                                 PanelComponent plate) throws LuaException {
        PanelPeripheral panel = new PanelPeripheral(block);
        String label = io.label();
        TestIoData data = (TestIoData) io.data();

        // set / get / awaiting
        check(helper, ((CcSource) io.source(0)).awaiting() && Double.isNaN(io.input(0)), "awaiting CC until the program sets it");
        check(helper, panel.set(label, 5), "set accepted");
        check(helper, io.input(0) == 5.0, "the input took the value: " + io.input(0));
        Map<String, Object> got = panel.get(label);
        check(helper, ((Map<?, ?>) got.get("inputs")).get("a").equals(5.0), "get reports it: " + got);
        check(helper, throwsLua(lua(() -> panel.setPort(label, "b", 1))), "an input not wired to CC refuses set");
        check(helper, throwsLua(lua(() -> panel.setPort(label, "nope", 1))), "an unknown port");
        check(helper, throwsLua(lua(() -> panel.set("NO-SUCH", 1))), "an unknown label");
        check(helper, throwsLua(lua(() -> panel.set(label, Double.NaN))), "a non-finite value");

        // getInputs
        block.setOutput(io, 1, 4.0);
        Map<?, ?> controls = panel.getInputs();
        check(helper, controls.size() == 1 && ((Map<?, ?>) controls.get(label)).get("y").equals(4.0)
                && !controls.containsKey(plate.label()), "getInputs lists controls with their outputs: " + controls);

        // setText
        check(helper, panel.setText(plate.label(), "HELLO") && ((NameplateData) plate.data()).text.equals("HELLO"),
                "setText on a nameplate");
        check(helper, !panel.setText(plate.label(), "HELLO"), "setting the same text reports no change");
        check(helper, panel.setText(label, "abc") && data.text.equals("abc"), "setText on any PanelTextTarget");

        // Drawing, through PanelDrawTarget
        Object[] size = panel.getSize(label);
        check(helper, size.length == 2 && size[0].equals(8) && size[1].equals(4), "getSize");
        check(helper, panel.setPixel(label, 1, 1, 3) && data.pixels[9] == 3, "setPixel");
        check(helper, !panel.setPixel(label, 99, 1, 3), "an out-of-range pixel reports false");
        check(helper, panel.fill(label, 0, 0, 2, 2, 5) && data.pixels[0] == 5 && data.pixels[9] == 5, "fill");
        check(helper, panel.drawText(label, 1, 2, "hi", 7) && data.drawn.equals("1,2:hi:7"), "drawText");
        check(helper, panel.write(label, 1, 2, "ok") && data.written.equals("1,2:ok"), "write");
        check(helper, panel.setPalette(label, 3, 0x123456) && data.paletteIndex == 3 && data.paletteRgb == 0x123456, "setPalette");
        check(helper, panel.blit(label, new String[] {"01", "10"}) && data.blit.length == 2, "blit");
        check(helper, panel.clear(label, 2) && data.clearedTo == 2 && data.pixels[5] == 2, "clear");
        check(helper, throwsLua(lua(() -> panel.getSize(plate.label()))), "a nameplate is not a drawable display");

        // Lua tables of strings arrive as String[]
        Map<Object, Object> table = new HashMap<>();
        table.put(1.0, "0123");
        table.put(2.0, "4567");
        String[] rows = LivePeripheral.tableStrings(new ObjectArguments(table).getTableUnsafe(0));
        check(helper, rows.length == 2 && rows[0].equals("0123") && rows[1].equals("4567"), "blit rows convert from a Lua table");

        // The input event, through LivePeripheral's attach/detach like a real computer.
        List<Object[]> events = new ArrayList<>();
        IComputerAccess computer = (IComputerAccess) Proxy.newProxyInstance(PanelCcChecks.class.getClassLoader(),
                new Class<?>[] {IComputerAccess.class}, (proxy, method, args) -> {
                    if (method.getName().equals("queueEvent")) {
                        Object[] event = new Object[1 + ((Object[]) args[1]).length];
                        event[0] = args[0];
                        System.arraycopy((Object[]) args[1], 0, event, 1, event.length - 1);
                        events.add(event);
                    } else if (method.getName().equals("getAttachmentName")) {
                        return "top";
                    } else if (method.getName().equals("getID")) {
                        return 1;
                    } else if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    } else if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    return null;
                });
        PanelPeripheral.install();
        int before = PanelPeripheral.attachedCount();
        LivePeripheral live = new LivePeripheral(level, block.getBlockPos(), block.getBlockState().getBlock(), "bwr_panel",
                PanelPeripheral.class, () -> new PanelPeripheral(block));
        live.attach(computer);
        try {
            check(helper, PanelPeripheral.attachedCount() == before + 1, "attach registers the computer");
            block.setOutput(io, 0, 3.0);
            check(helper, events.size() == 1 && events.get(0)[0].equals("bwr_panel_input") && events.get(0)[1].equals("top")
                            && events.get(0)[2].equals(label) && events.get(0)[3].equals(3.0)
                            && events.get(0)[4].equals("x"),
                    "a player operating a control raises bwr_panel_input(name, label, value, port): " + events.size());
            block.setOutput(io, 0, 3.0);
            check(helper, events.size() == 1, "no event when nothing changed");
            io.addSink(0, new dev.bwr.mod.panel.wiring.RedstoneSink(1));
            block.setOutput(io, 0, 2.0);
            check(helper, events.size() == 2, "events fire whatever the output is wired to");
        } finally {
            live.detach(computer);
        }
        check(helper, PanelPeripheral.attachedCount() == before, "detach unregisters the computer");
        block.setOutput(io, 0, 9.0);
        check(helper, events.size() == 2, "a detached computer gets no events");
        check(helper, panel.list().containsKey(label) && panel.list().containsKey(plate.label()), "list still works");
    }
}
