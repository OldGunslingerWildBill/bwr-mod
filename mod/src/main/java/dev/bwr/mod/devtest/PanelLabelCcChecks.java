package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dev.bwr.mod.devtest.PanelLabelTests.Fixture;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.peripheral.LivePeripheral;
import dev.bwr.mod.peripheral.PanelPeripheral;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** CC-touching parts of {@link PanelLabelTests}; only loaded when CC is present. */
final class PanelLabelCcChecks {
    private PanelLabelCcChecks() {
    }

    /** A mock computer attachment: its own id and attachment name, queued events recorded as {@code [name, args...]}. */
    private static IComputerAccess computer(int id, String name, List<Object[]> events) {
        return (IComputerAccess) Proxy.newProxyInstance(PanelLabelCcChecks.class.getClassLoader(),
                new Class<?>[] {IComputerAccess.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "queueEvent" -> {
                            Object[] rest = (Object[]) args[1];
                            Object[] event = new Object[1 + rest.length];
                            event[0] = args[0];
                            System.arraycopy(rest, 0, event, 1, rest.length);
                            events.add(event);
                        }
                        case "getID" -> {
                            return id;
                        }
                        case "getAttachmentName" -> {
                            return name;
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        case "hashCode" -> {
                            return System.identityHashCode(proxy);
                        }
                        default -> {
                        }
                    }
                    return null;
                });
    }

    private static LivePeripheral live(Fixture f, PanelBlockEntity block) {
        return new LivePeripheral(f.level, block.getBlockPos(), block.getBlockState().getBlock(), "bwr_panel",
                PanelPeripheral.class, () -> new PanelPeripheral(block));
    }

    static void events(Fixture f) {
        var helper = f.helper;
        f.panel(0);
        f.panel(1);
        PanelBlockEntity block0 = f.be(0);
        PanelBlockEntity block1 = f.be(1);
        PanelComponent toggle = f.toggle(1, 0, 0);
        PanelComponent other = f.toggle(0, 0, 0);
        PanelPeripheral.install();
        int before = PanelPeripheral.attachedCount();

        // Computer 7 is attached to both blocks of the one group, under two names; computer 8 only to block 1.
        List<Object[]> seven = new ArrayList<>();
        List<Object[]> eight = new ArrayList<>();
        IComputerAccess sevenLeft = computer(7, "left", seven);
        IComputerAccess sevenModem = computer(7, "bwr_panel_3", seven);
        IComputerAccess eightRight = computer(8, "right", eight);
        LivePeripheral live0 = live(f, block0);
        LivePeripheral live1 = live(f, block1);
        live0.attach(sevenLeft);
        live1.attach(sevenModem);
        live1.attach(eightRight);
        try {
            helper.assertTrue(PanelPeripheral.attachedCount() == before + 3, "three attachments registered");
            block1.setOutput(toggle, 0, 1.0);
            helper.assertTrue(seven.size() == 1, "one event for a computer attached twice: " + seven.size());
            Object[] event = seven.get(0);
            helper.assertTrue(event.length == 5 && event[0].equals("bwr_panel_input") && event[1].equals("left")
                            && event[2].equals(toggle.label()) && event[3].equals(1.0) && event[4].equals("state"),
                    "bwr_panel_input(name, label, value, port) with the first attached name");
            helper.assertTrue(eight.size() == 1 && eight.get(0)[1].equals("right"),
                    "another computer gets its own name: " + eight.size());

            // Operating a control on the other block is the same group: still one event each.
            block0.setOutput(other, 0, 1.0);
            helper.assertTrue(seven.size() == 2 && eight.size() == 2, "one event per computer per change");
            helper.assertTrue(seven.get(1)[1].equals("left") && seven.get(1)[2].equals(other.label()),
                    "the same name and the other label");

            // When the first attachment goes, the next one supplies the name.
            live0.detach(sevenLeft);
            block1.setOutput(toggle, 0, 0.0);
            helper.assertTrue(seven.size() == 3 && seven.get(2)[1].equals("bwr_panel_3"),
                    "falls back to the next attached name: " + seven.size());
        } finally {
            live0.detach(sevenLeft);
            live1.detach(sevenModem);
            live1.detach(eightRight);
        }
        helper.assertTrue(PanelPeripheral.attachedCount() == before, "everything detached");
    }

    static void duplicates(Fixture f) {
        var helper = f.helper;
        f.panel(0);
        f.panel(1);
        PanelBlockEntity block0 = f.be(0);
        PanelComponent toggle = f.toggle(1, 0, 0); // lower X: first in canonical order
        PanelComponent lamp = f.lamp(0, 8, 8);
        PanelComponent plate = f.lamp(0, 12, 12);
        // Forced through the back door: a duplicate the merge rule did not see.
        toggle.setLabel("DUP");
        lamp.setLabel("DUP");
        plate.setLabel("Z");
        PanelPeripheral panel = new PanelPeripheral(block0);
        try {
            Map<String, Object> list = panel.list();
            helper.assertTrue(list.size() == 3 && list.containsKey("DUP") && list.containsKey("DUP#2")
                    && list.containsKey("Z"), "list keeps every component: " + list.keySet());
            helper.assertTrue(panel.get("DUP").get("type").toString().endsWith("toggle_switch"),
                    "the plain label is the first component by position: " + panel.get("DUP"));
            helper.assertTrue(panel.get("DUP#2").get("type").toString().endsWith("lamp"), "the second is reachable as DUP#2");
            helper.assertTrue(panel.get("DUP").get("type").equals(new PanelPeripheral(f.be(1)).get("DUP").get("type")),
                    "the lookup does not depend on which block you ask through");
        } catch (LuaException e) {
            helper.fail("unexpected LuaException: " + e.getMessage());
        }
    }
}
