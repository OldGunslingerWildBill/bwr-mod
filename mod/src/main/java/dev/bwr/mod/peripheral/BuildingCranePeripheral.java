package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.core.refuelling.BridgeMotion;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.VesselInternal;
import dev.bwr.mod.reactor.VesselState;
import dev.bwr.mod.refuelling.BridgeSite;
import dev.bwr.mod.refuelling.BuildingCraneBlockEntity;
import dev.bwr.mod.text.BwrText;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CC:Tweaked peripheral for the reactor building crane console: queue the head's lift or return, queue the dryer's or the separator
 * assembly's, read what the crane is doing, stop it, resume it. Nothing moves unless the program says so; a halt waits for
 * {@code resume()} or {@code abort()}. Every method runs on the server thread. Lifts return {@code ok} and, when nothing was
 * queued, the reason.
 */
public class BuildingCranePeripheral implements IPeripheral {

    private final BuildingCraneBlockEntity be;

    public BuildingCranePeripheral(BuildingCraneBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "bwr_building_crane";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof BuildingCranePeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    private static Map<String, Object> result(boolean ok, @Nullable Component why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        if (why != null) {
            m.put("message", BwrText.english(why));
        }
        return m;
    }

    private static Map<String, Object> point(@Nullable BridgeMotion.Point p) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (p != null) {
            m.put("x", p.x());
            m.put("y", p.y());
            m.put("z", p.z());
        }
        return m;
    }

    private static VesselInternal internal(String name) throws LuaException {
        var which = VesselInternal.byId(name);
        if (which == null) {
            throw new LuaException("expected \"dryer\" or \"separator\"");
        }
        return which;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        BridgeSite site = be.refreshSite();
        m.put("ready", site != null && site.ok());
        if (site != null && !site.ok()) {
            m.put("problem", BwrText.english(site.problem));
        }
        m.put("phase", be.phase().name().toLowerCase(Locale.ROOT));
        m.put("busy", be.busy());
        m.put("halted", be.phase() == BridgeMotion.Phase.HALTED);
        m.put("progress", be.progress());
        m.put("secondsLeftInPhase", be.phaseTicksLeft() / 20.0);
        m.put("queued", be.queued());
        m.put("holding", be.holding());
        m.put("carried", be.carriedSummary());
        m.put("hook", point(be.tip()));
        if (be.haltMessage() != null) {
            m.put("haltReason", BwrText.english(be.haltMessage()));
        }
        if (site != null && site.ok()) {
            ReactorControllerBlockEntity r = site.reactor(be.getLevel());
            m.put("reactorFound", r != null);
            if (r != null) {
                m.put("headOff", r.vesselState() == VesselState.REFUELING);
                m.put("studsTensioned", r.studsTensioned());
                m.put("studTotal", r.studTotal());
                m.put("dryerInstalled", r.internalInstalled(VesselInternal.DRYER));
                m.put("separatorInstalled", r.internalInstalled(VesselInternal.SEPARATOR));
            }
        }
        if (be.currentMove() != null) {
            m.put("from", be.currentMove().from().describe());
            m.put("to", be.currentMove().to().describe());
        }
        return m;
    }

    /** Queue lifting the vessel head, slung on the strongback, onto the nearest free head stand. Refused while any closure stud is tensioned. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> queueHeadOut() {
        Component why = be.queueHead(true);
        return result(why == null, why);
    }

    /** Queue setting the head back on the vessel flange from the stand that holds it (or from where it lies, in an older world). */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> queueHeadIn() {
        Component why = be.queueHead(false);
        return result(why == null, why);
    }

    /** Queue lifting the steam dryer ("dryer") or the separator assembly ("separator") out of the open, flooded vessel to the nearest free equipment pad. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> queueInternalOut(String name) throws LuaException {
        Component why = be.queueInternal(internal(name), true);
        return result(why == null, why);
    }

    /** Queue setting the steam dryer or the separator assembly back into the vessel from the pad that holds it. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> queueInternalIn(String name) throws LuaException {
        Component why = be.queueInternal(internal(name), false);
        return result(why == null, why);
    }

    @LuaFunction(mainThread = true)
    public final void abort() {
        be.abort();
    }

    @LuaFunction(mainThread = true)
    public final void resume() {
        be.resume();
    }
}
