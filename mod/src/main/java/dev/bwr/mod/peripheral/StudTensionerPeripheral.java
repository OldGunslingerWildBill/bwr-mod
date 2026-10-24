package dev.bwr.mod.peripheral;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dev.bwr.core.refuelling.CraneMotion;
import dev.bwr.mod.reactor.ReactorControllerBlockEntity;
import dev.bwr.mod.reactor.StudTensionerBlockEntity;
import dev.bwr.mod.text.BwrText;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CC:Tweaked peripheral for the stud tensioner's power pack: start a detensioning or a retensioning pass, stop it, read where it is.
 * Nothing starts unless the program says so. Every method runs on the server thread.
 */
public class StudTensionerPeripheral implements IPeripheral {

    private final StudTensionerBlockEntity be;

    public StudTensionerPeripheral(StudTensionerBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return "bwr_stud_tensioner";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof StudTensionerPeripheral p && p.be == this.be;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    private static Map<String, Object> result(@Nullable Component why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", why == null);
        if (why != null) {
            m.put("message", BwrText.english(why));
        }
        return m;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        ReactorControllerBlockEntity r = be.findReactor();
        m.put("reactorFound", r != null);
        m.put("mode", be.mode().name().toLowerCase(Locale.ROOT));
        m.put("running", be.running());
        m.put("studProgress", be.studProgress());
        m.put("energyFE", be.energyFe());
        m.put("capacityFE", StudTensionerBlockEntity.FE_CAPACITY);
        if (r != null) {
            m.put("studTotal", r.studTotal());
            m.put("studsTensioned", r.studsTensioned());
            m.put("studsDetensioned", r.studsOff());
            m.put("secondsPerStudDetension", CraneMotion.DETENSION_TICKS_PER_STUD / 20.0);
            m.put("secondsPerStudRetension", CraneMotion.RETENSION_TICKS_PER_STUD / 20.0);
        }
        if (be.message() != null) {
            m.put("message", BwrText.english(be.message()));
        }
        return m;
    }

    /** Start a pass backing every stud's nut off (the head must be on, the vessel at 25 psig or less). */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> detension() {
        return result(be.start(true));
    }

    /** Start a pass bringing every stud to load (the head must be back on the flange). */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> retension() {
        return result(be.start(false));
    }

    @LuaFunction(mainThread = true)
    public final void stop() {
        be.stop();
    }
}
