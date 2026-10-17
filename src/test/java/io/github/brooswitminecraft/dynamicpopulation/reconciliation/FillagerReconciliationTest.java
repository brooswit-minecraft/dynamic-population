package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicpopulation.propagation.CellKey;

class FillagerReconciliationTest {
    private static final CellKey ORIGIN = new CellKey(0, 0, 0);

    @Test
    void overlappingBoxesDoNotGiveNeighbourCellsOwnership() {
        // Each query can return both entities because their boxes cross a horizontal or vertical boundary.
        record Resident(CellKey owner) { }
        for (CellKey neighbour : java.util.List.of(ORIGIN.offset(1, 0, 0), ORIGIN.offset(0, 1, 0))) {
            var local = new Resident(ORIGIN);
            var foreign = new Resident(neighbour);
            var overlapping = java.util.List.of(local, foreign);
            assertEquals(java.util.List.of(local),
                FillagerReconciliation.residentsInCell(overlapping, ORIGIN, Resident::owner));
            assertEquals(java.util.List.of(foreign),
                FillagerReconciliation.residentsInCell(overlapping, neighbour, Resident::owner));

            var neighbourResidents = FillagerReconciliation.residentsInCell(java.util.List.of(local),
                neighbour, Resident::owner);
            var params = new FillagerReconciliation.Params(0, 0, 8, 1, 2, 2);
            assertEquals(0, FillagerReconciliation.decide(neighbour, neighbourResidents.size(),
                params, cell -> 0).despawn(), "zero-target neighbour must not discard the local resident");
        }
    }

    @Test
    void samplingIsReadOnlyEvenWhenRepeated() {
        Map<CellKey, Integer> storage = new HashMap<>();
        storage.put(ORIGIN, 800);
        storage.put(ORIGIN.offset(1, 0, 0), 200);
        Map<CellKey, Integer> before = Map.copyOf(storage);
        FillagerReconciliation.sampledTarget(ORIGIN, 1, 0, cell -> storage.getOrDefault(cell, 0));
        FillagerReconciliation.sampledTarget(ORIGIN, 1, 0, cell -> storage.getOrDefault(cell, 0));
        assertEquals(before, storage, "sampling must never pool or write a target back to storage");
    }

    @Test
    void liveParamsBoundRefillAndCaps() {
        var slow = new FillagerReconciliation.Params(0, 0, 8, 1, 4, 2);
        var fast = new FillagerReconciliation.Params(0, 0, 8, 5, 4, 2);
        var reader = (java.util.function.ToIntFunction<CellKey>) cell -> 1000;
        assertEquals(1, FillagerReconciliation.decide(ORIGIN, 0, slow, reader).spawn());
        assertEquals(4, FillagerReconciliation.decide(ORIGIN, 0, fast, reader).spawn());
        assertEquals(2, FillagerReconciliation.decide(ORIGIN, 8, fast, cell -> 0).despawn());
    }

    @Test
    void fallingTargetDespawnsInsteadOfAccumulating() {
        var params = new FillagerReconciliation.Params(0, 0, 8, 2, 2, 3);
        assertEquals(3, FillagerReconciliation.decide(ORIGIN, 8, params, cell -> 0).despawn());
    }

    @Test
    void unsafeDeathOnlyChangesItsSingleLocalTarget() {
        Map<CellKey, Integer> storage = new HashMap<>();
        CellKey neighbour = ORIGIN.offset(1, 0, 0);
        storage.put(ORIGIN, 100);
        storage.put(neighbour, 700);
        storage.put(ORIGIN, FillagerReconciliation.unsafeDeathTargetAfter(storage.get(ORIGIN), 25));
        assertEquals(75, storage.get(ORIGIN));
        assertEquals(700, storage.get(neighbour), "unsafe death must not decrement the sampled neighbourhood");
    }
}
