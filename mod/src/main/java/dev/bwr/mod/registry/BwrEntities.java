package dev.bwr.mod.registry;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.accident.ReactorHeadEntity;
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

    private BwrEntities() {
    }
}
