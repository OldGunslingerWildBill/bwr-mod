package dev.bwr.mod.registry;

import dev.bwr.mod.BwrMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Stable sound IDs: silent L4 placeholders, the synthesised rumbles (RUMBLE.md) and the synthesised turbine accident sounds (TurbineSounds). */
public final class BwrSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, BwrMod.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> ANNUNCIATOR_HORN = register("annunciator_horn");
    /** The synthesised horn an annunciator window can sound instead of the panel alarm (tools-make-annunciator-sounds.py). */
    public static final DeferredHolder<SoundEvent, SoundEvent> ANNUNCIATOR_HORN_SYNTH = register("annunciator_horn_synth");
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
    /** Looping deep rumbles of the plant (RUMBLE.md): steam condensing in the pool, and a shaking turbine train. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RUMBLE_POOL = register("rumble_pool");
    public static final DeferredHolder<SoundEvent, SoundEvent> RUMBLE_TURBINE = register("rumble_turbine");
    /** Turbine accidents (TURBINE-MAINTENANCE.md, "Sounds"): synthesised by tools-make-turbine-sounds.py, played by TurbineSounds. */
    public static final DeferredHolder<SoundEvent, SoundEvent> TURBINE_MISSILE_BANG = register("turbine_missile_bang");
    public static final DeferredHolder<SoundEvent, SoundEvent> TURBINE_MISSILE_IMPACT = register("turbine_missile_impact");
    /** The distant (air-absorbed) and muffled (behind walls) variants of the two blasts that carry for kilometres, played by BlastSounds. */
    public static final DeferredHolder<SoundEvent, SoundEvent> TURBINE_MISSILE_BANG_DISTANT = register("turbine_missile_bang_distant");
    public static final DeferredHolder<SoundEvent, SoundEvent> TURBINE_MISSILE_BANG_MUFFLED = register("turbine_missile_bang_muffled");
    public static final DeferredHolder<SoundEvent, SoundEvent> TURBINE_EXCURSION = register("turbine_excursion");
    public static final DeferredHolder<SoundEvent, SoundEvent> TURBINE_EXCURSION_DISTANT = register("turbine_excursion_distant");
    public static final DeferredHolder<SoundEvent, SoundEvent> TURBINE_EXCURSION_MUFFLED = register("turbine_excursion_muffled");
    public static final DeferredHolder<SoundEvent, SoundEvent> TURBINE_OVERSPEED_WHINE = register("turbine_overspeed_whine");
    public static final DeferredHolder<SoundEvent, SoundEvent> TURBINE_FIRE_OIL = register("turbine_fire_oil");
    public static final DeferredHolder<SoundEvent, SoundEvent> TURBINE_FIRE_HYDROGEN = register("turbine_fire_hydrogen");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String path) {
        return SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(BwrMod.id(path)));
    }

    private BwrSounds() { }
}
