package dev.bwr.mod.power;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.eccs.EccsPower;
import dev.bwr.mod.piping.OilLineNetwork;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrItems;
import dev.bwr.mod.world.LoadedWorld;
import dev.bwr.mod.water.ThermalWater;
import dev.bwr.mod.water.ThermalWaterTank;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The state of one turbine auxiliary skid: the Main Oil Tank (oil, AC and DC pumps, oil cooler), the Shaft-Driven Main Oil Pump
 * (nothing to keep) or the Generator Cooling Skid (stator water pumps, hydrogen, coolers). See TURBINE-AUX.md.
 *
 * <p>No ticker. A skid does nothing on its own: the shaft train it is attached to steps it once a tick, from the train's own ticker
 * ({@link TurbineAuxTrain}). A skid with no train ticking, or none attached, is not simulated and draws nothing. Its pumps run only
 * while the player has switched them on.
 *
 * <p>The fourth kind, the Oil Filter Housing, is not on the train at all: it stands against a Main Oil Tank, holds one Oil Filter
 * Cartridge, and is stepped by that tank's own oil step, so it has no ticker either (see {@link #filterPass}).
 */
public final class TurbineAuxBlockEntity extends BlockEntity {
    public static final int AC_CAPACITY_FE = 100_000, DC_CAPACITY_FE = 1_000_000, WATER_CAPACITY = 5_000;
    public static final int AC_PUMP_FE_PER_TICK = EccsPower.fePerTickFromWatts(TurbineAux.AC_PUMP_W);
    public static final int DC_PUMP_FE_PER_TICK = EccsPower.fePerTickFromWatts(TurbineAux.DC_PUMP_W);
    public static final int STATOR_PUMPS_FE_PER_TICK = EccsPower.fePerTickFromWatts(TurbineAux.STATOR_PUMPS_W);
    public static final double OIL_PER_DRUM_L = 1000;
    private static final double DT = 0.05;

    private BlockPos host;
    private boolean acOn, dcOn, statorOn;
    private int acFe, dcFe;
    private double oilL, oilC = TurbineAux.AMBIENT_C, h2Kg, coolantC = TurbineAux.AMBIENT_C, statorFlow;
    private final ThermalWaterTank cold = new ThermalWaterTank(WATER_CAPACITY, 13), hot = new ThermalWaterTank(WATER_CAPACITY, 13);
    private long stepTick = Long.MIN_VALUE;
    /** What the last step found, for the skid's own screen and signals. */
    private double psig, film, coolerKw, pumpFlow;
    private boolean acRan, dcRan, statorRan;

    /**
     * The Oil Filter Housing's two cartridge slots, A (0) and B (1) (null on the other kinds), how full each cartridge is (0 to 1), which side
     * is in service (the transfer valve: false A, true B) and the last redstone reading of the transfer valve. Only the side in service filters
     * and loads; the other can be opened and changed while the plant runs.
     */
    private final CartridgeSlots slot;
    private final double[] filterLoads = new double[2];
    private final int[] stampedMilles = {-1, -1};
    private boolean sideB, transferPowered;

    /** The Lube Oil Conditioner (null stuff on the other kinds): the start switch and what the last step found. */
    private boolean condOn, condRan;

