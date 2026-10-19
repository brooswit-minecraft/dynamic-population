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
        return new KingRecord.Entry(id, 0, 64, 0, faction, true);
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

    // --- [P1 review fix] isConfirmedDeathRemoval: only a genuine, irreversible death removal counts ---

    @Test
    void killedIsAConfirmedDeathRemoval() {
        assertTrue(KingDeathRetirement.isConfirmedDeathRemoval("KILLED"));
    }

    @Test
    void administrativeDiscardIsNotAConfirmedDeathRemoval() {
        // KingManager.removeNearest's entity.discard() -- must never be treated as a death.
        assertFalse(KingDeathRetirement.isConfirmedDeathRemoval("DISCARDED"));
    }

    @Test
    void ordinaryChunkUnloadIsNotAConfirmedDeathRemoval() {
        // A King merely going out of loaded range must never be retired/announced as dead.
        assertFalse(KingDeathRetirement.isConfirmedDeathRemoval("UNLOADED_TO_CHUNK"));
        assertFalse(KingDeathRetirement.isConfirmedDeathRemoval("UNLOADED_WITH_PLAYER"));
    }

    @Test
    void changedDimensionIsNotAConfirmedDeathRemoval() {
        assertFalse(KingDeathRetirement.isConfirmedDeathRemoval("CHANGED_DIMENSION"));
    }

    @Test
    void nullRemovalReasonIsNotAConfirmedDeathRemoval() {
        // The entity is still alive/loaded (getRemovalReason() only non-null once actually removed).
        assertFalse(KingDeathRetirement.isConfirmedDeathRemoval(null));
    }

    @Test
    void aPreventedDeathNeverProducesAConfirmedRemovalReasonAtAll() {
        // Models the review's exact concern: a death cancelled AFTER this mod's own callback ran. Since a
        // cancelled LivingDeathEvent never reaches LivingEntity#tickDeath's removal branch, the entity's
        // getRemovalReason() stays permanently null for that (prevented) death -- there is no reason value
        // a prevented death could ever produce that this classifier would mistake for KILLED.
        for (String notAConfirmedRemoval : new String[] {null, "DISCARDED", "UNLOADED_TO_CHUNK",
                "UNLOADED_WITH_PLAYER", "CHANGED_DIMENSION"}) {
            assertFalse(KingDeathRetirement.isConfirmedDeathRemoval(notAConfirmedRemoval),
                "must not treat " + notAConfirmedRemoval + " as a confirmed death");
        }
    }
}
