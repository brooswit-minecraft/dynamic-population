package io.github.brooswitminecraft.dynamicpopulation.client;

import io.github.brooswitminecraft.dynamicpopulation.entity.VillagerKingEntity;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Reuses the vanilla (non-nitwit) villager visuals, scaled up via the SCALE attribute -- a King is a real villager-looking figure, not a nitwit one, to read as distinct from ordinary Fillagers. */
public final class VillagerKingRenderer extends MobRenderer<VillagerKingEntity, VillagerModel<VillagerKingEntity>> {
    private static final ResourceLocation BASE =
        ResourceLocation.withDefaultNamespace("textures/entity/villager/villager.png");

    public VillagerKingRenderer(EntityRendererProvider.Context context) {
        super(context, new VillagerModel<>(context.bakeLayer(ModelLayers.VILLAGER)), 0.6F);
    }

    @Override
    public ResourceLocation getTextureLocation(VillagerKingEntity entity) {
        return BASE;
    }
}
