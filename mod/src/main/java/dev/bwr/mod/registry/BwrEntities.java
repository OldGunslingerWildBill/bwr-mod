package dev.bwr.mod.registry;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.accident.ReactorHeadEntity;
import dev.bwr.mod.refuelling.PlugSectionEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BwrEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, BwrMod.MOD_ID);

    /** The vessel head thrown by a head failure. The size is set per head (see {@link ReactorHeadEntity#getDimensions}). */
    public static final DeferredHolder<EntityType<?>, EntityType<ReactorHeadEntity>> REACTOR_HEAD =
            ENTITIES.register("reactor_head", () -> EntityType.Builder.<ReactorHeadEntity>of(ReactorHeadEntity::new, MobCategory.MISC)
                    .sized(4.0f, 1.0f).clientTrackingRange(16).updateInterval(1).fireImmune().build("reactor_head"));

    /** A shield plug section hanging from the reactor building crane's hook (see {@link PlugSectionEntity}). Cannot be summoned. */
    public static final DeferredHolder<EntityType<?>, EntityType<PlugSectionEntity>> PLUG_SECTION =
            ENTITIES.register("plug_section", () -> EntityType.Builder.<PlugSectionEntity>of(PlugSectionEntity::new, MobCategory.MISC)
                    .sized(1.0f, 1.0f).clientTrackingRange(16).updateInterval(1).fireImmune().noSummon().build("plug_section"));

    private BwrEntities() {
    }
}
