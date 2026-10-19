package io.github.brooswitminecraft.dynamicpopulation.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-417 review fix: {@link PopulationHudClientState#shouldClearForDimensionChange}, the pure
 * decision that replaced the broken {@code PlayerEvent.PlayerChangedDimensionEvent}-based listener (a
 * SERVER-side event carrying a {@code ServerPlayer} that is never identity-equal to the client's own
 * {@code LocalPlayer}, so the old listener silently never fired for the client actually viewing the HUD).
 * This pure method needs no {@code Minecraft}/level bootstrap to exercise -- it is a plain
 * {@link ResourceLocation} comparison.
 *
 * <p>Round-2 review fix: the method grew a third parameter, the cached snapshot's own dimension, to close
 * a race between packet arrival ({@code PopulationHudClientState#onReceived}) and the next client tick --
 * see the class doc on {@link PopulationHudClientState#onClientTick} for the full story. The cases below
 * cover both event orderings the review called out: tick-then-packet (the cache has NOT yet caught up, so
 * clearing is still correct) and packet-then-tick (the cache HAS already caught up, so clearing would
 * wrongly discard good fresh data).
 */
class PopulationHudClientStateTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation NETHER = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

    @Test
    void genuineDimensionChangeMustClearWhenCacheHasNotCaughtUp() {
        // Ordering (a) from the review: tick fires before the new-dimension packet arrives. The cache is
        // still whatever it was in the OLD dimension (or null) -- either way, not already NETHER -- so this
        // must clear.
        assertTrue(PopulationHudClientState.shouldClearForDimensionChange(OVERWORLD, NETHER, OVERWORLD));
        assertTrue(PopulationHudClientState.shouldClearForDimensionChange(OVERWORLD, NETHER, null));
    }

    @Test
    void genuineDimensionChangeMustNotClearWhenCacheAlreadyCaughtUp() {
        // Ordering (b) from the review: the new-dimension packet already arrived via onReceived and updated
        // the cache to NETHER before this tick ran. The stale lastSeenDimension (still OVERWORLD) must not
        // cause the fresh cache to be thrown away.
        assertFalse(PopulationHudClientState.shouldClearForDimensionChange(OVERWORLD, NETHER, NETHER));
    }

    @Test
    void stayingInTheSameDimensionMustNotClear() {
        assertFalse(PopulationHudClientState.shouldClearForDimensionChange(OVERWORLD, OVERWORLD, OVERWORLD));
        assertFalse(PopulationHudClientState.shouldClearForDimensionChange(OVERWORLD, OVERWORLD, null));
    }

    @Test
    void noPreviouslyKnownDimensionMustNotClear() {
        // Nothing observed yet (e.g. the very first tick after logging in) -- login/logout already clear
        // the cache separately; this check must not also fire here.
        assertFalse(PopulationHudClientState.shouldClearForDimensionChange(null, OVERWORLD, null));
        assertFalse(PopulationHudClientState.shouldClearForDimensionChange(null, OVERWORLD, OVERWORLD));
    }

    @Test
    void noCurrentPlayerMustNotClearHere() {
        // No live player right now (e.g. mid-disconnect, or a transient null during a loading screen) --
        // disconnect handling clears the cache on its own; this check must not also fire for a merely-
        // absent current dimension, regardless of what is cached.
        assertFalse(PopulationHudClientState.shouldClearForDimensionChange(OVERWORLD, null, OVERWORLD));
        assertFalse(PopulationHudClientState.shouldClearForDimensionChange(OVERWORLD, null, null));
    }
}
