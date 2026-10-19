package io.github.brooswitminecraft.dynamicpopulation.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;

import io.github.brooswitminecraft.dynamicpopulation.faction.Faction;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-417: {@link PopulationHudPayload} encode/decode round-trip, including the per-faction
 * identity/no-clobbering requirement and the readable-vs-unknown distinction (ticket AC1/AC2).
 */
class PopulationHudPayloadTest {
    private static PopulationHudPayload roundTrip(PopulationHudPayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PopulationHudPayload.STREAM_CODEC.encode(buf, payload);
        return PopulationHudPayload.STREAM_CODEC.decode(buf);
    }

    private static Map<Faction, PopulationHudPayload.Reading> readings(
            boolean villagerReadable, int villagerPpt, boolean zombieReadable, int zombiePpt,
            boolean skeletonReadable, int skeletonPpt) {
        Map<Faction, PopulationHudPayload.Reading> map = new EnumMap<>(Faction.class);
        map.put(Faction.VILLAGER, new PopulationHudPayload.Reading(villagerReadable, villagerPpt));
        map.put(Faction.ZOMBIE, new PopulationHudPayload.Reading(zombieReadable, zombiePpt));
        map.put(Faction.SKELETON, new PopulationHudPayload.Reading(skeletonReadable, skeletonPpt));
        return map;
    }

    @Test
    void roundTripPreservesEveryField() {
        PopulationHudPayload payload = new PopulationHudPayload(
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), true, 64, -3, 2, 5,
            -191, 70, 320, readings(true, 120, true, 600, false, 0));

        PopulationHudPayload decoded = roundTrip(payload);

        assertEquals(payload, decoded);
    }

    @Test
    void eachFactionKeepsItsOwnReadingNeverClobberingAnother() {
        // Three distinct values, one per faction -- if decode ever mis-ordered or overwrote by a fixed
        // slot instead of the faction tag actually on the wire, two of these three would collide.
        PopulationHudPayload payload = new PopulationHudPayload(
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), true, 64, 0, 0, 0, 0, 64, 0,
            readings(true, 111, true, 222, true, 333));

        PopulationHudPayload decoded = roundTrip(payload);

        assertEquals(111, decoded.readings().get(Faction.VILLAGER).densityPpt());
        assertEquals(222, decoded.readings().get(Faction.ZOMBIE).densityPpt());
        assertEquals(333, decoded.readings().get(Faction.SKELETON).densityPpt());
    }

    @Test
    void decodingDoesNotDependOnEncodedOrder() {
        // Build the readings map so iteration happens to visit Skeleton/Zombie/Villager -- order on the
        // wire must not matter, since each entry is tagged with its own faction ordinal.
        Map<Faction, PopulationHudPayload.Reading> out = new EnumMap<>(Faction.class);
        out.put(Faction.SKELETON, new PopulationHudPayload.Reading(true, 1));
        out.put(Faction.ZOMBIE, new PopulationHudPayload.Reading(true, 2));
        out.put(Faction.VILLAGER, new PopulationHudPayload.Reading(true, 3));
        PopulationHudPayload payload = new PopulationHudPayload(
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), true, 64, 0, 0, 0, 0, 64, 0, out);

        PopulationHudPayload decoded = roundTrip(payload);

        assertEquals(1, decoded.readings().get(Faction.SKELETON).densityPpt());
        assertEquals(2, decoded.readings().get(Faction.ZOMBIE).densityPpt());
        assertEquals(3, decoded.readings().get(Faction.VILLAGER).densityPpt());
    }

    @Test
    void unreadableIsDistinctFromARealZero() {
        PopulationHudPayload payload = new PopulationHudPayload(
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), true, 64, 0, 0, 0, 0, 64, 0,
            readings(false, 0, true, 0, true, 0));

        PopulationHudPayload decoded = roundTrip(payload);

        assertFalse(decoded.readings().get(Faction.VILLAGER).readable(), "unloaded/corrupt must stay unreadable");
        assertTrue(decoded.readings().get(Faction.ZOMBIE).readable(), "a genuine loaded zero must stay readable");
        assertEquals(0, decoded.readings().get(Faction.ZOMBIE).densityPpt());
    }

    @Test
    void notSimulatedFlagRoundTrips() {
        PopulationHudPayload payload = new PopulationHudPayload(
            ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"), false, 64, 0, 0, 0, 0, 64, 0,
            readings(false, 0, false, 0, false, 0));

        PopulationHudPayload decoded = roundTrip(payload);

        assertFalse(decoded.simulated());
        assertEquals(ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"), decoded.dimension());
    }

    @Test
    void togglePayloadRoundTrips() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PopulationHudTogglePayload.STREAM_CODEC.encode(buf, new PopulationHudTogglePayload(true));
        assertTrue(PopulationHudTogglePayload.STREAM_CODEC.decode(buf).enabled());

        FriendlyByteBuf buf2 = new FriendlyByteBuf(Unpooled.buffer());
        PopulationHudTogglePayload.STREAM_CODEC.encode(buf2, new PopulationHudTogglePayload(false));
        assertFalse(PopulationHudTogglePayload.STREAM_CODEC.decode(buf2).enabled());
    }
}
