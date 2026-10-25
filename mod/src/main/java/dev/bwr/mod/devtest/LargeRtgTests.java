package dev.bwr.mod.devtest;

import dev.bwr.core.isotope.LargeRtgStore;
import dev.bwr.core.isotope.RadioisotopePower;
import dev.bwr.mod.fuel.ReprocessingPlantBlockEntity;
import dev.bwr.mod.isotope.LargeRtgBlock;
import dev.bwr.mod.isotope.LargeRtgBlockEntity;
import dev.bwr.mod.isotope.RtgBlockEntity;
import dev.bwr.mod.isotope.RtgFuel;
import dev.bwr.mod.isotope.RtgFuels;
import dev.bwr.mod.isotope.RtgHeat;
import dev.bwr.mod.radiation.RadiationSources;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.signal.SignalProviders;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The Large RTG (2 x 3 x 2 multiblock, 1,000 kg bulk store), RTG heat and the isotope ingot and block pattern (RTG.md):
 * placement and breaking in all four facings, loading by hopper and capability up to the cap and the refusal after it, FE
 * into a real neighbour, heat-only mode, the dose readout and the point source, save and reload (and an old small-RTG save),
 * decay in the block, signals, the recipes and the data-map lines, and the mod running with Cold Sweat absent. It is its
 * own namespace (and not part of {@link RtgTests}) so the two batches stay apart. Cold Sweat and CC:Tweaked types live in
 * {@code RtgColdSweatChecks} and {@code LargeRtgCcChecks}, never in this class.
 */
@GameTestHolder("bwrlargertg")
@PrefixGameTestTemplate(false)
public final class LargeRtgTests {

    private static final String NS = "bwrlargertg";

    /** Places the item the way a player does (BlockItem.place: the footprint check, the controller, then the other cells). */
    private static boolean place(ServerLevel l, BlockPos at, Direction facing) {
        var stack = new ItemStack(BwrBlocks.LARGE_RTG.get());
        // A player looking at facing.getOpposite() places an assembly whose front (the hatch) faces them.
        return ((BlockItem) stack.getItem()).place(new DirectionalPlaceContext(l, at, facing.getOpposite(), stack, Direction.UP)).consumesAction();
    }

    /** A survival player with a pickaxe breaks one cell (playerWillDestroy, the break event, loot), not a bare destroyBlock. */
    private static void breakAsPlayer(ServerLevel l, BlockPos p) {
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(l);
        var held = player.getMainHandItem().copy();
        var mode = player.gameMode.getGameModeForPlayer();
        try {
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
            player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            player.setPos(p.getX() + 2.5, p.getY(), p.getZ() + .5);
            player.gameMode.destroyBlock(p);
        } finally {
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
            player.gameMode.changeGameModeForPlayer(mode);
        }
    }

    private static void clear(ServerLevel l, BlockPos a, BlockPos b) {
        for (var p : BlockPos.betweenClosed(a, b)) {
            l.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    /** The footprint written out literally (not read from the layout): offsets from the controller for each facing. */
    private static int[] box(Direction facing) {
        // {minDx, maxDx, minDz, maxDz}
        return switch (facing) {
            case NORTH -> new int[] {0, 1, 0, 1};
            case EAST -> new int[] {-1, 0, 0, 1};
            case SOUTH -> new int[] {-1, 0, -1, 0};
            default -> new int[] {0, 1, -1, 0};
        };
    }

    private static LargeRtgBlockEntity assembled(ServerLevel l, BlockPos root, Direction facing) {
        l.getChunk(root);
        clear(l, root.offset(-2, -1, -2), root.offset(3, 5, 3));
        if (!place(l, root, facing)) {
            throw new AssertionError("placement refused on a clear footprint facing " + facing);
        }
        return (LargeRtgBlockEntity) l.getBlockEntity(root);
    }

    private static CompoundTag save(ServerLevel l, net.minecraft.world.level.block.entity.BlockEntity be) {
        return be.saveWithFullMetadata(l.registryAccess());
    }

    // ------------------------------------------------------------------------------------------------------------
    // The multiblock

    /** One item places 12 cells in all four facings; only the controller has a block entity; breaking any part clears it and drops one item and the contents. */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 800)
    public static void assemblyPlacesAndBreaksInAllFacings(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos o = new BlockPos(7200, 190, 7200);
        l.getChunk(o);
        // hold the chunks so item entities exist for the drop counts
        java.util.List<long[]> held = new java.util.ArrayList<>();
        for (int cx = (o.getX() - 40) >> 4; cx <= (o.getX() + 60) >> 4; cx++) {
            for (int cz = (o.getZ() - 8) >> 4; cz <= (o.getZ() + 8) >> 4; cz++) {
                l.getChunk(cx, cz);
                if (l.setChunkForced(cx, cz, true)) {
                    held.add(new long[] {cx, cz});
                }
            }
        }
        // The tickets take effect between ticks and a chunk's entities load a little after the chunk, longer on a busy server:
        // wait until every chunk says so (or give up with a message).
        awaitEntities(h, l, held, 300, () -> {
            try {
                placementChecks(h, l, o);
            } finally {
                for (long[] c : held) {
                    l.setChunkForced((int) c[0], (int) c[1], false);
                }
            }
            h.succeed();
        });
    }

    private static void awaitEntities(GameTestHelper h, ServerLevel l, java.util.List<long[]> chunks, int tries, Runnable then) {
        boolean ready = true;
        for (long[] c : chunks) {
            ready &= l.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong((int) c[0], (int) c[1]));
        }
        if (ready) {
            then.run();
        } else if (tries <= 0) {
            for (long[] c : chunks) {
                l.setChunkForced((int) c[0], (int) c[1], false);
            }
            h.fail("the chunks' entities never loaded");
        } else {
            h.runAfterDelay(2, () -> awaitEntities(h, l, chunks, tries - 1, then));
        }
    }

