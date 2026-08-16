package dev.bwr.mod.rods;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.registry.BwrBlockEntities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * The two supplies a control rod drive needs, exposed to cables and pipes —
 * {@code SPEC.md} section 3.3.
 *
 * <p>A CRD needs <b>both</b> electrical power and pumped demineralised water.
 * Power runs the directional control valves; water is the working fluid and the
 * continuous purge flow into the vessel. Losing either stops normal rod motion,
 * and losing water additionally stops the accumulator recharging, so scram
 * capability decays even with the bus energised. None of that can happen unless
 * the buffers can be filled in the first place, which is what this class is for:
 * {@link ControlRodDriveBlockEntity} tick spends the supplies, and without a
 * registered capability there was no way to put any back, so enabling the tick
 * without this would seize every drive in the plant after about three minutes.
 *
 * <p>Registered from an {@code @EventBusSubscriber} rather than from
 * {@code BwrMod}'s constructor, unlike the ECCS and fuel capabilities. Both wire
 * up identically at runtime; this form keeps the registration in the same
 * package as the block entity it registers, which is where the next person will
 * look for it. Plain NeoForge throughout — nothing here is optional-dependency
 * sensitive, so it registers unconditionally on every install.
 *
 * <p>Nothing here decides anything. It moves energy and water across a boundary
 * and forms no opinion about whether there is enough of either.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID)
public final class ControlRodDriveCapabilities {

    private ControlRodDriveCapabilities() {
    }

    // No bus() on the annotation: RegisterCapabilitiesEvent is an IModBusEvent
    // and NeoForge routes it by type. Naming a bus is deprecated for removal.
    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                BwrBlockEntities.CONTROL_ROD_DRIVE.get(),
                (be, side) -> be.energy());

        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                BwrBlockEntities.CONTROL_ROD_DRIVE.get(),
                (be, side) -> be.water());
    }
}
