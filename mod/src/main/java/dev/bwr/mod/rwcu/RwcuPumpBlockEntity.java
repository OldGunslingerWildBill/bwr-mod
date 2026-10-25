package dev.bwr.mod.rwcu;

import dev.bwr.core.chemistry.CoolantChemistry;
import dev.bwr.core.chemistry.RwcuLoop;
import dev.bwr.core.chemistry.RwcuPumpDesign;
import dev.bwr.core.eccs.EccsPump;
import dev.bwr.mod.eccs.EccsPower;
import dev.bwr.mod.eccs.MachineEnergy;
import dev.bwr.mod.eccs.PlantActuators;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.ReactorStateNbt;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.minecraft.world.level.material.Fluids;

/**
 * The RWCU pump, and the driver of the whole cleanup skid. When it runs it resolves the circuit the pipes make
 * ({@link RwcuCircuit}), draws reactor water from the vessel, and steps the regenerative exchanger, the cooler and the
 * demineraliser with that flow, then tells the core what the loop cost the vessel: the heat it took (net of the
 * regeneration) and the water it rejected. The water it draws and returns is no flow of the vessel's: it leaves and comes back.
 *
 * <p>It never starts itself and nothing isolates it: a redstone signal, the panel or a computer starts it, and it keeps
 * running through an over-temperature demineraliser or a low level until the player stops it (it draws nothing once the
 * water falls to its suction port, which is geometry, not a trip).
 */
public class RwcuPumpBlockEntity extends BlockEntity {
    private static final double DT = 0.05;
    private final EccsPump pump = new EccsPump(RwcuPumpDesign.INSTANCE);
    private final MachineEnergy energy = new MachineEnergy(EccsPower.fePerTickFromWatts(RwcuPumpDesign.INSTANCE.motorRatingWatts()));
    private volatile boolean computerControlled;
    private boolean panelControlled;
    private volatile String status = "Awaiting pipe survey";
    private volatile double flowKgPerS, rejectKgPerS, demineraliserKgPerS, drawKgPerS;
    private volatile double demineraliserC, returnC, heatLossMW, coolerDutyMW, regenDutyMW;
    private volatile boolean valid;
    private double rejectCarryKg;
    private boolean rejectBlocked;
    private int tickCounter;
    private BlockPos reactorPos;

