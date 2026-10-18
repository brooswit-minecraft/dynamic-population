package io.github.brooswitminecraft.dynamicpopulation.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FillerSpawnRulesTest {
    @Test
    void normallyAllowsDarkSpawn() {
        assertTrue(FillerSpawnRules.lightLevelAllows(0, false));
        assertTrue(FillerSpawnRules.lightLevelAllows(7, false));
    }

    @Test
    void normallyBlocksBrightSpawn() {
        assertFalse(FillerSpawnRules.lightLevelAllows(8, false));
        assertFalse(FillerSpawnRules.lightLevelAllows(15, false));
    }

    @Test
    void overrideAllowsBrightSpawnRegardless() {
        assertTrue(FillerSpawnRules.lightLevelAllows(15, true));
        assertTrue(FillerSpawnRules.lightLevelAllows(8, true));
    }

    @Test
    void overrideDoesNotAffectDarkSpawn() {
        assertTrue(FillerSpawnRules.lightLevelAllows(0, true));
    }

    @Test
    void densityBoundaryDrivesOverrideCorrectly() {
        assertFalse(FillerSpawnRules.lightLevelAllows(15, EnvironmentalOverride.active(250)));
        assertTrue(FillerSpawnRules.lightLevelAllows(15, EnvironmentalOverride.active(251)));
    }
}
