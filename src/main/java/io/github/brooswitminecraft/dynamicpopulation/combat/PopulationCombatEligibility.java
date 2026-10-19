package io.github.brooswitminecraft.dynamicpopulation.combat;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;

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
 *
 * <p><b>MINECRAFT-426:</b> the threshold is now PER-FACTION, not a single shared constant: the Villager
 * faction (Fillager territorial combat) is corrected to 0 -- i.e. territorial population-combat eligibility
 * at ANY local density, including exactly zero -- while Zombie and Skeleton deliberately keep the original
 * 500 threshold unchanged. {@link #THRESHOLD_PPT} is kept (unchanged value, still used by Zombie/Skeleton)
 * rather than removed, so existing call sites/tests pinned to the shared constant keep compiling and the
 * diff for this ticket stays additive. {@link #active(Faction, int)} is the new, faction-aware entry point
 * every REAL combat call site ({@code PopulationCombatTargetGoal}) now uses; the pre-existing single-arg
 * {@link #active(int)} is kept as-is (still exactly the original 500-threshold check) purely because
 * {@code EnvironmentalOverrideTest}/{@code DayNightSpawnRateTest} use it as a generic, faction-agnostic
 * "the (unchanged, Zombie/Skeleton) combat threshold" reference point for THEIR OWN independent-threshold
 * assertions -- it was never about Fillager/Villager eligibility and this ticket does not touch what it
 * means.
 */
public final class PopulationCombatEligibility {
    /** Unchanged threshold for Zombie/Skeleton (ticket clarification: "oops it should be at 500"), on the existing 0..1000 scale. */
    public static final int THRESHOLD_PPT = 500;

    /** MINECRAFT-426: Fillager/Villager territorial combat is eligible at any density, including exactly zero. */
    public static final int VILLAGER_THRESHOLD_PPT = 0;

    private PopulationCombatEligibility() { }

    /** Per-faction threshold, on the existing 0..1000 scale. */
    public static int thresholdFor(Faction faction) {
        return faction == Faction.VILLAGER ? VILLAGER_THRESHOLD_PPT : THRESHOLD_PPT;
    }

    /** Whether {@code faction} with this local density may acquire population-combat targets right now. */
    public static boolean active(Faction faction, int localDensityPpt) {
        return localDensityPpt >= thresholdFor(faction);
    }

    /**
     * Pre-existing, UNCHANGED single-arg check against the original shared {@link #THRESHOLD_PPT} (500) --
     * kept only for the pre-existing tests that use it as a faction-agnostic reference point (see class
     * doc). No real production call site uses this overload any more; use {@link #active(Faction, int)}.
     */
    public static boolean active(int localDensityPpt) {
        return localDensityPpt >= THRESHOLD_PPT;
    }
}
