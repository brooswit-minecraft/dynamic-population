package io.github.brooswitminecraft.dynamicpopulation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.brooswitminecraft.dynamicpopulation.entity.Fillager;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

/** Reuses vanilla nitwit visuals without inheriting any villager gameplay. */
public final class FillagerRenderer extends MobRenderer<Fillager, VillagerModel<Fillager>> {
    private static final ResourceLocation BASE =
        ResourceLocation.withDefaultNamespace("textures/entity/villager/villager.png");
    private static final ResourceLocation NITWIT =
        ResourceLocation.withDefaultNamespace("textures/entity/villager/profession/nitwit.png");

    public FillagerRenderer(EntityRendererProvider.Context context) {
        super(context, new VillagerModel<>(context.bakeLayer(ModelLayers.VILLAGER)), 0.5F);
        addLayer(new NitwitLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(Fillager entity) {
        return BASE;
    }

    private static final class NitwitLayer extends RenderLayer<Fillager, VillagerModel<Fillager>> {
        private NitwitLayer(RenderLayerParent<Fillager, VillagerModel<Fillager>> renderer) {
            super(renderer);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight, Fillager entity,
                float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                float netHeadYaw, float headPitch) {
            if (!entity.isInvisible()) {
                renderColoredCutoutModel(getParentModel(), NITWIT, poseStack, buffers, packedLight, entity, -1);
            }
        }
    }
}
