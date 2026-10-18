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
     * <p>The CLIENT must never evaluate the emerald threshold itself, in either direction: per NeoForge's
     * interaction pipeline, ANY non-PASS {@code mobInteract} result -- including a client-side one -- is
     * definitive, which also decides (via Minecraft's own main-hand/off-hand fallback loop, run once per
     * side) which single hand even gets sent to the server at all. NeoForge's automatic config sync only
     * runs at connection time; a later server-side live reload of {@code fillager_promotion_emerald_cost}
     * (this config's whole point -- it is {@code RestartType.NONE}) is NOT re-pushed to an already-connected
     * client, so the client's cached cost can silently diverge from the server's authoritative one for the
     * rest of that session. If the client used that possibly-stale cost to decide "this hand already
     * succeeded," it would never even try (or send to the server) a DIFFERENT hand the server's real cost
     * would have accepted -- an empty/below-(stale-)cost main hand wrongly ending the attempt, or a
     * below-(stale-)cost-looking main hand that the server would actually have accepted never getting sent
     * because the client thought it needed the offhand instead. Always deferring (PASS) on the client makes
     * every hand attempt reach the server regardless, which alone holds the authoritative cost and performs
     * the real consumption -- correctness no longer depends on the two sides agreeing on a config value at
     * all.
     */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!FillagerPromotion.shouldEvaluateOnThisSide(!level().isClientSide)) {
            return super.mobInteract(player, hand);
        }
        var stack = player.getItemInHand(hand);
        int cost = DynamicPopulationConfig.get().promotionEmeraldCost();
        if (!FillagerPromotion.canPromote(stack.is(Items.EMERALD), stack.getCount(), cost)) {
            return super.mobInteract(player, hand);
        }
        stack.shrink(cost);
        promote((ServerLevel) level());
        return InteractionResult.SUCCESS;
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
