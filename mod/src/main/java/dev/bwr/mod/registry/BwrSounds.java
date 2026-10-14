package dev.bwr.mod.registry;

import dev.bwr.mod.BwrMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Stable sound IDs with silent L4 assets. Playback and synthesized audio belong to Phase Q. */
public final class BwrSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, BwrMod.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> ANNUNCIATOR_HORN = register("annunciator_horn");
    public static final DeferredHolder<SoundEvent, SoundEvent> ALARM_ACKNOWLEDGE = register("alarm_acknowledge");
    public static final DeferredHolder<SoundEvent, SoundEvent> RELAY_CLICK = register("relay_click");
    public static final DeferredHolder<SoundEvent, SoundEvent> CONTACTOR_CLICK = register("contactor_click");
    public static final DeferredHolder<SoundEvent, SoundEvent> CONTROL_SWITCH_DETENT = register("control_switch_detent");
    public static final DeferredHolder<SoundEvent, SoundEvent> BREAKER_CLOSE = register("breaker_close");
    public static final DeferredHolder<SoundEvent, SoundEvent> BREAKER_TRIP = register("breaker_trip");
    public static final DeferredHolder<SoundEvent, SoundEvent> PUMP_MOTOR = register("pump_motor");
    public static final DeferredHolder<SoundEvent, SoundEvent> TURBINE_WHINE = register("turbine_whine");
    public static final DeferredHolder<SoundEvent, SoundEvent> STEAM_FLOW = register("steam_flow");
    public static final DeferredHolder<SoundEvent, SoundEvent> SRV_LIFT = register("srv_lift");
    public static final DeferredHolder<SoundEvent, SoundEvent> MSIV_SLAM = register("msiv_slam");
    public static final DeferredHolder<SoundEvent, SoundEvent> SCRAM_BUZZER = register("scram_buzzer");
    public static final DeferredHolder<SoundEvent, SoundEvent> EDG_START = register("edg_start");
    public static final DeferredHolder<SoundEvent, SoundEvent> EDG_RUN = register("edg_run");
    public static final DeferredHolder<SoundEvent, SoundEvent> GEIGER_CLICK = register("geiger_click");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String path) {
        return SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(BwrMod.id(path)));
    }

    private BwrSounds() { }
}
