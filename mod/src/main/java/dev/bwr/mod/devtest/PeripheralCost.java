package dev.bwr.mod.devtest;

import dan200.computercraft.api.lua.LuaFunction;
import dev.bwr.mod.peripheral.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What a ComputerCraft program costs the server thread per call: every argument-less getter of the peripheral of
 * each plant device is called a few hundred times (the call a Lua loop makes between {@code sleep(0)}s) and the
 * mean microseconds per call printed, with the sum for the device (a program that reads everything once per tick)
 * and the dearest getters. References CC types, so it is only loaded when CC is present.
 */
final class PeripheralCost {
    private PeripheralCost() {
    }

    record Row(String name, double micros) {
    }

    /** @return the sum over the device's getters of the mean microseconds per call */
    static double measure(String label, Object peripheral, int repeats) {
        List<Row> rows = new ArrayList<>();
        for (Method m : peripheral.getClass().getMethods()) {
            if (!m.isAnnotationPresent(LuaFunction.class) || m.getParameterCount() != 0
                    || !(m.getName().startsWith("get") || m.getName().startsWith("is"))) {
                continue;
            }
            try {
                for (int i = 0; i < 20; i++) {
                    m.invoke(peripheral);
                }
                long t = System.nanoTime();
                for (int i = 0; i < repeats; i++) {
                    m.invoke(peripheral);
                }
                rows.add(new Row(m.getName(), (System.nanoTime() - t) / 1000.0 / repeats));
            } catch (ReflectiveOperationException | RuntimeException e) {
                // A getter that needs a formed plant or a computer is not a polling cost.
            }
        }
        rows.sort(Comparator.comparingDouble(Row::micros).reversed());
        double total = rows.stream().mapToDouble(Row::micros).sum();
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "CC COST %-28s %3d getters, %8.1f us for one pass;", label, rows.size(), total));
        for (int i = 0; i < Math.min(3, rows.size()); i++) {
            b.append(String.format(Locale.ROOT, " %s %.1f", rows.get(i).name(), rows.get(i).micros()));
        }
        System.out.println(b);
        return total;
    }

    static void all(PlantKit kit) {
        measure("reactor", new ReactorPeripheral(kit.reactor), 200);
        for (var p : kit.recirculation) {
            measure("recirculation pump", new RecirculationPumpPeripheral(p), 200);
            break;
        }
        for (var o : kit.outlets) {
            measure("turbine steam outlet", new TurbineSteamOutletPeripheral(o), 200);
            break;
        }
        for (var e : kit.eccs) {
            measure("eccs pump", new EccsPumpPeripheral(e), 200);
            break;
        }
        for (var f : kit.feed) {
            measure("feedwater pump", new FeedwaterPumpPeripheral(f), 200);
            break;
        }
        if (kit.pool != null) {
            measure("suppression pool", new SuppressionPoolPeripheral(kit.pool), 200);
        }
        if (kit.condenser != null) {
            measure("condenser", new CondenserPeripheral(kit.condenser), 200);
        }
        for (var c : kit.cooling) {
            measure("cooling", new CoolingPeripheral(c), 200);
            break;
        }
        if (kit.room != null) {
            measure("containment", new ContainmentPeripheral(kit.room), 200);
        }
    }
}
