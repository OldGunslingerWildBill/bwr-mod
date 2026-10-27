package dev.bwr.mod.devtest;

import dev.bwr.core.refuelling.BridgeMotion.Phase;
import dev.bwr.core.refuelling.PlugLift;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.refuelling.BridgeSite;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity;
import dev.bwr.mod.refuelling.CanalExchange;
import dev.bwr.mod.refuelling.CanalGateBlock;
import dev.bwr.mod.refuelling.CanalGateLift;
import dev.bwr.mod.refuelling.CanalGatePlugBlock;
import dev.bwr.mod.refuelling.CraneRunwayRailBlock;
import dev.bwr.mod.refuelling.PlugSectionEntity;
import dev.bwr.mod.refuelling.TransferCanalBlock;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * The canal gate lift (GATE OUT / GATE IN, {@link CanalGateLift}): a canal gate is a stack of Canal Gate Plug blocks in the slot of the cattle chute,
 * lifted out whole by the reactor building crane onto a Gate Laydown Stand and set back exactly. Real blocks: the chute slot's rig (a formed
 * 5 x 5 x 8 reactor with its head off and its well, a 7 x 7 pool with the slot in its west wall, the canal between; {@link ChuteSlotTests.Rig}) with the
 * slot's gate made a two-block plug, and a crane on two runs of runway rails over it all. The crane is stepped a tick at a time as its scheduled block tick
 * would be; the pool's exchange is stepped alone, so the water adds up exactly. Namespace {@code bwrplug}, batch {@code gates}; own fixture origins.
 */
@GameTestHolder("bwrplug")
@PrefixGameTestTemplate(false)
public final class CanalGateLiftTests {
    private CanalGateLiftTests() {
    }

    private static final int SETTLE_TICKS = 60;

    private static BlockPos origin(int i) {
        return new BlockPos(171_000 + 200 * i, 190, 173_000);
    }

    private static String english(Component c) {
        return c == null ? "(none)" : BwrText.english(c);
    }

    /** The chute slot rig, its slot's gate a plug, a crane over it and (optionally) a laydown stand on the floor beside the canal. */
    static final class Rig {
        final ChuteSlotTests.Rig s;
        final ServerLevel l;
        final int railY;
        final int railLo;
        final int railHi;
        final int zN;
        final int zS;
        final BlockPos console;
        final BlockPos stand;
        BuildingCraneBlockEntity crane;

        Rig(ServerLevel level, BlockPos o, boolean withStand) throws Exception {
            this.l = level;
            this.s = new ChuteSlotTests.Rig(level, o, 4, 8, 0);
            var env = s.s.env();
            // the slot's gate: a plug from the rim down to the slot's floor
            for (int y = s.slotBottom; y <= s.top; y++) {
                BlockPos p = new BlockPos(s.columnsX[0], y, s.zc);
                l.setBlock(p, BwrBlocks.CANAL_GATE_PLUG.get().defaultBlockState().setValue(TransferCanalBlock.AXIS, Direction.Axis.X)
                        .setValue(TransferCanalBlock.TOP, y == s.top).setValue(TransferCanalBlock.FLOOR, y == s.slotBottom), 3);
            }
            railY = s.top + 10;
            railLo = env.min().getX() - 4;
            railHi = s.x1 + 12;
            zN = s.zc - 9;
            zS = s.zc + 9;
            for (int x = railLo; x <= railHi; x++) {
                for (int z : new int[]{zN, zS}) {
                    l.setBlock(new BlockPos(x, railY, z), BwrBlocks.CRANE_RUNWAY_RAIL.get().defaultBlockState()
                            .setValue(CraneRunwayRailBlock.AXIS, Direction.Axis.X), 2);
                }
            }
            console = new BlockPos(railLo - 1, railY, zN);
            l.setBlock(console, BwrBlocks.BUILDING_CRANE_CONSOLE.get().defaultBlockState(), 2);
            crane = (BuildingCraneBlockEntity) l.getBlockEntity(console);
            stand = new BlockPos(s.x1 + 2, s.top + 1, s.zc - 6);
            if (withStand) {
                l.setBlock(stand, BwrBlocks.GATE_LAYDOWN_STAND.get().defaultBlockState(), 2);
            }
            BridgeSite site = crane.refreshSite();
            if (site == null || !site.ok() || site.reactor(l) != s.reactor()) {
                throw new IllegalStateException("the crane sees the reactor: " + (site == null ? "no survey" : english(site.problem)));
            }
            // the pool's exchange reads its row once
            s.step();
        }

