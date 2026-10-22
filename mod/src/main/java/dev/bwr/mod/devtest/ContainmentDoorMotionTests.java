package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import dev.bwr.mod.containment.ContainmentVaultDoorBlock;
import dev.bwr.mod.containment.ContainmentVaultDoorBlockEntity;
import dev.bwr.mod.containment.DoorMotion;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The containment doors' collision against what is drawn. A leaf is clear (walk-through) exactly when it is at least half open,
 * in both directions, on the server's own clock: the opening is solid while a leaf that is opening is still across it, and
 * clear while a leaf that is closing has not yet come back. The same open fraction is what the renderers draw
 * ({@code swing}), so this also pins "never passable while drawn closed".
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class ContainmentDoorMotionTests {
    private ContainmentDoorMotionTests() {}

    private static final double EPS = 1e-9;

    private static void chunks(ServerLevel level, BlockPos origin, int radius, boolean on) {
        for (int x = (origin.getX() - radius) >> 4; x <= (origin.getX() + radius) >> 4; x++)
            for (int z = (origin.getZ() - radius) >> 4; z <= (origin.getZ() + radius) >> 4; z++)
                FixtureChunks.force(level, origin, x, z, on);
    }

    private static void air(ServerLevel level, BlockPos origin, int radius) {
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            level.getChunk((origin.getX() + x) >> 4, (origin.getZ() + z) >> 4);
            for (int y = -1; y <= 6; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    /** True when an entity can stand in the cell: nothing solid there. */
    private static boolean walkable(ServerLevel level, BlockPos pos) {
        return level.noCollision(new AABB(pos).deflate(0.05));
    }

    private static boolean vaultWalkable(ServerLevel level, ContainmentVaultDoorBlockEntity door) {
        var block = (ContainmentVaultDoorBlock) door.getBlockState().getBlock();
        for (int i = 0; i < block.size() * block.size(); i++)
            if (!walkable(level, block.cell(door.getBlockPos(), Direction.EAST, i))) return false;
        return true;
    }

    private static ContainmentVaultDoorBlockEntity vault(ServerLevel level, BlockPos root, int size) {
        var block = size == 3 ? BwrBlocks.CONTAINMENT_VAULT_DOOR.get() : BwrBlocks.CONTAINMENT_VAULT_DOOR_4X4.get();
        var state = block.defaultBlockState().setValue(ContainmentVaultDoorBlock.FACING, Direction.EAST);
        level.setBlock(root, state, 3);
        block.setPlacedBy(level, root, state, null, ItemStack.EMPTY);
        return (ContainmentVaultDoorBlockEntity) level.getBlockEntity(root);
    }

    /** Door cells of one leaf of the airlock: depth 0 is the inner leaf (cells 0 and 1), depth 2 the outer one (4 and 5). */
    private static boolean leafWalkable(ServerLevel level, ContainmentAirlockBlock block, BlockPos root, int depth) {
        return walkable(level, block.cell(root, Direction.EAST, depth * 2)) && walkable(level, block.cell(root, Direction.EAST, depth * 2 + 1));
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void vaultIsSolidUntilTheLeafSwingsClearAndSolidAgainBeforeItShuts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(47312, 220, 47312);
        chunks(level, origin, 8, true);
        air(level, origin, 6);
        var door = vault(level, origin, 3);
        door.energy().receiveEnergy(20_000, false);
        helper.assertTrue(!vaultWalkable(level, door), "a closed vault door could be walked through");
        helper.assertTrue(door.setOpen(true, true), "powered opening refused");
        var save = door.saveWithoutMetadata(level.registryAccess());
        long total = save.getLong("StrokeEnd") - save.getLong("StrokeStart");
        helper.assertTrue(total == ContainmentVaultDoorBlockEntity.POWERED_TICKS, "powered stroke is " + total + " ticks");
        long gateOpen = DoorMotion.gateTicks(total, DoorMotion.VAULT_LATCH, false);
        long gateClose = DoorMotion.gateTicks(total, DoorMotion.VAULT_LATCH, true);
        helper.assertTrue(!level.getBlockState(door.getBlockPos()).getValue(ContainmentVaultDoorBlock.SEALED)
                        && level.getBlockState(door.getBlockPos()).getValue(ContainmentVaultDoorBlock.HELD),
                "an opening vault must break the seal at once and still stand across the opening: " + level.getBlockState(door.getBlockPos()));
        long[] opening = {level.getGameTime(), -1};
        long[] closing = {-1, -1};
        helper.onEachTick(() -> {
            boolean walk = vaultWalkable(level, door);
            double swing = door.swing(0);
            if (closing[0] < 0) {
                long k = level.getGameTime() - opening[0];
                if (walk && swing < 0.5 - EPS) helper.fail("opening vault walkable while drawn shut at tick " + k + " swing=" + swing);
                if (!walk && swing > 0.5 + EPS) helper.fail("opening vault solid while drawn wide at tick " + k + " swing=" + swing);
                if (level.getBlockState(door.getBlockPos()).getValue(ContainmentVaultDoorBlock.SEALED))
                    helper.fail("opening vault sealed at tick " + k);
                if (walk && opening[1] < 0) opening[1] = k;
                if (door.state() == ContainmentVaultDoorBlockEntity.State.OPEN) {
                    helper.assertTrue(k == total, "powered opening finished at tick " + k + ", not " + total);
                    helper.assertTrue(opening[1] == gateOpen, "walkable from tick " + opening[1] + ", expected " + gateOpen);
                    helper.assertTrue(walk && swing >= 1 - EPS, "an open vault is not clear and wide open");
                    door.energy().receiveEnergy(20_000, false);
                    helper.assertTrue(door.setOpen(false, true), "powered closing refused");
                    closing[0] = level.getGameTime();
                }
                return;
            }
            long k = level.getGameTime() - closing[0];
            if (walk && swing < 0.5 - EPS) helper.fail("closing vault walkable while drawn shut at tick " + k + " swing=" + swing);
            if (!walk && swing > 0.5 + EPS) helper.fail("closing vault solid while drawn wide at tick " + k + " swing=" + swing);
            if (!walk && closing[1] < 0) closing[1] = k;
            if (door.state() == ContainmentVaultDoorBlockEntity.State.CLOSED_SEALED) {
                helper.assertTrue(k == total, "powered closing finished at tick " + k + ", not " + total);
                helper.assertTrue(closing[1] == gateClose, "solid again from tick " + closing[1] + ", expected " + gateClose);
                var root = level.getBlockState(door.getBlockPos());
                helper.assertTrue(root.getValue(ContainmentVaultDoorBlock.SEALED) && !root.getValue(ContainmentVaultDoorBlock.HELD) && !walk,
                        "a closed vault is not sealed and solid: " + root);
                LogUtils.getLogger().info("VAULT DOOR TIMING: powered stroke " + total + " ticks (" + total / 20.0 + " s), walkable after "
                        + opening[1] + " ticks opening, solid after " + closing[1] + " ticks closing");
                chunks(level, origin, 8, false);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void bigVaultFollowsTheSameClearance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(47412, 220, 47412);
        chunks(level, origin, 8, true);
        air(level, origin, 6);
        var door = vault(level, origin, 4);
        door.energy().receiveEnergy(20_000, false);
        helper.assertTrue(door.setOpen(true, true), "powered opening of the 4x4 refused");
        long[] start = {level.getGameTime()};
        helper.onEachTick(() -> {
            boolean walk = vaultWalkable(level, door);
            double swing = door.swing(0);
            if (walk && swing < 0.5 - EPS) helper.fail("4x4 vault walkable while drawn shut, swing=" + swing);
            if (!walk && swing > 0.5 + EPS) helper.fail("4x4 vault solid while drawn wide, swing=" + swing);
            if (door.state() == ContainmentVaultDoorBlockEntity.State.OPEN) {
                helper.assertTrue(walk, "an open 4x4 vault is not clear");
                chunks(level, origin, 8, false);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void airlockLeavesAreSolidUntilSwungClearAndNeverBothDrawnOpen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos root = new BlockPos(47512, 220, 47512);
        chunks(level, root, 8, true);
        air(level, root, 6);
        var block = BwrBlocks.CONTAINMENT_AIRLOCK.get();
        var state = block.defaultBlockState().setValue(ContainmentAirlockBlock.FACING, Direction.EAST);
        level.setBlock(root, state, 3);
        block.setPlacedBy(level, root, state, null, ItemStack.EMPTY);
        var door = (ContainmentAirlockBlockEntity) level.getBlockEntity(root);
        helper.assertTrue(!leafWalkable(level, block, root, 0) && !leafWalkable(level, block, root, 2) && leafWalkable(level, block, root, 1),
                "a closed airlock's leaves are not both solid with a clear chamber between");

        // The pass, as a player makes it: outer opens, walks in, outer closes and inner opens, walks through.
        // 'Human' time is a click and a step each time (STEP ticks); everything else is the doors.
        final int STEP = 10;
        long[] t = new long[8];       // 0 outer command, 1 outer walkable, 2 closing+inner command, 3 inner walkable
        int[] phase = {0};
        long[] doorTicks = {0};
        helper.assertTrue(door.setOuterOpen(true, false), "outer leaf refused to open");
        t[0] = level.getGameTime();
        helper.assertTrue(!leafWalkable(level, block, root, 2), "outer leaf walkable the instant it was told to open");
        var outerCell = level.getBlockState(block.cell(root, Direction.EAST, 4));
        helper.assertTrue(outerCell.getValue(ContainmentAirlockBlock.OUTER_OPEN) && outerCell.getValue(ContainmentAirlockBlock.OUTER_MOVE) == 1,
                "an opening leaf must hold its cells solid: " + outerCell);
        helper.onEachTick(() -> {
            long now = level.getGameTime();
            double inner = door.innerSwing(0), outer = door.outerSwing(0);
            boolean innerWalk = leafWalkable(level, block, root, 0), outerWalk = leafWalkable(level, block, root, 2);
            if (inner > 1e-6 && outer > 1e-6) helper.fail("both leaves drawn open at once at tick " + (now - t[0]) + ": " + inner + " " + outer);
            if (outerWalk && outer < 0.5 - EPS) helper.fail("outer leaf walkable while drawn shut at tick " + (now - t[0]) + " swing=" + outer + " holds: " + door.holds() + " now " + level.getGameTime() + " cell " + level.getBlockState(block.cell(root, Direction.EAST, 4)));
            if (!outerWalk && outer > 0.5 + EPS) helper.fail("outer leaf solid while drawn wide at tick " + (now - t[0]) + " swing=" + outer + " holds: " + door.holds() + " now " + level.getGameTime() + " cell " + level.getBlockState(block.cell(root, Direction.EAST, 4)));
            if (innerWalk && inner < 0.5 - EPS) helper.fail("inner leaf walkable while drawn shut at tick " + (now - t[0]) + " swing=" + inner);
            if (!innerWalk && inner > 0.5 + EPS) helper.fail("inner leaf solid while drawn wide at tick " + (now - t[0]) + " swing=" + inner);
            switch (phase[0]) {
                case 0 -> {
                    if (outerWalk) {
                        t[1] = now;
                        phase[0] = 1;
                        helper.assertTrue(t[1] - t[0] == DoorMotion.gateTicks(DoorMotion.AIRLOCK_LEAF_TICKS, DoorMotion.AIRLOCK_LATCH, false),
                                "outer leaf walkable after " + (t[1] - t[0]) + " ticks");
                    }
                }
                case 1 -> {
                    if (now - t[1] >= STEP) {
                        helper.assertTrue(door.setOuterOpen(false, false) && door.setInnerOpen(true, false), "the interlock refused the second step");
                        t[2] = now;
                        phase[0] = 2;
                    }
                }
                case 2 -> {
                    if (innerWalk) {
                        t[3] = now;
                        doorTicks[0] = (t[1] - t[0]) + (t[3] - t[2]);
                        long total = t[3] - t[0] + STEP;   // plus the walk to the far side
                        LogUtils.getLogger().info("AIRLOCK PASS: outer walkable after " + (t[1] - t[0]) + " ticks, inner walkable " + (t[3] - t[2])
                                + " ticks after the second command; the doors take " + doorTicks[0] + " ticks (" + doorTicks[0] / 20.0
                                + " s), the whole pass with " + STEP + "-tick steps " + total + " ticks (" + total / 20.0 + " s)");
                        helper.assertTrue(total <= 160, "an airlock pass took " + total + " ticks");
                        helper.assertTrue(!door.outerOpen() && door.innerOpen(), "interlock state after the pass");
                        phase[0] = 3;
                        chunks(level, root, 8, false);
                        helper.succeed();
                    }
                }
                default -> { }
            }
        });
    }

    /** The owner's targets: a vault door opens or closes in 2 to 3 seconds with power, and the hand-wheel is a fraction of a minute. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void vaultStrokeMeetsTheTargets(GameTestHelper helper) {
        helper.assertTrue(ContainmentVaultDoorBlockEntity.POWERED_TICKS >= 40 && ContainmentVaultDoorBlockEntity.POWERED_TICKS <= 60,
                "powered vault stroke is " + ContainmentVaultDoorBlockEntity.POWERED_TICKS + " ticks, not 2 to 3 seconds");
        helper.assertTrue(ContainmentVaultDoorBlockEntity.MANUAL_TICKS <= 200 && ContainmentVaultDoorBlockEntity.MANUAL_TICKS > ContainmentVaultDoorBlockEntity.POWERED_TICKS,
                "hand-wheel stroke is " + ContainmentVaultDoorBlockEntity.MANUAL_TICKS + " ticks");
        helper.succeed();
    }

    /** A door saved half way through an old 60 s hand-wheel stroke (or 10 s powered one) finishes in the new time, from where it was. */
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void vaultSavedMidStrokeBeforeTheRetimeFinishesInTheNewTime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(47612, 220, 47612);
        chunks(level, origin, 8, true);
        air(level, origin, 6);
        var door = vault(level, origin, 3);
        long now = level.getGameTime();
        for (int legacy : new int[] {DoorMotion.LEGACY_MANUAL_TICKS, DoorMotion.LEGACY_POWERED_TICKS}) {
            var tag = door.saveWithoutMetadata(level.registryAccess());
            tag.putString("State", "OPENING");
            tag.putLong("StrokeStart", now - legacy / 2);
            tag.putLong("StrokeEnd", now + legacy / 2);
            door.loadWithComponents(tag, level.registryAccess());
            door.onLoad();
            var saved = door.saveWithoutMetadata(level.registryAccess());
            long target = legacy == DoorMotion.LEGACY_MANUAL_TICKS ? ContainmentVaultDoorBlockEntity.MANUAL_TICKS : ContainmentVaultDoorBlockEntity.POWERED_TICKS;
            helper.assertTrue(saved.getLong("StrokeEnd") - saved.getLong("StrokeStart") == target,
                    "an old " + legacy + " tick stroke was not rescaled to " + target + ": " + (saved.getLong("StrokeEnd") - saved.getLong("StrokeStart")));
            helper.assertTrue(saved.getLong("StrokeEnd") - now == target / 2,
                    "an old stroke saved half way did not have half of the new stroke left: " + (saved.getLong("StrokeEnd") - now));
        }
        chunks(level, origin, 8, false);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void clientStrokeClockStartsAtArrivalAndJoinsOnTheServerClock(GameTestHelper helper) {
        var clock = new DoorMotion.StrokeClock();
        // A client that joins during a stroke only has the server's clock: 3 of 50 ticks in.
        helper.assertTrue(Math.abs(clock.fraction(100, 50, "OPENING", 103) - 0.06) < EPS, "a joining client did not follow the server clock");
        // A stroke that starts while it watches is timed from its own arrival, whatever its clock says: 4 ticks behind the server...
        helper.assertTrue(clock.fraction(300, 50, "CLOSING", 296) == 0, "a stroke seen 4 ticks early did not start at 0: " + clock.fraction(300, 50, "CLOSING", 296));
        helper.assertTrue(Math.abs(clock.fraction(300, 50, "CLOSING", 321) - 0.5) < EPS, "the stroke did not run from its arrival");
        // ...and 7 ticks ahead of it.
        helper.assertTrue(clock.fraction(500, 50, "OPENING", 507) == 0, "a stroke seen 7 ticks late did not start at 0: " + clock.fraction(500, 50, "OPENING", 507));
        helper.assertTrue(clock.fraction(500, 50, "OPENING", 600) == 1, "a finished stroke is not at 1");
        helper.succeed();
    }
}
