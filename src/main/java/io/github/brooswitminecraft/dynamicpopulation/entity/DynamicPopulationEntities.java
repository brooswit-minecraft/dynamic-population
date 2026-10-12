package io.github.brooswitminecraft.dynamicpopulation.entity;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
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
    }
}
