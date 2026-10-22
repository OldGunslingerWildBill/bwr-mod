package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.model.BakedPanelModel;
import dev.bwr.mod.panel.model.PanelModelParser;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A cleared, force-loaded stage with one panel block and a survival fake player in front of it, for the tests of the
 * owner's CAD hardware ({@code BarGaugeTests}, {@code RpsTripTests}, {@code PushTurnTests}). Each test class gives its
 * own far-away origin (no two classes share chunks); the stage empties its volume first, so a second run on a
 * persistent world starts clean, and releases the chunks in every outcome.
 */
final class HardwareStage {
    static final Direction FACING = Direction.NORTH;

    final GameTestHelper helper;
    final ServerLevel level;
    final BlockPos origin;
    final FakePlayer player;

    private HardwareStage(GameTestHelper helper, int originX, int originZ, String name) {
        this.helper = helper;
        this.level = helper.getLevel();
        this.origin = new BlockPos(originX, 110, originZ);
        hold(true);
        this.player = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes(("new-hardware-test-" + name).getBytes(StandardCharsets.UTF_8)), "hw-" + name));
    }

    /** Runs {@code body} on a clean stage two ticks after forcing its chunks; every outcome releases them. */
    static void run(GameTestHelper helper, int originX, int originZ, String name, Consumer<HardwareStage> body) {
        HardwareStage s = new HardwareStage(helper, originX, originZ, name);
        helper.runAfterDelay(2, () -> {
            try {
                s.clear();
                s.player.setGameMode(GameType.SURVIVAL);
                s.player.getInventory().clearContent();
                s.player.setShiftKeyDown(false);
                s.player.setPos(s.origin.getX() + 0.5, s.origin.getY() - 1.0, s.origin.getZ() - 1.5);
                s.level.setBlock(s.origin, BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, FACING), 3);
                body.accept(s);
            } catch (GameTestAssertException e) {
                throw e;
            } catch (RuntimeException | Error e) {
                helper.fail("unexpected " + e);
            } finally {
                s.release();
            }
            helper.succeed();
        });
    }

    private void hold(boolean force) {
        for (int x = (origin.getX() - 8) >> 4; x <= (origin.getX() + 8) >> 4; x++) {
            for (int z = (origin.getZ() - 8) >> 4; z <= (origin.getZ() + 8) >> 4; z++) {
                FixtureChunks.force(level, origin, x, z, force);
                if (force) {
                    level.getChunk(x, z);
                }
            }
        }
    }

    private void clear() {
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(origin).inflate(8))) {
            item.discard();
        }
    }

    private void release() {
        hold(true);
        clear();
        hold(false);
    }

    PanelBlockEntity be() {
        return (PanelBlockEntity) level.getBlockEntity(origin);
    }

    PanelComponent place(ComponentType type, int x, int y, Footprint size) {
        PanelComponent placed = PanelOps.placeDirect(level, origin, x, y, type, size);
        helper.assertTrue(placed != null, "placement refused " + type.id() + " at " + x + "," + y);
        return placed;
    }

    void wrench() {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
    }

    /** The shipped panel model {@code id}, parsed by the game's own loader. */
    static BakedPanelModel shipped(String id) {
        try (InputStream in = HardwareStage.class.getResourceAsStream("/assets/bwr/models/panel/" + id + ".json")) {
            if (in == null) {
                throw new AssertionError("model " + id + " is not shipped");
            }
            return PanelModelParser.parse(id, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("model " + id + " unreadable", e);
        }
    }
}
