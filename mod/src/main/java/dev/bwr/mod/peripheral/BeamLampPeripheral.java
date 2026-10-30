package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.mod.floodlight.BeamLampBlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CC face of the beam lamps (xenon floodlight, xenon high-bay, HPS flat panel). Redstone is the only switch. The xenon
 * floodlight can also be aimed: {@code setAim(yawDeg, pitchDeg)} and {@code setReach(blocks)}; the other two lamps are fixed
 * and refuse both. A value out of range is refused and nothing changes. The head turns at once and the light follows within a
 * fifth of a second (one re-plan for a run of calls).
 */
public final class BeamLampPeripheral implements IPeripheral {
    private final BeamLampBlockEntity lamp;

    public BeamLampPeripheral(BeamLampBlockEntity lamp) {
        this.lamp = lamp;
    }

    @Override public String getType() { return "bwr_beam_lamp"; }
    @Override public boolean equals(IPeripheral other) { return other instanceof BeamLampPeripheral p && p.lamp == lamp; }
    @Override public Object getTarget() { return lamp; }

    /**
     * {@code lit}, {@code lightBlocks}, {@code yawDeg}, {@code pitchDeg} and {@code reach} (blocks). {@code yawDegrees} and
     * {@code pitchDegrees} are the same two numbers under their older names. A lamp that cannot be aimed reports its fixed
     * pointing as yaw 0 and pitch 0 and its range as the reach.
     */
    @LuaFunction(mainThread = true) public Map<String, Object> getStatus() {
        var m = new LinkedHashMap<String, Object>();
        m.put("lit", lamp.lit());
        m.put("lightBlocks", lamp.placed().size());
        m.put("yawDegrees", lamp.yawDegrees());
        m.put("pitchDegrees", lamp.pitchDegrees());
        m.put("yawDeg", lamp.yawDegrees());
        m.put("pitchDeg", lamp.pitchDegrees());
        m.put("reach", lamp.reach());
        return m;
    }

    /** Aims the xenon floodlight: yaw offset from the way it faces, 0 to 359 degrees, and pitch, -75 to +30 degrees (up is plus). */
    @LuaFunction(mainThread = true) public void setAim(double yawDeg, double pitchDeg) throws LuaException {
        aimable();
        int yaw = whole(yawDeg, "Yaw");
        int pitch = whole(pitchDeg, "Pitch");
        if (!BeamLampBlockEntity.validYaw(yaw)) throw new LuaException("Yaw must be a whole number of degrees from 0 to " + BeamLampBlockEntity.YAW_MAX);
        if (!BeamLampBlockEntity.validPitch(pitch)) throw new LuaException("Pitch must be a whole number of degrees from " + BeamLampBlockEntity.PITCH_MIN + " to " + BeamLampBlockEntity.PITCH_MAX);
        lamp.setAim(yaw, pitch);
    }

    /** Sets how far the xenon floodlight's beam reaches: 8 to 128 blocks (32 is the usual reach). */
    @LuaFunction(mainThread = true) public void setReach(double blocks) throws LuaException {
        aimable();
        int reach = whole(blocks, "Reach");
        if (!BeamLampBlockEntity.validReach(reach)) throw new LuaException("Reach must be a whole number of blocks from " + BeamLampBlockEntity.REACH_MIN + " to " + BeamLampBlockEntity.REACH_MAX);
        lamp.setReach(reach);
    }

    /** A whole number (a fraction, NaN or infinity is refused, so is a number too big for an int). */
    private static int whole(double value, String what) throws LuaException {
        if (!Double.isFinite(value) || value != Math.rint(value) || Math.abs(value) > 1_000_000) {
            throw new LuaException(what + " must be a whole number");
        }
        return (int) value;
    }

    private void aimable() throws LuaException {
        if (!lamp.floodlight()) throw new LuaException("Only the xenon floodlight can be aimed");
    }
}
