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

    // Review fix (round 1), issue 1: a client-side leave event (same UUID, integrated client+server JVM)
    // must never touch the shared PENDING map at all.

    @Test
    void aClientSideLeaveEventNeverConsumesTheServerCapturedPending() {
        UUID id = UUID.randomUUID();
        PopulationDeathDecrement.capture(id, Faction.ZOMBIE, ORIGIN);

        var consumedByClient = PopulationDeathDecrement.consumeIfConfirmedServerKill(false, id, "KILLED");

        assertFalse(consumedByClient.isPresent(), "a client-side (non-server) leave event must never return a pending death");
        var stillPending = PopulationDeathDecrement.take(id);
        assertTrue(stillPending.isPresent(), "the server-captured pending death must survive an earlier "
            + "client-side leave event for the same UUID, untouched, for the real server-side event to find");
        assertEquals(ORIGIN, stillPending.get().cell());
    }

    @Test
    void aServerSideConfirmedKillConsumesAndReturnsThePendingDeath() {
        UUID id = UUID.randomUUID();
        PopulationDeathDecrement.capture(id, Faction.SKELETON, ORIGIN);

        var consumed = PopulationDeathDecrement.consumeIfConfirmedServerKill(true, id, "KILLED");

        assertTrue(consumed.isPresent());
        assertEquals(Faction.SKELETON, consumed.get().faction());
        assertFalse(PopulationDeathDecrement.take(id).isPresent(), "a confirmed KILLED consume must remove the entry");
    }

    @Test
    void aServerSideNonKillRemovalStillDiscardsTheStaleEntryButReturnsNothing() {
        // An ordinary chunk unload/reload, or an administrative discard, is still server-side and must still
        // clear a stale pending entry (so it cannot later be wrongly consumed by an unrelated future event
        // for a reused/colliding UUID scenario), but must never itself return a pending death to apply.
        UUID id = UUID.randomUUID();
        PopulationDeathDecrement.capture(id, Faction.VILLAGER, ORIGIN);

        var consumed = PopulationDeathDecrement.consumeIfConfirmedServerKill(true, id, "UNLOADED_TO_CHUNK");

        assertFalse(consumed.isPresent());
        assertFalse(PopulationDeathDecrement.take(id).isPresent(), "the entry must not be left dangling after a server-side leave");
    }

    // Review fix (round 1), issue 2: a stale capture from a CANCELLED death must not survive a later
    // onLivingDeath call for the same UUID that finds the entity no longer eligible -- see
    // FillerFactionSimulation.onLivingDeath's own "take() first, capture() only if still eligible" fix.

    @Test
    void takingFirstThenNotRecapturingDiscardsAStaleCancelledDeathBeforeTheRealOne() {
        UUID id = UUID.randomUUID();
        CellKey cancelledDeathCell = ORIGIN;
        CellKey realDeathCell = ORIGIN.offset(5, 0, 0);

        // The cancelled attempt: captured, never consumed (the death never reached confirmed removal).
        PopulationDeathDecrement.capture(id, Faction.ZOMBIE, cancelledDeathCell);

        // Models FillerFactionSimulation.onLivingDeath's fix: the entity has since become ineligible (e.g.
        // persistence-required), so this later onLivingDeath call for the SAME uuid takes-first (discarding
        // the stale cancelledDeathCell entry) and does NOT recapture, since it is no longer eligible.
        PopulationDeathDecrement.take(id);

        // The real death's confirmed removal must find nothing pending -- never the stale cancelled cell.
        var consumed = PopulationDeathDecrement.consumeIfConfirmedServerKill(true, id, "KILLED");
        assertFalse(consumed.isPresent(), "a stale cancelled-death capture must never survive to be applied "
            + "at the wrong cell once the entity becomes ineligible before its real death");
    }

    @Test
    void takingFirstThenRecapturingReplacesAStaleCancelledDeathWithTheRealOne() {
        UUID id = UUID.randomUUID();
        CellKey cancelledDeathCell = ORIGIN;
        CellKey realDeathCell = ORIGIN.offset(5, 0, 0);

        PopulationDeathDecrement.capture(id, Faction.ZOMBIE, cancelledDeathCell);

        // The entity is STILL eligible at its real death: take-first discards the stale entry, then capture
        // records the real death's own cell.
        PopulationDeathDecrement.take(id);
        PopulationDeathDecrement.capture(id, Faction.ZOMBIE, realDeathCell);

        var consumed = PopulationDeathDecrement.consumeIfConfirmedServerKill(true, id, "KILLED");
        assertTrue(consumed.isPresent());
        assertEquals(realDeathCell, consumed.get().cell(), "the real death's own cell must win, never the stale cancelled one");
    }
}
