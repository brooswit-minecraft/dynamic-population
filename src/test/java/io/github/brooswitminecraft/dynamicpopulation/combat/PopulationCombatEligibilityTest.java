package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PopulationCombatEligibilityTest {
    @Test
    void inactiveJustBelowThreshold() {
        assertFalse(PopulationCombatEligibility.active(499));
    }

    @Test
    void activeExactlyAtThreshold() {
        assertTrue(PopulationCombatEligibility.active(500));
    }

    @Test
    void activeJustAboveThreshold() {
        assertTrue(PopulationCombatEligibility.active(501));
    }

    @Test
    void inactiveAtZero() {
        assertFalse(PopulationCombatEligibility.active(0));
    }

    @Test
    void activeAtCapacity() {
        assertTrue(PopulationCombatEligibility.active(1000));
    }

    @Test
    void upwardCrossingFlipsActive() {
        assertFalse(PopulationCombatEligibility.active(499));
        assertTrue(PopulationCombatEligibility.active(500));
    }

    @Test
    void downwardCrossingFlipsInactive() {
        assertTrue(PopulationCombatEligibility.active(500));
        assertFalse(PopulationCombatEligibility.active(499));
    }

    @Test
    void noClampingOrOverflowNearBoundary() {
        // Deliberately not clamped/saturated at the boundary -- a plain >= comparison, every value on
        // either side of 500 reads exactly as expected with no off-by-one.
        for (int v = 495; v <= 505; v++) {
            assertTrue(PopulationCombatEligibility.active(v) == (v >= 500), "v=" + v);
        }
    }
}
