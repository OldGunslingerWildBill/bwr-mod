package dev.bwr.mod.gui;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrMenus;
import dev.bwr.mod.spentfuel.NewFuelElevatorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The new fuel elevator's screen: whether it is on a formed pool, what it is doing, how many assemblies wait, how many rack cells are
 * free, the water over an assembly at the transfer height against the 1.5 m it needs, and why the last thing was refused. Three
 * buttons, each a player's command: RAISE the coolest assembly in the pool to the hatch, TRY AGAIN with a waiting assembly that was
 * blocked, and TAKE the assembly that waits at the hatch.
 */
public class NewFuelElevatorMenu extends BwrMenu {

    public static final int CMD_RAISE = 0;
    public static final int CMD_RETRY = 1;
    public static final int CMD_TAKE = 2;

    public boolean present;
    public boolean linked;
    public String problem = "";
    public String state = "";
    public String leg = "";
    public double progress;
    public double secondsLeft;
    public int queued;
    public boolean landing;
    public double landingKw;
    public double inFlightKw;
    public int freeCells;
    public int cells;
    public double waterOverM;
    public double minCoverM;
    public String lastRefusal = "";
    public double coolestKw = -1.0;
    public double levelM;

    /** Client constructor. */
    public NewFuelElevatorMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public NewFuelElevatorMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.NEW_FUEL_ELEVATOR.get(), containerId, inventory, pos, BwrBlocks.NEW_FUEL_ELEVATOR.get());
    }

    public static void open(ServerPlayer player, NewFuelElevatorBlockEntity be) {
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, who) -> new NewFuelElevatorMenu(id, inventory, be.getBlockPos()),
                        Component.translatable("menu.bwr.new_fuel_elevator")),
                be.getBlockPos());
    }

    private NewFuelElevatorBlockEntity elevator() {
        return blockEntity(NewFuelElevatorBlockEntity.class);
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        NewFuelElevatorBlockEntity be = elevator();
        buf.writeBoolean(be != null);
        if (be == null) {
            return;
        }
        var s = be.status();
        buf.writeBoolean(s.linked());
        buf.writeUtf(s.problem(), 256);
        buf.writeUtf(s.state(), 32);
        buf.writeUtf(s.leg(), 32);
        buf.writeDouble(s.progress());
        buf.writeDouble(s.secondsLeft());
        buf.writeVarInt(s.queued());
        buf.writeBoolean(s.landing());
        buf.writeDouble(s.landingDecayKw());
        buf.writeDouble(s.inFlightDecayKw());
        buf.writeVarInt(s.freeCells());
        buf.writeVarInt(s.cells());
        buf.writeDouble(s.waterOverTransferM());
        buf.writeDouble(s.minCoverM());
        buf.writeUtf(s.lastRefusal(), 256);
        buf.writeDouble(s.coolestKw());
        buf.writeDouble(s.levelM());
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
        present = buf.readBoolean();
        if (!present) {
            return;
        }
        linked = buf.readBoolean();
        problem = buf.readUtf(256);
        state = buf.readUtf(32);
        leg = buf.readUtf(32);
        progress = buf.readDouble();
        secondsLeft = buf.readDouble();
        queued = buf.readVarInt();
        landing = buf.readBoolean();
        landingKw = buf.readDouble();
        inFlightKw = buf.readDouble();
        freeCells = buf.readVarInt();
        cells = buf.readVarInt();
        waterOverM = buf.readDouble();
        minCoverM = buf.readDouble();
        lastRefusal = buf.readUtf(256);
        coolestKw = buf.readDouble();
        levelM = buf.readDouble();
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        NewFuelElevatorBlockEntity be = elevator();
        if (be == null) {
            return;
        }
        switch (command) {
            case CMD_RAISE -> {
                var why = be.raise(null);
                if (why != null) {
                    sender.displayClientMessage(why.text(), true);
                }
            }
            case CMD_RETRY -> {
                var why = be.retry();
                if (why != null) {
                    sender.displayClientMessage(why.text(), true);
                }
            }
            case CMD_TAKE -> {
                ItemStack landed = be.takeLanding();
                if (!landed.isEmpty() && !sender.getInventory().add(landed)) {
                    Containers.dropItemStack(sender.level(), sender.getX(), sender.getY(), sender.getZ(), landed);
                }
            }
            default -> {
            }
        }
        markSnapshotDirty();
    }
}
