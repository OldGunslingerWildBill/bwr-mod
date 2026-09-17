package dev.bwr.mod.gui;

import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

/**
 * Recirculation pump control — {@code SPEC.md} section 4.2, which specifies
 * this screen almost line by line.
 *
 * <ul>
 *   <li>a continuously variable 0-100% slider, because a real recirculation
 *       pump has no notches;</li>
 *   <li>a CC control toggle in the same screen, so it is visually obvious who
 *       is in charge;</li>
 *   <li>both paths writing one authoritative
 *       {@link RecirculationPumpBlockEntity#setTargetSpeedFraction target
 *       speed}, so there is no second setpoint to disagree with the first;</li>
 *   <li>the slider greyed out while a computer holds control, which is what
 *       stops the confusing case where a player drags a slider that is being
 *       overwritten twenty times a second;</li>
 *   <li>the slider setting a <i>target</i> that ramps through the same
 *       first-order lag Lua's commands ramp through, so the pump behaves
 *       identically whichever path commanded it;</li>
 *   <li>and the mismatch surfaced: "commanded 80% / limited to 50% (power)".</li>
 * </ul>
 *
 * <p>The toggle is an ownership switch, not an interlock. It decides which of
 * the two writers may set the target; it does not decide anything about the
 * plant, and there is no state in which the pump refuses a speed it has the
 * power to reach.
 */
public class RecirculationPumpMenu extends BwrMenu {

    /** a = commanded speed in per mille. Ignored while a computer holds control. */
    public static final int CMD_SET_TARGET = 0;
    /** a != 0 hands control to CC:Tweaked; a == 0 takes it back. */
    public static final int CMD_SET_COMPUTER_CONTROL = 1;

    // --- client-visible snapshot -------------------------------------

    public boolean present;
    public double targetSpeedFraction;
    public double actualSpeedFraction;
    public double maxAchievableSpeedFraction;
    public boolean computerControlled;
    public boolean powerLimited;
    public boolean attachedToReactor;
    public double energyStoredFe;
    public double energyCapacityFe;

    /** Client constructor. */
    public RecirculationPumpMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public RecirculationPumpMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.RECIRCULATION_PUMP.get(), containerId, inventory, pos,
                inventory.player.level().getBlockState(pos).is(BwrBlocks.RIP_PUMP.get())
                        ? BwrBlocks.RIP_PUMP.get() : BwrBlocks.RECIRCULATION_PUMP.get());
    }

    public static void open(ServerPlayer player, RecirculationPumpBlockEntity be) {
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, who) -> new RecirculationPumpMenu(id, inventory, be.getBlockPos()),
                        Component.translatable(be.getBlockState().is(BwrBlocks.RIP_PUMP.get())
                                ? "block.bwr.rip_pump" : "menu.bwr.recirculation_pump")),
                be.getBlockPos());
    }

    private RecirculationPumpBlockEntity pump() {
        return blockEntity(RecirculationPumpBlockEntity.class);
    }

    /** Full power draw at 100%, for the energy readout. */
    public static double maxDrawFePerTick() {
        return RecirculationPumpBlockEntity.MAX_FE_PER_TICK;
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        RecirculationPumpBlockEntity be = pump();
        buf.writeBoolean(be != null);
        if (be == null) {
            return;
        }
        buf.writeDouble(be.getTargetSpeedFraction());
        buf.writeDouble(be.getActualSpeedFraction());
        buf.writeDouble(be.maxAchievableSpeedFraction());
        buf.writeBoolean(be.isComputerControlled());
        buf.writeBoolean(be.isPowerLimited());
        buf.writeBoolean(be.getControllerPos() != null);
        buf.writeDouble(be.getEnergyStoredFe());
        buf.writeDouble(be.getEnergyCapacityFe());
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
        present = buf.readBoolean();
        if (!present) {
            return;
        }
        targetSpeedFraction = buf.readDouble();
        actualSpeedFraction = buf.readDouble();
        maxAchievableSpeedFraction = buf.readDouble();
        computerControlled = buf.readBoolean();
        powerLimited = buf.readBoolean();
        attachedToReactor = buf.readBoolean();
        energyStoredFe = buf.readDouble();
        energyCapacityFe = buf.readDouble();
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        RecirculationPumpBlockEntity be = pump();
        if (be == null) {
            return;
        }
        switch (command) {
            case CMD_SET_TARGET -> {
                // One writer at a time. The screen has already greyed the slider
                // out, so a command arriving in this state is a stale drag; it
                // is dropped rather than fighting Lua for the field.
                if (!be.isComputerControlled()) {
                    be.setTargetSpeedFraction(fromPerMille(a));
                }
            }
            case CMD_SET_COMPUTER_CONTROL -> be.setComputerControlled(a != 0);
            default -> {
                return;
            }
        }
        markSnapshotDirty();
    }
}
