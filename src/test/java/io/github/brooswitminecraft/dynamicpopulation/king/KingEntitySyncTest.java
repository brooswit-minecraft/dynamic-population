package io.github.brooswitminecraft.dynamicpopulation.king;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import org.junit.jupiter.api.Test;

/** No-duplicate-entity-on-reload coverage (ticket's "Tests to write" list), as a pure function. */
class KingEntitySyncTest {
    /** A record whose entity has already been confirmed real at least once -- the ordinary case post-MINECRAFT-390. */
    private static KingRecord.Entry entry(UUID id, Faction faction) {
        return new KingRecord.Entry(id, 0, 64, 0, faction, true);
    }

    /** A genuine pre-390 legacy record: never had a real entity at all until KingEntitySync's own migration spawn. */
    private static KingRecord.Entry legacyEntry(UUID id, Faction faction) {
        return new KingRecord.Entry(id, 0, 64, 0, faction, false);
    }

    @Test
    void recordWithNoLoadedEntityIsMissing() {
        UUID id = UUID.randomUUID();
        var missing = KingEntitySync.missingEntities(List.of(entry(id, Faction.VILLAGER)), Set.of());
        assertEquals(1, missing.size());
        assertEquals(id, missing.get(0).id());
    }

    @Test
    void recordWithAlreadyLoadedEntityIsNotMissing() {
        UUID id = UUID.randomUUID();
        var missing = KingEntitySync.missingEntities(List.of(entry(id, Faction.ZOMBIE)), Set.of(id));
        assertTrue(missing.isEmpty(), "an already-loaded King's entity must never be reported missing");
    }

    @Test
    void onlyTheActuallyMissingRecordIsReported() {
        UUID present = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        var missing = KingEntitySync.missingEntities(
            List.of(entry(present, Faction.VILLAGER), entry(missingId, Faction.SKELETON)), Set.of(present));
        assertEquals(1, missing.size());
        assertEquals(missingId, missing.get(0).id());
    }

    @Test
    void noRecordsIsNeverMissingAnything() {
        assertTrue(KingEntitySync.missingEntities(List.of(), Set.of()).isEmpty());
    }

