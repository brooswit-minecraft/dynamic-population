package io.github.brooswitminecraft.dynamicpopulation.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-417: {@link PopulationHudSnapshot}'s pure staleness check (AC3) and the equals-based
 * change-detection {@code PopulationHudTracker.sendIfChanged} relies on to decide when a refresh is actually
 * due -- cell/layer crossing, dimension change, and a stationary density change must each make two
 * snapshots unequal; nothing changing must not.
 */
class PopulationHudSnapshotTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation NETHER = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

    private static Map<Faction, PopulationHudPayload.Reading> readings(int villager, int zombie, int skeleton) {
        Map<Faction, PopulationHudPayload.Reading> map = new EnumMap<>(Faction.class);
        map.put(Faction.VILLAGER, new PopulationHudPayload.Reading(true, villager));
        map.put(Faction.ZOMBIE, new PopulationHudPayload.Reading(true, zombie));
        map.put(Faction.SKELETON, new PopulationHudPayload.Reading(true, skeleton));
        return map;
    }

    private static PopulationHudPayload payload(ResourceLocation dim, int cellX, int cellLayer, int cellZ,
            Map<Faction, PopulationHudPayload.Reading> readings) {
        return new PopulationHudPayload(dim, true, 64, cellX, cellLayer, cellZ, cellX * 64, 70, cellZ * 64, readings);
    }

    @Test
    void identicalReadingsAtTheSameCellAreEqual() {
        PopulationHudSnapshot a = PopulationHudSnapshot.from(payload(OVERWORLD, 1, 2, 3, readings(10, 20, 30)));
        PopulationHudSnapshot b = PopulationHudSnapshot.from(payload(OVERWORLD, 1, 2, 3, readings(10, 20, 30)));

        assertEquals(a, b, "no change at all must not look like a change to the tracker");
    }

    @Test
    void cellCrossingChangesTheSnapshot() {
        PopulationHudSnapshot before = PopulationHudSnapshot.from(payload(OVERWORLD, 1, 2, 3, readings(10, 20, 30)));
        PopulationHudSnapshot afterXCrossing = PopulationHudSnapshot.from(payload(OVERWORLD, 2, 2, 3, readings(10, 20, 30)));

        assertNotEquals(before, afterXCrossing);
    }

    @Test
    void verticalLayerCrossingChangesTheSnapshot() {
        PopulationHudSnapshot before = PopulationHudSnapshot.from(payload(OVERWORLD, 1, 2, 3, readings(10, 20, 30)));
        PopulationHudSnapshot afterLayerCrossing = PopulationHudSnapshot.from(payload(OVERWORLD, 1, 3, 3, readings(10, 20, 30)));

        assertNotEquals(before, afterLayerCrossing);
    }

    @Test
    void dimensionChangeChangesTheSnapshotEvenAtTheSameCellCoordinates() {
        PopulationHudSnapshot before = PopulationHudSnapshot.from(payload(OVERWORLD, 1, 2, 3, readings(10, 20, 30)));
        PopulationHudSnapshot afterDimensionChange = PopulationHudSnapshot.from(payload(NETHER, 1, 2, 3, readings(10, 20, 30)));

        assertNotEquals(before, afterDimensionChange);
    }

    @Test
    void densityChangeWhileStationaryChangesTheSnapshot() {
        PopulationHudSnapshot before = PopulationHudSnapshot.from(payload(OVERWORLD, 1, 2, 3, readings(10, 20, 30)));
        PopulationHudSnapshot afterDensityRises = PopulationHudSnapshot.from(payload(OVERWORLD, 1, 2, 3, readings(10, 999, 30)));

        assertNotEquals(before, afterDensityRises, "same cell, same dimension, but one faction's density moved");
    }

    @Test
    void readableFlippingIsItselfAChangeEvenIfTheNumberStaysZero() {
        Map<Faction, PopulationHudPayload.Reading> unknown = new EnumMap<>(Faction.class);
        unknown.put(Faction.VILLAGER, new PopulationHudPayload.Reading(false, 0));
        unknown.put(Faction.ZOMBIE, new PopulationHudPayload.Reading(true, 0));
        unknown.put(Faction.SKELETON, new PopulationHudPayload.Reading(true, 0));
        Map<Faction, PopulationHudPayload.Reading> nowKnownZero = new EnumMap<>(Faction.class);
        nowKnownZero.put(Faction.VILLAGER, new PopulationHudPayload.Reading(true, 0));
        nowKnownZero.put(Faction.ZOMBIE, new PopulationHudPayload.Reading(true, 0));
        nowKnownZero.put(Faction.SKELETON, new PopulationHudPayload.Reading(true, 0));

        PopulationHudSnapshot before = PopulationHudSnapshot.from(payload(OVERWORLD, 1, 2, 3, unknown));
        PopulationHudSnapshot after = PopulationHudSnapshot.from(payload(OVERWORLD, 1, 2, 3, nowKnownZero));

        assertNotEquals(before, after);
    }

    @Test
    void freshPayloadForTheLiveDimensionIsNotStale() {
        PopulationHudPayload payload = payload(OVERWORLD, 1, 2, 3, readings(10, 20, 30));

        assertFalse(PopulationHudSnapshot.isStale(payload, OVERWORLD));
    }

    @Test
    void payloadComputedForADimensionThePlayerHasSinceLeftIsStale() {
        // Server computed this for the Overworld, but by the time it's handled the player has already
        // moved to the Nether (e.g. travelled through a portal while the packet was in flight).
        PopulationHudPayload payload = payload(OVERWORLD, 1, 2, 3, readings(10, 20, 30));

        assertTrue(PopulationHudSnapshot.isStale(payload, NETHER));
    }

    @Test
    void noLivePlayerAtAllIsAlwaysStale() {
        PopulationHudPayload payload = payload(OVERWORLD, 1, 2, 3, readings(10, 20, 30));

        assertTrue(PopulationHudSnapshot.isStale(payload, null));
    }
}
