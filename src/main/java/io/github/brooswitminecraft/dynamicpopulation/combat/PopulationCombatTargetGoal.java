package io.github.brooswitminecraft.dynamicpopulation.combat;

import java.util.EnumSet;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.propagation.LevelField;
import io.github.brooswitminecraft.dynamicpopulation.propagation.PopulationSimulation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;

/**
 * MINECRAFT-390 scope items 4-6: the narrowly-scoped target-acquisition goal for threshold-driven population
 * battles. Added to a filler's {@code targetSelector} ONLY (see {@code Fillager#registerGoals} and
 * {@code FillerTargetingInstaller} for the two installation sites -- one of our own classes, one join-time
 * mirroring onto vanilla Zombie/Skeleton instances, the same pattern {@code FillagerTargetCompat} already
 * uses for a different purpose).
 *
 * <p>Continuous/reactive, no hysteresis: {@link #canUse} AND {@link #canContinueToUse} both re-read the
 * mob's OWN faction's CURRENT local density every time vanilla's goal selector evaluates them (every few
 * ticks, vanilla's own throttling) -- there is no cached "was active" flag. The moment the local density
 * drops back below {@link PopulationCombatEligibility#THRESHOLD_PPT}, {@code canContinueToUse} returns
 * false, vanilla's own {@code TargetGoal} machinery calls {@link #stop} (which clears the mob's target),
 * and the target is gone the same evaluation cycle -- no separate "clear stale targets" sweep is needed.
 *
 * <p><b>Goal-priority documentation (ticket scope item 6):</b> on {@link Fillager}, this goal is a
 * {@code targetSelector} goal; the pre-existing MINECRAFT-389 {@code PanicGoal} (flee-on-hurt) and the
 * population-combat attack-execution {@code MeleeAttackGoal} this story adds both live in the
 * {@code goalSelector} instead, at priority 1 and 2 respectively. Both share the {@code MOVE} flag, so they
 * can never run concurrently -- whichever one's {@code canUse()} is checked first by vanilla's priority-order
 * evaluation (priority 1 first) wins for that cycle. Net effect: a recently-hurt Fillager's {@code PanicGoal}
 * always preempts the {@code MeleeAttackGoal} that would otherwise walk it toward a population-combat
 * target, for as long as {@code PanicGoal.canUse()}/{@code canContinueToUse()} stays true (vanilla's own
 * "still fleeing" window) -- fleeing wins over attacking, every time both could apply. Once the flee window
 * ends, the Fillager reverts to acting on whatever target this goal has already set (it never stopped being
 * the target -- only the goalSelector's movement goal changes). This is purely additive: it does not touch,
 * weaken, or bypass the no-retaliation rule for any Fillager that is NOT above its own threshold -- such a
 * Fillager never has this goal's {@code canUse()} return true in the first place, so it still "never
 * retaliates or acquires a combat target" exactly as MINECRAFT-389 specified.
 *
 * <p>On a vanilla Zombie/Skeleton filler this goal is the ONLY new goal added -- their existing vanilla
 * attack-execution goals (e.g. {@code ZombieAttackGoal}, the skeleton bow goal) already act on whatever
 * {@code Mob#getTarget()} holds, so no separate attack-execution goal is needed there.
 */
public final class PopulationCombatTargetGoal extends TargetGoal {
    private static final double RANGE_BLOCKS = 16.0;

    private final Faction ownFaction;
    private LivingEntity acquired;

    public PopulationCombatTargetGoal(Mob mob, Faction ownFaction) {
        super(mob, false);
        this.ownFaction = ownFaction;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    private int localDensity(ServerLevel level) {
        LevelField field = PopulationSimulation.field(level, ownFaction);
        return field.get(field.cellAt(mob.blockPosition()));
    }

    /**
     * Review fix (P1, round 2): the ATTACKER's own current persistence must be revalidated every tick, not
     * just at acquisition time -- a filler that becomes persistent (named, leashed, etc.) mid-chase must
     * stop initiating/continuing population-combat itself, exactly as it already stops being a valid
     * TARGET for others ({@code PopulationCombatTargeting.isEligibleOpponent} already checks this on the
     * target side). Kings never reach this check at all: this goal is never installed on a King in the
     * first place ({@code Fillager} doesn't extend to the Kings, {@code FillerTargetingInstaller}
     * explicitly excludes King instances), so there is no risk of this accidentally disabling a King's
     * own (nonexistent) use of this goal.
     */
    private boolean attackerEligible() {
        return !mob.isPersistenceRequired();
    }

    @Override
    public boolean canUse() {
        if (!attackerEligible()) {
            return false;
        }
        if (!(mob.level() instanceof ServerLevel level) || !PopulationSimulation.simulated(level)) {
            return false;
        }
        if (!PopulationCombatEligibility.active(localDensity(level))) {
            return false;
        }
        acquired = PopulationCombatTargeting.findNearestTarget(level, mob, ownFaction, RANGE_BLOCKS);
        return acquired != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!attackerEligible()) {
            return false;
        }
        if (!(mob.level() instanceof ServerLevel level) || !PopulationSimulation.simulated(level)) {
            return false;
        }
        if (!PopulationCombatEligibility.active(localDensity(level))) {
            return false;
        }
        return acquired != null && acquired.isAlive() && PopulationCombatTargeting.isEligibleOpponent(ownFaction, acquired);
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
