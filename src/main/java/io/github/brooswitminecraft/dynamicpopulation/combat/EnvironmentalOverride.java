package io.github.brooswitminecraft.dynamicpopulation.combat;

/**
 * MINECRAFT-390 (late-added requirement): "population &gt; 250 removes light level and burning under the sun
 * requirements for zombies and skeles." A SECOND, fully independent threshold on the exact same existing
 * 0..1000 parts-per-thousand local density scale {@link PopulationCombatEligibility} reads -- not a related
 * concept, not a second stage of the same threshold. The two coexist: a cell can be below 250 (normal light
 * rules), between 250 and 500 (light/sun override active, combat not yet active), or at/above 500 (both
 * active) -- there is no clamping or shared state between them.
 *
 * <p>Strictly greater-than 250, not greater-or-equal: at exactly 250 normal rules still apply. Scoped to
 * ONLY the Zombie and Skeleton factions' ordinary filler members -- never Fillagers, never any King, and
 * never general fire/lava immunity (only the sun-exposure burning check and the spawn light-level check are
 * suppressed; ordinary fire/lava damage is untouched -- see {@code SunImmunityTicking} and
 * {@code FillerSpawnRules} for the two call sites). Continuous/reactive, same as
 * {@link PopulationCombatEligibility}: re-evaluated at the entity's CURRENT location every tick for a live
 * filler, and at the candidate location for a new spawn -- no hysteresis, no "stays suppressed once
 * triggered" latch. Each faction's own density grants only that faction's override (Zombie density never
 * suppresses Skeleton burning or vice versa) -- callers always pass the faction-matched density.
 */
public final class EnvironmentalOverride {
    /** Strictly above this (not at-or-above) activates the override. */
    public static final int LIGHT_AND_SUN_OVERRIDE_THRESHOLD_PPT = 250;

    private EnvironmentalOverride() { }

    /** Whether a Zombie/Skeleton filler at this faction-matched local density ignores light/sun checks right now. */
    public static boolean active(int localDensityPpt) {
        return localDensityPpt > LIGHT_AND_SUN_OVERRIDE_THRESHOLD_PPT;
    }
}