    @Test
    void everyRecordAlreadyLoadedMeansNothingMissing() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var missing = KingEntitySync.missingEntities(
            List.of(entry(a, Faction.VILLAGER), entry(b, Faction.ZOMBIE)), Set.of(a, b));
        assertTrue(missing.isEmpty(), "a reload where every King's entity already reloaded must spawn nothing new");
    }

    // --- MINECRAFT-414 bug fix (round 1): KingRecord.updatePosition keeps the anchor current ---

    @Test
    void updatePositionReplacesStoredCoordinatesKeepingIdAndFaction() {
        UUID id = UUID.randomUUID();
        KingRecord record = new KingRecord();
        record.add(entry(id, Faction.ZOMBIE)); // founded at anchor A
        record.updatePosition(id, 500, 70, 500); // sync observed it loaded, wandered to B
        KingRecord.Entry updated = record.kings().get(0);
        assertEquals(500, updated.x());
        assertEquals(70, updated.y());
        assertEquals(500, updated.z());
        assertEquals(id, updated.id(), "updatePosition must never change the King's identity");
        assertEquals(Faction.ZOMBIE, updated.faction(), "updatePosition must never change the King's faction");
        assertTrue(updated.hasEntity());
    }

    @Test
    void updatePositionLeavesOtherEntriesUntouched() {
        UUID moved = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        KingRecord record = new KingRecord();
        record.add(entry(moved, Faction.VILLAGER));
        record.add(new KingRecord.Entry(other, 10, 64, 10, Faction.SKELETON, true));
        record.updatePosition(moved, 999, 64, 999);
        var others = record.kings().stream().filter(e -> e.id().equals(other)).findFirst().orElseThrow();
        assertEquals(10, others.x());
        assertEquals(10, others.z());
    }

    @Test
    void updatePositionOnAnUnknownIdIsANoOp() {
        KingRecord record = new KingRecord();
        UUID present = UUID.randomUUID();
        record.add(new KingRecord.Entry(present, 1, 64, 1, Faction.VILLAGER, true));
        record.updatePosition(UUID.randomUUID(), 999, 64, 999); // not in the record at all
        assertEquals(1, record.kings().size());
        assertEquals(1, record.kings().get(0).x());
    }

    @Test
    void markHasEntitySetsTheFlagWithoutChangingPosition() {
        UUID id = UUID.randomUUID();
        KingRecord record = new KingRecord();
        record.add(legacyEntry(id, Faction.VILLAGER));
        record.markHasEntity(id);
        var updated = record.kings().get(0);
        assertTrue(updated.hasEntity());
        assertEquals(0, updated.x());
        assertEquals(64, updated.y());
        assertEquals(0, updated.z());
    }

    // --- [P1 review fix, round 2] needingFirstSpawn: eliminates the duplicate-entity bug, not just narrows it ---

    @Test
    void genuineLegacyRecordWithLoadedAnchorNeedsItsFirstSpawn() {
        UUID id = UUID.randomUUID();
        var missing = List.of(legacyEntry(id, Faction.VILLAGER));
        var toSpawn = KingEntitySync.needingFirstSpawn(missing, e -> true); // anchor chunk loaded
        assertEquals(1, toSpawn.size(), "a genuine pre-390 legacy record with its anchor chunk loaded must get its first-ever entity");
    }

    @Test
    void genuineLegacyRecordWithUnloadedAnchorWaits() {
        UUID id = UUID.randomUUID();
        var missing = List.of(legacyEntry(id, Faction.VILLAGER));
        var toSpawn = KingEntitySync.needingFirstSpawn(missing, e -> false); // anchor chunk not loaded
        assertTrue(toSpawn.isEmpty());
    }

    /**
     * THE EXACT FAILING ORDERING THE REVIEW DESCRIBED: a King already confirmed real ({@code hasEntity} is
     * {@code true}) is observed loaded at A, then wanders into B, and B's chunk unloads BEFORE the next
     * sync call ever gets a chance to observe it at B (sync only runs every {@code king_seed_interval_ticks},
     * not every tick) -- so the stored anchor is STILL A, which (per this exact scenario) happens to still
     * be loaded (e.g. spawn chunks). The position-refresh-only fix from the prior round does nothing here,
     * since sync never observed B to refresh the anchor to it. {@link KingEntitySync#needingFirstSpawn}
     * must still refuse to spawn, because it checks {@code hasEntity} FIRST, unconditionally, before ever
     * consulting the (here, misleadingly still-loaded) anchor at all.
     */
    @Test
    void aKingAlreadyConfirmedRealNeverGetsRespawnedEvenWhenItsStaleAnchorHappensToStillBeLoaded() {
        UUID id = UUID.randomUUID();
        // entry() => hasEntity true, position frozen at stale anchor A = (0, 64, 0); the King is, in
        // truth, actually unloaded somewhere far away at B, which this record never even learned about.
        var missing = List.of(entry(id, Faction.ZOMBIE));
        var toSpawn = KingEntitySync.needingFirstSpawn(missing, e -> true); // stale anchor A IS loaded
        assertTrue(toSpawn.isEmpty(), "a King already confirmed real must never be respawned, no matter "
            + "what any stale anchor position says is loaded -- this is what actually ELIMINATES the "
            + "duplicate-entity bug, not merely narrows its race window");
    }

    @Test
    void mixedMissingListOnlyRespawnsTheGenuineLegacyRecord() {
        UUID legacyId = UUID.randomUUID();
        UUID alreadyRealId = UUID.randomUUID();
        var missing = List.of(legacyEntry(legacyId, Faction.VILLAGER), entry(alreadyRealId, Faction.ZOMBIE));
        var toSpawn = KingEntitySync.needingFirstSpawn(missing, e -> true); // both anchors "loaded"
        assertEquals(1, toSpawn.size());
        assertEquals(legacyId, toSpawn.get(0).id(), "only the genuine legacy record may be spawned; the "
            + "already-real King must never be duplicated regardless of its stale anchor's loaded state");
    }

    /**
     * Full end-to-end composition (missingEntities -> needingFirstSpawn), the same shape {@code sync}
     * itself uses: a King already confirmed real, observed loaded at A, wanders to B, B unloads WITHOUT
     * sync ever observing B first (so the anchor is still stale A, which happens to be loaded) -- the
     * composed pipeline must still produce zero respawns.
     */
    @Test
    void endToEndStaleAnchorNeverProducesADuplicateSpawn() {
        UUID id = UUID.randomUUID();
        KingRecord record = new KingRecord();
        record.add(entry(id, Faction.SKELETON)); // already real, anchor frozen at A

        // This sync tick: entity not found loaded anywhere (it's actually at B, whose chunk just unloaded,
        // but this record/sync call has no way to know that -- exactly the review's scenario).
        var missing = KingEntitySync.missingEntities(record.kings(), Set.<UUID>of());
        assertEquals(1, missing.size(), "sync correctly observes the entity as currently not loaded");

        var toSpawn = KingEntitySync.needingFirstSpawn(missing, e -> true); // stale anchor A IS loaded
        assertTrue(toSpawn.isEmpty(), "no duplicate must ever be spawned at the stale anchor for a King already confirmed real");
    }

    // --- [P1/P3 re-review fixes] insertion-confirmation gating: no phantom source, no permanent retry block ---

    /**
     * (a) A failed spawn (insertion never actually confirmed -- modeled by simply never calling
     * {@code markHasEntity}, exactly what the fixed {@code sync} glue does on a failed
     * {@code level.getEntity(id) != null} check) must leave {@code hasEntity} false, so the record is
     * STILL eligible for a first-spawn attempt on the very next sync call -- never permanently blocked,
     * and never silently promoted to "confirmed real" without a real entity behind it.
     */
    @Test
    void aFailedSpawnLeavesHasEntityFalseAndIsRetriedNextSync() {
        UUID id = UUID.randomUUID();
        KingRecord record = new KingRecord();
        record.add(legacyEntry(id, Faction.VILLAGER)); // genuine legacy record, anchor chunk loaded

        // Sync tick 1 (modeled): a spawn was attempted for this id, but insertion was never confirmed, so
        // (per the fixed glue) markHasEntity is never called -- hasEntity stays exactly as it was.
        assertFalse(record.kings().get(0).hasEntity(), "a failed/unconfirmed insertion must never be promoted to hasEntity=true");

        // Sync tick 2: entity still not loaded (the failed spawn landed no real entity), anchor still loaded.
        var missing = KingEntitySync.missingEntities(record.kings(), Set.<UUID>of());
        var toSpawn = KingEntitySync.needingFirstSpawn(missing, e -> true);
        assertEquals(1, toSpawn.size(), "a failed insertion must not permanently block retry");
        assertEquals(id, toSpawn.get(0).id());
    }

    /**
     * (c) An entity observed loaded at EXACTLY its already-stored anchor position (the legacy-migration
     * case the reviewer called out: a record whose migration-spawned entity happens to land back at its
     * own stored anchor) must still be marked {@code hasEntity} true -- the fixed {@code sync} glue calls
     * {@code updatePosition} UNCONDITIONALLY now, not only when the position actually differs.
     */
    @Test
    void observingAnEntityAtUnchangedCoordinatesStillMarksHasEntityTrue() {
        UUID id = UUID.randomUUID();
        KingRecord record = new KingRecord();
        record.add(legacyEntry(id, Faction.ZOMBIE)); // hasEntity=false, stored at (0, 64, 0)

        record.updatePosition(id, 0, 64, 0); // same coordinates -- must NOT be treated as a no-op

        var updated = record.kings().get(0);
        assertTrue(updated.hasEntity(), "observing a real loaded entity for this id must confirm "
            + "hasEntity even when its position happens to exactly match the stored anchor -- otherwise "
            + "this record would be spawned a SECOND time the very next sync tick");
        assertEquals(0, updated.x());
        assertEquals(64, updated.y());
        assertEquals(0, updated.z());
    }
}
