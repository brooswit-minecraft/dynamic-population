package io.github.brooswitminecraft.dynamicpopulation.king;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import org.junit.jupiter.api.Test;

/** No-duplicate-entity-on-reload coverage (ticket's "Tests to write" list), as a pure function. */
class KingEntitySyncTest {
    private static KingRecord.Entry entry(UUID id, Faction faction) {
        return new KingRecord.Entry(id, 0, 64, 0, faction);
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

    // --- MINECRAFT-414 bug fix: KingRecord.updatePosition keeps the anchor current, not frozen at founding ---

    @Test
    void updatePositionReplacesStoredCoordinatesKeepingIdAndFaction() {
        UUID id = UUID.randomUUID();
        KingRecord record = new KingRecord();
        record.add(new KingRecord.Entry(id, 0, 64, 0, Faction.ZOMBIE)); // founded at anchor A
        record.updatePosition(id, 500, 70, 500); // sync observed it loaded, wandered to B
        KingRecord.Entry updated = record.kings().get(0);
        assertEquals(500, updated.x());
        assertEquals(70, updated.y());
        assertEquals(500, updated.z());
        assertEquals(id, updated.id(), "updatePosition must never change the King's identity");
        assertEquals(Faction.ZOMBIE, updated.faction(), "updatePosition must never change the King's faction");
    }

    @Test
    void updatePositionLeavesOtherEntriesUntouched() {
        UUID moved = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        KingRecord record = new KingRecord();
        record.add(new KingRecord.Entry(moved, 0, 64, 0, Faction.VILLAGER));
        record.add(new KingRecord.Entry(other, 10, 64, 10, Faction.SKELETON));
        record.updatePosition(moved, 999, 64, 999);
        var others = record.kings().stream().filter(e -> e.id().equals(other)).findFirst().orElseThrow();
        assertEquals(10, others.x());
        assertEquals(10, others.z());
    }

    @Test
    void updatePositionOnAnUnknownIdIsANoOp() {
        KingRecord record = new KingRecord();
        UUID present = UUID.randomUUID();
        record.add(new KingRecord.Entry(present, 1, 64, 1, Faction.VILLAGER));
        record.updatePosition(UUID.randomUUID(), 999, 64, 999); // not in the record at all
        assertEquals(1, record.kings().size());
        assertEquals(1, record.kings().get(0).x());
    }

    /**
     * The exact duplicate-entity scenario the coordinator-confirmed bug produced: a King founded at anchor
     * A wanders to B; sync observes it loaded there and updates the anchor (as {@code sync} itself now
     * does, via {@link KingRecord#updatePosition}). Later, B's chunk unloads -- the entity is no longer
     * loaded, so {@link #missingEntities} correctly reports it missing -- but the record's OWN stored
     * position is now B, not the stale founding spot A. A caller that checks hasChunk on the record's
     * position (exactly what {@code KingEntitySync.sync} does) therefore checks B, which correctly reads
     * as unloaded, and skips the respawn -- no duplicate. Before this fix, the anchor would have stayed
     * frozen at A, and if A's own chunk happened to still be loaded (e.g. inside spawn chunks), the exact
     * same missing-entity signal would have wrongly triggered a second entity spawned AT A under the same
     * uuid -- a real duplicate, not a false positive.
     */
    @Test
    void aMissingCheckAfterWanderingReflectsTheNewPositionNotTheStaleFoundingSpot() {
        UUID id = UUID.randomUUID();
        KingRecord record = new KingRecord();
        record.add(new KingRecord.Entry(id, 0, 64, 0, Faction.ZOMBIE)); // founded at A = (0, 64, 0)
        record.updatePosition(id, 500, 70, 500); // observed loaded at B = (500, 70, 500); anchor refreshed

        var missing = KingEntitySync.missingEntities(record.kings(), Set.of()); // now not loaded (B's chunk unloaded)
        assertEquals(1, missing.size());
        assertEquals(500, missing.get(0).x(), "a respawn decision must consult the King's LAST KNOWN "
            + "(post-wander) position, never its stale founding position -- consulting the stale position "
            + "is exactly the duplicate-entity bug this fix closes");
        assertEquals(500, missing.get(0).z());
    }
}
