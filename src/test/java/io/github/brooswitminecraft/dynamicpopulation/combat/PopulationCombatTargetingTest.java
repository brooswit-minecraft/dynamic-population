package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import org.junit.jupiter.api.Test;

/** Pure predicate coverage for the population-combat targeting filter (ticket scope item 5). */
class PopulationCombatTargetingTest {
    @Test
    void everyOpposingFactionPairingIsEligible() {
        for (Faction own : Faction.values()) {
            for (Faction other : Faction.values()) {
                if (own == other) {
                    continue;
                }
                assertTrue(PopulationCombatTargeting.isEligibleOpponent(own, other, false, false, false),
                    own + " vs " + other + " must be eligible");
            }
        }
    }

    @Test
    void sameFactionIsNeverEligible() {
        for (Faction faction : Faction.values()) {
            assertFalse(PopulationCombatTargeting.isEligibleOpponent(faction, faction, false, false, false),
                faction + " vs itself must never be eligible");
        }
    }

    @Test
    void playersAreNeverEligible() {
        for (Faction own : Faction.values()) {
            for (Faction other : Faction.values()) {
                assertFalse(PopulationCombatTargeting.isEligibleOpponent(own, other, true, false, false),
                    "a player must never be a population-combat target");
            }
        }
    }

    @Test
    void kingsAreNeverEligible() {
        for (Faction own : Faction.values()) {
            for (Faction other : Faction.values()) {
                if (own == other) {
                    continue;
                }
                assertFalse(PopulationCombatTargeting.isEligibleOpponent(own, other, false, true, false),
                    "a King must never be a population-combat target");
            }
        }
    }

    @Test
    void persistentOrPromotedAreNeverEligible() {
        assertFalse(PopulationCombatTargeting.isEligibleOpponent(Faction.ZOMBIE, Faction.VILLAGER, false, false, true),
            "a persistent/promoted villager must never be a population-combat target");
    }

    @Test
    void ordinaryNonPersistentOpponentIsEligible() {
        assertTrue(PopulationCombatTargeting.isEligibleOpponent(Faction.ZOMBIE, Faction.VILLAGER, false, false, false));
        assertTrue(PopulationCombatTargeting.isEligibleOpponent(Faction.SKELETON, Faction.ZOMBIE, false, false, false));
        assertTrue(PopulationCombatTargeting.isEligibleOpponent(Faction.VILLAGER, Faction.SKELETON, false, false, false));
    }
}
