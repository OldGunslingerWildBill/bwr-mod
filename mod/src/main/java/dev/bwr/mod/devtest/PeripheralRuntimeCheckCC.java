package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The CC:Tweaked half of {@link PeripheralRuntimeCheck}. Loaded only when
 * {@code BwrMod.isComputerCraftPresent()} is true, so a CC-less JVM never
 * resolves any of these imports.
 *
 * <p>What it proves, in order:
 * <ol>
 *   <li>{@code PeripheralCapability} resolves on our block entities at all,
 *       i.e. {@code BwrPeripheralSupport.registerCapabilities} really ran and
 *       really registered something;</li>
 *   <li>the peripheral object constructs, reports a type, identifies its
 *       target and compares equal to a second lookup of the same block — CC
 *       detaches and re-attaches on every tick where that last one is
 *       false;</li>
 *   <li>every {@code @LuaFunction} has a signature CC can actually generate a
 *       wrapper for;</li>
 *   <li>every actuator declares {@code mainThread = true} — see
 *       {@link #callOne};</li>
 *   <li>every {@code @LuaFunction} returns something Lua can represent, and
 *       either succeeds or fails with a {@link LuaException} — never with a
 *       raw Java exception — on a formed plant AND on an unformed one.</li>
 * </ol>
 *
 * <h2>Two passes, on two threads</h2>
 * {@link #probeAll} does all of the above from a {@code ServerTickEvent}
 * handler, i.e. <b>on the server thread</b>. That is the wrong thread: CC
 * invokes a plain {@code @LuaFunction} on the computer thread, and it was
 * precisely because nothing here had ever called one from anywhere else that
 * the whole peripheral surface came to be written with no
 * {@code mainThread = true} on it anywhere.
 *
 * <p>{@link #startOffThreadProbe} therefore runs a second pass from a thread
 * that is not the server thread, and splits the surface the way CC splits it:
 * <ul>
 *   <li>a {@code @LuaFunction} with {@code mainThread = false} is invoked
 *       directly on that thread, which is exactly what CC does with it. If it
 *       is not safe to call from there, this is where that shows;</li>
 *   <li>a {@code @LuaFunction} with {@code mainThread = true} is handed to
 *       {@code MinecraftServer}'s main-thread executor and waited on with a
 *       timeout, which is the marshalling CC's own main-thread dispatch relies
 *       on. It arriving back, on the server thread, is the proof that the
 *       declaration does something.</li>
 * </ul>
 *
 * <h2>What it still cannot prove</h2>
 * The second pass submits to the server's executor itself rather than going
 * through CC's generated wrapper, because building one of those needs a real
 * computer with a real {@code ILuaContext}. It proves the marshalling works and
 * that the methods survive being called the way CC calls them; it does not
 * prove CC's wrapper generator picked the right side. The static check in
 * {@link #callOne} still covers that, and it is static on purpose.
 */
final class PeripheralRuntimeCheckCC {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How long the off-thread pass waits for one {@code mainThread = true}
     * method to make the round trip to the server thread and back. Generous:
     * the queue is drained once per tick at worst, and a slow dev server on a
     * cold chunk load can miss a few.
     */
    private static final long MARSHAL_TIMEOUT_SECONDS = 10L;

    private PeripheralRuntimeCheckCC() {
    }

    static int probeAll(ServerLevel level, BlockPos[] positions, String[] labels) {
        LOGGER.info("=== BWR PERIPHERAL RUNTIME CHECK: CC:Tweaked IS PRESENT, probing ===");
        int failures = 0;
        for (int i = 0; i < positions.length; i++) {
            failures += probe(level, positions[i], labels[i]);
        }
        return failures;
    }

    private static int probe(ServerLevel level, BlockPos pos, String label) {
        LOGGER.info("--- probing {} at {}", label, pos);

        IPeripheral p = level.getCapability(PeripheralCapability.get(), pos, (Direction) null);
        if (p == null) {
            LOGGER.error("FAIL {}: PeripheralCapability resolved to null - the capability is "
                    + "not registered for this block entity", label);
            return 1;
        }

        int failures = 0;

        String type = p.getType();
        LOGGER.info("  getType() = {}", type);
        if (type == null || type.isEmpty()) {
            LOGGER.error("FAIL {}: getType() returned {}", label, type);
            failures++;
        }

        Object target = p.getTarget();
        LOGGER.info("  getTarget() = {}", target == null ? "null" : target.getClass().getName());
        if (target == null) {
            LOGGER.error("FAIL {}: getTarget() returned null", label);
            failures++;
        }

        // CC compares the old and new peripheral on every block update. A
        // peripheral that is not equal to a fresh lookup of the same block
        // detaches and re-attaches forever, which fires peripheral_detach /
        // peripheral events at 20 Hz in the player's Lua.
        IPeripheral again = level.getCapability(PeripheralCapability.get(), pos, (Direction) null);
        if (again == null || !p.equals(again)) {
            LOGGER.error("FAIL {}: a second capability lookup of the same block is not "
                    + "equal() to the first; CC would thrash attach/detach", label);
            failures++;
        } else {
            LOGGER.info("  equals(second lookup) = true");
        }
        if (p.equals(null)) {
            LOGGER.error("FAIL {}: equals(null) returned true", label);
            failures++;
        }

        List<Method> methods = luaFunctions(p);
        LOGGER.info("  {} @LuaFunction methods", methods.size());
        if (methods.isEmpty()) {
            LOGGER.error("FAIL {}: no @LuaFunction methods at all", label);
            failures++;
        }

        for (Method m : methods) {
            failures += callOne(p, m, label);
        }
        return failures;
    }

    /** Every {@code @LuaFunction} on a peripheral, in a stable order. */
    private static List<Method> luaFunctions(IPeripheral p) {
        List<Method> methods = new ArrayList<>();
        for (Method m : p.getClass().getMethods()) {
            if (m.isAnnotationPresent(LuaFunction.class)) {
                methods.add(m);
            }
        }
        methods.sort((a, b) -> a.getName().compareTo(b.getName()));
        return methods;
    }

    private static int callOne(IPeripheral p, Method m, String label) {
        String sig = signature(m);
        int failures = 0;

        // CC generates a wrapper per method by reflecting over the signature.
        // Anything outside the supported set is silently dropped at attach
        // time and the method simply does not exist in Lua.
        String bad = unsupportedSignature(m);
        if (bad != null) {
            LOGGER.error("FAIL {}: {} has a signature CC cannot wrap: {}", label, sig, bad);
            failures++;
        }

        // THE THREADING RULE, checked statically because it cannot be checked
        // dynamically from here (see the class comment).
        //
        // CC runs a plain @LuaFunction on the CC computer thread, not the server
        // tick thread. Every actuator in this mod ends in
        // BlockEntity.setChanged(), which in 1.21.1 calls Level.blockEntityChanged
        // -> ServerChunkCache.getChunk (which sees the wrong thread and blocks the
        // caller on the server's main-thread task queue) and then
        // updateNeighbourForOutputSignal, which dispatches neighborChanged to
        // adjacent comparators from the wrong thread while the tick is already
        // running block updates.
        //
        // The rule is deliberately mechanical so it cannot rot into a hardcoded
        // list the way this project's earlier scan lists did: an @LuaFunction
        // that returns void is an actuator, and an actuator must declare
        // mainThread = true. Read-only getters are not covered by this and have
        // to be reasoned about individually, which the peripheral classes do in
        // their class comments.
        LuaFunction annotation = m.getAnnotation(LuaFunction.class);
        if (m.getReturnType() == void.class && annotation != null && !annotation.mainThread()) {
            LOGGER.error("FAIL {}: {} mutates the plant but is not declared "
                    + "@LuaFunction(mainThread = true), so CC will run it on the computer "
                    + "thread and it will reach setChanged() off the server thread", label, sig);
            failures++;
        }

        Object[] args = sampleArguments(m);

        Object result;
        try {
            result = m.invoke(p, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LuaException) {
                LOGGER.info("  {} -> LuaException: {}  (clean, Lua sees a plain error)",
                        sig, cause.getMessage());
                return failures;
            }
            LOGGER.error("FAIL {}: {} threw a RAW {} : {} - Lua sees "
                            + "\"Java Exception Thrown\" instead of a usable error",
                    label, sig, cause == null ? "null" : cause.getClass().getName(),
                    cause == null ? "" : cause.getMessage());
            return failures + 1;
        } catch (Throwable t) {
            LOGGER.error("FAIL {}: {} could not be invoked at all: {}", label, sig, t.toString());
            return failures + 1;
        }

        String rendered = render(result);
        LOGGER.info("  {} -> {}", sig, rendered);
        if (rendered.contains("NaN") || rendered.contains("Infinity")) {
            LOGGER.warn("WARN {}: {} returned a non-finite number; Lua will show it as "
                    + "nan/inf and arithmetic on it silently poisons a control program",
                    label, sig);
        }
        return failures;
    }

    // -----------------------------------------------------------------
    // The off-tick-thread pass
    // -----------------------------------------------------------------

    /**
     * Look the peripherals up on the caller's thread, then start a daemon thread
     * that exercises them from off it. Must be called <b>from the server tick
     * thread</b>.
     *
     * <p>The lookup stays here for the same reason CC does its lookup on the
     * server thread: {@code level.getCapability} goes through
     * {@code Level.getBlockEntity} and is not safe to call from anywhere else.
     * What CC then holds is the peripheral object, and what it calls from the
     * computer thread are methods on that object — so that is exactly what gets
     * handed across, and nothing else.
     *
     * <p>The thread is a daemon deliberately. If a peripheral method wedges — it
     * is one of the things this pass exists to find — the JVM must still be able
     * to exit so the harness can report the failure and set an exit status.
     * {@code PeripheralRuntimeCheck.pollOffThreadProbe} carries the matching
     * deadline; read its comment before changing anything here.
     */
    static PeripheralRuntimeCheck.OffThreadProbe startOffThreadProbe(
            MinecraftServer server, ServerLevel level, BlockPos[] positions, String[] labels) {
        List<IPeripheral> peripherals = new ArrayList<>();
        List<String> found = new ArrayList<>();
        for (int i = 0; i < positions.length; i++) {
            IPeripheral p = level.getCapability(PeripheralCapability.get(), positions[i],
                    (Direction) null);
            if (p != null) {
                peripherals.add(p);
                found.add(labels[i]);
            }
            // A null here is already a FAIL from probeAll; not counting it twice.
        }

        PeripheralRuntimeCheck.OffThreadProbe probe = new PeripheralRuntimeCheck.OffThreadProbe();
        Thread thread = new Thread(() -> runOffThread(server, peripherals, found, probe),
                "bwr-peripheral-check-off-tick-thread");
        thread.setDaemon(true);
        thread.start();
        return probe;
    }

    private static void runOffThread(MinecraftServer server, List<IPeripheral> peripherals,
                                     List<String> labels,
                                     PeripheralRuntimeCheck.OffThreadProbe probe) {
        try {
            if (server.isSameThread()) {
                // Belt and braces. If this ever came out true the pass would be
                // testing the same thread twice and quietly reporting a pass for
                // work it never did, which is the class of defect this whole
                // harness exists to stop shipping.
                LOGGER.error("FAIL off-thread pass: it is running ON the server tick thread, so "
                        + "it would prove nothing. Refusing to call anything.");
                probe.addFailures(1);
                return;
            }
            LOGGER.info("=== BWR PERIPHERAL RUNTIME CHECK: second pass, from thread '{}', which "
                            + "is NOT the server tick thread. This is where CC:Tweaked really "
                            + "calls from ===",
                    Thread.currentThread().getName());
            for (int i = 0; i < peripherals.size(); i++) {
                if (probe.isAbandoned()) {
                    LOGGER.warn("off-thread pass abandoned after {} of {} peripheral(s); the "
                            + "harness stopped waiting", i, peripherals.size());
                    return;
                }
                probe.addFailures(probeOffThread(server, peripherals.get(i), labels.get(i)));
            }
        } catch (Throwable t) {
            LOGGER.error("=== BWR PERIPHERAL RUNTIME CHECK: THE OFF-THREAD PASS ITSELF FAILED ===",
                    t);
            probe.addFailures(1);
        } finally {
            probe.markDone();
        }
    }

    private static int probeOffThread(MinecraftServer server, IPeripheral p, String label) {
        LOGGER.info("--- off-thread: {}", label);
        int failures = 0;
        for (Method m : luaFunctions(p)) {
            LuaFunction annotation = m.getAnnotation(LuaFunction.class);
            failures += annotation != null && annotation.mainThread()
                    ? callMarshalled(server, p, m, label)
                    : callFromThisThread(p, m, label);
        }
        return failures;
    }

    /**
     * Invoke on the calling (non-tick) thread — byte for byte what CC does with
     * a {@code mainThread = false} method.
     */
    private static int callFromThisThread(IPeripheral p, Method m, String label) {
        String sig = signature(m);
        try {
            Object result = m.invoke(p, sampleArguments(m));
            LOGGER.info("  [off tick thread] {} -> {}", sig, render(result));
            return 0;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LuaException) {
                LOGGER.info("  [off tick thread] {} -> LuaException: {}  (clean)",
                        sig, cause.getMessage());
                return 0;
            }
            LOGGER.error("FAIL {}: {} threw a RAW {} : {} when called off the server tick "
                            + "thread. This is the call CC really makes for a method that is not "
                            + "declared mainThread = true, so a player's Lua sees this and not "
                            + "the clean result the on-thread pass reported",
                    label, sig, cause == null ? "null" : cause.getClass().getName(),
                    cause == null ? "" : cause.getMessage());
            return 1;
        } catch (Throwable t) {
            LOGGER.error("FAIL {}: {} could not be invoked off the server tick thread at all: {}",
                    label, sig, t.toString());
            return 1;
        }
    }

    /**
     * Submit to the server's main-thread executor from this (non-tick) thread
     * and wait, with a timeout, for it to come back — the marshalling that
     * {@code mainThread = true} exists to get.
     *
     * <p>The method is never invoked unless the executor really did land on the
     * server thread. Running an actuator off the server thread is the exact
     * defect the declaration prevents, and a harness that caused it while
     * checking for it would be worse than useless. {@code BlockableEventLoop
     * .execute} runs a task inline on the caller when the loop has stopped
     * accepting work, so this is a real case and not a hypothetical one.
     */
    private static int callMarshalled(MinecraftServer server, IPeripheral p, Method m,
                                      String label) {
        String sig = signature(m);
        Object[] args = sampleArguments(m);
        // [0] = return value, [1] = non-null reason the call was refused.
        CompletableFuture<Object[]> future = new CompletableFuture<>();

        server.execute(() -> {
            if (!server.isSameThread()) {
                future.complete(new Object[]{null,
                        "ran inline on '" + Thread.currentThread().getName() + "' instead; the "
                                + "method was NOT invoked"});
                return;
            }
            try {
                future.complete(new Object[]{m.invoke(p, args), null});
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            Object[] box = future.get(MARSHAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (box[1] != null) {
                LOGGER.error("FAIL {}: {} was handed to the server's main-thread executor from a "
                                + "non-tick thread and it {}. The marshalling CC relies on did "
                                + "not happen", label, sig, box[1]);
                return 1;
            }
            LOGGER.info("  [marshalled to the server thread] {} -> {}", sig, render(box[0]));
            return 0;
        } catch (TimeoutException e) {
            LOGGER.error("FAIL {}: {} was submitted from a non-tick thread and had not run on the "
                            + "server thread {} seconds later. CC's mainThread dispatch uses the "
                            + "same queue, so this would hang a player's Lua",
                    label, sig, MARSHAL_TIMEOUT_SECONDS);
            return 1;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InvocationTargetException ite) {
                cause = ite.getCause();
            }
            if (cause instanceof LuaException) {
                LOGGER.info("  [marshalled to the server thread] {} -> LuaException: {}  (clean)",
                        sig, cause.getMessage());
                return 0;
            }
            LOGGER.error("FAIL {}: {} threw a RAW {} : {} on the server thread after being "
                            + "marshalled from a non-tick thread",
                    label, sig, cause == null ? "null" : cause.getClass().getName(),
                    cause == null ? "" : cause.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("FAIL {}: {} was interrupted while waiting for the server thread",
                    label, sig);
            return 1;
        }
    }

    // -----------------------------------------------------------------

    private static Object[] sampleArguments(Method m) {
        Object[] args = new Object[m.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            args[i] = sampleArgument(m.getParameterTypes()[i]);
        }
        return args;
    }

    private static Object sampleArgument(Class<?> type) {
        if (type == int.class || type == Integer.class) {
            return 1;
        }
        if (type == long.class || type == Long.class) {
            return 1L;
        }
        if (type == double.class || type == Double.class) {
            return 0.0;
        }
        if (type == boolean.class || type == Boolean.class) {
            return false;
        }
        if (type == String.class) {
            // A real component name on the default plant configuration, so the
            // happy path of getBoundaryStress is exercised rather than only
            // its rejection path.
            return "recirculation_line";
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        return null;
    }

    /**
     * The parameter and return types CC's method generator understands. Kept
     * deliberately close to what {@code dan200.computercraft.core.asm} accepts;
     * anything else means the method is quietly missing from the Lua side.
     */
    private static String unsupportedSignature(Method m) {
        for (Class<?> t : m.getParameterTypes()) {
            boolean ok = t == int.class || t == long.class || t == double.class
                    || t == boolean.class || t == String.class || t == Object.class
                    || t == Map.class || t == Optional.class
                    || t == IArguments.class || t == ILuaContext.class
                    || t == IComputerAccess.class;
            if (!ok) {
                return "parameter type " + t.getName();
            }
        }
        Class<?> r = m.getReturnType();
        boolean ok = r == void.class || r == int.class || r == long.class || r == double.class
                || r == boolean.class || r == String.class || r == Object.class
                || r == Object[].class || r == MethodResult.class
                || Map.class.isAssignableFrom(r) || Collection.class.isAssignableFrom(r)
                || r == byte[].class;
        if (!ok) {
            return "return type " + r.getName();
        }
        if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
            return "not public";
        }
        return null;
    }

    private static String signature(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ps[i].getSimpleName());
        }
        sb.append(')');
        // Which thread CC will run this on is the single most important fact
        // about a method on this interface, so it goes in every log line.
        LuaFunction annotation = m.getAnnotation(LuaFunction.class);
        if (annotation != null) {
            sb.append(annotation.mainThread() ? " [server thread]" : " [computer thread]");
        }
        return sb.toString();
    }

    private static String render(Object o) {
        if (o == null) {
            return "nil";
        }
        if (o instanceof Object[] a) {
            return Arrays.toString(a);
        }
        if (o instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object k : map.keySet()) {
                keys.add(String.valueOf(k));
            }
            keys.sort(null);
            StringBuilder sb = new StringBuilder("table{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(keys.get(i)).append('=').append(map.get(keyFor(map, keys.get(i))));
            }
            return sb.append('}').toString();
        }
        return String.valueOf(o);
    }

    private static Object keyFor(Map<?, ?> map, String rendered) {
        for (Object k : map.keySet()) {
            if (String.valueOf(k).equals(rendered)) {
                return k;
            }
        }
        return rendered;
    }
}
