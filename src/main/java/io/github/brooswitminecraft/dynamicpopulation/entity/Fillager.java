package io.github.brooswitminecraft.dynamicpopulation.entity;

import io.github.brooswitminecraft.dynamicpopulation.combat.PopulationCombatTargetGoal;
import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.reconciliation.FillagerPromotion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * A deliberately separate, lightweight population entity. It has no Villager brain, profession, trades, or
 * persistent history; the client presentation is intentionally the nitwit-shaped villager presentation only.
 *
 * <p>Deliberately does NOT extend {@code net.minecraft.world.entity.npc.AbstractVillager}: that would force
 * it to implement {@code Merchant} and drag in trading machinery this mod explicitly keeps out. See
 * {@code io.github.brooswitminecraft.dynamicpopulation.combat.FillagerTargetCompat} for how vanilla villager
 * attackers are still made to target this entity despite that, without any shared supertype.
 */
public final class Fillager extends PathfinderMob {
    private boolean unsafeLocationDeath;

    /**
     * Hurt-triggered flee (MINECRAFT-389, AC2): the EXACT vanilla {@link PanicGoal} sheep/cow/pig already
     * use, unmodified -- no custom flee state machine, since vanilla's own is already a well-tested,
     * lightweight animal-style navigation/avoidance goal and the ticket names it as the reference. Its
     * default trigger set is {@code DamageTypeTags.PANIC_CAUSES} (player/mob melee, projectiles, explosions,
     * magic, etc. -- confirmed against the vanilla data pack, NOT the self-inflicted {@code generic} source
     * {@link #isLethalHazard} uses for an instant hazard kill, which is correct: that case never leaves a
     * survivor to flee). {@code PanicGoal} only ever touches navigation/movement (its one {@code Goal.Flag} is
     * {@code MOVE}); it never sets a combat target, so a Fillager (which also gets NO {@code targetSelector}
     * goals at all, below) structurally cannot retaliate. Priority 1 (goalSelector's highest), matching every
     * vanilla animal's own placement, so fleeing always preempts any other movement goal.
     *
     * <p>Speed modifier 1.25 matches vanilla Sheep/Pig (Cow uses 2.0; Chicken uses 1.4) -- chosen over Cow's
     * higher value because a Fillager is villager-shaped/human-sized, not ox-shaped, and 1.25 is the more
     * common vanilla choice for a similarly-proportioned animal. No specific number was mandated by the
     * ticket.
     *
     * <p>Recovery, blocked-route, and attacker-removal handling all come from vanilla's own
     * {@code PanicGoal}/navigation unmodified: {@code canContinueToUse()} ends the panic the moment
     * navigation completes or fails to find a path (a cornered Fillager simply stays put, like a cornered
     * animal), and the goal never reads or dereferences the attacking entity at all -- only
     * {@code getLastDamageSource()}'s damage-type tag membership -- so the attacker's death, removal, or a
     * chunk/entity reload cannot leave it in a broken state.
     */
    private static final double PANIC_SPEED_MODIFIER = 1.25;

    public Fillager(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.xpReward = 0;
    }

    public static EntityType.Builder<Fillager> typeBuilder() {
        return EntityType.Builder.of(Fillager::new, MobCategory.CREATURE).sized(0.6F, 1.95F).clientTrackingRange(8);
    }

    /**
     * MINECRAFT-390 scope item 6: the ONE narrowly-scoped exception to MINECRAFT-389's "never retaliate or
     * acquire a combat target" rule -- a {@code targetSelector} goal ({@link PopulationCombatTargetGoal})
     * that can only ever acquire a target when THIS Fillager's own local Villager-faction density is at or
     * above {@code PopulationCombatEligibility.thresholdFor(Faction.VILLAGER)} -- corrected by MINECRAFT-426
     * to 0 on the existing 0..1000 scale (i.e. eligible at ANY density, including exactly zero; this was
     * originally 500 like every other faction, see {@code PopulationCombatEligibility}'s own class doc for
     * the per-faction split), and only against an opposing-faction NON-PERSISTENT filler (see
     * {@code PopulationCombatTargeting}). Below that threshold the goal's own {@code canUse()} always
     * returns false, so a Fillager that never crosses it behaves EXACTLY as MINECRAFT-389 specified: it
     * still never retaliates or acquires a target -- though since MINECRAFT-426 that floor is never actually
     * above zero for this faction any more. This is additive only -- it does not touch
     * {@code FillagerTargetCompat} (other mobs targeting a Fillager) or loosen any check in
     * {@link PanicGoal} itself.
     *
     * <p>{@link MeleeAttackGoal} is the attack-EXECUTION half this goal needs but Fillager never had before
     * (a {@code targetSelector} goal only sets {@code getTarget()}; nothing walks toward or swings at that
     * target without a {@code goalSelector} goal that reads it) -- see {@link PopulationCombatTargetGoal}'s
     * own class doc for the full goal-priority writeup (PanicGoal at goalSelector priority 1 vs this at
     * priority 2, sharing the {@code MOVE} flag, so fleeing always preempts attacking whenever both could
     * apply).
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new PanicGoal(this, PANIC_SPEED_MODIFIER));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, true));
        this.targetSelector.addGoal(1, new PopulationCombatTargetGoal(this, Faction.VILLAGER));
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
