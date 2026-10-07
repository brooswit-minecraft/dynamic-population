package io.github.brooswitminecraft.dynamicpopulation.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class PropagationTest {
    private static final Propagation.Params DEFAULTS = new Propagation.Params(3.0, 2.0, 1.0, 0.1);

    /** A field backed by a map, with a predicate for which cells exist. */
    private static final class MapField implements Propagation.Field {
        final Map<CellKey, Integer> values = new HashMap<>();
        final Predicate<CellKey> exists;

        MapField(Predicate<CellKey> exists) {
            this.exists = exists;
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
            return exists.test(cell);
        }

        int total() {
            return values.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private static final CellKey ORIGIN = new CellKey(0, 0, 0);

    private static MapField openWorld() {
        return new MapField(c -> true);
    }

    @Test
    void totalIsConserved() {
        MapField field = openWorld();
        field.set(ORIGIN, 1000);
        Set<CellKey> active = Set.of(ORIGIN);
        var random = new java.util.Random(1);
        for (int i = 0; i < 200; i++) {
            active = Propagation.step(field, active, DEFAULTS, random);
        }
        assertEquals(1000, field.total());
    }

    @Test
    void oneHopPerStep() {
        MapField field = openWorld();
        field.set(ORIGIN, 1000);
        Set<CellKey> active = Propagation.step(field, Set.of(ORIGIN), DEFAULTS, new java.util.Random(2));
        for (CellKey cell : field.values.keySet()) {
            if (field.get(cell) > 0) {
                int distance = Math.abs(cell.x()) + Math.abs(cell.layer()) + Math.abs(cell.z());
                assertTrue(distance <= 1, "reached " + cell + " in one step");
            }
        }
        assertTrue(active.contains(ORIGIN));
    }

    @Test
    void downGetsMoreThanSidesAndSidesMoreThanUp() {
        MapField field = openWorld();
        field.set(ORIGIN, 1000);
        // A large fraction so rounding does not matter.
        Propagation.step(field, Set.of(ORIGIN), new Propagation.Params(3.0, 2.0, 1.0, 1.0), new java.util.Random(3));
        int down = field.get(ORIGIN.offset(0, -1, 0));
        int side = field.get(ORIGIN.offset(1, 0, 0));
        int up = field.get(ORIGIN.offset(0, 1, 0));
        assertTrue(down > side && side > up && up > 0, down + " > " + side + " > " + up);
        assertEquals(1000, field.total());
    }

    @Test
    void everyDirectionStillGetsAShareUnderTightCapacity() {
        MapField field = openWorld();
        field.set(ORIGIN, 1000);
        // Every neighbour has just 3 units of room.
        for (int[] d : new int[][] {{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}}) {
            field.set(ORIGIN.offset(d[0], d[1], d[2]), Propagation.CAPACITY - 3);
        }
        Propagation.step(field, Set.of(ORIGIN), new Propagation.Params(3.0, 2.0, 1.0, 0.5), new java.util.Random(4));
        for (int[] d : new int[][] {{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}}) {
            assertTrue(field.get(ORIGIN.offset(d[0], d[1], d[2])) > Propagation.CAPACITY - 3, "no share toward " + java.util.Arrays.toString(d));
        }
    }

    @Test
    void upIsNeverStarvedByRoundingOverManySteps() {
        MapField field = openWorld();
        Set<CellKey> active = Set.of(ORIGIN);
        field.set(ORIGIN, 40);
        var random = new java.util.Random(5);
        boolean up = false;
        for (int i = 0; i < 400 && !up; i++) {
            active = Propagation.step(field, active, DEFAULTS, random);
            up = field.get(ORIGIN.offset(0, 1, 0)) > 0;
        }
        assertTrue(up);
    }

    @Test
    void cellsNeverExceedCapacity() {
        MapField field = openWorld();
        Set<CellKey> active = new HashSet<>();
        for (int x = -2; x <= 2; x++) {
            CellKey c = new CellKey(x, 0, 0);
            field.set(c, 1000);
            active.add(c);
        }
        var random = new java.util.Random(6);
        for (int i = 0; i < 100; i++) {
            active = Propagation.step(field, active, DEFAULTS, random);
        }
        field.values.values().forEach(v -> assertTrue(v >= 0 && v <= Propagation.CAPACITY));
        assertEquals(5000, field.total());
    }

    @Test
    void doesNotEnterNonTraversableCells() {
        MapField field = new MapField(c -> c.layer() == 0 && c.z() == 0);
        field.set(ORIGIN, 1000);
        Set<CellKey> active = Set.of(ORIGIN);
        var random = new java.util.Random(7);
        for (int i = 0; i < 50; i++) {
            active = Propagation.step(field, active, DEFAULTS, random);
        }
        field.values.forEach((cell, v) -> assertTrue(v == 0 || (cell.layer() == 0 && cell.z() == 0)));
        assertEquals(1000, field.total());
    }

    @Test
    void liquidCountsAsTraversable() {
        assertTrue(Propagation.traversable(false, true));
        assertTrue(Propagation.traversable(true, false));
        assertFalse(Propagation.traversable(false, false));
    }

    @Test
    void weightsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new Propagation.Params(0.0, 2.0, 1.0, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new Propagation.Params(3.0, 0.0, 1.0, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new Propagation.Params(3.0, 2.0, -1.0, 0.1));
    }

    @Test
    void allocateSplitsExactlyAndRespectsCaps() {
        int[] share = Propagation.allocate(100, List.of(3.0, 2.0, 1.0), List.of(1000, 10, 1000), new java.util.Random(8));
        assertEquals(100, share[0] + share[1] + share[2]);
        assertTrue(share[1] <= 10);
    }

    /**
     * TRIPWIRE (MINECRAFT-27): a column of six cells with population injected into the top cell every step and a
     * persistent drain on the bottom cell, run to steady state. If the top cell is drained to near zero at the
     * shipped default weights, the conservative-transport premise is wrong (population flowing down and never
     * coming back): reopen the design question, do not tune the weights until this passes.
     */
    @Test
    void surfaceKeepsAShareUnderPersistentBottomDrain() {
        final int layers = 6;
        MapField field = new MapField(c -> c.x() == 0 && c.z() == 0 && c.layer() >= 0 && c.layer() < layers);
        CellKey top = new CellKey(0, layers - 1, 0);
        CellKey bottom = new CellKey(0, 0, 0);
        Set<CellKey> active = new HashSet<>();
        var random = new java.util.Random(9);
        // Defaults as shipped in DynamicPopulationConfig.
        Propagation.Params shipped = new Propagation.Params(3.0, 2.0, 1.0, 0.1);
        int topAtEnd = 0;
        for (int step = 0; step < 6000; step++) {
            field.set(top, Math.min(Propagation.CAPACITY, field.get(top) + 20));
            active.add(top);
            active = new HashSet<>(Propagation.step(field, active, shipped, random));
            field.set(bottom, Math.max(0, field.get(bottom) - 20));
            if (field.get(top) > 0) {
                active.add(top);
            }
            topAtEnd = field.get(top);
        }
        assertTrue(topAtEnd >= 100, "surface cell drained to " + topAtEnd + "/1000 at steady state");
    }
}