    private static void placementChecks(GameTestHelper h, ServerLevel l, BlockPos o) {
        var LR = BwrBlocks.LARGE_RTG.get();
        clear(l, o.offset(-6, -1, -6), o.offset(70, 6, 8));
        for (BlockPos p : BlockPos.betweenClosed(o.offset(-6, -1, -6), o.offset(70, -1, 8))) {
            l.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
        }
        h.assertTrue(LargeRtgBlock.CELLS == 12 && LargeRtgBlock.WIDTH == 2 && LargeRtgBlock.HEIGHT == 3 && LargeRtgBlock.DEPTH == 2, "the footprint is 2 x 3 x 2");
        int dropsExpected = 0;
        for (var facing : Direction.Plane.HORIZONTAL) {
            // a chunk boundary through the footprint for one of the four
            BlockPos root = o.offset(facing.get2DDataValue() * 14, 0, 0);
            if (facing == Direction.EAST) {
                root = new BlockPos(((o.getX() + 48) >> 4) << 4, o.getY(), o.getZ()); // x = a multiple of 16 (7248): the footprint reaches x - 1, across a chunk boundary, clear of the others
            }
            h.assertTrue(place(l, root, facing), "placement refused on a clear footprint facing " + facing);
            var rootState = l.getBlockState(root);
            h.assertTrue(rootState.is(LR) && rootState.getValue(LargeRtgBlock.FACING) == facing && rootState.getValue(LargeRtgBlock.CELL) == 0, "controller wrong facing " + facing + ": " + rootState);
            var be = l.getBlockEntity(root) instanceof LargeRtgBlockEntity e ? e : null;
            h.assertTrue(be != null, "the controller has no Large RTG block entity facing " + facing);
            int[] bx = box(facing);
            int cells = 0;
            for (int dx = bx[0]; dx <= bx[1]; dx++) {
                for (int dy = 0; dy < 3; dy++) {
                    for (int dz = bx[2]; dz <= bx[3]; dz++) {
                        BlockPos p = root.offset(dx, dy, dz);
                        var s = l.getBlockState(p);
                        h.assertTrue(s.is(LR) && s.getValue(LargeRtgBlock.FACING) == facing, "cell " + p + " is not part of the assembly facing " + facing);
                        h.assertTrue((l.getBlockEntity(p) != null) == p.equals(root), "block entity outside the controller at " + p);
                        h.assertTrue(LargeRtgBlock.controller(l, p, s) == be, "cell " + p + " does not address the controller");
                        h.assertTrue(dev.bwr.mod.signal.DeviceResolver.resolve(l, p) == be, "the device lookup of " + p + " is not the controller");
                        int loot = net.minecraft.world.level.block.Block.getDrops(s, l, p, l.getBlockEntity(p)).stream().mapToInt(ItemStack::getCount).sum();
                        h.assertTrue(loot == (p.equals(root) ? 1 : 0), "cell " + p + " loot is " + loot);
                        // every cell forwards energy and items to the one controller, on every face
                        for (var side : Direction.values()) {
                            h.assertTrue(l.getCapability(Capabilities.EnergyStorage.BLOCK, p, side) != null && l.getCapability(Capabilities.ItemHandler.BLOCK, p, side) != null,
                                    "no energy or item capability on " + p + " " + side);
                        }
                        cells++;
                    }
                }
            }
            h.assertTrue(cells == 12 && LargeRtgBlock.complete(l, root, rootState), "assembly incomplete facing " + facing);
            // nothing spilled outside the footprint
            h.assertTrue(l.getBlockState(root.above(3)).isAir() && l.getBlockState(root.offset(bx[0] - 1, 0, bx[2])).isAir()
                    && l.getBlockState(root.offset(bx[1] + 1, 0, bx[2])).isAir() && l.getBlockState(root.offset(bx[0], 0, bx[3] + 1)).isAir(), "assembly spilled out of its footprint");
            // load 7 strontium ingots, then break a top part as a survival player: one item for the unit, seven for the contents, every cell gone
            be.loadFrom(7, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get(), 7));
            h.assertTrue(Math.abs(be.output().massGrams() - 1750.0) < 1e-9, "7 ingots did not make 1,750 g");
            BlockPos neighbour = root.offset(bx[0] - 2, 0, bx[2]);
            l.setBlock(neighbour, Blocks.STONE.defaultBlockState(), 3);
            breakAsPlayer(l, root.offset(bx[1], 2, bx[3]));
            for (int dx = bx[0]; dx <= bx[1]; dx++) {
                for (int dy = 0; dy < 3; dy++) {
                    for (int dz = bx[2]; dz <= bx[3]; dz++) {
                        h.assertTrue(!l.getBlockState(root.offset(dx, dy, dz)).is(LR), "orphan cell left after breaking, facing " + facing + " at " + root.offset(dx, dy, dz));
                    }
                }
            }
            h.assertTrue(l.getBlockState(neighbour).is(Blocks.STONE), "breaking removed a neighbour");
            l.removeBlock(neighbour, false);
            long here = 0;
            long hereIngots = 0;
            for (ItemEntity e : l.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(4, 4, 4))) {
                if (e.getItem().is(BwrItems.LARGE_RTG.get())) {
                    here += e.getItem().getCount();
                }
                if (e.getItem().is(BwrItems.STRONTIUM_90_INGOT.get())) {
                    hereIngots += e.getItem().getCount();
                }
            }
            h.assertTrue(here == 1 && hereIngots == 7, "breaking the assembly facing " + facing + " at " + root + " dropped " + here + " units and " + hereIngots + " ingots, not 1 and 7 (entities loaded in the root chunk: " + l.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(root)) + ", item entities within 40 blocks: " + l.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(40)).size() + ")");
            dropsExpected++;
        }
        long units = 0;
        long ingots = 0;
        for (ItemEntity e : l.getEntitiesOfClass(ItemEntity.class, new AABB(o).inflate(80, 10, 20))) {
            if (e.getItem().is(BwrItems.LARGE_RTG.get())) {
                units += e.getItem().getCount();
            }
            if (e.getItem().is(BwrItems.STRONTIUM_90_INGOT.get())) {
                ingots += e.getItem().getCount();
            }
        }
        h.assertTrue(units == dropsExpected && ingots == 7L * dropsExpected, "four broken assemblies should drop four units and 28 ingots, dropped " + units + " and " + ingots);
        // a blocked footprint refuses placement and changes nothing: a block in the top corner, in the middle layer, on the base
        BlockPos root = o.offset(0, 0, 0);
        for (BlockPos blocker : new BlockPos[] {root.offset(1, 2, 1), root.offset(1, 1, 0), root.offset(0, 0, 1)}) {
            l.setBlock(blocker, Blocks.STONE.defaultBlockState(), 3);
            h.assertTrue(!place(l, root, Direction.NORTH), "placement accepted with " + blocker + " blocked");
            h.assertTrue(l.getBlockState(root).isAir() && l.getBlockState(blocker).is(Blocks.STONE), "refused placement changed the world at " + blocker);
            for (var p : BlockPos.betweenClosed(root.offset(-1, 0, -1), root.offset(2, 2, 2))) {
                h.assertTrue(!l.getBlockState(p).is(LR), "refused placement left a cell at " + p);
            }
            l.removeBlock(blocker, false);
        }
        // breaking the controller itself also clears the whole unit
        h.assertTrue(place(l, root, Direction.EAST), "placement refused after clearing");
        l.destroyBlock(root, false);
        for (var p : BlockPos.betweenClosed(root.offset(-2, 0, -2), root.offset(2, 2, 2))) {
            h.assertTrue(!l.getBlockState(p).is(LR), "cell left after breaking the controller at " + p);
        }
        // a part cell whose controller is removed with a bare setBlock goes too (the neighbour update)
        h.assertTrue(place(l, root, Direction.NORTH), "placement refused");
        l.setBlock(root, Blocks.AIR.defaultBlockState(), 3);
        h.assertTrue(!l.getBlockState(root.offset(1, 2, 1)).is(LR), "an orphan part stayed after its controller was set to air");
        System.out.println("LARGE RTG PLACEMENT PASS: 12 cells, one controller, 4 facings (one across a chunk boundary), one drop each plus the contents, blocked footprint refused");
    }

    // ------------------------------------------------------------------------------------------------------------
    // Loading

    /** A hopper loads it by the item capability; at the cap it refuses and keeps the rest; a stack that does not fit whole is split at the cap. */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 400)
    public static void hopperLoadsUpToTheCapThenRefuses(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos root = h.absolutePos(new BlockPos(3, 2, 3));
        var rtg = assembled(l, root, Direction.NORTH);
        BlockPos hopperPos = root.above(3);
        l.setBlock(hopperPos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN), 3);
        var hopper = (HopperBlockEntity) l.getBlockEntity(hopperPos);
        hopper.setItem(0, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get(), 5));
        h.runAfterDelay(90, () -> {
            h.assertTrue(hopper.isEmpty(), "the hopper still holds " + hopper.getItem(0));
            h.assertTrue(rtg.store().itemCount() == 5 && Math.abs(rtg.output().massGrams() - 1250.0) < 1e-9, "five ingots from the hopper made " + rtg.output().massGrams() + " g");
            // fill by the capability from another face, as a pipe would, to 999,750 g
            IItemHandler side = l.getCapability(Capabilities.ItemHandler.BLOCK, root.offset(1, 0, 1), Direction.EAST);
            h.assertTrue(side != null, "no item capability");
            int left = 3995;
            while (left > 0) {
                int n = Math.min(64, left);
                ItemStack rest = side.insertItem(0, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get(), n), false);
                h.assertTrue(rest.isEmpty(), "a stack of " + n + " was refused with " + rest.getCount() + " left at " + rtg.output().massGrams() + " g");
                left -= n;
            }
            h.assertTrue(Math.abs(rtg.output().massGrams() - 1_000_000.0) < 1e-6 && rtg.store().itemCount() == 4000, "4,000 ingots should be 1,000 kg exactly: " + rtg.output().massGrams());
            // full: a capability insert is refused whole, a simulated one says so, extraction gives nothing
            ItemStack back = side.insertItem(0, new ItemStack(BwrItems.CAESIUM_137_INGOT.get(), 3), false);
            h.assertTrue(back.getCount() == 3 && !side.isItemValid(0, new ItemStack(Blocks.STONE)), "a full unit took something: " + back);
            h.assertTrue(side.extractItem(0, 64, false).isEmpty() && side.getStackInSlot(0).isEmpty(), "something came out of the unit");
            h.assertTrue(Math.abs(rtg.output().massGrams() - 1_000_000.0) < 1e-6, "a refused insert changed the mass");
            // a real hopper now: it keeps all ten
            hopper.setItem(0, new ItemStack(BwrItems.CAESIUM_137_INGOT.get(), 10));
            h.runAfterDelay(80, () -> {
                h.assertTrue(hopper.getItem(0).getCount() == 10, "a full unit took " + (10 - hopper.getItem(0).getCount()) + " from the hopper");
                h.assertTrue(Math.abs(rtg.output().massGrams() - 1_000_000.0) < 1e-6, "mass changed beyond the cap");
                // a block is refused whole too, simulated or not
                h.assertTrue(side.insertItem(0, new ItemStack(BwrItems.STRONTIUM_90_BLOCK.get()), true).getCount() == 1, "a block was accepted by a full unit");
                System.out.println("LARGE RTG LOADING PASS: hopper 5 ingots, capability to exactly 1,000 kg, full refuses ingots and blocks, hopper keeps its 10");
                l.removeBlock(hopperPos, false);
                rtg.removalDrops();
                l.removeBlock(root, false);
                h.succeed();
            });
        });
    }

    /** A block goes in whole and counts nine times an ingot; by hand (right click) a stack loads as much as fits. */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 100)
    public static void blocksAndIngotsAddUpAndByHandLoadingStopsAtTheCap(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos root = h.absolutePos(new BlockPos(3, 2, 3));
        var rtg = assembled(l, root, Direction.NORTH);
        h.assertTrue(rtg.loadFrom(444, new ItemStack(BwrItems.STRONTIUM_90_BLOCK.get(), 64)) == 64, "a stack of 64 blocks did not go in");
        for (int i = 0; i < 6; i++) {
            rtg.loadFrom(64, new ItemStack(BwrItems.STRONTIUM_90_BLOCK.get(), 64));
        }
        // 7 x 64 = 448 blocks wanted, 444 fit (999 kg)
        h.assertTrue(rtg.store().itemCount() == 444 && Math.abs(rtg.output().massGrams() - 999_000.0) < 1e-6, "blocks made " + rtg.output().massGrams() + " g in " + rtg.store().itemCount() + " items");
        // by hand: the player's stack of 10 ingots, only 4 fit (999,000 + 4 x 250 = 1,000,000)
        var player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var inHand = new ItemStack(BwrItems.CAESIUM_137_INGOT.get(), 10);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, inHand);
        var state = l.getBlockState(root);
        var result = state.useItemOn(inHand, l, player, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(root), Direction.NORTH, root, false));
        h.assertTrue(result.consumesAction() && inHand.getCount() == 6, "right-click loaded " + (10 - inHand.getCount()) + " of 10");
        h.assertTrue(Math.abs(rtg.output().massGrams() - 1_000_000.0) < 1e-6, "by-hand loading did not stop at exactly 1,000 kg: " + rtg.output().massGrams());
        // an item that is not fuel is not taken by hand
        var coal = new ItemStack(net.minecraft.world.item.Items.COAL, 3);
        h.assertTrue(!state.useItemOn(coal, l, player, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(root), Direction.NORTH, root, false)).consumesAction() && coal.getCount() == 3, "coal was taken");
        // breaking returns every item that went in (mass conserved to the item): 444 blocks and 4 ingots
        var drops = rtg.removalDrops();
        long blocks = drops.stream().filter(s -> s.is(BwrItems.STRONTIUM_90_BLOCK.get())).mapToLong(ItemStack::getCount).sum();
        long ingots = drops.stream().filter(s -> s.is(BwrItems.CAESIUM_137_INGOT.get())).mapToLong(ItemStack::getCount).sum();
        h.assertTrue(blocks == 444 && ingots == 4, "drops " + blocks + " blocks and " + ingots + " ingots");
        l.removeBlock(root, false);
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Power

    /** FE to a real adjacent consumer, and heat-only: the same heat, no FE. */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 400)
    public static void largeRtgPowersANeighbourAndHeatOnlyStopsTheFe(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos root = h.absolutePos(new BlockPos(3, 2, 3));
        var rtg = assembled(l, root, Direction.NORTH);
        BlockPos plantPos = root.offset(2, 0, 0); // touches the bottom layer's east face
        l.setBlock(plantPos, BwrBlocks.REPROCESSING_PLANT.get().defaultBlockState(), 3);
        var plant = (ReprocessingPlantBlockEntity) l.getBlockEntity(plantPos);
        long t0 = l.getGameTime();
        int loaded = rtg.loadFrom(40, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get(), 40));
        h.assertTrue(loaded == 40, "40 ingots did not load");
        var r = rtg.output();
        // 10 kg of Sr-90: 9,200 W thermal, 460 W electric
        h.assertTrue(Math.abs(r.thermalWatts() - 9200.0) < 1e-6 && Math.abs(r.electricWatts() - 460.0) < 1e-6 && Math.abs(r.fePerTick() - 460.0 / 13.4) < 1e-9, "readout " + r);
        double rate = 460.0 / 13.4;
        h.runAfterDelay(100, () -> {
            long elapsed = l.getGameTime() - t0;
            double made = rate * elapsed;
            int delivered = plant.energy().getEnergyStored();
            double waiting = rtg.bufferFe();
            h.assertTrue(delivered > 0.8 * made, "the neighbour got " + delivered + " FE of " + made);
            h.assertTrue(Math.abs(delivered + waiting - made) < 1e-6 * made + 1e-6, "accounting: delivered " + delivered + " + waiting " + waiting + " vs made " + made);
            // heat-only: thermal unchanged, electric and FE zero, and the neighbour stops gaining
            rtg.setHeatOnly(true);
            var off = rtg.output();
            h.assertTrue(Math.abs(off.thermalWatts() - r.thermalWatts()) < 1e-3 && off.electricWatts() == 0.0 && off.fePerTick() == 0.0, "heat-only readout " + off);
            int before = plant.energy().getEnergyStored();
            double bufferBefore = rtg.bufferFe();
            h.runAfterDelay(60, () -> {
                h.assertTrue(plant.energy().getEnergyStored() - before <= (int) Math.ceil(bufferBefore) + 1,
                        "heat-only still made FE: neighbour went " + before + " -> " + plant.energy().getEnergyStored() + " with " + bufferBefore + " waiting");
                h.assertTrue(rtg.bufferFe() <= bufferBefore + 1e-9, "heat-only filled the buffer: " + bufferBefore + " -> " + rtg.bufferFe());
                h.assertTrue(Math.abs(rtg.thermalWatts() - r.thermalWatts()) < 1e-3, "heat-only lost the heat");
                // and back on: FE flows again
                rtg.setHeatOnly(false);
                int mid = plant.energy().getEnergyStored();
                h.runAfterDelay(40, () -> {
                    h.assertTrue(plant.energy().getEnergyStored() > mid, "FE did not resume after heat-only was switched off");
                    System.out.println("LARGE RTG POWER: 10 kg Sr-90 " + String.format("%.4f", rate) + " FE/t, " + delivered + " FE delivered in " + elapsed + " ticks, heat-only: same 9,200 W, no FE");
                    rtg.removalDrops();
                    l.removeBlock(plantPos, false);
                    l.removeBlock(root, false);
                    h.succeed();
                });
            });
        });
    }

    /** 1,000 kg of strontium in the block: 920 kW, 46 kW, 3,432.8 FE/t; the output multiplier scales the electricity only. */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 100)
    public static void aThousandKilogramsOfStrontiumReadsTheDocumentedFigures(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos root = h.absolutePos(new BlockPos(3, 2, 3));
        var rtg = assembled(l, root, Direction.NORTH);
        for (int i = 0; i < 63; i++) {
            rtg.loadFrom(64, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get(), 64));
        }
        rtg.loadFrom(64, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get(), 64));
        var r = rtg.output();
        h.assertTrue(Math.abs(r.massGrams() - 1_000_000.0) < 1e-6, "mass " + r.massGrams());
        h.assertTrue(Math.abs(r.thermalWatts() - 920_000.0) < 1e-3 && Math.abs(r.electricWatts() - 46_000.0) < 1e-4
                && Math.abs(r.fePerTick() - 46_000.0 / 13.4) < 1e-6, "1,000 kg of strontium reads " + r);
        var provider = SignalProviders.of(rtg);
        h.assertTrue(provider != null && Math.abs(provider.read("thermal_power").orElse(-1) - 920_000.0) < 1e-3
                && Math.abs(provider.read("mass").orElse(-1) - 1000.0) < 1e-9
                && Math.abs(provider.read("electric_power").orElse(-1) - 46_000.0 / 13.4) < 1e-6, "signals do not read the figures");
        System.out.println("LARGE RTG 1,000 kg Sr-90: " + (int) r.thermalWatts() + " W thermal, " + (int) r.electricWatts() + " W electric, "
                + String.format("%.1f", r.fePerTick()) + " FE/t, dose at 1 m " + dev.bwr.core.radiation.DoseModel.formatRate(r.doseAtOneMetreMsvPerH()));
        rtg.removalDrops();
        l.removeBlock(root, false);
        h.succeed();
    }

    /** Decay in the block: one half-life later (the epoch moved back, as a save would carry it) the power is half. */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 100)
    public static void decayFollowsTheHalfLifeInTheBlock(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos root = h.absolutePos(new BlockPos(3, 2, 3));
        var rtg = assembled(l, root, Direction.NORTH);
        rtg.loadFrom(100, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get(), 64));
        rtg.loadFrom(36, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get(), 36));
        rtg.loadFrom(4, new ItemStack(BwrItems.AMERICIUM_241_INGOT.get(), 4));
        double fresh = rtg.output().thermalWatts();
        h.assertTrue(Math.abs(fresh - (100 * 230.0 + 4 * 250.0 * 0.1147)) < 1e-6, "fresh thermal " + fresh);
        // the strontium kind one Sr-90 half-life old: edit its epoch in the saved form and load it back
        CompoundTag tag = save(l, rtg);
        ListTag kinds = tag.getList("Kinds", Tag.TAG_COMPOUND);
        double nowS = l.getGameTime() / 20.0;
        for (int i = 0; i < kinds.size(); i++) {
            var k = kinds.getCompound(i);
            if (k.getString("Id").equals("bwr:strontium_90_ingot")) {
                k.putDouble("Epoch", nowS - 28.9 * RadioisotopePower.SECONDS_PER_YEAR);
            }
        }
        var fresh2 = new LargeRtgBlockEntity(rtg.getBlockPos(), rtg.getBlockState());
        fresh2.setLevel(l);
        fresh2.loadWithComponents(tag, l.registryAccess());
        double aged = fresh2.output().thermalWatts();
        double expected = 100 * 115.0 + 4 * 250.0 * 0.1147;
        h.assertTrue(Math.abs(aged - expected) < 1e-3, "after one Sr-90 half-life " + aged + ", wanted " + expected);
        rtg.removalDrops();
        l.removeBlock(root, false);
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Dose

    /** The dose at 1 m is the DoseModel's number through the housing, the block is the point source of exactly that, and it clears with the contents. */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 100)
    public static void theDoseReadoutIsHonestAndTheBlockIsASource(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos root = new BlockPos(7300, 200, 7300);
        var rtg = assembled(l, root, Direction.NORTH);
        Vec3 centre = Vec3.atCenterOf(root).add(0.5, 1.0, 0.5); // the middle of the 2 x 3 x 2
        Vec3 metre = centre.add(1.0, 0.0, 0.0);
        h.assertTrue(RadiationSources.doseRateMsvPerHour(l, metre) == 0.0, "an empty unit reads a dose");
        // 1,000 kg of Co-60 is 10,000 ingots of 100 g: 156 stacks of 64 and a remainder
        int left = 10_000;
        while (left > 0) {
            int n = Math.min(64, left);
            left -= rtg.loadFrom(n, new ItemStack(BwrItems.COBALT_60_INGOT.get(), n));
        }
        var r = rtg.output();
        h.assertTrue(Math.abs(r.massGrams() - 1_000_000.0) < 1e-6, "cobalt mass " + r.massGrams());
        double photonsW = 1_000_000.0 * 16.8 * 0.9;
        double hvlTransmission = Math.pow(0.5, LargeRtgStore.LEAD_EQUIVALENT_CM / 1.2);
        double expected = dev.bwr.core.radiation.DoseModel.pointSourceMsvPerH(photonsW * hvlTransmission, 1.0, 1.0);
        h.assertTrue(Math.abs(r.doseAtOneMetreMsvPerH() / expected - 1.0) < 1e-9, "dose " + r.doseAtOneMetreMsvPerH() + " vs " + expected);
        h.assertTrue(r.doseAtOneMetreMsvPerH() > 0.1 && r.doseAtOneMetreMsvPerH() < 10.0, "1,000 kg of cobalt-60 should read about a mSv/h at 1 m, not " + r.doseAtOneMetreMsvPerH());
        h.assertTrue(r.bareDoseAtOneMetreMsvPerH() > 1.0e9, "the bare core reads only " + r.bareDoseAtOneMetreMsvPerH());
        double world = RadiationSources.doseRateMsvPerHour(l, metre);
        h.assertTrue(Math.abs(world / r.doseAtOneMetreMsvPerH() - 1.0) < 1e-6, "the point source reads " + world + " at 1 m, the readout " + r.doseAtOneMetreMsvPerH());
        // caesium: the housing stops it to nothing, and the number is still there (not hidden)
        rtg.removalDrops();
        h.assertTrue(RadiationSources.doseRateMsvPerHour(l, metre) == 0.0, "the source stayed after the contents were removed");
        rtg.loadFrom(64, new ItemStack(BwrItems.CAESIUM_137_INGOT.get(), 64));
        var cs = rtg.output();
        h.assertTrue(cs.bareDoseAtOneMetreMsvPerH() > 1000.0 && cs.doseAtOneMetreMsvPerH() < 1.0e-6 && cs.doseAtOneMetreMsvPerH() >= 0.0, "caesium bare " + cs.bareDoseAtOneMetreMsvPerH() + " housed " + cs.doseAtOneMetreMsvPerH());
        rtg.loadFrom(1, new ItemStack(BwrItems.COBALT_60_INGOT.get(), 1));
        h.assertTrue(RadiationSources.doseRateMsvPerHour(l, metre) > 0.0, "no source with a cobalt ingot");
        l.removeBlock(root, false);
        h.assertTrue(RadiationSources.doseRateMsvPerHour(l, metre) == 0.0, "the source stayed after the block went");
        System.out.println("LARGE RTG DOSE at 1 m: 1,000 kg Co-60 housed " + dev.bwr.core.radiation.DoseModel.formatRate(r.doseAtOneMetreMsvPerH())
                + " (bare " + dev.bwr.core.radiation.DoseModel.formatRate(r.bareDoseAtOneMetreMsvPerH()) + "); 16 kg Cs-137 housed " + dev.bwr.core.radiation.DoseModel.formatRate(cs.doseAtOneMetreMsvPerH())
                + " (bare " + dev.bwr.core.radiation.DoseModel.formatRate(cs.bareDoseAtOneMetreMsvPerH()) + ")");
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Saves

    /** Contents, epochs, the switch and the buffer save and load; an empty unit writes nothing; a hand-edited save cannot mint mass. */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 100)
    public static void largeRtgSavesAndReloads(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos root = h.absolutePos(new BlockPos(3, 2, 3));
        var rtg = assembled(l, root, Direction.NORTH);
        var empty = save(l, rtg);
        h.assertTrue(!empty.contains("Kinds") && !empty.contains("HeatOnly") && !empty.contains("Buffer"), "an empty unit wrote " + empty);
        rtg.loadFrom(30, new ItemStack(BwrItems.CAESIUM_137_BLOCK.get(), 30));
        rtg.loadFrom(7, new ItemStack(BwrItems.AMERICIUM_241_CAPSULE.get(), 7));
        rtg.loadFrom(10, new ItemStack(BwrItems.COBALT_SAMPLE.get(), 10));
        h.assertTrue(!save(l, rtg).contains("HeatOnly"), "the heat-only key is written when it is off");
        rtg.setHeatOnly(true);
        var tag = save(l, rtg);
        h.assertTrue(tag.contains("Kinds") && tag.getBoolean("HeatOnly"), "a loaded heat-only unit did not write its state: " + tag);
        var reborn = new LargeRtgBlockEntity(rtg.getBlockPos(), rtg.getBlockState());
        reborn.setLevel(l);
        reborn.loadWithComponents(tag, l.registryAccess());
        h.assertTrue(reborn.heatOnly() && reborn.store().itemCount() == 47 && Math.abs(reborn.output().massGrams() - (30 * 2250.0 + 7 * 250.0 + 10 * 100.0)) < 1e-6,
                "reloaded " + reborn.store().itemCount() + " items, " + reborn.output().massGrams() + " g, heat-only " + reborn.heatOnly());
        h.assertTrue(Math.abs(reborn.output().thermalWatts() - rtg.output().thermalWatts()) < 1e-6, "thermal power changed over a reload");
        // hand-edited: more than the cap, an unknown item, a negative count
        var odd = new CompoundTag();
        var list = new ListTag();
        var big = new CompoundTag();
        big.putString("Id", "bwr:strontium_90_block");
        big.putLong("Count", 5_000_000L);
        list.add(big);
        var ghost = new CompoundTag();
        ghost.putString("Id", "bwr:no_such_item");
        ghost.putLong("Count", 5L);
        list.add(ghost);
        odd.put("Kinds", list);
        odd.putDouble("Buffer", 1.0e12);
        var oddRtg = new LargeRtgBlockEntity(rtg.getBlockPos(), rtg.getBlockState());
        oddRtg.setLevel(l);
        oddRtg.loadWithComponents(odd, l.registryAccess());
        h.assertTrue(oddRtg.output().massGrams() <= LargeRtgStore.CAPACITY_GRAMS + 1e-6, "a hand-edited save holds " + oddRtg.output().massGrams() + " g");
        h.assertTrue(oddRtg.store().itemCount() == 444, "a hand-edited count was not clamped to the cap: " + oddRtg.store().itemCount());
        rtg.removalDrops();
        l.removeBlock(root, false);
        h.succeed();
    }

    /** A small RTG saved by the build before the Large RTG (no HeatOnly key) loads and reads exactly as it did; the new key appears only when set. */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 100)
    public static void anOldSmallRtgSaveLoads(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = h.absolutePos(new BlockPos(1, 2, 1));
        l.setBlock(p, BwrBlocks.RTG.get().defaultBlockState(), 3);
        var old = (RtgBlockEntity) l.getBlockEntity(p);
        // the exact form the small RTG has always written: Fuel (an ItemStackHandler), LoadedAt (long[6]), Buffer
        var handler = new net.neoforged.neoforge.items.ItemStackHandler(6);
        handler.setStackInSlot(0, new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get()));
        handler.setStackInSlot(3, new ItemStack(BwrItems.CAESIUM_137_CAPSULE.get()));
        var tag = new CompoundTag();
        tag.put("Fuel", handler.serializeNBT(l.registryAccess()));
        long now = l.getGameTime();
        tag.putLongArray("LoadedAt", new long[] {now, -1, -1, now, -1, -1});
        tag.putDouble("Buffer", 12.5);
        old.loadWithComponents(tag, l.registryAccess());
        var r = old.readout();
        h.assertTrue(r.capsules() == 2 && Math.abs(r.thermalWatts() - (230.0 + 105.0)) < 1e-6 && Math.abs(r.electricWatts() - (335.0 * 0.05)) < 1e-6, "old save reads " + r);
        h.assertTrue(!old.heatOnly() && old.bufferFe() >= 12.5 && old.bufferFe() <= RtgBlockEntity.BUFFER_FE, "old save: heat-only " + old.heatOnly() + ", buffer " + old.bufferFe());
        h.assertTrue(!save(l, old).contains("HeatOnly"), "the new key is written by default");
        old.setHeatOnly(true);
        var heat = old.readout();
        h.assertTrue(heat.electricWatts() == 0.0 && heat.fePerTick() == 0.0 && heat.thermalWatts() == r.thermalWatts(), "small RTG heat-only readout " + heat);
        h.assertTrue(save(l, old).getBoolean("HeatOnly"), "heat-only was not saved");
        var provider = SignalProviders.of(old);
        h.assertTrue(provider.read("heat_only").orElse(-1) == 1.0 && provider.commands().isEmpty(), "the small RTG's heat_only signal or its (empty) command list is wrong");
        old.setHeatOnly(false);
        h.assertTrue(!save(l, old).contains("HeatOnly"), "the key stayed after the switch went back");
        old.removalDrops();
        l.removeBlock(p, false);
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Signals, recipes, the data

    /** Networking signals read the Large RTG and the heat-only command switches it. */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 100)
    public static void signalsReadAndSwitchTheLargeRtg(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos root = h.absolutePos(new BlockPos(3, 2, 3));
        var rtg = assembled(l, root, Direction.NORTH);
        var provider = SignalProviders.of(rtg);
        h.assertTrue(provider != null, "no signal provider for the Large RTG");
        h.assertTrue(provider.read("electric_power").orElse(-1) == 0.0 && provider.read("fuelled").orElse(-1) == 0.0 && provider.read("mass").orElse(-1) == 0.0, "an empty unit signals power");
        rtg.loadFrom(8, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get(), 8));
        h.assertTrue(Math.abs(provider.read("thermal_power").orElse(-1) - 1840.0) < 1e-6 && provider.read("fuelled").orElse(-1) == 1.0 && Math.abs(provider.read("mass").orElse(-1) - 2.0) < 1e-9, "signals wrong");
        h.assertTrue(provider.read("heat_only").orElse(-1) == 0.0, "heat_only should start off");
        h.assertTrue(provider.commands().stream().anyMatch(c -> c.key().equals("set_heat_only")), "no set_heat_only command: " + provider.commands());
        h.assertTrue(provider.command("set_heat_only", 1.0), "the command was refused");
        h.assertTrue(rtg.heatOnly() && provider.read("electric_power").orElse(-1) == 0.0 && provider.read("heat_only").orElse(-1) == 1.0 && Math.abs(provider.read("thermal_power").orElse(-1) - 1840.0) < 1e-6,
                "heat-only through the signal layer: " + rtg.output());
        rtg.removalDrops();
        l.removeBlock(root, false);
        h.succeed();
    }

    /** Ingots and blocks: nine to one and back, the capsules and the cobalt sample convert 1:1, mass conserved; the data lines agree. */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 100)
    public static void ingotAndBlockRecipesConserveMassAndTheDataAgrees(GameTestHelper h) {
        var manager = h.getLevel().getServer().getRecipeManager();
        String[][] pairs = {{"strontium_90", "strontium_90_capsule"}, {"caesium_137", "caesium_137_capsule"}, {"americium_241", "americium_241_capsule"}, {"cobalt_60", "cobalt_sample"}};
        for (String[] pair : pairs) {
            String iso = pair[0];
            var ingot = BuiltInRegistriesItem(iso + "_ingot");
            var block = BuiltInRegistriesItem(iso + "_block");
            var old = BuiltInRegistriesItem(pair[1]);
            // 3 x 3 ingots make a block; a block makes nine ingots; the old form makes one ingot
            var pack = (CraftingRecipe) manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", iso + "_block")).orElseThrow().value();
            var grid = NonNullList.withSize(9, new ItemStack(ingot));
            h.assertTrue(pack.matches(CraftingInput.of(3, 3, grid), h.getLevel()) && pack.assemble(CraftingInput.of(3, 3, grid), h.getLevel().registryAccess()).is(block), iso + ": nine ingots did not make a block");
            grid.set(4, ItemStack.EMPTY);
            h.assertTrue(!pack.matches(CraftingInput.of(3, 3, grid), h.getLevel()), iso + ": eight ingots made a block");
            var unpack = (CraftingRecipe) manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", iso + "_ingot_from_block")).orElseThrow().value();
            var one = NonNullList.withSize(1, new ItemStack(block));
            var made = unpack.assemble(CraftingInput.of(1, 1, one), h.getLevel().registryAccess());
            h.assertTrue(unpack.matches(CraftingInput.of(1, 1, one), h.getLevel()) && made.is(ingot) && made.getCount() == 9, iso + ": a block did not make nine ingots");
            var convert = (CraftingRecipe) manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", iso + (iso.equals("cobalt_60") ? "_ingot_from_sample" : "_ingot_from_capsule"))).orElseThrow().value();
            var oldGrid = NonNullList.withSize(1, new ItemStack(old));
            var conv = convert.assemble(CraftingInput.of(1, 1, oldGrid), h.getLevel().registryAccess());
            h.assertTrue(convert.matches(CraftingInput.of(1, 1, oldGrid), h.getLevel()) && conv.is(ingot) && conv.getCount() == 1, iso + ": the old form did not make one ingot");
            // the data: ingot and old form the same grams and power, the block nine times
            RtgFuel fi = RtgFuels.of(new ItemStack(ingot));
            RtgFuel fb = RtgFuels.of(new ItemStack(block));
            RtgFuel fo = RtgFuels.of(new ItemStack(old));
            h.assertTrue(fi != null && fb != null && fo != null, iso + ": a form has no RTG fuel line");
            h.assertTrue(fi.gramsPerItem() == fo.gramsPerItem() && fb.gramsPerItem() == 9 * fi.gramsPerItem(), iso + ": grams " + fo.gramsPerItem() + ", " + fi.gramsPerItem() + ", " + fb.gramsPerItem());
            h.assertTrue(fi.specificPowerWPerG() == fb.specificPowerWPerG() && fi.halfLifeYears() == fb.halfLifeYears() && fi.gammaWPerG() == fb.gammaWPerG()
                    && fi.capsuleTransmission() == fb.capsuleTransmission() && fi.leadHvlCm() == fb.leadHvlCm()
                    && fi.specificPowerWPerG() == fo.specificPowerWPerG() && fi.leadHvlCm() == fo.leadHvlCm(), iso + ": the lines differ beyond the mass");
        }
        // the separator's capsule is 250 g (its grain accounting is untouched), the cobalt sample 100 g
        h.assertTrue(RtgFuels.of(new ItemStack(BwrItems.STRONTIUM_90_CAPSULE.get())).gramsPerItem() == dev.bwr.mod.isotope.Isotopes.CAPSULE_GRAMS, "the capsule's grams moved");
        RtgFuel co = RtgFuels.of(new ItemStack(BwrItems.COBALT_SAMPLE.get()));
        h.assertTrue(co.gramsPerItem() == 100.0 && co.halfLifeYears() == 5.2714 && co.specificPowerWPerG() == 18.9, "cobalt " + co);
        // the cobalt sample's existing use still works: a comparator from a sample, redstone and quartz
        var comparator = manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", "cobalt_sample_detector"));
        h.assertTrue(comparator.isPresent(), "the cobalt sample's own recipe is gone");
        var cr = (CraftingRecipe) comparator.get().value();
        h.assertTrue(cr.getIngredients().stream().anyMatch(i -> i.test(new ItemStack(BwrItems.COBALT_SAMPLE.get()))), "the cobalt sample is no longer an ingredient of its recipe");
        // the large RTG recipe
        var lr = (CraftingRecipe) manager.byKey(ResourceLocation.fromNamespaceAndPath("bwr", "large_rtg")).orElseThrow().value();
        var lg = NonNullList.withSize(9, ItemStack.EMPTY);
        for (int i : new int[] {0, 1, 2, 3, 5}) {
            lg.set(i, new ItemStack(BwrItems.LEAD_INGOT.get()));
        }
        lg.set(4, new ItemStack(BwrItems.RTG.get()));
        lg.set(6, new ItemStack(net.minecraft.world.item.Items.IRON_BLOCK));
        lg.set(8, new ItemStack(net.minecraft.world.item.Items.IRON_BLOCK));
        lg.set(7, new ItemStack(net.minecraft.world.item.Items.HOPPER));
        h.assertTrue(lr.matches(CraftingInput.of(3, 3, lg), h.getLevel()) && lr.assemble(CraftingInput.of(3, 3, lg), h.getLevel().registryAccess()).is(BwrItems.LARGE_RTG.get()), "the Large RTG recipe does not craft");
        h.succeed();
    }

    private static net.minecraft.world.item.Item BuiltInRegistriesItem(String path) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("bwr", path));
    }

    /** The small RTG accepts an ingot or a block in a well (a block is nine ingots of heat). */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 100)
    public static void theSmallRtgTakesIngotsAndBlocks(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = h.absolutePos(new BlockPos(1, 2, 1));
        l.setBlock(p, BwrBlocks.RTG.get().defaultBlockState(), 3);
        var rtg = (RtgBlockEntity) l.getBlockEntity(p);
        h.assertTrue(rtg.fuel().isItemValid(0, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get())) && rtg.fuel().isItemValid(1, new ItemStack(BwrItems.COBALT_60_BLOCK.get())), "a well refused an ingot or a block");
        rtg.fuel().setStackInSlot(0, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get()));
        rtg.fuel().setStackInSlot(1, new ItemStack(BwrItems.STRONTIUM_90_BLOCK.get()));
        h.assertTrue(Math.abs(rtg.readout().thermalWatts() - (230.0 + 9 * 230.0)) < 1e-6, "an ingot and a block read " + rtg.readout().thermalWatts());
        rtg.removalDrops();
        l.removeBlock(p, false);
        h.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Heat, and Cold Sweat

    /** The heat an RTG gives a room is proportional to its thermal watts, shared across the Large RTG's cells, zero when empty; heat-only changes nothing. */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 100)
    public static void rtgHeatIsProportionalToThermalWatts(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos p = h.absolutePos(new BlockPos(1, 2, 1));
        l.setBlock(p, BwrBlocks.RTG.get().defaultBlockState(), 3);
        var small = (RtgBlockEntity) l.getBlockEntity(p);
        var state = l.getBlockState(p);
        h.assertTrue(RtgHeat.cellWatts(l, p, state) == 0.0 && RtgHeat.mcUnits(RtgHeat.cellWatts(l, p, state)) == 0.0, "an empty RTG gives heat");
        small.fuel().setStackInSlot(0, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get()));
        double one = RtgHeat.mcUnits(RtgHeat.cellWatts(l, p, state));
        small.fuel().setStackInSlot(1, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get()));
        double two = RtgHeat.mcUnits(RtgHeat.cellWatts(l, p, state));
        h.assertTrue(one > 0.0 && Math.abs(two / one - 2.0) < 1e-9, "two ingots do not give twice the heat: " + one + ", " + two);
        h.assertTrue(Math.abs(one - 230.0 / 1000.0 * RtgHeat.MC_UNITS_PER_KILOWATT) < 1e-12, "one ingot gives " + one + " MC units");
        small.setHeatOnly(true);
        h.assertTrue(RtgHeat.mcUnits(RtgHeat.cellWatts(l, p, state)) == two, "heat-only changed the heat");
        small.removalDrops();
        l.removeBlock(p, false);
        // the Large RTG: the twelve cells share the thermal watts, so the whole gives what the same watts would
        BlockPos root = h.absolutePos(new BlockPos(3, 2, 3));
        var large = assembled(l, root, Direction.NORTH);
        h.assertTrue(RtgHeat.cellWatts(l, root, l.getBlockState(root)) == 0.0, "an empty Large RTG gives heat");
        large.loadFrom(40, new ItemStack(BwrItems.STRONTIUM_90_INGOT.get(), 40));
        double total = 0;
        for (int i = 0; i < LargeRtgBlock.CELLS; i++) {
            BlockPos cell = LargeRtgBlock.cellPos(root, Direction.NORTH, i);
            total += RtgHeat.cellWatts(l, cell, l.getBlockState(cell));
        }
        h.assertTrue(Math.abs(total - 9200.0) < 1e-6, "the cells' shares add to " + total + " W, not 9,200");
        large.setHeatOnly(true);
        h.assertTrue(Math.abs(RtgHeat.cellWatts(l, root, l.getBlockState(root)) * 12 - 9200.0) < 1e-6, "heat-only changed the Large RTG's heat");
        large.removalDrops();
        l.removeBlock(root, false);
        h.succeed();
    }

    /**
     * The mod runs with Cold Sweat absent (every ordinary run, including the gates): nothing of Cold Sweat is loaded. With
     * {@code -PbwrColdSweat} Cold Sweat is on the dev runtime and {@code RtgColdSweatChecks} exercises its real registry.
     */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 200)
    public static void theModRunsWithColdSweatAbsentOrRegistersItsHeatSources(GameTestHelper h) throws Exception {
        boolean present = ModList.get().isLoaded("cold_sweat");
        if (!present) {
            boolean found;
            try {
                Class.forName("com.momosoftworks.coldsweat.api.registry.BlockTempRegistry", false, LargeRtgTests.class.getClassLoader());
                found = true;
            } catch (ClassNotFoundException e) {
                found = false;
            }
            h.assertTrue(!found, "Cold Sweat classes are visible but the mod is not loaded");
            // and a Large RTG and an RTG place, tick and report with it absent: the other tests in this class do that; this one says so
            System.out.println("COLD SWEAT: absent; the RTG, the Large RTG and their heat helpers ran without it");
            h.succeed();
            return;
        }
        RtgColdSweatChecks.run(h);
        h.succeed();
    }

    /** The computer face (CC:Tweaked) of the Large RTG and the small RTG's switch; says it was skipped without CC:Tweaked. */
    @GameTest(batch = "largertg", template = "empty", templateNamespace = NS, timeoutTicks = 200)
    public static void computerFaces(GameTestHelper h) throws Exception {
        if (!ModList.get().isLoaded("computercraft")) {
            System.out.println("LARGE RTG CC: skipped, CC:Tweaked is not loaded");
            h.succeed();
            return;
        }
        LargeRtgCcChecks.run(h);
        System.out.println("LARGE RTG CC: bwr_large_rtg and bwr_rtg read through getStatus and switch through setHeatOnly");
        h.succeed();
    }
}
