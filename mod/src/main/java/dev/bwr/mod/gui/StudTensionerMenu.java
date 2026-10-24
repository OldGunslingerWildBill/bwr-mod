package dev.bwr.mod.gui;

import dev.bwr.core.refuelling.CraneMotion;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.StudTensionerBlockEntity;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrMenus;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

/**
 * The stud tensioner's screen: the studs at load, what the tool is doing and the pack's power, and three buttons a player presses:
 * detension, retension, stop. Nothing here starts a pass by itself.
 */
public class StudTensionerMenu extends BwrMenu {

    public static final int CMD_DETENSION = 0;
    public static final int CMD_RETENSION = 1;
    public static final int CMD_STOP = 2;

    public boolean present;
    public boolean reactorFound;
    public boolean headOff;
    public int studTotal;
    public int studsOff;
    /** 0 idle, 1 loosening, 2 tightening. */
    public int mode;
    public double studProgress;
    public int energyFe;
    public int capacityFe;
    public String message = "";
    public int secondsLeft;
    public long feLeft;
    public double psig;

    /** Client constructor. */
    public StudTensionerMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public StudTensionerMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.STUD_TENSIONER.get(), containerId, inventory, pos, BwrBlocks.STUD_TENSIONER.get());
    }

    public static void open(ServerPlayer player, StudTensionerBlockEntity be) {
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, who) -> new StudTensionerMenu(id, inventory, be.getBlockPos()),
                        Component.translatable("menu.bwr.stud_tensioner")),
                be.getBlockPos());
    }

    private StudTensionerBlockEntity tensioner() {
        return blockEntity(StudTensionerBlockEntity.class);
    }

    @Override
    protected int syncIntervalTicks() {
        return 10;
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        StudTensionerBlockEntity be = tensioner();
        buf.writeBoolean(be != null);
        if (be == null) {
            return;
        }
        ReactorControllerBlockEntity r = be.findReactor();
        buf.writeBoolean(r != null);
        buf.writeBoolean(r != null && r.vesselState() == VesselState.REFUELING);
        int total = r == null ? 0 : r.studTotal();
        int off = r == null ? 0 : r.studsOff();
        buf.writeVarInt(total);
        buf.writeVarInt(off);
        buf.writeVarInt(be.mode().ordinal());
        buf.writeDouble(be.studProgress());
        buf.writeVarInt(be.energyFe());
        buf.writeVarInt(StudTensionerBlockEntity.FE_CAPACITY);
        buf.writeUtf(be.message() == null ? "" : BwrText.english(be.message()), 300);
        boolean loosen = be.mode() != StudTensionerBlockEntity.Mode.TIGHTEN;
        int left = be.mode() == StudTensionerBlockEntity.Mode.IDLE ? 0 : loosen ? total - off : off;
        buf.writeVarInt(CraneMotion.passTicks(left, loosen) / 20);
        buf.writeLong(CraneMotion.passFe(left, loosen));
        buf.writeDouble(r == null || r.core() == null ? 0.0 : r.core().getPressurePsig());
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
        present = buf.readBoolean();
        if (!present) {
            return;
        }
        reactorFound = buf.readBoolean();
        headOff = buf.readBoolean();
        studTotal = buf.readVarInt();
        studsOff = buf.readVarInt();
        mode = buf.readVarInt();
        studProgress = buf.readDouble();
        energyFe = buf.readVarInt();
        capacityFe = buf.readVarInt();
        message = buf.readUtf(300);
        secondsLeft = buf.readVarInt();
        feLeft = buf.readLong();
        psig = buf.readDouble();
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        StudTensionerBlockEntity be = tensioner();
        if (be == null) {
            return;
        }
        switch (command) {
            case CMD_DETENSION, CMD_RETENSION -> {
                Component why = be.start(command == CMD_DETENSION);
                if (why != null) {
                    refuse(sender, why);
                }
            }
            case CMD_STOP -> be.stop();
            default -> {
                return;
            }
        }
        markSnapshotDirty();
    }
}
