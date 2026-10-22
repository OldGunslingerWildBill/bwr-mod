package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.ObjectArguments;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.PlantComputerData;
import dev.bwr.mod.panel.plant.PlantLayout;
import dev.bwr.mod.panel.plant.PlantLimits;
import dev.bwr.mod.peripheral.LivePeripheral;
import dev.bwr.mod.peripheral.PanelPeripheral;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The plant computer calls of {@code bwr_panel} (also {@code bwr_plant_computer}) against the real CC:Tweaked classes. Reaches CC
 * types, so it is only called when {@code BwrMod.isComputerCraftPresent()}.
 */
final class PlantComputerCcChecks {
    private PlantComputerCcChecks() {
    }

    private interface Call {
        void run() throws LuaException;
    }

    private static String lua(Call call) {
        try {
            call.run();
            return null;
        } catch (LuaException e) {
            return e.getMessage();
        }
    }

    private static Map<String, Object> spec(Object... keyValues) {
        return PlantComputerTests.spec(keyValues);
    }

    static void run(GameTestHelper helper, ServerLevel level, PanelBlockEntity block, PanelComponent component) {
        try {
            exercise(helper, level, block, component);
        } catch (LuaException e) {
            helper.fail("unexpected LuaException: " + e.getMessage());
        }
    }

    private static void exercise(GameTestHelper helper, ServerLevel level, PanelBlockEntity block, PanelComponent component) throws LuaException {
        PanelPeripheral panel = new PanelPeripheral(block);
        String label = component.label();
        PlantComputerData data = (PlantComputerData) component.data();

        // finding it: the panel answers to bwr_plant_computer too, and plantList names the displays
        helper.assertTrue(panel.getAdditionalTypes().equals(Set.of("bwr_plant_computer")), "a panel also has the type bwr_plant_computer");
        LivePeripheral live = new LivePeripheral(level, block.getBlockPos(), block.getBlockState().getBlock(), "bwr_panel",
                PanelPeripheral.class, () -> new PanelPeripheral(block));
        helper.assertTrue(live.getType().equals("bwr_panel") && live.getAdditionalTypes().equals(Set.of("bwr_plant_computer")),
                "the live peripheral keeps bwr_panel as its type and adds the plant computer");
        LivePeripheral machine = new LivePeripheral(level, block.getBlockPos(), block.getBlockState().getBlock(), "bwr_reactor",
                PanelPeripheral.class, () -> new PanelPeripheral(block));
        helper.assertTrue(machine.getAdditionalTypes().isEmpty(), "and no other peripheral does");
        List<String> methods = List.of(live.getMethodNames());
        for (String name : new String[] {"plantList", "plantInfo", "plantClear", "plantClearPage", "plantSetPage", "plantTitle", "plantSet",
                "plantValue", "plantPush", "plantRemove", "plantFlush"}) {
            helper.assertTrue(methods.contains(name), name + " is a method");
        }
        Map<String, Object> list = panel.plantList();
        helper.assertTrue(list.size() == 1 && list.containsKey(label) && ((Map<?, ?>) list.get(label)).get("cols").equals(31), "plantList: " + list);
        Map<String, Object> info = panel.plantInfo(label);
        helper.assertTrue(info.get("cols").equals(31) && info.get("rows").equals(15) && info.get("mode").equals("program")
                && info.get("page").equals(1) && info.get("elements").equals(0), "plantInfo: " + info);
        Map<?, ?> limits = (Map<?, ?>) info.get("limits");
        helper.assertTrue(limits.get("pages").equals(PlantLimits.MAX_PAGES) && limits.get("elements").equals(PlantLimits.MAX_ELEMENTS)
                && limits.get("points").equals(PlantLimits.MAX_POINTS), "and the limits: " + limits);

        // a table from Lua arrives as plain Java: ObjectArguments is CC's own conversion of what a program passes
        Map<Object, Object> table = new HashMap<>();
        table.put("type", "field");
        table.put("col", 2.0);
        table.put("row", 3.0);
        table.put("text", "POWER");
        table.put("unit", "%");
        table.put("value", 87.4);
        table.put("alarmHigh", 110.0);
        Map<String, Object> plain = LivePeripheral.plainTable(new ObjectArguments(table).getTable(0));
        helper.assertTrue(plain.size() == 7 && plain.get("col").equals(2.0) && plain.get("text").equals("POWER"), "a Lua table converts: " + plain);
        Map<Object, Object> nested = new HashMap<>();
        nested.put("type", new HashMap<>());
        helper.assertTrue(lua(() -> LivePeripheral.plainTable(new ObjectArguments(nested).getTable(0))) != null, "a table inside a table is refused");
        Map<Object, Object> numbered = new HashMap<>();
        numbered.put(1.0, "x");
        helper.assertTrue(lua(() -> LivePeripheral.plainTable(new ObjectArguments(numbered).getTable(0))) != null, "an array is not a spec");
        Map<Object, Object> big = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            big.put("k" + i, 1.0);
        }
        helper.assertTrue(lua(() -> LivePeripheral.plainTable(new ObjectArguments(big).getTable(0))) != null, "a table of 40 keys is refused");
        helper.assertTrue(LivePeripheral.plainValue(null) == null && LivePeripheral.plainValue(3).equals(3.0) && LivePeripheral.plainValue("x").equals("x"),
                "plain values");
        helper.assertTrue(lua(() -> LivePeripheral.plainValue(new HashMap<>())) != null, "a table is not a plain value");

