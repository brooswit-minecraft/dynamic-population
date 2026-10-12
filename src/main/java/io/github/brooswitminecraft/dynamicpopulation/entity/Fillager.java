package io.github.brooswitminecraft.dynamicpopulation.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

/**
 * A deliberately separate, lightweight population entity. It has no Villager brain, profession, trades, or
 * persistent history; the client presentation is intentionally the nitwit-shaped villager presentation only.
 */
public final class Fillager extends PathfinderMob {
    private boolean unsafeLocationDeath;

    public Fillager(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.xpReward = 0;
    }

    public static EntityType.Builder<Fillager> typeBuilder() {
        return EntityType.Builder.of(Fillager::new, MobCategory.CREATURE).sized(0.6F, 1.95F).clientTrackingRange(8);
    }

    @Override
    protected void registerGoals() {
        // Fillagers are background population, not AI villagers.
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!level().isClientSide && isUnsafe(blockPosition())) {
            unsafeLocationDeath = true;
            hurt(damageSources().generic(), Float.MAX_VALUE);
        }
    }

    private boolean isUnsafe(BlockPos pos) {
        var state = level().getBlockState(pos);
        return state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) || state.is(net.minecraft.world.level.block.Blocks.FIRE)
            || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE);
    }

    public boolean wasUnsafeLocationDeath() {
        return unsafeLocationDeath;
    }
}
