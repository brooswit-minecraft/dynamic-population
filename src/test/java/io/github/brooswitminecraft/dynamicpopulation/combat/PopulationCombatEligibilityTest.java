package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
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

    // --- MINECRAFT-426: Fillager/Villager territorial threshold corrected to 0 (including exactly zero),
    // Zombie/Skeleton population-battle thresholds explicitly UNCHANGED at 500.

    @Test
    void villagerActiveAtExactlyZero() {
        assertTrue(PopulationCombatEligibility.active(Faction.VILLAGER, 0),
            "Fillager territorial combat must be eligible at exactly zero density");
    }

    @Test
    void villagerActiveAtOne() {
        assertTrue(PopulationCombatEligibility.active(Faction.VILLAGER, 1));
    }

    @Test
    void villagerActiveAcrossTheOldThresholdToo() {
        assertTrue(PopulationCombatEligibility.active(Faction.VILLAGER, 499));
        assertTrue(PopulationCombatEligibility.active(Faction.VILLAGER, 500));
    }

    @Test
    void zombieThresholdUnchangedAt499And500() {
        assertFalse(PopulationCombatEligibility.active(Faction.ZOMBIE, 499));
        assertTrue(PopulationCombatEligibility.active(Faction.ZOMBIE, 500));
    }

    @Test
    void skeletonThresholdUnchangedAt499And500() {
        assertFalse(PopulationCombatEligibility.active(Faction.SKELETON, 499));
        assertTrue(PopulationCombatEligibility.active(Faction.SKELETON, 500));
    }

    @Test
    void skeletonAndZombieAreInactiveAtZero() {
        // Confirms the Villager-only zero fix did not leak into the other two factions' threshold.
        assertFalse(PopulationCombatEligibility.active(Faction.ZOMBIE, 0));
        assertFalse(PopulationCombatEligibility.active(Faction.SKELETON, 0));
    }

    @Test
    void thresholdForReflectsThePerFactionSplit() {
        assertTrue(PopulationCombatEligibility.thresholdFor(Faction.VILLAGER) == 0);
        assertTrue(PopulationCombatEligibility.thresholdFor(Faction.ZOMBIE) == 500);
        assertTrue(PopulationCombatEligibility.thresholdFor(Faction.SKELETON) == 500);
    }
}
