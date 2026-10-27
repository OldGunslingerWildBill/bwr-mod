package dev.bwr.mod.gui;

import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrMenus;
import dev.bwr.mod.spentfuel.SpentFuelPoolBlockEntity;
import dev.bwr.mod.text.BwrText;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * The spent fuel pool's panel: per-rack heat, the pool's total heat, water level and temperature, the time to boil
 * and to uncover the fuel, and the cooling pump. Read only apart from the cooling pump's switch, which the player
 * presses, and the page of the rack list being looked at; nothing here starts anything by itself.
 *
 * <p>A pool holds up to 2,048 racks, and one line each would be about 74 kB, more than a snapshot may carry
 * ({@code MenuSyncPayload.MAX_BYTES}). So the snapshot carries the pool's numbers, the number of racks and one page of
 * {@value #PAGE_RACKS} rack lines (under 2 kB in all); the screen asks for another page with {@link #CMD_RACK_PAGE}.
 */
public class SpentFuelPoolMenu extends BwrMenu {

    /** a = 1 switches the cooling pump on, 0 off. */
    public static final int CMD_COOLING = 0;

    /** a = index of the first rack to show (rounded down to a page). Changes only what this player's screen lists. */
    public static final int CMD_RACK_PAGE = 1;

    /** Rack lines a snapshot carries. */
    public static final int PAGE_RACKS = 8;

    /** One rack's line on the screen. */
    public record Rack(BlockPos pos, double topM, int assemblies, double decayW, double hottestC, int uncovered,
                       int damaged, int burning) {
    }

    // --- client-visible snapshot -------------------------------------

    public boolean present;
    public boolean formed;
    public String problem = "";
    public int footprint;
    public int depth;
    public double massKg;
    public double capacityKg;
    public double levelM;
    public double temperatureC;
    public double waterAboveFuelM;
    public double highestTopM;
    public int assemblies;
    public int cells;
    public double decayW;
    public double coveredDecayW;
    public double uncoveredDecayW;
    public double evaporationKgPerS;
    public double boilKgPerS;
    public double passiveLossW;
    public double coolingW;
    public double secondsToBoil;
    public double secondsToUncover;
    public boolean coolingEnabled;
    public boolean coolingRunning;
    public int energyFE;
    public double coldKg;
    public double hotKg;
    public double coldC;
    public double hotC;
    public double hazard;
    public int damagedAssemblies;
    public int burningAssemblies;
    /** The page of rack lines: racks {@link #rackFirst} to {@code rackFirst + racks.size() - 1} of {@link #rackCount}. */
    public List<Rack> racks = List.of();
    /** Racks in the pool. */
    public int rackCount;
    /** Index (from 0) of the first rack in {@link #racks}. */
    public int rackFirst;

    /** Server side: the first rack of the page this player looks at. */
    private int pageFirst;

    /** Client constructor. */
    public SpentFuelPoolMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extra) {
        this(containerId, inventory, extra.readBlockPos());
    }

    public SpentFuelPoolMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(BwrMenus.SPENT_FUEL_POOL.get(), containerId, inventory, pos, BwrBlocks.SPENT_FUEL_POOL_CONTROLLER.get());
    }

    public static void open(ServerPlayer player, SpentFuelPoolBlockEntity be) {
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, who) -> new SpentFuelPoolMenu(id, inventory, be.getBlockPos()),
                        Component.translatable("menu.bwr.spent_fuel_pool")),
                be.getBlockPos());
    }

    private SpentFuelPoolBlockEntity pool() {
        return blockEntity(SpentFuelPoolBlockEntity.class);
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf buf) {
        SpentFuelPoolBlockEntity be = pool();
        buf.writeBoolean(be != null);
        if (be == null) {
            return;
        }
        buf.writeBoolean(be.isFormed());
        if (!be.isFormed()) {
            buf.writeUtf(be.problem() == null ? "" : BwrText.english(be.problem()), 256);
            return;
        }
        var v = be.summary();
        buf.writeVarInt(be.layout().footprintCells());
        buf.writeVarInt(be.layout().heightCells());
        for (double d : new double[] {v.massKg(), v.capacityKg(), v.levelM(), v.temperatureC(), v.waterAboveFuelM(),
                v.highestRackTopM(), v.decayW(), v.coveredDecayW(), v.uncoveredDecayW(), v.evaporationKgPerS(),
                v.boilKgPerS(), v.passiveLossW(), v.coolingW(), v.secondsToBoil(), v.secondsToUncover(),
                v.coolerColdKg(), v.coolerHotKg(), v.coolerColdC(), v.coolerHotC(), v.hazard()}) {
            buf.writeDouble(d);
        }
        buf.writeVarInt(v.assemblies());
        buf.writeVarInt(v.cells());
        buf.writeBoolean(v.coolingEnabled());
        buf.writeBoolean(v.coolingRunning());
        buf.writeVarInt(v.energyFE());
        buf.writeVarInt(v.damaged());
        buf.writeVarInt(v.burning());
        int count = v.rackCount();
        int first = pageFirst >= count ? Math.max(0, (count - 1) / PAGE_RACKS * PAGE_RACKS) : pageFirst;
        buf.writeVarInt(count);
        buf.writeVarInt(first);
        var page = be.rackViews(first, PAGE_RACKS);
        buf.writeVarInt(page.size());
        for (var r : page) {
            buf.writeBlockPos(r.pos());
            buf.writeDouble(r.topM());
            buf.writeVarInt(r.assemblies());
            buf.writeDouble(r.decayW());
            buf.writeDouble(r.hottestFuelC());
            buf.writeVarInt(r.uncovered());
            buf.writeVarInt(r.damaged());
            buf.writeVarInt(r.burning());
        }
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf buf) {
        present = buf.readBoolean();
        if (!present) {
            return;
        }
        formed = buf.readBoolean();
        if (!formed) {
            problem = buf.readUtf(256);
            return;
        }
        footprint = buf.readVarInt();
        depth = buf.readVarInt();
        massKg = buf.readDouble();
        capacityKg = buf.readDouble();
        levelM = buf.readDouble();
        temperatureC = buf.readDouble();
        waterAboveFuelM = buf.readDouble();
        highestTopM = buf.readDouble();
        decayW = buf.readDouble();
        coveredDecayW = buf.readDouble();
        uncoveredDecayW = buf.readDouble();
        evaporationKgPerS = buf.readDouble();
        boilKgPerS = buf.readDouble();
        passiveLossW = buf.readDouble();
        coolingW = buf.readDouble();
        secondsToBoil = buf.readDouble();
        secondsToUncover = buf.readDouble();
        coldKg = buf.readDouble();
        hotKg = buf.readDouble();
        coldC = buf.readDouble();
        hotC = buf.readDouble();
        hazard = buf.readDouble();
        assemblies = buf.readVarInt();
        cells = buf.readVarInt();
        coolingEnabled = buf.readBoolean();
        coolingRunning = buf.readBoolean();
        energyFE = buf.readVarInt();
        damagedAssemblies = buf.readVarInt();
        burningAssemblies = buf.readVarInt();
        rackCount = buf.readVarInt();
        rackFirst = buf.readVarInt();
        int n = Math.min(buf.readVarInt(), PAGE_RACKS);
        List<Rack> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Rack(buf.readBlockPos(), buf.readDouble(), buf.readVarInt(), buf.readDouble(), buf.readDouble(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        racks = list;
    }

    @Override
    public void handleCommand(ServerPlayer sender, int command, int a, int b) {
        SpentFuelPoolBlockEntity be = pool();
        if (be != null && command == CMD_COOLING) {
            be.setCoolingEnabled(a != 0);
            markSnapshotDirty();
        } else if (command == CMD_RACK_PAGE) {
            pageFirst = Math.max(0, a) / PAGE_RACKS * PAGE_RACKS;
            markSnapshotDirty();
        }
    }
}
