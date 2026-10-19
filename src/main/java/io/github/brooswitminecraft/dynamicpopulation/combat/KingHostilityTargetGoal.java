package io.github.brooswitminecraft.dynamicpopulation.combat;

import java.util.EnumSet;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;

/**
 * MINECRAFT-416: the target-acquisition half of cross-type King-vs-King hostility. Installed as a
 * {@code targetSelector} goal on all three King entities -- see {@code VillagerKingEntity#registerGoals},
 * {@code ZombieKingEntity#registerGoals} and {@code SkeletonKingEntity#registerGoals}.
 *
 * <p>Continuous/reactive like {@link PopulationCombatTargetGoal}: {@link #canUse} and
 * {@link #canContinueToUse} both re-read live state every evaluation cycle, so a dead/removed/unloaded
 * opposing King is dropped as a target the very next check (vanilla's own {@code TargetGoal} machinery then
 * calls {@link #stop}, which clears {@code Mob#getTarget()}) -- no separate "clear stale targets" sweep is
 * needed, and no synthetic target can survive a reload.
 *
 * <p>Deliberately has no density gate at all (unlike {@link PopulationCombatTargetGoal}'s
 * {@code PopulationCombatEligibility} check) and no attacker-persistence gate (every King is always
 * persistence-required; that fact is irrelevant here, unlike for an ordinary filler).
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
 * <p>Range is the King's own live {@code Attributes.FOLLOW_RANGE} value rather than a new hardcoded
 * constant, so it naturally matches whatever follow range each King's attribute map already declares (see
 * {@code DynamicPopulationEntities#createAttributes}) instead of introducing a second, possibly-inconsistent
 * number.
 */
public final class KingHostilityTargetGoal extends TargetGoal {
    private final Faction ownFaction;
    private LivingEntity acquired;

    public KingHostilityTargetGoal(Mob mob, Faction ownFaction) {
        super(mob, false);
        this.ownFaction = ownFaction;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    private double range() {
        return mob.getAttributeValue(Attributes.FOLLOW_RANGE);
    }

    @Override
    public boolean canUse() {
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        acquired = KingHostilityTargeting.findNearestTarget(level, mob, ownFaction, range());
        return acquired != null;
    }

    @Override
    public boolean canContinueToUse() {
        return acquired != null && acquired.isAlive() && KingHostilityTargeting.isEligibleOpponent(ownFaction, acquired);
    }

    @Override
    public void start() {
        mob.setTarget(acquired);
        super.start();
    }

    @Override
    public void stop() {
        mob.setTarget(null);
        acquired = null;
        super.stop();
    }
}
