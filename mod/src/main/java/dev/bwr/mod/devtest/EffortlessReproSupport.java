package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Side-neutral helpers of the Effortless reproduction (no client classes), used by EffortlessReproCheck and EffortlessMpRepro. */
final class EffortlessReproSupport {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    static final BlockPos C = new BlockPos(0, 100, 0);
    record Scenario(String name, String mode, net.minecraft.world.level.GameType game, boolean attack, int clicks) {}
    private EffortlessReproSupport() {}

    static void report(String side, Scenario s, Level l) {
        int compact = 0, full = 0;
        Map<String, Integer> controllers = new TreeMap<>();
        List<String> compacts = new ArrayList<>();
        for (var at : BlockPos.betweenClosed(C.offset(-20, 1, -20), C.offset(20, 12, 20))) {
            BlockState st = l.getBlockState(at);
            if (!(st.getBlock() instanceof PumpAssemblyBlock b)) continue;
            if (!st.getValue(PumpAssemblyBlock.ASSEMBLED)) { compact++; compacts.add(at.toShortString() + " " + st); }
            else {
                full++;
                if (st.getValue(PumpAssemblyBlock.CELL) == b.controllerCell(st)) controllers.put(at.toShortString(), 1);
            }
        }
        LOG.info("EFFORTLESS REPRO [{}] {} world: compact cells={} full cells={} full controllers={} compact={}", s.name(), side, compact, full,
                controllers.keySet(), compacts);
    }

    // ------------------------------------------------------------------------------------------------ Effortless by reflection

    static Object setMode(String mode) throws Exception {
        Class<?> clientClass = Class.forName("dev.huskuraft.effortless.EffortlessClient");
        Object client = clientClass.getMethod("getInstance").invoke(null);
        Object builder = call(client, "getStructureBuilder");
        Object effPlayer = call(call(client, "getClient"), "getPlayer");
        Class<?> buildMode = Class.forName("dev.huskuraft.effortless.building.structure.BuildMode");
        Object m = buildMode.getMethod("valueOf", String.class).invoke(null, mode);
        Object structure = call(m, "getDefaultStructure");
        return call(builder, "setStructure", effPlayer, structure);
    }

    static void lastEffortlessResult(Scenario s, ServerPlayer p) throws Exception {
        Object eff = Class.forName("dev.huskuraft.effortless.Effortless").getMethod("getInstance").invoke(null);
        Object builder = call(eff, "getStructureBuilder");
        Object effPlayer = Class.forName("dev.huskuraft.effortless.neoforge.core.MinecraftPlayer")
                .getMethod("ofNullable", net.minecraft.world.entity.player.Player.class).invoke(null, p);
        Object stack = call(builder, "getOperationResultStack", effPlayer);
        Object last = null;
        for (Field f : stack.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object v = f.get(stack);
            if (v instanceof java.util.Deque<?> dq && !dq.isEmpty()) last = dq.peekFirst() != null ? dq.peekFirst() : last;
            else if (v instanceof java.util.List<?> li && !li.isEmpty()) last = li.get(li.size() - 1);
            else if (v instanceof java.util.Stack<?> st && !st.isEmpty()) last = st.peek();
            if (v instanceof java.util.Stack<?> st2 && !st2.isEmpty()) last = st2.peek();
        }
        if (last == null) { LOG.info("EFFORTLESS REPRO [{}] effortless result stack empty", s.name()); return; }
        Collection<?> results = (Collection<?>) call(last, "getResults");
        LOG.info("EFFORTLESS REPRO [{}] effortless last batch: {} results", s.name(), results.size());
        int n = 0;
        for (Object r : results) {
            if (n++ >= 12) break;
            Object op = call(r, "getOperation");
            Object type = tryCall(r, "result");
            Object pos = tryCall(op, "getBlockPosition");
            Object state = tryCall(op, "getBlockState");
            Object placed = tryCall(r, "getBlockStatePlaced");
            LOG.info("EFFORTLESS REPRO [{}]   op={} pos={} result={} opState={} placed={}", s.name(), op.getClass().getSimpleName(), pos, type,
                    state == null ? null : tryCall(state, "reference"), placed == null ? null : tryCall(placed, "reference"));
        }
    }

    private static Object tryCall(Object o, String name, Object... args) {
        try { return call(o, name, args); } catch (Exception e) { return "<" + e + ">"; }
    }

    static Object call(Object o, String name, Object... args) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return m.invoke(o, args);
                }
            }
        }
        for (Method m : o.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                return m.invoke(o, args);
            }
        }
        throw new NoSuchMethodException(o.getClass() + "." + name + "/" + args.length);
    }
}