        BlockPos gateBottom() {
            return new BlockPos(s.columnsX[0], s.slotBottom, s.zc);
        }

        int gateHeight() {
            return s.top - s.slotBottom + 1;
        }

        boolean gateHome() {
            for (int y = s.slotBottom; y <= s.top; y++) {
                if (!(l.getBlockState(new BlockPos(s.columnsX[0], y, s.zc)).getBlock() instanceof CanalGatePlugBlock)) {
                    return false;
                }
            }
            return true;
        }

        boolean canalOpen() {
            for (int y = s.slotBottom; y <= s.top; y++) {
                BlockState st = l.getBlockState(new BlockPos(s.columnsX[0], y, s.zc));
                if (!(st.getBlock() instanceof TransferCanalBlock) || CanalGateBlock.closes(st)) {
                    return false;
                }
            }
            return true;
        }

        boolean gateOnStand() {
            for (int i = 0; i < gateHeight(); i++) {
                if (!(l.getBlockState(stand.above(1 + i)).getBlock() instanceof CanalGatePlugBlock)) {
                    return false;
                }
            }
            return l.getBlockState(stand.above(1 + gateHeight())).isAir();
        }

        List<PlugSectionEntity> entities() {
            return l.getEntitiesOfClass(PlugSectionEntity.class, new AABB(console.getCenter().add(-40, -40, -40), console.getCenter().add(60, 40, 40)));
        }

        List<ItemEntity> items() {
            return l.getEntitiesOfClass(ItemEntity.class, new AABB(console.getCenter().add(-40, -40, -40), console.getCenter().add(60, 40, 40)));
        }

        /** Gate plug blocks anywhere in the rig. */
        int gateBlocks() {
            int n = 0;
            for (BlockPos p : BlockPos.betweenClosed(new BlockPos(railLo, s.slotBottom - 2, zN), new BlockPos(railHi, railY, zS))) {
                if (l.getBlockState(p).getBlock() instanceof CanalGatePlugBlock) {
                    n++;
                }
            }
            return n;
        }

        void run(GameTestHelper h) {
            int n = 0;
            while (crane.busy() && n < 120_000) {
                crane.scheduledTick(l);
                n++;
            }
            h.assertTrue(!crane.busy() && crane.phase() == Phase.IDLE, "the crane did not finish: " + crane.phase() + " " + english(crane.haltMessage()));
        }

        void runUntil(Phase phase) {
            int n = 0;
            while (crane.busy() && crane.phase() != phase && n < 120_000) {
                crane.scheduledTick(l);
                n++;
            }
        }