    public TurbineAuxBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.TURBINE_AUX.get(), pos, state);
        slot = state.getBlock() instanceof TurbineAuxBlock b && b.kind() == TurbineAuxBlock.Kind.OIL_FILTER ? new CartridgeSlots() : null;
    }

    public TurbineAuxBlock.Kind kind() { return ((TurbineAuxBlock) getBlockState().getBlock()).kind(); }

    // ---- attachment

    public BlockPos host() { return host; }

    /** The module this skid is attached to, or null when it is not (or the chunk is not loaded, or the module no longer lists it). */
    public PowerModuleBlockEntity module() {
        if (host == null || level == null || !level.isLoaded(host)) return null;
        return dev.bwr.mod.world.LoadedWorld.blockEntity(level, host) instanceof PowerModuleBlockEntity m && m.hasAux(worldPosition) ? m : null;
    }

    void setHost(BlockPos pos) {
        if (java.util.Objects.equals(host, pos)) return;
        host = pos == null ? null : pos.immutable();
        setChanged();
    }

    // ---- switches (the player's; nothing here flips by itself)

    public boolean acPumpOn() { return acOn; }
    public boolean dcPumpOn() { return dcOn; }
    public boolean statorPumpsOn() { return statorOn; }
    public void setAcPump(boolean on) { if (kind() == TurbineAuxBlock.Kind.LUBE_OIL && acOn != on) { acOn = on; setChanged(); } }
    public void setDcPump(boolean on) { if (kind() == TurbineAuxBlock.Kind.LUBE_OIL && dcOn != on) { dcOn = on; setChanged(); } }
    public void setStatorPumps(boolean on) { if (kind() == TurbineAuxBlock.Kind.GEN_COOLING && statorOn != on) { statorOn = on; setChanged(); } }

    // ---- inventories

    public double oilLitres() { return oilL; }
    public double oilFraction() { return oilL / TurbineAux.OIL_TANK_L; }
    public double oilC() { return oilC; }
    public double h2Kg() { return h2Kg; }
    public double h2Psig() { return TurbineAux.h2Psig(h2Kg); }
    public double coolantC() { return coolantC; }
    public double statorFlow() { return statorFlow; }
    public double psig() { return psig; }
    public double film() { return film; }
    public double coolerKw() { return coolerKw; }
    public double pumpFlow() { return pumpFlow; }
    public boolean acRunning() { return acRan; }
    public boolean dcRunning() { return dcRan; }
    public boolean statorRunning() { return statorRan; }
    public int acFe() { return acFe; }
    public int dcFe() { return dcFe; }
    public double coldKg() { return cold.getFluidAmount(); }
    public double hotKg() { return hot.getFluidAmount(); }
    public double coldC() { return cold.getFluidAmount() > 0 ? cold.temperatureC() : 13; }
    public double hotC() { return hot.getFluidAmount() > 0 ? hot.temperatureC() : 13; }

    /** Pours up to {@code litres} of turbine oil in; returns what the tank took. */
    public double addOil(double litres) {
        if (kind() != TurbineAuxBlock.Kind.LUBE_OIL || !(litres > 0)) return 0;
        double n = Math.min(litres, TurbineAux.OIL_TANK_L - oilL);
        if (n > 0) { oilL += n; setChanged(); }
        return Math.max(0, n);
    }

    /** Charges the casing with hydrogen; returns the kg taken. */
    public double addHydrogen(double kg) {
        if (kind() != TurbineAuxBlock.Kind.GEN_COOLING || !(kg > 0)) return 0;
        double n = Math.min(kg, TurbineAux.H2_FULL_KG - h2Kg);
        if (n > 0) { h2Kg += n; setChanged(); }
        return Math.max(0, n);
    }

    /** Oil lost out of the system to a fire (a cut line, a wiped bearing), up to {@code litres}; returns what there was. */
    public double drainOil(double litres) {
        if (kind() != TurbineAuxBlock.Kind.LUBE_OIL || !(litres > 0)) return 0;
        double n = Math.min(litres, oilL);
        if (n > 0) { oilL -= n; setChanged(); }
        return Math.max(0, n);
    }

    /** The hydrogen charge escaping at broken seals, up to {@code kg}; returns what there was. */
    public double ventHydrogen(double kg) {
        if (kind() != TurbineAuxBlock.Kind.GEN_COOLING || !(kg > 0)) return 0;
        double n = Math.min(kg, h2Kg);
        if (n > 0) { h2Kg -= n; setChanged(); }
        return Math.max(0, n);
    }

    // ---- the Oil Filter Housing (TURBINE-AUX.md): a duplex filter, two cartridge slots A and B and a transfer valve, stepped by the Main Oil Tank it serves

    private final class CartridgeSlots extends ItemStackHandler {
        private boolean quiet;
        CartridgeSlots() { super(2); }
        @Override public boolean isItemValid(int index, ItemStack stack) { return stack.is(BwrItems.OIL_FILTER_CARTRIDGE.get()); }
        @Override public int getSlotLimit(int index) { return 1; }
        /** A cartridge leaves carrying exactly how full it is. */
        @Override public ItemStack extractItem(int index, int amount, boolean simulate) {
            var held = getStackInSlot(index);
            if (!simulate && !held.isEmpty()) OilFilterCartridgeItem.setLoad(held, filterLoads[index]);
            return super.extractItem(index, amount, simulate);
        }
        @Override protected void onContentsChanged(int index) {
            var held = getStackInSlot(index);
            filterLoads[index] = held.isEmpty() ? 0 : OilFilterCartridgeItem.load(held);
            stampedMilles[index] = -1;
            if (!quiet) { setChanged(); syncState(); }
        }
        void put(int index, ItemStack stack) {
            quiet = true;
            try { stacks.set(index, stack); onContentsChanged(index); } finally { quiet = false; }
        }
    }

    /** The housing's two slots (0 is side A, 1 is side B), for the screen and the player; null on the other kinds. */
    public ItemStackHandler cartridgeSlot() { return slot; }

    private IItemHandler automation;
    /**
     * What a hopper or a pipe sees: the two slots, A and B. It may put a cartridge into an <b>empty</b> slot, on either side (an empty
     * slot in service is running on its bypass, an empty one out of service is waiting to be filled), and can take nothing out, so
     * nothing pulls a cartridge in service or out of it.
     */
    public IItemHandler cartridgeAutomation() {
        if (slot == null) return null;
        if (automation == null) automation = new IItemHandler() {
            public int getSlots() { return 2; }
            public ItemStack getStackInSlot(int i) { return slot.getStackInSlot(i); }
            public ItemStack insertItem(int i, ItemStack s, boolean simulate) { return slot.getStackInSlot(i).isEmpty() ? slot.insertItem(i, s, simulate) : s; }
            public ItemStack extractItem(int i, int amount, boolean simulate) { return ItemStack.EMPTY; }
            public int getSlotLimit(int i) { return 1; }
            public boolean isItemValid(int i, ItemStack s) { return slot.isItemValid(i, s); }
        };
        return automation;
    }

    // -- the transfer valve

    /** Whether side B is in service (the transfer valve); side A otherwise, which is also what a housing saved with one cartridge reads. */
    public boolean sideBInService() { return slot != null && sideB; }
    /** The side in service as a slot index: 0 for A, 1 for B. */
    public int sideInService() { return sideBInService() ? 1 : 0; }

    /**
     * Throws the transfer valve: true selects side B, false side A. The side that goes out of service is no longer loaded and can be
     * opened; the one that comes into service starts filtering and loading (an empty one runs the oil on the bypass). Returns whether it moved.
     */
    public boolean selectSide(boolean b) {
        if (slot == null || sideB == b) return false;
        sideB = b;
        setChanged();
        syncState();
        return true;
    }

    /** Redstone: power selects side B, losing it side A; only a change of the power is a command. */
    void transferRedstone(boolean powered) {
        if (slot == null || powered == transferPowered) return;
        transferPowered = powered;
        setChanged();
        selectSide(powered);
    }

    /** The block state shows the lever (the side in service) and the lid of the side out of service (open when its slot is empty). Server only. */
    void syncState() {
        if (slot == null || level == null || level.isClientSide()) return;
        var state = getBlockState();
        if (!(state.getBlock() instanceof TurbineAuxBlock)) return;
        boolean offOpen = slot.getStackInSlot(sideB ? 0 : 1).isEmpty();
        if (state.getValue(TurbineAuxBlock.SIDE_B) != sideB || state.getValue(TurbineAuxBlock.OFF_OPEN) != offOpen)
            level.setBlock(worldPosition, state.setValue(TurbineAuxBlock.SIDE_B, sideB).setValue(TurbineAuxBlock.OFF_OPEN, offOpen), 3);
    }

    /** The screen brings the block state up to date when it opens (a housing saved before the transfer valve existed). */
    public void syncStateForScreen() { syncState(); }

    // -- the readings of one side (0 A, 1 B); the readings without a side are the side in service

    public boolean hasCartridge(int side) { return slot != null && !slot.getStackInSlot(side).isEmpty(); }
    /** How full the cartridge of a side is, 0 to 1 (0 with none in). */
    public double filterLoad(int side) { return hasCartridge(side) ? filterLoads[side] : 0; }
    /** What is left in the cartridge of a side, 1 new to 0 spent; 0 with none in. */
    public double filterLife(int side) { return hasCartridge(side) ? 1 - filterLoads[side] : 0; }
    /** The pressure drop across the cartridge of a side, psi; none with no cartridge. */
    public double filterDpPsi(int side) { return hasCartridge(side) ? TurbineAux.filterDpPsi(filterLoads[side]) : 0; }
    /** Whether the oil would go round that side: its slot is empty, or its cartridge is full. */
    public boolean filterBypass(int side) { return !hasCartridge(side) || TurbineAux.filterBypassOpen(filterLoads[side]); }
    /** Whether that side needs a change: a pressure drop at the change level, or oil that would go round it. */
    public boolean filterChangeDue(int side) { return filterBypass(side) || filterDpPsi(side) >= TurbineAux.FILTER_DP_CHANGE_PSI; }

    public boolean hasCartridge() { return hasCartridge(sideInService()); }
    /** How full the cartridge in service is, 0 to 1 (0 with none in). */
    public double filterLoad() { return filterLoad(sideInService()); }
    /** What is left in the cartridge in service, 1 new to 0 spent; 0 with none in. */
    public double filterLife() { return filterLife(sideInService()); }
    /** The pressure drop across the cartridge in service, psi; none with no cartridge. */
    public double filterDpPsi() { return filterDpPsi(sideInService()); }
    /** Whether the oil goes round the filter: the side in service has an empty slot, or its cartridge is full. */
    public boolean filterBypass() { return filterBypass(sideInService()); }
    /** Whether a change of the side in service is due: a pressure drop at the change level, or oil going round. */
    public boolean filterChangeDue() { return filterChangeDue(sideInService()); }
    /** The share of the unfiltered oil loss that this housing lets through right now (only the side in service filters). */
    public double filterFactor() { return TurbineAux.filterFactor(hasCartridge(), filterLoad()); }

    /** Hours the cartridge in service has left at the header pressure and oil temperature the tank holds now; infinite when nothing flows through it. */
    public double filterHoursLeft() {
        var t = tank();
        if (!hasCartridge() || t == null) return Double.POSITIVE_INFINITY;
        return TurbineAux.filterSecondsLeft(filterLoad(), t.psig(), t.oilC()) / 3600;
    }

    /**
     * <b>The one place that decides which Main Oil Tank a housing serves</b> (and {@link #oilFilter} is the one place a tank finds its
     * housing; everything else asks one of these two). In order: the tank bolted on its {@link TurbineAuxBlock#oilInFace inlet face} (the
     * back); else the tank whose discharge the Lube Oil Pipe run from the inlet face leads back to (the housing is in-line in that tank's
     * discharge line; the first tank the run joins, in the order the survey finds them); else the first tank against any other face in
     * the order of {@link Direction#values()} (the old bolted-on rule, so a housing mounted against a tank by its side still works).
     * The outlet face ({@link TurbineAuxBlock#oilOutFace}) is where the oil leaves toward the turbine train. Never loads a chunk.
     */
    public TurbineAuxBlockEntity tank() {
        if (kind() == TurbineAuxBlock.Kind.CONDITIONER) return conditionerTank();
        if (slot == null || level == null) return null;
        var block = (TurbineAuxBlock) getBlockState().getBlock();
        var inlet = worldPosition.relative(block.oilInFace(getBlockState()));
        if (TurbineAuxBlock.tankAt(level, inlet) && LoadedWorld.blockEntity(level, inlet) instanceof TurbineAuxBlockEntity t && !t.isRemoved()) return t;
        for (var e : OilLineNetwork.survey(level, worldPosition, block.oilInFace(getBlockState())).topo) {
            if (e.role() == OilLineNetwork.Role.TANK_DISCHARGE && LoadedWorld.blockEntity(level, e.pos()) instanceof TurbineAuxBlockEntity t && !t.isRemoved()) return t;
        }
        for (Direction d : Direction.values()) {
            var p = worldPosition.relative(d);
            if (TurbineAuxBlock.tankAt(level, p) && LoadedWorld.blockEntity(level, p) instanceof TurbineAuxBlockEntity t && !t.isRemoved()) return t;
        }
        return null;
    }

    /**
     * The housing this tank is fitted with, or null: first the first of those bolted against it that has this tank as theirs (in the order
     * of {@link Direction#values()}: direct contact wins), then the first housing in-line in the Lube Oil Pipe run leaving its discharge
     * port, entered by its inlet face (the nearest one; a second housing further along does not filter).
     */
    public TurbineAuxBlockEntity oilFilter() {
        if (kind() != TurbineAuxBlock.Kind.LUBE_OIL || level == null) return null;
        for (Direction d : Direction.values()) {
            var p = worldPosition.relative(d);
            var s = LoadedWorld.state(level, p);
            if (s == null || !(s.getBlock() instanceof TurbineAuxBlock b) || b.kind() != TurbineAuxBlock.Kind.OIL_FILTER) continue;
            if (LoadedWorld.blockEntity(level, p) instanceof TurbineAuxBlockEntity h && !h.isRemoved() && h.tank() == this) return h;
        }
        for (var p : oilSurvey(OilLineNetwork.Role.TANK_DISCHARGE).filters) {
            if (LoadedWorld.blockEntity(level, p) instanceof TurbineAuxBlockEntity h && !h.isRemoved() && h.tank() == this) return h;
        }
        return null;
    }

    // ---- the lube oil lines (TURBINE-AUX.md, Piping): what the run on each oil port joins

    /** What the Lube Oil Pipe run on this skid's port of {@code role} joins; nothing when it has no such port or no pipe there. */
    public OilLineNetwork.Survey oilSurvey(OilLineNetwork.Role role) {
        if (level == null || level.isClientSide()) return OilLineNetwork.Survey.NONE;
        var face = ((TurbineAuxBlock) getBlockState().getBlock()).oilFaceOf(getBlockState(), role);
        return face == null ? OilLineNetwork.Survey.NONE : OilLineNetwork.survey(level, worldPosition, face);
    }

    /** Whether this pump's discharge (the tank's, or the shaft pump's) is piped to a section's bearing supply: it then feeds the sections that run reaches and not the train it is bolted on. */
    public boolean dischargePiped() {
        return switch (kind()) {
            case LUBE_OIL -> oilSurvey(OilLineNetwork.Role.TANK_DISCHARGE).joins(OilLineNetwork.Role.SECTION_SUPPLY);
            case SHAFT_PUMP -> oilSurvey(OilLineNetwork.Role.PUMP_DISCHARGE).joins(OilLineNetwork.Role.SECTION_SUPPLY);
            default -> false;
        };
    }

    /** Whether the shaft pump's suction is piped to a tank's suction outlet: it then draws from the tanks that run reaches and not from the tanks of its train. */
    public boolean suctionPiped() {
        return kind() == TurbineAuxBlock.Kind.SHAFT_PUMP && oilSurvey(OilLineNetwork.Role.PUMP_SUCTION).joins(OilLineNetwork.Role.TANK_SUCTION);
    }

    /**
     * One tick of oil through the cartridge in service, called by the tank's oil step: returns the share of the unfiltered loss that gets
     * through and loads that cartridge by what flowed. A full cartridge or an empty slot lets all of it through and loads nothing. The
     * side out of service is not in the flow: it does not load.
     */
    double filterPass(double headerPsig, double oilTempC, double dt) {
        syncState(); // a housing saved before the transfer valve existed (or a slot changed behind the screen's back) is brought up to date here
        double factor = filterFactor();
        if (factor < 1) {
            int side = sideInService();
            filterLoads[side] = TurbineAux.filterLoadStep(filterLoads[side], headerPsig, oilTempC, dt);
            int mille = (int) Math.floor(filterLoads[side] * 1000);
            if (mille != stampedMilles[side]) {
                // What the screen and a viewer show: the stack in the slot follows the load by a thousandth, not every tick.
                OilFilterCartridgeItem.setLoad(slot.getStackInSlot(side), mille / 1000.0);
                stampedMilles[side] = mille;
            }
            setChanged();
        }
        return factor;
    }

    /** The cartridges fall out when the housing is broken, each carrying exactly how full it is. */
    void dropCartridge() {
        if (slot == null || level == null) return;
        for (int i = 0; i < 2; i++) {
            if (!hasCartridge(i)) continue;
            var out = slot.getStackInSlot(i).copy();
            OilFilterCartridgeItem.setLoad(out, filterLoads[i]);
            slot.put(i, ItemStack.EMPTY);
            Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, out);
        }
    }

    // ---- the Lube Oil Conditioner (TURBINE-AUX.md): a side-stream centrifuge and vacuum dehydrator, stepped by the tank it serves

    public boolean conditionerOn() { return condOn; }
    public boolean conditionerRunning() { return condRan; }
    public void setConditioner(boolean on) {
        if (kind() == TurbineAuxBlock.Kind.CONDITIONER && condOn != on) {
            condOn = on;
            if (!on) condRan = false;
            setChanged();
            syncLamp();
        }
    }

    /** The running lamp on the control box: lit while the conditioner runs, dark otherwise; the block state carries it, so the client needs nothing else. */
    private void syncLamp() {
        if (kind() != TurbineAuxBlock.Kind.CONDITIONER || level == null || level.isClientSide()) return;
        var state = getBlockState();
        if (state.getValue(TurbineAuxBlock.LIT) != condRan) level.setBlock(worldPosition, state.setValue(TurbineAuxBlock.LIT, condRan), 3);
    }
    /** FE a conditioner draws a tick while it runs. */
    public static final int CONDITIONER_FE_PER_TICK = EccsPower.fePerTickFromWatts(TurbineAux.CONDITIONER_W);

    /**
     * <b>The one place that decides which tank a conditioner serves.</b> The tank bolted on its inlet face (the back), else the tank whose
     * suction outlet the run from its inlet reaches <i>and</i> whose fill connection the run from its outlet reaches (it draws from the
     * tank and returns to the same tank), else the first tank against any other face. Never loads a chunk.
     */
    TurbineAuxBlockEntity conditionerTank() {
        if (level == null) return null;
        var block = (TurbineAuxBlock) getBlockState().getBlock();
        var inlet = worldPosition.relative(block.conditionerInFace(getBlockState()));
        if (TurbineAuxBlock.tankAt(level, inlet) && LoadedWorld.blockEntity(level, inlet) instanceof TurbineAuxBlockEntity t && !t.isRemoved()) return t;
        var inSurvey = OilLineNetwork.survey(level, worldPosition, block.conditionerInFace(getBlockState()));
        var outSurvey = OilLineNetwork.survey(level, worldPosition, block.conditionerOutFace(getBlockState()));
        for (var e : inSurvey.topo) {
            if (e.role() != OilLineNetwork.Role.TANK_SUCTION || !(LoadedWorld.blockEntity(level, e.pos()) instanceof TurbineAuxBlockEntity t) || t.isRemoved()) continue;
            for (var o : outSurvey.topo) if (o.role() == OilLineNetwork.Role.TANK_FILL && o.pos().equals(e.pos())) return t;
        }
        for (Direction d : Direction.values()) {
            var p = worldPosition.relative(d);
            if (TurbineAuxBlock.tankAt(level, p) && LoadedWorld.blockEntity(level, p) instanceof TurbineAuxBlockEntity t && !t.isRemoved()) return t;
        }
        return null;
    }

    /** Whether oil can pass from the tank through this conditioner and back: bolted on it, or both runs open (valves as they are). */
    private boolean conditionerFlows(TurbineAuxBlockEntity tank) {
        var block = (TurbineAuxBlock) getBlockState().getBlock();
        for (Direction d : Direction.values()) if (worldPosition.relative(d).equals(tank.getBlockPos())) return true;
        var in = OilLineNetwork.survey(level, worldPosition, block.conditionerInFace(getBlockState()));
        var out = OilLineNetwork.survey(level, worldPosition, block.conditionerOutFace(getBlockState()));
        return in.reaches(OilLineNetwork.Role.TANK_SUCTION, tank.getBlockPos()) && out.reaches(OilLineNetwork.Role.TANK_FILL, tank.getBlockPos());
    }

    /**
     * The conditioner a tank is fitted with, or null: first one bolted against it (the first in the order of {@link Direction#values()} that
     * has this tank as its tank), then the first one whose inlet the run from the tank's suction outlet reaches and that has this tank as
     * its tank. A tank is fitted with one conditioner.
     */
    public TurbineAuxBlockEntity conditioner() {
        if (kind() != TurbineAuxBlock.Kind.LUBE_OIL || level == null) return null;
        for (Direction d : Direction.values()) {
            var p = worldPosition.relative(d);
            var s = LoadedWorld.state(level, p);
            if (s == null || !(s.getBlock() instanceof TurbineAuxBlock b) || b.kind() != TurbineAuxBlock.Kind.CONDITIONER) continue;
            if (LoadedWorld.blockEntity(level, p) instanceof TurbineAuxBlockEntity c && !c.isRemoved() && c.conditionerTank() == this) return c;
        }
        for (var e : oilSurvey(OilLineNetwork.Role.TANK_SUCTION).topo) {
            if (e.role() != OilLineNetwork.Role.COND_INLET) continue;
            if (LoadedWorld.blockEntity(level, e.pos()) instanceof TurbineAuxBlockEntity c && !c.isRemoved() && c.kind() == TurbineAuxBlock.Kind.CONDITIONER && c.conditionerTank() == this) return c;
        }
        return null;
    }

    /**
     * One tick of the conditioner, called by the tank's oil step: the share of the oil's degradation that remains. It runs when it is
     * switched on, the tank has oil, its bus holds the FE for the tick and oil can pass (bolted on the tank, or both runs open); otherwise it
     * leaves all of it, so the loss is the filter-only rate.
     */
    double conditionerPass(TurbineAuxBlockEntity tank) {
        boolean ran = condOn && tank.oilL > 0 && acFe >= CONDITIONER_FE_PER_TICK && conditionerFlows(tank);
        if (ran) acFe -= CONDITIONER_FE_PER_TICK;
        if (ran != condRan) { condRan = ran; syncLamp(); }
        setChanged();
        return TurbineAux.conditionerFactor(ran);
    }

    // ---- the water ports (oil cooler, generator coolers): cold in on one flange, hot out on the other

    public IFluidHandler coldInlet() { return port(cold, true); }
    public IFluidHandler hotOutlet() { return port(hot, false); }

    private IFluidHandler port(ThermalWaterTank tank, boolean inlet) {
        return new IFluidHandler() {
            public int getTanks() { return 1; }
            public FluidStack getFluidInTank(int t) { return tank.getFluid().copy(); }
            public int getTankCapacity(int t) { return WATER_CAPACITY; }
            public boolean isFluidValid(int t, FluidStack s) { return inlet && s.is(Fluids.WATER); }
            public int fill(FluidStack s, FluidAction a) {
                if (!inlet || isRemoved()) return 0;
                int n = tank.fill(s, a);
                if (n > 0 && a.execute()) setChanged();
                return n;
            }
            public FluidStack drain(FluidStack s, FluidAction a) { return s.is(Fluids.WATER) ? drain(s.getAmount(), a) : FluidStack.EMPTY; }
            public FluidStack drain(int max, FluidAction a) {
                if (inlet || isRemoved()) return FluidStack.EMPTY;
                var out = tank.drain(max, a);
                if (!out.isEmpty() && a.execute()) setChanged();
                return out;
            }
        };
    }

    /** Takes heat out of a process at {@code processC} through the skid's cooler, kW. Passes the design flow of cooling water cold to hot. */
    private double cool(double processC, double ua, double designKgPerS) {
        int want = (int) Math.round(designKgPerS * DT);
        int use = Math.min(want, Math.min(cold.getFluidAmount(), WATER_CAPACITY - hot.getFluidAmount()));
        if (use <= 0) return 0;
        double inC = cold.temperatureC(), inH = ThermalWater.enthalpy(cold.getFluid(), 13);
        double q = TurbineAux.coolerKw(processC, ua, inC, use / DT);
        cold.drain(use, IFluidHandler.FluidAction.EXECUTE);
        hot.fill(ThermalWater.stack(use, inH + q * DT / use), IFluidHandler.FluidAction.EXECUTE);
        return q;
    }

    private void pushHot() {
        if (level == null || hot.getFluidAmount() <= 0) return;
        Direction face = ((TurbineAuxBlock) getBlockState().getBlock()).hotOutFace(getBlockState());
        BlockPos next = worldPosition.relative(face);
        if (!level.isLoaded(next)) return;
        var target = level.getCapability(Capabilities.FluidHandler.BLOCK, next, face.getOpposite());
        if (target == null) return;
        var out = hot.drain(hot.getFluidAmount(), IFluidHandler.FluidAction.SIMULATE);
        if (out.isEmpty()) return;
        int n = target.fill(out, IFluidHandler.FluidAction.EXECUTE);
        if (n > 0) hot.drain(n, IFluidHandler.FluidAction.EXECUTE);
    }

    // ---- the train's step (once a tick)

    /** True the first time it is asked in a game tick. */
    boolean beginStep(long now) {
        if (now == stepTick) return false;
        stepTick = now;
        return true;
    }

    /** The oil the AC and DC pumps deliver this tick as a fraction of the bearings' rated need (they draw FE only while switched on and fed). */
    double oilPumpFlow() {
        acRan = acOn && acFe >= AC_PUMP_FE_PER_TICK;
        dcRan = dcOn && dcFe >= DC_PUMP_FE_PER_TICK;
        if (acRan) acFe -= AC_PUMP_FE_PER_TICK;
        if (dcRan) dcFe -= DC_PUMP_FE_PER_TICK;
        pumpFlow = ((acRan ? TurbineAux.AC_PUMP_FLOW : 0) + (dcRan ? TurbineAux.DC_PUMP_FLOW : 0)) * TurbineAux.suction(oilL);
        return pumpFlow;
    }

    double suction() { return TurbineAux.suction(oilL); }

    /** The oil's heat, cooler and leak for the tick, given the train's header pressure and this skid's share of the bearing heat. */
    void oilThermal(double heatKw, double headerPsig, double filmQuality) {
        psig = headerPsig;
        film = filmQuality;
        coolerKw = cool(oilC, TurbineAux.OIL_COOLER_UA_KW_PER_K, TurbineAux.OIL_COOLER_WATER_KG_PER_S);
        oilC = TurbineAux.oilStepC(oilC, oilL, heatKw, coolerKw, DT);
        if (oilL > 0) {
            // A tank without a housing loses oil exactly as it always did; with one, the cartridge in it lets a tenth through.
            var filter = oilFilter();
            double loss = filter == null ? TurbineAux.oilLossLitres(headerPsig, oilC, DT)
                    : TurbineAux.oilLossLitres(headerPsig, oilC, DT, filter.filterPass(headerPsig, oilC, DT));
            // A running Lube Oil Conditioner leaves 30 % of what degrades the oil, whatever the filter does; stopped, short of power or cut off
            // it leaves all of it, and the loss is the filter-only rate, to the bit.
            var conditioner = conditioner();
            if (conditioner != null) loss *= conditioner.conditionerPass(this);
            oilL = Math.max(0, oilL - loss);
        }
        pushHot();
        setChanged();
    }

    /** The stator water pumps for the tick: they draw FE while switched on and the flow follows them. Returns the flow fraction. */
    double statorStep() {
        statorRan = statorOn && acFe >= STATOR_PUMPS_FE_PER_TICK;
        if (statorRan) acFe -= STATOR_PUMPS_FE_PER_TICK;
        statorFlow = TurbineAux.approach(statorFlow, statorRan ? 1 : 0, TurbineAux.STATOR_FLOW_TAU_S, DT);
        return statorFlow;
    }

    /** The coolant loop for the tick, given the heat the winding put into it, kW. */
    void coolantStep(double fromWindingKw) {
        coolerKw = cool(coolantC, TurbineAux.GEN_COOLER_UA_KW_PER_K, TurbineAux.GEN_COOLER_WATER_KG_PER_S);
        coolantC = TurbineAux.coolantStepC(coolantC, fromWindingKw, coolerKw, DT);
        if (h2Kg > 0) h2Kg = Math.max(0, h2Kg - TurbineAux.H2_LEAK_KG_PER_S * (TurbineAux.h2Psig(h2Kg) / TurbineAux.H2_FULL_PSIG) * DT);
        pushHot();
        setChanged();
    }

    /** Takes {@code fe} off the AC bus (the turning gear motor's supply), if the bus holds that much. */
    boolean drawAc(int fe) {
        if (acFe < fe) return false;
        acFe -= fe;
        return true;
    }

    // ---- FE: the AC bus (and, on the generator cooling skid, its pump supply) and the DC bus

    public final IEnergyStorage acEnergy = new Buffer(true);
    public final IEnergyStorage dcEnergy = new Buffer(false);

    private final class Buffer implements IEnergyStorage {
        private final boolean ac;
        Buffer(boolean ac) { this.ac = ac; }
        public int receiveEnergy(int max, boolean simulate) {
            int stored = ac ? acFe : dcFe, cap = ac ? AC_CAPACITY_FE : DC_CAPACITY_FE;
            int n = Math.min(Math.max(0, max), cap - stored);
            if (!simulate && n > 0) { if (ac) acFe += n; else dcFe += n; setChanged(); }
            return n;
        }
        public int extractEnergy(int max, boolean simulate) { return 0; }
        public int getEnergyStored() { return ac ? acFe : dcFe; }
        public int getMaxEnergyStored() { return ac ? AC_CAPACITY_FE : DC_CAPACITY_FE; }
        public boolean canExtract() { return false; }
        public boolean canReceive() { return true; }
    }

    // ---- saves: a key only when it is not its default

    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.saveAdditional(t, r);
        if (host != null) t.putLong("Host", host.asLong());
        if (acOn) t.putBoolean("AcOn", true);
        if (dcOn) t.putBoolean("DcOn", true);
        if (statorOn) t.putBoolean("StatorOn", true);
        if (acFe != 0) t.putInt("AcFe", acFe);
        if (dcFe != 0) t.putInt("DcFe", dcFe);
        if (oilL != 0) t.putDouble("OilL", oilL);
        if (Math.abs(oilC - TurbineAux.AMBIENT_C) > 1e-9) t.putDouble("OilC", oilC);
        if (h2Kg != 0) t.putDouble("H2Kg", h2Kg);
        if (Math.abs(coolantC - TurbineAux.AMBIENT_C) > 1e-9) t.putDouble("CoolantC", coolantC);
        if (statorFlow != 0) t.putDouble("StatorFlow", statorFlow);
        saveWater(t, "Cold", cold);
        saveWater(t, "Hot", hot);
        if (slot != null) {
            // A: the key the single-slot housing always used (an old save's one cartridge is side A, A in service). B, the transfer valve and
            // its redstone reading are written only when set.
            for (int i = 0; i < 2; i++) {
                if (slot.getStackInSlot(i).isEmpty()) continue;
                var held = slot.getStackInSlot(i).copy();
                OilFilterCartridgeItem.setLoad(held, filterLoads[i]);
                t.put(i == 0 ? "Cartridge" : "CartridgeB", held.save(r));
            }
            if (sideB) t.putBoolean("SideB", true);
            if (transferPowered) t.putBoolean("Powered", true);
        }
        if (condOn) t.putBoolean("CondOn", true);
    }

    private static void saveWater(CompoundTag t, String key, ThermalWaterTank tank) {
        if (tank.getFluidAmount() <= 0) return;
        t.putInt(key + "Kg", tank.getFluidAmount());
        t.putDouble(key + "H", ThermalWater.enthalpy(tank.getFluid(), 13));
    }

    private static void loadWater(CompoundTag t, String key, ThermalWaterTank tank) {
        int kg = Math.max(0, Math.min(WATER_CAPACITY, t.getInt(key + "Kg")));
        double h = t.getDouble(key + "H");
        tank.setFluid(kg > 0 && Double.isFinite(h) && h > 0 && h < 5000 ? ThermalWater.stack(kg, h) : FluidStack.EMPTY);
    }

    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t, r);
        host = t.contains("Host") ? BlockPos.of(t.getLong("Host")) : null;
        acOn = t.getBoolean("AcOn");
        dcOn = t.getBoolean("DcOn");
        statorOn = t.getBoolean("StatorOn");
        acFe = Math.clamp(t.getInt("AcFe"), 0, AC_CAPACITY_FE);
        dcFe = Math.clamp(t.getInt("DcFe"), 0, DC_CAPACITY_FE);
        oilL = finite(t.getDouble("OilL"), 0, TurbineAux.OIL_TANK_L, 0);
        oilC = finite(t.contains("OilC") ? t.getDouble("OilC") : TurbineAux.AMBIENT_C, -50, 400, TurbineAux.AMBIENT_C);
        h2Kg = finite(t.getDouble("H2Kg"), 0, TurbineAux.H2_FULL_KG, 0);
        coolantC = finite(t.contains("CoolantC") ? t.getDouble("CoolantC") : TurbineAux.AMBIENT_C, -50, 400, TurbineAux.AMBIENT_C);
        statorFlow = finite(t.getDouble("StatorFlow"), 0, 1, 0);
        loadWater(t, "Cold", cold);
        loadWater(t, "Hot", hot);
        if (slot != null) {
            for (int i = 0; i < 2; i++) {
                String key = i == 0 ? "Cartridge" : "CartridgeB";
                var held = t.contains(key) ? ItemStack.parse(r, t.get(key)).orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
                slot.put(i, held.is(BwrItems.OIL_FILTER_CARTRIDGE.get()) ? held.copyWithCount(1) : ItemStack.EMPTY);
            }
            sideB = t.getBoolean("SideB");
            transferPowered = t.getBoolean("Powered");
        }
        condOn = t.getBoolean("CondOn");
        condRan = false;
        stepTick = Long.MIN_VALUE;
        psig = film = coolerKw = pumpFlow = 0;
        acRan = dcRan = statorRan = false;
    }

    private static double finite(double v, double lo, double hi, double fallback) {
        return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : fallback;
    }
}
