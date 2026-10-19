package io.github.brooswitminecraft.dynamicpopulation.combat;

import java.util.EnumSet;

import javax.annotation.Nullable;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;

/**
 * MINECRAFT-416: the target-acquisition half of cross-type King-vs-King hostility. Installed as a
 * {@code targetSelector} goal on all three King entities -- see {@code VillagerKingEntity#registerGoals},
 * {@code ZombieKingEntity#registerGoals} and {@code SkeletonKingEntity#registerGoals}.
 *
 * <p><b>Review fix (P1):</b> the original version acquired a target via a hand-rolled AABB scan and only
 * re-validated it on continuation with its own {@code isAlive()} check -- bypassing every normal combat
 * condition ({@code TargetGoal}'s own team/alliance exclusion, distance-to-{@code FOLLOW_RANGE} bound, and
 * optional line-of-sight memory), so a King could lock onto an ally (if ever teamed) or an opposing King
 * that had long since walked out of range, starving other targeting indefinitely. This version instead
 * follows the exact composition {@code NearestAttackableTargetGoal} itself uses:
 * {@code TargetingConditions.forCombat()} (alive/not-spectator, {@code canAttack}/{@code canAttackType},
 * team/alliance exclusion, visibility-adjusted range, line-of-sight) with {@link KingHostilityTargeting}'s
 * cross-type predicate plugged in as the narrow {@code selector} -- and it no longer overrides
 * {@link #canContinueToUse} at all, relying on {@code TargetGoal}'s own base implementation (which re-checks
 * {@code mob.canAttack(target)}, team, {@code FOLLOW_RANGE} distance, and -- since this goal passes
 * {@code mustSee = true} to the superclass constructor -- line-of-sight with a 60-tick "recently seen"
 * memory window, same default every vanilla {@code mustSee} target goal uses) every evaluation cycle. A
 * dead, removed, out-of-range, or long-unseen opposing King is therefore dropped as a target automatically,
 * the same tick-granular way vanilla's own player-targeting goal already behaves -- no separate "clear stale
 * targets" sweep is needed, and no synthetic King can survive a reload.
 *
 * <p>Deliberately still has no density gate at all (unlike {@link PopulationCombatTargetGoal}'s
 * {@code PopulationCombatEligibility} check) and no attacker-persistence gate (every King is always
 * persistence-required; that fact is irrelevant here, unlike for an ordinary filler). {@code mustReach}
 * (vanilla's own pathfinding-reach cache, see {@code TargetGoal}) is deliberately NOT used -- vanilla's own
 * primary {@code NearestAttackableTargetGoal<Player>} doesn't use it either; this goal's distance/visibility
 * conditions already bound it to "can plausibly reach", and a dedicated reach-cache was judged
 * disproportionate tuning the ticket does not mandate ("no new pathfinding/reachability system").
 *
 * <p><b>Priority choice:</b> installed at priority 0 (the highest -- evaluated, and therefore preferred over
 * same-flag goals, before anything else) on every King's {@code targetSelector}. On
 * {@code VillagerKingEntity} there are no other {@code targetSelector} goals to collide with. On
 * {@code ZombieKingEntity}/{@code SkeletonKingEntity}, vanilla Zombie/Skeleton already populate
 * {@code targetSelector} with goals such as {@code HurtByTargetGoal} and a player-targeting
 * {@code NearestAttackableTargetGoal} at their own vanilla priorities; priority 0 guarantees this goal is
 * always checked first without needing to know or renumber those vanilla priorities, while still falling
 * through to them unchanged whenever no opposing King is in range -- satisfying the ticket's "demonstrate
 * initiation without first being hit" requirement without disabling vanilla retaliation/player-targeting
 * when no opposing King is present.
 *
 * <p>Range is the King's own live {@code Attributes.FOLLOW_RANGE} value ({@link #getFollowDistance}, the
 * same accessor {@code TargetGoal} itself uses) rather than a new hardcoded constant, so it naturally
 * matches whatever follow range each King's attribute map already declares (see
 * {@code DynamicPopulationEntities#createAttributes}) instead of introducing a second, possibly-inconsistent
 * number.
 */
public final class KingHostilityTargetGoal extends TargetGoal {
    private final TargetingConditions targetConditions;

    @Nullable
    private LivingEntity target;

    public KingHostilityTargetGoal(Mob mob, Faction ownFaction) {
        super(mob, true);
        this.setFlags(EnumSet.of(Flag.TARGET));
        this.targetConditions = TargetingConditions.forCombat()
            .range(getFollowDistance())
            .selector(candidate -> KingHostilityTargeting.isEligibleOpponent(ownFaction, candidate));
    }

    @Override
    public boolean canUse() {
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        target = level.getNearestEntity(
            level.getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(getFollowDistance(), 4.0, getFollowDistance()),
                candidate -> true),
            targetConditions, mob, mob.getX(), mob.getEyeY(), mob.getZ());
        return target != null;
    }

    @Override
    public void start() {
        mob.setTarget(target);
        super.start();
    }
}
