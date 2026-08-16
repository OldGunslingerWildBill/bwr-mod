package dev.bwr.mod.gui;

import dev.bwr.core.pool.SuppressionPool;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrMenus;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

/**
 * Suppression pool panel — {@code SPEC.md} section 12.
 *
 * <p>Temperature, subcooling, condensation effectiveness and the heat capacity
 * that is left, plus RHR as a commanded duty slider.
 *
 * <p>There is no heat capacity temperature limit on this screen, because a heat
 * capacity temperature limit is an administrative number a real plant's
 * technical specifications pick, not a property of water. What the pool
 * publishes is how much heat it can still absorb before it saturates, in
 * megajoules, and how effectively it is condensing right now. Deciding at what
 * point continued discharge is unacceptable, and doing something about it, is
 * the player's — which is why RHR is a slider they drag and not something that
 * comes on by itself.
 */
public class SuppressionPoolMenu extends BwrMenu {

    /** a = RHR duty in per mille. */
    public static final int CMD_SET_RHR_DUTY = 0;

    // --- client-visible snapshot -------------------------------------

    public boolean present;
    public boolean formed;
    public double temperatureC;
    public double saturationTemperatureC;
    public double subcoolingC;
    public double condensationEffectiveness;
    public double remainingHeatCapacityMJ;
    public double cumulativeHeatInputMJ;
    public double cumulativeRhrRemovedMJ;
    public double uncondensedSteamKgPerS;
    public double massKg;
    public double rhrDuty;
    public double rhrCapacityMW;
    public boolean boiling;
    public int waterBlocks;
    public int dischargingValves;

    /** Client constructor. */
    public SuppressionPoolMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public SuppressionPoolMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.SUPPRESSION_POOL.get(), containerId, inventory, pos,
                BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get());
    }

    public static void open(ServerPlayer player, SuppressionPoolBlockEntity be) {
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, who) -> new SuppressionPoolMenu(id, inventory, be.getBlockPos()),
                        Component.translatable("menu.bwr.suppression_pool")),
                be.getBlockPos());
    }

    private SuppressionPoolBlockEntity poolBlock() {
        return blockEntity(SuppressionPoolBlockEntity.class);
    }

    /** RHR duty actually being delivered in MW, for the readout. */
    public double rhrDutyMW() {
        return rhrDuty * rhrCapacityMW;
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        SuppressionPoolBlockEntity be = poolBlock();
        buf.writeBoolean(be != null);
        if (be == null) {
            return;
        }
        SuppressionPool pool = be.pool();
        buf.writeBoolean(be.isFormed());
        buf.writeDouble(pool.getTemperatureC());
        buf.writeDouble(pool.getSaturationTemperatureC());
        buf.writeDouble(pool.getSubcoolingC());
        buf.writeDouble(pool.condensationEffectiveness());
        buf.writeDouble(pool.getRemainingHeatCapacityMJ());
        buf.writeDouble(pool.getCumulativeHeatInputMJ());
        buf.writeDouble(pool.getCumulativeRhrRemovedMJ());
        buf.writeDouble(pool.getUncondensedSteamKgPerS());
        buf.writeDouble(pool.getMassKg());
        buf.writeDouble(be.getRhrDuty());
        buf.writeDouble(be.getRhrCapacityMW());
        buf.writeBoolean(pool.isBoiling());
        buf.writeVarInt(be.waterBlocks());
        buf.writeVarInt(be.dischargingValveCount());
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
        present = buf.readBoolean();
        if (!present) {
            return;
        }
        formed = buf.readBoolean();
        temperatureC = buf.readDouble();
        saturationTemperatureC = buf.readDouble();
        subcoolingC = buf.readDouble();
        condensationEffectiveness = buf.readDouble();
        remainingHeatCapacityMJ = buf.readDouble();
        cumulativeHeatInputMJ = buf.readDouble();
        cumulativeRhrRemovedMJ = buf.readDouble();
        uncondensedSteamKgPerS = buf.readDouble();
        massKg = buf.readDouble();
        rhrDuty = buf.readDouble();
        rhrCapacityMW = buf.readDouble();
        boiling = buf.readBoolean();
        waterBlocks = buf.readVarInt();
        dischargingValves = buf.readVarInt();
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        SuppressionPoolBlockEntity be = poolBlock();
        if (be == null) {
            return;
        }
        if (command == CMD_SET_RHR_DUTY) {
            be.setRhrDuty(fromPerMille(a));
            markSnapshotDirty();
        }
    }
}
