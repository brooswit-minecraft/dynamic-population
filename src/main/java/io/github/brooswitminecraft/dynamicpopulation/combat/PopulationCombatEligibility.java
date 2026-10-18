package io.github.brooswitminecraft.dynamicpopulation.combat;

/**
 * MINECRAFT-390 (threshold-driven population battles). Pure, unit-testable threshold check against the
 * EXISTING local per-cell target density field -- the same 0..1000 parts-per-thousand scale
 * {@code Propagation}/{@code CellFieldRecord}/{@code fillagers_per_full_cell} already use. No scale
 * expansion: this is a straight comparison against that existing scale, corrected (per ticket clarification)
 * from the original ticket text's "10000" to 500 on this 0..1000 scale.
 *
 * <p>Deliberately continuous/reactive, never sticky: there is no separate "was active last tick" state and
 * no hysteresis band. {@link #active} is called fresh every time a goal re-evaluates eligibility (see
 * {@code PopulationCombatTargetGoal}), so a density crossing back below the threshold the very next tick
 * clears eligibility (and therefore any stale target, via the goal's {@code canContinueToUse}) immediately,
 * never carrying over a previous tick's "was active" verdict.
 *
 * <p>Each faction (Zombie, Skeleton, Fillager/Villager) is checked against its OWN local density only --
 * never the opposing faction's. A faction becomes attacker-eligible purely from its own number; the
 * opponent's own density is irrelevant to whether IT can be attacked (see
 * {@code PopulationCombatTargeting#isEligibleOpponent}, which has no density check of its own at all).
 */
public final class PopulationCombatEligibility {
    /** Corrected threshold (ticket clarification: "oops it should be at 500"), on the existing 0..1000 scale. */
    public static final int THRESHOLD_PPT = 500;

    private PopulationCombatEligibility() { }

    /** Whether a faction with this local density may acquire population-combat targets right now. */
    public static boolean active(int localDensityPpt) {
        return localDensityPpt >= THRESHOLD_PPT;
    }
}
