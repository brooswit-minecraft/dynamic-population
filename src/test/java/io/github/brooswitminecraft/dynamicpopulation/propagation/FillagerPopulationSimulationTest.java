package io.github.brooswitminecraft.dynamicpopulation.propagation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FillagerPopulationSimulationTest {
    @Test
    void recognizesEveryEnvironmentalHazardAsUnsafe() {
        for (String path : new String[] {"lava", "in_fire", "on_fire", "hot_floor", "campfire", "cactus",
                "sweet_berry_bush", "drown", "in_wall"}) {
            assertTrue(FillagerPopulationSimulation.isUnsafeDeathTypePath(path), path + " must be unsafe");
        }
    }

    @Test
    void ordinaryDamageIsNotUnsafe() {
        for (String path : new String[] {"generic", "player_attack", "mob_attack", "fall", "starve", "cramming"}) {
            assertFalse(FillagerPopulationSimulation.isUnsafeDeathTypePath(path), path + " must not be unsafe");
        }
    }

    @Test
    void witherIsDeliberatelyNotInTheDamageTypeSet() {
        // DamageTypes.WITHER is the same registry key whether the Wither status effect came from standing
        // on a wither rose or from a wither skeleton/the Wither boss killing this mob outright -- classifying
        // by damage type here would misattribute an ordinary mob kill as a local-cell hazard death. Wither
        // rose is instead classified by location in Fillager.aiStep (wasUnsafeLocationDeath).
        assertFalse(FillagerPopulationSimulation.isUnsafeDeathTypePath("wither"),
            "wither must be classified by location (Fillager.wasUnsafeLocationDeath), never by DamageType");
    }
}
