package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfigTestSupport;
import io.github.brooswitminecraft.dynamicpopulation.propagation.CellKey;

class FillagerReconciliationTest {
    private static final CellKey ORIGIN = new CellKey(0, 0, 0);
    private static final int NO_LAYER_BOUND_LOW = Integer.MIN_VALUE;
    private static final int NO_LAYER_BOUND_HIGH = Integer.MAX_VALUE;

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
                params, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH, cell -> 0).despawn(),
                "zero-target neighbour must not discard the local resident");
        }
    }

    @Test
    void samplingIsReadOnlyEvenWhenRepeated() {
        Map<CellKey, Integer> storage = new HashMap<>();
        storage.put(ORIGIN, 800);
        storage.put(ORIGIN.offset(1, 0, 0), 200);
        Map<CellKey, Integer> before = Map.copyOf(storage);
        FillagerReconciliation.sampledTarget(ORIGIN, 1, 0, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH,
            cell -> storage.getOrDefault(cell, 0));
        FillagerReconciliation.sampledTarget(ORIGIN, 1, 0, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH,
            cell -> storage.getOrDefault(cell, 0));
        assertEquals(before, storage, "sampling must never pool or write a target back to storage");
    }

    @Test
    void nonexistentWorldLayersAreExcludedFromTheSample() {
        // Six layers (0..5), vertical radius 2, every valid layer uniformly at CAPACITY (1000 ppt). Without
        // clamping, a window near an edge counts out-of-world layers as 0 and dilutes the average; with
        // clamping, every in-bounds centre reads the same uniform 1000, interior or edge alike.
        java.util.function.ToIntFunction<CellKey> uniform = cell -> 1000;
        for (int layer : new int[] {0, 1, 2, 3, 4, 5}) {
            CellKey centre = new CellKey(0, layer, 0);
            assertEquals(1000, FillagerReconciliation.sampledTarget(centre, 0, 2, 0, 5, uniform),
                "layer " + layer + " must read the true uniform density, not a value diluted by nonexistent layers");
        }
    }

    @Test
    void unreadableCellsWithinBoundsStillCountAsRealZeroDensity() {
        // A cell inside the valid layer range that simply has no stored data is a real empty cell (0 ppt),
        // not an absent one: it must still count toward the denominator, unlike an out-of-world layer.
        assertEquals(500, FillagerReconciliation.sampledTarget(new CellKey(0, 0, 0), 0, 1, 0, 1,
            cell -> cell.layer() == 0 ? 1000 : 0));
    }

    @Test
    void liveParamsBoundRefillAndCaps() {
        var slow = new FillagerReconciliation.Params(0, 0, 8, 1, 4, 2);
        var fast = new FillagerReconciliation.Params(0, 0, 8, 5, 4, 2);
        var reader = (java.util.function.ToIntFunction<CellKey>) cell -> 1000;
        assertEquals(1, FillagerReconciliation.decide(ORIGIN, 0, slow, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH, reader).spawn());
        assertEquals(4, FillagerReconciliation.decide(ORIGIN, 0, fast, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH, reader).spawn());
        assertEquals(2, FillagerReconciliation.decide(ORIGIN, 8, fast, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH, cell -> 0).despawn());
    }

    @Test
    void liveConfigReloadChangesTheNextReconciliationDecisionWithoutRestart() {
        DynamicPopulationConfigTestSupport.load();
        var reader = (java.util.function.ToIntFunction<CellKey>) cell -> 1000;

        DynamicPopulationConfigTestSupport.reload("fillager_refill_per_interval", 1);
        var before = DynamicPopulationConfig.get();
        var paramsBefore = new FillagerReconciliation.Params(before.samplingHorizontalRadius(),
            before.samplingVerticalRadius(), before.fillagersPerFullCell(), before.reconciliationRefillPerInterval(),
            before.spawnCapPerInterval(), before.despawnCapPerInterval());
        assertEquals(1, FillagerReconciliation.decide(ORIGIN, 0, paramsBefore, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH, reader).spawn());

        DynamicPopulationConfigTestSupport.reload("fillager_refill_per_interval", 5);
        DynamicPopulationConfigTestSupport.reload("fillager_spawn_cap_per_interval", 5);
        var after = DynamicPopulationConfig.get();
        assertEquals(5, after.reconciliationRefillPerInterval(), "the live snapshot must observe the reload, not a frozen value");
        var paramsAfter = new FillagerReconciliation.Params(after.samplingHorizontalRadius(),
            after.samplingVerticalRadius(), after.fillagersPerFullCell(), after.reconciliationRefillPerInterval(),
            after.spawnCapPerInterval(), after.despawnCapPerInterval());
        assertEquals(5, FillagerReconciliation.decide(ORIGIN, 0, paramsAfter, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH, reader).spawn(),
            "the very next reconciliation decision must use the reloaded value, with no restart");

        DynamicPopulationConfigTestSupport.reload("fillager_refill_per_interval", 1);
        DynamicPopulationConfigTestSupport.reload("fillager_spawn_cap_per_interval", 2);
    }

    @Test
    void fallingTargetDespawnsInsteadOfAccumulating() {
        var params = new FillagerReconciliation.Params(0, 0, 8, 2, 2, 3);
        assertEquals(3, FillagerReconciliation.decide(ORIGIN, 8, params, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH, cell -> 0).despawn());
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

    @Test
    void unsafeDeathEventWiringDecrementsExactlyTheOneLocalCell() {
        // Exercises FillagerPopulationSimulation.onLivingDeath's actual wiring (applyUnsafeDeathIfUnsafe),
        // not just the arithmetic helper it eventually calls -- this is what the review flagged as missing.
        Map<CellKey, Integer> storage = new HashMap<>();
        CellKey neighbour = ORIGIN.offset(1, 0, 0);
        storage.put(ORIGIN, 100);
        storage.put(neighbour, 700);

        FillagerReconciliation.applyUnsafeDeathIfUnsafe(true, ORIGIN, storage::get, storage::put, 25);

        assertEquals(75, storage.get(ORIGIN), "the local cell must be decremented by exactly the configured amount");
        assertEquals(700, storage.get(neighbour), "a neighbouring cell must never be touched by a local unsafe death");
    }

    @Test
    void ordinaryRemovalOrPromotionNeverTouchesAnyTargetThroughTheDeathWiring() {
        // Ordinary despawn-over-target and promotion never fire LivingDeathEvent at all (they use discard()),
        // but if a death ever reaches this wiring unclassified as unsafe, storage must stay untouched -- no
        // read, no write -- exactly as "removing a Fillager by any other means never decrements any target".
        Map<CellKey, Integer> storage = new HashMap<>();
        storage.put(ORIGIN, 100);
        Map<CellKey, Integer> before = Map.copyOf(storage);

        FillagerReconciliation.applyUnsafeDeathIfUnsafe(false, ORIGIN,
            cell -> { throw new AssertionError("an unclassified death must never even read the local cell"); },
            (cell, value) -> { throw new AssertionError("an unclassified death must never write the local cell"); },
            25);

        assertEquals(before, storage);
    }
}
