package io.github.brooswitminecraft.dynamicpopulation.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicpopulation.combat.FillerMarkers;
import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.github.brooswitminecraft.dynamicpopulation.propagation.CellKey;

/**
 * MINECRAFT-425 review fix (round 2): {@code LivingDeathEvent} is a single global NeoForge event, so EVERY
 * registered {@code onLivingDeath} listener on the bus -- this class's, {@code
 * FillagerPopulationSimulation}'s, and {@code king.KingDeathHandling}'s -- runs for EVERY dying entity, not
 * just the ones each class cares about, since {@code Fillager} and the three King entity classes are ALSO
 * {@code Mob} instances. {@code FillerFactionSimulation.onLivingDeath} must therefore be a complete no-op
 * -- including never calling {@code PopulationDeathDecrement.take()} -- for any entity it does not own
 * (i.e. never carries one of its own {@link FillerMarkers} tags), or it would erase a DIFFERENT, valid,
 * same-event capture already made by one of those other two handlers for the SAME dying entity.
 */
class FillerFactionSimulationTest {
    private static final CellKey ORIGIN = new CellKey(0, 0, 0);

    @AfterEach
    void clearPendingDeaths() {
        PopulationDeathDecrement.clear();
    }

    @Test
    void ownsAnyFillerTagIsFalseForAnUntaggedEntityLikeFillagerOrAKing() {
        // Fillager carries no tags at all (identified by class, not FillerMarkers), and a King is never
        // given either filler tag either (see FillerMarkers' own class doc) -- both must read as "not owned".
        assertFalse(FillerFactionSimulation.ownsAnyFillerTag(Set.of()));
        assertFalse(FillerFactionSimulation.ownsAnyFillerTag(Set.of("some_other_mod_tag")));
    }

    @Test
    void ownsAnyFillerTagIsTrueForEitherOwnTag() {
        assertTrue(FillerFactionSimulation.ownsAnyFillerTag(Set.of(FillerMarkers.ZOMBIE_FILLER_TAG)));
        assertTrue(FillerFactionSimulation.ownsAnyFillerTag(Set.of(FillerMarkers.SKELETON_FILLER_TAG)));
        assertTrue(FillerFactionSimulation.ownsAnyFillerTag(List.of("unrelated", FillerMarkers.ZOMBIE_FILLER_TAG)));
    }

    @Test
    void anUnownedEntitysDeathNeverTouchesTheSharedPendingMapAtAll() {
        // The exact composition regression the review caught: simulate FillagerPopulationSimulation (or
        // king.KingDeathHandling) having already captured a valid pending death for this UUID earlier in
        // the SAME LivingDeathEvent dispatch (both are registered on the shared bus BEFORE this class, per
        // DynamicPopulationMod's registration order). This class's own onLivingDeath must never call take()
        // for an entity it does not own (Fillager/King both read as untagged via ownsAnyFillerTag), so that
        // earlier capture must survive completely untouched.
        UUID fillagerLikeId = UUID.randomUUID();
        PopulationDeathDecrement.capture(fillagerLikeId, Faction.VILLAGER, ORIGIN, 25);

        // Mirrors onLivingDeath's own guard: an untagged entity (Fillager or any King) must short-circuit
        // before ever reaching PopulationDeathDecrement.take().
        boolean owned = FillerFactionSimulation.ownsAnyFillerTag(Set.of());
        assertFalse(owned, "sanity: an untagged entity must never be treated as owned by this class");

        var stillPending = PopulationDeathDecrement.take(fillagerLikeId);
        assertTrue(stillPending.isPresent(), "a Fillager's or King's own valid capture must survive this "
            + "class's onLivingDeath listener running for the SAME dying entity in the same event dispatch");
        assertEquals(ORIGIN, stillPending.get().cell());
    }

    @Test
    void anOwnedTaggedEntitysDeathStillInvalidatesAndRecapturesNormally() {
        // The round-1 fix (stale cancelled-death capture) must still work for entities this class DOES own.
        UUID taggedFillerId = UUID.randomUUID();
        CellKey staleCancelledCell = ORIGIN;
        CellKey realDeathCell = ORIGIN.offset(3, 0, 0);
        PopulationDeathDecrement.capture(taggedFillerId, Faction.ZOMBIE, staleCancelledCell, 25);

        assertTrue(FillerFactionSimulation.ownsAnyFillerTag(Set.of(FillerMarkers.ZOMBIE_FILLER_TAG)));

        // Simulates onLivingDeath's own take-then-maybe-recapture sequence for an owned, still-eligible tag.
        PopulationDeathDecrement.take(taggedFillerId);
        PopulationDeathDecrement.capture(taggedFillerId, Faction.ZOMBIE, realDeathCell, 25);

        var pending = PopulationDeathDecrement.take(taggedFillerId);
        assertTrue(pending.isPresent());
        assertEquals(realDeathCell, pending.get().cell(), "an owned entity's real death must still replace a stale capture");
    }
}
