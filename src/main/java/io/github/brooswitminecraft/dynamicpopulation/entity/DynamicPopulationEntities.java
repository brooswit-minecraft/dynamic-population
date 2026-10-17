package io.github.brooswitminecraft.dynamicpopulation.entity;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

/** Entity registration stays server-safe; a client renderer may reuse only nitwit visual assets. */
public final class DynamicPopulationEntities {
    private DynamicPopulationEntities() { }

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE,
        DynamicPopulationMod.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<Fillager>> FILLAGER = ENTITIES.register("fillager",
        () -> Fillager.typeBuilder().build("fillager"));

    public static void register(IEventBus modEventBus) {
        ENTITIES.register(modEventBus);
        modEventBus.addListener(DynamicPopulationEntities::createAttributes);
    }
    private static void createAttributes(EntityAttributeCreationEvent event) {
        event.put(FILLAGER.get(), Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.5D)
            .add(Attributes.FOLLOW_RANGE, 16.0D).build());
    }
}
