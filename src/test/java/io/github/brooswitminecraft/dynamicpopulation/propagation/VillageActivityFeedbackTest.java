package io.github.brooswitminecraft.dynamicpopulation.propagation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VillageActivityFeedbackTest {
    @Test
    void aFreshBabyVillagerIsAnOrdinaryBirth() {
        assertTrue(VillageActivityFeedback.isOrdinaryVillagerBirth(false, true));
    }

    @Test
    void aBabyVillagerMerelyLoadedBackInWithItsChunkIsNotABirth() {
        // This is exactly why BabyEntitySpawnEvent (fired only from Animal/Fox breeding, never from the
        // vanilla VillagerMakeLove behavior normal villagers breed through) was replaced with
        // EntityJoinLevelEvent here: loadedFromDisk is the signal that distinguishes a real birth from a
        // chunk simply loading an already-existing baby villager back in.
        assertFalse(VillageActivityFeedback.isOrdinaryVillagerBirth(true, true),
            "a villager loaded back in with its chunk must not count as a birth");
    }

    @Test
    void anAdultVillagerJoiningTheLevelIsNotABirth() {
        assertFalse(VillageActivityFeedback.isOrdinaryVillagerBirth(false, false),
            "an adult villager joining the level by any other means (cured zombie villager, /summon, "
                + "boat/portal transfer) must not count as a birth");
    }
}
