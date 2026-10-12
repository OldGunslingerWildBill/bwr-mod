package dev.bwr.mod.devtest;

import dev.bwr.mod.containment.ContainmentControllerBlockEntity;
import dev.bwr.mod.containment.ContainmentPenetrationBlockEntity;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.feedwater.FeedwaterPumpBlockEntity;
import dev.bwr.mod.flow.RecirculationPumpBlockEntity;
import dev.bwr.mod.peripheral.ContainmentPenetrationPeripheral;
import dev.bwr.mod.peripheral.ContainmentPeripheral;
import dev.bwr.mod.peripheral.EccsPumpPeripheral;
import dev.bwr.mod.peripheral.FeedwaterPumpPeripheral;
import dev.bwr.mod.peripheral.MainSteamIsolationValvePeripheral;
import dev.bwr.mod.peripheral.ReactorPeripheral;
import dev.bwr.mod.peripheral.RecirculationPumpPeripheral;
import dev.bwr.mod.peripheral.SuppressionPoolPeripheral;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.signal.SignalProvider;
import dev.bwr.mod.steam.MainSteamIsolationValveBlockEntity;
import dev.bwr.mod.suppression.SuppressionPoolBlockEntity;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.Map;

/**
 * Adapter reads against the CC peripherals' own getters. References CC classes, so callers must be behind
 * {@code BwrMod.isComputerCraftPresent()}; without CC the comparison is skipped and only the sanity
 * assertions in {@link SignalLayerTests} run.
 */
final class SignalCcChecks {
    private SignalCcChecks() {}

    /** Count of adapter-versus-peripheral comparisons made, logged so a run proves they were not skipped. */
    static int compared;

    private static void same(GameTestHelper h, SignalProvider p, String key, double expected) {
        compared++;
        var actual = p.read(key);
        h.assertTrue(actual.isPresent() && Double.compare(actual.getAsDouble(), expected) == 0,
                "signal " + key + " = " + actual + " but the CC peripheral reports " + expected);
    }

