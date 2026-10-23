package dev.bwr.mod.gui;

import dev.bwr.mod.config.GameplayConfig;
import dev.bwr.mod.refuelling.BridgeSite;
import dev.bwr.mod.refuelling.RefuellingBridgeBlockEntity;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import dev.bwr.mod.text.BwrText;
import net.minecraft.world.inventory.ContainerLevelAccess;

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
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
 * A maximum compact core has 1,476 positions. Vanilla slots would put all of them in the
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
    /** Bridge: a = core slot index, b = pool cell index (rack index x 4 + cell). Queue a move from the core to the pool. */
    public static final int CMD_BRIDGE_TO_POOL = 10;
    /** Bridge: a = pool cell index, b = core slot index. Queue a move from the pool to the core. */
    public static final int CMD_BRIDGE_TO_CORE = 11;
    /** Bridge: stop where it is and forget the queue. */
    public static final int CMD_BRIDGE_ABORT = 12;
    /** Bridge: after a halt, try the step again. */
    public static final int CMD_BRIDGE_RESUME = 13;
    /** Bridge: a = 1 overrides the hoist-limit interlock, 0 restores it. */
    public static final int CMD_BRIDGE_OVERRIDE = 14;
    /** Bridge: a = pool cell index. Set the assembly on the grapple down in that cell. */
    public static final int CMD_BRIDGE_SET_DOWN = 15;

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
    /** The server lets this map move fuel instantly ({@code instantRefuelling}); off, its buttons point to the bridge. */
    public boolean instant;

    // --- the refuelling bridge, when this map is the bridge's control ------

    /** True when the screen was opened from a bridge console. */
    public boolean bridgeMode;
    public BlockPos consolePos;
    public String bridgePhase = "";
    public boolean bridgeBusy;
    public boolean bridgeHalted;
    public double bridgeProgress;
    public int bridgeSecondsLeft;
    public int bridgeQueued;
    public boolean bridgeOverride;
    public boolean bridgeHolding;
    public String bridgeCarried = "";
    public String bridgeMessage = "";
    public String bridgeChute = "ABSENT";
    public double bridgeCavityWaterM;
    public double bridgePoolCoverM = Double.NaN;
    public double bridgeCarriedCoverM;
    public boolean bridgePool;
    /** One entry per rack of the bridge's pool: occupancy bits (cell k = bit k) and decay kW of each cell. */
    public java.util.List<BridgeRack> bridgeRacks = new java.util.ArrayList<>();

    public record BridgeRack(BlockPos pos, int mask, float[] decayKw) {
    }

    /** Client constructor. */
    public RefuellingMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos(), extra.readBoolean() ? extra.readBlockPos() : null);
    }

    public RefuellingMenu(int containerId, Inventory inventory, BlockPos pos) {
        this(containerId, inventory, pos, null);
    }

    public RefuellingMenu(int containerId, Inventory inventory, BlockPos pos, BlockPos console) {
        super(BwrMenus.REFUELLING.get(), containerId, inventory, pos,
                BwrBlocks.REACTOR_CONTROLLER.get());
        this.consolePos = console;
        this.bridgeMode = console != null;
        if (!bridgeMode) {
            addPlayerInventory(inventory, INVENTORY_X, INVENTORY_Y, HOTBAR_Y);
        }
    }

    public static void open(ServerPlayer player, ReactorControllerBlockEntity be) {
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, who) -> new RefuellingMenu(id, inventory, be.getBlockPos()),
                        Component.translatable("menu.bwr.refuelling")),
                (RegistryFriendlyByteBuf buf) -> {
                    buf.writeBlockPos(be.getBlockPos());
                    buf.writeBoolean(false);
                });
    }

    /** Opens the map as the control of the refuelling bridge whose console is {@code console}. */
    public static void openBridge(ServerPlayer player, ReactorControllerBlockEntity be, RefuellingBridgeBlockEntity console) {
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, who) -> new RefuellingMenu(id, inventory, be.getBlockPos(), console.getBlockPos()),
                        Component.translatable("menu.bwr.refuelling_bridge")),
                (RegistryFriendlyByteBuf buf) -> {
                    buf.writeBlockPos(be.getBlockPos());
                    buf.writeBoolean(true);
                    buf.writeBlockPos(console.getBlockPos());
                });
    }

    @Override
    public boolean stillValid(Player who) {
        if (bridgeMode) {
            return AbstractContainerMenu.stillValid(ContainerLevelAccess.create(who.level(), consolePos), who,
                    BwrBlocks.REFUELLING_BRIDGE_CONSOLE.get());
        }
        return super.stillValid(who);
    }

    private RefuellingBridgeBlockEntity bridge() {
        return bridgeMode && level().getBlockEntity(consolePos) instanceof RefuellingBridgeBlockEntity b ? b : null;
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
        if (be == null || (!bridgeMode && be.vesselState() != VesselState.REFUELING)) {
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
        CoreMapSnapshot.write(buf, be.core(), be.corePositions());
        buf.writeBoolean(GameplayConfig.instantRefuelling());
        RefuellingBridgeBlockEntity bridge = bridge();
        buf.writeBoolean(bridge != null);
        if (bridge != null) {
            writeBridge(buf, bridge, be);
        }
    }

    private void writeBridge(FriendlyByteBuf buf, RefuellingBridgeBlockEntity bridge, ReactorControllerBlockEntity reactor) {
        BridgeSite site = bridge.refreshSite();
        buf.writeUtf(bridge.phase().name());
        buf.writeBoolean(bridge.busy());
        buf.writeDouble(bridge.progress());
        buf.writeVarInt((bridge.phaseTicksLeft() + 19) / 20);
        buf.writeVarInt(bridge.queued());
        buf.writeBoolean(bridge.override());
        buf.writeBoolean(!bridge.carried().isEmpty());
        buf.writeUtf(bridge.carriedSummary());
        Component halt = site.ok() ? bridge.haltMessage() : site.problem;
        buf.writeUtf(halt == null ? "" : BwrText.english(halt));
        buf.writeUtf(bridge.chute().state().name());
        buf.writeDouble(BridgeSite.cavityWaterM(reactor));
        SpentFuelPoolBlockEntity pool = site.ok() ? site.pool(level()) : null;
        buf.writeBoolean(pool != null);
        if (pool == null) {
            return;
        }
        buf.writeDouble(BridgeSite.poolSurfaceY(pool) - (pool.layout().min().getY() + 1 + dev.bwr.mod.spentfuel.SpentFuelPoolLayout.RACK_HEIGHT));
        buf.writeDouble(bridge.carriedCoverM());
        var racks = pool.layout().racks();
        long now = level().getGameTime();
        buf.writeVarInt(racks.size());
        for (BlockPos rack : racks) {
            buf.writeBlockPos(rack);
            int mask = 0;
            for (int k = 0; k < 4; k++) {
                if (!pool.stackAt(rack, k).isEmpty()) {
                    mask |= 1 << k;
                }
            }
            buf.writeByte(mask);
            for (int k = 0; k < 4; k++) {
                if ((mask & (1 << k)) != 0) {
                    buf.writeFloat((float) (dev.bwr.mod.fuel.SpentFuelHeat.decayWatts(pool.stackAt(rack, k), now) / 1000.0));
                }
            }
        }
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
        instant = buf.readBoolean();
        bridgeBusy = false;
        if (buf.readBoolean()) {
            readBridge(buf);
        }
    }

    private void readBridge(FriendlyByteBuf buf) {
        bridgePhase = buf.readUtf();
        bridgeBusy = buf.readBoolean();
        bridgeHalted = bridgePhase.equals("HALTED");
        bridgeProgress = buf.readDouble();
        bridgeSecondsLeft = buf.readVarInt();
        bridgeQueued = buf.readVarInt();
        bridgeOverride = buf.readBoolean();
        bridgeHolding = buf.readBoolean();
        bridgeCarried = buf.readUtf();
        bridgeMessage = buf.readUtf();
        bridgeChute = buf.readUtf();
        bridgeCavityWaterM = buf.readDouble();
        bridgePool = buf.readBoolean();
        bridgeRacks = new java.util.ArrayList<>();
        if (!bridgePool) {
            return;
        }
        bridgePoolCoverM = buf.readDouble();
        bridgeCarriedCoverM = buf.readDouble();
        int racks = buf.readVarInt();
        for (int i = 0; i < racks && i < 64; i++) {
            BlockPos at = buf.readBlockPos();
            int mask = buf.readByte();
            float[] kw = new float[4];
            for (int k = 0; k < 4; k++) {
                if ((mask & (1 << k)) != 0) {
                    kw[k] = buf.readFloat();
                }
            }
            bridgeRacks.add(new BridgeRack(at, mask, kw));
        }
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
        if (command >= CMD_BRIDGE_TO_POOL) {
            handleBridge(sender, be, command, a, b);
            markSnapshotDirty();
            return;
        }
        if (!GameplayConfig.instantRefuelling()) {
            refuse(sender, instantOffMessage());
            return;
        }
        Component blocked = be.refuellingBlockedMessage();
        if (blocked != null) {
            refuse(sender, BwrText.tr("status.bwr.refuelling_menu.refuelling_is_not_possible", "Refuelling is not possible: %s." , blocked));
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

    /** What the instant map says when {@code instantRefuelling} is off: how fuel moves instead. */
    public static Component instantOffMessage() {
        return BwrText.tr("status.bwr.refuelling_menu.instant_off",
                "Instant refuelling is off on this server. Move fuel with the refuelling bridge: lay two parallel runs of refuelling rail over the reactor and the spent fuel pool, join the reactor to the pool with a row of transfer canal blocks and a gate, stand a bridge console beside a rail and right-click it. (The server option instantRefuelling turns the instant map back on.)");
    }

    private void handleBridge(ServerPlayer sender, ReactorControllerBlockEntity be, int command, int a, int b) {
        RefuellingBridgeBlockEntity bridge = bridge();
        if (bridge == null) {
            return;
        }
        switch (command) {
            case CMD_BRIDGE_ABORT -> bridge.abort();
            case CMD_BRIDGE_RESUME -> bridge.resume();
            case CMD_BRIDGE_OVERRIDE -> bridge.setOverride(a != 0);
            case CMD_BRIDGE_TO_POOL, CMD_BRIDGE_TO_CORE, CMD_BRIDGE_SET_DOWN -> {
                BridgeSite site = bridge.refreshSite();
                SpentFuelPoolBlockEntity pool = site.ok() ? site.pool(level()) : null;
                int[] positions = be.corePositions();
                int cellIndex = command == CMD_BRIDGE_TO_POOL ? b : a;
                int coreIndex = command == CMD_BRIDGE_TO_POOL ? a : b;
                if (pool == null) {
                    refuse(sender, BwrText.tr("status.bwr.refuelling_bridge.no_pool", "No formed spent fuel pool lies between the bridge's rails."));
                    return;
                }
                var racks = pool.layout().racks();
                if (cellIndex < 0 || cellIndex / 4 >= racks.size()) {
                    return;
                }
                RefuellingBridgeBlockEntity.Loc cell = RefuellingBridgeBlockEntity.Loc.rack(racks.get(cellIndex / 4), cellIndex % 4);
                RefuellingBridgeBlockEntity.Move move;
                if (command == CMD_BRIDGE_SET_DOWN) {
                    move = new RefuellingBridgeBlockEntity.Move(RefuellingBridgeBlockEntity.Loc.HELD, cell);
                } else {
                    if (!inRange(positions, coreIndex)) {
                        return;
                    }
                    RefuellingBridgeBlockEntity.Loc core = RefuellingBridgeBlockEntity.Loc.core(positions[coreIndex]);
                    move = command == CMD_BRIDGE_TO_POOL
                            ? new RefuellingBridgeBlockEntity.Move(core, cell)
                            : new RefuellingBridgeBlockEntity.Move(cell, core);
                }
                Component why = bridge.queue(move);
                if (why != null) {
                    refuse(sender, why);
                } else {
                    sender.displayClientMessage(BwrText.tr("status.bwr.refuelling_bridge.queued", "Move queued: %s to %s.",
                            move.from().describe(), move.to().describe()), true);
                }
            }
            default -> {
            }
        }
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
        int found = dev.bwr.mod.fuel.SpecialtyRodItem.isCoreItem(inventory.getSelected())
                ? inventory.selected
                : -1;
        for (int i = 0; found < 0 && i < inventory.getContainerSize(); i++) {
            if (dev.bwr.mod.fuel.SpecialtyRodItem.isCoreItem(inventory.getItem(i))) {
                found = i;
            }
        }
        if (found < 0) {
            refuse(sender, BwrText.tr(
                    "status.bwr.refuelling_menu.no_fuel_assembly_or_specialty_rod_in_your",
                    "No fuel assembly or specialty rod in your inventory."));
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
        // Inventory.add silently discards overflow in Creative. This helper
        // finds actual space first and drops any remainder in every game mode.
        sender.getInventory().placeItemBackInInventory(removed);
    }

    private static boolean inRange(int[] positions, int slot) {
        return slot >= 0 && slot < positions.length;
    }

    private static int countBundles(Player who) {
        int total = 0;
        Inventory inventory = who.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (dev.bwr.mod.fuel.SpecialtyRodItem.isCoreItem(stack)) {
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

    public void sendBatch(boolean load, int[] slots) {
        if (slots.length > 0) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new dev.bwr.mod.gui.net.RefuellingBatchPayload(containerId, load, slots));
        }
    }

    public void handleBatch(ServerPlayer sender, boolean loading, int[] slots) {
        if (slots.length == 0 || slots.length > dev.bwr.mod.gui.net.RefuellingBatchPayload.MAX_SLOTS) {
            return;
        }
        var be = controller();
        if (be == null || !be.isFormed()) {
            return;
        }
        if (!GameplayConfig.instantRefuelling()) {
            refuse(sender, instantOffMessage());
            return;
        }
        Component blocked = be.refuellingBlockedMessage();
        if (blocked != null) {
            refuse(sender, BwrText.tr("status.bwr.refuelling_menu.refuelling_is_not_possible", "Refuelling is not possible: %s." , blocked));
            return;
        }
        int[] positions = be.corePositions();
        // Reject an invalid request before modifying anything; ignore repeated positions.
        for (int slot : slots) {
            if (!inRange(positions, slot)) return;
        }
        var unique = new java.util.LinkedHashSet<Integer>();
        for (int slot : slots) unique.add(slot);
        int changed = 0;
        for (int slot : unique) {
            boolean occupied = be.core().getCoreLoading().isOccupied(positions[slot]);
            if (loading) {
                if (occupied) continue;
                if (countBundles(sender) == 0) break;
                load(sender, be, positions, slot);
                if (be.core().getCoreLoading().isOccupied(positions[slot])) changed++;
            } else if (occupied) {
                unload(sender, be, positions, slot);
                changed++;
            }
        }
        markSnapshotDirty();
        sender.displayClientMessage(BwrText.tr(
                "status.bwr.refuelling_menu.of_selected_positions",
                "%s%s of %s selected positions.",
                (loading ? BwrText.tr("status.bwr.refuelling_menu.loaded", "Loaded ") : BwrText.tr("status.bwr.refuelling_menu.unloaded", "Unloaded ")),
                changed,
                unique.size()), true);
    }
}
