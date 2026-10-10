package io.github.brooswitminecraft.dynamicpopulation.king;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicpopulation.king.KingUniqueness.CellCoord;

class KingUniquenessTest {
    @Test
    void allowedFarAwayFromEveryExistingKing() {
        assertTrue(KingUniqueness.allowed(new CellCoord(100, 100), List.of(new CellCoord(0, 0)), 8));
    }

    @Test
    void notAllowedInsideTheRadiusOfAnExistingKing() {
        // Second King must not spawn/persist alongside an existing one within the relevant area (AC4).
        assertFalse(KingUniqueness.allowed(new CellCoord(5, 0), List.of(new CellCoord(0, 0)), 8));
    }

    @Test
    void notAllowedAtTheSamePosition() {
        assertFalse(KingUniqueness.allowed(new CellCoord(0, 0), List.of(new CellCoord(0, 0)), 8));
    }

    @Test
    void allowedExactlyOneCellOutsideTheRadius() {
        assertTrue(KingUniqueness.allowed(new CellCoord(9, 0), List.of(new CellCoord(0, 0)), 8));
    }

    @Test
    void notAllowedExactlyAtTheRadiusBoundary() {
        assertFalse(KingUniqueness.allowed(new CellCoord(8, 0), List.of(new CellCoord(0, 0)), 8));
    }

    @Test
    void distanceIsChebyshevNotEuclidean() {
        // (6, 6) is 6 away on each axis -- inside an 8-cell Chebyshev radius even though its Euclidean
        // distance (~8.49) would exceed a circular radius of 8. Confirms the shape is a square, not a circle.
        assertFalse(KingUniqueness.allowed(new CellCoord(6, 6), List.of(new CellCoord(0, 0)), 8));
    }

    @Test
    void noExistingKingsAlwaysAllowed() {
        assertTrue(KingUniqueness.allowed(new CellCoord(0, 0), List.of(), 8));
    }
}
