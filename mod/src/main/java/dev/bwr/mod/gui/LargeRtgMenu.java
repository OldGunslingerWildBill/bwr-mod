package dev.bwr.mod.gui;

import dev.bwr.core.isotope.LargeRtgStore;
import dev.bwr.mod.isotope.LargeRtgBlockEntity;
import dev.bwr.mod.isotope.RtgFuels;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Large RTG's screen: the stored mass by isotope against the 1,000 kg cap, thermal W, electric W and FE/t, the
 * dose at 1 m (through the housing, and bare for comparison), the energy buffer, the heat-only switch and a button that
 * loads every fuel item in the player's inventory (4,000 ingots is a lot of right-clicks). It has no item slots: material goes
 * in by hand on the block, by that button, or by hopper and pipe. The numbers are the block entity's own output, sent four times
 * a second; nothing here decides anything.
 */
public class LargeRtgMenu extends BwrMenu {

    public static final int CMD_HEAT_ONLY = 1;
    public static final int CMD_LOAD_ALL = 2;
    /** Isotope rows the screen has room for. */
    public static final int MAX_KINDS = 8;

    public record Kind(String label, double grams, double thermalWatts) {
    }

    public boolean present;
    public boolean heatOnly;
    public double massGrams;
    public long items;
    public double thermalWatts;
    public double electricWatts;
    public double fePerTick;
    public double doseMsvPerH;
    public double bareDoseMsvPerH;
    public double shortestHalfLifeYears;
    public double bufferFe;
    public List<Kind> kinds = List.of();

    /** Client constructor. */
    public LargeRtgMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public LargeRtgMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.LARGE_RTG.get(), containerId, inventory, pos, BwrBlocks.LARGE_RTG.get());
    }

    public static void open(ServerPlayer player, LargeRtgBlockEntity be) {
        player.openMenu(new SimpleMenuProvider((id, inventory, who) -> new LargeRtgMenu(id, inventory, be.getBlockPos()),
                Component.translatable("menu.bwr.large_rtg")), be.getBlockPos());
    }

    private LargeRtgBlockEntity rtg() {
        return blockEntity(LargeRtgBlockEntity.class);
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        LargeRtgBlockEntity be = rtg();
        buf.writeBoolean(be != null);
        if (be == null) {
            return;
        }
        LargeRtgStore.Output r = be.output();
        buf.writeBoolean(be.heatOnly());
        buf.writeDouble(r.massGrams());
        buf.writeVarLong(be.store().itemCount());
        buf.writeDouble(r.thermalWatts());
        buf.writeDouble(r.electricWatts());
        buf.writeDouble(r.fePerTick());
        buf.writeDouble(r.doseAtOneMetreMsvPerH());
        buf.writeDouble(r.bareDoseAtOneMetreMsvPerH());
        buf.writeDouble(r.shortestHalfLifeYears());
        buf.writeDouble(be.bufferFe());
        // Mass and heat by isotope: ingots, blocks and capsules of one isotope are one row.
        double now = be.getLevel() == null ? 0.0 : be.getLevel().getGameTime() / 20.0;
        Map<String, double[]> byLabel = new LinkedHashMap<>();
        for (LargeRtgStore.Bucket b : be.store().buckets()) {
            String label = b.fuel().label().isEmpty() ? b.key() : b.fuel().label();
            double[] acc = byLabel.computeIfAbsent(label, k -> new double[2]);
            acc[0] += b.grams();
            acc[1] += b.count() * b.fuel().gramsPerItem() * b.fuel().specificPowerWPerG() * b.decay(now);
        }
        buf.writeVarInt(Math.min(MAX_KINDS, byLabel.size()));
        int n = 0;
        for (var e : byLabel.entrySet()) {
            if (n++ >= MAX_KINDS) {
                break;
            }
            buf.writeUtf(e.getKey(), 48);
            buf.writeDouble(e.getValue()[0]);
            buf.writeDouble(e.getValue()[1]);
        }
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
        present = buf.readBoolean();
        if (!present) {
            return;
        }
        heatOnly = buf.readBoolean();
        massGrams = buf.readDouble();
        items = buf.readVarLong();
        thermalWatts = buf.readDouble();
        electricWatts = buf.readDouble();
        fePerTick = buf.readDouble();
        doseMsvPerH = buf.readDouble();
        bareDoseMsvPerH = buf.readDouble();
        shortestHalfLifeYears = buf.readDouble();
        bufferFe = buf.readDouble();
        int count = buf.readVarInt();
        List<Kind> read = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            read.add(new Kind(buf.readUtf(48), buf.readDouble(), buf.readDouble()));
        }
        kinds = read;
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        LargeRtgBlockEntity be = rtg();
        if (be == null || !dev.bwr.mod.world.AssemblyAccess.mayOperate(sender)) {
            return;
        }
        switch (command) {
            case CMD_HEAT_ONLY -> be.setHeatOnly(a != 0);
            case CMD_LOAD_ALL -> loadAll(sender, be);
            default -> {
            }
        }
        markSnapshotDirty();
    }

    /** Puts every fuel item in the player's inventory into the unit, as much as the 1,000 kg cap allows. */
    private static void loadAll(ServerPlayer player, LargeRtgBlockEntity be) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !RtgFuels.isFuel(stack)) {
                continue;
            }
            int taken = be.loadFrom(stack.getCount(), stack);
            if (taken > 0 && !player.isCreative()) {
                stack.shrink(taken);
            }
        }
        inv.setChanged();
    }
}
