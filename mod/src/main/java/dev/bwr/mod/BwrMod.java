package dev.bwr.mod;

import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.registry.BwrItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

/**
 * Entry point for the Realistic BWR mod.
 *
 * <p>The mod provides hardware and physics. It does not provide control logic.
 * There is no built-in scram, no automatic ECCS actuation, no trip setpoints
 * and no protection system anywhere in this codebase — the player writes all of
 * that themselves in CC:Tweaked Lua. See {@code README.md} and {@code SPEC.md}
 * section 9.
 *
 * <p>Architecturally everything physical lives in the {@code :core} module,
 * which is plain Java with no Minecraft on its classpath at all. This module is
 * the shell: blocks, block entities, structure validation, persistence, sync
 * and the peripheral surface.
 */
@Mod(BwrMod.MOD_ID)
public final class BwrMod {

    public static final String MOD_ID = "bwr";

    /** CC:Tweaked is optional at runtime. Everything CC-facing checks this first. */
    public static final String CC_MOD_ID = "computercraft";

    /** Mekanism is optional at runtime. Everything Mekanism-facing checks this first. */
    public static final String MEKANISM_MOD_ID = "mekanism";

    private static boolean computerCraftPresent;

    private static boolean mekanismPresent;

    public BwrMod(IEventBus modBus, ModContainer container) {
        BwrBlocks.BLOCKS.register(modBus);
        BwrItems.ITEMS.register(modBus);
        BwrItems.TABS.register(modBus);
        BwrBlockEntities.BLOCK_ENTITIES.register(modBus);
        dev.bwr.mod.registry.BwrParticles.PARTICLES.register(modBus);
        dev.bwr.mod.registry.BwrMenus.MENUS.register(modBus);
        dev.bwr.mod.fuel.BwrDataComponents.DATA_COMPONENTS.register(modBus);

        // The two packets every screen runs on: a snapshot down, a command up.
        // Both handlers only touch Player#containerMenu, which is common code,
        // so nothing here is dist-sensitive.
        modBus.addListener(
                net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent.class,
                dev.bwr.mod.gui.net.BwrGuiNetwork::register);

        // The fabricator's output slot and energy buffer are plain NeoForge and
        // exist on every install; only its chemical tanks need Mekanism.
        modBus.addListener(
                net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent.class,
                dev.bwr.mod.fuel.FuelCapabilities::register);

        // Emergency core cooling (SPEC section 9): energy for the motor-driven
        // machines, a water tank on the condensate storage tank. Plain NeoForge,
        // no optional dependency involved, so it registers unconditionally.
        modBus.addListener(
                net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent.class,
                dev.bwr.mod.eccs.EccsCapabilities::register);

        // The SPEC 2.1 fuel registry: data/<namespace>/fuel_type/*.json, reloaded
        // with the rest of the datapack. This is on the game bus, not the mod
        // bus, because it is server data and it happens again on every /reload.
        modBus.addListener(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent.class,
                dev.bwr.mod.power.PowerModuleCapabilities::register);
        modBus.addListener(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent.class,
                dev.bwr.mod.condenser.CondenserCapabilities::register);
        modBus.addListener(dev.bwr.mod.cooling.CoolingCapabilities::register);
        modBus.addListener(dev.bwr.mod.suppression.SuppressionWaterCapabilities::register);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.AddReloadListenerEvent.class,
                event -> event.addListener(new dev.bwr.mod.fuel.FuelTypeLoader()));

        computerCraftPresent = ModList.get().isLoaded(CC_MOD_ID);
        mekanismPresent = ModList.get().isLoaded(MEKANISM_MOD_ID);

        // Peripheral support is attached only when CC:Tweaked is actually
        // installed. The check lives here rather than inside the support class
        // so the JVM never has to resolve CC types on a CC-less install.
        if (computerCraftPresent) {
            modBus.addListener(
                    net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent.class,
                    dev.bwr.mod.peripheral.BwrPeripheralSupport::registerCapabilities);
        }

        // Same treatment for the Mekanism steam boundary, and for the same
        // reason: dev.bwr.mod.mekanism is the only package that names a
        // Mekanism type, and nothing outside this branch mentions it.
        if (mekanismPresent) {
            modBus.addListener(
                    net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent.class,
                    dev.bwr.mod.mekanism.BwrMekanismSupport::registerCapabilities);

            // The fuel boundary (SPEC 2.4). Same rule: this branch is the only
            // place that names MekanismFuelFeed, so a Mekanism-less JVM never
            // loads it and the fabricator falls back to FuelFeed.NONE.
            dev.bwr.mod.fuel.MekanismFuelFeed.install();
            modBus.addListener(
                    net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent.class,
                    dev.bwr.mod.fuel.MekanismFuelFeed::registerCapabilities);
        }
    }

    /**
     * Whether CC:Tweaked is installed.
     *
     * <p>Guards every peripheral touchpoint. The mod must load and run correctly
     * without CC:Tweaked present — the API is a compileOnly dependency and is
     * deliberately never bundled, because parts of it (notably
     * {@code IPeripheral}) remain under LicenseRef-CCPL, which permits
     * redistribution only unmodified and in full.
     */
    public static boolean isComputerCraftPresent() {
        return computerCraftPresent;
    }

    /**
     * Whether Mekanism is installed.
     *
     * <p>Guards the steam boundary. Mekanism is declared optional and the mod
     * must load and run perfectly without it: the turbine steam outlet still
     * places, ticks and reports, it simply has nothing to hand its steam to and
     * therefore delivers none. The reactor then behaves exactly as it would with
     * the turbine stop valves shut — pressure rises, and what to do about that
     * is the player's problem, as always.
     */
    public static boolean isMekanismPresent() {
        return mekanismPresent;
    }

    /** Namespaced resource path helper. */
    public static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
