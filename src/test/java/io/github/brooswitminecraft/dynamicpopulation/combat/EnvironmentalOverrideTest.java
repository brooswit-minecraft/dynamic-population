package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EnvironmentalOverrideTest {
    @Test
    void normalAtExactly250() {
        assertFalse(EnvironmentalOverride.active(250), "250 is NOT above 250 -- normal rules apply");
    }

    @Test
    void normalJustBelow250() {
        assertFalse(EnvironmentalOverride.active(249));
    }

    @Test
    void overrideActiveJustAbove250() {
        assertTrue(EnvironmentalOverride.active(251));
    }

    @Test
    void overrideActiveAtCapacity() {
        assertTrue(EnvironmentalOverride.active(1000));
    }

    @Test
    void independentFromCombatThreshold() {
        // Between 250 and 500: override active, combat not yet active -- the two thresholds coexist with
        // no shared state and no clamping between them.
        int density = 300;
        assertTrue(EnvironmentalOverride.active(density));
        assertFalse(PopulationCombatEligibility.active(density));
    }

    @Test
    void bothActiveAtOrAbove500() {
        assertTrue(EnvironmentalOverride.active(500));
        assertTrue(PopulationCombatEligibility.active(500));
    }

    @Test
    void combatThresholdBoundariesUnaffectedByThisThreshold() {
        // Recheck 499/500/501 combat boundaries still work unaffected by the independent >250 threshold.
        assertFalse(PopulationCombatEligibility.active(499));
        assertTrue(PopulationCombatEligibility.active(500));
        assertTrue(PopulationCombatEligibility.active(501));
    }
}
