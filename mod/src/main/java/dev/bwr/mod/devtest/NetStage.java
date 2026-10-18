package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.fuel.BwrDataComponents;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.PanelOps;
import dev.bwr.mod.panel.component.ComponentType;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.Footprint;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.panel.net.PanelNetwork;
import dev.bwr.mod.panel.net.PanelPayloads;
import dev.bwr.mod.panel.wiring.WiringOps;
import dev.bwr.mod.panel.wiring.WiringOps.BindKind;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The fixture of the Networking Wrench tests: a cleared, force-loaded stage with a survival fake player in front of
 * a wall of panels and devices behind it, run in timed steps. Every test takes its own slot (its own far-away origin,
 * from {@code 71_500} east), empties the volume first so a second run on a persistent world starts clean, and
 * releases the chunks in every outcome. Bindings are sent through the real {@link PanelPayloads.Bind} payload: encoded
 * and decoded with the wire codec, then handed to the same handler the network calls.
 */
final class NetStage {
    static final Direction FACING = Direction.NORTH;
    private static final int BASE_X = 71_500;
    private static final int BASE_Z = 71_500;

    final GameTestHelper helper;
    final ServerLevel level;
    /** Top-left block of the panel wall. Columns run toward -X (the right of a north-facing panel). */
    final BlockPos origin;
    final FakePlayer player;
    /** Where the first device stands: well inside the force-loaded area and 8 blocks below the wall. */
    final BlockPos devices;

    private final List<Runnable> steps = new ArrayList<>();
    private final List<Integer> delays = new ArrayList<>();

