package io.github.brooswitminecraft.dynamicpopulation.entity;

import io.github.brooswitminecraft.dynamicpopulation.combat.KingHostilityTargetGoal;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.level.Level;

/**
 * MINECRAFT-390: the Skeleton King -- the Skeleton-faction counterpart to {@link ZombieKingEntity}. See that
 * class's javadoc for the full rationale (extends-vanilla-directly design, unconditional sun immunity,
 * stat-choice reasoning); everything there applies here with Skeleton in place of Zombie. Skeleton's own
 * sun-burn override lives on {@code AbstractSkeleton} (the Zombie-side name, {@code isSunBurnTick}, is
 * mirrored there under the same name), so the override below is identical in shape.
 */
public final class SkeletonKingEntity extends Skeleton {
    public SkeletonKingEntity(EntityType<? extends Skeleton> type, Level level) {
        super(type, level);
        this.xpReward = 0;
        this.setPersistenceRequired();
    }

    public static EntityType.Builder<SkeletonKingEntity> typeBuilder() {
        return EntityType.Builder.of(SkeletonKingEntity::new, net.minecraft.world.entity.MobCategory.MISC)
            .sized(0.9F, 2.9F).clientTrackingRange(10);
    }

    @Override
    public boolean isPersistenceRequired() {
        return true;
    }

    @Override
    protected boolean isSunBurnTick() {
        return false;
    }

    /**
     * MINECRAFT-416: see {@code ZombieKingEntity#registerGoals}'s identical-shape override for the full
     * rationale -- unmodified vanilla Skeleton AI (ranged bow attack, player targeting, etc.) plus
     * {@link KingHostilityTargetGoal} at targetSelector priority 0 for cross-type King hostility.
     */
    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.targetSelector.addGoal(0, new KingHostilityTargetGoal(this, Faction.SKELETON));
    }
}
