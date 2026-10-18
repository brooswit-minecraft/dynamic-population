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
}
