package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfigTestSupport;
import io.github.brooswitminecraft.dynamicpopulation.propagation.CellKey;

class FillagerReconciliationTest {
    private static final CellKey ORIGIN = new CellKey(0, 0, 0);
    private static final int NO_LAYER_BOUND_LOW = Integer.MIN_VALUE;
    private static final int NO_LAYER_BOUND_HIGH = Integer.MAX_VALUE;
    private static final java.util.function.Predicate<CellKey> ALWAYS_READABLE = cell -> true;

    @AfterEach
    void restoreLiveConfigDefaults() {
        // liveConfigReloadChangesTheNextReconciliationDecisionWithoutRestart mutates the live, process-wide
        // DynamicPopulationConfig snapshot; restoring here (rather than inline at the end of that test body)
        // means a mid-test assertion failure still leaves the defaults restored for every later test in this
        // JVM, instead of leaking refill=5/spawn_cap=5 into them and masking the real failure.
        DynamicPopulationConfigTestSupport.reload("fillager_refill_per_interval", 1);
        DynamicPopulationConfigTestSupport.reload("fillager_spawn_cap_per_interval", 2);
    }

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
                params, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH, ALWAYS_READABLE, cell -> 0).despawn(),
                "zero-target neighbour must not discard the local resident");
        }
    }

    @Test
    void samplingIsReadOnlyEvenWhenRepeated() {
        Map<CellKey, Integer> storage = new HashMap<>();
        storage.put(ORIGIN, 800);
        storage.put(ORIGIN.offset(1, 0, 0), 200);
        Map<CellKey, Integer> before = Map.copyOf(storage);
        FillagerReconciliation.sampledTarget(ORIGIN, 1, 0, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH, ALWAYS_READABLE,
            cell -> storage.getOrDefault(cell, 0));
        FillagerReconciliation.sampledTarget(ORIGIN, 1, 0, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH, ALWAYS_READABLE,
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
            assertEquals(1000, FillagerReconciliation.sampledTarget(centre, 0, 2, 0, 5, ALWAYS_READABLE, uniform),
                "layer " + layer + " must read the true uniform density, not a value diluted by nonexistent layers");
        }
    }

    @Test
    void inBoundsEmptyCellsStillCountAsRealZeroDensity() {
        // A cell inside the valid layer range, and readable, that simply has no stored data is a real empty
        // cell (0 ppt), not an absent one: it must still count toward the denominator, unlike an
        // out-of-world layer or an unreadable one (see the two tests below).
        assertEquals(500, FillagerReconciliation.sampledTarget(new CellKey(0, 0, 0), 0, 1, 0, 1, ALWAYS_READABLE,
            cell -> cell.layer() == 0 ? 1000 : 0));
    }

    @Test
    void unreadableCellsAreExcludedFromTheSampleLikeOutOfWorldLayers() {
        // Centre column (x=0,z=0) readable at CAPACITY; every other column in a 3x3x1 window unreadable
        // (anchor chunk not loaded, say). Diluting with those as zero would read 1000/9 = 111; excluding
        // them must read the true 1000 -- the same bug class as the world-layer dilution, but for chunk
        // load state instead of world height.
        Set<CellKey> readableCells = Set.of(ORIGIN);
        assertEquals(1000, FillagerReconciliation.sampledTarget(ORIGIN, 1, 0, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH,
            readableCells::contains, cell -> 1000));
    }

    @Test
    void theReaderIsNeverConsultedForAnUnreadableCell() {
        // Mirrors the layer clamp's own contract: an excluded cell is skipped before any read, not read and
        // discarded. A reader that throws for anything but the centre proves this for readability too.
        Set<CellKey> readableCells = Set.of(ORIGIN);
        java.util.function.ToIntFunction<CellKey> readOrFail = cell -> {
            if (!readableCells.contains(cell)) {
                fail("sampledTarget must not call read() for an unreadable cell: " + cell);
            }
            return 1000;
        };
        assertEquals(1000, FillagerReconciliation.sampledTarget(ORIGIN, 1, 1, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH,
            readableCells::contains, readOrFail));
    }

    @Test
    void liveParamsBoundRefillAndCaps() {
        var slow = new FillagerReconciliation.Params(0, 0, 8, 1, 4, 2);
        var fast = new FillagerReconciliation.Params(0, 0, 8, 5, 4, 2);
        var reader = (java.util.function.ToIntFunction<CellKey>) cell -> 1000;
        assertEquals(1, FillagerReconciliation.decide(ORIGIN, 0, slow, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH,
            ALWAYS_READABLE, reader).spawn());
        assertEquals(4, FillagerReconciliation.decide(ORIGIN, 0, fast, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH,
            ALWAYS_READABLE, reader).spawn());
        assertEquals(2, FillagerReconciliation.decide(ORIGIN, 8, fast, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH,
            ALWAYS_READABLE, cell -> 0).despawn());
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
        assertEquals(1, FillagerReconciliation.decide(ORIGIN, 0, paramsBefore, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH,
            ALWAYS_READABLE, reader).spawn());

        DynamicPopulationConfigTestSupport.reload("fillager_refill_per_interval", 5);
        DynamicPopulationConfigTestSupport.reload("fillager_spawn_cap_per_interval", 5);
        var after = DynamicPopulationConfig.get();
        assertEquals(5, after.reconciliationRefillPerInterval(), "the live snapshot must observe the reload, not a frozen value");
        var paramsAfter = new FillagerReconciliation.Params(after.samplingHorizontalRadius(),
            after.samplingVerticalRadius(), after.fillagersPerFullCell(), after.reconciliationRefillPerInterval(),
            after.spawnCapPerInterval(), after.despawnCapPerInterval());
        assertEquals(5, FillagerReconciliation.decide(ORIGIN, 0, paramsAfter, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH,
            ALWAYS_READABLE, reader).spawn(),
            "the very next reconciliation decision must use the reloaded value, with no restart");
    }

    @Test
    void fallingTargetDespawnsInsteadOfAccumulating() {
        var params = new FillagerReconciliation.Params(0, 0, 8, 2, 2, 3);
        assertEquals(3, FillagerReconciliation.decide(ORIGIN, 8, params, NO_LAYER_BOUND_LOW, NO_LAYER_BOUND_HIGH,
            ALWAYS_READABLE, cell -> 0).despawn());
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

    // AC6 (MINECRAFT-30): village activity feedback.

    @Test
    void positiveActivityRaisesOnlyTheLocalCell() {
        Map<CellKey, Integer> storage = new HashMap<>();
        CellKey neighbour = ORIGIN.offset(1, 0, 0);
        storage.put(ORIGIN, 100);
        storage.put(neighbour, 700);

        FillagerReconciliation.adjustLocalTarget(ORIGIN, 10, storage::get, storage::put);

        assertEquals(110, storage.get(ORIGIN), "a positive delta must raise exactly the local cell");
        assertEquals(700, storage.get(neighbour), "a neighbouring cell must never be touched");
    }

    @Test
    void negativeActivityLowersOnlyTheLocalCell() {
        Map<CellKey, Integer> storage = new HashMap<>();
        storage.put(ORIGIN, 100);

        FillagerReconciliation.adjustLocalTarget(ORIGIN, -40, storage::get, storage::put);

        assertEquals(60, storage.get(ORIGIN));
    }

    @Test
    void targetAdjustmentNeverGoesBelowZero() {
        assertEquals(0, FillagerReconciliation.targetAfterAdjustment(10, -40),
            "a lowering event must clamp at zero, never go negative");
    }

    @Test
    void targetAdjustmentNeverExceedsCapacity() {
        assertEquals(1000, FillagerReconciliation.targetAfterAdjustment(990, 500),
            "a raising event must clamp at the field's own CAPACITY, matching LevelField.set's own clamp");
    }

    @Test
    void destructionDecrementsEveryDistinctTouchedCellExactlyOnce() {
        Map<CellKey, Integer> storage = new HashMap<>();
        CellKey second = ORIGIN.offset(1, 0, 0);
        CellKey untouched = ORIGIN.offset(5, 0, 0);
        storage.put(ORIGIN, 100);
        storage.put(second, 100);
        storage.put(untouched, 100);

        FillagerReconciliation.applyDestruction(Set.of(ORIGIN, second), 30, storage::get, storage::put);

        assertEquals(70, storage.get(ORIGIN));
        assertEquals(70, storage.get(second));
        assertEquals(100, storage.get(untouched), "a cell an explosion never touched must be left alone");
    }

    @Test
    void raisingAnInitiallyEmptyCellEnrollsItInTheActiveSet() {
        // The actual production join point VillageActivityFeedback.raise calls: without this, a cell with
        // no existing Fillagers and outside King-seeding range would have its stored target raised but stay
        // invisible to propagation (which only steps the active set) and reconciliation (active set plus
        // cells containing existing Fillagers) until some unrelated event happened to add it.
        Map<CellKey, Integer> storage = new HashMap<>();
        storage.put(ORIGIN, 0);
        Set<CellKey> active = new java.util.HashSet<>();

        FillagerReconciliation.raiseLocalTargetAndActivate(ORIGIN, 10, storage::get, storage::put, active::add);

        assertEquals(10, storage.get(ORIGIN));
        assertTrue(active.contains(ORIGIN), "a raise that makes the stored target positive must enroll the cell in the active set");
    }

    @Test
    void raisingACellThatStaysAtOrBelowZeroDoesNotEnrollIt() {
        Map<CellKey, Integer> storage = new HashMap<>();
        storage.put(ORIGIN, 0);
        Set<CellKey> active = new java.util.HashSet<>();

        // A 0 config weight is possible (the min range is 1, but defensively: any non-positive outcome).
        FillagerReconciliation.raiseLocalTargetAndActivate(ORIGIN, 0, storage::get, storage::put, active::add);

        assertEquals(0, storage.get(ORIGIN));
        assertTrue(active.isEmpty(), "a raise that leaves the cell at zero must not mark it active");
    }

    @Test
    void raisingAnAlreadyActiveCellIsUnaffected() {
        Map<CellKey, Integer> storage = new HashMap<>();
        storage.put(ORIGIN, 50);
        Set<CellKey> active = new java.util.HashSet<>(Set.of(ORIGIN));

        FillagerReconciliation.raiseLocalTargetAndActivate(ORIGIN, 10, storage::get, storage::put, active::add);

        assertEquals(60, storage.get(ORIGIN));
        assertEquals(Set.of(ORIGIN), active);
    }
}
