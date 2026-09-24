package dev.bwr.mod.gui;

import dev.bwr.mod.gui.net.MenuCommandPayload;
import dev.bwr.mod.gui.net.MenuSyncPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Shared plumbing for every screen in the mod: find the block entity, push a
 * snapshot of it to the one player looking at it, and take actuator commands
 * back.
 *
 * <h2>Why not {@code ContainerData}</h2>
 * Vanilla's container data slots are 16-bit and are sent one changed value per
 * packet. A reactor panel carries a few hundred lattice cells plus two dozen
 * doubles; encoding pressure in psig, a burnup in MWd/t and a k-infinity into
 * shorts would mean picking a scale factor for each and losing precision at
 * both ends of every one of them. So each menu writes its own snapshot as
 * bytes and reads it back on the client, and the numbers arrive as the doubles
 * the physics produced.
 *
 * <h2>What a menu is allowed to do</h2>
 * Report and actuate. A menu may format a number, colour it, and send a command
 * a player pressed. It may not decide anything: no menu in this package
 * examines plant state and takes an action off the back of it, and there is no
 * command whose meaning depends on conditions at the far end. The refuelling
 * menu refusing to open on a pressurised vessel is not an exception — that is a
 * physical constraint on unbolting a head, the same one that already lives in
 * {@code ReactorControllerBlockEntity.setVesselState}, and it refuses rather
 * than acting.
 */
public abstract class BwrMenu extends AbstractContainerMenu {

    /** Snapshot rate, ticks. 5 ticks is 4 Hz, matching the block entity's own client sync. */
    public static final int DEFAULT_SYNC_INTERVAL_TICKS = 5;

    protected final ContainerLevelAccess access;
    protected final BlockPos pos;
    protected final Player player;
    private final Block block;

    /** Counter towards the next snapshot; starts high so the first tick sends one. */
    private int sinceSync = Integer.MAX_VALUE / 2;

    protected BwrMenu(MenuType<?> type, int containerId, Inventory inventory,
                      BlockPos pos, Block block) {
        super(type, containerId);
        this.player = inventory.player;
        this.pos = pos;
        this.block = block;
        this.access = ContainerLevelAccess.create(inventory.player.level(), pos);
    }

    public BlockPos pos() {
        return pos;
    }

    /** The level this menu's block lives in — the client level on the client. */
    protected Level level() {
        return player.level();
    }

    /** The backing block entity, or null if it has gone away. */
    protected <T extends BlockEntity> T blockEntity(Class<T> type) {
        BlockEntity be = level().getBlockEntity(pos);
        return type.isInstance(be) ? type.cast(be) : null;
    }

    @Override
    public boolean stillValid(Player who) {
        return AbstractContainerMenu.stillValid(access, who, block);
    }

    // -----------------------------------------------------------------
    // Snapshot push
    // -----------------------------------------------------------------

    /** Ticks between snapshots. Override for a map that only needs 1 Hz. */
    protected int syncIntervalTicks() {
        return DEFAULT_SYNC_INTERVAL_TICKS;
    }

    /** Force the next server tick to send a snapshot, e.g. straight after a command. */
    public void markSnapshotDirty() {
        sinceSync = Integer.MAX_VALUE / 2;
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        serverTick(serverPlayer);
        if (++sinceSync >= syncIntervalTicks()) {
            sinceSync = 0;
            sendSnapshot(serverPlayer);
        }
    }

    /** Called every tick server-side while the menu is open. Default does nothing. */
    protected void serverTick(ServerPlayer viewer) {
    }

    private void sendSnapshot(ServerPlayer viewer) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writeSnapshot(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            if (data.length <= MenuSyncPayload.MAX_BYTES) {
                PacketDistributor.sendToPlayer(viewer, new MenuSyncPayload(containerId, data));
            }
        } finally {
            buf.release();
        }
    }

    /** Decode a snapshot on the client. Called by the payload handler. */
    public final void acceptSnapshot(byte[] data) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            readSnapshot(buf);
        } catch (RuntimeException e) {
            // A malformed snapshot must not take the client's world down with
            // it. The screen simply keeps showing the last good values.
            buf.clear();
        } finally {
            buf.release();
        }
    }

    /** Server side: write everything the screen needs. */
    protected abstract void writeSnapshot(FriendlyByteBuf buf);

    /** Client side: read what {@link #writeSnapshot} wrote, in the same order. */
    protected abstract void readSnapshot(FriendlyByteBuf buf);

    // -----------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------

    /** Client side: send an actuator command from a button or a slider. */
    public void sendCommand(int command, int a, int b) {
        PacketDistributor.sendToServer(new MenuCommandPayload(containerId, command, a, b));
    }

    public void sendCommand(int command, int a) {
        sendCommand(command, a, 0);
    }

    public void sendCommand(int command) {
        sendCommand(command, 0, 0);
    }

    /** Server side: apply a command. Validity of the <i>menu</i> is already checked. */
    public abstract void handleCommand(ServerPlayer sender, int command, int a, int b);

    /**
     * Tell the player why something was refused. Used for the physical
     * constraints — a head that cannot come off against pressure — never for
     * advice about whether an action is wise.
     */
    protected static void refuse(ServerPlayer who, String reason) {
        who.displayClientMessage(Component.literal(reason), false);
    }

    // -----------------------------------------------------------------
    // Slots
    // -----------------------------------------------------------------

    /** Standard 3x9 + hotbar layout at a given origin. */
    protected void addPlayerInventory(Inventory inventory, int x, int y, int hotbarY) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, x + col * 18, y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, x + col * 18, hotbarY));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player who, int index) {
        // Menus with no slots of their own have nowhere to shift-click to.
        return ItemStack.EMPTY;
    }

    protected static double clampFraction(double value) {
        return value < 0.0 ? 0.0 : Math.min(1.0, value);
    }

    /** Per-mille is the wire unit for every fraction a slider sends. */
    protected static double fromPerMille(int perMille) {
        return clampFraction(perMille / 1000.0);
    }

    protected static int toPerMille(double fraction) {
        return (int) Math.round(clampFraction(fraction) * 1000.0);
    }
}
