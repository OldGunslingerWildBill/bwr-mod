package dev.bwr.mod.gui;

import dev.bwr.mod.fabrication.FabricationCrafting;
import dev.bwr.mod.fabrication.FabricationCrafting.Outcome;
import dev.bwr.mod.fabrication.FabricationRecipe;
import dev.bwr.mod.panel.PanelRateLimit;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The fabrication desk's screen (FABRICATION-DESK.md). It has no slots: the player picks a part from a list, the screen shows
 * what it is made of with the player's counts, and the Craft button asks the server for it.
 *
 * <p>The recipes are the server's loaded {@code bwr:fabrication} recipes, which the game already sends to the client. What the
 * player holds comes down in the menu's snapshot (the paying stacks as item and count), not from the client's own inventory:
 * the client's inventory is only brought up to date when no other screen is open, and the numbers on the screen must be the
 * ones the server will check. The snapshot is re-sent when the inventory changes, and after every request, together with what
 * the request came to.
 *
 * <p>One command: {@link #CMD_CRAFT} with the recipe key ({@link FabricationCrafting#key}) and the number of crafts wanted
 * (1, or {@link FabricationRecipe#MAX_BATCH} for a shift-click). The server decides everything again in
 * {@link FabricationCrafting#craft}; this menu only adds the rate limit.
 */
public final class FabricationDeskMenu extends BwrMenu {
    public static final int CMD_CRAFT = 1;

    /** What the player holds that can pay (the stacks the server counts), as the last snapshot said. */
    public List<ItemStack> held = List.of();
    /** What the last request came to, and how many crafts it made; {@code serial} counts requests, so the screen sees a repeat. */
    public Outcome lastOutcome = Outcome.CRAFTED;
    public int lastCrafts;
    public int lastDropped;
    public int serial;
    /** True once a request has been answered (before that the screen shows no result line). */
    public boolean answered;

    private long signature;

    public FabricationDeskMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) {
        this(id, inventory, data.readBlockPos());
    }

    public FabricationDeskMenu(int id, Inventory inventory, BlockPos pos) {
        super(BwrMenus.FABRICATION_DESK.get(), id, inventory, pos, BwrBlocks.FABRICATION_DESK.get());
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        player.openMenu(new SimpleMenuProvider((id, inventory, who) -> new FabricationDeskMenu(id, inventory, pos),
                Component.translatable("block.bwr.fabrication_desk")), pos);
    }

    // ---- snapshot

    /** The paying stacks as one number, so the menu notices a change without comparing lists. */
    private long signatureOf(List<ItemStack> stacks) {
        long hash = 1125899906842597L;
        for (ItemStack stack : stacks) {
            hash = 31 * hash + (stack.isEmpty() ? 0 : BuiltInRegistries.ITEM.getId(stack.getItem()) * 131L + stack.getCount());
        }
        return hash;
    }

    @Override
    protected void serverTick(ServerPlayer viewer) {
        long now = signatureOf(FabricationCrafting.payingStacks(viewer.getInventory()));
        if (now != signature) {
            signature = now;
            markSnapshotDirty();
        }
    }

    /** Never on a timer: a snapshot goes out when something changed (serverTick, handleCommand) and once when the screen opens. */
    @Override
    protected int syncIntervalTicks() {
        return Integer.MAX_VALUE / 4;
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        buf.writeVarInt(lastOutcome.ordinal());
        buf.writeVarInt(lastCrafts);
        buf.writeVarInt(lastDropped);
        buf.writeVarInt(serial);
        buf.writeBoolean(answered);
        List<ItemStack> stacks = player instanceof ServerPlayer
                ? FabricationCrafting.payingStacks(player.getInventory()) : List.of();
        int count = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                count++;
            }
        }
        buf.writeVarInt(count);
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                buf.writeVarInt(BuiltInRegistries.ITEM.getId(stack.getItem()));
                buf.writeVarInt(stack.getCount());
            }
        }
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
        // read everything first: a snapshot that fails halfway must leave the last good state alone
        int outcome = buf.readVarInt();
        int crafts = buf.readVarInt();
        int droppedNow = buf.readVarInt();
        int serialNow = buf.readVarInt();
        boolean answeredNow = buf.readBoolean();
        int n = buf.readVarInt();
        if (outcome < 0 || outcome >= Outcome.values().length || n < 0 || n > 64) {
            throw new IllegalArgumentException("bad fabrication snapshot");
        }
        List<ItemStack> stacks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Item item = BuiltInRegistries.ITEM.byId(buf.readVarInt());
            stacks.add(new ItemStack(item, Math.max(1, buf.readVarInt())));
        }
        lastOutcome = Outcome.values()[outcome];
        lastCrafts = crafts;
        lastDropped = droppedNow;
        serial = serialNow;
        answered = answeredNow;
        held = List.copyOf(stacks);
    }

    // ---- command

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        if (command != CMD_CRAFT) {
            return;
        }
        FabricationCrafting.Result result;
        if (!PanelRateLimit.tryAcquire(sender, PanelRateLimit.Kind.FABRICATE)) {
            result = new FabricationCrafting.Result(Outcome.RATE_LIMITED, 0, 0);
        } else {
            result = FabricationCrafting.craft(sender, pos, a, b);
        }
        lastOutcome = result.outcome();
        lastCrafts = result.crafts();
        lastDropped = result.dropped();
        serial++;
        answered = true;
        signature = signatureOf(FabricationCrafting.payingStacks(sender.getInventory()));
        markSnapshotDirty();
        if (result.outcome() == Outcome.CRAFTED) {
            sender.inventoryMenu.broadcastChanges();
        }
    }
}
