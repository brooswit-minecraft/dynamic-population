package io.github.brooswitminecraft.dynamicpopulation.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfigTestSupport;

/**
 * Every test here drives a package-private adapter {@code VillageActivityFeedback}'s own
 * {@code @SubscribeEvent} handlers call into -- never a re-derived copy of their logic -- and never passes
 * in an already-resolved weight: each adapter selects its OWN live {@code DynamicPopulationConfig} getter
 * internally, the same way production does, so a bug that reads the wrong activity's weight (or omits the
 * call entirely) is something these tests can actually catch. Cells are deliberately non-origin throughout,
 * so a production adapter that silently hardcoded {@code CellKey(0,0,0)} instead of using the cell it was
 * given would also fail these.
 */
class VillageActivityFeedbackTest {
    private static final CellKey FAR_CELL = new CellKey(37, 2, -19);

    @AfterEach
    void restoreLiveConfigDefaults() {
        DynamicPopulationConfigTestSupport.reload("village_trade_target_increment", 2);
        DynamicPopulationConfigTestSupport.reload("village_breeding_target_increment", 10);
        DynamicPopulationConfigTestSupport.reload("village_building_target_increment", 1);
        DynamicPopulationConfigTestSupport.reload("village_farming_target_increment", 3);
        DynamicPopulationConfigTestSupport.reload("village_golem_creation_target_increment", 50);
        DynamicPopulationConfigTestSupport.reload("village_destruction_target_decrement", 15);
    }

    @Test
    void tradingRaisesTheGivenCellByItsOwnLiveConfiguredWeightAndActivatesIt() {
        DynamicPopulationConfigTestSupport.reload("village_trade_target_increment", 77);
        // A different activity's weight set to something that would make a mix-up obvious.
        DynamicPopulationConfigTestSupport.reload("village_building_target_increment", 999);
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(FAR_CELL, 10));
        Set<CellKey> active = new HashSet<>();

        VillageActivityFeedback.raiseForTrade(FAR_CELL, storage::get, storage::put, active::add);

