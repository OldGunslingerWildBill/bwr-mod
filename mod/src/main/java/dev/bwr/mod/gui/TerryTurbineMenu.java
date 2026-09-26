package dev.bwr.mod.gui;

import dev.bwr.mod.eccs.*;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

/** Steam-driven emergency skid panel. Shares the pump's checked command path. */
public final class TerryTurbineMenu extends PumpControlMenu {
    public static final AssemblyPort[] PORTS={AssemblyPort.STEAM_INLET,AssemblyPort.STEAM_EXHAUST,
            AssemblyPort.WATER_SUCTION,AssemblyPort.WATER_DISCHARGE};
    public double steamFlow,inletPressure,exhaustPressure,vesselPressure;
    public final String[] portLocations={"","","",""};
    public final boolean[] portReady=new boolean[4];
    public TerryTurbineMenu(int id,Inventory inv,RegistryFriendlyByteBuf data) { this(id,inv,data.readBlockPos(),data.readBlockPos()); }
    public TerryTurbineMenu(int id,Inventory inv,BlockPos root,BlockPos anchor) { super(BwrMenus.TERRY_TURBINE.get(),id,inv,root,anchor); }
    public static void open(ServerPlayer player,BlockPos root,BlockPos anchor) {
        var be=player.level().getBlockEntity(root);
        if(!(be instanceof EccsPumpBlockEntity) || !(be.getBlockState().getBlock() instanceof TurbineAssemblyBlock))return;
        player.openMenu(new SimpleMenuProvider((id,inv,who)->new TerryTurbineMenu(id,inv,root,anchor),be.getBlockState().getBlock().getName()),
                data->{data.writeBlockPos(root);data.writeBlockPos(anchor);});
    }
    @Override public void sample() {
        super.sample();
        if(!(level().getBlockEntity(pos) instanceof EccsPumpBlockEntity be)
                || !(be.getBlockState().getBlock() instanceof TurbineAssemblyBlock block)) { present=false;return; }
        steamFlow=be.getAssemblySteamDrawKgPerS();inletPressure=be.pump().getSteamInletPressurePsig();
        exhaustPressure=be.pump().getExhaustPressurePsig();vesselPressure=be.pump().getVesselPressurePsig();
        for(int i=0;i<PORTS.length;i++) {
            var p=block.portPosition(pos,be.getBlockState(),PORTS[i]);
            portLocations[i]=block.portFace(be.getBlockState(),PORTS[i]).getSerializedName()+"  "+p.getX()+", "+p.getY()+", "+p.getZ();
            portReady[i]=be.assemblyPortReady(PORTS[i]);
        }
    }
    @Override protected void writeSnapshot(FriendlyByteBuf b) {
        super.writeSnapshot(b);if(!present)return;
        b.writeDouble(steamFlow);b.writeDouble(inletPressure);b.writeDouble(exhaustPressure);b.writeDouble(vesselPressure);
        for(int i=0;i<PORTS.length;i++){b.writeUtf(portLocations[i]);b.writeBoolean(portReady[i]);}
    }
    @Override protected void readSnapshot(FriendlyByteBuf b) {
        super.readSnapshot(b);if(!present)return;
        steamFlow=b.readDouble();inletPressure=b.readDouble();exhaustPressure=b.readDouble();vesselPressure=b.readDouble();
        for(int i=0;i<PORTS.length;i++){portLocations[i]=b.readUtf();portReady[i]=b.readBoolean();}
    }
}
