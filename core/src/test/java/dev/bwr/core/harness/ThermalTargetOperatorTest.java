package dev.bwr.core.harness;

/** A heat-balance target must not silently become a neutron-instrument target. */
public final class ThermalTargetOperatorTest {
    public static void testHeatBalanceTargetDoesNotMoveRodsAtEquilibrium() {
        checkDecision(0, 0);
    }
    public static void testSmallThermalErrorsCommandBothDirections() {
        checkDecision(.004, 1);
        checkDecision(-.004, -1);
    }
    private static void checkDecision(double offset, int expected) {
        var core = TransientHarness.ratedCore();
        var operator = new TransientHarness.PlantOperator(core);
        operator.setPressureRegulatorEnabled(false);
        operator.setLevelControlEnabled(false);
        operator.setRodControlEnabled(true);
        operator.setTargetTotalPowerFraction(core.getTotalPowerFractionOfRated() + offset);
        int before = demands(core);
        // Freeze the plant measurement while one operator decision is made.
        for (int i = 0; i <= Math.round(4 / core.getConfig().tickSeconds); i++) operator.tick();
        int after = demands(core);
        System.out.printf("THERMAL TARGET total=%.6f APRM=%.6f decay=%.6f offset=%.6f rodDelta=%d%n",
                core.getTotalPowerFractionOfRated(), core.getAveragePowerRangePercent(0),
                core.getDecayHeatFraction(), offset, after - before);
        if (after - before != expected) throw new AssertionError("Thermal target commanded wrong rod move: " + (after - before));
    }
    private static int demands(dev.bwr.core.ReactorCore core) {
        int sum = 0;
        for (int i = 0; i < core.getControlRodCount(); i++) sum += core.getRodNotchDemand(i);
        return sum;
    }
}