        // the calls
        helper.assertTrue(panel.plantTitle(label, 1, "SPDS") && panel.plantSet(label, 1, "pw", plain), "title and a field");
        helper.assertTrue(panel.plantSet(label, 1, "tr", spec("type", "trend", "col", 2, "row", 5, "w", 28, "h", 6, "text", "PRESS", "min", 0.0, "max", 1400.0)), "a trend");
        helper.assertTrue(panel.plantPush(label, 1, "tr", 900) && panel.plantValue(label, 1, "pw", 91.5) && !panel.plantValue(label, 1, "pw", 91.5),
                "push and value (the same value again is no change)");
        helper.assertTrue(panel.plantValue(label, 1, "pw", null), "nil is no data");
        helper.assertTrue(data.program().element(0, "pw").valueText().equals("---"), "which shows dashes");
        helper.assertTrue(panel.plantSet(label, 2, "k1", spec("type", "key", "key", 1, "text", "BACK")) && panel.plantSetPage(label, 2), "a second page, shown");
        helper.assertTrue(data.program().selected() == 1 && panel.plantInfo(label).get("page").equals(2) && panel.plantInfo(label).get("pages").equals(2), "page 2 is shown");
        helper.assertTrue(panel.plantClearPage(label, 2) && !panel.plantClearPage(label, 2), "clear a page");
        helper.assertTrue(panel.plantRemove(label, 1, "pw") && !panel.plantRemove(label, 1, "pw"), "remove an element");
        helper.assertTrue(panel.plantFlush(label), "flush");
        helper.assertTrue(panel.plantClear(label) && data.program().isEmpty() && data.program().selected() == 0 && !panel.plantClear(label), "clear");

        // errors are Lua errors with a reason
        helper.assertTrue(lua(() -> panel.plantInfo("NO-SUCH")) != null, "an unknown label");
        helper.assertTrue(lua(() -> panel.plantSetPage(label, 9)).contains("1 to 8"), "page 9");
        helper.assertTrue(lua(() -> panel.plantSet(label, 1, "bad id", spec("type", "text", "col", 1, "row", 1))).contains("letters"), "a bad id");
        helper.assertTrue(lua(() -> panel.plantSet(label, 1, "x", spec("type", "text", "col", 99, "row", 1, "text", "x"))) != null, "off the grid");
        helper.assertTrue(lua(() -> panel.plantValue(label, 1, "nothing", 1.0)).contains("nothing"), "no such element");
        helper.assertTrue(lua(() -> panel.plantPush(label, 1, "nothing", Double.NaN)) != null, "a point that is not finite");
        data.mode = "overview";
        helper.assertTrue(lua(() -> panel.plantClear(label)).contains("built-in"), "a built-in display refuses the program");
        data.mode = "program";
        // the touch event reaches an attached computer, once per touch, with the name, label, page and key
        List<Object[]> events = new ArrayList<>();
        IComputerAccess computer = (IComputerAccess) Proxy.newProxyInstance(PlantComputerCcChecks.class.getClassLoader(),
                new Class<?>[] {IComputerAccess.class}, (proxy, method, args) -> {
                    if (method.getName().equals("queueEvent")) {
                        Object[] event = new Object[1 + ((Object[]) args[1]).length];
                        event[0] = args[0];
                        System.arraycopy((Object[]) args[1], 0, event, 1, event.length - 1);
                        events.add(event);
                    } else if (method.getName().equals("getAttachmentName")) {
                        return "top";
                    } else if (method.getName().equals("getID")) {
                        return 7;
                    } else if (method.getName().equals("equals")) {
                        return proxy == args[0];
                    } else if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    return null;
                });
        PanelPeripheral.install();
        int attachedBefore = PanelPeripheral.attachedCount();
        live.attach(computer);
        helper.assertTrue(PanelPeripheral.attachedCount() == attachedBefore + 1, "attach registers the computer");
        try {
            // (the data is attached to the panel, so its time is the level's game time)
            PlantLayout layout = data.layout();
            panel.plantClear(label);
            helper.assertTrue(panel.plantSet(label, 1, "k2", spec("type", "key", "key", 2, "text", "NEXT")), "a soft key");
            ComponentType type = component.type();
            double click = type.clickValue(component, layout.left() + (layout.keyStart(2) + 0.5) * layout.cellW(),
                    layout.top() + (layout.rows() - 0.5) * layout.cellH());
            var context = new ComponentType.OperateContext(level, null, block, component);
            helper.assertTrue(type.operate(context, OperateAction.PRESS, click), "the key is touched");
            helper.assertTrue(events.size() == 1 && events.get(0)[0].equals("plant_computer_touch") && events.get(0)[1].equals("top")
                            && events.get(0)[2].equals(label) && events.get(0)[3].equals(1) && events.get(0)[4].equals(2),
                    "plant_computer_touch(name, label, page, key): " + (events.isEmpty() ? "no event" : java.util.Arrays.toString(events.get(0))));
        } finally {
            live.detach(computer);
        }
        helper.assertTrue(PanelPeripheral.attachedCount() == attachedBefore, "detach unregisters it");
        int before = events.size();
        PlantLayout layout = data.layout();
        double click = component.type().clickValue(component, layout.left() + (layout.keyStart(2) + 0.5) * layout.cellW(),
                layout.top() + (layout.rows() - 0.5) * layout.cellH());
        component.type().operate(new ComponentType.OperateContext(level, null, block, component), OperateAction.PRESS, click);
        helper.assertTrue(events.size() == before, "a detached computer gets no touch events");
        helper.assertTrue(new LinkedHashMap<>(panel.plantList()).containsKey(label), "still listed");

        // the rate limit is a Lua error too (last: the flood leaves the bucket empty for the rest of the tick)
        String limit = null;
        for (int i = 0; i < PlantLimits.CALL_BURST + 10 && limit == null; i++) {
            limit = lua(() -> panel.plantSetPage(label, 1));
        }
        helper.assertTrue(limit != null && limit.contains("too many"), "a flood of calls is refused: " + limit);
    }
}