    public RwcuPumpBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.RWCU_PUMP.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, RwcuPumpBlockEntity be) { be.tick(level); }

    public EccsPump pump() { return pump; }
    public MachineEnergy energy() { return energy; }
    public boolean isComputerControlled() { return computerControlled; }
    public void setComputerControlled(boolean on) { computerControlled = on; if (on) panelControlled = false; setChanged(); }
    public boolean isPanelControlled() { return panelControlled; }
    public void setPanelControlled(boolean on) { panelControlled = on; if (on) computerControlled = false; setChanged(); }
    public void setRunning(boolean running) { PlantActuators.run(this, () -> { pump.setRunning(running); setChanged(); }); }
    public boolean isRunning() { return pump.isRunning(); }
    public void setSpeedDemandFraction(double fraction) { PlantActuators.run(this, () -> { pump.setSpeedDemandFraction(fraction); setChanged(); }); }
    public String connectionStatus() { return status; }
    public boolean circuitValid() { return valid; }
    public double getFlowKgPerS() { return flowKgPerS; }
    public double getRejectKgPerS() { return rejectKgPerS; }
    public double getDemineraliserKgPerS() { return demineraliserKgPerS; }
    public double getDemineraliserC() { return demineraliserC; }
    public double getReturnC() { return returnC; }
    public double getHeatLossMW() { return heatLossMW; }
    public double getCoolerDutyMW() { return coolerDutyMW; }
    public double getRegenDutyMW() { return regenDutyMW; }
    public BlockPos reactorPos() { return reactorPos; }

    private void idle(String why) {
        valid = false;
        status = why;
        flowKgPerS = rejectKgPerS = demineraliserKgPerS = 0;
        heatLossMW = coolerDutyMW = regenDutyMW = 0;
    }

    private void tick(Level level) {
        tickCounter++;
        boolean running = pump.isRunning() || pump.getSpeedFraction() > 1e-6;
        if (!running && tickCounter % 20 != 0) return;
        var circuit = RwcuCircuit.resolve(level, worldPosition, getBlockState());
        valid = circuit.valid;
        var reactor = circuit.valid ? circuit.reactor : null;
        var core = reactor == null ? null : reactor.core();
        reactorPos = reactor == null ? null : reactor.getBlockPos();
        double suction = 0;
        if (core != null) {
            for (int i = 0; i < circuit.suctionPorts.size(); i++)
                if (RwcuCircuit.covered(reactor, circuit.suctionPorts.get(i).getBlockPos()))
                    suction = Math.max(suction, circuit.suctionOpenings.get(i));
        }
        double main = core == null ? 0 : Math.min(suction, circuit.mainBeyondSuction);
        double od = circuit.valid ? circuit.beddedOpening : 0;
        double or = circuit.valid && !rejectBlocked ? circuit.rejectOpening : 0;
        double throttle = Math.min(1.0, od + or);
        // The pump's own work: the loop is a system curve, differential growing with flow squared.
        var design = RwcuPumpDesign.INSTANCE;
        double rated = design.ratedFlowKgPerS();
        double c = RwcuPumpDesign.LOOP_DIFFERENTIAL_PSI / (rated * rated);
        double k = (design.curve().shutoffHeadPsi() - RwcuPumpDesign.LOOP_DIFFERENTIAL_PSI) / (rated * rated);
        double s = Math.min(1.0, Math.max(0.0, pump.getSpeedFraction()));
        double qStar = s * Math.sqrt(design.curve().shutoffHeadPsi() / (k + c));
        pump.setVesselPressurePsig(c * qStar * qStar);
        pump.setSuctionPressurePsig(0.0);
        pump.setSuctionTemperatureC(core == null ? 32 : core.getCoolantTemperatureC());
        double vesselKg = core == null ? 0 : core.getLiquidMassKg();
        pump.setSuctionFlowLimitKgPerS(core == null ? 0 : Math.min(rated * main * throttle, vesselKg * 0.2 / DT));
        pump.setElectricalPowerAvailableWatts(Math.min(EccsPower.wattsFromFePerTick(energy.getEnergyStored()), design.motorRatingWatts()));
        pump.step(DT);
        int spend = EccsPower.fePerTickFromWatts(pump.getElectricalDemandWatts());
        if (spend > 0) energy.drain(spend);
        double flow = core == null ? 0 : pump.getFlowKgPerS() * (od + or > 0 ? 1 : 0);
        if (!circuit.valid) {
            idle(circuit.status);
            drawKgPerS = 0;
            setChanged();
            return;
        }
        if (!(flow > 1e-9)) {
            flowKgPerS = rejectKgPerS = demineraliserKgPerS = 0;
            heatLossMW = coolerDutyMW = regenDutyMW = 0;
            status = pump.isRunning() ? (main <= 0 ? "No water at the suction ports (level, or a closed valve)" : "Pump starting")
                    : "Pump stopped";
            circuit.regen.report(0, 0, 0, 0, 0);
            circuit.demin.idle();
            setChanged();
            return;
        }
        // Where the water goes: the demineraliser and the reject path split by their openings.
        double reject = or > 0 ? flow * or / (od + or) : 0;
        // The reject sink takes whole millibuckets; the remainder is carried. A sink that is full blocks it.
        double sinkTemperature = 49;
        double wantedKg = reject * DT;
        if (wantedKg > 0 && !circuit.rejectEnds.isEmpty()) {
            rejectCarryKg += wantedKg;
            if (rejectCarryKg >= 1.0) {
                int whole = (int) Math.floor(rejectCarryKg);
                int taken = deliverReject(level, circuit, whole, sinkTemperature);
                rejectCarryKg -= taken;
                rejectBlocked = taken < whole && rejectCarryKg > 50;
                if (rejectBlocked) rejectCarryKg = 0;
            }
        } else rejectCarryKg = 0;
        if (rejectBlocked && tickCounter % 40 == 0) rejectBlocked = false; // try the sink again now and then
        double vesselC = core.getCoolantTemperatureC();
        double limit = circuit.cooler.maxDutyMW(DT);
        var loop = RwcuLoop.solve(vesselC, flow, reject, limit);
        circuit.cooler.commit(loop.coolerDutyMW(), DT);
        double bedFlow = Math.max(0, flow - reject);
        var chemistry = core.getChemistry();
        double liquid = core.getLiquidMassKg();
        double ionConc = chemistry.ionicConcentrationGPerKg(liquid), crudConc = chemistry.crudConcentrationGPerKg(liquid);
        double bq = chemistry.specificActivityBqPerG();
        var removed = circuit.demin.process(bedFlow, loop.demineraliserC(), ionConc, crudConc, bq, DT);
        chemistry.removeIons(removed.removedIonG());
        chemistry.removeCrud(removed.removedCrudG());
        chemistry.addIons(removed.releasedIonG());
        // What leaves with the reject goes with it, to the sink.
        if (reject > 0) {
            chemistry.removeIons(reject * DT * ionConc);
            chemistry.removeCrud(reject * DT * crudConc);
        }
        core.addCleanupLoop(loop.vesselHeatLossMW(), reject);
        circuit.regen.report(vesselC, loop.hotSideOutC(), loop.demineraliserC(), loop.returnC(), loop.regenDutyMW());
        flowKgPerS = flow;
        rejectKgPerS = reject;
        demineraliserKgPerS = bedFlow;
        demineraliserC = loop.demineraliserC();
        returnC = loop.returnC();
        heatLossMW = loop.vesselHeatLossMW();
        coolerDutyMW = loop.coolerDutyMW();
        regenDutyMW = loop.regenDutyMW();
        double needed = RwcuLoop.coolerDutyNeededMW(vesselC, flow, reject, RwcuLoop.REGEN_EFFECTIVENESS);
        String note = circuit.demin.overTemperature() ? "demineraliser resin is over 140 F"
                : circuit.cooler.dutyMW() + 1e-6 < needed ? "cooling water short, the water is warm"
                : !circuit.demin.hasBed() ? "no resin bed" : "circuit complete";
        status = "Running: " + note;
        setChanged();
    }

    private int deliverReject(Level level, RwcuCircuit circuit, int kg, double temperatureC) {
        double h = RwcuLoop.enthalpy(temperatureC);
        for (var end : circuit.rejectEnds) {
            if (end.opening() <= 0 || !dev.bwr.mod.world.LoadedWorld.available(level, end.pos())) continue;
            IFluidHandler sink = level.getCapability(Capabilities.FluidHandler.BLOCK, end.pos(), end.face());
            if (sink == null) continue;
            int filled = sink.fill(dev.bwr.mod.water.ThermalWater.stack(kg, h), IFluidHandler.FluidAction.EXECUTE);
            if (filled > 0) return filled;
        }
        return 0;
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ReactorStateNbt.putDoubles(tag, "Pump", pump.toArray());
        tag.putBoolean("ComputerControlled", computerControlled);
        tag.putBoolean("PanelControlled", panelControlled);
        tag.putInt("Energy", energy.getEnergyStored());
        if (rejectCarryKg > 0) tag.putDouble("RejectCarryKg", rejectCarryKg);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Pump")) pump.fromArray(ReactorStateNbt.getDoubles(tag, "Pump"));
        computerControlled = tag.getBoolean("ComputerControlled");
        panelControlled = !computerControlled && tag.getBoolean("PanelControlled");
        energy.setStored(tag.getInt("Energy"));
        rejectCarryKg = tag.getDouble("RejectCarryKg");
    }
}
