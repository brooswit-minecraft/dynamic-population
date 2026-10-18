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
        if (level().isClientSide) {
            return;
        }
        BlockPos pos = blockPosition();
        // Overwritten every tick, never OR-latched: a wither rose only stings on contact, then lets its
        // damage-over-time effect finish the kill ~2s later, so this flag must track CURRENT presence, not
        // "was ever on a hazard" -- otherwise a Fillager that survives stepping off the rose would carry a
        // stale true into a later, unrelated death (e.g. a player or mob kill) and misattribute it as AC9.
        // Lava/fire/soul-fire are the opposite case (instant kill, same tick), so overwriting changes nothing
        // for them.
        boolean lethal = isLethalHazard(pos);
        unsafeLocationDeath = lethal || isOnWitherRose(pos);
        if (lethal) {
            hurt(damageSources().generic(), Float.MAX_VALUE);
        }
    }

    private boolean isLethalHazard(BlockPos pos) {
        var state = level().getBlockState(pos);
        return state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) || state.is(net.minecraft.world.level.block.Blocks.FIRE)
            || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE);
    }

    private boolean isOnWitherRose(BlockPos pos) {
        return level().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.WITHER_ROSE);
    }

    /**
     * Whether this Fillager is (as of this tick) standing on an unsafe location, for AC9. Deliberately
     * location-based rather than damage-type-based for wither rose: {@code DamageTypes.WITHER} is also the
     * damage dealt by the Wither status effect from a wither skeleton or the Wither boss, which must NOT
     * count as a local-cell hazard death.
     */
    public boolean wasUnsafeLocationDeath() {
        return unsafeLocationDeath;
    }
}
