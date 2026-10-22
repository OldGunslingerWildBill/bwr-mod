package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.advancement.PlantMilestones;
import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.wiring.PlantSource;
import dev.bwr.mod.panel.wiring.PanelSampler;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real advancement progress, event gates and block-entity persistence. Each test owns a disjoint
 * 69,900 + slot*96 fixture, restores its synthetic online-player entries and releases its chunks.
 * Runtime extraction/refuelling/containment/panel paths are exercised; reactor observer predicates
 * bracket actual core physics steps without adding a production test callback.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PhaseLAdvancementTests {
    private PhaseLAdvancementTests() { }
    private static final List<String> IDS = List.of("root", "first_criticality", "power_to_grid", "survive_scram",
            "refuel", "seal_containment", "build_control_room", "lower_head_failure", "melt_through");

    @GameTest(template = "empty", batch = "l3_advancements", timeoutTicks = 300)
    public static void definitionsLoadAndRemainEventGated(GameTestHelper h) {
        var manager = h.getLevel().getServer().getAdvancements();
        for (String name : IDS) {
            var advancement = manager.get(ResourceLocation.fromNamespaceAndPath("bwr", "milestones/" + name));
            h.assertTrue(advancement != null, "advancement failed to decode: " + name);
            h.assertTrue(advancement.value().criteria().keySet().equals(java.util.Set.of("achieved")),
                    "wrong criterion set: " + name);
            if (!name.equals("root"))
                h.assertTrue(advancement.value().criteria().get("achieved").trigger()
                                instanceof net.minecraft.advancements.critereon.ImpossibleTrigger,
                        "milestone can be granted by a free vanilla trigger: " + name);
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "l3_advancements", timeoutTicks = 300)
    public static void placementAndPermittedLegacyInteractionClaimWithoutStealing(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h, 7)) {
            var pos = f.origin;
            f.level.setBlock(pos, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
            var legacy = f.level.getBlockEntity(pos);
            var automated = new FakePlayer(f.level, new GameProfile(
                    UUID.nameUUIDFromBytes("L3AutomationOnly".getBytes(StandardCharsets.UTF_8)), "L3AutomationOnly"));
            h.assertTrue(!PlantMilestones.claim(legacy, automated), "automation permanently claimed player attribution");
            NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(
                    BlockSnapshot.create(f.level.dimension(), f.level, pos), Blocks.STONE.defaultBlockState(), automated));
            h.assertTrue(PlantMilestones.milestoneOwner(legacy) == null, "automated placement claimed player attribution");
            f.owner.setPos(pos.getX() + .5, pos.getY(), pos.getZ() - 2);
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            var canceled = new PlayerInteractEvent.RightClickBlock(f.owner, InteractionHand.MAIN_HAND, pos, hit);
            canceled.setCanceled(true);
            NeoForge.EVENT_BUS.post(canceled);
            h.assertTrue(PlantMilestones.milestoneOwner(legacy) == null, "canceled interaction claimed legacy equipment");
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(f.owner, InteractionHand.MAIN_HAND, pos, hit));
            h.assertTrue(f.owner.getUUID().equals(PlantMilestones.milestoneOwner(legacy)),
                    "permitted first interaction did not claim legacy equipment");
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(f.other, InteractionHand.MAIN_HAND, pos, hit));
            h.assertTrue(f.owner.getUUID().equals(PlantMilestones.milestoneOwner(legacy)), "visitor stole legacy attribution");

            var placed = pos.east(5);
            var snapshot = BlockSnapshot.create(f.level.dimension(), f.level, placed);
            f.level.setBlock(placed, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
            NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(snapshot, Blocks.STONE.defaultBlockState(), f.other));
            h.assertTrue(f.other.getUUID().equals(PlantMilestones.milestoneOwner(f.level.getBlockEntity(placed))),
                    "placement event failed to attribute the builder");
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "l3_advancements", timeoutTicks = 300)
    public static void actualEnergyDeliveryAttributesOwnerAndSurvivesOfflineReload(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h, 0)) {
            var generator = PowerModuleRuntimeCheck.place(f.level, BwrBlocks.NUCLEAR_GENERATOR.get(), f.origin, Direction.EAST);
            var seed = generator.saveWithoutMetadata(f.level.registryAccess());
            seed.putDouble("Energy", 1000.75);
            generator.loadWithComponents(seed, f.level.registryAccess());
            h.assertTrue(PlantMilestones.claim(generator, f.owner), "first claim refused");
            PlantMilestones.claim(generator, f.other);
            h.assertTrue(f.owner.getUUID().equals(PlantMilestones.milestoneOwner(generator)), "later visitor stole attribution");
            h.assertTrue(generator.energy.extractEnergy(10, true) == 10, "fixture has no exportable energy");
            h.assertTrue(generator.energy.extractEnergy(-1, false) == 0, "negative extraction accepted");
            h.assertTrue(generator.energy.extractEnergy(0, false) == 0, "zero extraction accepted");
            h.assertTrue(!f.has(f.owner, "power_to_grid"), "simulation/zero export awarded power");

            f.offline(f.owner);
            h.assertTrue(generator.energy.extractEnergy(10, false) == 10, "real export failed");
            h.assertTrue(!f.has(f.owner, "power_to_grid") && !f.has(f.other, "power_to_grid"),
                    "offline export was attributed to a nearby player");
            var restored = f.reload(generator);
            h.assertTrue(f.owner.getUUID().equals(PlantMilestones.milestoneOwner(restored)), "owner UUID lost on reload");
            h.assertTrue(Math.abs(restored.energyStored() - 990.75) < 1e-9, "milestone persistence changed FE balance");
            f.online(f.owner);
            PlantMilestones.flush(f.owner);
            h.assertTrue(f.has(f.owner, "power_to_grid") && !f.has(f.other, "power_to_grid"),
                    "pending real export was lost or awarded to the wrong player");
            f.revoke(f.owner, "power_to_grid");
            PlantMilestones.flush(f.owner);
            restored.energy.extractEnergy(1, false);
            h.assertTrue(!f.has(f.owner, "power_to_grid"), "completed device milestone replayed after its criterion was revoked");
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "l3_advancements", timeoutTicks = 300)
    public static void refuelNeedsARealUnloadAndReloadOfTheSameFuelSlot(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h, 1)) {
            var reactor = f.reactor(false);
            int first = reactor.corePositions()[0], second = reactor.corePositions()[1];
            h.assertTrue(reactor.loadAssembly(first, new ItemStack(BwrItems.FUEL_ASSEMBLY.get())), "first fuel load failed");
            h.assertTrue(!f.has(f.owner, "refuel"), "initial fuel loading counted as refuelling");
            h.assertTrue(reactor.unloadAssembly(second).isEmpty(), "empty slot unload returned an item");
            h.assertTrue(!reactor.loadAssembly(second, new ItemStack(Blocks.STONE)), "non-fuel was accepted");
            var removed = reactor.unloadAssembly(first);
            h.assertTrue(!removed.isEmpty(), "actual fuel was not unloaded");
            h.assertTrue(!f.has(f.owner, "refuel"), "unloading alone awarded refuelling");
            h.assertTrue(reactor.loadAssembly(second, new ItemStack(BwrItems.FUEL_ASSEMBLY.get())), "alternate slot load failed");
            h.assertTrue(!f.has(f.owner, "refuel"), "loading a different slot completed refuelling");
            reactor = f.reload(reactor);
            validate(reactor);
            reactor.setVesselState(VesselState.REFUELING);
            h.assertTrue(reactor.loadAssembly(first, new ItemStack(BwrItems.FUEL_ASSEMBLY.get())), "replacement load failed");
            h.assertTrue(f.has(f.owner, "refuel") && !f.has(f.other, "refuel"), "replacement lost its owner or unload history");
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "l3_advancements", timeoutTicks = 300)
    public static void completedContainmentSurveyAwardsOnlyASealedRoom(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h, 2)) {
            var pos = f.origin;
            f.level.setBlock(pos, BwrBlocks.CONTAINMENT_CONTROLLER.get().defaultBlockState(), 3);
            var room = (ContainmentControllerBlockEntity) f.level.getBlockEntity(pos);
            PlantMilestones.claim(room, f.owner);
            PlantMilestones.containmentSurveyCompleted(room);
            h.assertTrue(!f.has(f.owner, "seal_containment"), "unsealed controller awarded containment");
            for (int x = -3; x <= 3; x++) for (int y = -3; y <= 3; y++) for (int z = -3; z <= 3; z++)
                if (Math.abs(x) == 3 || Math.abs(y) == 3 || Math.abs(z) == 3)
                    f.level.setBlock(pos.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
            room.markDirty();
            for (int i = 0; i < 40; i++) {
                ContainmentControllerBlockEntity.serverTick(f.level, pos, room.getBlockState(), room);
                if (room.isSealed() && !room.isScanning()) break;
            }
            h.assertTrue(room.isSealed(), "closed room failed its actual survey");
            h.assertTrue(f.has(f.owner, "seal_containment") && !f.has(f.other, "seal_containment"),
                    "survey completion did not award its builder");
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "l3_advancements", timeoutTicks = 300)
    public static void controlRoomNeedsConnectedHardwareAndALiveSignal(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h, 3)) {
            var block = BwrBlocks.CONTROL_PANEL_VERTICAL.get();
            for (int i = 0; i < 4; i++) {
                var pos = f.origin.west(i);
                f.level.setBlock(pos, block.defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH), 3);
                PlantMilestones.claim(f.level.getBlockEntity(pos), f.owner);
            }
            var panel = (PanelBlockEntity) f.level.getBlockEntity(f.origin);
            PlantMilestones.panelChanged(panel);
            h.assertTrue(!f.has(f.owner, "build_control_room"), "empty wall counted as a control room");
            var lamp = PanelOps.placeDirect(f.level, f.origin, 1, 1, ComponentTypes.LAMP, Footprint.of(2, 2));
            var button = PanelOps.placeDirect(f.level, f.origin.west(), 1, 1, ComponentTypes.PUSHBUTTON, Footprint.of(2, 2));
            var label = PanelOps.placeDirect(f.level, f.origin.west(2), 1, 1, ComponentTypes.NAMEPLATE, Footprint.of(2, 2));
            var secondLamp = PanelOps.placeDirect(f.level, f.origin.west(3), 1, 1, ComponentTypes.LAMP, Footprint.of(2, 2));
            h.assertTrue(lamp != null && button != null && label != null && secondLamp != null, "panel fixture placement failed");
            PlantMilestones.panelChanged(panel);
            h.assertTrue(!f.has(f.owner, "build_control_room"), "unwired components counted as live instrumentation");
            lamp.setSource(0, InputSources.constant(1));
            panel.resample(lamp, true);
            PlantMilestones.panelChanged(panel);
            h.assertTrue(!f.has(f.owner, "build_control_room"), "constant input counted as live instrumentation");
            lamp.setSource(0, new PlantSource(f.origin.east(8), "stored_fe"));
            panel.resample(lamp, true);
            h.assertTrue(!f.has(f.owner, "build_control_room"), "missing device counted as a finite live input");
            var generator = PowerModuleRuntimeCheck.place(f.level, BwrBlocks.NUCLEAR_GENERATOR.get(), f.origin.east(8), Direction.EAST);
            panel.resample(lamp, true);
            h.assertTrue(Double.isFinite(lamp.input(0)), "live fixture signal did not resolve");
            h.assertTrue(f.has(f.owner, "build_control_room") && !f.has(f.other, "build_control_room"),
                    "actual wiring/config resample did not award the instrumented panel owner");
        }
        h.succeed();
    }

    /** A separate batch keeps unrelated structural edits out of the global diagnostic counter. */
    @GameTest(template = "empty", batch = "l3_panel_sampling", timeoutTicks = 2000)
    public static void gaugeWallSamplingDoesNotReevaluateTheGroup(GameTestHelper h) throws Exception {
        var f = new Fixture(h, 9);
        try {
            f.releaseWhenTestEnds();
            var panels = new PanelBlockEntity[6];
            var gauges = new PanelComponent[panels.length];
            var generator = PowerModuleRuntimeCheck.place(f.level, BwrBlocks.NUCLEAR_GENERATOR.get(), f.origin.east(8), Direction.EAST);
            for (int i = 0; i < panels.length; i++) {
                var pos = f.origin.west(i);
                f.level.setBlock(pos, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, Direction.NORTH), 3);
                panels[i] = (PanelBlockEntity) f.level.getBlockEntity(pos);
                h.assertTrue(PlantMilestones.claim(panels[i], f.owner), "gauge panel ownership failed");
                gauges[i] = PanelOps.placeDirect(f.level, pos, 1, 1, ComponentTypes.DIAL_GAUGE, Footprint.of(12, 12));
                h.assertTrue(gauges[i] != null && gauges[i].inputCount() > 0
                        && gauges[i].outputCount() == 0, "fixture must contain input-only gauges");
                gauges[i].setSource(0, new PlantSource(generator.getBlockPos(), "stored_fe"));
                panels[i].resample(gauges[i], true);
                h.assertTrue(PanelSampler.isTracked(panels[i]), "live gauge is not registered with the sampler");
            }
            h.assertTrue(panels[0].group().members().size() == panels.length, "gauge wall is not one connected group");
            h.assertTrue(!f.has(f.owner, "build_control_room"), "gauges without outputs qualified as a control room");
            // Newly placed BEs can receive their queued onLoad/group-join callback next tick.
            // Those structural evaluations are legitimate; measure only after the setup settles.
            h.runAfterDelay(2, () -> {
                try {
                    long evaluations = panelGroupEvaluations();
                    h.assertTrue(evaluations > 0, "structural setup did not exercise the evaluation counter");
                    awaitGaugeSample(h, f, panels, gauges, generator, evaluations, 8);
                } catch (RuntimeException | Error failure) {
                    f.close();
                    throw failure;
                }
            });
        } catch (RuntimeException | Error failure) {
            f.close();
            throw failure;
        }
    }

    private static void awaitGaugeSample(GameTestHelper h, Fixture f, PanelBlockEntity[] panels,
            PanelComponent[] gauges, PowerModuleBlockEntity generator, long evaluations, int remaining) {
        double energy = remaining * 100.0 + 0.5;
        var snapshot = generator.saveWithoutMetadata(f.level.registryAccess());
        snapshot.putDouble("Energy", energy);
        generator.loadWithComponents(snapshot, f.level.registryAccess());
        // Do not call sampleLive/resample here: a changed value must arrive via the real server sampler.
        h.runAfterDelay(PanelConfig.sampleTicks() + 1L, () -> {
            try {
                for (int i = 0; i < panels.length; i++) {
                    h.assertTrue(PanelSampler.isTracked(panels[i]), "sampler silently stopped tracking a gauge");
                    h.assertTrue(Math.abs(gauges[i].input(0) - energy) < 1e-9,
                            "normal sampler did not refresh gauge " + i + "; remaining cycles=" + remaining);
                }
                h.assertTrue(panelGroupEvaluations() == evaluations,
                        "live sampling reevaluated a group which cannot qualify; remaining cycles=" + remaining);
                h.assertTrue(!f.has(f.owner, "build_control_room"), "sampling input-only gauges awarded a control room");
                if (remaining > 1) {
                    awaitGaugeSample(h, f, panels, gauges, generator, evaluations, remaining - 1);
                    return;
                }
                com.mojang.logging.LogUtils.getLogger().info(
                        "L3 PANEL SAMPLING: {} panels, 8 measured live cycles, group evaluations {} -> {}",
                        panels.length, evaluations, panelGroupEvaluations());
            } catch (RuntimeException | Error failure) {
                f.close();
                throw failure;
            }
            f.close();
            h.succeed();
        });
    }

    @GameTest(template = "empty", batch = "l3_advancements", timeoutTicks = 300)
    public static void oneStructuralEvaluationCreditsEveryPanelOwner(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h, 10)) {
            var panels = new PanelBlockEntity[4];
            var components = new PanelComponent[panels.length];
            for (int i = 0; i < panels.length; i++) {
                var pos = f.origin.west(i);
                f.level.setBlock(pos, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, Direction.NORTH), 3);
                panels[i] = (PanelBlockEntity) f.level.getBlockEntity(pos);
                PlantMilestones.claim(panels[i], i < 2 ? f.owner : f.other);
                components[i] = PanelOps.placeDirect(f.level, pos, 1, 1,
                        i == 1 ? ComponentTypes.PUSHBUTTON : ComponentTypes.LAMP, Footprint.of(2, 2));
                h.assertTrue(components[i] != null, "multi-owner fixture placement failed");
            }
            h.assertTrue(panels[0].group().members().size() == panels.length, "owners' panels did not join one group");
            h.assertTrue(!f.has(f.owner, "build_control_room") && !f.has(f.other, "build_control_room"),
                    "unwired group awarded an owner");
            var generator = PowerModuleRuntimeCheck.place(f.level, BwrBlocks.NUCLEAR_GENERATOR.get(), f.origin.east(8), Direction.EAST);
            components[0].setSource(0, new PlantSource(generator.getBlockPos(), "stored_fe"));
            long before = panelGroupEvaluations();
            panels[0].resample(components[0], true);
            h.assertTrue(panelGroupEvaluations() == before + 1, "one wiring edit did not evaluate exactly one group");
            h.assertTrue(f.has(f.owner, "build_control_room") && f.has(f.other, "build_control_room"),
                    "one qualifying group pass failed to credit every member's owner");
            int earnedBit = 1 << PlantMilestones.Milestone.BUILD_CONTROL_ROOM.ordinal();
            for (var panel : panels)
                h.assertTrue((panel.getPersistentData().getCompound(PlantMilestones.DATA_KEY).getInt("Earned") & earnedBit) != 0,
                        "qualifying member was not marked earned in the shared pass");
        }
        h.succeed();
    }

    private static long panelGroupEvaluations() {
        try {
            var counter = PlantMilestones.class.getDeclaredField("panelGroupEvaluations");
            counter.setAccessible(true);
            return counter.getLong(null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("panel group evaluation diagnostic is unavailable", failure);
        }
    }

    @GameTest(template = "empty", batch = "l3_advancements", timeoutTicks = 400)
    public static void firstCriticalityNeedsFuelAndSelfSustainingNeutronics(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h, 4)) {
            var reactor = f.reactor(false);
            observeStep(reactor);
            h.assertTrue(!f.has(f.owner, "first_criticality"), "empty/source-only reactor awarded criticality");
            f.fill(reactor);
            reactor.setVesselState(VesselState.SHUTDOWN);
            reactor.core().initialiseAtTotalPowerFraction(.02);
            double achieved = reactor.core().positionRodsForReactivity(.005);
            h.assertTrue(Double.isFinite(achieved) && achieved > 0, "criticality fixture did not reach positive reactivity");
            // The in-world rod network owns the standing demand used by serverTick.
            reactor.rods().setCore(reactor.core());
            for (int i = 0; i < 20 && !f.has(f.owner, "first_criticality"); i++)
                ReactorControllerBlockEntity.serverTick(f.level, reactor.getBlockPos(), reactor.getBlockState(), reactor);
            h.assertTrue(f.has(f.owner, "first_criticality") && !f.has(f.other, "first_criticality"),
                    "fuelled self-sustaining operation not attributed to owner; rho=" + reactor.core().getReactivityDkOverK());
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "l3_advancements", timeoutTicks = 500)
    public static void surviveScramRequiresAnOperatingReactorAndIntactSettledShutdown(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h, 5)) {
            var reactor = f.reactor(true);
            // A cold scram is a command, but it has not survived a shutdown from operating power.
            reactor.core().scram();
            for (int i = 0; i < 220; i++) observeStep(reactor);
            h.assertTrue(!f.has(f.owner, "survive_scram"), "cold scram granted an operating-shutdown milestone");
            reactor.core().resetScram();
            reactor.core().initialiseAtTotalPowerFraction(.02);
            observeStep(reactor);
            reactor.core().scram();
            observeStep(reactor);
            h.assertTrue(!f.has(f.owner, "survive_scram"), "scram button press awarded before shutdown settled");
            for (int i = 0; i < 1400 && !f.has(f.owner, "survive_scram"); i++) observeStep(reactor);
            h.assertTrue(f.has(f.owner, "survive_scram") && !f.has(f.other, "survive_scram"),
                    "intact completed scram not awarded; neutron=" + reactor.core().getNeutronPowerFraction());
        }
        h.succeed();
    }

    @GameTest(template = "empty", batch = "l3_advancements", timeoutTicks = 400)
    public static void scramResetDamageAndUnformedVesselCancelThePendingShutdown(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h, 8)) {
            var reactor = f.reactor(true);
            armScram(h, reactor);
            reactor.core().resetScram();
            observeStep(reactor);
            h.assertTrue(!reactor.getPersistentData().getCompound(PlantMilestones.DATA_KEY).getBoolean("ScramPending"),
                    "reset scram left its completion timer armed");
            armScram(h, reactor);
            var severe = reactor.core().getSevereAccident().toArray();
            severe[1] = .01;
            reactor.core().getSevereAccident().fromArray(severe);
            observeStep(reactor);
            h.assertTrue(!reactor.getPersistentData().getCompound(PlantMilestones.DATA_KEY).getBoolean("ScramPending"),
                    "damaged shutdown left the survived-scram timer armed");
            severe[1] = 0;
            reactor.core().getSevereAccident().fromArray(severe);
            armScram(h, reactor);
            f.level.removeBlock(reactor.structure().interiorMin().below(), false);
            validate(reactor);
            ReactorControllerBlockEntity.serverTick(f.level, reactor.getBlockPos(), reactor.getBlockState(), reactor);
            h.assertTrue(!reactor.isFormed(), "removed floor did not invalidate the fixture");
            h.assertTrue(!reactor.getPersistentData().getCompound(PlantMilestones.DATA_KEY).getBoolean("ScramPending"),
                    "unformed vessel preserved the completion timer");
            h.assertTrue(!f.has(f.owner, "survive_scram"), "interrupted/damaged scrams granted survival");
        }
        h.succeed();
    }

    private static void armScram(GameTestHelper h, ReactorControllerBlockEntity reactor) {
        reactor.core().resetScram();
        reactor.core().initialiseAtTotalPowerFraction(.02);
        observeStep(reactor);
        reactor.core().scram();
        observeStep(reactor);
        h.assertTrue(reactor.getPersistentData().getCompound(PlantMilestones.DATA_KEY).getBoolean("ScramPending"),
                "operating-power scram fixture did not arm");
    }

    @GameTest(template = "empty", batch = "l3_advancements", timeoutTicks = 400)
    public static void darkMilestonesObserveNewFailuresAndDoNotRetroactivelyAwardOldSaves(GameTestHelper h) throws Exception {
        try (var f = new Fixture(h, 6)) {
            var reactor = f.reactor(true);
            observeStep(reactor); // Establish the intact baseline.
            PlantMilestones.beforeReactorStep(reactor);
            accident(reactor, true, false);
            PlantMilestones.afterReactorStep(reactor);
            h.assertTrue(f.has(f.owner, "lower_head_failure") && !f.has(f.owner, "melt_through"),
                    "lower-head failure did not distinguish the intact basemat");
            PlantMilestones.beforeReactorStep(reactor);
            double[] fullDepth = reactor.core().getSevereAccident().toArray();
            fullDepth[8] = dev.bwr.core.accident.SevereAccident.BASEMAT_THICKNESS_M;
            fullDepth[9] = 0;
            reactor.core().getSevereAccident().fromArray(fullDepth);
            PlantMilestones.afterReactorStep(reactor);
            h.assertTrue(!f.has(f.owner, "melt_through"), "full-depth ablation without basemat failure awarded melt-through");
            PlantMilestones.beforeReactorStep(reactor);
            accident(reactor, true, true);
            PlantMilestones.afterReactorStep(reactor);
            h.assertTrue(f.has(f.owner, "melt_through") && !f.has(f.other, "melt_through"),
                    "new basemat failure was not attributed to the owner");
            f.revoke(f.owner, "lower_head_failure");
            f.revoke(f.owner, "melt_through");
            var saved = reactor.saveWithFullMetadata(f.level.registryAccess());
            // Model a pre-L3 save: damage exists but no milestone baseline/ownership history.
            saved.getCompound("NeoForgeData").remove(PlantMilestones.DATA_KEY);
            saved.getCompound("ForgeData").remove(PlantMilestones.DATA_KEY);
            var pos = reactor.getBlockPos();
            var state = reactor.getBlockState();
            f.level.removeBlockEntity(pos);
            reactor = (ReactorControllerBlockEntity) BlockEntity.loadStatic(pos, state, saved, f.level.registryAccess());
            f.level.setBlockEntity(reactor);
            validate(reactor);
            reactor.getPersistentData().remove(PlantMilestones.DATA_KEY);
            reactor.getPersistentData().remove(PlantMilestones.OWNER_KEY);
            PlantMilestones.claim(reactor, f.owner);
            observeStep(reactor);
            h.assertTrue(!f.has(f.owner, "lower_head_failure") && !f.has(f.owner, "melt_through"),
                    "claiming a legacy failed reactor retroactively awarded its old accident");
        }
        h.succeed();
    }

    private static void observeStep(ReactorControllerBlockEntity reactor) {
        PlantMilestones.beforeReactorStep(reactor);
        reactor.core().step(.05);
        PlantMilestones.afterReactorStep(reactor);
    }

    private static void accident(ReactorControllerBlockEntity reactor, boolean head, boolean basemat) {
        double[] snapshot = reactor.core().getSevereAccident().toArray();
        snapshot[6] = head ? 1 : 0;
        snapshot[7] = head ? 1 : 0;
        snapshot[8] = basemat ? dev.bwr.core.accident.SevereAccident.BASEMAT_THICKNESS_M : 0;
        snapshot[9] = basemat ? 1 : 0;
        reactor.core().getSevereAccident().fromArray(snapshot);
    }

    private static void validate(ReactorControllerBlockEntity reactor) throws Exception {
        var method = ReactorControllerBlockEntity.class.getDeclaredMethod("revalidate", Level.class);
        method.setAccessible(true);
        method.invoke(reactor, reactor.getLevel());
    }

    static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos origin;
        final FakePlayer owner;
        final FakePlayer other;
        final Map<UUID, ServerPlayer> online;
        final Map<UUID, ServerPlayer> previous = new LinkedHashMap<>();
        final Map<UUID, PlayerAdvancements> advancementStore;
        final Map<UUID, PlayerAdvancements> previousAdvancements = new LinkedHashMap<>();
        private boolean closed;

        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper, int slot) throws Exception {
            this.helper = helper;
            level = helper.getLevel();
            origin = new BlockPos(69_900 + slot * 96, 190, 69_900);
            var field = PlayerList.class.getDeclaredField("playersByUUID");
            field.setAccessible(true);
            online = (Map<UUID, ServerPlayer>) field.get(level.getServer().getPlayerList());
            var advancementsField = PlayerList.class.getDeclaredField("advancements");
            advancementsField.setAccessible(true);
            advancementStore = (Map<UUID, PlayerAdvancements>) advancementsField.get(level.getServer().getPlayerList());
            try {
                owner = player("MilestoneOwner" + slot);
                other = player("MilestoneOther" + slot);
                for (var player : List.of(owner, other)) {
                    previous.put(player.getUUID(), online.put(player.getUUID(), player));
                    for (String name : IDS) revoke(player, name);
                }
                hold(true);
                clear();
            } catch (RuntimeException | Error failure) {
                // A throwing constructor never enters try-with-resources, so release here too.
                try { hold(false); }
                catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                finally { restorePlayers(); }
                throw failure;
            }
        }

        private FakePlayer player(String name) {
            UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            previousAdvancements.put(id, advancementStore.remove(id));
            // NeoForge returns a no-op FakePlayerAdvancements otherwise. Retain the fake's harmless
            // packet connection, but use the actual vanilla progress/award implementation.
            return new FakePlayer(level, new GameProfile(id, name)) {
                @Override public boolean isFakePlayer() { return false; }
            };
        }

        AdvancementHolder advancement(String name) {
            var holder = level.getServer().getAdvancements().get(ResourceLocation.fromNamespaceAndPath("bwr", "milestones/" + name));
            helper.assertTrue(holder != null, "advancement resource missing: " + name);
            return holder;
        }

        boolean has(ServerPlayer player, String name) { return player.getAdvancements().getOrStartProgress(advancement(name)).isDone(); }
        void revoke(ServerPlayer player, String name) { player.getAdvancements().revoke(advancement(name), "achieved"); }
        void offline(ServerPlayer player) { online.remove(player.getUUID()); }
        void online(ServerPlayer player) { online.put(player.getUUID(), player); }

        ReactorControllerBlockEntity reactor(boolean fuelled) throws Exception {
            var reactor = CompactCoreRegressionTests.build(level, origin, 5, 5, false);
            helper.assertTrue(reactor.isFormed(), "reactor fixture did not form");
            PlantMilestones.claim(reactor, owner);
            reactor.setVesselState(VesselState.REFUELING);
            if (fuelled) fill(reactor);
            return reactor;
        }

        void fill(ReactorControllerBlockEntity reactor) {
            for (int position : reactor.corePositions())
                reactor.loadAssembly(position, new ItemStack(BwrItems.FUEL_ASSEMBLY.get()));
        }

        @SuppressWarnings("unchecked")
        <T extends BlockEntity> T reload(T before) {
            var pos = before.getBlockPos();
            var state = before.getBlockState();
            var snapshot = before.saveWithFullMetadata(level.registryAccess());
            level.removeBlockEntity(pos);
            var after = (T) BlockEntity.loadStatic(pos, state, snapshot, level.registryAccess());
            helper.assertTrue(after != null, "block entity reload failed");
            level.setBlockEntity(after);
            PlantMilestones.loaded(after);
            return after;
        }

        void hold(boolean forced) {
            for (int x = (origin.getX() - 32) >> 4; x <= (origin.getX() + 32) >> 4; x++)
                for (int z = (origin.getZ() - 32) >> 4; z <= (origin.getZ() + 32) >> 4; z++) {
                    FixtureChunks.force(level, origin, x, z, forced);
                    if (forced) level.getChunk(x, z);
                }
        }

        void clear() {
            // Fuel goes out of the controller first, so removing it pops nothing into the saved chunk.
            FixtureCleanup.drainFuel(level, origin.offset(-12, -6, -12), origin.offset(30, 18, 20));
            for (var pos : BlockPos.betweenClosed(origin.offset(-12, -6, -12), origin.offset(30, 18, 20)))
                if (!level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            // Safety net: drops of the removed panels and machines (they fall out of the box) stay out of the saved chunk.
            FixtureCleanup.discardItems(level, origin.offset(-12, -80, -12), origin.offset(30, 18, 20));
        }

        void releaseWhenTestEnds() {
            helper.testInfo.addListener(new GameTestListener() {
                @Override public void testStructureLoaded(GameTestInfo info) { }
                @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
                @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
                @Override public void testAddedForRerun(GameTestInfo before, GameTestInfo after, GameTestRunner runner) { close(); }
            });
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try { clear(); }
            finally {
                try { hold(false); }
                finally { restorePlayers(); }
            }
        }

        private void restorePlayers() {
            try {
                for (var entry : previous.entrySet()) {
                    if (entry.getValue() == null) online.remove(entry.getKey());
                    else online.put(entry.getKey(), entry.getValue());
                }
            } finally {
                for (var entry : previousAdvancements.entrySet()) {
                    var created = advancementStore.remove(entry.getKey());
                    if (created != null) created.stopListening();
                    if (entry.getValue() != null) advancementStore.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
