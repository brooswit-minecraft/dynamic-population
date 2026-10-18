package io.github.brooswitminecraft.dynamicpopulation.client;

import io.github.brooswitminecraft.dynamicpopulation.DynamicPopulationMod;
import io.github.brooswitminecraft.dynamicpopulation.entity.DynamicPopulationEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Loaded only on clients; dedicated servers never link renderer classes. */
@EventBusSubscriber(modid = DynamicPopulationMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class DynamicPopulationClient {
    private DynamicPopulationClient() { }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(DynamicPopulationEntities.FILLAGER.get(), FillagerRenderer::new);
    }
}
