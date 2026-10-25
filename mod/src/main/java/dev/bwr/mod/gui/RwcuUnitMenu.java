package dev.bwr.mod.gui;

import dev.bwr.mod.registry.BwrMenus;
import dev.bwr.mod.rwcu.RwcuUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/** Read-only status of one passive RWCU skid unit (regenerative exchanger, cooler, demineraliser). */
public class RwcuUnitMenu extends BwrMenu {
    public String headline = "";
    public int headlineStatus;
    public List<RwcuUnit.Line> lines = List.of();

    public RwcuUnitMenu(int id, Inventory inv, RegistryFriendlyByteBuf b) { this(id, inv, b.readBlockPos()); }
    public RwcuUnitMenu(int id, Inventory inv, BlockPos p) {
        super(BwrMenus.RWCU_UNIT.get(), id, inv, p, inv.player.level().getBlockState(p).getBlock());
    }

    public static void open(ServerPlayer p, BlockPos pos) {
        var state = p.level().getBlockState(pos);
        p.openMenu(new SimpleMenuProvider((id, inv, who) -> new RwcuUnitMenu(id, inv, pos), state.getBlock().getName()), b -> b.writeBlockPos(pos));
    }

    @Override protected void writeSnapshot(FriendlyByteBuf b) {
        var be = blockEntity(net.minecraft.world.level.block.entity.BlockEntity.class);
        var readout = be instanceof RwcuUnit unit ? unit.readout() : null;
        b.writeBoolean(readout != null);
        if (readout == null) return;
        b.writeUtf(readout.headline());
        b.writeByte(readout.headlineStatus());
        b.writeVarInt(readout.lines().size());
        for (var l : readout.lines()) {
            b.writeUtf(l.label());
            b.writeUtf(l.value());
            b.writeUtf(l.unit());
            b.writeByte(l.status());
        }
    }

    @Override protected void readSnapshot(FriendlyByteBuf b) {
        if (!b.readBoolean()) return;
        headline = b.readUtf();
        headlineStatus = b.readByte();
        int n = b.readVarInt();
        List<RwcuUnit.Line> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new RwcuUnit.Line(b.readUtf(), b.readUtf(), b.readUtf(), b.readByte()));
        lines = out;
    }

    @Override public void handleCommand(ServerPlayer p, int command, int a, int b) {}
}
