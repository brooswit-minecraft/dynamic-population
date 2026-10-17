package io.github.brooswitminecraft.dynamicpopulation.king;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KingTerritoryTest {
    @Test
    void suitableWhenPlainsBrightAndAtSurface() {
        assertTrue(KingTerritory.suitable(true, 15, 0, 9, 4));
    }

    @Test
    void unsuitableWhenNotPlains() {
        assertFalse(KingTerritory.suitable(false, 15, 0, 9, 4));
    }

    @Test
    void unsuitableWhenSkyLightTooLow() {
        assertFalse(KingTerritory.suitable(true, 8, 0, 9, 4));
    }

    @Test
    void suitableAtExactSkyLightThreshold() {
        assertTrue(KingTerritory.suitable(true, 9, 0, 9, 4));
    }

    @Test
    void unsuitableWhenTooDeepBelowSurface() {
        assertFalse(KingTerritory.suitable(true, 15, 5, 9, 4));
    }

    @Test
    void suitableAtExactDepthThreshold() {
        assertTrue(KingTerritory.suitable(true, 15, 4, 9, 4));
    }

    @Test
    void suitableAboveTheSurfaceIsNeverPenalised() {
        // "depth below surface" is clamped to >= 0 by the caller; a location above the heightmap surface
        // (depthBelowSurfaceBlocks == 0) must never be rejected for being "above" it.
        assertTrue(KingTerritory.suitable(true, 15, 0, 9, 0));
    }
}
