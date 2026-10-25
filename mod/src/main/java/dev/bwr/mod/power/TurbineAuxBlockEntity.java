package dev.bwr.mod.power;

import dev.bwr.core.turbine.TurbineAux;
import dev.bwr.mod.eccs.EccsPower;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.water.ThermalWater;
import dev.bwr.mod.water.ThermalWaterTank;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * The state of one turbine auxiliary skid: the Main Oil Tank (oil, AC and DC pumps, oil cooler), the Shaft-Driven Main Oil Pump
 * (nothing to keep) or the Generator Cooling Skid (stator water pumps, hydrogen, coolers). See TURBINE-AUX.md.
 *
 * <p>No ticker. A skid does nothing on its own: the shaft train it is attached to steps it once a tick, from the train's own ticker
 * ({@link TurbineAuxTrain}). A skid with no train ticking, or none attached, is not simulated and draws nothing. Its pumps run only
 * while the player has switched them on.
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

    public TurbineAuxBlockEntity(BlockPos pos, BlockState state) { super(BwrBlockEntities.TURBINE_AUX.get(), pos, state); }

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
        if (oilL > 0) oilL = Math.max(0, oilL - TurbineAux.oilLossLitres(headerPsig, oilC, DT));
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
        stepTick = Long.MIN_VALUE;
        psig = film = coolerKw = pumpFlow = 0;
        acRan = dcRan = statorRan = false;
    }

    private static double finite(double v, double lo, double hi, double fallback) {
        return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : fallback;
    }
}
