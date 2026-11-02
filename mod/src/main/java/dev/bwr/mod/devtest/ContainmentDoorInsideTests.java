package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.containment.ContainmentAirlockBlock;
import dev.bwr.mod.containment.ContainmentAirlockBlockEntity;
import dev.bwr.mod.containment.ContainmentVaultDoorBlock;
import dev.bwr.mod.containment.ContainmentVaultDoorBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A player who stands inside a containment door must still be able to click what they look at. The airlock chamber and
 * the cell of an open leaf or vault door used to have a full-block selection outline; a ray that starts inside such a
 * block hits it at once, so a player in the chamber could click neither door, and a player in an open doorway nothing
 * beyond it. The ray here is the one the game casts from the eye ({@code Player.pick}), the click the real right-click path.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class ContainmentDoorInsideTests {
    private ContainmentDoorInsideTests() {}

    private static FakePlayer player(ServerLevel level, String name) {
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes(("door-inside-" + name).getBytes(StandardCharsets.UTF_8)), "door-" + name));
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    /** Whatever the player sees when looking at {@code target} from where they stand. */
    private static HitResult look(FakePlayer player, Vec3 target) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
        return player.pick(4.5, 1.0F, false);
    }

    private static String describe(ServerLevel level, HitResult hit) {
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return "nothing";
        return level.getBlockState(block.getBlockPos()) + " at " + block.getBlockPos().toShortString();
    }

    private static boolean hits(HitResult hit, BlockPos pos) {
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && block.getBlockPos().equals(pos);
    }

    /** The airlock cell index the ray lands on, or -1 for anything else. */
    private static int airlockCell(ServerLevel level, HitResult hit) {
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return -1;
        var state = level.getBlockState(block.getBlockPos());
        return state.getBlock() instanceof ContainmentAirlockBlock ? state.getValue(ContainmentAirlockBlock.CELL) : -1;
    }

    private static boolean click(FakePlayer player, ServerLevel level, HitResult hit) {
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                && player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, block).consumesAction();
    }

    private static boolean walkable(ServerLevel level, BlockPos pos) {
        return level.noCollision(new AABB(pos).deflate(0.05));
    }

    private static void lay(ServerLevel level, BlockPos pos, boolean solid) {
        level.setBlock(pos, (solid ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
    }

    /** Runs {@code setup} once the fixture's chunks are live, then the per-tick check it returns. */
    private static void whenLiveEachTick(GameTestHelper helper, AABB bounds, java.util.function.Supplier<Runnable> setup) {
        Runnable[] tick = {null};
        helper.onEachTick(() -> {
            if (tick[0] != null) tick[0].run();
        });
        EntityLookupProbe.runWhenLive(helper, bounds, () -> tick[0] = setup.get());
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aPlayerInTheAirlockChamberCanWorkBothDoorsAndWalkOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos root = new BlockPos(48212, 220, 48212);
        whenLiveEachTick(helper, new AABB(root).inflate(12), () -> {
            // a stone floor, stone round the chamber (sides and ceiling), a stone marker out in the corridor east of the outer leaf
            for (int x = -5; x <= 9; x++) for (int z = -3; z <= 3; z++) for (int y = -1; y <= 4; y++) {
                boolean shell = x >= 0 && x <= 2 && y >= 0 && (Math.abs(z) == 1 && y <= 2 || y == 2 && Math.abs(z) <= 1);
                lay(level, root.offset(x, y, z), y == -1 || shell);
            }
            BlockPos marker = root.offset(6, 1, 0);
            lay(level, marker, true);
            ContainmentAirlockBlock block = BwrBlocks.CONTAINMENT_AIRLOCK.get();
            var state = block.defaultBlockState().setValue(ContainmentAirlockBlock.FACING, Direction.EAST);
            for (int i = 0; i < 6; i++) level.setBlock(block.cell(root, Direction.EAST, i), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(root, state, 3);
            block.setPlacedBy(level, root, state, null, ItemStack.EMPTY);
            var door = (ContainmentAirlockBlockEntity) level.getBlockEntity(root);
            helper.assertTrue(door != null, "the airlock did not place");
            FakePlayer player = player(level, "chamber");
            // in the middle of the chamber (the cells at depth 1), standing on the floor
            player.setPos(new Vec3(root.getX() + 1.5, root.getY(), root.getZ() + 0.5));
            helper.assertTrue(walkable(level, block.cell(root, Direction.EAST, 2)) && walkable(level, block.cell(root, Direction.EAST, 3)),
                    "the chamber is not walkable");
            Vec3 innerLeaf = Vec3.atCenterOf(block.cell(root, Direction.EAST, 1));
            Vec3 outerLeaf = Vec3.atCenterOf(block.cell(root, Direction.EAST, 5));
            Vec3 outerLow = Vec3.atCenterOf(block.cell(root, Direction.EAST, 4));

            // 1. from inside, the ray reaches each door and not the chamber around the player
            HitResult hit = look(player, innerLeaf);
            helper.assertTrue(airlockCell(level, hit) == 1 || airlockCell(level, hit) == 0,
                    "looking at the inner door from inside the chamber hits " + describe(level, hit));
            hit = look(player, outerLeaf);
            helper.assertTrue(airlockCell(level, hit) == 5 || airlockCell(level, hit) == 4,
                    "looking at the outer door from inside the chamber hits " + describe(level, hit));
            hit = look(player, outerLow);
            helper.assertTrue(airlockCell(level, hit) == 4 || airlockCell(level, hit) == 5,
                    "looking down at the outer door from inside the chamber hits " + describe(level, hit));

            // 2. each click works: the inner leaf opens, the interlock refuses the outer one meanwhile
            helper.assertTrue(click(player, level, look(player, innerLeaf)) && door.innerOpen(), "the click on the inner door did not open it");
            click(player, level, look(player, outerLeaf));
            helper.assertTrue(door.innerOpen() && !door.outerOpen(), "the interlock let the outer door open beside the inner one");
            int[] phase = {0};
            long[] since = {level.getGameTime()};
            return () -> {
                // the leaves swing for 12 ticks and are solid or clear by the time they have
                if (level.getGameTime() - since[0] < 30) return;
                since[0] = level.getGameTime();
                switch (phase[0]++) {
                    case 0 -> {
                        helper.assertTrue(walkable(level, block.cell(root, Direction.EAST, 0)) && walkable(level, block.cell(root, Direction.EAST, 1)),
                                "the open inner leaf is not walkable");
                        helper.assertTrue(!walkable(level, block.cell(root, Direction.EAST, 4)), "the shut outer leaf is walkable");
                        // 3. the open inner leaf can be clicked shut again from the chamber
                        HitResult h = look(player, innerLeaf);
                        helper.assertTrue(airlockCell(level, h) == 1 && click(player, level, h) && !door.innerOpen(),
                                "the open inner door could not be shut from the chamber: " + describe(level, h));
                    }
                    case 1 -> {
                        helper.assertTrue(!walkable(level, block.cell(root, Direction.EAST, 1)), "the shut inner leaf is walkable");
                        // and then the outer one opens
                        HitResult h = look(player, outerLeaf);
                        helper.assertTrue(airlockCell(level, h) == 5 && click(player, level, h) && door.outerOpen() && !door.innerOpen(),
                                "the outer door could not be opened from the chamber: " + describe(level, h));
                    }
                    case 2 -> {
                        // 4. the way out is clear: chamber, outer doorway, corridor, as one tunnel the width of the player
                        AABB tunnel = player.getBoundingBox().minmax(player.getBoundingBox().move(2, 0, 0));
                        helper.assertTrue(level.noCollision(tunnel), "the way from the chamber out through the outer door is blocked");
                        helper.assertTrue(!walkable(level, block.cell(root, Direction.EAST, 0)) && !walkable(level, block.cell(root, Direction.EAST, 1)),
                                "the inner wall is not solid with the outer door open");
                        for (BlockPos wall : new BlockPos[] {root.offset(1, 0, 1), root.offset(1, 1, -1), root.offset(1, 2, 0), root.offset(1, -1, 0)})
                            helper.assertTrue(!walkable(level, wall), "the chamber wall at " + wall.toShortString() + " is not solid");
                        // standing in the open outer doorway, the player can still aim past the leaf
                        player.setPos(new Vec3(root.getX() + 2.5, root.getY(), root.getZ() + 0.5));
                        HitResult h = look(player, Vec3.atCenterOf(marker));
                        helper.assertTrue(hits(h, marker), "looking out from the open outer doorway hits " + describe(level, h));
                        player.setPos(new Vec3(root.getX() + 3.5, root.getY(), root.getZ() + 0.5));
                        helper.assertTrue(level.noCollision(player.getBoundingBox()), "the player cannot stand outside the outer door");
                        helper.succeed();
                    }
                    default -> { }
                }
            };
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aPlayerInAnOpenVaultDoorwayCanAimPastIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos root = new BlockPos(48312, 220, 48312);
        whenLiveEachTick(helper, new AABB(root).inflate(12), () -> {
            for (int x = -2; x <= 8; x++) for (int z = -2; z <= 6; z++) for (int y = -1; y <= 5; y++)
                lay(level, root.offset(x, y, z), y == -1);
            var block = BwrBlocks.CONTAINMENT_VAULT_DOOR.get();
            var state = block.defaultBlockState().setValue(ContainmentVaultDoorBlock.FACING, Direction.EAST);
            level.setBlock(root, state, 3);
            block.setPlacedBy(level, root, state, null, ItemStack.EMPTY);
            var door = (ContainmentVaultDoorBlockEntity) level.getBlockEntity(root);
            door.energy().receiveEnergy(20_000, false);
            helper.assertTrue(door.setOpen(true, true), "powered opening refused");
            BlockPos marker = root.offset(4, 1, 1);
            lay(level, marker, true);
            return () -> {
                if (door.state() != ContainmentVaultDoorBlockEntity.State.OPEN) return;
                FakePlayer player = player(level, "vault");
                // in the opening: the middle column of the door (cells 1, 4, 7), the eyes in the middle row
                player.setPos(new Vec3(root.getX() + 0.5, root.getY(), root.getZ() + 1.5));
                HitResult hit = look(player, Vec3.atCenterOf(marker));
                helper.assertTrue(hits(hit, marker), "looking out of the open vault doorway hits " + describe(level, hit));
                // the rest of the door is still there to be clicked from the doorway
                BlockPos side = block.cell(root, Direction.EAST, 5);
                hit = look(player, Vec3.atCenterOf(side));
                helper.assertTrue(hits(hit, side), "the door beside the player's cell cannot be picked out: " + describe(level, hit));
                helper.succeed();
            };
        });
    }
}