    private static double number(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean b) return b ? 1 : 0;
        return ((Number) value).doubleValue();
    }

    static void msiv(GameTestHelper h, MainSteamIsolationValveBlockEntity be, SignalProvider p) {
        var cc = new MainSteamIsolationValvePeripheral(be);
        same(h, p, "position", cc.getPosition());
        same(h, p, "demand_open", cc.isDemandOpen() ? 1 : 0);
        same(h, p, "powered", cc.isPowered() ? 1 : 0);
        same(h, p, "energy_fe", cc.getEnergyStoredFe());
        same(h, p, "computer_controlled", cc.isComputerControlled() ? 1 : 0);
    }

    static void recirculationPump(GameTestHelper h, RecirculationPumpBlockEntity be, SignalProvider p) {
        var cc = new RecirculationPumpPeripheral(be);
        same(h, p, "speed_demand", cc.getSpeed());
        same(h, p, "speed", cc.getActualSpeed());
        same(h, p, "achievable_speed", cc.getAchievableSpeed());
        same(h, p, "energy_fe", cc.getEnergy());
        same(h, p, "attached", cc.isAttached() ? 1 : 0);
    }

    static void feedwaterPump(GameTestHelper h, FeedwaterPumpBlockEntity be, SignalProvider p) {
        var cc = new FeedwaterPumpPeripheral(be);
        same(h, p, "running", cc.isRunning() ? 1 : 0);
        same(h, p, "flow", cc.getFlow());
        same(h, p, "flow_demand", cc.getFlowDemand());
        same(h, p, "speed", cc.getSpeed());
        same(h, p, "differential_pressure", cc.getDifferentialPressure());
        same(h, p, "energy_fe", cc.getEnergy());
        same(h, p, "rated_flow", cc.getRatedFlow());
    }

    static void eccsPump(GameTestHelper h, EccsPumpBlockEntity be, SignalProvider p) {
        var cc = new EccsPumpPeripheral(be);
        same(h, p, "running", cc.isRunning() ? 1 : 0);
        same(h, p, "flow", cc.getFlow());
        same(h, p, "rated_flow", cc.getRatedFlow());
        same(h, p, "speed", cc.getSpeed());
        same(h, p, "speed_demand", cc.getTargetSpeed());
        same(h, p, "developed_head", cc.getDevelopedHead());
        same(h, p, "energy_fe", cc.getEnergy());
    }

    static void suppressionPool(GameTestHelper h, SuppressionPoolBlockEntity be, SignalProvider p) {
        var cc = new SuppressionPoolPeripheral(be);
        same(h, p, "temperature", cc.getTemperature());
        same(h, p, "saturation_temperature", cc.getSaturationTemperature());
        same(h, p, "subcooling", cc.getSubcooling());
        same(h, p, "level", cc.getLevel());
        same(h, p, "mass", cc.getMass());
        same(h, p, "rhr_duty", cc.getRhrDuty());
        same(h, p, "formed", cc.isFormed() ? 1 : 0);
        same(h, p, "boiling", cc.isBoiling() ? 1 : 0);
    }

    static void reactorAndContainment(GameTestHelper h, ReactorControllerBlockEntity reactor, SignalProvider rp,
                                      ContainmentControllerBlockEntity room, SignalProvider cp) throws Exception {
        var cc = new ReactorPeripheral(reactor);
        same(h, rp, "power", cc.getTotalPower());
        same(h, rp, "neutron_power", cc.getNeutronPower());
        same(h, rp, "thermal_mw", cc.getThermalPowerMW());
        same(h, rp, "period", cc.getPeriod());
        same(h, rp, "aprm_percent", cc.getAprmPercent());
        same(h, rp, "pressure", cc.getPressure());
        same(h, rp, "level", cc.getWaterLevel());
        same(h, rp, "core_flow", cc.getCoreFlow());
        same(h, rp, "void_fraction", cc.getVoidFraction());
        same(h, rp, "coolant_temp_c", cc.getCoolantTemperature());
        same(h, rp, "fuel_temp_c", cc.getFuelTemperature());
        same(h, rp, "clad_temp_c", cc.getCladTemperature());
        same(h, rp, "peak_clad_temp_c", cc.getPeakCladTemperature());
        same(h, rp, "boron_ppm", cc.getBoronPpm());
        same(h, rp, "steam_generation", cc.getSteamGeneration());
        same(h, rp, "feedwater_flow", cc.getFeedwaterFlow());
        same(h, rp, "core_damage_stage", cc.getCoreDamageStage());
        same(h, rp, "rod_count", cc.getRodCount());
        same(h, rp, "rod_1_position", cc.getRodPosition(1));
        same(h, rp, "rod_1_demand", cc.getRodDemand(1));
        same(h, rp, "rod_1_accumulator", cc.getAccumulatorCharge(1));
        same(h, rp, "rods_not_at_demand", cc.getRodsNotAtDemand());
        same(h, rp, "scram", cc.isScramActive() ? 1 : 0);
        var atmosphere = cc.getContainment();
        same(h, rp, "containment_h2_percent", number(atmosphere, "hydrogenPercent"));
        same(h, rp, "containment_o2_percent", number(atmosphere, "oxygenPercent"));
        same(h, rp, "containment_pressure", number(atmosphere, "pressure"));
        same(h, rp, "containment_inerted", number(atmosphere, "inerted"));
        h.assertTrue(rp.readText("vessel_state").orElse("").equals(cc.getVesselState()), "vessel_state differs from the peripheral");

        var room_cc = new ContainmentPeripheral(room);
        same(h, cp, "sealed", room_cc.isSealed() ? 1 : 0);
        same(h, cp, "free_volume", room_cc.getFreeVolume());
        same(h, cp, "failure_pressure", room_cc.getFailurePressure());
        same(h, cp, "isolated", room_cc.isIsolated() ? 1 : 0);
        same(h, cp, "h2_percent", room_cc.getHydrogenPercent());
        same(h, cp, "o2_percent", room_cc.getOxygenPercent());
        same(h, cp, "pressure", room_cc.getPressurePsig());
        same(h, cp, "bypass_flow", room_cc.getBypassFlow());
    }

    static void penetration(GameTestHelper h, ContainmentPenetrationBlockEntity be, SignalProvider p) {
        var cc = new ContainmentPenetrationPeripheral(be);
        same(h, p, "position", cc.getPosition());
        same(h, p, "isolated", cc.isIsolated() ? 1 : 0);
        System.out.println("SIGNAL CC COMPARISON: " + compared + " adapter reads matched the CC peripherals so far");
    }
}
