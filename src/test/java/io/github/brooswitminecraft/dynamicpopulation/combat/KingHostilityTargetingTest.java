package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import org.junit.jupiter.api.Test;

/** Pure predicate coverage for cross-type King-vs-King hostility (MINECRAFT-416). */
class KingHostilityTargetingTest {
    @Test
    void everyDirectedOpposingFactionPairingIsEligible() {
        // All six directed pairings the ticket enumerates: Villager<->Zombie, Villager<->Skeleton,
        // Zombie<->Skeleton, in both directions.
        for (Faction own : Faction.values()) {
            for (Faction other : Faction.values()) {
                if (own == other) {
                    continue;
                }
                assertTrue(KingHostilityTargeting.isEligibleOpponent(own, other),
                    own + " King vs " + other + " King must be mutually hostile");
            }
        }
    }

    @Test
    void sameFactionKingIsNeverEligible() {
        // "Do not add same-type King hostility."
        for (Faction faction : Faction.values()) {
            assertFalse(KingHostilityTargeting.isEligibleOpponent(faction, faction),
                faction + " King vs another " + faction + " King must never be eligible");
        }
    }
}
