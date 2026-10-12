package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.containment.ContainmentPenetrationBlock;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationRatings;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional CC face; the controller's room-wide API is added with build item 6. */
public final class ContainmentPenetrationPeripheral implements IPeripheral {
    private final ContainmentPenetrationBlockEntity be;
    public ContainmentPenetrationPeripheral(ContainmentPenetrationBlockEntity be) { this.be = be; }
    @Override public String getType() { return "bwr_containment_penetration"; }
    @Override public boolean equals(@Nullable IPeripheral other) {
        return other instanceof ContainmentPenetrationPeripheral p && p.be == be;
    }
    @Override public Object getTarget() { return be; }
    private ContainmentPenetrationBlock block() { return (ContainmentPenetrationBlock) be.getBlockState().getBlock(); }
    @LuaFunction(mainThread = true) public final boolean setOpen(boolean open) {
        return dev.bwr.mod.signal.DeviceCommands.penetrationOpen(be, open);
    }
    @LuaFunction(mainThread = true) public final void releaseControl() { be.setComputerControlled(false); }
    @LuaFunction(mainThread = true) public final double getPosition() { return be.position(); }
    @LuaFunction(mainThread = true) public final boolean isIsolated() { return be.isIsolated(); }
    @LuaFunction(mainThread = true) public final Map<String, Object> getStatus() {
        var status = new LinkedHashMap<String, Object>();
        status.put("service", block().service().name().toLowerCase(java.util.Locale.ROOT));
        status.put("nameplate", ContainmentPenetrationRatings.nameplate(block().service()));
        status.put("containmentRatingPsig", ContainmentPenetrationRatings.containmentPsig());
        status.put("ruptured", be.getBlockState().getValue(ContainmentPenetrationBlock.RUPTURED));
        status.put("isolationPosition", be.position());
        status.put("demandOpen", be.demandOpen());
        status.put("energyFE", be.energy().getEnergyStored());
        return status;
    }
}
