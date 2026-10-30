package dev.bwr.mod.devtest;

import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;

/**
 * Rod selection and the select / step commands behind the rod select matrix and the rod motion pushbuttons (full core
 * display spec, item 1): the selection is reactor state that is saved and that old saves load without; the step
 * commands move only the selected rod, one notch, through the drive network, so they honour CRD power exactly as
 * {@code rod_<n>_position} does; the continuous commands start on 1 and stop on 0.
 *
 * <p>Fixture origin 75_900 / 75_900, {@code slot * 64} blocks apart in X (see {@link FcdTestSupport}).
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class FcdRodTests {
    static final int ORIGIN = 75_900;

    private static SignalProvider provider(FcdTestSupport.Run run) {
        SignalProvider provider = SignalProviders.of(run.be);
        run.helper.assertTrue(provider != null, "reactor has no signal provider");
        return provider;
    }

    private static double read(SignalProvider provider, String key) {
        return provider.read(key).orElse(Double.NaN);
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = FcdTestSupport.WAIT_TICKS + 200)
    public static void selectionIsReactorStateThatSavesAndOldSavesLoadWithNone(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 0, 9, 9, run -> {
            SignalProvider rp = provider(run);
            int rods = run.be.core().getControlRodCount();
            h.assertTrue(read(rp, "selected_rod") == 0.0, "a fresh reactor has no selection");
            h.assertTrue(rp.command("select_rod", 3) && read(rp, "selected_rod") == 3.0, "select_rod 3 failed");
            h.assertTrue(!rp.command("select_rod", rods + 1) && !rp.command("select_rod", -1)
                    && !rp.command("select_rod", 2.5) && read(rp, "selected_rod") == 3.0,
                    "a rod outside the core, a negative and a fractional rod number must be refused and change nothing");
            for (int step = 0; step < 4; step++) {
                h.assertTrue(run.be.rods().getCommandedNotchIndex(step) == run.be.core().getRodNotchIndex(step),
                        "selecting moved a rod demand");
            }
            // It survives a save and a reload...
            CompoundTag saved = run.be.saveWithFullMetadata(run.level.registryAccess());
            h.assertTrue(saved.getInt("SelectedRod") == 3, "the selection is not saved");
            ReactorControllerBlockEntity restored = reload(run, saved);
            h.assertTrue(restored.isFormed() && restored.selectedRod() == 3, "the selection did not survive a reload");
            // ...and a save from before the feature (no key) loads with none.
            saved.remove("SelectedRod");
            restored = reload(run, saved);
            h.assertTrue(restored.isFormed() && restored.selectedRod() == 0, "an old save must load with no selection");
            // Clearing is a selection too, and it is saved as none.
            h.assertTrue(restored.selectRod(5) && restored.selectRod(0) && restored.selectedRod() == 0,
                    "selection 0 clears");
            h.assertTrue(!restored.saveWithFullMetadata(run.level.registryAccess()).contains("SelectedRod"),
                    "a cleared selection must not be written");
        });
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = FcdTestSupport.WAIT_TICKS + 200)
    public static void unformedReactorHasNoSelection(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 1, 9, 9, run -> {
            SignalProvider rp = provider(run);
            h.assertTrue(rp.command("select_rod", 2), "select before breaking");
            run.level.removeBlock(run.be.structure().crdPositions().get(0), false);
            run.then(5, () -> {
                revalidate(run.be);
                h.assertTrue(!run.be.isFormed(), "the reactor should be unformed with a drive missing");
                h.assertTrue(run.be.selectedRod() == 0 && !rp.read("selected_rod").isPresent(),
                        "an unformed reactor reads no selected rod");
                h.assertTrue(!rp.command("select_rod", 1) && !rp.command("selected_rod_withdraw", 1)
                        && !rp.command("selected_rod_insert_continuous", 1), "an unformed reactor accepts no rod selection command");
            });
        });
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = FcdTestSupport.WAIT_TICKS + 500)
    public static void stepsMoveOnlyTheSelectedRodOneNotchAndHonourCrdPower(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 2, 9, 9, run -> {
            SignalProvider rp = provider(run);
            int[] demandBefore = run.be.rods().getCommandedNotchIndices();
            // Nothing selected: the step commands are refused and change nothing.
            h.assertTrue(!rp.command("selected_rod_withdraw", 1) && !rp.command("selected_rod_insert", 1),
                    "stepping with no rod selected must be refused");
            h.assertTrue(Arrays.equals(demandBefore, run.be.rods().getCommandedNotchIndices()), "a refused step changed a demand");
            h.assertTrue(rp.command("select_rod", 4), "select 4");
            // Unpowered drives: the demand moves one notch, the rod does not. (A tick passes before a drive that lost
            // its supplies reports it, so the step is made after that.)
            FcdTestSupport.starveDrives(run.level, run.be);
            run.then(5, () -> {
                h.assertTrue(rp.command("selected_rod_withdraw", 1), "withdraw step refused");
                int[] demand = run.be.rods().getCommandedNotchIndex(3) == demandBefore[3] + 1
                        ? run.be.rods().getCommandedNotchIndices() : null;
                h.assertTrue(demand != null, "the selected rod's demand did not move one notch");
                for (int r = 0; r < demand.length; r++) {
                    h.assertTrue(demand[r] == demandBefore[r] + (r == 3 ? 1 : 0),
                            "rod " + (r + 1) + " demand " + demand[r] + " (a step must move the selected rod, one notch, and no other)");
                }
                run.scratch = run.be.core().getRodPositionNotches(3);
            });
            run.then(120, () -> {
                h.assertTrue(run.be.core().getRodPositionNotches(3) == run.scratch,
                        "a rod with an unpowered drive moved: step commands must honour CRD power");
                h.assertTrue(run.be.rods().getCommandedNotchIndex(3) == demandBefore[3] + 1,
                        "the demand of an unpowered drive stands, as for rod_<n>_position");
                // Power back: the standing demand is carried out, one notch and no more.
                FcdTestSupport.supplyDrives(run.level, run.be);
            });
            run.then(150, () -> {
                h.assertTrue(run.be.core().getRodNotchIndex(3) == demandBefore[3] + 1,
                        "a powered drive did not index the selected rod one notch (at " + run.be.core().getRodPositionNotches(3) + ")");
                for (int r = 0; r < demandBefore.length; r++) {
                    if (r != 3) {
                        h.assertTrue(run.be.core().getRodNotchIndex(r) == demandBefore[r], "rod " + (r + 1) + " moved");
                    }
                }
                // And back in one notch.
                h.assertTrue(rp.command("selected_rod_insert", 1), "insert step refused");
                h.assertTrue(run.be.rods().getCommandedNotchIndex(3) == demandBefore[3], "insert step is one notch");
                // Inserting a rod that is fully in clamps there.
                h.assertTrue(rp.command("select_rod", 6) && rp.command("selected_rod_insert", 1)
                        && run.be.rods().getCommandedNotchIndex(5) == Math.max(0, demandBefore[5] - 1), "insert at the stop clamps");
            });
        });
    }

    @GameTest(template = "empty", batch = "fcd", timeoutTicks = FcdTestSupport.WAIT_TICKS + 700)
    public static void continuousSteppingStartsOnOneStopsOnZeroAndNeverRunsAway(GameTestHelper h) {
        FcdTestSupport.Run.start(h, ORIGIN, 3, 9, 9, run -> {
            SignalProvider rp = provider(run);
            h.assertTrue(!rp.command("selected_rod_withdraw_continuous", 1), "holding with no selection is refused");
            h.assertTrue(rp.command("selected_rod_withdraw_continuous", 0), "releasing always works");
            h.assertTrue(rp.command("select_rod", 2), "select 2");
            int start = run.be.rods().getCommandedNotchIndex(1);
            h.assertTrue(rp.command("selected_rod_withdraw_continuous", 1) && run.be.selectedRodHeld(1), "hold withdraw");
            run.then(160, () -> {
                int demand = run.be.rods().getCommandedNotchIndex(1);
                h.assertTrue(demand >= start + 3, "a held control steps at the rod speed (demand moved " + (demand - start) + " notches in 160 ticks)");
                h.assertTrue(demand <= start + 6, "a held control must not outrun the rod speed (demand moved " + (demand - start) + ")");
                h.assertTrue(rp.command("selected_rod_withdraw_continuous", 0) && !run.be.selectedRodHeld(1), "release");
                run.stopDemand = run.be.rods().getCommandedNotchIndex(1);
            });
            run.then(150, () -> {
                h.assertTrue(run.be.rods().getCommandedNotchIndex(1) == run.stopDemand, "a released control kept stepping");
                h.assertTrue(run.be.core().getRodNotchIndex(1) == run.stopDemand, "the rod did not settle on the last demand");
                // Selecting another rod releases a held control.
                h.assertTrue(rp.command("selected_rod_insert_continuous", 1) && rp.command("select_rod", 3)
                        && !run.be.selectedRodHeld(-1), "selecting a rod releases the held controls");
                // A drive that cannot move leads its demand by one notch at most.
                FcdTestSupport.starveDrives(run.level, run.be);
                int base = run.be.rods().getCommandedNotchIndex(2);
                h.assertTrue(rp.command("selected_rod_withdraw_continuous", 1), "hold on a starved drive");
                run.stopDemand = base;
            });
            run.then(150, () -> {
                int led = run.be.rods().getCommandedNotchIndex(2) - run.stopDemand;
                h.assertTrue(led >= 0 && led <= 1, "a starved drive's demand ran " + led + " notches ahead");
                rp.command("selected_rod_withdraw_continuous", 0);
            });
        });
    }

    // ---- helpers

    static void revalidate(ReactorControllerBlockEntity be) {
        try {
            var method = ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate", Level.class);
            method.setAccessible(true);
            method.invoke(be, be.getLevel());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Replaces the controller by one loaded from {@code saved}, as a chunk reload would. */
    static ReactorControllerBlockEntity reload(FcdTestSupport.Run run, CompoundTag saved) {
        BlockPos pos = run.be.getBlockPos();
        var state = run.be.getBlockState();
        run.level.removeBlockEntity(pos);
        BlockEntity loaded = BlockEntity.loadStatic(pos, state, saved, run.level.registryAccess());
        run.level.setBlockEntity(loaded);
        ReactorControllerBlockEntity restored = (ReactorControllerBlockEntity) loaded;
        revalidate(restored);
        run.be = restored;
        return restored;
    }
}
