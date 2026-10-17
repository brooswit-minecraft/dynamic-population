package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FillagerSpawnSearchTest {
    @Test
    void undergroundCellNeverUsesSurfaceOutsideItsLayer() {
        assertTrue(FillagerSpawnSearch.findY(0, 64, -64, 320, y -> y == 70).isEmpty());
        assertEquals(-20, FillagerSpawnSearch.findY(0, 64, -64, 320, y -> y == -20).orElseThrow());
    }

    @Test
    void separateLayersSelectTheirOwnSafeFloor() {
        assertEquals(12, FillagerSpawnSearch.findY(1, 64, -64, 320, y -> y == 12 || y == 70).orElseThrow());
        assertEquals(70, FillagerSpawnSearch.findY(2, 64, -64, 320, y -> y == 12 || y == 70).orElseThrow());
    }

    @Test
    void collisionRejectedPositionsAreSkippedAndSearchIsBounded() {
        List<Integer> checked = new ArrayList<>();
        assertTrue(FillagerSpawnSearch.findY(1, 16, -64, 320, y -> { checked.add(y); return false; }).isEmpty());
        assertEquals(15, checked.size());
        assertTrue(checked.stream().allMatch(y -> y >= -48 && y <= -34));
    }

    @Test
    void invalidLayersNeverProbeWorldAndHeadStaysInsideWorld() {
        assertTrue(FillagerSpawnSearch.findY(-1, 64, -64, 320, y -> { fail(); return true; }).isEmpty());
        assertTrue(FillagerSpawnSearch.findY(6, 64, -64, 320, y -> { fail(); return true; }).isEmpty());
        assertEquals(318, FillagerSpawnSearch.findY(5, 64, -64, 320, y -> true).orElseThrow());
    }
}
