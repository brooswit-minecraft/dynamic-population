package io.github.brooswitminecraft.dynamicpopulation.client;

import io.github.brooswitminecraft.dynamicpopulation.entity.ZombieKingEntity;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.AbstractZombieRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * Mirrors vanilla {@code ZombieRenderer}'s own constructor exactly (same model layers/armor layers), just
 * parameterized over {@link ZombieKingEntity} instead of the fixed {@code Zombie} -- vanilla's
 * {@code ZombieRenderer} itself is not generic, so it cannot be reused directly for a subclass; its
 * generic base {@link AbstractZombieRenderer} can.
 */
public final class ZombieKingRenderer extends AbstractZombieRenderer<ZombieKingEntity, ZombieModel<ZombieKingEntity>> {
    public ZombieKingRenderer(EntityRendererProvider.Context context) {
        super(context, new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE)),
            new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE_INNER_ARMOR)),
            new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE_OUTER_ARMOR)));
    }
}
