package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.PanelConfig;
import dev.bwr.mod.panel.networking.NetworkShare;
import dev.bwr.mod.panel.networking.NetworkingPayloads;
import dev.bwr.mod.registry.BwrItems;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Part 2: the share relay's range filter (server side), the hover packets and the share distance cap. */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class NetworkShareTests {
    private NetworkShareTests() {
    }

    private static FakePlayer fake(ServerLevel level, String name, double x, double z) {
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes(("net-share-" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), "ns-" + name));
        player.setPos(x, 100, z);
        return player;
    }

    @GameTest(template = "empty")
    public static void relayRangeFilter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "fixture: the nether exists");
        double base = 73_000;
        FakePlayer sender = fake(level, "sender", base, base);
        FakePlayer near = fake(level, "near", base + 10, base);
        FakePlayer far = fake(level, "far", base + 40, base);
        FakePlayer otherDimension = fake(nether, "nether", base + 5, base);
        List<ServerPlayer> all = List.of(sender, near, far, otherDimension);

        List<ServerPlayer> got = NetworkShare.recipients(sender, 32, all);
        helper.assertTrue(got.equals(List.of(near)), "within 32: only the near player in the same dimension: " + got);
        got = NetworkShare.recipients(sender, 100, all);
        helper.assertTrue(got.equals(List.of(near, far)),
                "within 100: near and far, never the other dimension and never the sender: " + got);
        helper.assertTrue(NetworkShare.recipients(sender, 5, all).isEmpty(), "within 5: nobody (the near player is 10 away)");
        helper.assertTrue(NetworkShare.recipients(sender, 0, all).isEmpty(), "a share distance of 0 drops everything");
        helper.assertTrue(NetworkShare.recipients(sender, 10, all).equals(List.of(near)), "exactly on the limit is in");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void shareDistanceIsCappedAtTheLinkRangeAndTheWrenchGatesTheRelay(GameTestHelper helper) {
        helper.assertTrue(NetworkShare.shareDistance() <= PanelConfig.linkRange(), "the share distance never exceeds linkRange");
        helper.assertTrue(PanelConfig.networkLineShareDistance() == NetworkShare.shareDistance(), "one source for the value");

        ServerLevel level = helper.getLevel();
        FakePlayer sender = fake(level, "holder", 73_200, 73_200);
        sender.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertTrue(NetworkShare.handle(sender, 99, BlockPos.ZERO, 0) == -1, "an unknown kind is dropped");
        helper.assertTrue(NetworkShare.handle(sender, NetworkingPayloads.KIND_DEVICE, new BlockPos(1, 2, 3), 0) >= 0,
                "without the wrench a target is treated as a clear and relayed (to nobody here)");
        sender.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BwrItems.NETWORKING_WRENCH.get()));
        // Same game tick: a second target is too soon.
        helper.assertTrue(NetworkShare.handle(sender, NetworkingPayloads.KIND_DEVICE, new BlockPos(1, 2, 3), 0) == -1,
                "a second hover within the same tick is dropped");
        helper.assertTrue(NetworkShare.handle(sender, NetworkingPayloads.KIND_NONE, BlockPos.ZERO, 0) >= 0,
                "a clear is never throttled");
        NetworkShare.forget(sender.getUUID());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void hoverPayloadsRoundTrip(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess();
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
        var hover = new NetworkingPayloads.Hover(NetworkingPayloads.KIND_COMPONENT, new BlockPos(-7, 64, 9000), 12);
        NetworkingPayloads.Hover.STREAM_CODEC.encode(buffer, hover);
        helper.assertTrue(NetworkingPayloads.Hover.STREAM_CODEC.decode(buffer).equals(hover), "hover round trip");
        var relay = new NetworkingPayloads.HoverRelay(UUID.randomUUID(), NetworkingPayloads.KIND_DEVICE, new BlockPos(1, 2, 3), 0);
        var out = new RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
        NetworkingPayloads.HoverRelay.STREAM_CODEC.encode(out, relay);
        helper.assertTrue(NetworkingPayloads.HoverRelay.STREAM_CODEC.decode(out).equals(relay), "relay round trip");
        helper.succeed();
    }
}
