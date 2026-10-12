package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Label uniqueness across group merges, rename refusal, and (with CC present) the {@code bwr_panel} event identity
 * and duplicate-event rules, which live in {@link PanelLabelCcChecks}.
 *
 * <p>Fixture at 50_900 / 50_900: chunks are forced, the volume is emptied before and after, and the chunks are
 * released in every outcome, so a persistent world can run the tests again.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PanelLabelTests {
    private PanelLabelTests() {
    }

    private static final Direction FACING = Direction.NORTH;

    static final class Fixture {
        final GameTestHelper helper;
        final ServerLevel level;
        /** Column 0; columns run toward -X, so a higher column has a lower X (and sorts first by BlockPos). */
        final BlockPos origin = new BlockPos(50_900, 110, 50_900);

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
        }

        BlockPos col(int column) {
            return origin.relative(FACING.getCounterClockWise(), column);
        }

        void panel(int column) {
            level.setBlock(col(column), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                    .setValue(PanelBlock.FACING, FACING), 3);
        }

        PanelBlockEntity be(int column) {
            return (PanelBlockEntity) level.getBlockEntity(col(column));
        }

        PanelComponent toggle(int column, int cx, int cy) {
            PanelComponent component = PanelOps.placeDirect(level, col(column), cx, cy, ComponentTypes.TOGGLE_SWITCH,
                    Footprint.of(2, 3));
            helper.assertTrue(component != null, "toggle did not fit at column " + column);
            return component;
        }

        PanelComponent lamp(int column, int cx, int cy) {
            PanelComponent component = PanelOps.placeDirect(level, col(column), cx, cy, ComponentTypes.LAMP,
                    Footprint.of(1, 1));
            helper.assertTrue(component != null, "lamp did not fit at column " + column);
            return component;
        }

        private void hold(boolean force) {
            for (int x = (origin.getX() - 40) >> 4; x <= (origin.getX() + 40) >> 4; x++) {
                for (int z = (origin.getZ() - 16) >> 4; z <= (origin.getZ() + 16) >> 4; z++) {
                    level.setChunkForced(x, z, force);
                    if (force) {
                        level.getChunk(x, z);
                    }
                }
            }
        }

        void clear() {
            for (int x = -24; x <= 4; x++) {
                for (int y = -12; y <= 12; y++) {
                    for (int z = -6; z <= 6; z++) {
                        BlockPos pos = origin.offset(x, y, z);
                        if (!level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(24, 12, 12))) {
                item.discard();
            }
        }

        void release() {
            clear();
            hold(false);
        }
    }

    /** Forces the chunks, empties the fixture, runs {@code body} two ticks later and always releases. */
    private static void run(GameTestHelper helper, Consumer<Fixture> body) {
        Fixture f = new Fixture(helper);
        f.hold(true);
        helper.runAfterDelay(2, () -> {
            try {
                f.clear();
                body.accept(f);
                helper.succeed();
            } finally {
                f.release();
            }
        });
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, message);
    }

    private static List<String> labels(Fixture f, int... columns) {
        List<String> labels = new ArrayList<>();
        for (int column : columns) {
            for (PanelComponent component : f.be(column).owned()) {
                labels.add(component.label());
            }
        }
        return labels;
    }

    // ---- merge renaming

    /** Builds two groups at columns 0 and 2, each with TS-1 and a custom "Pump", then bridges them at column 1. */
    private static String mergeOnce(Fixture f) {
        f.panel(0);
        f.panel(2);
        PanelComponent toggle0 = f.toggle(0, 0, 0);
        PanelComponent toggle2 = f.toggle(2, 0, 0);
        PanelComponent lamp0 = f.lamp(0, 8, 8);
        PanelComponent lamp2 = f.lamp(2, 8, 8);
        check(f.helper, toggle0.label().equals("TS-1") && toggle2.label().equals("TS-1"),
                "separate groups both start at TS-1");
        lamp0.setLabel("Pump");
        lamp2.setLabel("Pump");
        check(f.helper, f.be(0).group() != f.be(2).group(), "two separate groups before the bridge");

        f.panel(1);
        check(f.helper, f.be(0).group() == f.be(2).group() && f.be(0).group().members().size() == 3, "the bridge merges");
        List<String> after = labels(f, 0, 1, 2);
        check(f.helper, new HashSet<>(after).size() == after.size(), "labels unique after the merge: " + after);
        // Column 2 has the lower X, so it sorts first by BlockPos and keeps the labels.
        check(f.helper, f.col(2).compareTo(f.col(0)) < 0, "fixture assumption: column 2 sorts first");
        check(f.helper, toggle2.label().equals("TS-1") && toggle0.label().equals("TS-2"),
                "the later holder is renamed to the next free prefix label: " + toggle2.label() + " " + toggle0.label());
        check(f.helper, lamp2.label().equals("Pump") && lamp0.label().equals("Pump-2"),
                "a custom label gets a numeric suffix: " + lamp2.label() + " " + lamp0.label());

        // Splitting and re-merging does not rename anything again (no flip-flop).
        String snapshot = String.join(",", labels(f, 0, 2));
        f.level.setBlock(f.col(1), Blocks.AIR.defaultBlockState(), 3);
        check(f.helper, f.be(0).group() != f.be(2).group(), "split again");
        f.panel(1);
        check(f.helper, f.be(0).group() == f.be(2).group(), "merged again");
        check(f.helper, snapshot.equals(String.join(",", labels(f, 0, 2))), "stable across split and merge: " + labels(f, 0, 2));
        // A new component after the merge takes the first free label of the merged group.
        PanelComponent third = f.toggle(1, 0, 0);
        check(f.helper, third.label().equals("TS-3"), "next label after the merge: " + third.label());
        return snapshot;
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void mergingGroupsRenamesDuplicateLabelsDeterministically(GameTestHelper helper) {
        run(helper, f -> {
            String first = mergeOnce(f);
            f.clear();
            String second = mergeOnce(f);
            check(helper, first.equals(second), "same layout, same result: " + first + " vs " + second);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void wrenchRenameToATakenLabelIsRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.panel(0);
            f.panel(1);
            PanelComponent a = f.toggle(0, 0, 0);
            PanelComponent b = f.toggle(1, 0, 0);
            check(helper, a.label().equals("TS-1") && b.label().equals("TS-2"), "labels " + a.label() + " " + b.label());
            FakePlayer player = FakePlayerFactory.get(f.level, new GameProfile(
                    UUID.nameUUIDFromBytes("panel-label-test".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "panel-label"));
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(f.origin.getX() - 0.5, f.origin.getY() - 1.0, f.origin.getZ() - 2.5);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));

            CompoundTag taken = new CompoundTag();
            taken.putString("Label", "TS-2");
            PanelOps.configure(player, f.col(0), a.id(), taken);
            check(helper, a.label().equals("TS-1"), "a taken label is refused: " + a.label());

            CompoundTag free = new CompoundTag();
            free.putString("Label", "MAIN");
            PanelOps.configure(player, f.col(0), a.id(), free);
            check(helper, a.label().equals("MAIN"), "a free label is accepted: " + a.label());
            // The label it gave up is free again.
            CompoundTag old = new CompoundTag();
            old.putString("Label", "TS-1");
            PanelOps.configure(player, f.col(1), b.id(), old);
            check(helper, b.label().equals("TS-1"), "the released label can be taken: " + b.label());
        });
    }

    // ---- CC

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void ccPanelEventCarriesTheAttachmentNameOnce(GameTestHelper helper) {
        if (!BwrMod.isComputerCraftPresent()) {
            helper.succeed();
            return;
        }
        run(helper, f -> PanelLabelCcChecks.events(f));
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void ccPanelListKeepsEveryComponentEvenWithDuplicateLabels(GameTestHelper helper) {
        if (!BwrMod.isComputerCraftPresent()) {
            helper.succeed();
            return;
        }
        run(helper, f -> PanelLabelCcChecks.duplicates(f));
    }
}
