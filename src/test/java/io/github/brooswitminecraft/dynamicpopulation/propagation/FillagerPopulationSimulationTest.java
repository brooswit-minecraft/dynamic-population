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
}
