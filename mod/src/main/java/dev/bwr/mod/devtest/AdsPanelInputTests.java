package dev.bwr.mod.devtest;

import dev.bwr.mod.eccs.AdsControllerBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.OperateAction;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.wiring.PlantSink;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.signal.CommandSpec;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.signal.SignalProviders;
import dev.bwr.mod.steam.SafetyReliefValveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * The bindable inputs of the ADS controller and the relief valves (NEW-HARDWARE-SPEC.md, part 3), wired the way a player
 * wires them: two push-to-turn switches to {@code ads_activate_a} and {@code ads_activate_b} and a toggle to
 * {@code ads_allow} on the controller, and the per-valve {@code ads_allow} of a relief valve. The controller still does
 * nothing until a player pushes the switches or a program starts it; these tests drive every change through the panel.
 *
 * <p>World fixture at 97_100 / 97_100 (see {@link HardwareStage}), the controller and two valves behind the panel. No
 * CC:Tweaked types here.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class AdsPanelInputTests {
    private AdsPanelInputTests() {
    }

    static final int ORIGIN_X = 97_100;
    static final int ORIGIN_Z = 97_100;

    private static void turn(HardwareStage s, PanelComponent sw, double direction) {
        PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.PRESS, 1.0);
        PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.TURN, direction);
        PanelOps.operate(s.player, s.origin, sw.id(), OperateAction.RELEASE, 0.0);
    }

    private static void ticks(AdsControllerBlockEntity ads, BlockPos at, int n) {
        for (int i = 0; i < n; i++) {
            AdsControllerBlockEntity.serverTick(ads.getLevel(), at, ads.getBlockState(), ads);
        }
    }

    @GameTest(template = "empty")
    public static void adsSignalsListTheThreeInputsAndPlainBehaviourIsUnchanged(GameTestHelper helper) {
        BlockPos at = new BlockPos(ORIGIN_X + 200, 110, ORIGIN_Z);
        var level = helper.getLevel();
        FixtureChunks.force(level, at.getX() >> 4, at.getZ() >> 4, true);
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        try {
            level.setBlock(at, BwrBlocks.ADS_CONTROLLER.get().defaultBlockState(), 2);
            AdsControllerBlockEntity ads = (AdsControllerBlockEntity) level.getBlockEntity(at);
            SignalProvider provider = SignalProviders.of(ads);
            helper.assertTrue(provider != null, "the ADS controller has a signal provider now");
            List<String> commands = provider.commands().stream().map(CommandSpec::key).toList();
            helper.assertTrue(commands.equals(List.of("ads_allow", "ads_activate_a", "ads_activate_b")), "its commands: " + commands);
            helper.assertTrue(provider.read("allowed").orElse(-1) == 1.0 && provider.read("activate_a").orElse(-1) == 0.0
                    && provider.read("open").orElse(-1) == 0.0, "a new controller is allowed, nothing activated, not open");
            // A controller nobody wired behaves exactly as before: the defaults save nothing new.
            helper.assertTrue(ads.isAllowed() && !ads.saveWithoutMetadata(level.registryAccess()).contains("Inhibited"),
                    "the allow input is not saved while it is the default");
            provider.command("ads_allow", 0.0);
            CompoundTag saved = ads.saveWithoutMetadata(level.registryAccess());
            helper.assertTrue(!ads.isAllowed() && saved.getBoolean("Inhibited"), "an inhibited controller saves that");
            ads.loadWithComponents(saved, level.registryAccess());
            helper.assertTrue(!ads.isAllowed(), "and loads it back");
            provider.command("ads_allow", 1.0);
            helper.assertTrue(ads.isAllowed() && !ads.saveWithoutMetadata(level.registryAccess()).contains("Inhibited"),
                    "allowing again leaves no key");
            // The activation inputs are held switches: never saved.
            provider.command("ads_activate_a", 1.0);
            helper.assertTrue(ads.isActivateA() && !ads.saveWithoutMetadata(level.registryAccess()).toString().contains("ctivate"),
                    "an activation input is not saved");
            // One of the two alone opens nothing.
            helper.assertTrue(!ads.isOpen(), "one activation input alone opens nothing");
        } finally {
            level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
            FixtureChunks.force(level, at.getX() >> 4, at.getZ() >> 4, false);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void pushingBothSwitchesOpensTheBankOnlyWhileAllowedAndEachValveHasItsOwnPermit(GameTestHelper helper) {
        HardwareStage.run(helper, ORIGIN_X, ORIGIN_Z, "ads", s -> {
            BlockPos adsAt = s.origin.offset(0, 2, 6);
            BlockPos firstAt = s.origin.offset(3, 2, 6);
            BlockPos secondAt = s.origin.offset(-3, 2, 6);
            try {
                s.level.setBlock(adsAt, BwrBlocks.ADS_CONTROLLER.get().defaultBlockState(), 2);
                for (BlockPos valve : new BlockPos[] {firstAt, secondAt}) {
                    s.level.setBlock(valve, BwrBlocks.ADS_RELIEF_VALVE.get().defaultBlockState(), 2);
                    s.level.setBlock(valve.below(), Blocks.WATER.defaultBlockState(), 2);
                }
                AdsControllerBlockEntity ads = (AdsControllerBlockEntity) s.level.getBlockEntity(adsAt);
                SafetyReliefValveBlockEntity first = (SafetyReliefValveBlockEntity) s.level.getBlockEntity(firstAt);
                SafetyReliefValveBlockEntity second = (SafetyReliefValveBlockEntity) s.level.getBlockEntity(secondAt);

                // The panel: two push-to-turn switches and a toggle, wired to the controller's three inputs.
                PanelComponent a = s.place(ComponentTypes.PUSH_TURN_SWITCH, 0, 0, Footprint.of(8, 8));
                PanelComponent b = s.place(ComponentTypes.PUSH_TURN_SWITCH, 8, 0, Footprint.of(8, 8));
                PanelComponent allow = s.place(ComponentTypes.TOGGLE_SWITCH, 0, 8, Footprint.of(6, 8));
                a.addSink(0, new PlantSink(adsAt, "ads_activate_a", 1.0));
                b.addSink(0, new PlantSink(adsAt, "ads_activate_b", 1.0));
                allow.addSink(0, new PlantSink(adsAt, "ads_allow", 1.0));
                ads.setDivision(1);
                helper.assertTrue(!ads.isOpen() && ads.isAllowed(), "nothing acts on its own: closed and allowed to begin with");

                // Turning A to ARM alone does nothing; B to ARM as well opens the bank (the controller is claimed, like start()).
                turn(s, a, 1.0);
                helper.assertTrue(ads.isActivateA() && !ads.isActivateB() && !ads.isOpen(), "A alone does not open the bank");
                turn(s, b, 1.0);
                helper.assertTrue(ads.isActivateB() && ads.isOpen() && ads.isComputerControlled(), "both held in ARM opens the bank");
                ticks(ads, adsAt, 45);
                helper.assertTrue(first.isOpen() && second.isOpen(), "and both relief valves are held open");

                // A program closes it. The switches are still both at ARM: that alone does not open it again.
                ads.setOpen(false);
                ticks(ads, adsAt, 2);
                helper.assertTrue(!ads.isOpen() && !first.isOpen(), "stopped by the program");
                // Bringing the switches together again, one at a time, is a new activation.
                turn(s, a, -1.0);
                helper.assertTrue(!ads.isActivateA() && !ads.isOpen(), "A back to AUTO changes nothing");
                turn(s, a, 1.0);
                helper.assertTrue(ads.isOpen(), "both together again opens it again");

                // Inhibit from the toggle: PRESS makes it 1 (allowed), the second PRESS 0 (not allowed).
                ads.setOpen(false);
                PanelOps.operate(s.player, s.origin, allow.id(), OperateAction.PRESS, 1.0);
                helper.assertTrue(ads.isAllowed(), "the toggle in its on position allows");
                PanelOps.operate(s.player, s.origin, allow.id(), OperateAction.PRESS, 1.0);
                helper.assertTrue(!ads.isAllowed(), "off, the wired allow input is cleared");
                turn(s, a, -1.0);
                turn(s, b, -1.0);
                turn(s, a, 1.0);
                turn(s, b, 1.0);
                helper.assertTrue(!ads.isOpen(), "pushing both while not allowed opens nothing");
                // Allowing it while both are still held in ARM is the player bringing them together: it opens.
                PanelOps.operate(s.player, s.origin, allow.id(), OperateAction.PRESS, 1.0);
                helper.assertTrue(ads.isAllowed() && ads.isOpen(), "allowed with both held opens it");
                ticks(ads, adsAt, 45);
                helper.assertTrue(first.isOpen() && second.isOpen(), "both valves held open");

                // The controller's allow input: cleared, the bank is let go whatever the open request.
                SignalProvider adsSignals = SignalProviders.of(ads);
                adsSignals.command("ads_allow", 0.0);
                ticks(ads, adsAt, 2);
                helper.assertTrue(ads.isOpen() && !first.isOpen() && !second.isOpen(), "inhibited: the request stands, no valve is held");
                adsSignals.command("ads_allow", 1.0);
                ticks(ads, adsAt, 2);
                helper.assertTrue(first.isOpen() && second.isOpen(), "allowed again: the bank is held open again");

                // The per-valve permit (the OPEN/AUTO/CLOSE switch of a relief valve): CLOSE means the ADS may not open it.
                SignalProvider firstSignals = SignalProviders.of(first);
                helper.assertTrue(firstSignals.read("ads_allowed").orElse(-1) == 1.0
                        && !first.saveWithoutMetadata(s.level.registryAccess()).contains("AdsInhibited"),
                        "a valve is permitted by default and saves nothing for it");
                helper.assertTrue(firstSignals.command("ads_allow", 0.0), "the valve takes the command");
                ticks(ads, adsAt, 2);
                helper.assertTrue(!first.isOpen() && second.isOpen(), "the inhibited valve is let go, the other stays open");
                CompoundTag firstSaved = first.saveWithoutMetadata(s.level.registryAccess());
                helper.assertTrue(firstSaved.getBoolean("AdsInhibited") && !second.saveWithoutMetadata(s.level.registryAccess()).contains("AdsInhibited"),
                        "the permit is saved only on the valve that lost it");
                first.loadWithComponents(firstSaved, s.level.registryAccess());
                helper.assertTrue(!first.isAdsAllowed(), "and survives a reload");
                firstSignals.command("ads_allow", 1.0);
                ticks(ads, adsAt, 2);
                helper.assertTrue(first.isOpen() && second.isOpen(), "permitted again: held open again");
            } finally {
                for (BlockPos pos : new BlockPos[] {adsAt, firstAt, secondAt}) {
                    s.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    s.level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        });
    }
}
