package io.github.brooswitminecraft.dynamicpopulation.propagation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FillagerPopulationSimulationTest {
    @Test
    void recognizesEveryEnvironmentalHazardAsUnsafe() {
        for (String path : new String[] {"lava", "in_fire", "hot_floor", "campfire", "cactus",
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

    @Test
    void onFireIsDeliberatelyNotInTheDamageTypeSet() {
        // DamageTypes.ON_FIRE is the burning status damage-over-time tick, dealt regardless of what ignited
        // the Fillager -- a Flame bow, Fire Aspect, a blaze/ghast fireball, lightning, none of which are a
        // location hazard. The location causes of burning (lava, fire, soul fire) are already covered by
        // Fillager.wasUnsafeLocationDeath, which kills the Fillager the same tick before any on_fire
        // damage-over-time tick can fire, so dropping "on_fire" here loses no real coverage.
        assertFalse(FillagerPopulationSimulation.isUnsafeDeathTypePath("on_fire"),
            "on_fire must not be unsafe: it is dealt regardless of what ignited the Fillager, not a location");
    }

    @Test
    void locationHazardWithNoAttackerIsUnsafe() {
        assertTrue(FillagerPopulationSimulation.isUnsafeDeath(true, false, "generic"),
            "a genuine location-hazard self-kill (lava/fire, or wither rose's own effect source) must count");
    }

    @Test
    void locationHazardWithAnAttackerIsNotUnsafe() {
        // A Fillager standing on a wither rose that a player or mob kills the same tick must not decrement
        // the cell -- this is the exact case the P2 review finding exists to prevent.
        assertFalse(FillagerPopulationSimulation.isUnsafeDeath(true, true, "player_attack"),
            "a player/mob kill must never count as AC9, even while standing on a survivable hazard");
    }

    @Test
    void damageTypeClassificationIsUnaffectedByTheAttackerGate() {
        assertTrue(FillagerPopulationSimulation.isUnsafeDeath(false, false, "drown"));
        assertFalse(FillagerPopulationSimulation.isUnsafeDeath(false, true, "mob_attack"));
    }
}
