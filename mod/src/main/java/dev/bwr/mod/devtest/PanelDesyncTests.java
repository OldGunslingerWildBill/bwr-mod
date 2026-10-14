package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelConsistency;
import dev.bwr.mod.panel.PanelGroup;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.PanelType;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Claim/ownership desync repair ({@link PanelBlockEntity#verifyConsistency()}): a component whose covered block
 * was removed while the owner's chunk was unloaded, and a claim whose owner vanished while the covered block's
 * chunk was unloaded.
 *
 * <p>The simulated tests make the owner stale by saving its panel state, breaking the covered block (which, with
 * the owner loaded, releases everything normally), discarding the resulting drop and restoring the saved state:
 * exactly what the owner holds after a break it never heard about. The two real-unload tests do it with chunks
 * held at ticket level 33 so one half genuinely unloads.
 *
 * <p>Fixtures: origin 52_900 / 52_900 plus {@code slot * 64} blocks in X (every slot is a whole number of chunks,
 * and columns 4 and 5 sit either side of a chunk border). Chunks are released in every outcome and the fixture is
 * cleared first, so a persistent world can run this twice.
 *
 * <p>Isolation: GameTests of one batch run in the same level at the same time, and a force-loaded chunk becomes
 * visible to entity lookups some ticks after it is readable, by a delay that depends on the machine's load. So
 * every count here is of the fixture's own things (deferrals of the fixture's block entities, nameplate items in
 * the fixture box), and nothing counts items until {@link EntityLookupProbe} has seen a probe item found.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelDesyncTests {
    private PanelDesyncTests() {
    }

    private static final Direction FACING = Direction.NORTH;
    private static final TicketType<ChunkPos> HOLD = TicketType.create("bwr_panel_desync_hold",
            Comparator.comparingLong(ChunkPos::toLong));

    /** A cleared, force-loaded stage with a survival fake player in front of the wall. */
    private static final class Stage {
        final ServerLevel level;
        final BlockPos origin;
        final FakePlayer player;
        private final List<Runnable> steps = new ArrayList<>();
        private final EntityLookupProbe probe;
        private int polls;

        private Stage(GameTestHelper helper, int slot) {
            level = helper.getLevel();
            origin = new BlockPos(52_900 + slot * 64, 110, 52_900);
            probe = new EntityLookupProbe(level, origin.getY() + 3.0);
            hold(true);
            player = FakePlayerFactory.get(level, new GameProfile(
                    UUID.nameUUIDFromBytes(("panel-desync-" + slot).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "panel-desync-" + slot));
        }

        /**
         * Runs {@code body} on a clean fixture once both chunks answer entity lookups (see {@link #entitiesLive}); then
         * each added step eight ticks apart.
         */
        static void run(GameTestHelper helper, int slot, java.util.function.Consumer<Stage> body) {
            Stage s = new Stage(helper, slot);
            s.whenLive(helper, () -> {
                try {
                    s.prepare();
                    body.accept(s);
                } catch (RuntimeException | Error e) {
                    s.release();
                    throw e;
                }
                s.next(helper);
            });
        }

        /** Waits, a tick at a time and without a fixed count, until both fixture chunks answer entity lookups; then runs {@code then}. */
        private void whenLive(GameTestHelper helper, Runnable then) {
            helper.runAfterDelay(1, () -> {
                if (entitiesLive(at(4, 0)) && entitiesLive(at(5, 0))) {
                    then.run();
                    return;
                }
                if (++polls > 200) {
                    release();
                    helper.assertTrue(false, "the fixture chunks never answered entity lookups");
                }
                whenLive(helper, then);
            });
        }

        /** Whether an entity spawned at {@code pos} can be found by a lookup yet (see {@link EntityLookupProbe}). */
        boolean entitiesLive(BlockPos pos) {
            return probe.live(pos);
        }

        /** Forget what was seen of the chunks' entity lookups (after they were unloaded and loaded again). */
        void forgetLive() {
            probe.forget();
        }

        /** Whether a position belongs to this fixture's wall (columns -1 to 8, two rows). */
        boolean owns(BlockPos pos) {
            return pos.getZ() == origin.getZ() && pos.getX() >= origin.getX() - 8 && pos.getX() <= origin.getX() + 1
                    && pos.getY() <= origin.getY() && pos.getY() >= origin.getY() - 1;
        }

        /** Deferred verifications of this fixture's block entities: the count that no other test can move. */
        int waiting() {
            return PanelConsistency.waitingCount(level, this::owns);
        }

        void then(Runnable step) {
            steps.add(step);
        }

        private void next(GameTestHelper helper) {
            if (steps.isEmpty()) {
                release();
                helper.succeed();
                return;
            }
            helper.runAfterDelay(8, () -> {
                try {
                    steps.remove(0).run();
                } catch (RuntimeException | Error e) {
                    release();
                    throw e;
                }
                next(helper);
            });
        }

        void prepare() {
            clear();
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.setShiftKeyDown(false);
            player.setPos(origin.getX() - 1.5, origin.getY() - 1.0, origin.getZ() - 2.5);
        }

        ChunkPos ownerChunk() {
            return new ChunkPos(at(4, 0));
        }

        ChunkPos coveredChunk() {
            return new ChunkPos(at(5, 0));
        }

        void hold(boolean force) {
            level.getChunkSource().removeRegionTicket(HOLD, ownerChunk(), 0, ownerChunk());
            level.getChunkSource().removeRegionTicket(HOLD, coveredChunk(), 0, coveredChunk());
            for (ChunkPos chunk : new ChunkPos[] {ownerChunk(), coveredChunk()}) {
                level.setChunkForced(chunk.x, chunk.z, force);
                if (force) {
                    level.getChunk(chunk.x, chunk.z);
                }
            }
        }

        private void clear() {
            for (int c = -1; c <= 8; c++) {
                for (int r = 0; r <= 1; r++) {
                    BlockPos pos = at(c, r);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area())) {
                item.discard();
            }
        }

        private AABB area() {
            return new AABB(origin).inflate(14, 6, 6);
        }

        BlockPos at(int column, int row) {
            return origin.relative(FACING.getCounterClockWise(), column).below(row);
        }

        void panel(BlockPos pos, PanelType type) {
            var block = type == PanelType.CONSOLE ? BwrBlocks.CONTROL_PANEL_CONSOLE.get()
                    : BwrBlocks.CONTROL_PANEL_VERTICAL.get();
            level.setBlock(pos, block.defaultBlockState().setValue(PanelBlock.FACING, FACING), 3);
        }

        void wall() {
            for (int c = 0; c < 6; c++) {
                panel(at(c, 0), PanelType.VERTICAL);
            }
        }

        PanelBlockEntity be(BlockPos pos) {
            return level.getBlockEntity(pos) instanceof PanelBlockEntity be ? be : null;
        }

        /** An 8 x 4 nameplate anchored in column 4 at cell 10, covering columns 4 and 5 (a chunk border). */
        PanelGroup.Result placeStraddler() {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.NAMEPLATE.get()));
            ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
            stack.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), 1);
            return PanelOps.place(player, InteractionHand.MAIN_HAND, at(4, 0), 10, 5);
        }

        List<ItemEntity> drops() {
            return level.getEntitiesOfClass(ItemEntity.class, area(), e -> e.getItem().is(BwrItems.NAMEPLATE.get()));
        }

        void discardDrops() {
            for (ItemEntity item : drops()) {
                item.discard();
            }
        }

        void release() {
            forgetLive();
            hold(true);
            clear();
            hold(false);
        }
    }

    // ---- simulated: owner stale after its covered block was removed unseen

    /** Owner holds a component over a covered cell that is air, stone, a different panel, or lacks the claim. */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void staleOwnerComponentIsRemovedOnVerify(GameTestHelper helper) {
        Stage.run(helper, 0, s -> {
            s.wall();
            helper.assertTrue(s.placeStraddler() == PanelGroup.Result.OK, "straddling plate");
            PanelBlockEntity owner = s.be(s.at(4, 0));
            CompoundTag saved = owner.savePanel(false);
            BlockPos covered = s.at(5, 0);

            // 1. Covered block became air; owner never heard.
            s.level.destroyBlock(covered, false);
            helper.assertTrue(owner.component(1) == null, "precondition: a heard break removes the component");
            s.then(() -> {
                helper.assertTrue(s.drops().size() == 1, "precondition: heard break dropped once");
                s.discardDrops();
                owner.loadPanel(saved);
                helper.assertTrue(owner.component(1) != null, "owner is stale");
                helper.assertTrue(owner.verifyConsistency(), "air: repaired");
                helper.assertTrue(owner.component(1) == null, "air: component removed");
                helper.assertTrue(!owner.verifyConsistency(), "air: second verify changes nothing");
            });
            s.then(() -> {
                helper.assertTrue(s.drops().size() == 1, "air: exactly one item dropped, got " + s.drops().size());
                s.discardDrops();
                // 2. Covered is stone.
                s.level.setBlock(covered, Blocks.STONE.defaultBlockState(), 3);
                owner.loadPanel(saved);
                helper.assertTrue(owner.verifyConsistency() && owner.component(1) == null, "stone: repaired");
            });
            s.then(() -> {
                helper.assertTrue(s.drops().size() == 1, "stone: one item");
                s.discardDrops();
                // 3. Covered is a different panel style.
                s.panel(covered, PanelType.CONSOLE);
                owner.loadPanel(saved);
                helper.assertTrue(owner.verifyConsistency() && owner.component(1) == null, "console: repaired");
            });
            s.then(() -> {
                helper.assertTrue(s.drops().size() == 1, "console: one item");
                s.discardDrops();
                // 4. Covered is the right panel but holds no claim.
                s.panel(covered, PanelType.VERTICAL);
                owner.loadPanel(saved);
                helper.assertTrue(owner.verifyConsistency() && owner.component(1) == null, "missing claim: repaired");
                helper.assertTrue(s.be(covered).claimAt(0, 5) == null, "no claim appears");
            });
            s.then(() -> helper.assertTrue(s.drops().size() == 1, "missing claim: one item, item has footprint variant"));
            s.then(() -> {
                ItemStack stack = s.drops().get(0).getItem();
                helper.assertTrue(stack.get(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get()) != null,
                        "dropped item carries the footprint");
                s.discardDrops();
                // 5. A consistent straddler is left alone, however often it is verified.
                helper.assertTrue(s.placeStraddler() == PanelGroup.Result.OK, "place again");
                helper.assertTrue(!owner.verifyConsistency() && !owner.verifyConsistency(), "consistent: unchanged");
                helper.assertTrue(owner.owned().size() == 1, "component kept");
                helper.assertTrue(s.be(covered).claimAt(0, 5) != null, "claim kept");
            });
            s.then(() -> helper.assertTrue(s.drops().isEmpty(), "consistent: nothing dropped"));
        });
    }

    /** The load hook: a stale owner is queued by onLoad and repaired by the tick handler without a direct call. */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void ownerLoadQueuesVerification(GameTestHelper helper) {
        Stage.run(helper, 1, s -> {
            s.wall();
            helper.assertTrue(s.placeStraddler() == PanelGroup.Result.OK, "straddling plate");
            PanelBlockEntity owner = s.be(s.at(4, 0));
            CompoundTag saved = owner.savePanel(false);
            s.level.destroyBlock(s.at(5, 0), false);
            s.then(() -> {
                s.discardDrops();
                owner.loadPanel(saved);
                owner.onLoad();
            });
            s.then(() -> {
                helper.assertTrue(owner.component(1) == null, "the queued verification ran on a tick");
                helper.assertTrue(s.drops().size() == 1, "one item, got " + s.drops().size());
            });
        });
    }

    // ---- simulated: orphan claims

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void orphanClaimIsDroppedOnVerify(GameTestHelper helper) {
        Stage.run(helper, 2, s -> {
            s.wall();
            helper.assertTrue(s.placeStraddler() == PanelGroup.Result.OK, "straddling plate");
            BlockPos ownerPos = s.at(4, 0);
            BlockPos coveredPos = s.at(5, 0);
            PanelBlockEntity covered = s.be(coveredPos);
            CompoundTag saved = covered.savePanel(false);
            helper.assertTrue(covered.foreignClaims().size() == 1, "covered holds the claim");

            // The owner block is removed without the covered block hearing (its claim comes back from the save).
            s.level.destroyBlock(ownerPos, false);
            helper.assertTrue(covered.foreignClaims().isEmpty(), "precondition: a heard break releases the claim");
            covered.loadPanel(saved);
            helper.assertTrue(covered.foreignClaims().size() == 1 && covered.claimAt(0, 5) != null, "orphan restored");
            helper.assertTrue(covered.verifyConsistency(), "owner is air: orphan repaired");
            helper.assertTrue(covered.foreignClaims().isEmpty() && covered.claimAt(0, 5) == null, "claim gone");
            helper.assertTrue(covered.isFree(0, 0, 16, 16), "placement is no longer blocked");
            helper.assertTrue(!covered.verifyConsistency(), "second verify changes nothing");

            // The owner position is a panel again, but owns nothing.
            s.panel(ownerPos, PanelType.VERTICAL);
            covered.loadPanel(saved);
            helper.assertTrue(covered.verifyConsistency() && covered.foreignClaims().isEmpty(), "empty owner: repaired");

            // A claim whose owner really owns the component survives.
            helper.assertTrue(s.placeStraddler() == PanelGroup.Result.OK, "place a real straddler");
            helper.assertTrue(s.be(coveredPos).foreignClaims().size() == 1, "real claim present");
            helper.assertTrue(!s.be(coveredPos).verifyConsistency() && s.be(coveredPos).foreignClaims().size() == 1,
                    "a valid claim survives verification");
            s.then(() -> helper.assertTrue(s.drops().size() == 1,
                    "only the owner break dropped an item, got " + s.drops().size()));
        });
    }

    // ---- real chunk unloading

    private static void feasibilityHold(ServerLevel level, ChunkPos held) {
        level.getChunkSource().addRegionTicket(HOLD, held, 0, held);
    }

    /**
     * Owner chunk unloads, the covered block (next chunk, held at ticket level 33 so it stays loaded) is broken, the
     * owner chunk loads again: the owner's component is repaired with exactly one item.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void unloadedOwnerRepairsWhenItsChunkLoads(GameTestHelper helper) {
        Stage s = new Stage(helper, 3);
        ServerLevel level = s.level;
        BlockPos ownerPos = s.at(4, 0);
        BlockPos coveredPos = s.at(5, 0);
        int[] baseline = new int[1];
        int[] polls = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> poll(helper, s, polls, s.entitiesLive(ownerPos) && s.entitiesLive(coveredPos),
                        "waiting for the fixture chunks to answer entity lookups"))
                .thenExecute(() -> guard(s, () -> {
                    s.prepare();
                    s.wall();
                    helper.assertTrue(s.placeStraddler() == PanelGroup.Result.OK, "straddling plate");
                    helper.assertTrue(!s.ownerChunk().equals(s.coveredChunk()), "the plate spans a chunk border");
                }))
                .thenExecuteAfter(3, () -> guard(s, () -> {
                    baseline[0] = s.waiting();
                    level.setChunkForced(s.ownerChunk().x, s.ownerChunk().z, false);
                    level.setChunkForced(s.coveredChunk().x, s.coveredChunk().z, false);
                    feasibilityHold(level, s.coveredChunk());
                }))
                .thenWaitUntil(() -> poll(helper, s, polls, level.isLoaded(coveredPos) && !level.isLoaded(ownerPos),
                        "waiting for the owner chunk to unload"))
                .thenExecute(() -> guard(s, () -> {
                    // Flags 2|16 (no neighbour updates or shape updates): those would make vanilla load the adjacent (owner) chunk on its own.
                    level.setBlock(coveredPos, Blocks.AIR.defaultBlockState(), 18);
                    helper.assertTrue(s.drops().isEmpty(), "the unloaded owner was not told (and not loaded)");
                    helper.assertTrue(!level.isLoaded(ownerPos), "the owner chunk was not force-loaded by the break");
                    s.forgetLive(); // the owner chunk loads again: its entity lookups have to be seen to work again
                    level.setChunkForced(s.ownerChunk().x, s.ownerChunk().z, true);
                }))
                .thenWaitUntil(() -> poll(helper, s, polls, level.isLoaded(ownerPos)
                        && s.be(ownerPos) != null && s.be(ownerPos).owned().isEmpty(), "waiting for the repair loaded=" + level.isLoaded(ownerPos) + " be=" + (level.isLoaded(ownerPos) ? s.be(ownerPos) + " state=" + level.getBlockState(ownerPos) + " col3=" + level.getBlockState(s.at(3, 0)).getBlock() + " col0=" + level.getBlockState(s.at(0, 0)).getBlock() : "-") + " covered=" + level.isLoaded(coveredPos) + " drops=" + s.drops().size()))
                .thenWaitUntil(() -> poll(helper, s, polls, s.entitiesLive(ownerPos),
                        "waiting for the owner chunk to answer entity lookups again"))
                .thenExecuteAfter(4, () -> guard(s, () -> {
                    helper.assertTrue(s.drops().size() == 1, "one item dropped, got " + s.drops().size());
                    helper.assertTrue(!s.be(ownerPos).verifyConsistency(), "idempotent");
                    helper.assertTrue(s.waiting() == baseline[0], "nothing left deferred: now=" + s.waiting()
                            + " baseline=" + baseline[0] + " " + PanelConsistency.describeWaiting(level));
                }))
                .thenExecuteAfter(3, () -> guard(s, () -> helper.assertTrue(s.drops().size() == 1, "still one item")))
                .thenExecute(s::release)
                .thenSucceed();
    }

    /**
     * The covered chunk really unloads while the owner chunk stays loaded; the owner is then given a straddling
     * component whose covered block (a panel without the claim) sits in the unloaded chunk. Verification defers
     * without loading anything, and when the covered chunk loads the component is repaired, once.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void deferredVerificationRunsWhenCoveredChunkLoads(GameTestHelper helper) {
        Stage s = new Stage(helper, 4);
        ServerLevel level = s.level;
        BlockPos ownerPos = s.at(4, 0);
        BlockPos coveredPos = s.at(5, 0);
        int[] baseline = new int[1];
        int[] before = new int[1];
        int[] polls = new int[1];
        CompoundTag[] saved = new CompoundTag[1];
        helper.startSequence()
                .thenWaitUntil(() -> poll(helper, s, polls, s.entitiesLive(ownerPos) && s.entitiesLive(coveredPos),
                        "waiting for the fixture chunks to answer entity lookups"))
                .thenExecute(() -> guard(s, () -> {
                    s.prepare();
                    s.wall();
                    helper.assertTrue(s.placeStraddler() == PanelGroup.Result.OK, "straddling plate");
                }))
                // New block entities run onLoad on the next level tick; let that (and its queued check) pass first.
                .thenExecuteAfter(4, () -> guard(s, () -> {
                    saved[0] = s.be(ownerPos).savePanel(false);
                    // The owner chunk moves from forced to a level-33 hold while the covered chunk is still forced.
                    feasibilityHold(level, s.ownerChunk());
                    level.setChunkForced(s.ownerChunk().x, s.ownerChunk().z, false);
                }))
                .thenExecuteAfter(4, () -> guard(s, () -> {
                    helper.assertTrue(level.isLoaded(ownerPos) && level.isLoaded(coveredPos), "both chunks still loaded");
                    // Clear both halves silently (no item): the covered block is left a panel without the claim.
                    s.be(ownerPos).loadPanel(new CompoundTag());
                    s.be(coveredPos).loadPanel(new CompoundTag());
                    helper.assertTrue(s.drops().isEmpty(), "nothing dropped");
                    level.setChunkForced(s.coveredChunk().x, s.coveredChunk().z, false);
                }))
                .thenWaitUntil(() -> poll(helper, s, polls, level.isLoaded(ownerPos) && !level.isLoaded(coveredPos),
                        "waiting for the covered chunk to unload"))
                .thenExecuteAfter(8, () -> guard(s, () -> {
                    s.discardDrops();
                    before[0] = s.drops().size();
                    baseline[0] = s.waiting();
                    PanelBlockEntity owner = s.be(ownerPos);
                    helper.assertTrue(owner.owned().isEmpty(), "owner is empty after the wrench removal");
                    owner.loadPanel(saved[0]); // a straddler over a covered block that is not loaded
                    helper.assertTrue(!level.isLoaded(coveredPos), "covered chunk still unloaded");
                    helper.assertTrue(!owner.verifyConsistency(), "undecidable: nothing repaired yet");
                    helper.assertTrue(owner.owned().size() == 1, "component kept while the covered block is unloaded");
                    helper.assertTrue(s.waiting() == baseline[0] + 1, "deferred on the chunk, waiting " + s.waiting() + " baseline "
                            + baseline[0] + " " + PanelConsistency.describeWaiting(level));
                    helper.assertTrue(!level.isLoaded(coveredPos), "verification did not load the covered chunk");
                    helper.assertTrue(s.drops().size() == before[0], "no item yet");
                    level.setChunkForced(s.coveredChunk().x, s.coveredChunk().z, true);
                }))
                .thenWaitUntil(() -> poll(helper, s, polls, level.isLoaded(ownerPos)
                        && s.be(ownerPos).owned().isEmpty(), "waiting for the deferred verification to repair the owner"))
                .thenExecuteAfter(4, () -> guard(s, () -> {
                    helper.assertTrue(s.drops().size() == before[0] + 1, "one item dropped, got " + (s.drops().size() - before[0]));
                    helper.assertTrue(s.waiting() == baseline[0], "deferral cleared, waiting " + s.waiting() + " baseline "
                            + baseline[0] + " " + PanelConsistency.describeWaiting(level));
                    helper.assertTrue(!s.be(ownerPos).verifyConsistency(), "idempotent");
                }))
                .thenExecuteAfter(3, () -> guard(s, () -> helper.assertTrue(s.drops().size() == before[0] + 1, "still one item")))
                .thenExecute(s::release)
                .thenSucceed();
    }

    /** One poll of a wait condition; fails the test (after releasing the fixture) once it has been tried too often. */
    private static void poll(GameTestHelper helper, Stage s, int[] polls, boolean ready, String waiting) {
        if (!ready) {
            if (++polls[0] > 200) {
                s.release();
            }
            helper.assertTrue(false, waiting);
        }
        polls[0] = 0;
    }

    /** Runs a step; on any failure releases the fixture before the failure propagates. */
    private static void guard(Stage s, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | Error e) {
            s.release();
            throw e;
        }
    }
}