    private NetStage(GameTestHelper helper, int slot, ServerLevel level) {
        this.helper = helper;
        this.level = level;
        origin = new BlockPos(BASE_X + slot * 64, 110, BASE_Z);
        devices = origin.offset(-12, -8, 0);
        hold(true);
        player = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes(("networking-wrench-test-" + slot).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "net-wrench-" + slot));
    }

    /** Runs {@code body} on a clean fixture two ticks after the chunks are forced, then each {@link #then} step in order. */
    static void run(GameTestHelper helper, int slot, Consumer<NetStage> body) {
        run(helper, slot, helper.getLevel(), body);
    }

    /** {@link #run(GameTestHelper, int, Consumer)} in another level (the Nether, where a test may narrow the world border). */
    static void run(GameTestHelper helper, int slot, ServerLevel level, Consumer<NetStage> body) {
        NetStage s = new NetStage(helper, slot, level);
        helper.runAfterDelay(2, () -> {
            try {
                s.clear();
                s.player.setGameMode(GameType.SURVIVAL);
                s.player.getInventory().clearContent();
                s.player.setShiftKeyDown(false);
                s.player.setPos(s.origin.getX() - 1.5, s.origin.getY() - 1.0, s.origin.getZ() - 2.5);
                body.accept(s);
            } catch (RuntimeException | Error e) {
                s.release();
                throw e;
            }
            s.next();
        });
    }

    /** A step to run {@code delay} ticks after the previous one. */
    void then(int delay, Runnable step) {
        delays.add(delay);
        steps.add(step);
    }

    private void next() {
        if (steps.isEmpty()) {
            release();
            helper.succeed();
            return;
        }
        int delay = delays.remove(0);
        Runnable step = steps.remove(0);
        helper.runAfterDelay(delay, () -> {
            try {
                step.run();
            } catch (RuntimeException | Error e) {
                release();
                throw e;
            }
            next();
        });
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

    private void clear() {
        for (int x = -30; x <= 4; x++) {
            for (int y = -12; y <= 12; y++) {
                for (int z = -6; z <= 6; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(30, 12, 12))) {
            item.discard();
        }
    }

    void release() {
        clear();
        hold(false);
    }

    void check(boolean condition, String message) {
        helper.assertTrue(condition, message);
    }

    // ---- panels and components

    BlockPos col(int column) {
        return origin.relative(FACING.getCounterClockWise(), column);
    }

    BlockPos at(int column, int row) {
        return col(column).below(row);
    }

    void wall(int columns, int rows) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                level.setBlock(at(c, r), BwrBlocks.CONTROL_PANEL_VERTICAL.get().defaultBlockState()
                        .setValue(PanelBlock.FACING, FACING), 3);
            }
        }
    }

    PanelBlockEntity be(BlockPos pos) {
        return (PanelBlockEntity) level.getBlockEntity(pos);
    }

    PanelComponent place(BlockPos block, int cx, int cy, ComponentType type, Footprint footprint) {
        PanelComponent component = PanelOps.placeDirect(level, block, cx, cy, type, footprint);
        helper.assertTrue(component != null, type.id() + " did not fit at " + cx + "," + cy);
        return component;
    }

    PanelComponent gauge(BlockPos block, int cx, int cy) {
        return place(block, cx, cy, ComponentTypes.DIAL_GAUGE, Footprint.of(12, 12));
    }

    PanelComponent control(BlockPos block, int cx, int cy) {
        return place(block, cx, cy, ComponentTypes.PUSHBUTTON, Footprint.of(6, 6));
    }

    // ---- devices

    MainSteamIsolationValveBlockEntity msiv(BlockPos root) {
        var block = BwrBlocks.MSIV.get();
        var state = block.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
        level.setBlock(root, state, 3);
        block.setPlacedBy(level, root, state, null, new ItemStack(block));
        var valve = (MainSteamIsolationValveBlockEntity) level.getBlockEntity(root);
        valve.setDemandOpen(false);
        valve.tickValve(5);
        return valve;
    }

    /** A suppression pool controller (one block): signals {@code level} (LEVEL) and {@code temperature}. */
    net.minecraft.world.level.block.entity.BlockEntity pool(BlockPos at) {
        level.setBlock(at, BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get().defaultBlockState(), 3);
        return level.getBlockEntity(at);
    }

    /** An FE meter (one block): signals {@code mw_now} (POWER, MW) and {@code fe_per_tick_now} (POWER, FE/t). */
    net.minecraft.world.level.block.entity.BlockEntity meter(BlockPos at) {
        level.setBlock(at, BwrBlocks.FE_METER.get().defaultBlockState(), 3);
        return level.getBlockEntity(at);
    }

    void removeMsiv(BlockPos root) {
        level.removeBlock(root.above(2), false);
        level.removeBlock(root.above(), false);
        level.removeBlock(root, false);
    }

    /** Moves the player next to {@code pos} (in reach of it), as a player who just clicked the block stands. */
    void standBy(BlockPos pos) {
        player.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 2.5);
    }

    /** Moves the player back in front of the panel wall. */
    void standAtPanels() {
        player.setPos(origin.getX() - 1.5, origin.getY() - 1.0, origin.getZ() - 2.5);
    }

    // ---- tools

    /** Puts a Networking Wrench in the player's main hand, with {@code device} selected (or none). */
    ItemStack wrench(BlockPos device) {
        ItemStack stack = new ItemStack(BwrItems.NETWORKING_WRENCH.get());
        if (device != null) {
            stack.set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(level.dimension(), device));
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return stack;
    }

    /** Puts a Panel Linker in the player's main hand, with {@code device} stored (or none). */
    ItemStack linker(BlockPos device) {
        ItemStack stack = new ItemStack(BwrItems.PANEL_LINKER.get());
        if (device != null) {
            stack.set(BwrDataComponents.LINKED_DEVICE.get(), GlobalPos.of(level.dimension(), device));
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return stack;
    }

    /** Applies config fields with the Panel Wrench, as its screen's Done does. */
    void configure(PanelBlockEntity block, PanelComponent component, CompoundTag config) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.PANEL_WRENCH.get()));
        CompoundTag edit = new CompoundTag();
        edit.put("Config", config);
        helper.assertTrue(PanelOps.configure(player, block.getBlockPos(), component.id(), edit),
                "configure refused for " + component.label());
    }

    // ---- real payloads

    /** The player as the payload context the network hands to a handler. */
    IPayloadContext context() {
        return (IPayloadContext) java.lang.reflect.Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
                new Class<?>[] {IPayloadContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("player")) {
                        return player;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Sends a payload as the client would: encoded, decoded with the wire codec, then given to the real handler. */
    PanelPayloads.Bind wire(PanelPayloads.Bind bind) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        PanelPayloads.Bind.STREAM_CODEC.encode(buffer, bind);
        PanelPayloads.Bind decoded = PanelPayloads.Bind.STREAM_CODEC.decode(buffer);
        PanelNetwork.handleBind(decoded, context());
        return decoded;
    }

    /** Sends any payload as the client would: encoded, decoded with its wire codec, then given to the real handler. */
    <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> T wire(
            net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T> codec, T payload,
            java.util.function.BiConsumer<T, IPayloadContext> handler) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        codec.encode(buffer, payload);
        T decoded = codec.decode(buffer);
        handler.accept(decoded, context());
        return decoded;
    }

    /** A plant input bind request for {@code port} of {@code component}. */
    PanelPayloads.Bind plantIn(PanelBlockEntity block, PanelComponent component, int port, BlockPos device, String key,
                               double scale, double offset) {
        return new PanelPayloads.Bind(block.getBlockPos(), component.id(), false, port, BindKind.PLANT.ordinal(), device, key,
                BlockPos.ZERO, 0, 0, scale, 0, 0.0, offset);
    }

    /** A plant output (command) bind request for output {@code port} of {@code component}, following the output. */
    PanelPayloads.Bind plantOut(PanelBlockEntity block, PanelComponent component, int port, BlockPos device, String key) {
        return new PanelPayloads.Bind(block.getBlockPos(), component.id(), true, port, BindKind.PLANT.ordinal(), device, key,
                BlockPos.ZERO, 0, 0, 1.0);
    }

    /** What the server would answer a bind request with (result and refusal reason), for the same payload. */
    WiringOps.Outcome outcome(PanelPayloads.Bind bind) {
        return WiringOps.bindDetailed(player, bind);
    }
}
