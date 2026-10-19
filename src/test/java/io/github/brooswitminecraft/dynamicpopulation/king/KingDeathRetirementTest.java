package io.github.brooswitminecraft.dynamicpopulation.king;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import org.junit.jupiter.api.Test;

/** Pure genuine-King-death / dedupe coverage (MINECRAFT-414's death-announcement scope item). */
class KingDeathRetirementTest {
    private static KingRecord.Entry entry(UUID id, Faction faction) {
        return new KingRecord.Entry(id, 0, 64, 0, faction);
    }

    @Test
    void stillRegisteredIdIsAGenuineDeath() {
        UUID id = UUID.randomUUID();
        assertTrue(KingDeathRetirement.isGenuineKingDeath(List.of(entry(id, Faction.ZOMBIE)), id));
    }

    @Test
    void alreadyRemovedIdIsNotAGenuineDeath() {
        // Models a second die() callback for the same entity: the first callback already removed it.
        UUID id = UUID.randomUUID();
        assertFalse(KingDeathRetirement.isGenuineKingDeath(List.of(), id));
    }

    @Test
    void administrativelyDiscardedIdIsNotAGenuineDeath() {
        // Models KingManager.removeNearest: the record is removed before entity.discard() runs, so even
        // if that discard somehow routed through a death event, this id would already be unregistered.
        UUID removed = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var kings = List.of(entry(other, Faction.SKELETON));
        assertFalse(KingDeathRetirement.isGenuineKingDeath(kings, removed));
    }

    @Test
    void onlyTheMatchingIdCounts() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var kings = List.of(entry(a, Faction.VILLAGER), entry(b, Faction.ZOMBIE));
        assertTrue(KingDeathRetirement.isGenuineKingDeath(kings, a));
        assertTrue(KingDeathRetirement.isGenuineKingDeath(kings, b));
        assertFalse(KingDeathRetirement.isGenuineKingDeath(kings, UUID.randomUUID()));
    }
}