        void dispose() {
            try {
                if (crane != null) {
                    crane.abort();
                    crane.dropCarried(l);
                }
            } catch (RuntimeException ignored) {
                // thrown away anyway
            }
            for (PlugSectionEntity e : entities()) {
                e.discard();
            }
            for (Entity e : items()) {
                e.discard();
            }
            for (BlockPos p : BlockPos.betweenClosed(new BlockPos(railLo - 1, s.top + 1, zN), new BlockPos(railHi, railY, zS))) {
                if (!l.getBlockState(p).isAir()) {
                    l.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
            s.finish();
        }
    }

    private interface Body {
        void run() throws Exception;
    }

    /** Runs the body ten ticks after the fixture was built (an entity added to a chunk only just forced is not visible before then), then cleans up. */
    private static void later(GameTestHelper h, Rig r, Body body) {
        h.runAfterDelay(10, () -> {
            try {
                body.run();
            } catch (RuntimeException | Error e) {
                r.dispose();
                throw e;
            } catch (Exception e) {
                r.dispose();
                throw new IllegalStateException(e);
            }
            r.dispose();
            h.runAfterDelay(SETTLE_TICKS, h::succeed);
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // GATE OUT and GATE IN
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "gates", timeoutTicks = 400)
    public static void gateOutLiftsTheWholeGateOntoItsStandAndTheWaterConnectsThenGateInClosesTheCanal(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), origin(0), true);
        later(h, r, () -> {
            // the pool and the well level, a little over the slot's floor
            r.s.poolAt(r.s.top + 0.6);
            r.s.wellAt(r.s.top + 0.5);
            r.s.step();
            h.assertTrue(r.s.exchange().state() == CanalExchange.State.GATE_CLOSED, "the plug closes the canal: " + r.s.exchange().state());
            List<BlockState> before = new ArrayList<>();
            for (int y = r.s.slotBottom; y <= r.s.top; y++) {
                before.add(r.l.getBlockState(new BlockPos(r.s.columnsX[0], y, r.s.zc)));
            }
            var preview = r.crane.gatePreview();
            h.assertTrue(preview != null && preview.blocks() == 2 && Math.abs(preview.massT() - 2 * CanalGateLift.DEFAULT_TONNES_PER_BLOCK) < 1e-9
                    && preview.rigged() && !preview.over(), "the pendant shows the gate before the lift: " + (preview == null ? "none" : preview.text()));
            h.assertTrue(preview.massT() < PlugLift.RATED_CAPACITY_T, "a few tonnes, far within the rating");
            Component why = r.crane.queueGate(true, null);
            h.assertTrue(why == null, "GATE OUT is queued: " + english(why));
            h.assertTrue(r.crane.gateLift().plan() != null && r.crane.gateLift().plan().lay().equals(r.stand.above()), "to the stand: " + r.crane.gateLift().plan());
            r.runUntil(Phase.TRANSIT);
            h.assertTrue(r.crane.carriedGate() && r.entities().size() == 1 && r.entities().get(0).cells().size() == 2, "the gate hangs from the hook as one entity");
            h.assertTrue(r.canalOpen() && !r.gateHome(), "the column it left is open canal");
            h.assertTrue(r.crane.carriedSummary().contains("canal gate"), "the hook row says so: " + r.crane.carriedSummary());
            r.run(h);
            h.assertTrue(r.gateOnStand() && r.canalOpen() && r.entities().isEmpty() && r.gateBlocks() == 2, "the gate stands on its stand, once: " + r.gateBlocks());
            h.assertTrue(r.crane.gateLift().laid().size() == 1, "the book remembers it");
            // the water connects: the pool runs into the well through the open slot, mass exact
            r.s.poolAt(r.s.top + 0.8);
            r.s.wellAt(r.s.top + 0.3);
            double total = r.s.total();
            r.s.step();
            h.assertTrue(r.s.exchange().state() == CanalExchange.State.TO_WELL && r.s.exchange().flowKgPerS() > 0, "with the gate out the pool and the well are one: " + r.s.exchange().state());
            h.assertTrue(Math.abs(r.s.total() - total) < 1e-6, "what the pool lost the well gained");
            for (int i = 0; i < 4000 && r.s.exchange().state() == CanalExchange.State.TO_WELL; i++) {
                r.s.step();
            }
            h.assertTrue(r.s.exchange().state() == CanalExchange.State.LEVEL && Math.abs(r.s.poolY() - r.s.wellY()) < 0.01, "level: " + r.s.exchange().state());
            // GATE IN: back exactly where it was
            why = r.crane.queueGate(false, null);
            h.assertTrue(why == null, "GATE IN is queued: " + english(why));
            r.run(h);
            h.assertTrue(r.gateHome() && r.entities().isEmpty() && r.gateBlocks() == 2 && r.l.getBlockState(r.stand.above()).isAir(), "the gate is back in its slot, once");
            for (int i = 0; i < before.size(); i++) {
                BlockState now = r.l.getBlockState(new BlockPos(r.s.columnsX[0], r.s.slotBottom + i, r.s.zc));
                h.assertTrue(now.getValue(TransferCanalBlock.AXIS) == before.get(i).getValue(TransferCanalBlock.AXIS)
                        && now.getValue(TransferCanalBlock.TOP) == before.get(i).getValue(TransferCanalBlock.TOP)
                        && now.getValue(TransferCanalBlock.FLOOR) == before.get(i).getValue(TransferCanalBlock.FLOOR), "block " + i + " as it was: " + now);
            }
            h.assertTrue(r.crane.gateLift().laid().isEmpty(), "the book is empty");
            r.s.poolAt(r.s.top + 0.9);
            r.s.wellAt(r.s.top + 0.2);
            total = r.s.total();
            r.s.step();
            h.assertTrue(r.s.exchange().state() == CanalExchange.State.GATE_CLOSED && Math.abs(r.s.total() - total) < 1e-9, "closed again: " + r.s.exchange().state());
            h.assertTrue(r.items().isEmpty(), "nothing dropped");
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "gates", timeoutTicks = 400)
    public static void gateOutIsRefusedWithTheReasonAndNothingMoves(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), origin(1), false);
        later(h, r, () -> {
            if (net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
                CavityFloodCcChecks.verifyGate(h, r.crane, r.gateBottom());
            }
            r.s.poolAt(r.s.top + 0.6);
            r.s.wellAt(r.s.top + 0.5);
            // a big difference across the gate: the well under the slot's floor, the pool full
            r.s.wellAt(r.s.slotBottom - 0.6);
            Component why = r.crane.queueGate(true, null);
            h.assertTrue(why != null && english(why).contains("holds back") && english(why).contains("0.3 m"), "a gate is not pulled against a head: " + english(why));
            double[] sides = new double[3];
            double head = CanalGateLift.headAcross(r.l, CanalGateLift.survey(r.l, r.gateBottom()).gate(), sides);
            h.assertTrue(head > 1.0 && Math.abs(sides[0] - r.s.poolY()) < 1e-9, "the head is the pool over the slot's floor: " + head);
            h.assertTrue(!r.crane.busy() && r.gateHome(), "nothing moved");
            // within 0.3 m it is taken (no stand here: the clear top of a wall beside the slot is the laydown); ABORT before the hook closes forgets it
            r.s.wellAt(r.s.top + 0.4);
            why = r.crane.queueGate(true, null);
            h.assertTrue(why == null && r.crane.busy() && r.crane.gateLift().plan() != null, "within 0.3 m the lift is queued: " + english(why));
            r.crane.abort();
            h.assertTrue(!r.crane.busy() && r.crane.gateLift().plan() == null && r.gateHome(), "aborted before the hook closed: nothing moved");
            // the eyes covered
            BlockPos over = r.gateBottom().above(r.gateHeight());
            r.l.setBlock(over, Blocks.STONE.defaultBlockState(), 2);
            why = r.crane.queueGate(true, null);
            h.assertTrue(why != null && english(why).contains("lifting eyes"), "a covered gate is not rigged: " + english(why));
            r.l.setBlock(over, Blocks.AIR.defaultBlockState(), 2);
            // over the rating
            Component heavy = CanalGateLift.weightProblem(PlugLift.RATED_CAPACITY_T + 0.5, 210, r.gateBottom());
            h.assertTrue(heavy != null && english(heavy).contains("over the crane's rated 125 t"), "over the rating: " + english(heavy));
            h.assertTrue(CanalGateLift.weightProblem(2 * CanalGateLift.DEFAULT_TONNES_PER_BLOCK, 2, r.gateBottom()) == null, "the gate itself is light");
            // not under the crane: a gate outside the rails
            BlockPos far = new BlockPos(r.railHi + 6, r.s.top, r.s.zc);
            r.l.setBlock(far, BwrBlocks.CANAL_GATE_PLUG.get().defaultBlockState(), 2);
            why = r.crane.queueGate(true, far);
            h.assertTrue(why != null && english(why).contains("outside the reach"), "out of reach: " + english(why));
            r.l.setBlock(far, Blocks.AIR.defaultBlockState(), 2);
            // nothing to lift: no gate between the rails
            for (int y = r.s.slotBottom; y <= r.s.top; y++) {
                BlockPos p = new BlockPos(r.s.columnsX[0], y, r.s.zc);
                r.l.setBlock(p, CanalGateLift_open(r.l.getBlockState(p)), 3);
            }
            why = r.crane.queueGate(true, null);
            h.assertTrue(why != null && english(why).contains("sees no canal gate"), "nothing rigged: " + english(why));
            why = r.crane.queueGate(false, null);
            h.assertTrue(why != null && english(why).contains("No canal gate is laid down"), "nothing to put back: " + english(why));
            h.assertTrue(r.items().isEmpty() && r.entities().isEmpty(), "nothing dropped or hung");
        });
    }

    private static BlockState CanalGateLift_open(BlockState gate) {
        return BwrBlocks.TRANSFER_CANAL.get().defaultBlockState().setValue(TransferCanalBlock.AXIS, gate.getValue(TransferCanalBlock.AXIS))
                .setValue(TransferCanalBlock.TOP, gate.getValue(TransferCanalBlock.TOP)).setValue(TransferCanalBlock.FLOOR, gate.getValue(TransferCanalBlock.FLOOR));
    }

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "gates", timeoutTicks = 400)
    public static void gateInIsRefusedWhileItsPlaceInTheCanalIsBlockedAndAHeadChangeStopsTheHookBeforeItCloses(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), origin(2), true);
        later(h, r, () -> {
            r.s.poolAt(r.s.top + 0.6);
            r.s.wellAt(r.s.top + 0.5);
            h.assertTrue(r.crane.queueGate(true, null) == null, "queued");
            // the levels part while the hook is on its way down: the hook does not close on a loaded gate
            r.runUntil(Phase.LATCH);
            h.assertTrue(r.crane.phase() == Phase.LATCH || r.crane.phase() == Phase.HALTED, "at the gate: " + r.crane.phase());
            r.s.wellAt(r.s.slotBottom - 0.6);
            r.runUntil(Phase.TRANSIT);
            h.assertTrue(r.crane.phase() == Phase.HALTED && !r.crane.carriedGate() && r.gateHome(),
                    "halted with the gate in its slot: " + r.crane.phase() + " " + english(r.crane.haltMessage()));
            h.assertTrue(english(r.crane.haltMessage()).contains("holds back"), "and says why: " + english(r.crane.haltMessage()));
            // the hook let go of nothing; levelled again, GATE OUT takes it
            r.crane.resume();
            r.run(h);
            h.assertTrue(r.gateHome() && !r.crane.carriedGate(), "RESUME after a refused hook-on lifts nothing");
            r.s.wellAt(r.s.top + 0.5);
            h.assertTrue(r.crane.queueGate(true, null) == null, "GATE OUT once the levels are within 0.3 m");
            r.run(h);
            h.assertTrue(r.gateOnStand() && r.canalOpen(), "out");
            // the slot is built over: GATE IN is refused and nothing moves
            BlockPos inSlot = r.gateBottom();
            BlockState canal = r.l.getBlockState(inSlot);
            r.l.setBlock(inSlot, Blocks.STONE.defaultBlockState(), 2);
            Component why = r.crane.queueGate(false, null);
            h.assertTrue(why != null && english(why).contains("not open"), "a blocked place is refused: " + english(why));
            h.assertTrue(!r.crane.busy() && r.gateOnStand(), "nothing moved");
            r.l.setBlock(inSlot, canal, 2);
            h.assertTrue(r.crane.queueGate(false, null) == null, "with the canal open again it goes");
            r.run(h);
            h.assertTrue(r.gateHome() && r.gateBlocks() == 2, "home");
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // Saves, ABORT and RESUME, a broken console
    // ------------------------------------------------------------------------------------------------------------

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "gates", timeoutTicks = 400)
    public static void aGateHangingFromTheHookSavesAndLoadsOnceAndAbortThenGateOutFinishesTheLift(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), origin(3), true);
        later(h, r, () -> {
            r.s.poolAt(r.s.top + 0.6);
            r.s.wellAt(r.s.top + 0.5);
            var regs = r.l.registryAccess();
            h.assertTrue(r.crane.queueGate(true, null) == null, "queued");
            r.runUntil(Phase.TRANSIT);
            for (int i = 0; i < 40; i++) {
                r.crane.scheduledTick(r.l);
            }
            h.assertTrue(r.crane.phase() == Phase.TRANSIT && r.crane.carriedGate(), "in transit with the gate: " + r.crane.phase());
            double progress = r.crane.progress();
            var tipBefore = r.crane.tip();
            CompoundTag craneTag = r.crane.saveWithoutMetadata(regs);
            h.assertTrue(craneTag.contains("Gate") && craneTag.getCompound("Gate").contains("Entity") && craneTag.getCompound("Gate").contains("Plan"),
                    "the crane saved the gate's plan and the entity's id");
            PlugSectionEntity e = r.entities().get(0);
            CompoundTag entityTag = new CompoundTag();
            h.assertTrue(e.save(entityTag), "the entity is saved");
            java.util.UUID id = e.getUUID();
            e.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            h.assertTrue(r.entities().isEmpty() && r.gateBlocks() == 0 && r.items().isEmpty(), "unloading put nothing in the world and dropped nothing");
            r.l.removeBlockEntity(r.console);
            var again = new BuildingCraneBlockEntity(r.console, r.l.getBlockState(r.console));
            again.setLevel(r.l);
            again.loadWithComponents(craneTag, regs);
            r.l.setBlockEntity(again);
            r.crane = again;
            Entity loaded = EntityType.create(entityTag, r.l).orElseThrow();
            h.assertTrue(r.l.addFreshEntity(loaded), "the entity loads");
            h.assertTrue(r.entities().size() == 1 && loaded.getUUID().equals(id) && again.carriedGate(), "exactly one entity, the same one, on the hook");
            h.assertTrue(Math.abs(again.progress() - progress) < 1e-9 && Math.abs(again.tip().x() - tipBefore.x()) < 1e-9, "the crane carries on from where it was");
            // ABORT leaves it on the hook; GATE OUT again finishes the lift from the hook
            for (int i = 0; i < 20; i++) {
                again.scheduledTick(r.l);
            }
            again.abort();
            h.assertTrue(!again.busy() && again.carriedGate() && r.entities().size() == 1, "aborted with the gate on the hook");
            h.assertTrue(again.queuePlug(true, null) != null, "nothing else is lifted while the gate hangs");
            h.assertTrue(again.queueGate(true, null) == null, "GATE OUT continues from the hook");
            r.run(h);
            h.assertTrue(r.gateOnStand() && r.entities().isEmpty() && r.gateBlocks() == 2 && r.items().isEmpty(), "on the stand, once, nothing lost");
            CompoundTag done = again.saveWithoutMetadata(regs);
            h.assertTrue(done.contains("Gate") && !done.getCompound("Gate").contains("Entity") && !done.getCompound("Gate").contains("Plan"),
                    "only the laydown book remains");
            // an idle crane with no gate data loads as before
            var fresh = new BuildingCraneBlockEntity(r.console, r.l.getBlockState(r.console));
            fresh.loadWithComponents(new CompoundTag(), regs);
            h.assertTrue(!fresh.carriedGate() && fresh.gateLift().laid().isEmpty() && fresh.gateLift().plan() == null, "an old crane has no gate");
        });
    }

    @GameTest(template = "empty", templateNamespace = "bwrplug", batch = "gates", timeoutTicks = 400)
    public static void aConsoleBrokenUnderAHangingGatePutsItBackInItsCanal(GameTestHelper h) throws Exception {
        Rig r = new Rig(h.getLevel(), origin(4), true);
        later(h, r, () -> {
            r.s.poolAt(r.s.top + 0.6);
            r.s.wellAt(r.s.top + 0.5);
            h.assertTrue(r.crane.queueGate(true, null) == null, "queued");
            r.runUntil(Phase.TRANSIT);
            h.assertTrue(r.crane.carriedGate() && r.canalOpen(), "hanging, the canal open");
            r.l.setBlock(r.console, Blocks.AIR.defaultBlockState(), 3);
            h.assertTrue(r.gateHome() && r.entities().isEmpty() && r.items().isEmpty() && r.gateBlocks() == 2, "back in its slot, once, nothing dropped");
            r.crane = null;
        });
    }
}
