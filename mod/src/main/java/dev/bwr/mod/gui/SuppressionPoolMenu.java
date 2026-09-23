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
    public static final int CMD_FILL_MODE = 1;
    public boolean sprayMode;
    public double passiveCoolingMW;
    public double capacityKg,sprayHeaderKg,sprayFlow,sprayCondensed;

    // --- client-visible snapshot -------------------------------------

    public boolean present;
    public boolean formed;
    public boolean concrete;
    public double physicalCoolingMW;
    private final BlockPos anchor;
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
        this(containerId, inventory, extra.readBlockPos(),extra.readBlockPos());
    }

    public SuppressionPoolMenu(int containerId, Inventory inventory, BlockPos pos) {
        this(containerId,inventory,pos,pos);
    }
    public SuppressionPoolMenu(int containerId, Inventory inventory, BlockPos pos,BlockPos anchor) {
        super(BwrMenus.SUPPRESSION_POOL.get(), containerId, inventory, pos,
                BwrBlocks.SUPPRESSION_POOL_CONTROLLER.get());
        this.anchor=anchor;
    }

    public static void open(ServerPlayer player, SuppressionPoolBlockEntity be) {
        open(player,be,be.getBlockPos());
    }
    public static void open(ServerPlayer player, SuppressionPoolBlockEntity be,BlockPos anchor) {
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, who) -> new SuppressionPoolMenu(id, inventory, be.getBlockPos(),anchor),
                        Component.translatable("menu.bwr.suppression_pool")),
                b->{b.writeBlockPos(be.getBlockPos());b.writeBlockPos(anchor);});
    }
    @Override public boolean stillValid(net.minecraft.world.entity.player.Player who) {
        if(!level().isLoaded(pos)||!level().isLoaded(anchor)||who.distanceToSqr(anchor.getX()+.5,anchor.getY()+.5,anchor.getZ()+.5)>64)return false;
        if(anchor.equals(pos))return super.stillValid(who);
        return level().getBlockEntity(anchor) instanceof dev.bwr.mod.suppression.SuppressionPoolPortBlockEntity port&&port.owner()!=null&&port.owner().getBlockPos().equals(pos);
    }

    private SuppressionPoolBlockEntity poolBlock() {
        return blockEntity(SuppressionPoolBlockEntity.class);
    }

    /** RHR duty actually being delivered in MW, for the readout. */
    public double rhrDutyMW() {
        if(concrete)return physicalCoolingMW;
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
        buf.writeDouble(pool.getUncondensedSteamKgPerS()+be.getBypassedSteamKgPerS());
        buf.writeDouble(pool.getMassKg());
        buf.writeDouble(be.getRhrDuty());
        buf.writeDouble(be.getRhrCapacityMW());
        buf.writeBoolean(pool.isBoiling());
        buf.writeVarInt(be.waterBlocks());
        buf.writeVarInt(be.dischargingValveCount());
        buf.writeBoolean(be.isConcreteBasin());buf.writeDouble(be.physicalCoolingMW());
        buf.writeBoolean(pool.isSprayMode());buf.writeDouble(pool.getDesignMassKg());buf.writeDouble(pool.getSprayWaterKg());
        buf.writeDouble(pool.getSprayKgPerS());buf.writeDouble(pool.getSprayCondensedKgPerS());
        buf.writeDouble(be.passiveCoolingMW());
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
        concrete=buf.readBoolean();physicalCoolingMW=buf.readDouble();
        sprayMode=buf.readBoolean();capacityKg=buf.readDouble();sprayHeaderKg=buf.readDouble();
        sprayFlow=buf.readDouble();sprayCondensed=buf.readDouble();
        passiveCoolingMW=buf.readDouble();
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        SuppressionPoolBlockEntity be = poolBlock();
        if (be == null) {
            return;
        }
        if(command==CMD_FILL_MODE&&(a==0||a==1)&&be.isConcreteBasin()) {
            be.setSprayMode(a==1);markSnapshotDirty();
        }
        if (command == CMD_SET_RHR_DUTY && !be.isConcreteBasin()) {
            be.setRhrDuty(fromPerMille(a));
            markSnapshotDirty();
        }
    }
}
