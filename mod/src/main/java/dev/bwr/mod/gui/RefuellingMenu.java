package dev.bwr.mod.gui;

import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Refuelling — {@code SPEC.md} section 10.
 *
 * <p>The same lattice grid as the operating panel with the other overlay on it:
 * what is in each position and how burned it is. Burnup visibility is the
 * mechanic. Without it every position is interchangeable and shuffling is a
 * ritual; with it a player can see the exposure gradient their last cycle built
 * and decide what to move where.
 *
 * <h2>Gating</h2>
 * This menu only exists while the vessel head is off. {@link #open} is the only
 * way in and it is called after
 * {@link ReactorControllerBlockEntity#refuellingBlockedReason()} has returned
 * null; if the head goes back on while the screen is up, the server closes it.
 * That is a physical constraint — bundles are lifted out through the top of an
 * open vessel — and the refusal names the actual condition, normally the
 * pressure the vessel is still holding.
 *
 * <h2>Why the lattice is not made of slots</h2>
 * A 21x21 vessel has 441 positions. Vanilla slots would mean 441 of them in the
 * container, synced every tick, plus the shift-click and quick-craft machinery
 * that comes with them. Positions are addressed by index in a command instead,
 * and the bundle itself is a normal item that moves between the core and the
 * player's inventory, which are ordinary slots.
 */
public class RefuellingMenu extends BwrMenu {

    /** a = core slot. Put one bundle from the player's inventory into an empty position. */
    public static final int CMD_LOAD = 0;
    /** a = core slot. Take the bundle out and give it to the player, exposure and all. */
    public static final int CMD_UNLOAD = 1;
    /** a, b = core slots to exchange. */
    public static final int CMD_SWAP = 2;

    private static final int INVENTORY_X = 48;
    private static final int INVENTORY_Y = 148;
    private static final int HOTBAR_Y = 206;

    /** Player inventory only; the core is addressed by index. */
    private static final int PLAYER_SLOT_COUNT = 36;

    // --- client-visible snapshot -------------------------------------

    public boolean open = true;
    public VesselState vesselState = VesselState.REFUELING;
    public int assemblyCount;
    public int loadedAssemblies;
    public double aggregateKInf;
    public double kEffAllRodsOut;
    public double averageBurnup;
    public double peakBurnup;
    public double effectiveBeta;
    public boolean endOfCycle;
    public int bundlesInInventory;
    public CoreMapSnapshot map;

    /** Client constructor. */
    public RefuellingMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public RefuellingMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.REFUELLING.get(), containerId, inventory, pos,
                BwrBlocks.REACTOR_CONTROLLER.get());
        addPlayerInventory(inventory, INVENTORY_X, INVENTORY_Y, HOTBAR_Y);
    }

    public static void open(ServerPlayer player, ReactorControllerBlockEntity be) {
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, who) -> new RefuellingMenu(id, inventory, be.getBlockPos()),
                        Component.translatable("menu.bwr.refuelling")),
                be.getBlockPos());
    }

    private ReactorControllerBlockEntity controller() {
        return blockEntity(ReactorControllerBlockEntity.class);
    }

    @Override
    protected int syncIntervalTicks() {
        // 1 Hz, plus an immediate resend after every command. Deliberately not
        // faster: writing the map reads {@code CoreLoading.powerWeights()}, and
        // moving a bundle invalidates those, so each snapshot after an edit
        // costs a nodal re-solve. One per player action is the honest price;
        // one per player action plus four idle ones a second is not. Nothing
        // else about a shut down, depressurised, open vessel is moving.
        return 20;
    }

    /**
     * Close the screen the moment the head goes back on. Not an interlock: the
     * head going on is an action the player took, and this is the GUI noticing
     * that what it is showing no longer exists.
     */
    @Override
    protected void serverTick(ServerPlayer viewer) {
        ReactorControllerBlockEntity be = controller();
        if (be == null || be.vesselState() != VesselState.REFUELING) {
            viewer.closeContainer();
        }
    }

    // -----------------------------------------------------------------
    // Snapshot
    // -----------------------------------------------------------------

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        ReactorControllerBlockEntity be = controller();
        boolean usable = be != null && be.isFormed();
        buf.writeBoolean(usable);
        if (!usable) {
            return;
        }
        var loading = be.core().getCoreLoading();

        buf.writeByte(be.vesselState().ordinal());
        buf.writeVarInt(be.assemblyCount());
        buf.writeVarInt(loading.loadedAssemblyCount());
        buf.writeDouble(loading.aggregateKInf());
        buf.writeDouble(loading.kEffAllRodsOut());
        buf.writeDouble(loading.averageBurnupMwdPerTonne());
        buf.writeDouble(loading.peakBurnupMwdPerTonne());
        buf.writeDouble(loading.effectiveBeta());
        buf.writeBoolean(loading.isEndOfCycle());
        buf.writeVarInt(countBundles(player));
        CoreMapSnapshot.write(buf, be.core(), be.assemblyCount());
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
        open = buf.readBoolean();
        if (!open) {
            return;
        }
        vesselState = VesselState.values()[buf.readByte() % VesselState.values().length];
        assemblyCount = buf.readVarInt();
        loadedAssemblies = buf.readVarInt();
        aggregateKInf = buf.readDouble();
        kEffAllRodsOut = buf.readDouble();
        averageBurnup = buf.readDouble();
        peakBurnup = buf.readDouble();
        effectiveBeta = buf.readDouble();
        endOfCycle = buf.readBoolean();
        bundlesInInventory = buf.readVarInt();
        map = CoreMapSnapshot.read(buf);
    }

    // -----------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        ReactorControllerBlockEntity be = controller();
        if (be == null || !be.isFormed()) {
            return;
        }
        String blocked = be.refuellingBlockedReason();
        if (blocked != null) {
            refuse(sender, "Refuelling is not possible: " + blocked + ".");
            return;
        }

        int[] positions = be.corePositions();
        switch (command) {
            case CMD_LOAD -> load(sender, be, positions, a);
            case CMD_UNLOAD -> unload(sender, be, positions, a);
            case CMD_SWAP -> {
                if (inRange(positions, a) && inRange(positions, b) && a != b) {
                    be.swapAssemblies(positions[a], positions[b]);
                }
            }
            default -> {
                return;
            }
        }
        markSnapshotDirty();
    }

    private void load(ServerPlayer sender, ReactorControllerBlockEntity be,
                      int[] positions, int slot) {
        if (!inRange(positions, slot)) {
            return;
        }
        Inventory inventory = sender.getInventory();
        // The held bundle first, so a player who cares which one goes where can
        // choose it by holding it. Two bundles are not interchangeable — that is
        // the entire point of exposure travelling on the item.
        int found = inventory.getSelected().is(BwrItems.FUEL_ASSEMBLY.get())
                ? inventory.selected
                : -1;
        for (int i = 0; found < 0 && i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(BwrItems.FUEL_ASSEMBLY.get())) {
                found = i;
            }
        }
        if (found < 0) {
            refuse(sender, "No fuel assembly in your inventory.");
            return;
        }
        ItemStack stack = inventory.getItem(found);
        if (be.loadAssembly(positions[slot], stack)) {
            stack.shrink(1);
            inventory.setChanged();
        }
    }

    private void unload(ServerPlayer sender, ReactorControllerBlockEntity be,
                        int[] positions, int slot) {
        if (!inRange(positions, slot)) {
            return;
        }
        ItemStack removed = be.unloadAssembly(positions[slot]);
        if (removed.isEmpty()) {
            return;
        }
        if (!sender.getInventory().add(removed)) {
            // 180 kg of irradiated heavy metal is not something to void because
            // a player's inventory was full.
            Containers.dropItemStack(sender.level(), sender.getX(), sender.getY(),
                    sender.getZ(), removed);
        }
    }

    private static boolean inRange(int[] positions, int slot) {
        return slot >= 0 && slot < positions.length;
    }

    private static int countBundles(Player who) {
        int total = 0;
        Inventory inventory = who.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(BwrItems.FUEL_ASSEMBLY.get())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // -----------------------------------------------------------------
    // Slots
    // -----------------------------------------------------------------

    @Override
    public ItemStack quickMoveStack(Player who, int index) {
        // Shift-clicking moves a bundle between the hotbar and the main
        // inventory. It cannot put one in the core: a core position is chosen
        // by clicking the map, because which position matters.
        if (index < 0 || index >= PLAYER_SLOT_COUNT) {
            return ItemStack.EMPTY;
        }
        var slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean fromHotbar = index >= 27;
        boolean moved = fromHotbar
                ? moveItemStackTo(stack, 0, 27, false)
                : moveItemStackTo(stack, 27, PLAYER_SLOT_COUNT, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }
}
