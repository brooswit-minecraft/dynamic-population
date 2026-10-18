package io.github.brooswitminecraft.dynamicpopulation.entity;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.reconciliation.FillagerPromotion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
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
     * Promotion economy (AC7): giving the live-configured number of emeralds converts this Fillager into a
     * normal, permanent villager. {@link FillagerPromotion#canPromote} is the pure, unit-tested threshold
     * check; everything below it is Minecraft wiring that cannot be unit-tested without a running game.
     *
     * <p>Deliberately NEVER touches any cell's target density, directly or otherwise -- see the fixed
     * decision in the ticket and {@code FillagerPromotion}'s own doc. {@link #convertTo} already removes
     * this Fillager (it calls {@code discard()} internally), exactly like an ordinary despawn-over-target,
     * so the only felt cost is the bounded refill rate the reconciliation loop already enforces on the next
     * interval -- there is no separate "leave a gap" step to perform here.
     *
     * <p>The eligibility check (and therefore whether this returns a definitive result or falls through to
     * {@code super}/PASS) MUST run identically on both sides, before any client/server branching: per
     * NeoForge's interaction pipeline, ANY non-PASS {@code mobInteract} result -- including a client-side
     * one -- is definitive and stops the game from then trying the other hand. An empty or below-cost main
     * hand must therefore still return PASS (via {@code super}) on the client too, so a valid offhand
     * emerald stack still gets its turn; unconditionally returning SUCCESS on the client regardless of what
     * is held (the review-flagged bug this replaces) would silently swallow that offhand attempt.
     */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        int cost = DynamicPopulationConfig.get().promotionEmeraldCost();
        if (!FillagerPromotion.canPromote(stack.is(Items.EMERALD), stack.getCount(), cost)) {
            return super.mobInteract(player, hand);
        }
        if (!level().isClientSide) {
            stack.shrink(cost);
            promote((ServerLevel) level());
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    private void promote(ServerLevel level) {
        Villager villager = convertTo(EntityType.VILLAGER, false);
        if (villager != null) {
            villager.finalizeSpawn(level, level.getCurrentDifficultyAt(villager.blockPosition()), MobSpawnType.CONVERSION, null);
        }
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
