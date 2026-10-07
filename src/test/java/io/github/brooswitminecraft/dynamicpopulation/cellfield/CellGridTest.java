package io.github.brooswitminecraft.dynamicpopulation.cellfield;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CellGridTest {
    @Test
    void footprintChunksForDefaultCellSize() {
        assertEquals(4, CellGrid.footprintChunks(64));
    }

    @Test
    void footprintChunksForSmallestValidCellSize() {
        assertEquals(1, CellGrid.footprintChunks(16));
    }

    @Test
    void footprintChunksRejectsNonMultipleOf16() {
        assertThrows(IllegalArgumentException.class, () -> CellGrid.footprintChunks(70));
    }

    @Test
    void anchorChunkCoordinatePositive() {
        assertEquals(4, CellGrid.anchorChunkCoordinate(4, 64));
        assertEquals(4, CellGrid.anchorChunkCoordinate(5, 64));
        assertEquals(4, CellGrid.anchorChunkCoordinate(7, 64));
        assertEquals(8, CellGrid.anchorChunkCoordinate(8, 64));
    }

    @Test
    void anchorChunkCoordinateNegative() {
        // footprint = 4 chunks; the footprint containing -1..-4 has min -4.
        assertEquals(-4, CellGrid.anchorChunkCoordinate(-1, 64));
        assertEquals(-4, CellGrid.anchorChunkCoordinate(-4, 64));
        assertEquals(-8, CellGrid.anchorChunkCoordinate(-5, 64));
        assertEquals(0, CellGrid.anchorChunkCoordinate(0, 64));
    }

    @Test
    void isAnchorChunkTrueOnlyForMinCornerOfFootprint() {
        assertTrue(CellGrid.isAnchorChunk(-4, -4, 64));
        assertFalse(CellGrid.isAnchorChunk(-1, -4, 64));
        assertFalse(CellGrid.isAnchorChunk(-4, -1, 64));
        assertTrue(CellGrid.isAnchorChunk(0, 0, 64));
    }

    @Test
    void layerCountForDefaultOverworld() {
        // -64..320, height 384, cellSize 64 -> exactly 6 layers.
        assertEquals(6, CellGrid.layerCount(-64, 320, 64));
    }

    @Test
    void layerCountCeilsAPartialTopLayer() {
        // height 364 / 64 = 5.6875 -> must round up to 6, not truncate to 5.
        assertEquals(6, CellGrid.layerCount(-64, 300, 64));
    }

    @Test
    void layerIndexAtWorldFloorIsZero() {
        assertEquals(0, CellGrid.layerIndex(-64, -64, 64));
    }

    @Test
    void layerIndexNegativeBlockYUsesFloorDivision() {
        // floorDiv(-65, 64) = -2, not the truncating -1 that (-65 / 64) would give in Java.
        assertEquals(-2, CellGrid.layerIndex(-65, 0, 64));
    }

    @Test
    void layerIndexAtTopOfOverworldIsLastLayer() {
        assertEquals(5, CellGrid.layerIndex(319, -64, 64));
    }
}