        assertEquals(87, storage.get(FAR_CELL), "trading must use its own weight, never another activity's");
        assertTrue(active.contains(FAR_CELL));
    }

    @Test
    void buildingRaisesTheGivenCellByItsOwnLiveConfiguredWeight() {
        DynamicPopulationConfigTestSupport.reload("village_building_target_increment", 5);
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(FAR_CELL, 100));

        VillageActivityFeedback.raiseForBuilding(FAR_CELL, storage::get, storage::put, cell -> { });

        assertEquals(105, storage.get(FAR_CELL));
    }

    @Test
    void farmingRaisesTheGivenCellByItsOwnLiveConfiguredWeight() {
        DynamicPopulationConfigTestSupport.reload("village_farming_target_increment", 9);
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(FAR_CELL, 0));
        Set<CellKey> active = new HashSet<>();

        VillageActivityFeedback.raiseForFarming(FAR_CELL, storage::get, storage::put, active::add);

        assertEquals(9, storage.get(FAR_CELL));
        assertTrue(active.contains(FAR_CELL));
    }

    @Test
    void golemCreationRaisesTheGivenCellByItsOwnLiveConfiguredWeight() {
        DynamicPopulationConfigTestSupport.reload("village_golem_creation_target_increment", 61);
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(FAR_CELL, 0));

        VillageActivityFeedback.raiseForGolemCreation(FAR_CELL, storage::get, storage::put, cell -> { });

        assertEquals(61, storage.get(FAR_CELL));
    }

    @Test
    void aLiveConfigReloadChangesTheNextTradeRaiseWithoutRestart() {
        DynamicPopulationConfigTestSupport.reload("village_trade_target_increment", 5);
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(FAR_CELL, 0));
        VillageActivityFeedback.raiseForTrade(FAR_CELL, storage::get, storage::put, cell -> { });
        assertEquals(5, storage.get(FAR_CELL));

        DynamicPopulationConfigTestSupport.reload("village_trade_target_increment", 50);
        VillageActivityFeedback.raiseForTrade(FAR_CELL, storage::get, storage::put, cell -> { });
        assertEquals(55, storage.get(FAR_CELL), "the very next raise must use the reloaded weight, with no restart");
    }

    // Breeding: confirmBreedingBirth is both the pure half of the two-event (FinalizeSpawnEvent marks,
    // EntityJoinLevelEvent confirms-and-consumes-and-raises) birth tracking, and the activity-specific
    // adapter onBreeding calls into -- proving the CORRECT, post-moveTo join-time cell is used (not
    // FinalizeSpawnEvent's own always-world-origin coordinates, which the first version of this fix got
    // wrong), the live breeding weight is selected internally, and an unconfirmed join never touches storage.

    @Test
    void aConfirmedBreedingBirthRaisesTheGivenNonOriginCellByTheLiveWeightAndConsumesTheMark() {
        DynamicPopulationConfigTestSupport.reload("village_breeding_target_increment", 42);
        Object villager = new Object();
        Set<Object> pendingBreedingSpawns = new HashSet<>(Set.of(villager)); // what onFinalizeSpawn does.
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(FAR_CELL, 0));
        Set<CellKey> active = new HashSet<>();

        VillageActivityFeedback.confirmBreedingBirth(pendingBreedingSpawns, villager, FAR_CELL, storage::get,
            storage::put, active::add);

        assertEquals(42, storage.get(FAR_CELL));
        assertTrue(active.contains(FAR_CELL), "a birth into a previously-empty cell must activate it");
        assertTrue(pendingBreedingSpawns.isEmpty(), "the one-shot mark must be consumed");
    }

    @Test
    void anUnconfirmedJoinNeverTouchesStorageAtAll() {
        // An ordinary villager joining the level (not preceded by a MobSpawnType.BREEDING finalize) must
        // not read, write, or activate -- not just leave the value unchanged.
        VillageActivityFeedback.confirmBreedingBirth(new HashSet<>(), new Object(), FAR_CELL,
            cell -> { throw new AssertionError("an unconfirmed join must never read the cell"); },
            (cell, value) -> fail("an unconfirmed join must never write the cell"),
            cell -> fail("an unconfirmed join must never activate the cell"));
    }

    @Test
    void confirmingOneVillagerNeverConsumesOrRaisesForADifferentOne() {
        Object flagged = new Object();
        Object other = new Object();
        Set<Object> pendingBreedingSpawns = new HashSet<>(Set.of(flagged));
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(FAR_CELL, 0));

        VillageActivityFeedback.confirmBreedingBirth(pendingBreedingSpawns, other, FAR_CELL, storage::get,
            storage::put, cell -> fail("a different, unflagged entity must never activate the cell"));

        assertEquals(0, storage.get(FAR_CELL), "looking up an unflagged entity must not raise anything");
        assertTrue(pendingBreedingSpawns.contains(flagged), "the actually-flagged entity's mark must remain");
    }

    // Destruction: lowerForDestruction is the activity-specific adapter onExplosion calls into.

    @Test
    void destructionLowersEveryTouchedCellByItsOwnLiveConfiguredWeight() {
        DynamicPopulationConfigTestSupport.reload("village_destruction_target_decrement", 20);
        CellKey second = FAR_CELL.offset(1, 0, 0);
        CellKey untouched = FAR_CELL.offset(9, 0, 0);
        Map<CellKey, Integer> storage = new HashMap<>(Map.of(FAR_CELL, 100, second, 100, untouched, 100));

        VillageActivityFeedback.lowerForDestruction(Set.of(FAR_CELL, second), storage::get, storage::put);

        assertEquals(80, storage.get(FAR_CELL));
        assertEquals(80, storage.get(second));
        assertEquals(100, storage.get(untouched), "a cell an explosion never touched must be left alone");
    }
}
