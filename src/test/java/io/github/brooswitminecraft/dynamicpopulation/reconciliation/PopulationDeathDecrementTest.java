package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfig;
import io.github.brooswitminecraft.dynamicpopulation.config.DynamicPopulationConfigTestSupport;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.propagation.CellKey;

/**
 * MINECRAFT-425: the shared capture/consume/apply wiring behind the flat -25-at-death-cell decrement, used
 * identically by every population-owned entity type (Fillager, tagged Zombie/Skeleton filler, and all three
 * Kings). Entity-type-specific capture call sites ({@code FillagerPopulationSimulation.onLivingDeath},
 * {@code FillerFactionSimulation.onLivingDeath}, {@code king.KingDeathHandling.onLivingDeath}) are Minecraft
 * wiring that cannot be unit-tested without a running game; this class is the pure, fully-tested half they
 * all delegate to.
 */
class PopulationDeathDecrementTest {
    private static final CellKey ORIGIN = new CellKey(0, 0, 0);

    @AfterEach
    void clearPendingDeaths() {
        // PopulationDeathDecrement.PENDING is process-wide (keyed by entity UUID, not per-test); a failed
        // assertion mid-test must not leak a pending entry into a later, unrelated test in this JVM.
        PopulationDeathDecrement.clear();
    }

    @Test
    void capturedDeathIsConsumedExactlyOnce() {
        UUID id = UUID.randomUUID();
        PopulationDeathDecrement.capture(id, Faction.ZOMBIE, ORIGIN);

        var first = PopulationDeathDecrement.take(id);
        assertTrue(first.isPresent(), "a captured death must be returned the first time it is taken");
        assertEquals(Faction.ZOMBIE, first.get().faction());
        assertEquals(ORIGIN, first.get().cell());

        var second = PopulationDeathDecrement.take(id);
        assertFalse(second.isPresent(), "a second take for the same id must find nothing -- repeated callbacks "
            + "for the same actual death must never apply twice");
    }

    @Test
    void takingAnUncapturedIdFindsNothing() {
        // Covers every non-death removal (ordinary reconciliation discard/despawn, chunk unload/reload,
        // promotion/conversion): none of these ever call capture(), so take() must find nothing for them.
        assertFalse(PopulationDeathDecrement.take(UUID.randomUUID()).isPresent());
    }

    @Test
    void aLaterCaptureForTheSameIdOverwritesAnEarlierOne() {
        // Models a cancelled death (capture happens, but the entity survives and the pending entry is never
        // taken) followed by that same entity's later, genuine death: the stale entry must not linger
        // underneath or get applied instead of the real one.
        UUID id = UUID.randomUUID();
        CellKey elsewhere = ORIGIN.offset(5, 0, 0);
        PopulationDeathDecrement.capture(id, Faction.VILLAGER, ORIGIN);
        PopulationDeathDecrement.capture(id, Faction.VILLAGER, elsewhere);

        var pending = PopulationDeathDecrement.take(id);
        assertTrue(pending.isPresent());
        assertEquals(elsewhere, pending.get().cell(), "the later capture must win, not the stale cancelled one");
    }

    @Test
    void applyDecrementsOnlyThePendingCell() {
        Map<CellKey, Integer> storage = new HashMap<>();
        CellKey neighbour = ORIGIN.offset(1, 0, 0);
        storage.put(ORIGIN, 100);
        storage.put(neighbour, 700);

        PopulationDeathDecrement.apply(new PopulationDeathDecrement.Pending(Faction.VILLAGER, ORIGIN), 25,
            storage::get, storage::put);

        assertEquals(75, storage.get(ORIGIN), "the local death cell must drop by exactly the decrement");
        assertEquals(700, storage.get(neighbour), "a neighbouring cell must never be touched");
    }

    @Test
    void applyFloorsAtZeroRatherThanGoingNegative() {
        // Ticket example: 0/10/25 all floor to 0 for a -25 decrement.
        for (int starting : new int[] {0, 10, 25}) {
            Map<CellKey, Integer> storage = new HashMap<>();
            storage.put(ORIGIN, starting);
            PopulationDeathDecrement.apply(new PopulationDeathDecrement.Pending(Faction.SKELETON, ORIGIN), 25,
                storage::get, storage::put);
            assertEquals(0, storage.get(ORIGIN), "starting value " + starting + " must floor to 0");
        }
    }

    @Test
    void applyMatchesTheTicketsWorkedExamples() {
        Map<CellKey, Integer> storage = new HashMap<>();
        storage.put(ORIGIN, 26);
        PopulationDeathDecrement.apply(new PopulationDeathDecrement.Pending(Faction.VILLAGER, ORIGIN), 25,
            storage::get, storage::put);
        assertEquals(1, storage.get(ORIGIN));

        storage.put(ORIGIN, 500);
        PopulationDeathDecrement.apply(new PopulationDeathDecrement.Pending(Faction.VILLAGER, ORIGIN), 25,
            storage::get, storage::put);
        assertEquals(475, storage.get(ORIGIN));
    }

    @Test
    void multipleDistinctDeathsEachApplyOnceWithNoLostUpdates() {
        Map<CellKey, Integer> storage = new HashMap<>();
        storage.put(ORIGIN, 1000);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        PopulationDeathDecrement.capture(first, Faction.VILLAGER, ORIGIN);
        PopulationDeathDecrement.capture(second, Faction.VILLAGER, ORIGIN);

        PopulationDeathDecrement.take(first).ifPresent(pending ->
            PopulationDeathDecrement.apply(pending, 25, storage::get, storage::put));
        PopulationDeathDecrement.take(second).ifPresent(pending ->
            PopulationDeathDecrement.apply(pending, 25, storage::get, storage::put));

        assertEquals(950, storage.get(ORIGIN), "two distinct deaths at the same cell must each apply once, "
            + "not be lost to a stale read");
    }

    @Test
    void theConfiguredDefaultDecrementIsTwentyFive() {
        DynamicPopulationConfigTestSupport.load();
        assertEquals(25, DynamicPopulationConfig.get().populationDeathTargetDecrement());
    }
}
