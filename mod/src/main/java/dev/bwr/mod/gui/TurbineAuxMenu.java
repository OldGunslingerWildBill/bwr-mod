package dev.bwr.mod.gui;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.power.TurbineAuxBlock;
import dev.bwr.mod.power.TurbineAuxBlockEntity;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

/** Readings and the pump switches of a turbine auxiliary skid: the Main Oil Tank, the Shaft-Driven Oil Pump or the Generator Cooling Skid. */
public class TurbineAuxMenu extends BwrMenu {
    public static final int CMD_AC_PUMP = 0, CMD_DC_PUMP = 1, CMD_STATOR_PUMPS = 2;

    public boolean present, attached, acOn, dcOn, acRun, dcRun, statorOn, statorRun;
    public int kind;
    public double rpm, psig, film, oilC, oilFraction, coolerKw, coldKg, hotKg, coldC, hotC, acFe, dcFe;
    public double statorFlow, h2Psig, h2Kg, coolantC, windingC, bearingC, electricMW, shaftFlow;
    /** The oil filter housing against the tank (TURBINE-AUX.md): fitted, its bypass open, the cartridge life left and the pressure drop across it. */
    public boolean filterHousing, filterBypass;
    public double filterLife, filterDp;
    public String status = "Waiting for server";

    public TurbineAuxMenu(int id, Inventory inv, RegistryFriendlyByteBuf b) { this(id, inv, b.readBlockPos()); }

    public TurbineAuxMenu(int id, Inventory inv, BlockPos pos) {
        super(BwrMenus.TURBINE_AUX.get(), id, inv, pos, inv.player.level().getBlockState(pos).getBlock());
    }

    public static void open(ServerPlayer p, BlockPos pos) {
        if (!(p.level().getBlockEntity(pos) instanceof TurbineAuxBlockEntity be)) return;
        p.openMenu(new SimpleMenuProvider((id, inv, who) -> new TurbineAuxMenu(id, inv, pos), be.getBlockState().getBlock().getName()), b -> b.writeBlockPos(pos));
    }

    @Override protected void writeSnapshot(FriendlyByteBuf b) {
        var be = blockEntity(TurbineAuxBlockEntity.class);
        b.writeBoolean(be != null);
        if (be == null) return;
        var m = be.module();
        var aux = m == null ? null : m.aux();
        b.writeInt(be.kind().ordinal());
        b.writeBoolean(m != null);
        b.writeBoolean(be.acPumpOn());
        b.writeBoolean(be.dcPumpOn());
        b.writeBoolean(be.acRunning());
        b.writeBoolean(be.dcRunning());
        b.writeBoolean(be.statorPumpsOn());
        b.writeBoolean(be.statorRunning());
        double rpm = m == null ? 0 : m.rpm;
        for (double v : new double[] {rpm, be.psig(), be.film(), be.oilC(), be.oilFraction(), be.coolerKw(), be.coldKg(), be.hotKg(), be.coldC(), be.hotC(),
                be.acFe(), be.dcFe(), be.statorFlow(), be.h2Psig(), be.h2Kg(), be.coolantC(), m == null ? 0 : m.windingC, m == null ? 0 : m.bearingC,
                m == null ? 0 : m.electricMW, aux != null && aux.shaftPump ? TurbineAux.shaftPumpFlow(rpm) : 0})
            b.writeDouble(v);
        var filter = be.oilFilter();
        b.writeBoolean(filter != null);
        b.writeBoolean(filter != null && filter.filterBypass());
        b.writeDouble(filter == null ? 0 : filter.filterLife());
        b.writeDouble(filter == null ? 0 : filter.filterDpPsi());
        b.writeUtf(be.kind() == TurbineAuxBlock.Kind.SHAFT_PUMP ? (m == null ? "Not on a generator shaft end" : "On the generator shaft end")
                        + (be.suctionPiped() ? "; suction piped to a tank" : "") + (be.dischargePiped() ? "; discharge piped to a bearing header" : "")
                : be.kind() == TurbineAuxBlock.Kind.LUBE_OIL ? (m == null ? (be.dischargePiped() ? "Piped to a bearing header (not bolted on a train)" : "Not against a turbine section and not piped")
                        : be.dischargePiped() ? "Attached; its discharge is piped to a bearing header" : "Attached")
                : m == null ? (be.kind() == TurbineAuxBlock.Kind.GEN_COOLING ? "Not against a generator" : "Not against a turbine section") : "Attached");
    }

    @Override protected void readSnapshot(FriendlyByteBuf b) {
        present = b.readBoolean();
        if (!present) return;
        kind = b.readInt();
        attached = b.readBoolean();
        acOn = b.readBoolean(); dcOn = b.readBoolean(); acRun = b.readBoolean(); dcRun = b.readBoolean(); statorOn = b.readBoolean(); statorRun = b.readBoolean();
        rpm = b.readDouble(); psig = b.readDouble(); film = b.readDouble(); oilC = b.readDouble(); oilFraction = b.readDouble(); coolerKw = b.readDouble();
        coldKg = b.readDouble(); hotKg = b.readDouble(); coldC = b.readDouble(); hotC = b.readDouble(); acFe = b.readDouble(); dcFe = b.readDouble();
        statorFlow = b.readDouble(); h2Psig = b.readDouble(); h2Kg = b.readDouble(); coolantC = b.readDouble(); windingC = b.readDouble(); bearingC = b.readDouble();
        electricMW = b.readDouble(); shaftFlow = b.readDouble();
        filterHousing = b.readBoolean(); filterBypass = b.readBoolean(); filterLife = b.readDouble(); filterDp = b.readDouble();
        status = b.readUtf();
    }

    @Override public void handleCommand(ServerPlayer who, int command, int a, int unused) {
        var be = blockEntity(TurbineAuxBlockEntity.class);
        if (!stillValid(who) || be == null) return;
        switch (command) {
            case CMD_AC_PUMP -> be.setAcPump(a != 0);
            case CMD_DC_PUMP -> be.setDcPump(a != 0);
            case CMD_STATOR_PUMPS -> be.setStatorPumps(a != 0);
            default -> { }
        }
        markSnapshotDirty();
    }
}
