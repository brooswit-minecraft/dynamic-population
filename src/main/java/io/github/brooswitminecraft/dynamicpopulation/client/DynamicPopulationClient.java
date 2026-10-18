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
        event.registerEntityRenderer(DynamicPopulationEntities.VILLAGER_KING.get(), VillagerKingRenderer::new);
        // Zombie/Skeleton Kings extend the vanilla classes directly, so vanilla's own rendering assets
        // (generic.scale alone drives the bigger size -- see DynamicPopulationEntities) are reused via a
        // thin renderer mirroring vanilla's own constructor (ZombieRenderer itself is not generic, so
        // ZombieKingRenderer reuses its generic AbstractZombieRenderer base instead; SkeletonRenderer IS
        // already generic over AbstractSkeleton, so it is reused directly).
        event.registerEntityRenderer(DynamicPopulationEntities.ZOMBIE_KING.get(), ZombieKingRenderer::new);
        event.registerEntityRenderer(DynamicPopulationEntities.SKELETON_KING.get(),
            net.minecraft.client.renderer.entity.SkeletonRenderer::new);
    }
}
