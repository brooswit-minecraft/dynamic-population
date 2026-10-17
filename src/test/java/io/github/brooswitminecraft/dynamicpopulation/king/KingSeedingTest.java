package io.github.brooswitminecraft.dynamicpopulation.king;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicpopulation.propagation.CellKey;
import io.github.brooswitminecraft.dynamicpopulation.propagation.Propagation;

/** Against the real storage contract {@code Propagation.Field} -- same test-double shape as {@code PropagationTest}. */
class KingSeedingTest {
    /** A field backed by a map, with a predicate for which cells are traversable. */
    private static final class MapField implements Propagation.Field {
        final Map<CellKey, Integer> values = new HashMap<>();
        final Predicate<CellKey> traversable;

        MapField(Predicate<CellKey> traversable) {
            this.traversable = traversable;
        }

        @Override
        public int get(CellKey cell) {
            return values.getOrDefault(cell, 0);
        }

        @Override
        public void set(CellKey cell, int value) {
            values.put(cell, value);
        }

        @Override
        public boolean traversable(CellKey cell) {
            return traversable.test(cell);
        }
    }

    private static final CellKey KING = new CellKey(0, 2, 0);

    @Test
    void raisesEveryTraversableCellInRange() {
        MapField field = new MapField(c -> true);
        KingSeeding.seed(field, KING, 1, 5);
        for (CellKey cell : KingSeeding.cellsInRange(KING, 1)) {
            assertEquals(5, field.get(cell));
        }
    }

    @Test
    void doesNotRaiseCellsOutsideTheRadius() {
        MapField field = new MapField(c -> true);
        KingSeeding.seed(field, KING, 1, 5);
        CellKey outside = KING.offset(2, 0, 0);
        assertEquals(0, field.get(outside));
    }

    @Test
    void onlySeedsTheKingsOwnLayer() {
        MapField field = new MapField(c -> true);
        KingSeeding.seed(field, KING, 1, 5);
        CellKey layerAbove = KING.offset(0, 1, 0);
        CellKey layerBelow = KING.offset(0, -1, 0);
        assertEquals(0, field.get(layerAbove));
        assertEquals(0, field.get(layerBelow));
    }

    @Test
    void skipsNonTraversableCells() {
        Set<CellKey> blocked = new HashSet<>(Set.of(KING.offset(1, 0, 0)));
        MapField field = new MapField(c -> !blocked.contains(c));
        KingSeeding.seed(field, KING, 1, 5);
        assertEquals(0, field.get(KING.offset(1, 0, 0)));
        assertEquals(5, field.get(KING));
    }

    @Test
    void neverExceedsCapacity() {
        MapField field = new MapField(c -> true);
        field.set(KING, Propagation.CAPACITY - 2);
        KingSeeding.seed(field, KING, 0, 10);
        assertEquals(Propagation.CAPACITY, field.get(KING));
    }

    @Test
    void repeatedSeedingAccumulatesUpToCapacity() {
        MapField field = new MapField(c -> true);
        for (int i = 0; i < 1000; i++) {
            KingSeeding.seed(field, KING, 0, 7);
        }
        assertTrue(field.get(KING) == Propagation.CAPACITY);
    }
}
