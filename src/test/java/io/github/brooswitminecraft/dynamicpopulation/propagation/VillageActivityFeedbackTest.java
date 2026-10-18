package io.github.brooswitminecraft.dynamicpopulation.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfigTestSupport;
import io.github.brooswitminecraft.dynamicpopulation.reconciliation.FillagerReconciliation;

class VillageActivityFeedbackTest {
    private static final CellKey ORIGIN = new CellKey(0, 0, 0);

    @AfterEach
    void restoreLiveConfigDefaults() {
        DynamicPopulationConfigTestSupport.reload("village_trade_target_increment", 2);
        DynamicPopulationConfigTestSupport.reload("village_breeding_target_increment", 10);
        DynamicPopulationConfigTestSupport.reload("village_building_target_increment", 1);
        DynamicPopulationConfigTestSupport.reload("fillager_other_death_target_decrement", 10);
        DynamicPopulationConfigTestSupport.reload("village_destruction_target_decrement", 15);
    }

    // isConfirmedBreedingBirth is the pure half of the two-event (FinalizeSpawnEvent marks,
    // EntityJoinLevelEvent confirms-and-consumes) birth tracking onBreeding/onFinalizeSpawn use in
    // production -- this is what makes onBreeding read the CORRECT, post-moveTo position from the join
    // event rather than FinalizeSpawnEvent's own (always world-origin) coordinates, which the first version
    // of this fix got wrong. Generic over T so it is testable without a real Villager/Minecraft bootstrap.

    @Test
    void aVillagerFlaggedAtFinalizeSpawnTimeIsConfirmedExactlyOnceAtJoinTime() {
        Set<Object> pending = new HashSet<>();
        Object villager = new Object();
        pending.add(villager); // what onFinalizeSpawn does on a MobSpawnType.BREEDING Villager.

        assertTrue(VillageActivityFeedback.isConfirmedBreedingBirth(pending, villager),
            "a villager finalized with BREEDING must be confirmed once it joins the level");
        assertFalse(VillageActivityFeedback.isConfirmedBreedingBirth(pending, villager),
            "the same entity must never be confirmed a second time");
    }

    @Test
    void aVillagerThatJoinsWithoutEverBeingFlaggedIsNotConfirmed() {
        assertFalse(VillageActivityFeedback.isConfirmedBreedingBirth(new HashSet<>(), new Object()),
            "an ordinary villager join (not preceded by a MobSpawnType.BREEDING finalize) must not raise anything");
    }

    @Test
    void flaggingOneVillagerNeverConfirmsADifferentOne() {
        Set<Object> pending = new HashSet<>();
        Object flagged = new Object();
        Object other = new Object();
        pending.add(flagged);

        assertFalse(VillageActivityFeedback.isConfirmedBreedingBirth(pending, other));
        assertTrue(VillageActivityFeedback.isConfirmedBreedingBirth(pending, flagged),
            "the actually-flagged entity must remain confirmable after an unrelated lookup");
    }

    // Each of these drives VillageActivityFeedback.raise -- the exact adapter every raise event hook
    // (onTrade/onBreeding/onBuilding/onFarming/onGolemCreation) calls into -- using the LIVE, reloaded
    // config value, not a hardcoded literal, so a regression that stops reading the live snapshot (e.g.
    // reverting to a captured default) would fail these even though the plain scalar/map arithmetic is
    // unchanged.

    @Test
    void tradingRaisesTheLocalCellByTheLiveConfiguredWeightAndActivatesIt() {
        DynamicPopulationConfigTestSupport.reload("village_trade_target_increment", 77);
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(ORIGIN, 10));
        Set<CellKey> active = new HashSet<>();

        VillageActivityFeedback.raise(ORIGIN, DynamicPopulationConfig.get().villageTradeTargetIncrement(),
            storage::get, storage::put, active::add);

        assertEquals(87, storage.get(ORIGIN));
        assertTrue(active.contains(ORIGIN));
    }

    @Test
    void breedingRaisesTheLocalCellByTheLiveConfiguredWeightAndActivatesIt() {
        DynamicPopulationConfigTestSupport.reload("village_breeding_target_increment", 42);
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(ORIGIN, 0));
        Set<CellKey> active = new HashSet<>();

        VillageActivityFeedback.raise(ORIGIN, DynamicPopulationConfig.get().villageBreedingTargetIncrement(),
            storage::get, storage::put, active::add);

        assertEquals(42, storage.get(ORIGIN));
        assertTrue(active.contains(ORIGIN), "a birth into a previously-empty cell must activate it");
    }

    @Test
    void buildingRaisesTheLocalCellByTheLiveConfiguredWeight() {
        DynamicPopulationConfigTestSupport.reload("village_building_target_increment", 5);
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(ORIGIN, 100));
        Set<CellKey> active = new HashSet<>();

        VillageActivityFeedback.raise(ORIGIN, DynamicPopulationConfig.get().villageBuildingTargetIncrement(),
            storage::get, storage::put, active::add);

        assertEquals(105, storage.get(ORIGIN));
    }

    @Test
    void aLiveConfigReloadChangesTheNextRaiseWithoutRestart() {
        DynamicPopulationConfigTestSupport.reload("village_trade_target_increment", 5);
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(ORIGIN, 0));
        VillageActivityFeedback.raise(ORIGIN, DynamicPopulationConfig.get().villageTradeTargetIncrement(),
            storage::get, storage::put, cell -> { });
        assertEquals(5, storage.get(ORIGIN));

        DynamicPopulationConfigTestSupport.reload("village_trade_target_increment", 50);
        VillageActivityFeedback.raise(ORIGIN, DynamicPopulationConfig.get().villageTradeTargetIncrement(),
            storage::get, storage::put, cell -> { });
        assertEquals(55, storage.get(ORIGIN), "the very next raise must use the reloaded weight, with no restart");
    }

    // The negative-feedback path (a non-unsafe-location Fillager death, and destruction) driven by its own
    // live-reloaded config value through FillagerReconciliation's production methods.

    @Test
    void theOtherFillagerDeathPathLowersByTheLiveConfiguredWeight() {
        DynamicPopulationConfigTestSupport.reload("fillager_other_death_target_decrement", 33);
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(ORIGIN, 100));

        FillagerReconciliation.adjustLocalTarget(ORIGIN, -DynamicPopulationConfig.get().fillagerOtherDeathTargetDecrement(),
            storage::get, storage::put);

        assertEquals(67, storage.get(ORIGIN));
    }

    @Test
    void destructionLowersEveryTouchedCellByTheLiveConfiguredWeight() {
        DynamicPopulationConfigTestSupport.reload("village_destruction_target_decrement", 20);
        CellKey second = ORIGIN.offset(1, 0, 0);
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(ORIGIN, 100, second, 100));

        FillagerReconciliation.applyDestruction(Set.of(ORIGIN, second),
            DynamicPopulationConfig.get().villageDestructionTargetDecrement(), storage::get, storage::put);

        assertEquals(80, storage.get(ORIGIN));
        assertEquals(80, storage.get(second));
    }
}
